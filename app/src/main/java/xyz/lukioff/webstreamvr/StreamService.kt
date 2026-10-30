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
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import xyz.lukioff.webstreamvr.cast.HeadsetLink
import xyz.lukioff.webstreamvr.replay.ReplayRecorder
import xyz.lukioff.webstreamvr.replay.SavedReplay
import xyz.lukioff.webstreamvr.web.ViewerHub
import xyz.lukioff.webstreamvr.web.WebServer
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

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
    val replayActive: Boolean = false,
    val replaySeconds: Int = 0,
    val replaySaving: Boolean = false,
    val replayMessage: String? = null,
)

/**
 * Foreground service that owns the headset link, the viewer hub and the web server.
 */
class StreamService : Service(), HeadsetLink.Listener {

    private lateinit var settings: StreamSettings
    private lateinit var hub: ViewerHub
    private lateinit var link: HeadsetLink
    private var web: WebServer? = null
    @Volatile private var replay: ReplayRecorder? = null
    // Only files from this stream can be downloaded, and only by name from this map.
    private val savedReplays = ConcurrentHashMap<String, SavedReplay>()
    private var keyframeTicks = 0
    private var notificationText = ""
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var started = false

    private val tick = object : Runnable {
        override fun run() {
            hub.updateStats()
            val recorder = replay
            // The encoder makes keyframes only on request, and a replay can only start at one.
            if (recorder != null && ++keyframeTicks % REPLAY_KEYFRAME_TICKS == 0) link.requestKeyframe()
            val replaySeconds = recorder?.buffer?.bufferedSeconds() ?: 0
            _status.update { it.copy(fps = hub.fps, kbps = hub.kbps, viewers = hub.viewerCount, replaySeconds = replaySeconds) }
            hub.broadcastText(statusJson())
            handler.postDelayed(this, 1000)
        }
    }

    private val restoreNotification = Runnable { updateNotification(notificationText) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
            }
            ACTION_APPLY -> if (started) applySettings()
            ACTION_SAVE_REPLAY -> if (started) saveReplay(null)
            else -> startStreaming()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    private var streamCode: String? = null

