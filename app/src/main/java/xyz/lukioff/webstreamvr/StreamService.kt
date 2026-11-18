package xyz.lukioff.webstreamvr

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import xyz.lukioff.webstreamvr.cast.HeadsetLink
import xyz.lukioff.webstreamvr.diag.AppLog
import xyz.lukioff.webstreamvr.web.ViewerHub
import xyz.lukioff.webstreamvr.web.WebServer
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface

/** What the UI shows; published by [StreamService]. */
data class StreamStatus(
    val running: Boolean = false,
    val headset: HeadsetLink.State = HeadsetLink.State.IDLE,
    val detail: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float = 0f,
    val kbps: Int = 0,
    val viewers: Int = 0,
    val urls: List<String> = emptyList(),
    val webError: String? = null,
    val streamCode: String? = null,
)

/**
 * Foreground service that owns the headset link, the viewer hub and the web server.
 */
class StreamService : Service(), HeadsetLink.Listener {

    private lateinit var settings: StreamSettings
    private lateinit var hub: ViewerHub
    private lateinit var link: HeadsetLink
    private var web: WebServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var started = false

    private val tick = object : Runnable {
        override fun run() {
            hub.updateStats()
            _status.update { it.copy(fps = hub.fps, kbps = hub.kbps, viewers = hub.viewerCount) }
            hub.broadcastText(statusJson())
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.init(applicationContext)
        AppLog.i(TAG, "StreamService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.i(TAG, "onStartCommand: action=${intent?.action} flags=$flags startId=$startId started=$started")
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
            }
            ACTION_APPLY -> if (started) applySettings()
            else -> startStreaming()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AppLog.i(TAG, "StreamService onDestroy (started=$started)")
        stopStreaming()
        super.onDestroy()
    }

    private var streamCode: String? = null

    private fun startStreaming() {
        if (started) stopStreaming()
        AppLog.i(TAG, "Starting streaming foreground service")
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(getString(R.string.notification_starting)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "startForeground failed", e)
            _status.value = StreamStatus(
                running = false,
                headset = HeadsetLink.State.ERROR,
                detail = "${e.javaClass.simpleName}: ${e.message}",
            )
            stopSelf()
            return
        }
        started = true
        val code = (100000..999999).random().toString()
        streamCode = code
        settings = StreamSettings.load(this)
        AppLog.i(
            TAG,
            "Loaded settings: aspect=${settings.aspect} quality=${settings.quality} (${settings.frameSize.first}x${settings.frameSize.second}) " +
                "fps=${settings.frameRate} bitrateMbps=${settings.bitrateMbps} fov=${settings.fovAdjustment} " +
                "eye=${settings.eye} stab=${settings.stabilization} audio=${settings.audio} port=${settings.httpPort}"
        )
        hub = ViewerHub { if (::link.isInitialized) link.requestKeyframe() }
        link = HeadsetLink(applicationContext, this)
        acquireLocks()
        _status.value = StreamStatus(running = true, streamCode = code)
        startWebServer()
        link.start(settings.toLinkSettings())
        handler.post(tick)
    }

    private fun stopStreaming() {
        if (!started) return
        AppLog.i(TAG, "Stopping streaming")
        started = false
        streamCode = null
        handler.removeCallbacks(tick)
        if (::link.isInitialized) link.stop()
        web?.stop()
        web = null
        releaseLocks()
        _status.value = StreamStatus()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            .onFailure { AppLog.w(TAG, "stopForeground failed", it) }
    }

    private fun applySettings() {
        val newSettings = StreamSettings.load(this)
        val portChanged = newSettings.httpPort != settings.httpPort
        AppLog.i(
            TAG,
            "Applying settings (portChanged=$portChanged): ${newSettings.frameSize.first}x${newSettings.frameSize.second} " +
                "fps=${newSettings.frameRate} bitrateMbps=${newSettings.bitrateMbps} audio=${newSettings.audio} port=${newSettings.httpPort}"
        )
        settings = newSettings
        if (::link.isInitialized) link.update(newSettings.toLinkSettings())
        if (portChanged) {
            web?.stop()
            web = null
            startWebServer()
        }
    }

    private fun startWebServer() {
        val server = WebServer(
            assets = assets,
            hub = hub,
            streamCode = streamCode,
            logReport = { AppLog.buildReport(applicationContext) },
            statusJson = ::statusJson,
        )
        try {
            server.start(settings.httpPort)
            web = server
            val urls = localAddresses().map { "http://$it:${settings.httpPort}" }
            AppLog.i(TAG, "WebServer started on port ${settings.httpPort}, urls=$urls")
            _status.update { it.copy(urls = urls, webError = null) }
            updateNotification(urls.firstOrNull() ?: getString(R.string.notification_no_network))
        } catch (e: IOException) {
            AppLog.e(TAG, "Web server failed on port ${settings.httpPort}", e)
            _status.update { it.copy(urls = emptyList(), webError = getString(R.string.web_port_busy, settings.httpPort)) }
        }
    }

