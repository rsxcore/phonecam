package com.phonecam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Handler
import android.util.Log
import android.util.Range
import android.view.Surface

/** Values the auto modes actually chose, shown next to the manual controls. */
data class LiveValues(
    val iso: Int = 0,
    val exposureNs: Long = 0,
    val focusDiopters: Float = 0f,
    val fps: Float = 0f,
)

/**
 * Camera2 wrapper: one camera device and one session with a single output,
 * the GL relay, which fans frames out to the encoder and the preview.
 *
 * Every method must be called on [handler]'s thread.
 */
class CameraEngine(
    context: Context,
    private val handler: Handler,
    private val onLive: (LiveValues) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var lens: Lens? = null
    private var mode: VideoMode? = null
    private var settings = CameraSettings()
    private var output: Surface? = null
    private var sensorArea: Rect? = null
    private var generation = 0

    private var fpsWindowStart = 0L
    private var fpsFrames = 0
    private var measuredFps = 0f
    private var lastLiveReport = 0L

    @SuppressLint("MissingPermission")
    fun open(lens: Lens, settings: CameraSettings, output: Surface) {
        close()
        val gen = ++generation
        this.lens = lens
        this.settings = settings
        this.mode = lens.modes.first { it.width == settings.width && it.height == settings.height && it.fps == settings.fps }
        this.output = output
        sensorArea = manager.getCameraCharacteristics(lens.id).get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        try {
            manager.openCamera(lens.id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (gen != generation) { camera.close(); return }
                    device = camera
                    createSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (gen == generation) { device = null; onError("Camera was taken by another app") }
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (gen == generation) { device = null; onError(describeError(error)) }
                }
            }, handler)
        } catch (e: Exception) {
            onError("Cannot open camera: ${e.message}")
        }
    }

    fun close() {
        generation++
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
    }

    /** Applies settings that do not need a new session. */
    fun update(settings: CameraSettings) {
        this.settings = settings
        repeat()
    }

    private fun createSession() {
        val camera = device ?: return
        val mode = mode ?: return
        val targets = listOfNotNull(output)
        val gen = generation
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (gen != generation || device !== camera) { s.close(); return }
                session = s
                repeat()
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (gen == generation) onError("Camera cannot stream ${mode.label} on this lens")
            }
        }
        try {
            @Suppress("DEPRECATION")
            if (mode.highSpeed) camera.createConstrainedHighSpeedCaptureSession(targets, callback, handler)
            else camera.createCaptureSession(targets, callback, handler)
        } catch (e: Exception) {
            onError("Cannot start camera session: ${e.message}")
        }
    }

    private fun repeat() {
        val s = session ?: return
        val camera = device ?: return
        val mode = mode ?: return
        val lens = lens ?: return
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                output?.let { addTarget(it) }
                val captureFps = if (mode.highSpeed) 120 else mode.fps
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(captureFps, captureFps))
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                applyExposure(this, lens, mode)
                applyFocus(this)
                set(CaptureRequest.CONTROL_AWB_MODE, settings.awbMode)
                set(CaptureRequest.CONTROL_AWB_LOCK, settings.awbLock)
                applyZoom(this)
                if (!mode.highSpeed) {
                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        if (settings.ois) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        if (settings.eis) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                    set(CaptureRequest.FLASH_MODE, if (settings.torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
                }
            }.build()
            if (s is CameraConstrainedHighSpeedCaptureSession) {
                s.setRepeatingBurst(s.createHighSpeedRequestList(request), results, handler)
            } else {
                s.setRepeatingRequest(request, results, handler)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Repeating request failed", e)
            onError("Camera rejected the settings: ${e.message}")
        }
    }

    private fun applyExposure(b: CaptureRequest.Builder, lens: Lens, mode: VideoMode) {
        if (settings.manualExposure && lens.manualSensor && !mode.highSpeed) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, settings.iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, settings.shutterNs)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / mode.fps)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, settings.ev)
            b.set(CaptureRequest.CONTROL_AE_LOCK, settings.aeLock)
            // Flicker from mains-powered lights is the most common artifact indoors.
            b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO)
        }
    }

    private fun applyFocus(b: CaptureRequest.Builder) {
        if (settings.manualFocus) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, settings.focusDiopters)
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
    }

    private fun applyZoom(b: CaptureRequest.Builder) {
        if (Build.VERSION.SDK_INT >= 30) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, settings.zoom)
        } else {
            val area = sensorArea ?: return
            val w = (area.width() / settings.zoom).toInt()
            val h = (area.height() / settings.zoom).toInt()
            b.set(CaptureRequest.SCALER_CROP_REGION, Rect(area.centerX() - w / 2, area.centerY() - h / 2, area.centerX() + w / 2, area.centerY() + h / 2))
        }
    }

    private val results = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val now = System.nanoTime()
            fpsFrames++
            if (fpsWindowStart == 0L) fpsWindowStart = now
            if (now - fpsWindowStart >= 1_000_000_000L) {
                measuredFps = fpsFrames * 1e9f / (now - fpsWindowStart)
                fpsFrames = 0; fpsWindowStart = now
            }
            if (now - lastLiveReport < 250_000_000L) return
            lastLiveReport = now
            onLive(LiveValues(
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0,
                focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f,
                fps = measuredFps,
            ))
        }
    }

    private fun describeError(error: Int) = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE, CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ->
            "Camera is busy in another app"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "Camera is disabled by device policy"
        else -> "Camera failed (code $error)"
    }

    companion object { private const val TAG = "PhoneCam.Camera" }
}
