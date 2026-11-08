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
    /** The PC this phone dials ("host" or "host:port"); null means the PC connects to the phone. */
    val pcAddress: String? = null,
    /** Progress or the last failure of dialling [pcAddress]. */
    val dialStatus: String? = null,
)

/**
 * Owns the whole pipeline: camera → hardware encoder → network. Lives as long
 * as [StreamService] is running; the UI only observes [state] and calls
 * [apply] / [setPreview].
 */
object Streamer {
    private const val TAG = "PhoneCam.Streamer"
    private const val PREFS = "phonecam.connection"
    private const val KEY_PC_ADDRESS = "pc_address"
    private const val KEY_BROKEN_MODES = "broken_modes"
    /** A working high-speed session delivers frames well within this. */
    private const val HIGH_SPEED_GRACE_MS = 4000L

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
    /** Rotation measured from the camera's own preview transform, or -1 until the first frame. */
    @Volatile private var baseRotation = -1

    private var thermalListener: android.os.PowerManager.OnThermalStatusChangedListener? = null
    private var recoveries = 0

    private var lastSentFrames = 0L
    private var lastSentBytes = 0L
    private var lastStatsAt = 0L
    @Volatile private var dialing = false
    /** Bumped by every pipeline start, so a delayed check knows whether it is stale. */
    private var pipelineId = 0

