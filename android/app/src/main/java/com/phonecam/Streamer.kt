package com.phonecam

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import com.phonecam.camera.CameraCatalog
import com.phonecam.camera.CameraEngine
import com.phonecam.camera.CameraSettings
import com.phonecam.camera.Lens
import com.phonecam.camera.LiveValues
import com.phonecam.camera.OrientationLock
import com.phonecam.net.Protocol
import com.phonecam.net.StreamServer
import com.phonecam.stream.GlRelay
import com.phonecam.stream.VideoEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class Phase { STOPPED, STARTING, WAITING, STREAMING, ERROR }

data class UiState(
    val phase: Phase = Phase.STOPPED,
    val error: String? = null,
    val lenses: List<Lens> = emptyList(),
    val settings: CameraSettings = CameraSettings(),
    val live: LiveValues = LiveValues(),
    val pc: String? = null,
    val address: String? = null,
    val sentFps: Float = 0f,
    val kbps: Int = 0,
    val dropped: Long = 0,
    val encoder: String = "",
    val pairing: com.phonecam.net.PairRequest? = null,
    /** PowerManager.THERMAL_STATUS_*; 0 = cool. */
    val thermal: Int = 0,
    /** Set when PhoneCam lowered the quality by itself to cool the phone down. */
    val throttled: Boolean = false,
)

/**
 * Owns the whole pipeline: camera → hardware encoder → network. Lives as long
 * as [StreamService] is running; the UI only observes [state] and calls
 * [apply] / [setPreview].
 */
object Streamer {
    private const val TAG = "PhoneCam.Streamer"

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var appContext: Context? = null
    private var engine: CameraEngine? = null
    private var encoder: VideoEncoder? = null
    private var relay: GlRelay? = null
    private var server: StreamServer? = null
    private var orientation: OrientationEventListener? = null
    private var preview: Surface? = null
    private var previewSize = 0 to 0

    @Volatile private var deviceDegrees = 0
    @Volatile private var lastLandscape = 90
    @Volatile private var rotation = 0

    private var thermalListener: android.os.PowerManager.OnThermalStatusChangedListener? = null
    private var recoveries = 0
    private var lastHealthyAt = 0L

    private var lastSentFrames = 0L
    private var lastSentBytes = 0L
    private var lastStatsAt = 0L

