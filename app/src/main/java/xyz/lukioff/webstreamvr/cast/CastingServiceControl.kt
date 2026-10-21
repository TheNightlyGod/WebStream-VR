package xyz.lukioff.webstreamvr.cast

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Talks to Meta's casting service (com.oculus.magicislandcastingservice).
 *
 * The service is exported, and on current firmware its broadcast receiver is
 * registered without a permission, so a regular app can start it and ask it to
 * connect. Everything after CONNECT goes over the TCP protocol instead of intents.
 */
object CastingServiceControl {
    private const val TAG = "CastingServiceControl"
    const val PACKAGE = "com.oculus.magicislandcastingservice"
    private const val SERVICE = "$PACKAGE.CastingService"
    private const val ACTION_START_SERVICE = "$PACKAGE.START_SERVICE"
    private const val ACTION_CONNECT = "$PACKAGE.CONNECT"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Returns null on success or the error text. */
    fun startService(context: Context): String? {
        val intent = Intent(ACTION_START_SERVICE)
            .setComponent(ComponentName(PACKAGE, SERVICE))
            .putExtra("id", context.packageName)
        return try {
            context.startForegroundService(intent)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start casting service", e)
            e.message ?: e.javaClass.simpleName
        }
    }

    /**
     * Stops the service. Its streamer object survives a failed CONNECT and keeps
     * retrying the old address, so a restart is the only way to reset it.
     */
    fun stopService(context: Context) {
        try {
            context.stopService(Intent().setComponent(ComponentName(PACKAGE, SERVICE)))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop casting service", e)
        }
    }

    fun connect(context: Context, host: String, port: Int, sessionUuid: String) {
        val intent = Intent(ACTION_CONNECT)
            .setPackage(PACKAGE)
            .putExtra("servers", arrayOf("$host:$port"))
            .putExtra("session_uuid", sessionUuid)
        context.sendBroadcast(intent)
    }
}
