package xyz.lukioff.webstreamvr

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import xyz.lukioff.webstreamvr.cast.CastConfig
import xyz.lukioff.webstreamvr.cast.HeadsetLink
import kotlin.math.sqrt

/** Frame shape. The headset fits its eye view into whatever surface size we request. */
enum class AspectRatio(val width: Int, val height: Int, val label: String) {
    WIDE(16, 9, "16:9"),
    CLASSIC(4, 3, "4:3"),
    SQUARE(1, 1, "1:1"),
    PORTRAIT(9, 16, "9:16"),
    /** The casting service's own default eye buffer, 1440×1584. */
    HEADSET(10, 11, "Как в шлеме"),
}

data class StreamSettings(
    val aspect: AspectRatio = AspectRatio.WIDE,
    /** Short side of the frame in pixels, see [QUALITIES]. */
    val quality: Int = 1080,
    val frameRate: Int = 60,
    /** Extra field of view in degrees; 0 is the stock crop. */
    val fovAdjustment: Int = 0,
    /** 0 = left eye, 1 = right eye. */
    val eye: Int = 1,
    val stabilization: Boolean = false,
    /** 0 lets the casting service pick the bitrate itself. */
    val bitrateMbps: Int = 8,
    val audio: Boolean = true,
    val httpPort: Int = DEFAULT_PORT,
) {
    /** Encoder size for this device; keeps the aspect ratio when it has to shrink. */
    val frameSize: Pair<Int, Int> get() = frameSize(aspect, quality, maxEncoderPixels())

    fun toLinkSettings(): HeadsetLink.Settings {
        val (width, height) = frameSize
        return HeadsetLink.Settings(
            cast = CastConfig(
                width = width,
                height = height,
                fovAdjustment = fovAdjustment.toFloat(),
                eye = eye,
                frameRate = frameRate,
                stabilization = stabilization,
            ),
            bitrate = bitrateMbps * 1_000_000,
            audio = audio,
        )
    }

    companion object {
        const val DEFAULT_PORT = 8080
        val QUALITIES = listOf(720, 1080, 1440)
        val BITRATES_MBPS = listOf(0, 4, 6, 8, 12, 16, 20)
        val FRAME_RATES = listOf(30, 60)
        const val MAX_FOV_ADJUSTMENT = 60

        private const val PREFS = "stream_settings"

        /**
         * Width and height for [aspect] with [shortSide] pixels on the short side, scaled
         * down to [maxPixels] and rounded to multiples of 8 for the encoder.
         */
        fun frameSize(aspect: AspectRatio, shortSide: Int, maxPixels: Int): Pair<Int, Int> {
            var width: Double
            var height: Double
            if (aspect.width >= aspect.height) {
                height = shortSide.toDouble()
                width = height * aspect.width / aspect.height
            } else {
                width = shortSide.toDouble()
                height = width * aspect.height / aspect.width
            }
            if (width * height > maxPixels) {
                val scale = sqrt(maxPixels / (width * height))
                width *= scale
                height *= scale
            }
            return (width.toInt() / 8 * 8) to (height.toInt() / 8 * 8)
        }

        /** Pixel budget of the casting encoder (LocalConfig defaults per device). */
        fun maxEncoderPixels(device: String = Build.DEVICE): Int = when (device) {
            "eureka", "panther" -> 2560 * 1440 // Quest 3, Quest 3S
            else -> 1920 * 1080 // Quest 2, Quest Pro
        }

        fun generateRandomPort(): Int = (1024..65535).random()

        fun load(context: Context): StreamSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val defaults = StreamSettings()
            // Versions before the aspect setting stored one of a few fixed sizes.
            val (legacyAspect, legacyQuality) = when (p.getString("resolution", null)) {
                "HD" -> AspectRatio.WIDE to 720
                "QHD" -> AspectRatio.WIDE to 1440
                "SQUARE" -> AspectRatio.SQUARE to 1440
                else -> defaults.aspect to defaults.quality
            }
            val savedPort = if (p.contains("httpPort")) p.getInt("httpPort", 0).takeIf { it in 1024..65535 } else null
            val httpPort = savedPort ?: generateRandomPort().also { port ->
                p.edit { putInt("httpPort", port) }
            }
            return StreamSettings(
                aspect = AspectRatio.entries.firstOrNull { it.name == p.getString("aspect", null) } ?: legacyAspect,
                quality = p.getInt("quality", legacyQuality).takeIf { it in QUALITIES } ?: defaults.quality,
                frameRate = p.getInt("frameRate", defaults.frameRate),
                fovAdjustment = p.getInt("fovAdjustment", defaults.fovAdjustment),
                eye = p.getInt("eye", defaults.eye),
                stabilization = p.getBoolean("stabilization", defaults.stabilization),
                bitrateMbps = p.getInt("bitrateMbps", defaults.bitrateMbps),
                audio = p.getBoolean("audio", defaults.audio),
                httpPort = httpPort,
            )
        }

        fun save(context: Context, settings: StreamSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                remove("resolution")
                putString("aspect", settings.aspect.name)
                putInt("quality", settings.quality)
                putInt("frameRate", settings.frameRate)
                putInt("fovAdjustment", settings.fovAdjustment)
                putInt("eye", settings.eye)
                putBoolean("stabilization", settings.stabilization)
                putInt("bitrateMbps", settings.bitrateMbps)
                putBoolean("audio", settings.audio)
                putInt("httpPort", settings.httpPort)
            }
        }
    }
}