    fun start(context: Context) {
        if (thread != null) return
        val ctx = context.applicationContext
        appContext = ctx
        val t = HandlerThread("phonecam-camera").apply { start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        val pcAddress = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PC_ADDRESS, null)
        _state.update { it.copy(phase = Phase.STARTING, error = null, address = localIpv4(), pcAddress = pcAddress) }
        h.post {
            try {
                val lenses = withoutBrokenModes(ctx, CameraCatalog.load(ctx))
                check(lenses.isNotEmpty()) { "No usable camera found" }
                val settings = CameraSettings.load(ctx).sanitized(lenses)
                _state.update { it.copy(lenses = lenses, settings = settings) }
                logCapabilities(ctx, lenses)
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
                startDialer(s)
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
            dialing = false
            server?.stop(); server = null
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
        thread?.quitSafely()
        thread = null; handler = null
        _state.update { UiState(lenses = it.lenses, settings = it.settings, pcAddress = it.pcAddress) }
    }

    /** Drops the current PC connection so it is made again from scratch. */
    fun reconnect() {
        _state.update { it.copy(dialStatus = null) }
        server?.dropClient()
    }

    /** Sets the PC to dial, or null to go back to waiting for the PC to connect. */
    fun setPcAddress(context: Context, address: String?) {
        val clean = address?.trim()?.takeIf { it.isNotEmpty() }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PC_ADDRESS, clean).apply()
        _state.update { it.copy(pcAddress = clean, dialStatus = null) }
    }

    /**
     * While no PC is connected, keeps dialling the PC the user entered. A PC
     * behind the same router can still connect to the phone as before.
     */
    private fun startDialer(s: StreamServer) {
        dialing = true
        kotlin.concurrent.thread(name = "phonecam-dial", isDaemon = true) {
            while (dialing && server === s) {
                val target = _state.value.pcAddress
                if (target != null && !s.connected) {
                    val host = target.substringBeforeLast(':', target)
                    val port = target.substringAfterLast(':', "").toIntOrNull() ?: Protocol.PC_PORT
                    _state.update { it.copy(dialStatus = "Connecting to $host:$port…") }
                    val error = s.dial(host, port)
                    _state.update { it.copy(dialStatus = error?.let { e -> "$host:$port: $e" }) }
                }
                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
            }
        }
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
            // Many HALs ignore exposure compensation while AE is locked, so the EV
            // slider seemed dead with "Lock exposure" on. Moving EV unlocks AE.
            val next = change(current).sanitized(_state.value.lenses)
                .let { if (it.ev != current.ev && it.aeLock && current.aeLock) it.copy(aeLock = false) else it }
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
        baseRotation = -1
        encoder?.release(); encoder = null
        // The old session is closed: until the new one reports, there are no frames.
        // (tick() treats a non-zero fps as "recovered", so it must not linger.)
        _state.update { it.copy(live = com.phonecam.camera.LiveValues()) }
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
            val r = GlRelay(mode.width, mode.height, mode.fps) { base ->
                handler?.post { baseRotation = base; updateRotation() }
            }
            relay = r
            r.setEncoder(enc.inputSurface)
            preview?.let { r.setPreview(it, previewSize.first, previewSize.second) }
            _state.update { it.copy(encoder = "${enc.name} · ${codec.name}") }
            updateRotation()
            engine?.open(lens, st.settings, r.cameraSurface)
            Log.i(TAG, "Pipeline ${mode.label} on ${lens.name} (${lens.id})")
            if (mode.highSpeed) {
                val id = ++pipelineId
                h.postDelayed({ if (id == pipelineId) checkHighSpeedStarted(lens, mode) }, HIGH_SPEED_GRACE_MS)
            } else {
                pipelineId++
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline failed", e)
            // Retry like a camera lost mid-stream: the camera service restarts on
            // its own after a crash, and a hot TECNO CK7n briefly listed no video
            // encoder at all ("No video/avc encoder for 1920x1080") right after
            // the thermal step-down, which used to leave the stream stopped for good.
            cameraFailed("Cannot start ${mode.label}: ${e.message}")
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
            if (_state.value.live.fps > 0) {
                recoveries = 0
                // Frames are flowing, so a start error is history, whichever path
                // recovered (retry, settings change, thermal step-down). Seen on a
                // TECNO CK7n: streaming at 27 fps under a stale "Cannot start" banner.
                _state.update {
                    if (it.phase == Phase.ERROR) it.copy(error = null, phase = if (it.pc != null) Phase.STREAMING else Phase.WAITING) else it
                }
            }
            s.sendState(stateJson())
        }
        h.postDelayed(::tick, 1000)
    }

    /**
     * Some HALs list a high-speed mode they cannot run: a Galaxy S21+ front
     * camera at 1080p 60 opens, then never delivers a frame, and closing that
     * session later makes the camera service's watchdog abort it. When a
     * high-speed mode shows no frames after the grace period, all high-speed
     * modes of that lens are remembered as broken (they share the session type,
     * and each failed try costs a camera service restart), dropped from both
     * UIs, and the stream falls back to 30 fps.
     */
    private fun checkHighSpeedStarted(lens: Lens, mode: com.phonecam.camera.VideoMode) {
        val st = _state.value
        if (st.phase == Phase.ERROR || st.live.fps > 0f) return
        val ctx = appContext ?: return
        Log.w(TAG, "No frames from ${mode.label} on ${lens.name} (${lens.id}); marking its high-speed modes unusable")
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val broken = prefs.getStringSet(KEY_BROKEN_MODES, emptySet()).orEmpty() +
            lens.modes.filter { it.highSpeed }.map { modeKey(lens.id, it) }
        prefs.edit().putStringSet(KEY_BROKEN_MODES, broken).apply()
        val message = "${mode.fps} fps does not work on ${lens.name} on this phone; switched to 30 fps"
        _state.update { it.copy(lenses = withoutBrokenModes(ctx, it.lenses), error = message) }
        apply { it.copy(fps = 30) }
        server?.sendState(hello()) // carries "lenses", so the PC refreshes its mode list
        handler?.postDelayed({ _state.update { if (it.error == message) it.copy(error = null) else it } }, 10_000)
    }

    /** True when some modes are hidden as broken, so the UI can offer [showAllModes]. */
    fun hasHiddenModes(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_BROKEN_MODES, emptySet()).orEmpty().isNotEmpty()

    /** Forgets every mode marked broken, e.g. after a system update fixed the camera. */
    fun showAllModes(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_BROKEN_MODES).apply()
        handler?.post {
            val lenses = runCatching { CameraCatalog.load(ctx) }.getOrNull() ?: return@post
            _state.update { it.copy(lenses = lenses) }
            server?.sendState(hello())
        }
    }

