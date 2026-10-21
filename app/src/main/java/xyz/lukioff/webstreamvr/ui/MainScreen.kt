package xyz.lukioff.webstreamvr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import xyz.lukioff.webstreamvr.AspectRatio
import xyz.lukioff.webstreamvr.R
import xyz.lukioff.webstreamvr.StreamSettings
import xyz.lukioff.webstreamvr.StreamStatus
import xyz.lukioff.webstreamvr.cast.HeadsetLink

@Composable
fun MainScreen(
    status: StreamStatus,
    settings: StreamSettings,
    onSettingsChange: (StreamSettings) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            StatusCard(status, onStart, onStop)
            SettingsCard(settings, onSettingsChange)
            Text(
                stringResource(R.string.hint_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusCard(status: StreamStatus, onStart: () -> Unit, onStop: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(stateColor(status)),
                )
                Spacer(Modifier.size(10.dp))
                Text(stateText(status), style = MaterialTheme.typography.titleMedium)
            }
            status.detail?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (status.running && status.headset == HeadsetLink.State.STREAMING) {
                Text(
                    stringResource(
                        R.string.stats_line,
                        status.width, status.height, status.fps, status.kbps / 1000f, status.viewers,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (status.running) {
                status.webError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                if (status.urls.isNotEmpty()) {
                    Text(stringResource(R.string.open_in_browser), style = MaterialTheme.typography.bodyMedium)
                    SelectionContainer {
                        Column {
                            status.urls.forEach {
                                Text(it, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
            if (status.running) {
                OutlinedButton(onClick = onStop) { Text(stringResource(R.string.action_stop)) }
            } else {
                Button(onClick = onStart) { Text(stringResource(R.string.action_start)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsCard(settings: StreamSettings, onChange: (StreamSettings) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleMedium)

            Setting(stringResource(R.string.setting_aspect)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AspectRatio.entries.forEach { aspect ->
                        FilterChip(
                            selected = settings.aspect == aspect,
                            onClick = { onChange(settings.copy(aspect = aspect)) },
                            label = { Text(aspect.label) },
                        )
                    }
                }
            }

            val (frameWidth, frameHeight) = settings.frameSize
            Setting(stringResource(R.string.setting_quality, frameWidth, frameHeight)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StreamSettings.QUALITIES.forEach { quality ->
                        FilterChip(
                            selected = settings.quality == quality,
                            onClick = { onChange(settings.copy(quality = quality)) },
                            label = { Text("${quality}p") },
                        )
                    }
                }
            }

            Setting(stringResource(R.string.setting_bitrate), stringResource(R.string.setting_bitrate_hint)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StreamSettings.BITRATES_MBPS.forEach { mbps ->
                        FilterChip(
                            selected = settings.bitrateMbps == mbps,
                            onClick = { onChange(settings.copy(bitrateMbps = mbps)) },
                            label = { Text(if (mbps == 0) stringResource(R.string.bitrate_auto) else stringResource(R.string.bitrate_mbps, mbps)) },
                        )
                    }
                }
            }

            Setting(stringResource(R.string.setting_frame_rate), stringResource(R.string.setting_frame_rate_hint)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StreamSettings.FRAME_RATES.forEach { fps ->
                        FilterChip(
                            selected = settings.frameRate == fps,
                            onClick = { onChange(settings.copy(frameRate = fps)) },
                            label = { Text("$fps") },
                        )
                    }
                }
            }

            Setting(stringResource(R.string.setting_eye)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(settings.eye == 0, { onChange(settings.copy(eye = 0)) }, { Text(stringResource(R.string.eye_left)) })
                    FilterChip(settings.eye == 1, { onChange(settings.copy(eye = 1)) }, { Text(stringResource(R.string.eye_right)) })
                }
            }

            Setting(stringResource(R.string.setting_fov, settings.fovAdjustment), stringResource(R.string.setting_fov_hint)) {
                // Keep the slider smooth and save only when the user lets go.
                var fov by remember(settings.fovAdjustment) { mutableFloatStateOf(settings.fovAdjustment.toFloat()) }
                Slider(
                    value = fov,
                    onValueChange = { fov = it },
                    onValueChangeFinished = { onChange(settings.copy(fovAdjustment = fov.toInt())) },
                    valueRange = 0f..StreamSettings.MAX_FOV_ADJUSTMENT.toFloat(),
                    steps = StreamSettings.MAX_FOV_ADJUSTMENT / 5 - 1,
                )
            }

            SwitchRow(stringResource(R.string.setting_stabilization), settings.stabilization) {
                onChange(settings.copy(stabilization = it))
            }
            SwitchRow(stringResource(R.string.setting_audio), settings.audio) {
                onChange(settings.copy(audio = it))
            }

            Setting(stringResource(R.string.setting_port)) {
                var text by remember(settings.httpPort) { mutableStateOf(settings.httpPort.toString()) }
                val port = text.toIntOrNull()
                val valid = port != null && port in 1024..65535
                OutlinedTextField(
                    value = text,
                    onValueChange = { value ->
                        text = value.filter(Char::isDigit).take(5)
                        text.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { onChange(settings.copy(httpPort = it)) }
                    },
                    singleLine = true,
                    isError = !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { if (!valid) Text(stringResource(R.string.setting_port_invalid)) },
                )
            }
        }
    }
}

@Composable
private fun Setting(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        content()
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun stateText(status: StreamStatus): String = when {
    !status.running -> stringResource(R.string.state_stopped)
    else -> when (status.headset) {
        HeadsetLink.State.IDLE, HeadsetLink.State.STARTING -> stringResource(R.string.state_starting)
        HeadsetLink.State.WAITING_FOR_HEADSET -> stringResource(R.string.state_waiting)
        HeadsetLink.State.CONNECTED -> stringResource(R.string.state_connected)
        HeadsetLink.State.STREAMING -> stringResource(R.string.state_streaming)
        HeadsetLink.State.ERROR -> stringResource(R.string.state_error)
    }
}

private fun stateColor(status: StreamStatus): Color = when {
    !status.running -> Color(0xFF8A8A8A)
    status.headset == HeadsetLink.State.STREAMING -> Color(0xFF3DDC84)
    status.headset == HeadsetLink.State.ERROR -> Color(0xFFFF5252)
    else -> Color(0xFFFFC107)
}
