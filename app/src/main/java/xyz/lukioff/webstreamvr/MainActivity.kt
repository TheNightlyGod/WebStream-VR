package xyz.lukioff.webstreamvr

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import xyz.lukioff.webstreamvr.cast.CastPkgState
import xyz.lukioff.webstreamvr.cast.CastingServiceInstaller
import xyz.lukioff.webstreamvr.cast.castPackageState
import xyz.lukioff.webstreamvr.diag.AppLog
import xyz.lukioff.webstreamvr.ui.MainScreen
import xyz.lukioff.webstreamvr.ui.theme.WebStreamVRTheme

class MainActivity : ComponentActivity() {
    private val castPkgStateState = mutableStateOf(CastPkgState.OK)

    private val installLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val newState = castPackageState(this)
        AppLog.i(TAG, "Service installer finished: resultCode=${result.resultCode}, castPkgState=$newState")
        castPkgStateState.value = newState
        if (newState == CastPkgState.OK) {
            StreamService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(applicationContext)
        AppLog.i(TAG, "onCreate(restored=${savedInstanceState != null}, action=${intent?.action})")
        enableEdgeToEdge()
        if (savedInstanceState == null) handleAction(intent)
        castPkgStateState.value = castPackageState(this)
        AppLog.i(TAG, "Initial castPkgState=${castPkgStateState.value}")
        setContent {
            WebStreamVRTheme {
                val status by StreamService.status.collectAsState()
                val hasCrashReport by AppLog.hasCrashReport.collectAsState()
                val logRevision by AppLog.revision.collectAsState()
                var settings by remember { mutableStateOf(StreamSettings.load(this)) }
                val castPkgState by castPkgStateState
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        status = status,
                        settings = settings,
                        castPkgState = castPkgState,
                        hasCrashReport = hasCrashReport,
                        logRevision = logRevision,
                        onSettingsChange = {
                            settings = it
                            StreamSettings.save(this, it)
                            StreamService.apply(this)
                        },
                        onStart = { StreamService.start(this) },
                        onStop = { StreamService.stop(this) },
                        onInstallService = {
                            if (!CastingServiceInstaller.install(this, installLauncher)) {
                                Toast.makeText(this, R.string.install_failed, Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val updated = castPackageState(this)
        if (updated != castPkgStateState.value) {
            AppLog.i(TAG, "castPkgState changed onResume: ${castPkgStateState.value} -> $updated")
        }
        castPkgStateState.value = updated
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AppLog.i(TAG, "onNewIntent(action=${intent.action})")
        handleAction(intent)
    }

    // Lets `adb shell am start -n xyz.lukioff.webstreamvr/.MainActivity -a xyz.lukioff.webstreamvr.START`
    // (or .STOP) control streaming without touching the panel.
    private fun handleAction(intent: Intent?) {
        when (intent?.action) {
            StreamService.ACTION_START -> StreamService.start(this)
            StreamService.ACTION_STOP -> StreamService.stop(this)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
