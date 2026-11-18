package xyz.lukioff.webstreamvr.diag

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import xyz.lukioff.webstreamvr.StreamService
import xyz.lukioff.webstreamvr.StreamSettings
import xyz.lukioff.webstreamvr.cast.CAST_PKG
import xyz.lukioff.webstreamvr.cast.MIN_CAST_VERSION_CODE
import xyz.lukioff.webstreamvr.cast.castPackageState
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Collects a snapshot of device, Horizon OS, Meta casting service, permissions,
 * network interfaces and stream settings/status for diagnostic logs.
 */
object Diagnostics {

    fun collectHeader(context: Context): String {
        val sb = StringBuilder()
        appendAppInfo(context, sb)
        appendDeviceInfo(sb)
        appendMemoryInfo(sb)
        appendCastServiceInfo(context, sb)
        appendPermissionsInfo(context, sb)
        appendNetworkInfo(context, sb)
        appendStreamState(context, sb)
        return sb.toString().trimEnd()
    }

    private fun appendAppInfo(context: Context, sb: StringBuilder) {
        sb.appendLine("[App]")
        runCatching {
            val pi = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
            val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            sb.appendLine("package=${context.packageName} version=${pi.versionName} (${pi.longVersionCode}) debuggable=$debuggable")
        }.onFailure {
            sb.appendLine("package=${context.packageName} info_error=${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun appendDeviceInfo(sb: StringBuilder) {
        sb.appendLine("[Device]")
        sb.appendLine("manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} device=${Build.DEVICE} product=${Build.PRODUCT} hardware=${Build.HARDWARE}")
        sb.appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} incremental=${Build.VERSION.INCREMENTAL}")
        sb.appendLine("display=${Build.DISPLAY}")
        sb.appendLine("fingerprint=${Build.FINGERPRINT}")
        sb.appendLine("abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
    }

    private fun appendMemoryInfo(sb: StringBuilder) {
        val rt = Runtime.getRuntime()
        val mb = 1024L * 1024L
        sb.appendLine(
            "heap_mb: used=${(rt.totalMemory() - rt.freeMemory()) / mb} total=${rt.totalMemory() / mb} max=${rt.maxMemory() / mb}"
        )
    }

    private fun appendCastServiceInfo(context: Context, sb: StringBuilder) {
        sb.appendLine("[Meta Casting Service: $CAST_PKG]")
        val pm = context.packageManager
        val state = runCatching { castPackageState(context) }.getOrElse { it }
        sb.appendLine("castPackageState=$state minRequiredVersionCode=$MIN_CAST_VERSION_CODE")
        runCatching {
            val pi = pm.getPackageInfo(CAST_PKG, PackageManager.PackageInfoFlags.of(0))
            val ai = pi.applicationInfo
            val flags = ai?.flags ?: 0
            val isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            sb.appendLine(
                "installed=true versionName=${pi.versionName} versionCode=${pi.longVersionCode} " +
                    "enabled=${ai?.enabled} targetSdk=${ai?.targetSdkVersion} " +
                    "system=$isSystem updatedSystem=$isUpdatedSystem sourceDir=${ai?.sourceDir}"
            )
        }.onFailure {
            sb.appendLine("installed=false error=${it.javaClass.simpleName}: ${it.message}")
        }

        runCatching {
            val serviceIntent = Intent().setComponent(ComponentName(CAST_PKG, "$CAST_PKG.CastingService"))
            val resolved = pm.resolveService(serviceIntent, PackageManager.ResolveInfoFlags.of(0))
            val si = resolved?.serviceInfo
            if (si == null) {
                sb.appendLine("resolveService(CastingService)=null (not found or not visible)")
            } else {
                sb.appendLine(
                    "resolveService(CastingService): name=${si.name} exported=${si.exported} " +
                        "enabled=${si.enabled} permission=${si.permission} fgType=${si.foregroundServiceType}"
                )
            }
        }.onFailure {
            sb.appendLine("resolveService(CastingService) error=${it.javaClass.simpleName}: ${it.message}")
        }

        runCatching {
            val connectIntent = Intent("$CAST_PKG.CONNECT").setPackage(CAST_PKG)
            val receivers = pm.queryBroadcastReceivers(connectIntent, PackageManager.ResolveInfoFlags.of(0))
            sb.appendLine("manifestReceivers(CONNECT)=${receivers.size} (dynamic receiver in onCreate is normal)")
        }.onFailure {
            sb.appendLine("queryBroadcastReceivers(CONNECT) error=${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun appendPermissionsInfo(context: Context, sb: StringBuilder) {
        sb.appendLine("[Permissions & Policy]")
        runCatching {
            val canInstall = context.packageManager.canRequestPackageInstalls()
            val notifGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            val notifEnabled = context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled()
            val ignoringBattery = context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName)
            sb.appendLine(
                "canRequestPackageInstalls=$canInstall postNotificationsGranted=$notifGranted " +
                    "notificationsEnabled=$notifEnabled ignoringBatteryOpt=$ignoringBattery"
            )
        }.onFailure {
            sb.appendLine("permissions_error=${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun appendNetworkInfo(context: Context, sb: StringBuilder) {
        sb.appendLine("[Network]")
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val active = cm?.activeNetwork
            val caps = if (active != null) cm.getNetworkCapabilities(active) else null
            val transports = buildList {
                if (caps != null) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETHERNET")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELLULAR")
                }
            }
            sb.appendLine("activeTransports=${if (transports.isEmpty()) "none" else transports.joinToString(",")}")
        }.onFailure {
            sb.appendLine("connectivity_error=${it.javaClass.simpleName}: ${it.message}")
        }

        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val up = ifaces.filter { it.isUp && !it.isLoopback }
            if (up.isEmpty()) {
                sb.appendLine("interfaces_up=none (total=${ifaces.size})")
            } else {
                for (iface in up) {
                    val addrs = iface.inetAddresses.toList().map { addr ->
                        val kind = when {
                            addr is Inet4Address && !addr.isLinkLocalAddress -> "ipv4"
                            addr is Inet4Address -> "ipv4-linklocal"
                            else -> "ipv6"
                        }
                        "$kind:${addr.hostAddress}"
                    }
                    sb.appendLine("iface ${iface.name}: ${addrs.joinToString(", ")}")
                }
            }
        }.onFailure {
            sb.appendLine("interfaces_error=${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun appendStreamState(context: Context, sb: StringBuilder) {
        sb.appendLine("[Stream Settings & Status]")
        runCatching {
            val s = StreamSettings.load(context)
            val (w, h) = s.frameSize
            sb.appendLine(
                "settings: aspect=${s.aspect.name} quality=${s.quality} (${w}x${h}) " +
                    "fps=${s.frameRate} bitrateMbps=${s.bitrateMbps} fov=${s.fovAdjustment} " +
                    "eye=${s.eye} stab=${s.stabilization} audio=${s.audio} port=${s.httpPort}"
            )
        }.onFailure {
            sb.appendLine("settings_error=${it.javaClass.simpleName}: ${it.message}")
        }
        runCatching {
            val st = StreamService.status.value
            sb.appendLine(
                "status: running=${st.running} headset=${st.headset} detail=${st.detail} " +
                    "video=${st.width}x${st.height} fps=${st.fps} kbps=${st.kbps} " +
                    "viewers=${st.viewers} webError=${st.webError} urls=${st.urls}"
            )
        }
    }
}
