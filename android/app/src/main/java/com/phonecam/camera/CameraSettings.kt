package com.phonecam.camera

import android.content.Context
import android.hardware.camera2.CameraMetadata
import org.json.JSONObject

enum class Codec(val mime: String, val wire: Int) {
    H264("video/avc", 1), HEVC("video/hevc", 2)
}

/** Which way "up" is for the stream sent to the PC. */
enum class OrientationLock { AUTO, LANDSCAPE, PORTRAIT }

/**
 * Everything the user can change. Fields marked "restart" need a new capture
 * session or encoder; the rest only update the repeating request.
 */
data class CameraSettings(
    val lensId: String = "",                          // restart
    val width: Int = 1920,                            // restart
    val height: Int = 1080,                           // restart
    val fps: Int = 30,                                // restart; 60 heats the phone, so opt-in
    val codec: Codec = Codec.H264,                    // restart
    val bitrate: Int = 20_000_000,
    val manualExposure: Boolean = false,
    val iso: Int = 400,
    val shutterNs: Long = 1_000_000_000L / 60,
    val ev: Int = 0,
    val aeLock: Boolean = false,
    val awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    val awbLock: Boolean = false,
    val manualFocus: Boolean = false,
    val focusDiopters: Float = 0f,
    val zoom: Float = 1f,
    val ois: Boolean = true,
    val eis: Boolean = false,
    val torch: Boolean = false,
    val orientation: OrientationLock = OrientationLock.AUTO,
) {
    fun needsRestart(other: CameraSettings) =
        lensId != other.lensId || width != other.width || height != other.height ||
            fps != other.fps || codec != other.codec || eis != other.eis

    fun toJson(): JSONObject = JSONObject()
        .put("lensId", lensId).put("width", width).put("height", height).put("fps", fps)
        .put("codec", codec.name).put("bitrate", bitrate)
        .put("manualExposure", manualExposure).put("iso", iso).put("shutterNs", shutterNs)
        .put("ev", ev).put("aeLock", aeLock).put("awbMode", awbMode).put("awbLock", awbLock)
        .put("manualFocus", manualFocus).put("focusDiopters", focusDiopters.toDouble())
        .put("zoom", zoom.toDouble()).put("ois", ois).put("eis", eis).put("torch", torch)
        .put("orientation", orientation.name)

    /** Applies a partial update; unknown or malformed keys are ignored. */
    fun merge(j: JSONObject): CameraSettings = copy(
        lensId = j.optString("lensId", lensId),
        width = j.optInt("width", width),
        height = j.optInt("height", height),
        fps = j.optInt("fps", fps),
        codec = runCatching { Codec.valueOf(j.getString("codec")) }.getOrDefault(codec),
        bitrate = j.optInt("bitrate", bitrate).coerceIn(1_000_000, 100_000_000),
        manualExposure = j.optBoolean("manualExposure", manualExposure),
        iso = j.optInt("iso", iso),
        shutterNs = j.optLong("shutterNs", shutterNs),
        ev = j.optInt("ev", ev),
        aeLock = j.optBoolean("aeLock", aeLock),
        awbMode = j.optInt("awbMode", awbMode),
        awbLock = j.optBoolean("awbLock", awbLock),
        manualFocus = j.optBoolean("manualFocus", manualFocus),
        focusDiopters = j.optDouble("focusDiopters", focusDiopters.toDouble()).toFloat(),
        zoom = j.optDouble("zoom", zoom.toDouble()).toFloat(),
        ois = j.optBoolean("ois", ois),
        eis = j.optBoolean("eis", eis),
        torch = j.optBoolean("torch", torch),
        orientation = runCatching { OrientationLock.valueOf(j.getString("orientation")) }.getOrDefault(orientation),
    )

    /** Clamps every value into what [lens] actually supports. */
    fun sanitized(lenses: List<Lens>): CameraSettings {
        val lens = lenses.firstOrNull { it.id == lensId } ?: lenses.first()
        val mode = lens.modes.firstOrNull { it.width == width && it.height == height && it.fps == fps }
            ?: lens.modes.firstOrNull { it.width == 1920 && it.fps == 30 } ?: lens.modes.first()
        return copy(
            lensId = lens.id,
            width = mode.width, height = mode.height, fps = mode.fps,
            manualExposure = manualExposure && lens.manualSensor && !mode.highSpeed,
            iso = lens.iso?.let { iso.coerceIn(it.lower, it.upper) } ?: iso,
            shutterNs = lens.exposureNs?.let { shutterNs.coerceIn(it.lower, minOf(it.upper, 1_000_000_000L / mode.fps)) } ?: shutterNs,
            ev = ev.coerceIn(lens.ev.lower, lens.ev.upper),
            awbMode = if (awbMode in lens.awbModes) awbMode else CameraMetadata.CONTROL_AWB_MODE_AUTO,
            manualFocus = manualFocus && lens.manualFocus,
            focusDiopters = focusDiopters.coerceIn(0f, lens.minFocusDiopters),
            zoom = zoom.coerceIn(1f, lens.maxZoom),
            ois = ois && lens.ois,
            eis = eis && lens.eis && !mode.highSpeed,
        )
    }

    companion object {
        private const val PREFS = "camera"

        fun load(context: Context): CameraSettings {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("settings", null)
            return runCatching { CameraSettings().merge(JSONObject(raw!!)) }.getOrDefault(CameraSettings())
        }

        fun save(context: Context, settings: CameraSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("settings", settings.toJson().toString()).apply()
        }
    }
}
