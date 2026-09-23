package com.phonecam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phonecam.UiState
import com.phonecam.camera.CameraSettings
import com.phonecam.camera.Codec
import com.phonecam.camera.OrientationLock
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(state: UiState, onApply: ((CameraSettings) -> CameraSettings) -> Unit, onDismiss: () -> Unit) {
    val s = state.settings
    val lens = state.lenses.firstOrNull { it.id == s.lensId }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface,
        dragHandle = { Spacer(Modifier.padding(top = 10.dp).height(4.dp)) },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text("Stream settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(20.dp))

            Section("Quality")
            if (lens != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    lens.modes.forEach { m ->
                        Choice(m.label, m.width == s.width && m.height == s.height && m.fps == s.fps,
                            sub = if (m.highSpeed) "needs good light" else null) {
                            onApply { it.copy(width = m.width, height = m.height, fps = m.fps) }
                        }
                    }
                }
            }

            Section("Codec")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("H.264", s.codec == Codec.H264, sub = "works everywhere") { onApply { it.copy(codec = Codec.H264) } }
                Choice("HEVC", s.codec == Codec.HEVC, sub = "sharper, needs HEVC on PC") { onApply { it.copy(codec = Codec.HEVC) } }
            }

            Section("Bitrate")
            var bitrate by remember(s.bitrate) { mutableFloatStateOf(s.bitrate / 1_000_000f) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${bitrate.roundToInt()} Mbps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        bitrate < 10 -> "Weak Wi-Fi"
                        bitrate < 25 -> "Recommended"
                        else -> "Best quality · 5 GHz Wi-Fi"
                    },
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
                )
            }
            Slider(
                value = bitrate, onValueChange = { bitrate = it },
                onValueChangeFinished = { onApply { it.copy(bitrate = (bitrate.roundToInt() * 1_000_000)) } },
                valueRange = 4f..60f,
                colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.Outline),
            )

            Section("Picture orientation")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Named after how the phone stands, since that is what the lock assumes.
                listOf(
                    Triple(OrientationLock.AUTO, "Auto", "follows the phone"),
                    Triple(OrientationLock.PORTRAIT, "Upright", "phone stands tall"),
                    Triple(OrientationLock.LANDSCAPE, "On its side", "lies sideways"),
                ).forEach { (o, label, hint) ->
                    Choice(label, s.orientation == o, sub = hint) { onApply { it.copy(orientation = o) } }
                }
            }

            Section("Image")
            if (lens?.ois == true) Toggle("Optical stabilization (OIS)", s.ois) { v -> onApply { it.copy(ois = v) } }
            if (lens?.eis == true && lens.modes.any { !it.highSpeed }) Toggle("Electronic stabilization (crops the frame)", s.eis) { v -> onApply { it.copy(eis = v) } }
            if (lens?.front == false) Toggle("Flashlight", s.torch) { v -> onApply { it.copy(torch = v) } }

            Section("Paired computers")
            val context = androidx.compose.ui.platform.LocalContext.current
            var pcs by remember { androidx.compose.runtime.mutableStateOf(com.phonecam.net.Identity.trusted(context)) }
            if (pcs.isEmpty()) Text("None yet. Open PhoneCam on your PC to pair.", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            pcs.forEach { pc ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pc.name, style = MaterialTheme.typography.bodyMedium)
                        Text(pc.fingerprint.take(16).chunked(4).joinToString(" "), style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
                    }
                    Text("Forget", style = MaterialTheme.typography.labelLarge, color = Palette.Live,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                            com.phonecam.net.Identity.forget(context, pc.fingerprint)
                            pcs = com.phonecam.net.Identity.trusted(context)
                        }.padding(8.dp))
                }
            }

            Section("Connection")
            Info("Phone address", state.address?.let { "$it:8080" } ?: "Not on Wi-Fi")
            Info("Connected PC", state.pc ?: "—")
            Info("Encoder", state.encoder.ifEmpty { "—" })
            Info("Dropped frames", state.dropped.toString())
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(22.dp))
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Choice(label: String, selected: Boolean, sub: String? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.clip(shape)
            .background(if (selected) Palette.Text else Palette.SurfaceHigh)
            .border(1.dp, if (selected) Palette.Text else Palette.Outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) Palette.Black else Palette.Text)
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = if (selected) Palette.Black.copy(0.6f) else Palette.TextDim)
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelSmall, color = Palette.Text)
    }
}