    fun start(context: Context) {
        if (thread != null) return
        val ctx = context.applicationContext
        appContext = ctx
        val t = HandlerThread("phonecam-camera").apply { start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        _state.update { it.copy(phase = Phase.STARTING, error = null, address = localIpv4()) }
        h.post {
            try {
                val lenses = CameraCatalog.load(ctx)
                check(lenses.isNotEmpty()) { "No usable camera found" }
                val settings = CameraSettings.load(ctx).sanitized(lenses)
                _state.update { it.copy(lenses = lenses, settings = settings) }
                logCapabilities(lenses)
                val s = StreamServer(
                    context = ctx,
                    hello = ::hello,
                    onPairRequest = { request -> _state.update { it.copy(pairing = request) } },
                    onControl = { json -> json.optJSONObject("set")?.let { set -> apply { it.merge(set) } } },
                    onClient = { pc -> _state.update { it.copy(pc = pc, phase = phaseFor(pc)) } },
                    requestKeyFrame = { encoder?.requestKeyFrame() },
                )
                s.start()
                server = s
                engine = CameraEngine(ctx, h, onLive = { live -> _state.update { it.copy(live = live) } }, onError = ::cameraFailed)
                startOrientation(ctx)
                startThermal(ctx)
                openPipeline()
                _state.update { it.copy(phase = phaseFor(it.pc)) }
                h.postDelayed(::tick, 1000)
            } catch (e: Exception) {
                Log.e(TAG, "Start failed", e)
                fail(if (e is java.net.BindException) "Port ${Protocol.PORT} is busy" else e.message ?: e.toString())
            }
        }
    }

    fun stop() {
        val h = handler ?: return
        val done = CountDownLatch(1)
        h.post {
            orientation?.disable(); orientation = null
            thermalListener?.let { appContext?.getSystemService(android.os.PowerManager::class.java)?.removeThermalStatusListener(it) }
            thermalListener = null
            engine?.close(); engine = null
            relay?.release(); relay = null
            encoder?.release(); encoder = null
            server?.stop(); server = null
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
        thread?.quitSafely()
        thread = null; handler = null
        _state.update { UiState(lenses = it.lenses, settings = it.settings) }
    }

    /** Updates settings from the UI or the PC. */
    fun apply(change: (CameraSettings) -> CameraSettings) {
        val h = handler
        if (h == null) {
            // Not streaming: just remember the choice for next time.
            appContext?.let { ctx ->
                val next = change(_state.value.settings)
                CameraSettings.save(ctx, next)
                _state.update { it.copy(settings = next) }
            }
            return
        }
        h.post {
            val current = _state.value.settings
            val next = change(current).sanitized(_state.value.lenses)
            if (next == current) return@post
            _state.update { it.copy(settings = next, throttled = it.throttled && !next.needsRestart(current)) }
            appContext?.let { CameraSettings.save(it, next) }
            updateRotation()
            if (next.needsRestart(current)) {
                openPipeline()
            } else {
                engine?.update(next)
                if (next.bitrate != current.bitrate) encoder?.setBitrate(next.bitrate)
            }
            server?.sendState(stateJson())
        }
    }

    /**
     * Called by the activity's SurfaceView with its buffer size. Blocks until
     * the relay has let go of a dying surface.
     */
    fun setPreview(surface: Surface?, width: Int = 0, height: Int = 0) {
        preview = surface
        previewSize = width to height
        runCatching { relay?.setPreview(surface, width, height) }
    }

    private fun openPipeline() {
        val h = handler ?: return
        _state.update { if (it.phase == Phase.ERROR) it.copy(error = null, phase = if (it.pc != null) Phase.STREAMING else Phase.WAITING) else it }
        val st = _state.value
        val lens = st.lenses.first { it.id == st.settings.lensId }
        val mode = lens.modes.first { it.width == st.settings.width && it.height == st.settings.height && it.fps == st.settings.fps }
        engine?.close()
        relay?.release(); relay = null
        encoder?.release(); encoder = null
        try {
            val codec = if (VideoEncoder.supports(st.settings.codec, mode.width, mode.height)) st.settings.codec
                else com.phonecam.camera.Codec.H264
            val enc = VideoEncoder(
                codec, mode.width, mode.height, mode.fps,
                captureFps = mode.fps,
                bitrate = st.settings.bitrate,
                handler = h,
                onConfig = { csd -> server?.setConfig(Protocol.config(codec.wire, mode.width, mode.height, mode.fps, csd)) },
                onFrame = { frame -> server?.submit(frame, rotation) },
                onError = ::fail,
            )
            encoder = enc
            // Announce the new stream now: some encoders (Qualcomm c2) never emit a
            // separate codec-config buffer and put SPS/PPS into each key frame instead.
            server?.setConfig(Protocol.config(codec.wire, mode.width, mode.height, mode.fps, ByteArray(0)))
            val r = GlRelay(mode.width, mode.height, mode.fps)
            relay = r
            r.setEncoder(enc.inputSurface)
            preview?.let { r.setPreview(it, previewSize.first, previewSize.second) }
            _state.update { it.copy(encoder = "${enc.name} · ${codec.name}") }
            updateRotation()
            engine?.open(lens, st.settings, r.cameraSurface)
            Log.i(TAG, "Pipeline ${mode.label} on ${lens.name} (${lens.id})")
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline failed", e)
            fail("Cannot start ${mode.label}: ${e.message}")
        }
    }

    private fun tick() {
        val h = handler ?: return
        val s = server
        if (s != null) {
            val now = System.nanoTime()
            val frames = s.sentFrames.get()
            val bytes = s.sentBytes.get()
            if (lastStatsAt != 0L) {
                val dt = (now - lastStatsAt) / 1e9f
                _state.update {
                    it.copy(
                        sentFps = (frames - lastSentFrames) / dt,
                        kbps = ((bytes - lastSentBytes) * 8 / 1000 / dt).toInt(),
                        dropped = s.droppedFrames.get(),
                        address = localIpv4(),
                    )
                }
            }
            lastSentFrames = frames; lastSentBytes = bytes; lastStatsAt = now
            if (_state.value.phase != Phase.ERROR && _state.value.live.fps > 0) lastHealthyAt = now
            s.sendState(stateJson())
        }
        h.postDelayed(::tick, 1000)
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        _state.update { it.copy(phase = Phase.ERROR, error = message) }
    }

    /**
     * The camera HAL can drop the device at any time (overheating, another app
     * taking the camera). Reopen it after a pause instead of leaving the PC
     * with a frozen stream; after overheating, only once the phone has cooled.
     */
    private fun cameraFailed(message: String) {
        fail(message)
        val h = handler ?: return
        if (System.nanoTime() - lastHealthyAt > 60_000_000_000L) recoveries = 0
        if (recoveries >= 5) return
        recoveries++
        val hot = _state.value.thermal >= android.os.PowerManager.THERMAL_STATUS_SEVERE
        if (hot) coolDown(reopen = false)
        h.postDelayed({ if (handler != null && _state.value.phase == Phase.ERROR) openPipeline() }, if (hot) 15_000L else 2_000L)
    }

    /** Steps down to the cheapest good-looking mode: 1080p30, manual controls intact. */
    private fun coolDown(reopen: Boolean = true) {
        val st = _state.value
        val lens = st.lenses.firstOrNull { it.id == st.settings.lensId } ?: return
        val mode = lens.modes.firstOrNull { it.width == st.settings.width && it.height == st.settings.height && it.fps == st.settings.fps }
        if (mode == null || (!mode.highSpeed && mode.height <= 1080 && mode.fps <= 30)) return
        val safe = lens.modes.firstOrNull { it.height == 1080 && it.fps == 30 } ?: lens.modes.last()
        Log.w(TAG, "Phone is hot: switching ${mode.label} -> ${safe.label}")
        val next = st.settings.copy(width = safe.width, height = safe.height, fps = safe.fps)
        _state.update { it.copy(settings = next, throttled = true) }
        if (reopen) openPipeline()
        server?.sendState(stateJson())
    }

    private fun startThermal(ctx: Context) {
        val power = ctx.getSystemService(android.os.PowerManager::class.java) ?: return
        val listener = android.os.PowerManager.OnThermalStatusChangedListener { status ->
            handler?.post {
                _state.update { it.copy(thermal = status) }
                if (status >= android.os.PowerManager.THERMAL_STATUS_SEVERE) coolDown()
                server?.sendState(stateJson())
            }
        }
        power.addThermalStatusListener(ctx.mainExecutor, listener)
        thermalListener = listener
    }

    private fun phaseFor(pc: String?) = when {
        _state.value.phase == Phase.ERROR || _state.value.phase == Phase.STOPPED -> _state.value.phase
        pc != null -> Phase.STREAMING
        else -> Phase.WAITING
    }

    private fun startOrientation(ctx: Context) {
        orientation = object : OrientationEventListener(ctx) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val snapped = ((degrees + 45) / 90 % 4) * 90
                if (snapped == deviceDegrees) return
                deviceDegrees = snapped
                if (snapped == 90 || snapped == 270) lastLandscape = snapped
                updateRotation()
            }
        }.also { it.enable() }
    }

