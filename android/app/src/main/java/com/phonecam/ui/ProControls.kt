package com.phonecam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phonecam.camera.CameraSettings
import com.phonecam.camera.Lens
import com.phonecam.camera.LiveValues
import kotlin.math.roundToInt

enum class ProControl(val label: String) { ISO("ISO"), SHUTTER("S"), EV("EV"), WB("WB"), FOCUS("F"), ZOOM("ZOOM") }

@Composable
fun ProBar(
    s: CameraSettings,
    live: LiveValues,
    lens: Lens,
    selected: ProControl?,
    rotation: Float,
    onSelect: (ProControl) -> Unit,
) {
    ChipRow {
        ProControl.entries.forEach { c ->
            val enabled = when (c) {
                ProControl.ISO, ProControl.SHUTTER -> lens.manualSensor && !isHighSpeed(s, lens)
                ProControl.FOCUS -> lens.manualFocus
                ProControl.ZOOM -> lens.maxZoom > 1f
                else -> true
            }
            if (!enabled) return@forEach
            val (value, manual) = when (c) {
                ProControl.ISO -> (if (s.manualExposure) "${s.iso}" else "${live.iso}") to s.manualExposure
                ProControl.SHUTTER -> Format.shutter(if (s.manualExposure) s.shutterNs else live.exposureNs) to s.manualExposure
                ProControl.EV -> Format.ev(s.ev, lens.evStep) to (s.ev != 0 && !s.manualExposure)
                ProControl.WB -> Format.awb(s.awbMode) to (s.awbMode != android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO)
                ProControl.FOCUS -> (if (s.manualFocus) Format.focus(s.focusDiopters) else "AF") to s.manualFocus
                ProControl.ZOOM -> Format.zoom(s.zoom) to (s.zoom > 1.01f)
            }
            ProChip(c.label, value, manual, selected == c, rotation) { onSelect(c) }
        }
    }
}

@Composable
private fun ProChip(label: String, value: String, manual: Boolean, selected: Boolean, rotation: Float, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.clip(shape)
            .background(if (selected) Palette.Text else Palette.Glass)
            .border(1.dp, if (manual && !selected) Palette.Accent else Palette.Outline, shape)
            .clickable(onClick = onClick)
            .widthIn(min = 62.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = if (selected) Palette.Black.copy(alpha = 0.6f) else if (manual) Palette.Accent else Palette.TextDim)
        Text(value, style = MaterialTheme.typography.labelLarge, color = if (selected) Palette.Black else Palette.Text, maxLines = 1)
    }
}

