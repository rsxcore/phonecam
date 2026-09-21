package com.phonecam

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class CameraSource(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val lens: Int,
    private val width: Int,
    private val fps: Int,
    private val quality: Int,
    private val onFrame: (ByteArray, Int) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "phonecam-camera") }
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    @Volatile private var stopped = false
    private var nv21 = ByteArray(0)
    private val encoded = ByteArrayOutputStream(256 * 1024)
    private var lastFrame = 0L
    private var failures = 0
    private var onError: ((String) -> Unit)? = null
    private val orientation = object : OrientationEventListener(context) {
        override fun onOrientationChanged(degrees: Int) {
            if (degrees == ORIENTATION_UNKNOWN) return
            analysis?.targetRotation = when (degrees) {
                in 45..134 -> Surface.ROTATION_270
                in 135..224 -> Surface.ROTATION_180
                in 225..314 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
        }
    }
    fun start(onReady: () -> Unit, onError: (String) -> Unit) {
        this.onError = onError
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (stopped) return@addListener
            try {
                val p = future.get(); provider = p
                val desired = CameraSelector.Builder().requireLensFacing(lens).build()
                val selected = if (p.hasCamera(desired)) desired else CameraSelector.DEFAULT_BACK_CAMERA
                val a = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setTargetRotation(Surface.ROTATION_0)
                    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).setResolutionStrategy(
                        ResolutionStrategy(Size(width, width * 9 / 16), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    ).build()).build()
                analysis = a
                a.setAnalyzer(executor, ::analyze)
                p.bindToLifecycle(owner, selected, a)
                orientation.enable()
                onReady()
            } catch (e: Exception) { if (!stopped) onError(e.message ?: "Camera unavailable") }
        }, ContextCompat.getMainExecutor(context))
    }
    fun stop() {
        stopped = true
        orientation.disable()
        analysis?.let { it.clearAnalyzer(); provider?.unbind(it) }
        analysis = null; provider = null
        executor.shutdown()
    }
    private fun analyze(proxy: ImageProxy) {
        try {
            if (stopped) return
            val now = SystemClock.elapsedRealtimeNanos()
            if (now < lastFrame) return
            lastFrame = maxOf(lastFrame + 1_000_000_000L / fps, now)
            val size = proxy.width * proxy.height * 3 / 2
            if (nv21.size != size) nv21 = ByteArray(size)
            YuvPlanes.pack(proxy.width, proxy.height, proxy.planes.map { it.buffer },
                proxy.planes.map { it.rowStride }.toIntArray(), proxy.planes.map { it.pixelStride }.toIntArray(), nv21)
            encoded.reset()
            check(YuvImage(nv21, ImageFormat.NV21, proxy.width, proxy.height, null)
                .compressToJpeg(Rect(0, 0, proxy.width, proxy.height), quality, encoded))
            if (!stopped) onFrame(encoded.toByteArray(), proxy.imageInfo.rotationDegrees)
            failures = 0
        } catch (e: Exception) {
            if (++failures == 30) ContextCompat.getMainExecutor(context).execute {
                if (!stopped) onError?.invoke("Cannot encode camera frames: ${e.message}")
            }
        } finally { proxy.close() }
    }
}