    private fun startStreaming() {
        if (started) stopStreaming()
        started = true
        startForeground(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notification_starting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        notificationText = getString(R.string.notification_starting)
        val code = (100000..999999).random().toString()
        streamCode = code
        settings = StreamSettings.load(this)
        hub = ViewerHub { link.requestKeyframe() }
        hub.onSaveReplay = ::saveReplay
        link = HeadsetLink(applicationContext, this)
        acquireLocks()
        _status.value = StreamStatus(running = true, streamCode = code)
        restartReplay()
        startWebServer()
        link.start(settings.toLinkSettings())
        handler.post(tick)
    }

    private fun stopStreaming() {
        if (!started) return
        started = false
        streamCode = null
        handler.removeCallbacks(tick)
        handler.removeCallbacks(restoreNotification)
        link.stop()
        web?.stop()
        web = null
        replay?.close()
        replay = null
        savedReplays.clear()
        releaseLocks()
        _status.value = StreamStatus()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun applySettings() {
        val newSettings = StreamSettings.load(this)
        val portChanged = newSettings.httpPort != settings.httpPort
        val replayChanged = newSettings.replayEnabled != settings.replayEnabled ||
            newSettings.replaySeconds != settings.replaySeconds ||
            newSettings.replayMemoryBytes != settings.replayMemoryBytes
        settings = newSettings
        link.update(newSettings.toLinkSettings())
        if (replayChanged) {
            restartReplay()
            updateNotification(notificationText)
        }
        if (portChanged) {
            web?.stop()
            web = null
            startWebServer()
        }
    }

    /** Sets the replay buffer up for the current settings; what it held so far is lost. */
    private fun restartReplay() {
        replay?.close()
        val recorder = if (settings.replayEnabled) {
            ReplayRecorder.create(contentResolver, settings.replaySeconds, settings.replayMemoryBytes)
        } else {
            null
        }
        // The encoder sends parameter sets once, and a buffer started mid-stream needs them too.
        hub.parameterSets?.let { recorder?.buffer?.onVideo(it, 0, it.size, 0) }
        replay = recorder
        val message = if (settings.replayEnabled && recorder == null) getString(R.string.replay_no_memory) else null
        _status.update { it.copy(replayActive = recorder != null, replaySeconds = 0, replaySaving = false, replayMessage = message) }
    }

    /** Saves the newest [seconds] (null = the full buffer); [reply] gets progress as JSON for a viewer. */
    private fun saveReplay(seconds: Int?, reply: (String) -> Unit = {}) {
        val recorder = replay
        if (recorder == null) {
            reply(replayJson("empty"))
            return
        }
        val limit = settings.replaySeconds
        val durationUs = (seconds ?: limit).coerceIn(1, limit) * 1_000_000L
        when (recorder.save(durationUs) { saved -> onReplaySaved(saved, reply) }) {
            ReplayRecorder.SaveStart.STARTED -> {
                _status.update { it.copy(replaySaving = true, replayMessage = getString(R.string.replay_saving)) }
                showBriefly(getString(R.string.replay_saving))
                reply(replayJson("saving"))
            }
            ReplayRecorder.SaveStart.BUSY -> reply(replayJson("busy"))
            ReplayRecorder.SaveStart.EMPTY -> {
                _status.update { it.copy(replayMessage = getString(R.string.replay_empty)) }
                showBriefly(getString(R.string.replay_empty))
                reply(replayJson("empty"))
            }
        }
    }

    private fun onReplaySaved(saved: SavedReplay?, reply: (String) -> Unit) {
        if (saved != null) savedReplays[saved.name] = saved
        reply(if (saved != null) replayJson("saved", saved) else replayJson("failed"))
        if (!started) return
        val message = if (saved != null) getString(R.string.replay_saved, saved.name) else getString(R.string.replay_failed)
        _status.update { it.copy(replaySaving = false, replayMessage = message) }
        showBriefly(message)
    }

    private fun replayJson(state: String, saved: SavedReplay? = null): String =
        JSONObject()
            .put("type", "replay")
            .put("state", state)
            .put("name", saved?.name ?: JSONObject.NULL)
            .put("size", saved?.size ?: 0)
            .toString()

    private fun openReplay(name: String): WebServer.Download? {
        val saved = savedReplays[name] ?: return null
        return WebServer.Download(saved.name, saved.size) {
            contentResolver.openInputStream(saved.uri) ?: throw IOException("Cannot open ${saved.uri}")
        }
    }

    private fun startWebServer() {
        val server = WebServer(assets, hub, streamCode, ::openReplay, ::statusJson)
        try {
            server.start(settings.httpPort)
            web = server
            val urls = localAddresses().map { "http://$it:${settings.httpPort}" }
            _status.update { it.copy(urls = urls, webError = null) }
            notificationText = urls.firstOrNull() ?: getString(R.string.notification_no_network)
            updateNotification(notificationText)
        } catch (e: IOException) {
            Log.e(TAG, "Web server failed", e)
            _status.update { it.copy(urls = emptyList(), webError = getString(R.string.web_port_busy, settings.httpPort)) }
        }
    }

    // HeadsetLink.Listener — called from network threads.

    override fun onStateChanged(state: HeadsetLink.State, detail: String?) {
        if (!started) return
        if (state == HeadsetLink.State.STARTING) {
            hub.resetStream()
            replay?.buffer?.onStreamRestart()
        }
        _status.update { it.copy(headset = state, detail = detail) }
    }

    override fun onLayerConfigured(width: Int, height: Int) {
        if (!started) return
        _status.update { it.copy(width = width, height = height) }
    }

    override fun onVideo(data: ByteArray, offset: Int, length: Int, timestampUs: Long) {
        replay?.buffer?.onVideo(data, offset, length, timestampUs)
        hub.onVideo(data, offset, length, timestampUs)
    }

    override fun onAudio(data: ByteArray, offset: Int, length: Int, timestampUs: Long) {
        replay?.buffer?.onAudio(data, offset, length, timestampUs)
        hub.onAudio(data, offset, length, timestampUs)
    }

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
            .put("replay", s.replayActive)
            .put("replayMax", settings.replaySeconds)
            .put("replaySeconds", s.replaySeconds)
            .put("replaySaving", s.replaySaving)
            .toString()
    }

    // Held for the whole stream and released in stopStreaming(); a timeout would cut long sessions.
    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "WebStreamVR:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WebStreamVR:stream").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wifiLock?.release()
        wifiLock = null
        wakeLock?.release()
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
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_stream)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
        if (replay != null) {
            val save = PendingIntent.getService(
                this, 2, Intent(this, StreamService::class.java).setAction(ACTION_SAVE_REPLAY), PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(Notification.Action.Builder(null, getString(R.string.action_save_replay), save).build())
        }
        return builder
            .addAction(Notification.Action.Builder(null, getString(R.string.action_stop), stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    /** Shows [text] in the notification for a few seconds, then goes back to the address. */
    private fun showBriefly(text: String) {
        handler.removeCallbacks(restoreNotification)
        updateNotification(text)
        handler.postDelayed(restoreNotification, BRIEF_NOTIFICATION_MS)
    }

    companion object {
        private const val TAG = "StreamService"
        private const val CHANNEL_ID = "stream"
        private const val NOTIFICATION_ID = 1
        private const val REPLAY_KEYFRAME_TICKS = 2
        private const val BRIEF_NOTIFICATION_MS = 4000L
        const val ACTION_START = "xyz.lukioff.webstreamvr.START"
        const val ACTION_STOP = "xyz.lukioff.webstreamvr.STOP"
        const val ACTION_APPLY = "xyz.lukioff.webstreamvr.APPLY"
        const val ACTION_SAVE_REPLAY = "xyz.lukioff.webstreamvr.SAVE_REPLAY"

        private val _status = MutableStateFlow(StreamStatus())
        val status: StateFlow<StreamStatus> = _status.asStateFlow()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, StreamService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, StreamService::class.java).setAction(ACTION_STOP))
        }

        /** Pushes freshly saved settings to a running stream. */
        fun apply(context: Context) {
            if (_status.value.running) {
                context.startService(Intent(context, StreamService::class.java).setAction(ACTION_APPLY))
            }
        }

        /** Saves the whole replay buffer of a running stream. */
        fun saveReplay(context: Context) {
            if (_status.value.running) {
                context.startService(Intent(context, StreamService::class.java).setAction(ACTION_SAVE_REPLAY))
            }
        }

        fun localAddresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .map { it.hostAddress!! }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