@Composable
fun ControlPanel(
    control: ProControl,
    s: CameraSettings,
    live: LiveValues,
    lens: Lens,
    onApply: ((CameraSettings) -> CameraSettings) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)
            .clip(RoundedCornerShape(22.dp)).background(Palette.Glass).border(1.dp, Palette.Outline, RoundedCornerShape(22.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        when (control) {
            ProControl.ISO -> {
                val stops = Format.ISO_STOPS.filter { lens.iso == null || it in lens.iso.lower..lens.iso.upper }
                val current = if (s.manualExposure) s.iso else live.iso.takeIf { it > 0 } ?: 400
                StepControl("ISO", "$current", stops.map { "$it" }, Format.nearestIndex(stops, current), auto = !s.manualExposure,
                    onAuto = { onApply { it.copy(manualExposure = false) } }) { i ->
                    onApply { it.copy(manualExposure = true, iso = stops[i], shutterNs = if (it.manualExposure) it.shutterNs else live.exposureNs.coerceAtLeast(1_000_000)) }
                }
            }
            ProControl.SHUTTER -> {
                val maxNs = 1_000_000_000L / s.fps
                val stops = Format.SHUTTER_STOPS.map { 1_000_000_000L / it }
                    .filter { it <= maxNs && (lens.exposureNs == null || it >= lens.exposureNs.lower) }
                val current = if (s.manualExposure) s.shutterNs else live.exposureNs.takeIf { it > 0 } ?: maxNs
                val index = stops.indices.minBy { kotlin.math.abs(stops[it] - current) }
                StepControl("Shutter", Format.shutter(current), stops.map { Format.shutter(it) }, index, auto = !s.manualExposure,
                    onAuto = { onApply { it.copy(manualExposure = false) } }) { i ->
                    onApply { it.copy(manualExposure = true, shutterNs = stops[i], iso = if (it.manualExposure) it.iso else live.iso.takeIf { v -> v > 0 } ?: it.iso) }
                }
                Hint("Keep 1/${s.fps * 2} for natural motion blur. Faster = sharper but darker.")
            }
            ProControl.EV -> {
                val steps = (lens.ev.lower..lens.ev.upper).toList()
                StepControl("Exposure", Format.ev(s.ev, lens.evStep), steps.map { Format.ev(it, lens.evStep) }, steps.indexOf(s.ev).coerceAtLeast(0),
                    auto = s.ev == 0, autoLabel = "Reset", onAuto = { onApply { it.copy(ev = 0) } }) { i ->
                    onApply { it.copy(ev = steps[i]) }
                }
                if (s.manualExposure) Hint("EV applies to auto exposure. Switch ISO or shutter back to Auto to use it.")
                Toggle("Lock exposure", s.aeLock) { v -> onApply { it.copy(aeLock = v) } }
            }
            ProControl.WB -> {
                val modes = Format.AWB_ORDER.filter { it in lens.awbModes }
                Text("White balance", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
                Spacer(Modifier.height(10.dp))
                ChipRow {
                    modes.forEach { m ->
                        val on = s.awbMode == m
                        Column(
                            Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Palette.Text else Palette.SurfaceHigh)
                                .clickable { onApply { it.copy(awbMode = m) } }.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(Format.awbName(m), style = MaterialTheme.typography.labelMedium, color = if (on) Palette.Black else Palette.Text)
                            Text(Format.awb(m), style = MaterialTheme.typography.labelSmall, color = if (on) Palette.Black.copy(0.6f) else Palette.TextDim)
                        }
                    }
                }
                Toggle("Lock white balance", s.awbLock) { v -> onApply { it.copy(awbLock = v) } }
            }
            ProControl.FOCUS -> {
                val max = lens.minFocusDiopters
                val value = if (s.manualFocus) s.focusDiopters else live.focusDiopters
                SliderControl("Focus", Format.focus(value), value / max, auto = !s.manualFocus, autoLabel = "AF",
                    onAuto = { onApply { it.copy(manualFocus = false) } }) { f ->
                    onApply { it.copy(manualFocus = true, focusDiopters = f * max) }
                }
                Row(Modifier.fillMaxWidth()) {
                    Text("∞", style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
                    Spacer(Modifier.weight(1f))
                    Text("Close-up", style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
                }
            }
            ProControl.ZOOM -> {
                val max = lens.maxZoom.coerceAtMost(8f)
                SliderControl("Zoom", Format.zoom(s.zoom), (s.zoom - 1f) / (max - 1f), auto = s.zoom <= 1.01f, autoLabel = "1×",
                    onAuto = { onApply { it.copy(zoom = 1f) } }) { f ->
                    onApply { it.copy(zoom = 1f + f * (max - 1f)) }
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, value: String, auto: Boolean, autoLabel: String, onAuto: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
        Spacer(Modifier.width(10.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.clip(CircleShape).background(if (auto) Palette.Accent else Palette.SurfaceHigh)
                .clickable(onClick = onAuto).padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(if (auto && autoLabel == "Auto") "AUTO" else autoLabel.uppercase(), style = MaterialTheme.typography.labelMedium,
                color = if (auto) Palette.Black else Palette.Text, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StepControl(
    title: String, value: String, labels: List<String>, index: Int, auto: Boolean,
    autoLabel: String = "Auto", onAuto: () -> Unit, onPick: (Int) -> Unit,
) {
    Header(title, value, auto, autoLabel, onAuto)
    if (labels.size < 2) return
    Slider(
        value = index.toFloat(),
        onValueChange = { v -> val i = v.roundToInt().coerceIn(0, labels.lastIndex); if (i != index || auto) onPick(i) },
        valueRange = 0f..labels.lastIndex.toFloat(),
        steps = (labels.size - 2).coerceAtLeast(0),
        colors = sliderColors(auto),
    )
    Row(Modifier.fillMaxWidth()) {
        Text(labels.first(), style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
        Spacer(Modifier.weight(1f))
        Text(labels.last(), style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
    }
}

@Composable
private fun SliderControl(
    title: String, value: String, fraction: Float, auto: Boolean,
    autoLabel: String = "Auto", onAuto: () -> Unit, onChange: (Float) -> Unit,
) {
    Header(title, value, auto, autoLabel, onAuto)
    Slider(value = fraction.coerceIn(0f, 1f), onValueChange = onChange, colors = sliderColors(auto))
}

@Composable
private fun sliderColors(auto: Boolean) = SliderDefaults.colors(
    thumbColor = if (auto) Palette.Text else Palette.Accent,
    activeTrackColor = if (auto) Palette.TextDim else Palette.Accent,
    inactiveTrackColor = Palette.Outline,
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
)

@Composable
private fun Hint(text: String) {
    Spacer(Modifier.height(6.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
}

@Composable
fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).clickable { onChange(!value) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        androidx.compose.material3.Switch(
            checked = value, onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedTrackColor = Palette.Accent, checkedThumbColor = Palette.Black,
                uncheckedTrackColor = Palette.SurfaceHigh, uncheckedThumbColor = Palette.TextDim, uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

fun isHighSpeed(s: CameraSettings, lens: Lens) =
    lens.modes.firstOrNull { it.width == s.width && it.height == s.height && it.fps == s.fps }?.highSpeed == true
