package com.phonecam.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.phonecam.Phase
import com.phonecam.UiState
import com.phonecam.camera.CameraSettings

@Composable
fun CameraScreen(
    state: UiState,
    deviceRotation: Float,
    onApply: ((CameraSettings) -> CameraSettings) -> Unit,
    onPower: () -> Unit,
    onPreview: (android.view.Surface?, Int, Int) -> Unit,
    dimmed: Boolean,
    onDim: (Boolean) -> Unit,
) {
    var control by rememberSaveable { mutableStateOf<ProControl?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val iconRotation by animateFloatAsState(-deviceRotation, tween(300), label = "iconRotation")
    val s = state.settings
    val lens = state.lenses.firstOrNull { it.id == s.lensId }

    Box(Modifier.fillMaxSize().background(Palette.Black)) {
        // Live preview, fitted to the sensor aspect ratio.
        if (state.phase != Phase.STOPPED && lens != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PreviewSurface(
                    onSurface = onPreview,
                    modifier = Modifier.fillMaxWidth().aspectRatio(s.height.toFloat() / s.width).clip(RoundedCornerShape(20.dp)),
                )
            }
        } else {
            IdleArt(state)
        }

        // Top scrim + status.
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(state)
                Spacer(Modifier.weight(1f))
                RoundIcon(Icons.Rounded.Tune, "Settings", iconRotation) { settingsOpen = true }
            }
            if (state.thermal >= android.os.PowerManager.THERMAL_STATUS_MODERATE || state.throttled) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.throttled) "Phone got hot: switched to 1080p30 to keep streaming"
                    else "Phone is getting warm. Remove the case or lower the frame rate.",
                    style = MaterialTheme.typography.labelMedium, color = Palette.Waiting,
                )
            }
            if (state.phase == Phase.STREAMING || state.phase == Phase.WAITING) {
                Spacer(Modifier.height(8.dp))
                Text(
                    buildString {
                        append(if (s.height >= 2160) "4K" else "${s.height}p"); append(s.fps)
                        append(" · "); append(s.codec.name.replace("H264", "H.264"))
                        if (state.phase == Phase.STREAMING) {
                            append(" · "); append(Format.mbps(state.kbps))
                            append(" · "); append(state.sentFps.toInt()); append(" fps")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall, color = Palette.TextDim,
                )
            }
        }

        // Bottom: control panel, pro bar, lens switcher, actions.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(
                visible = control != null && lens != null,
                enter = fadeIn() + slideInVertically { it / 3 },
                exit = fadeOut() + slideOutVertically { it / 3 },
            ) {
                val c = control
                if (c != null && lens != null) ControlPanel(c, s, state.live, lens, onApply)
            }
            if (lens != null) {
                ProBar(s, state.live, lens, control, iconRotation) { control = if (control == it) null else it }
                Spacer(Modifier.height(14.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIcon(Icons.Rounded.DarkMode, "Screen off", iconRotation, size = 52) { onDim(true) }
                Spacer(Modifier.weight(1f))
                PowerButton(state.phase, onPower)
                Spacer(Modifier.weight(1f))
                LensSwitcher(state, iconRotation, onApply)
            }
        }

        if (settingsOpen) SettingsSheet(state, onApply) { settingsOpen = false }
        state.pairing?.let { PairingDialog(it) }
        if (dimmed) DimOverlay(state) { onDim(false) }
    }
}

@Composable
private fun PreviewSurface(onSurface: (android.view.Surface?, Int, Int) -> Unit, modifier: Modifier) {
    // The preview is drawn by the GL relay, upright for a portrait screen, at
    // whatever size the view has; it never touches the camera session.
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) = Unit
                    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) = onSurface(h.surface, w, hh)
                    override fun surfaceDestroyed(h: SurfaceHolder) = onSurface(null, 0, 0)
                })
            }
        },
    )
}

@Composable
private fun IdleArt(state: UiState) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(96.dp).clip(RoundedCornerShape(28.dp)).background(Palette.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(34.dp).border(3.dp, Palette.Text, CircleShape))
            Box(Modifier.align(Alignment.TopEnd).padding(18.dp).size(10.dp).clip(CircleShape).background(Palette.Live))
        }
        Spacer(Modifier.height(24.dp))
        Text("PhoneCam", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            state.error ?: "Camera is off. Tap the power button to go live.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.error != null) Palette.Live else Palette.TextDim,
        )
    }
}

