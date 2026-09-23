package com.phonecam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonecam.ui.CameraScreen
import com.phonecam.ui.PhoneCamTheme

class MainActivity : ComponentActivity() {
    private var deviceRotation by mutableFloatStateOf(0f)
    private var dimmed by mutableStateOf(false)
    private lateinit var orientation: OrientationEventListener

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.CAMERA] == true) StreamService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Icons turn with the phone while the layout stays portrait, like a camera app.
        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val snapped = (((degrees + 45) / 90) % 4) * 90f
                val target = if (snapped == 270f) -90f else snapped
                if (target != deviceRotation) deviceRotation = target
            }
        }

        setContent {
            PhoneCamTheme {
                val state by Streamer.state.collectAsStateWithLifecycle()
                CameraScreen(
                    state = state,
                    deviceRotation = deviceRotation,
                    onApply = Streamer::apply,
                    onPower = { if (state.phase == Phase.STOPPED) goLive() else StreamService.stop(this) },
                    onPreview = Streamer::setPreview,
                    dimmed = dimmed,
                    onDim = ::dim,
                )
            }
        }
        goLive()
    }

    override fun onResume() {
        super.onResume()
        orientation.enable()
    }

    override fun onPause() {
        orientation.disable()
        super.onPause()
    }

    /** Opening the app is enough to go live; the PC connects on its own. */
    private fun goLive() {
        val needed = buildList {
            add(Manifest.permission.CAMERA)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) StreamService.start(this) else permissions.launch(needed.toTypedArray())
    }

    /** OLED-black screen with minimum brightness: the camera keeps streaming, the battery lasts longer. */
    private fun dim(on: Boolean) {
        dimmed = on
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }
}
