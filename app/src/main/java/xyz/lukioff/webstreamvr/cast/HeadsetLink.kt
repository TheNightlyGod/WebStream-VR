package xyz.lukioff.webstreamvr.cast

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

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
        if (running) return
        settings = initial
        running = true
        controlThread = Thread(::controlLoop, "HeadsetLink").apply { start() }
    }

    fun stop() {
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
        val s = session ?: return
        if (!s.handshakeDone) return
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
            when (castPackageState(context)) {
                CastPkgState.MISSING -> {
                    listener.onStateChanged(State.ERROR, "Не найден com.oculus.magicislandcastingservice")
                    return
                }
                CastPkgState.OUTDATED -> {
                    listener.onStateChanged(State.ERROR, "Необходима подходящая версия com.oculus.magicislandcastingservice")
                    return
                }
                CastPkgState.OK -> {}
            }
            listener.onStateChanged(State.STARTING, null)
            val serviceError = CastingServiceControl.startService(context)
            if (serviceError != null) Log.w(TAG, "startService: $serviceError")
            val result = try {
                runSession()
            } catch (_: InterruptedException) {
                return
            } catch (e: IOException) {
                Log.w(TAG, "Session failed", e)
                e.message
            }
            if (!running) return
            failures++
            val detail = result ?: "Соединение со шлемом закрыто"
            listener.onStateChanged(State.ERROR, if (serviceError != null) "$detail ($serviceError)" else detail)
            try {
                Thread.sleep(if (failures < 3) 2000L else 5000L)
            } catch (_: InterruptedException) {
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
            listener.onStateChanged(State.WAITING_FOR_HEADSET, null)
            server.soTimeout = CONNECT_WAIT_MS
            var first: Socket? = null
            for (attempt in 0 until CONNECT_ATTEMPTS) {
                if (!running) return null
                if (attempt % 2 == 1) {
                    Log.i(TAG, "Restarting the casting service")
                    CastingServiceControl.stopService(context)
                    Thread.sleep(500L)
                    CastingServiceControl.startService(context)
                }
                // The service registers its receiver in onCreate; give a fresh start a moment.
                Thread.sleep(if (attempt == 0) 700L else 1200L)
                CastingServiceControl.connect(context, LOOPBACK, server.localPort, sessionUuid)
                try {
                    first = server.accept()
                    break
                } catch (_: SocketTimeoutException) {
                    Log.i(TAG, "No connection from the casting service yet (attempt ${attempt + 1})")
                }
            }
            if (first == null) {
                return "Сервис кастинга не подключился. Возможно, он занят другой трансляцией (MQDH) — перезагрузите шлем."
            }
            server.soTimeout = SECOND_SOCKET_WAIT_MS
            val second = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                first.close()
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
            ServerSocket(PREFERRED_PORT, 4, address)
        } catch (_: IOException) {
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
            val input = BufferedInputStream(socket.getInputStream(), 256 * 1024)
            val assembler = MagicDatagramAssembler()
            var sawData = false
            try {
                while (!closed) {
                    val packet = Xrsp.read(input)
                    if (isA && !sawData) {
                        sawData = true
                        writer = writerB
                        Log.i(TAG, "Headset writes on the first socket; swapping directions")
                    }
                    if (packet.topic != Xrsp.TOPIC_MAGIC) continue
                    val message = assembler.accept(packet.payload) ?: continue
                    if (appId == 0) appId = message.appId
                    handle(IncomingMagic.parse(message.bytes))
                }
            } catch (e: IOException) {
                if (!closed) Log.i(TAG, "Headset socket closed: ${e.message}")
            } catch (e: RuntimeException) {
                Log.e(TAG, "Malformed data from headset", e)
            } finally {
                close()
            }
        }

        private fun handle(message: IncomingMagic) {
            when (message) {
                is IncomingMagic.Handshake -> {
                    Log.i(TAG, "Handshake v${message.version} over ${message.transport}")
                    val current = settings ?: return
                    send(MagicMessages.setAudioState(current.audio))
                    send(MagicMessages.bitrate(current.bitrate))
                    MagicMessages.encoderLimits(current.cast.width, current.cast.height).forEach(::send)
                    send(MagicMessages.startCasting(current.cast, UUID.randomUUID().toString()))
                    handshakeDone = true
                    listener.onStateChanged(State.CONNECTED, null)
                }
                is IncomingMagic.Ping -> send(MagicMessages.pong(message.timestamp))
                is IncomingMagic.LayerConfiguration -> {
                    Log.i(TAG, "Layer ${message.layerId} '${message.layerName}' ${message.width}x${message.height}")
                    layerId = message.layerId
                    send(MagicMessages.activateLayer(message.layerId))
                    listener.onLayerConfigured(message.width, message.height)
                }
                is IncomingMagic.VideoSegment -> {
                    if (!streaming) {
                        streaming = true
                        listener.onStateChanged(State.STREAMING, null)
                    }
                    listener.onVideo(message.data, message.offset, message.data.size - message.offset, nowUs())
                }
                is IncomingMagic.AudioSegment ->
                    listener.onAudio(message.data, message.offset, message.data.size - message.offset, nowUs())
                is IncomingMagic.DestroyLayer -> if (message.layerId == layerId) layerId = -1
                IncomingMagic.StopCasting -> {
                    Log.i(TAG, "Headset stopped casting")
                    close()
                }
                is IncomingMagic.Other -> Log.d(TAG, "Ignoring message ${message.type} (${message.size} bytes)")
            }
        }

        fun requestKeyframe() {
            val id = layerId
            val now = SystemClock.elapsedRealtime()
            if (id < 0 || now - lastResyncMs < RESYNC_MIN_INTERVAL_MS) return
            lastResyncMs = now
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
                Log.i(TAG, "Send failed: ${e.message}")
                close()
            }
        }

        fun close() {
            if (closed) return
            closed = true
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
    }
}