@Composable
private fun StatusPill(state: UiState) {
    val (color, text) = when (state.phase) {
        Phase.STREAMING -> Palette.Live to "LIVE · ${state.pc}"
        Phase.WAITING -> Palette.Waiting to "Waiting for PC · ${state.address ?: "no Wi-Fi"}"
        Phase.STARTING -> Palette.TextDim to "Starting camera…"
        Phase.ERROR -> Palette.Live to (state.error ?: "Error")
        Phase.STOPPED -> Palette.TextDim to "Off"
    }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(1f, 0.25f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    Row(
        Modifier.clip(CircleShape).background(Palette.Glass).border(1.dp, Palette.Outline, CircleShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp).clip(CircleShape)
                .graphicsLayer { this.alpha = if (state.phase == Phase.STREAMING || state.phase == Phase.WAITING) alpha else 1f }
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
fun RoundIcon(icon: ImageVector, description: String, rotation: Float, size: Int = 44, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .background(if (active) Palette.Accent else Palette.Glass)
            .border(1.dp, Palette.Outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size((size * 0.48f).dp).rotate(rotation), tint = if (active) Palette.Black else Palette.Text)
    }
}

@Composable
private fun PowerButton(phase: Phase, onClick: () -> Unit) {
    val on = phase != Phase.STOPPED
    Box(
        Modifier.size(76.dp).clip(CircleShape).border(3.dp, Palette.Text, CircleShape).padding(6.dp)
            .clip(CircleShape).background(if (on) Palette.Live else Palette.Text).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.PowerSettingsNew, if (on) "Stop" else "Go live", tint = if (on) Palette.Text else Palette.Black, modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun LensSwitcher(state: UiState, rotation: Float, onApply: ((CameraSettings) -> CameraSettings) -> Unit) {
    val backs = state.lenses.filter { !it.front }
    val current = state.lenses.firstOrNull { it.id == state.settings.lensId }
    val front = state.lenses.firstOrNull { it.front }
    if (current?.front == true || backs.size <= 1) {
        RoundIcon(Icons.Rounded.Cameraswitch, "Switch camera", rotation, size = 52) {
            val target = if (current?.front == true) backs.firstOrNull() else front
            if (target != null) onApply { it.copy(lensId = target.id, zoom = 1f) }
        }
        return
    }
    val main = backs.firstOrNull { it.name == "Main" } ?: backs.first()
    Row(
        Modifier.clip(CircleShape).background(Palette.Glass).border(1.dp, Palette.Outline, CircleShape).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        backs.sortedBy { it.focalMm }.forEach { lens ->
            val selected = lens.id == current?.id
            val label = Format.zoom(lens.focalMm / main.focalMm).replace(".0×", "×").replace("0.", ".")
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (selected) Palette.SurfaceHigh else Color.Transparent)
                    .clickable { onApply { it.copy(lensId = lens.id, zoom = 1f) } },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, Modifier.rotate(rotation), style = MaterialTheme.typography.labelMedium,
                    color = if (selected) Palette.Accent else Palette.Text, fontWeight = FontWeight.Bold)
            }
        }
        if (front != null) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { onApply { it.copy(lensId = front.id, zoom = 1f) } }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Cameraswitch, "Front camera", Modifier.size(20.dp).rotate(rotation), tint = Palette.Text)
            }
        }
    }
}

@Composable
private fun DimOverlay(state: UiState, onWake: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Palette.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onWake),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (state.phase == Phase.STREAMING) Palette.Live else Palette.Waiting))
            Spacer(Modifier.height(10.dp))
            AnimatedContent(state.phase, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "dim") { phase ->
                Text(
                    if (phase == Phase.STREAMING) "Streaming · tap to wake" else "Waiting for PC · tap to wake",
                    style = MaterialTheme.typography.labelSmall, color = Color(0x55FFFFFF),
                )
            }
        }
    }
}

/** A horizontally scrolling row of chips; kept here so the bottom area reads top to bottom. */
@Composable
fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** First connection from a PC: the only moment PhoneCam ever asks anything. */
@Composable
private fun PairingDialog(request: com.phonecam.net.PairRequest) {
    Box(
        Modifier.fillMaxSize().background(Color(0xB3000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).clip(RoundedCornerShape(28.dp)).background(Palette.Surface)
                .border(1.dp, Palette.Outline, RoundedCornerShape(28.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Palette.SurfaceHigh), contentAlignment = Alignment.Center) {
                Icon(androidx.compose.material.icons.Icons.Rounded.Computer, null, tint = Palette.Accent, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("Connect to this PC?", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text("“${request.pcName}” wants to use your camera.", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
            Spacer(Modifier.height(20.dp))
            Text(
                request.code.chunked(3).joinToString(" "),
                style = MaterialTheme.typography.titleLarge.copy(fontSize = androidx.compose.ui.unit.TextUnit(34f, androidx.compose.ui.unit.TextUnitType.Sp), letterSpacing = androidx.compose.ui.unit.TextUnit(4f, androidx.compose.ui.unit.TextUnitType.Sp)),
                fontWeight = FontWeight.Bold, color = Palette.Text,
            )
            Spacer(Modifier.height(6.dp))
            Text("Make sure your PC shows the same code.", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DialogButton("Deny", primary = false, modifier = Modifier.weight(1f)) { request.decide(false) }
                DialogButton("Allow", primary = true, modifier = Modifier.weight(1f)) { request.decide(true) }
            }
            Spacer(Modifier.height(10.dp))
            Text("You only do this once per PC.", style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
        }
    }
}

@Composable
private fun DialogButton(label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(52.dp).clip(CircleShape).background(if (primary) Palette.Accent else Palette.SurfaceHigh).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (primary) Palette.Black else Palette.Text)
    }
}
