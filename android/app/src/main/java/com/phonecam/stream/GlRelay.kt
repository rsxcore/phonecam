package com.phonecam.stream

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * GPU relay between the camera and its consumers.
 *
 * The camera writes into one [SurfaceTexture]; every frame is then drawn to
 * the encoder surface (sensor orientation, at exactly the target frame rate)
 * and to the on-screen preview (upright, mirrored like a mirror for the front
 * camera). Having a single camera output means the preview can come and go
 * without reconfiguring the capture session, and frame pacing is decided here
 * with a jitter tolerance instead of by the encoder, which on some chips
 * ignores the requested rate.
 */
class GlRelay(
    private val width: Int,
    private val height: Int,
    fps: Int,
    private val onBaseRotation: (Int) -> Unit = {},
) {
    private val thread = HandlerThread("phonecam-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val minIntervalNs = (1_000_000_000L / fps) * 3 / 4

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewSize = 0 to 0
    private var program = 0
    private var texture = 0
    private lateinit var surfaceTexture: SurfaceTexture
    lateinit var cameraSurface: Surface
        private set

    private val stMatrix = FloatArray(16)
    private var lastEncodedNs = 0L
    private var loggedMatrix = false
    @Volatile private var released = false

    /** Frames drawn to the encoder, for statistics. */
    @Volatile var encodedFrames = 0L
        private set

    init {
        runBlocking {
            setupEgl()
            texture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            program = buildProgram()
            surfaceTexture = SurfaceTexture(texture).apply {
                setDefaultBufferSize(width, height)
                setOnFrameAvailableListener({ onFrame() }, handler)
            }
            cameraSurface = Surface(surfaceTexture)
        }
    }

    fun setEncoder(surface: Surface?) = runBlocking {
        if (encoderSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encoderSurface)
        encoderSurface = surface?.let { windowSurface(it) } ?: EGL14.EGL_NO_SURFACE
        lastEncodedNs = 0
    }

    /** [w]×[h] is the preview buffer size; the image is fitted inside it. */
    fun setPreview(surface: Surface?, w: Int, h: Int) = runBlocking {
        if (previewSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, previewSurface)
        previewSurface = surface?.takeIf { it.isValid }?.let { windowSurface(it) } ?: EGL14.EGL_NO_SURFACE
        previewSize = w to h
    }

    fun release() {
        if (released) return
        released = true
        runBlocking {
            surfaceTexture.setOnFrameAvailableListener(null)
            listOf(encoderSurface, previewSurface, pbuffer).forEach { if (it != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, it) }
            encoderSurface = EGL14.EGL_NO_SURFACE; previewSurface = EGL14.EGL_NO_SURFACE
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            cameraSurface.release()
            surfaceTexture.release()
        }
        thread.quitSafely()
    }

    private fun onFrame() {
        if (released) return
        surfaceTexture.updateTexImage()
        val ts = surfaceTexture.timestamp
        surfaceTexture.getTransformMatrix(stMatrix)
        if (!loggedMatrix) {
            loggedMatrix = true
            val base = Orientation.baseRotation(stMatrix)
            Log.i(TAG, "SurfaceTexture matrix ${stMatrix.joinToString { "%.1f".format(it) }} -> base rotation $base")
            if (base >= 0) onBaseRotation(base)
        }

        // Encoder: raw sensor orientation, paced to the target rate. A 3/4
        // interval threshold keeps every frame of a jittery 30 fps camera but
        // exactly every second frame of a 120 fps high-speed session.
        if (encoderSurface != EGL14.EGL_NO_SURFACE && ts - lastEncodedNs >= minIntervalNs) {
            lastEncodedNs = ts
            makeCurrent(encoderSurface)
            GLES20.glViewport(0, 0, width, height)
            draw(SENSOR_MATRIX, IDENTITY)
            EGLExt.eglPresentationTimeANDROID(display, encoderSurface, ts)
            EGL14.eglSwapBuffers(display, encoderSurface)
            encodedFrames++

            // Preview follows the encoded frames, so it shows exactly what the PC gets.
            if (previewSurface != EGL14.EGL_NO_SURFACE) {
                makeCurrent(previewSurface)
                val (pw, ph) = previewSize
                GLES20.glViewport(0, 0, pw, ph)
                draw(stMatrix, IDENTITY)
                if (!EGL14.eglSwapBuffers(display, previewSurface)) {
                    Log.w(TAG, "Preview surface lost")
                    EGL14.eglDestroySurface(display, previewSurface)
                    previewSurface = EGL14.EGL_NO_SURFACE
                }
            }
        }
    }

    private fun draw(texMatrix: FloatArray, posMatrix: FloatArray) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTex = GLES20.glGetAttribLocation(program, "aTex")
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, texMatrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uPos"), 1, false, posMatrix, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, QUAD.position(0))
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, QUAD.position(2))
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun setupEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No EGL config" }
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        makeCurrent(pbuffer)
    }

    private fun windowSurface(surface: Surface): EGLSurface =
        EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)

    private fun makeCurrent(surface: EGLSurface) {
        EGL14.eglMakeCurrent(display, surface, surface, context)
    }

    private fun buildProgram(): Int {
        fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src); GLES20.glCompileShader(it)
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
        GLES20.glLinkProgram(p)
        return p
    }

    private fun <T> runBlocking(block: () -> T): T {
        if (Thread.currentThread() == thread) return block()
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        handler.post { result = runCatching(block); done.countDown() }
        check(done.await(3, TimeUnit.SECONDS)) { "GL thread timed out" }
        return result!!.getOrThrow()
    }

    companion object {
        private const val TAG = "PhoneCam.GL"


        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private val IDENTITY = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

        /** Buffer rows are top-down, GL textures bottom-up: flip vertically, nothing else. */
        private val SENSOR_MATRIX = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f,
        )

        private val QUAD: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
        }

        private const val VERTEX = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uPos;
            uniform mat4 uTex;
            varying vec2 vTex;
            void main() { gl_Position = uPos * aPos; vTex = (uTex * aTex).xy; }
        """
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTex;
            varying vec2 vTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """
    }
}
