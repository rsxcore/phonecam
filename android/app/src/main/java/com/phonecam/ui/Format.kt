package com.phonecam.ui

import android.hardware.camera2.CameraMetadata
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object Format {
    val ISO_STOPS = listOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400)

    /** Common shutter speeds as denominators of 1/x s. */
    val SHUTTER_STOPS = listOf(8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250, 200, 160, 125, 120, 100, 60, 50, 30, 25, 15)

    fun shutter(ns: Long): String {
        if (ns <= 0) return "—"
        val denominator = 1_000_000_000.0 / ns
        return if (denominator >= 1.5) "1/${denominator.roundToInt()}" else String.format(Locale.US, "%.1fs", ns / 1e9)
    }

    fun ev(steps: Int, step: Float): String {
        val v = steps * step
        return if (abs(v) < 0.01f) "±0" else String.format(Locale.US, "%+.1f", v)
    }

    fun focus(diopters: Float): String = when {
        diopters <= 0.01f -> "∞"
        1f / diopters >= 1f -> String.format(Locale.US, "%.1fm", 1f / diopters)
        else -> "${(100f / diopters).roundToInt()}cm"
    }

    fun zoom(z: Float) = String.format(Locale.US, if (z < 10) "%.1f×" else "%.0f×", z)

    fun mbps(kbps: Int) = String.format(Locale.US, "%.1f Mbps", kbps / 1000f)

    fun awb(mode: Int) = when (mode) {
        CameraMetadata.CONTROL_AWB_MODE_AUTO -> "Auto"
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "2700K"
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "3000K"
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "4000K"
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "5500K"
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "6500K"
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> "7500K"
        CameraMetadata.CONTROL_AWB_MODE_SHADE -> "8000K"
        else -> "Off"
    }

    fun awbName(mode: Int) = when (mode) {
        CameraMetadata.CONTROL_AWB_MODE_AUTO -> "Auto"
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "Tungsten"
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "Warm fluorescent"
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "Fluorescent"
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "Daylight"
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "Cloudy"
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> "Twilight"
        CameraMetadata.CONTROL_AWB_MODE_SHADE -> "Shade"
        else -> "Off"
    }

    /** White-balance presets in warm → cool order. */
    val AWB_ORDER = listOf(
        CameraMetadata.CONTROL_AWB_MODE_AUTO,
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT,
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT,
        CameraMetadata.CONTROL_AWB_MODE_SHADE,
    )

    fun nearestIndex(values: List<Int>, target: Int) = values.indices.minBy { abs(values[it] - target) }
}
