package com.phonecam.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
import android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaRecorder
import android.os.Build
import android.util.Range
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject

/** One video mode the phone can stream: resolution plus output frame rate. */
data class VideoMode(val width: Int, val height: Int, val fps: Int, val highSpeed: Boolean) {
    val label get() = "${if (height >= 2160) "4K" else "${height}p"} · $fps fps"
    fun toJson() = JSONObject().put("width", width).put("height", height).put("fps", fps).put("highSpeed", highSpeed)
}

/** Everything the UI and the PC need to know to show valid controls for a lens. */
data class Lens(
    val id: String,
    val facing: Int,
    val name: String,
    val focalMm: Float,
    val sensorOrientation: Int,
    val manualSensor: Boolean,
    val iso: Range<Int>?,
    val exposureNs: Range<Long>?,
    val ev: Range<Int>,
    val evStep: Float,
    val minFocusDiopters: Float,
    val awbModes: IntArray,
    val ois: Boolean,
    val eis: Boolean,
    val maxZoom: Float,
    val modes: List<VideoMode>,
) {
    val front get() = facing == CameraCharacteristics.LENS_FACING_FRONT
    val manualFocus get() = minFocusDiopters > 0f

    fun toJson() = JSONObject()
        .put("id", id).put("name", name).put("front", front).put("focalMm", focalMm.toDouble())
        .put("manualSensor", manualSensor)
        .put("iso", iso?.let { JSONArray().put(it.lower).put(it.upper) })
        .put("exposureNs", exposureNs?.let { JSONArray().put(it.lower).put(it.upper) })
        .put("ev", JSONArray().put(ev.lower).put(ev.upper)).put("evStep", evStep.toDouble())
        .put("minFocusDiopters", minFocusDiopters.toDouble())
        .put("awbModes", JSONArray(awbModes.toList()))
        .put("ois", ois).put("eis", eis).put("maxZoom", maxZoom.toDouble())
        .put("modes", JSONArray(modes.map { it.toJson() }))
}

object CameraCatalog {
    /** Standard 16:9 streaming sizes, largest first. */
    private val WANTED = listOf(Size(3840, 2160), Size(2560, 1440), Size(1920, 1080), Size(1280, 720))

    fun load(context: Context): List<Lens> {
        val manager = context.getSystemService(CameraManager::class.java)
        val all = manager.cameraIdList.mapNotNull { id ->
            runCatching { id to manager.getCameraCharacteristics(id) }.getOrNull()
        }
        // A logical multi-camera duplicates lenses that are also exposed on their
        // own, and cannot be manually controlled per lens; skip it when the
        // physical cameras are listed separately.
        val ids = all.map { it.first }.toSet()
        val physical = all.filter { (_, c) ->
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA !in caps || c.physicalCameraIds.none { it in ids }
        }
        val lenses = physical.mapNotNull { (id, c) -> describe(id, c) }
        val backs = lenses.filter { !it.front }.sortedBy { it.focalMm }
        val main = backs.maxByOrNull { it.modes.size * 100 + (if (it.manualSensor) 10 else 0) }
        return lenses.map { lens ->
            val name = when {
                lens.front -> "Front"
                lens == main -> "Main"
                lens.focalMm < (main?.focalMm ?: 0f) -> "Ultra-wide"
                else -> "Telephoto"
            }
            lens.copy(name = name)
        }.sortedBy { listOf("Main", "Ultra-wide", "Telephoto", "Front").indexOf(it.name) }
    }

    private fun describe(id: String, c: CameraCharacteristics): Lens? {
        val facing = c.get(CameraCharacteristics.LENS_FACING) ?: return null
        if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) return null
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val encoderSizes = map.getOutputSizes(MediaRecorder::class.java)?.toSet() ?: emptySet()
        val fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val maxFixedFps = fpsRanges.filter { it.lower == it.upper }.maxOfOrNull { it.upper } ?: 30

        val modes = mutableListOf<VideoMode>()
        for (size in WANTED) {
            if (size !in encoderSizes) continue
            val minDuration = map.getOutputMinFrameDuration(MediaRecorder::class.java, size)
            val sizeFps = if (minDuration > 0) (1_000_000_000L / minDuration).toInt() else maxFixedFps
            val fps = minOf(sizeFps, maxFixedFps)
            if (fps >= 60) modes += VideoMode(size.width, size.height, 60, false)
            modes += VideoMode(size.width, size.height, minOf(fps, 30), false)
        }
        // Constrained high-speed sessions run the sensor at 120 fps; the encoder
        // keeps every second frame, which gives a true 60 fps stream on phones
        // whose regular sessions stop at 30.
        if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO in caps) {
            for (size in map.highSpeedVideoSizes) {
                if (size !in WANTED || modes.any { it.width == size.width && it.height == size.height && it.fps == 60 }) continue
                if (map.getHighSpeedVideoFpsRangesFor(size).any { it.upper == 120 && it.lower == 120 }) {
                    modes += VideoMode(size.width, size.height, 60, true)
                }
            }
        }
        if (modes.isEmpty()) return null
        modes.sortWith(compareByDescending<VideoMode> { it.width }.thenByDescending { it.fps })

        return Lens(
            id = id,
            facing = facing,
            name = "",
            focalMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            manualSensor = REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
            iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureNs = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            ev = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0),
            evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 1f,
            minFocusDiopters = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            awbModes = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf(),
            ois = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in
                (c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()),
            eis = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in
                (c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()),
            maxZoom = (if (Build.VERSION.SDK_INT >= 30) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper else null)
                ?: c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f,
            modes = modes,
        )
    }
}
