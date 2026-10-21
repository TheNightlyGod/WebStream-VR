package xyz.lukioff.webstreamvr.cast

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

const val CAST_PKG = "com.oculus.magicislandcastingservice"
const val MIN_CAST_VERSION_CODE = 839200015L // 2.0.0.0.4915

enum class CastPkgState { MISSING, OUTDATED, OK }

fun castPackageState(ctx: Context): CastPkgState {
    val pi = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ctx.packageManager.getPackageInfo(CAST_PKG, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(CAST_PKG, 0)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        return CastPkgState.MISSING
    }
    return if (pi.longVersionCode >= MIN_CAST_VERSION_CODE) CastPkgState.OK
    else CastPkgState.OUTDATED
}

/**
 * Talks to Meta's casting service (com.oculus.magicislandcastingservice).
 *
 * The service is exported, and on current firmware its broadcast receiver is
 * registered without a permission, so a regular app can start it and ask it to
 * connect. Everything after CONNECT goes over the TCP protocol instead of intents.
 */
object CastingServiceControl {
    private const val TAG = "CastingServiceControl"
    const val PACKAGE = CAST_PKG
    private const val SERVICE = "$PACKAGE.CastingService"
    private const val ACTION_START_SERVICE = "$PACKAGE.START_SERVICE"
    private const val ACTION_CONNECT = "$PACKAGE.CONNECT"

    fun isInstalled(context: Context): Boolean = castPackageState(context) == CastPkgState.OK

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
