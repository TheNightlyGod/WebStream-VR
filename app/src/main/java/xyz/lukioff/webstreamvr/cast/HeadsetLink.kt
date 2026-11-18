package xyz.lukioff.webstreamvr.cast

import android.content.Context
import android.os.SystemClock
import xyz.lukioff.webstreamvr.diag.AppLog
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Connection to the headset's casting service.
 *
 * We listen on loopback and ask the casting service to connect to us. Its
 * `tcpclient` transport opens two sockets: it reads from the first one and
 * writes to the second one. No XRSP session handshake is needed; MagicIsland
 * datagrams flow on topic 2 right after the sockets are open.
 *
 * Single use: create a new instance for every [start].
 */
class HeadsetLink(private val context: Context, private val listener: Listener) {

    enum class State { IDLE, STARTING, WAITING_FOR_HEADSET, CONNECTED, STREAMING, ERROR }

    interface Listener {
        fun onStateChanged(state: State, detail: String?)
        fun onLayerConfigured(width: Int, height: Int)
        fun onVideo(data: ByteArray, offset: Int, length: Int, timestampUs: Long)
        fun onAudio(data: ByteArray, offset: Int, length: Int, timestampUs: Long)
    }

    class Settings(val cast: CastConfig, val bitrate: Int, val audio: Boolean)

    @Volatile private var running = false
    @Volatile private var settings: Settings? = null
    @Volatile private var session: Session? = null
    @Volatile private var everConnected = false
    private var controlThread: Thread? = null
    // Control messages from the UI must not touch sockets on the main thread.
    private val io = Executors.newSingleThreadExecutor { Thread(it, "HeadsetLink-io") }

    fun start(initial: Settings) {
        if (running) {
            AppLog.w(TAG, "start() ignored: already running")
            return
        }
        AppLog.i(
            TAG,
            "start(): ${initial.cast.width}x${initial.cast.height}@${initial.cast.frameRate} " +
                "eye=${initial.cast.eye} fov=${initial.cast.fovAdjustment} stab=${initial.cast.stabilization} " +
                "bitrate=${initial.bitrate} audio=${initial.audio}"
        )
        settings = initial
        running = true
        controlThread = Thread(::controlLoop, "HeadsetLink").apply { start() }
    }

    fun stop() {
        AppLog.i(TAG, "stop(): running=$running everConnected=$everConnected hasSession=${session != null}")
        running = false
        val s = session
        val thread = controlThread
        controlThread = null
        val stopService = everConnected
        io.execute {
            s?.sendQuietly(MagicMessages.stopCasting())
            s?.close()
            thread?.interrupt()
            // Leave nothing half-initialized behind for the next start (see runSession).
            if (stopService) CastingServiceControl.stopService(context)
        }
        io.shutdown() // runs the queued stop task, then lets the thread end
        listener.onStateChanged(State.IDLE, null)
    }

    /** Applies new settings to a running stream without reconnecting. */
    fun update(newSettings: Settings) {
        val old = settings
        settings = newSettings
        val s = session
        if (s == null || !s.handshakeDone) {
            AppLog.d(TAG, "update() stored for next handshake (session=${s != null}, handshakeDone=${s?.handshakeDone == true})")
            return
        }
        AppLog.i(
            TAG,
            "update(): ${newSettings.cast.width}x${newSettings.cast.height}@${newSettings.cast.frameRate} " +
                "bitrate=${newSettings.bitrate} audio=${newSettings.audio}"
        )
        submit {
            if (old == null || old.audio != newSettings.audio) s.sendQuietly(MagicMessages.setAudioState(newSettings.audio))
            if (old == null || old.bitrate != newSettings.bitrate) s.sendQuietly(MagicMessages.bitrate(newSettings.bitrate))
            if (old == null || old.cast != newSettings.cast) {
                MagicMessages.encoderLimits(newSettings.cast.width, newSettings.cast.height).forEach(s::sendQuietly)
                s.sendQuietly(MagicMessages.updateCastingConfig(newSettings.cast))
            }
        }
    }

