package xyz.lukioff.webstreamvr.cast

import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.FileProvider
import xyz.lukioff.webstreamvr.diag.AppLog
import java.io.File
import java.io.FileOutputStream

object CastingServiceInstaller {
    private const val TAG = "CastingServiceInstaller"
    const val APK_ASSET_NAME = "com.oculus.magicislandcastingservice.release.apk"

    fun checkPackageState(context: Context): CastPkgState {
        return castPackageState(context)
    }

    fun isInstalled(context: Context): Boolean {
        return castPackageState(context) == CastPkgState.OK
    }

    fun install(context: Context, launcher: ActivityResultLauncher<Intent>? = null): Boolean {
        return try {
            val canInstall = runCatching { context.packageManager.canRequestPackageInstalls() }.getOrNull()
            AppLog.i(TAG, "Starting service APK install: state=${castPackageState(context)} canRequestPackageInstalls=$canInstall")
            val cacheFile = File(context.cacheDir, APK_ASSET_NAME)
            val copiedBytes = context.assets.open(APK_ASSET_NAME).use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
            AppLog.i(TAG, "Copied $APK_ASSET_NAME ($copiedBytes bytes) to ${cacheFile.absolutePath}")
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                cacheFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            if (launcher != null) {
                AppLog.i(TAG, "Launching installer via ActivityResultLauncher: uri=$contentUri")
                launcher.launch(intent)
            } else {
                AppLog.i(TAG, "Launching installer via startActivity: uri=$contentUri")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to copy or install $APK_ASSET_NAME", e)
            false
        }
    }
}
