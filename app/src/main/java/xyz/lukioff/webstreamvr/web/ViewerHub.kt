package xyz.lukioff.webstreamvr.web

import android.util.Log
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Fans the headset stream out to browser viewers over WebSocket.
 *
 * Binary messages carry a 16-byte big-endian header:
 *   u8 type (1 = codec config, 2 = video frame, 3 = audio), u8 flags (bit 0 = keyframe),
 *   u8 audio channels, u8 reserved, u32 audio sample rate, i64 timestamp in microseconds,
 * followed by Annex-B H.264 or 16-bit big-endian PCM.
 *
 * Each viewer has its own queue. A viewer that falls behind loses its queue and
 * resumes from the next keyframe, which we request from the headset encoder (it
 * produces IDR frames only on request).
 */
class ViewerHub(private val requestKeyframe: () -> Unit) {

    private val viewers = CopyOnWriteArrayList<Viewer>()
    private val nextId = AtomicInteger(1)
    @Volatile private var configFrame: ByteArray? = null

    private val frameCounter = AtomicInteger()
    private val byteCounter = AtomicLong()
    @Volatile var fps = 0f
        private set
    @Volatile var kbps = 0
        private set
    private var statsWindowStart = System.nanoTime()

    val viewerCount get() = viewers.size

    fun onVideo(data: ByteArray, offset: Int, length: Int, timestampUs: Long) {
        val units = H264.scan(data, offset, length)
        if (units.hasParameterSets && !units.hasSlice) {
            // The encoder emits SPS/PPS once per (re)configuration; viewers need them to set up decoding.
            val frame = WebSocket.frame(WebSocket.OPCODE_BINARY, header(TYPE_CONFIG, 0, 0, 0, timestampUs), data, offset, length)
            configFrame = frame
            for (viewer in viewers) {
                viewer.waitingForKeyframe = true
                viewer.send(frame, essential = true)
            }
            return
        }
        if (!units.hasSlice) return
        frameCounter.incrementAndGet()
        byteCounter.addAndGet(length.toLong())
        if (viewers.isEmpty()) return

        val frame = WebSocket.frame(
            WebSocket.OPCODE_BINARY,
            header(TYPE_VIDEO, if (units.isKeyframe) FLAG_KEYFRAME else 0, 0, 0, timestampUs),
            data, offset, length,
        )
        var needKeyframe = false
        for (viewer in viewers) {
            if (viewer.waitingForKeyframe) {
                if (!units.isKeyframe) {
                    needKeyframe = true
                    continue
                }
                viewer.waitingForKeyframe = false
            }
            if (!viewer.send(frame, essential = false)) needKeyframe = true
        }
        if (needKeyframe) requestKeyframe()
    }

    fun onAudio(data: ByteArray, offset: Int, length: Int, timestampUs: Long) {
        if (viewers.isEmpty()) return
        val frame = WebSocket.frame(
            WebSocket.OPCODE_BINARY,
            header(TYPE_AUDIO, 0, AUDIO_CHANNELS, AUDIO_SAMPLE_RATE, timestampUs),
            data, offset, length,
        )
        for (viewer in viewers) if (!viewer.waitingForKeyframe) viewer.send(frame, essential = false)
    }

    fun broadcastText(json: String) {
        if (viewers.isEmpty()) return
        val frame = WebSocket.textFrame(json)
        for (viewer in viewers) viewer.send(frame, essential = true)
    }

    /** Recomputes fps/bitrate; call about once a second. */
    fun updateStats() {
        val now = System.nanoTime()
        val seconds = (now - statsWindowStart) / 1e9f
        if (seconds <= 0f) return
        statsWindowStart = now
        fps = frameCounter.getAndSet(0) / seconds
        kbps = (byteCounter.getAndSet(0) * 8 / 1000 / seconds).toInt()
    }

    /** Forgets codec state; call when the headset connection restarts. */
    fun resetStream() {
        configFrame = null
        for (viewer in viewers) viewer.waitingForKeyframe = true
    }