    /** Asks the encoder for an IDR frame; new viewers cannot start without one. */
    fun requestKeyframe() {
        val s = session ?: return
        submit { s.requestKeyframe() }
    }

    /** Runs [task] on the io thread; calls racing with [stop] are dropped. */
    private fun submit(task: () -> Unit) {
        try {
            io.execute(task)
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun controlLoop() {
        var failures = 0
        while (running) {
            when (val pkgState = castPackageState(context)) {
                CastPkgState.MISSING -> {
                    AppLog.e(TAG, "Aborting controlLoop: $CAST_PKG state=$pkgState")
                    listener.onStateChanged(State.ERROR, "Не найден com.oculus.magicislandcastingservice")
                    return
                }
                CastPkgState.OUTDATED -> {
                    AppLog.e(TAG, "Aborting controlLoop: $CAST_PKG state=$pkgState (min=$MIN_CAST_VERSION_CODE)")
                    listener.onStateChanged(State.ERROR, "Необходима подходящая версия com.oculus.magicislandcastingservice")
                    return
                }
                CastPkgState.OK -> {}
            }
            listener.onStateChanged(State.STARTING, null)
            val serviceError = CastingServiceControl.startService(context)
            if (serviceError != null) AppLog.w(TAG, "startService error: $serviceError")
            val result = try {
                runSession()
            } catch (_: InterruptedException) {
                AppLog.i(TAG, "controlLoop interrupted")
                return
            } catch (e: IOException) {
                AppLog.w(TAG, "Session failed with IOException", e)
                "${e.javaClass.simpleName}: ${e.message}"
            } catch (e: RuntimeException) {
                AppLog.e(TAG, "Session failed with RuntimeException", e)
                "${e.javaClass.simpleName}: ${e.message}"
            }
            if (!running) return
            failures++
            val detail = result ?: "Соединение со шлемом закрыто"
            val fullDetail = if (serviceError != null) "$detail ($serviceError)" else detail
            AppLog.w(TAG, "Control loop failure #$failures: $fullDetail")
            listener.onStateChanged(State.ERROR, fullDetail)
            val delayMs = if (failures < 3) 2000L else 5000L
            AppLog.i(TAG, "Retrying session in ${delayMs}ms")
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                AppLog.i(TAG, "controlLoop sleep interrupted")
                return
            }
        }
    }

    /**
     * Returns an error text when the casting service never connected, null on a normal disconnect.
     *
     * A CONNECT that fails leaves the service with a streamer that keeps the old
     * address and ignores new ones ("Streamer already exists"). A fixed port makes
     * such a streamer find us again; if it still does not, we restart the service.
     */
    private fun runSession(): String? {
        openServer().use { server ->
            val sessionUuid = UUID.randomUUID().toString()
            AppLog.i(TAG, "Loopback server listening on $LOOPBACK:${server.localPort}, sessionUuid=$sessionUuid")
            listener.onStateChanged(State.WAITING_FOR_HEADSET, null)
            server.soTimeout = CONNECT_WAIT_MS
            var first: Socket? = null
            var lastConnectError: String? = null
            for (attempt in 0 until CONNECT_ATTEMPTS) {
                if (!running) return null
                if (attempt % 2 == 1) {
                    AppLog.i(TAG, "Restarting the casting service before attempt ${attempt + 1}")
                    CastingServiceControl.stopService(context)
                    Thread.sleep(500L)
                    CastingServiceControl.startService(context)
                }
                // The service registers its receiver in onCreate; give a fresh start a moment.
                Thread.sleep(if (attempt == 0) 700L else 1200L)
                val connectErr = CastingServiceControl.connect(context, LOOPBACK, server.localPort, sessionUuid)
                if (connectErr != null) lastConnectError = connectErr
                val acceptStart = SystemClock.elapsedRealtime()
                try {
                    first = server.accept()
                    AppLog.i(
                        TAG,
                        "Accepted first socket from ${first.remoteSocketAddress} on attempt ${attempt + 1} " +
                            "in ${SystemClock.elapsedRealtime() - acceptStart}ms"
                    )
                    break
                } catch (_: SocketTimeoutException) {
                    AppLog.i(
                        TAG,
                        "No connection from the casting service yet (attempt ${attempt + 1}/$CONNECT_ATTEMPTS, " +
                            "port=${server.localPort}, waited=${SystemClock.elapsedRealtime() - acceptStart}ms)"
                    )
                }
            }
            if (first == null) {
                return if (lastConnectError != null) {
                    "Не удалось отправить CONNECT сервису кастинга ($lastConnectError)"
                } else {
                    "Сервис кастинга не подключился. Возможно, он занят другой трансляцией (MQDH) — перезагрузите шлем."
                }
            }
            server.soTimeout = SECOND_SOCKET_WAIT_MS
            val secondStart = SystemClock.elapsedRealtime()
            val second = try {
                server.accept().also {
                    AppLog.i(
                        TAG,
                        "Accepted second socket from ${it.remoteSocketAddress} in ${SystemClock.elapsedRealtime() - secondStart}ms"
                    )
                }
            } catch (e: SocketTimeoutException) {
                AppLog.w(TAG, "Timed out waiting for second socket after ${SystemClock.elapsedRealtime() - secondStart}ms", e)
                runCatching { first.close() }
                return "Сервис кастинга открыл только одно соединение"
            }
            val s = Session(first, second)
            session = s
            everConnected = true
            try {
                s.run()
            } finally {
                session = null
                s.close()
            }
        }
        return null
    }

    private fun openServer(): ServerSocket {
        // Not getLoopbackAddress(): on Android that is ::1, while the service dials 127.0.0.1.
        val address = InetAddress.getByName(LOOPBACK)
        return try {
            ServerSocket(PREFERRED_PORT, 4, address).also {
                AppLog.d(TAG, "Bound preferred loopback port $PREFERRED_PORT")
            }
        } catch (e: IOException) {
            AppLog.w(TAG, "Preferred port $PREFERRED_PORT unavailable (${e.message}), falling back to ephemeral port")
            ServerSocket(0, 4, address)
        }
    }

    private inner class Session(private val socketA: Socket, private val socketB: Socket) {
        private val writerA = XrspWriter(BufferedOutputStream(socketA.getOutputStream(), 64 * 1024))
        private val writerB = XrspWriter(BufferedOutputStream(socketB.getOutputStream(), 64 * 1024))
        // The headset reads from the first socket it opened; flip if data shows up there.
        @Volatile private var writer = writerA
        @Volatile private var appId = 0
        @Volatile var handshakeDone = false
        @Volatile private var layerId = -1
        @Volatile private var lastResyncMs = 0L
        @Volatile private var streaming = false
        @Volatile private var closed = false

        private val sessionStartMs = SystemClock.elapsedRealtime()
        private val videoFrames = AtomicLong()
        private val videoBytes = AtomicLong()
        private val audioFrames = AtomicLong()
        private val audioBytes = AtomicLong()
        private val pings = AtomicLong()
        private val resyncsSent = AtomicLong()
        private val otherCounts = ConcurrentHashMap<Int, AtomicInteger>()
        private val unexpectedTopicCounts = ConcurrentHashMap<Int, AtomicInteger>()
        @Volatile private var lastHealthLogMs = sessionStartMs

        fun run() {
            for (socket in arrayOf(socketA, socketB)) {
                socket.tcpNoDelay = true
                socket.receiveBufferSize = 1 shl 20
            }
            val readerA = Thread({ readLoop(socketA, isA = true) }, "HeadsetLink-A")
            readerA.start()
            readLoop(socketB, isA = false)
            close()
            readerA.join(1000)
        }

        private fun readLoop(socket: Socket, isA: Boolean) {
            val label = if (isA) "A" else "B"
            val input = BufferedInputStream(socket.getInputStream(), 256 * 1024)
            val assembler = MagicDatagramAssembler()
            var sawData = false
            var packets = 0L
            AppLog.d(TAG, "readLoop[$label] started (remote=${socket.remoteSocketAddress})")
            try {
                while (!closed) {
                    val packet = Xrsp.read(input)
                    packets++
                    if (isA && !sawData) {
                        sawData = true
                        writer = writerB
                        AppLog.i(TAG, "Headset writes on socket A; swapping writer to socket B")
                    }
                    if (packet.topic != Xrsp.TOPIC_MAGIC) {
                        val seen = unexpectedTopicCounts.getOrPut(packet.topic) { AtomicInteger() }.incrementAndGet()
                        if (seen <= 3 || seen % 100 == 0) {
                            AppLog.d(
                                TAG,
                                "readLoop[$label] ignoring XRSP topic=${packet.topic} (#$seen, size=${packet.payload.size})"
                            )
                        }
                        continue
                    }
                    val message = assembler.accept(packet.payload) ?: continue
                    if (appId == 0) appId = message.appId
                    handle(IncomingMagic.parse(message.bytes))
                }
                AppLog.i(TAG, "readLoop[$label] finished cleanly (packets=$packets, sawData=$sawData)")
            } catch (e: IOException) {
                if (!closed) {
                    AppLog.i(
                        TAG,
                        "readLoop[$label] socket closed after ${SystemClock.elapsedRealtime() - sessionStartMs}ms " +
                            "(packets=$packets, sawData=$sawData): ${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            } catch (e: RuntimeException) {
                AppLog.e(TAG, "readLoop[$label] malformed data (packets=$packets, sawData=$sawData)", e)
            } finally {
                close()
            }
        }

        private fun handle(message: IncomingMagic) {
            when (message) {
                is IncomingMagic.Handshake -> {
                    AppLog.i(TAG, "Handshake v${message.version} over '${message.transport}' (appId=$appId)")
                    val current = settings ?: return
                    send(MagicMessages.setAudioState(current.audio))
                    send(MagicMessages.bitrate(current.bitrate))
                    MagicMessages.encoderLimits(current.cast.width, current.cast.height).forEach(::send)
                    send(MagicMessages.startCasting(current.cast, UUID.randomUUID().toString()))
                    handshakeDone = true
                    listener.onStateChanged(State.CONNECTED, null)
                }
                is IncomingMagic.Ping -> {
                    val count = pings.incrementAndGet()
                    if (count == 1L || count % 30L == 0L) {
                        AppLog.d(TAG, "Ping #$count (ts=${message.timestamp})")
                    }
                    send(MagicMessages.pong(message.timestamp))
                }
                is IncomingMagic.LayerConfiguration -> {
                    AppLog.i(
                        TAG,
                        "Layer ${message.layerId} '${message.layerName}' pkg='${message.packageName}' ${message.width}x${message.height}"
                    )
                    layerId = message.layerId
                    send(MagicMessages.activateLayer(message.layerId))
                    listener.onLayerConfigured(message.width, message.height)
                }
                is IncomingMagic.VideoSegment -> {
                    val len = message.data.size - message.offset
                    val count = videoFrames.incrementAndGet()
                    val totalBytes = videoBytes.addAndGet(len.toLong())
                    if (count == 1L) {
                        AppLog.i(
                            TAG,
                            "First video frame: bytes=$len gop=${message.gopIndex} canonical=${message.canonicalIndex} layer=${message.layerId}"
                        )
                    }
                    if (!streaming) {
                        streaming = true
                        listener.onStateChanged(State.STREAMING, null)
                    }
                    maybeLogHealth(totalBytes)
                    listener.onVideo(message.data, message.offset, len, nowUs())
                }
                is IncomingMagic.AudioSegment -> {
                    val len = message.data.size - message.offset
                    val count = audioFrames.incrementAndGet()
                    audioBytes.addAndGet(len.toLong())
                    if (count == 1L) {
                        AppLog.i(TAG, "First audio frame: bytes=$len tsMs=${message.timestampMs} layer=${message.layerId}")
                    }
                    listener.onAudio(message.data, message.offset, len, nowUs())
                }
                is IncomingMagic.DestroyLayer -> {
                    AppLog.i(TAG, "DestroyLayer id=${message.layerId} (activeLayer=$layerId)")
                    if (message.layerId == layerId) layerId = -1
                }
                IncomingMagic.StopCasting -> {
                    AppLog.i(TAG, "Headset sent StopCasting")
                    close()
                }
                is IncomingMagic.Other -> {
                    val seen = otherCounts.getOrPut(message.type) { AtomicInteger() }.incrementAndGet()
                    if (seen <= 5 || seen % 100 == 0) {
                        AppLog.i(
                            TAG,
                            "Unhandled Magic message type=${message.type} (#$seen, size=${message.size}B, hex=[${message.preview}])"
                        )
                    }
                }
            }
        }

        private fun maybeLogHealth(totalVideoBytes: Long) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastHealthLogMs < HEALTH_LOG_INTERVAL_MS) return
            lastHealthLogMs = now
            AppLog.i(
                TAG,
                "Session health (${(now - sessionStartMs) / 1000}s): videoFrames=${videoFrames.get()} " +
                    "(${totalVideoBytes / (1024 * 1024)}MB), audioFrames=${audioFrames.get()}, " +
                    "pings=${pings.get()}, resyncs=${resyncsSent.get()}"
            )
        }

        fun requestKeyframe() {
            val id = layerId
            val now = SystemClock.elapsedRealtime()
            if (id < 0 || now - lastResyncMs < RESYNC_MIN_INTERVAL_MS) return
            lastResyncMs = now
            val count = resyncsSent.incrementAndGet()
            if (count <= 5 || count % 20L == 0L) {
                AppLog.d(TAG, "Requesting keyframe #$count for layerId=$id")
            }
            sendQuietly(MagicMessages.resyncFrame(id))
        }

        fun send(message: ByteArray) {
            if (closed) return
            writer.write(Xrsp.TOPIC_MAGIC, *MagicDatagramAssembler.frame(appId, message))
        }

        fun sendQuietly(message: ByteArray) {
            try {
                send(message)
            } catch (e: IOException) {
                AppLog.i(TAG, "Send failed: ${e.javaClass.simpleName}: ${e.message}")
                close()
            }
        }

        fun close() {
            if (closed) return
            closed = true
            val elapsedMs = SystemClock.elapsedRealtime() - sessionStartMs
            AppLog.i(
                TAG,
                "Closing session after ${elapsedMs}ms (handshakeDone=$handshakeDone, streaming=$streaming, " +
                    "videoFrames=${videoFrames.get()}, audioFrames=${audioFrames.get()}, pings=${pings.get()}, resyncs=${resyncsSent.get()})"
            )
            runCatching { socketA.close() }
            runCatching { socketB.close() }
        }
    }

    private fun nowUs() = SystemClock.elapsedRealtimeNanos() / 1000

    companion object {
        private const val TAG = "HeadsetLink"
        private const val LOOPBACK = "127.0.0.1"
        private const val PREFERRED_PORT = 47445
        private const val CONNECT_ATTEMPTS = 4
        private const val CONNECT_WAIT_MS = 3000
        private const val SECOND_SOCKET_WAIT_MS = 3000
        private const val RESYNC_MIN_INTERVAL_MS = 300L
        private const val HEALTH_LOG_INTERVAL_MS = 10_000L
    }
}