    // HeadsetLink.Listener — called from network threads.

    override fun onStateChanged(state: HeadsetLink.State, detail: String?) {
        if (!started) return
        AppLog.i(TAG, "Headset state -> $state${if (detail != null) " ($detail)" else ""}")
        if (state == HeadsetLink.State.STARTING && ::hub.isInitialized) hub.resetStream()
        _status.update { it.copy(headset = state, detail = detail) }
    }

    override fun onLayerConfigured(width: Int, height: Int) {
        if (!started) return
        AppLog.i(TAG, "Layer configured: ${width}x${height}")
        _status.update { it.copy(width = width, height = height) }
    }

    override fun onVideo(data: ByteArray, offset: Int, length: Int, timestampUs: Long) =
        hub.onVideo(data, offset, length, timestampUs)

    override fun onAudio(data: ByteArray, offset: Int, length: Int, timestampUs: Long) =
        hub.onAudio(data, offset, length, timestampUs)

    private fun statusJson(): String {
        val s = _status.value
        return JSONObject()
            .put("type", "status")
            .put("state", s.headset.name)
            .put("detail", s.detail ?: JSONObject.NULL)
            .put("width", s.width)
            .put("height", s.height)
            .put("fps", Math.round(s.fps * 10) / 10.0)
            .put("kbps", s.kbps)
            .put("viewers", s.viewers)
            .put("audio", settings.audio)
            .toString()
    }

    // Held for the whole stream and released in stopStreaming(); a timeout would cut long sessions.
    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        runCatching {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "WebStreamVR:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { AppLog.w(TAG, "Failed to acquire WifiLock", it) }
        runCatching {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WebStreamVR:stream").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { AppLog.w(TAG, "Failed to acquire WakeLock", it) }
    }

    private fun releaseLocks() {
        runCatching { wifiLock?.release() }.onFailure { AppLog.w(TAG, "Failed to release WifiLock", it) }
        wifiLock = null
        runCatching { wakeLock?.release() }.onFailure { AppLog.w(TAG, "Failed to release WakeLock", it) }
        wakeLock = null
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_stream)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.action_stop), stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        }.onFailure { AppLog.w(TAG, "Failed to update notification", it) }
    }

    companion object {
        private const val TAG = "StreamService"
        private const val CHANNEL_ID = "stream"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "xyz.lukioff.webstreamvr.START"
        const val ACTION_STOP = "xyz.lukioff.webstreamvr.STOP"
        const val ACTION_APPLY = "xyz.lukioff.webstreamvr.APPLY"

        private val _status = MutableStateFlow(StreamStatus())
        val status: StateFlow<StreamStatus> = _status.asStateFlow()

        fun start(context: Context) {
            AppLog.i(TAG, "StreamService.start() requested")
            try {
                context.startForegroundService(Intent(context, StreamService::class.java).setAction(ACTION_START))
            } catch (e: Exception) {
                AppLog.e(TAG, "Failed to start StreamService", e)
                _status.value = StreamStatus(
                    running = false,
                    headset = HeadsetLink.State.ERROR,
                    detail = "${e.javaClass.simpleName}: ${e.message}",
                )
            }
        }

        fun stop(context: Context) {
            AppLog.i(TAG, "StreamService.stop() requested")
            try {
                context.startService(Intent(context, StreamService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                AppLog.w(TAG, "Failed to stop StreamService", e)
            }
        }

        /** Pushes freshly saved settings to a running stream. */
        fun apply(context: Context) {
            if (_status.value.running) {
                try {
                    context.startService(Intent(context, StreamService::class.java).setAction(ACTION_APPLY))
                } catch (e: Exception) {
                    AppLog.w(TAG, "Failed to apply settings to StreamService", e)
                }
            }
        }

        fun localAddresses(): List<String> = try {
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val addrs = ifaces
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .mapNotNull { it.hostAddress }
            if (addrs.isEmpty()) {
                AppLog.w(TAG, "No non-loopback IPv4 addresses found (interfaces=${ifaces.map { "${it.name}:up=${it.isUp}" }})")
            }
            addrs
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to enumerate local addresses", e)
            emptyList()
        }
    }
}
