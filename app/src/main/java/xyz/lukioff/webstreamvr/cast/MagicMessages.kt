package xyz.lukioff.webstreamvr.cast

import java.nio.ByteBuffer

/**
 * MagicIsland casting messages (com.oculus.magic.networking.SerializableMessage).
 * Everything is big endian; strings are u32 length + UTF-8 bytes padded to 4.
 */
object MagicMessages {
    const val HANDSHAKE = 1
    const val STOP_STREAMING = 2
    const val PING = 3
    const val PONG = 4
    const val CONNECTION_INFO_RESPONSE = 6
    const val START_CASTING = 7
    const val STOP_CASTING = 8
    const val UPDATE_CASTING_CONFIG = 9
    const val SET_PROPERTY = 10
    const val VIDEO_SEGMENT = 100
    const val RESYNC_FRAME = 101
    const val AUDIO_SEGMENT = 102
    const val LAYER_CONFIGURATION = 300
    const val ACTIVATE_LAYER = 301
    const val DESTROY_LAYER = 302
    const val APP_STATE_CHANGE = 501
    const val SET_AUDIO_STATE = 503

    const val PROPERTY_BITRATE = "debug.oculus.magic.bitrate"
    const val PROPERTY_MAX_WIDTH = "debug.oculus.magic.maxWidth"
    const val PROPERTY_MAX_HEIGHT = "debug.oculus.magic.maxHeight"

    fun ping(timestamp: Int) = ByteBuffer.allocate(8).putInt(PING).putInt(timestamp).array()

    fun pong(timestamp: Int): ByteArray =
        ByteBuffer.allocate(16).putInt(PONG).putInt(timestamp).putString("").putString("").array()

    fun startCasting(config: CastConfig, sessionUuid: String): ByteArray {
        val buffer = ByteBuffer.allocate(28 + stringSize(sessionUuid))
        buffer.putInt(START_CASTING)
        putConfig(buffer, config)
        buffer.putString(sessionUuid)
        return buffer.array()
    }

    fun updateCastingConfig(config: CastConfig): ByteArray {
        val buffer = ByteBuffer.allocate(28)
        buffer.putInt(UPDATE_CASTING_CONFIG)
        putConfig(buffer, config)
        return buffer.array()
    }

    fun stopCasting() = ByteBuffer.allocate(4).putInt(STOP_CASTING).array()

    fun setProperty(key: String, value: String): ByteArray =
        ByteBuffer.allocate(4 + stringSize(key) + stringSize(value))
            .putInt(SET_PROPERTY).putString(key).putString(value).array()

    /**
     * Fixes the encoder bitrate. The service applies it on its congestion-control tick,
     * which only runs while we answer pings. 0 removes the override (adaptive bitrate).
     */
    fun bitrate(bitsPerSecond: Int) =
        setProperty(PROPERTY_BITRATE, if (bitsPerSecond > 0) bitsPerSecond.toString() else "")

    /**
     * Encoder size caps. The service clamps width and height separately to per-device
     * defaults (2560×1440 on Quest 3), which would squash e.g. a portrait frame, so we
     * set them to the requested size. Read when the encoder is (re)created.
     */
    fun encoderLimits(width: Int, height: Int) = listOf(
        setProperty(PROPERTY_MAX_WIDTH, width.toString()),
        setProperty(PROPERTY_MAX_HEIGHT, height.toString()),
    )

    fun setAudioState(enabled: Boolean) =
        ByteBuffer.allocate(8).putInt(SET_AUDIO_STATE).putInt(if (enabled) 1 else 0).array()

    fun activateLayer(layerId: Int) = ByteBuffer.allocate(8).putInt(ACTIVATE_LAYER).putInt(layerId).array()

    fun resyncFrame(layerId: Int) = ByteBuffer.allocate(8).putInt(RESYNC_FRAME).putInt(layerId).array()

    private fun putConfig(buffer: ByteBuffer, config: CastConfig) {
        buffer.putInt(config.width)
        buffer.putInt(config.height)
        buffer.putFloat(config.fovAdjustment)
        buffer.putInt(config.eye)
        buffer.putInt(config.frameRate)
        buffer.putInt(if (config.stabilization) 1 else 0)
    }

    fun stringSize(value: String): Int {
        val n = value.toByteArray().size
        return 4 + n + ((4 - (n and 3)) and 3)
    }

    private fun ByteBuffer.putString(value: String): ByteBuffer {
        val bytes = value.toByteArray()
        putInt(bytes.size)
        put(bytes)
        repeat((4 - (bytes.size and 3)) and 3) { put(0) }
        return this
    }

}

private fun ByteBuffer.getString(): String {
    val n = int
    val bytes = ByteArray(n)
    get(bytes)
    position(position() + ((4 - (n and 3)) and 3))
    return String(bytes)
}

/** Parameters of StartCasting/UpdateCastingConfig. */
data class CastConfig(
    val width: Int,
    val height: Int,
    /** Extra field of view in degrees on top of the default crop; 0 keeps the default. */
    val fovAdjustment: Float,
    /** 0 = left, 1 = right. */
    val eye: Int,
    val frameRate: Int,
    /** Rotation stabilization preset LOW (slerp 50, dead zone 3/3, crop 15 degrees). */
    val stabilization: Boolean,
)

/** Messages the headset sends to us. */
sealed interface IncomingMagic {
    data class Handshake(val version: Int, val transport: String) : IncomingMagic
    data class Ping(val timestamp: Int) : IncomingMagic
    class VideoSegment(val layerId: Int, val gopIndex: Int, val canonicalIndex: Int, val data: ByteArray, val offset: Int) : IncomingMagic
    class AudioSegment(val layerId: Int, val timestampMs: Long, val data: ByteArray, val offset: Int) : IncomingMagic
    data class LayerConfiguration(
        val layerId: Int,
        val width: Int,
        val height: Int,
        val layerName: String,
        val packageName: String,
    ) : IncomingMagic
    data class DestroyLayer(val layerId: Int) : IncomingMagic
    data object StopCasting : IncomingMagic
    data class Other(val type: Int, val size: Int) : IncomingMagic

    companion object {
        fun parse(bytes: ByteArray): IncomingMagic {
            val buffer = ByteBuffer.wrap(bytes)
            return when (val type = buffer.int) {
                MagicMessages.HANDSHAKE -> {
                    val version = buffer.int
                    Handshake(version, buffer.getString())
                }
                MagicMessages.PING -> Ping(buffer.int)
                MagicMessages.VIDEO_SEGMENT -> VideoSegment(buffer.int, buffer.int, buffer.int, bytes, 16)
                MagicMessages.AUDIO_SEGMENT -> AudioSegment(buffer.int, buffer.long, bytes, 16)
                MagicMessages.LAYER_CONFIGURATION -> {
                    val layerId = buffer.int
                    val width = buffer.int
                    val height = buffer.int
                    buffer.position(buffer.position() + 16) // posX, posY, depth, layerType
                    buffer.getString() // application name
                    val layerName = buffer.getString()
                    buffer.getString() // device serial
                    LayerConfiguration(layerId, width, height, layerName, buffer.getString())
                }
                MagicMessages.DESTROY_LAYER -> DestroyLayer(buffer.int)
                MagicMessages.STOP_CASTING -> StopCasting
                else -> Other(type, bytes.size)
            }
        }
    }
}