    /** Serves an upgraded WebSocket connection until it closes. Blocks the calling thread. */
    fun serve(socket: Socket, input: InputStream, welcome: String) {
        val viewer = Viewer(nextId.getAndIncrement(), socket)
        viewer.send(WebSocket.textFrame(welcome), essential = true)
        configFrame?.let { viewer.send(it, essential = true) }
        viewers.add(viewer)
        Log.i(TAG, "Viewer ${viewer.id} connected from ${socket.inetAddress.hostAddress} (${viewers.size} total)")
        requestKeyframe()
        try {
            while (!viewer.closed) {
                val message = WebSocket.read(input)
                when (message.opcode) {
                    WebSocket.OPCODE_CLOSE -> break
                    WebSocket.OPCODE_PING -> viewer.send(WebSocket.frame(WebSocket.OPCODE_PONG, null, message.payload), essential = true)
                    WebSocket.OPCODE_TEXT -> if (String(message.payload).contains("\"keyframe\"")) {
                        viewer.waitingForKeyframe = true
                        configFrame?.let { viewer.send(it, essential = true) }
                        requestKeyframe()
                    }
                }
            }
        } catch (_: IOException) {
        } finally {
            viewers.remove(viewer)
            viewer.close()
            Log.i(TAG, "Viewer ${viewer.id} disconnected (${viewers.size} left)")
        }
    }

    fun closeAll() {
        for (viewer in viewers) viewer.close()
        viewers.clear()
    }

    private class Viewer(val id: Int, private val socket: Socket) {
        private val queue = LinkedBlockingDeque<ByteArray>()
        private val queuedBytes = AtomicLong()
        @Volatile var waitingForKeyframe = true
        @Volatile var closed = false

        init {
            socket.tcpNoDelay = true
            socket.sendBufferSize = 512 * 1024
        }

        private val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        private val writer = Thread(::writeLoop, "Viewer-$id").apply { start() }

        /** Returns false when the viewer is too far behind and was reset to wait for a keyframe. */
        fun send(frame: ByteArray, essential: Boolean): Boolean {
            if (closed) return true
            if (!essential && (queue.size > MAX_QUEUED_MESSAGES || queuedBytes.get() > MAX_QUEUED_BYTES)) {
                queue.clear()
                queuedBytes.set(DROPPED_NOTICE.size.toLong())
                // Tell the page why the picture is about to pause.
                queue.offer(DROPPED_NOTICE)
                waitingForKeyframe = true
                return false
            }
            queuedBytes.addAndGet(frame.size.toLong())
            queue.offer(frame)
            return true
        }

        private fun writeLoop() {
            try {
                while (!closed) {
                    val frame = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    queuedBytes.addAndGet(-frame.size.toLong())
                    output.write(frame)
                    if (queue.isEmpty()) output.flush()
                }
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            } finally {
                close()
            }
        }

        fun close() {
            if (closed) return
            closed = true
            writer.interrupt()
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val TAG = "ViewerHub"
        const val TYPE_CONFIG = 1
        const val TYPE_VIDEO = 2
        const val TYPE_AUDIO = 3
        const val FLAG_KEYFRAME = 1
        const val AUDIO_CHANNELS = 2
        const val AUDIO_SAMPLE_RATE = 48000
        private const val MAX_QUEUED_MESSAGES = 90
        private const val MAX_QUEUED_BYTES = 3L * 1024 * 1024
        private val DROPPED_NOTICE = WebSocket.textFrame("""{"type":"dropped"}""")

        fun header(type: Int, flags: Int, channels: Int, sampleRate: Int, timestampUs: Long): ByteArray =
            ByteBuffer.allocate(16)
                .put(type.toByte()).put(flags.toByte()).put(channels.toByte()).put(0)
                .putInt(sampleRate).putLong(timestampUs).array()
    }
}