    private fun modeKey(lensId: String, m: com.phonecam.camera.VideoMode) = "$lensId:${m.width}x${m.height}@${m.fps}"

    private fun withoutBrokenModes(ctx: Context, lenses: List<Lens>): List<Lens> {
        val broken = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_BROKEN_MODES, emptySet()).orEmpty()
        return lenses.map { lens ->
            val usable = lens.modes.filterNot { modeKey(lens.id, it) in broken }
            if (usable.isEmpty()) lens else lens.copy(modes = usable)
        }
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
        val h = handler ?: return fail(message)
        val hot = _state.value.thermal >= android.os.PowerManager.THERMAL_STATUS_SEVERE
        if (hot) {
            // A hot phone may refuse the camera or the encoder outright (a TECNO
            // CK7n at SEVERE listed no video encoder at all), and only cooling
            // helps: wait for it without a retry limit, saying why.
            fail("Phone is too hot, waiting for it to cool down. Streaming resumes at 1080p 30 by itself. ($message)")
            coolDown(reopen = false)
            h.postDelayed({ if (handler != null && _state.value.phase == Phase.ERROR) openPipeline() }, 15_000L)
            return
        }
        fail(message)
        // Counts failures since the stream last ran (tick resets it). A crashed
        // camera service takes 5-10 s to come back, so the waits grow: 2, 4, 6,
        // 8 and 10 s, about 30 s in all before giving up.
        if (recoveries >= 5) return
        recoveries++
        h.postDelayed({ if (handler != null && _state.value.phase == Phase.ERROR) openPipeline() }, 2_000L * recoveries)
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
                else if (_state.value.phase == Phase.ERROR && engine != null) {
                    // Cooled down while stopped: start again now, with fresh retries.
                    recoveries = 0
                    openPipeline()
                }
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
        val base = com.phonecam.stream.Orientation.resolveBase(baseRotation, lens.sensorOrientation, lens.front)
        rotation = com.phonecam.stream.Orientation.streamRotation(base, device, lens.front)
    }

    private fun hello(): JSONObject = JSONObject()
        .put("proto", Protocol.VERSION)
        .put("device", Build.MODEL)
        .put("manufacturer", Build.MANUFACTURER)
        .put("android", Build.VERSION.RELEASE)
        // Where the PC can connect back, also when this phone dialled it
        // and the PC only saw the router's address.
        .put("address", localIpv4()?.let { "$it:${Protocol.PORT}" } ?: JSONObject.NULL)
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

    private fun logCapabilities(ctx: Context, lenses: List<Lens>) {
        lenses.forEach { Log.i(TAG, "Lens ${it.id} ${it.name}: ${it.toJson()}") }
        // Vendor effects the phone's own pipeline can apply (bokeh, face retouch,
        // HDR, night) at no cost to the PC; logged to judge a portrait mode.
        if (Build.VERSION.SDK_INT >= 31) {
            val manager = ctx.getSystemService(android.hardware.camera2.CameraManager::class.java)
            val names = mapOf(0 to "AUTOMATIC", 1 to "FACE_RETOUCH", 2 to "BOKEH", 3 to "HDR", 4 to "NIGHT")
            lenses.forEach { lens ->
                val ext = runCatching { manager.getCameraExtensionCharacteristics(lens.id).supportedExtensions }.getOrDefault(emptyList())
                Log.i(TAG, "Extensions ${lens.id} ${lens.name}: ${ext.map { names[it] ?: "#$it" }.ifEmpty { listOf("none") }}")
            }
        }
    }
}