    /** Clockwise rotation the PC must apply to the sensor image to make it upright. */
    private fun updateRotation() {
        val st = _state.value
        val lens = st.lenses.firstOrNull { it.id == st.settings.lensId } ?: return
        val device = when (st.settings.orientation) {
            OrientationLock.AUTO -> deviceDegrees
            OrientationLock.LANDSCAPE -> lastLandscape
            OrientationLock.PORTRAIT -> 0
        }
        rotation = if (lens.front) (lens.sensorOrientation + device) % 360
            else (lens.sensorOrientation - device + 360) % 360
    }

    private fun hello(): JSONObject = JSONObject()
        .put("proto", Protocol.VERSION)
        .put("device", Build.MODEL)
        .put("manufacturer", Build.MANUFACTURER)
        .put("android", Build.VERSION.RELEASE)
        .put("lenses", JSONArray(_state.value.lenses.map { it.toJson() }))
        .put("state", stateJson())

    private fun stateJson(): JSONObject {
        val st = _state.value
        return JSONObject()
            .put("settings", st.settings.toJson())
            .put("live", JSONObject().put("iso", st.live.iso).put("exposureNs", st.live.exposureNs)
                .put("focusDiopters", st.live.focusDiopters.toDouble()).put("cameraFps", st.live.fps.toDouble()))
            .put("sentFps", st.sentFps.toDouble()).put("kbps", st.kbps).put("dropped", st.dropped)
            .put("encoder", st.encoder)
            .put("thermal", st.thermal).put("throttled", st.throttled)
            .put("error", st.error ?: JSONObject.NULL)
    }

    private fun logCapabilities(lenses: List<Lens>) {
        lenses.forEach { Log.i(TAG, "Lens ${it.id} ${it.name}: ${it.toJson()}") }
    }
}
