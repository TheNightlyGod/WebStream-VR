package xyz.lukioff.webstreamvr

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import xyz.lukioff.webstreamvr.ui.MainScreen
import xyz.lukioff.webstreamvr.ui.theme.WebStreamVRTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleAction(intent)
        setContent {
            WebStreamVRTheme {
                val status by StreamService.status.collectAsState()
                var settings by remember { mutableStateOf(StreamSettings.load(this)) }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        status = status,
                        settings = settings,
                        onSettingsChange = {
                            settings = it
                            StreamSettings.save(this, it)
                            StreamService.apply(this)
                        },
                        onStart = { StreamService.start(this) },
                        onStop = { StreamService.stop(this) },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
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
}
