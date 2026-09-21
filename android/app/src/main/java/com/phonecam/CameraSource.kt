package com.phonecam

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The phone's camera, as a source of JPEG frames.
 *
 * A frame arrives with the rotation still needed to stand it upright:
 * `onFrame(jpeg, degrees)`. The phone deliberately does *not* apply it.
 * CameraX can hand back a JPEG but cannot rotate one, so the only rotation
 * available here is decode, rotate, re-encode — three passes over every frame
 * on the one device in this system that is battery-powered and has no spare
 * core. The PC has to decode the frame anyway, so the rotation happens there,
 * where it costs a memcpy.
 *
 * Frames are *not* mirrored, even on the front camera: mirroring the capture
 * rather than the preview is how you get text that reads backwards.
 */
class CameraSource(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val onFrame: (jpeg: ByteArray, rotationDegrees: Int) -> Unit,
) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "phonecam-camera").apply { isDaemon = true }
    }

    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null

    private val lensFacing: Int = CameraSelector.LENS_FACING_FRONT

    /**
     * The provider is fetched asynchronously, so the outcome is reported
     * through callbacks rather than by throwing — a missing camera is a thing
     * to show the user, not a crash.
     */
    fun start(onReady: () -> Unit, onError: (String) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                bind(p)
                onReady()
            } catch (e: Exception) {
                onError(e.message ?: e.toString())
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        analysis?.clearAnalyzer()
        analysis = null
        provider?.unbindAll()
        provider = null
        executor.shutdown()
    }

    private fun bind(p: ProcessCameraProvider) {
        p.unbindAll()

        // Ask for 720p and accept whatever is nearest. The list of supported
        // sizes varies wildly between phones, and a wrong guess here shows up
        // as a black screen rather than as an error.
        val selector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(TARGET, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()

        val a = ImageAnalysis.Builder()
            // Drop frames we cannot keep up with. A backlog would show up as
            // lag that grows without bound rather than as a lower frame rate.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_NV21)
            .setResolutionSelector(selector)
            .build()

        a.setAnalyzer(executor) { proxy -> analyze(proxy) }
        analysis = a

        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        p.bindToLifecycle(owner, cameraSelector, a)
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val nv21 = toNv21(proxy) ?: return
            val jpeg = encode(nv21, proxy.width, proxy.height) ?: return
            onFrame(jpeg, proxy.imageInfo.rotationDegrees)
        } catch (e: Exception) {
            // A single bad frame is not worth tearing the stream down for.
        } finally {
            proxy.close()
        }
    }

    /**
     * The plane as a packed NV21 buffer, which is the only layout [YuvImage]
     * accepts. CameraX normally returns rows with no padding, in which case the
     * buffer is already correct and gets copied once.
     */
    private fun toNv21(proxy: ImageProxy): ByteArray? {
        val plane = proxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val width = proxy.width
        val height = proxy.height
        val rowStride = plane.rowStride

        if (rowStride == width) {
            val out = ByteArray(buffer.remaining())
            buffer.get(out)
            return out
        }

        // Padded rows. NV21 chroma is interleaved at one byte per pixel, so all
        // 1.5*height rows are `width` bytes wide and luma and chroma repack the
        // same way.
        val rows = height * 3 / 2
        val out = ByteArray(width * rows)
        val row = ByteArray(rowStride)
        for (r in 0 until rows) {
            val start = r * rowStride
            if (start >= buffer.limit()) break
            buffer.position(start)
            val n = minOf(rowStride, buffer.remaining())
            buffer.get(row, 0, n)
            System.arraycopy(row, 0, out, r * width, minOf(width, n))
        }
        return out
    }

    private fun encode(nv21: ByteArray, width: Int, height: Int): ByteArray? {
        // YuvImage reads exactly this many bytes. A short buffer does not fail
        // cleanly — it reads past the end and emits a torn frame — so check.
        if (nv21.size < width * height * 3 / 2) return null

        val image = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream(nv21.size / 3)
        if (!image.compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, out)) return null
        return out.toByteArray()
    }

    private companion object {
        val TARGET = Size(1280, 720)
        const val JPEG_QUALITY = 80
    }
}
