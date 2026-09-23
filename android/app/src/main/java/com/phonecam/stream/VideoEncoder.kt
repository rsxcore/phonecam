package com.phonecam.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.Surface
import com.phonecam.camera.Codec

/** One encoded access unit, Annex-B. */
class EncodedFrame(val data: ByteArray, val ptsUs: Long, val key: Boolean)

/**
 * Hardware H.264 / HEVC encoder fed directly by the camera through an input
 * surface, so frames never touch the CPU on the phone.
 *
 * [captureFps] may be higher than [fps] (high-speed sessions run at 120); the
 * encoder then drops frames evenly to [fps] itself.
 */
class VideoEncoder(
    val codec: Codec,
    val width: Int,
    val height: Int,
    val fps: Int,
    captureFps: Int,
    bitrate: Int,
    handler: Handler,
    private val onConfig: (ByteArray) -> Unit,
    private val onFrame: (EncodedFrame) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val encoder: MediaCodec
    val inputSurface: Surface
    val name: String
    @Volatile private var released = false

    init {
        val info = pick(codec.mime, width, height)
        name = info.name
        encoder = MediaCodec.createByCodecName(info.name)
        val caps = info.getCapabilitiesForType(codec.mime)
        val format = MediaFormat.createVideoFormat(codec.mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_OPERATING_RATE, captureFps)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 1f)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_LATENCY, 1)
            if (captureFps > fps) setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
            val modes = caps.encoderCapabilities
            val mode = if (modes.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR))
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
            val profile = when (codec) {
                Codec.H264 -> CodecProfileLevel.AVCProfileHigh
                Codec.HEVC -> CodecProfileLevel.HEVCProfileMain
            }
            if (caps.profileLevels.any { it.profile == profile }) setInteger(MediaFormat.KEY_PROFILE, profile)
        }
        encoder.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (released) return
                try {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset).limit(info.offset + info.size)
                        val bytes = ByteArray(info.size).also { buffer.get(it) }
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) onConfig(bytes)
                        else onFrame(EncodedFrame(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0))
                    }
                    codec.releaseOutputBuffer(index, false)
                } catch (e: IllegalStateException) {
                    if (!released) onError("Encoder: ${e.message}")
                }
            }
            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                if (!released) onError("Encoder error: ${e.diagnosticInfo}")
            }
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.i(TAG, "Output format: $format")
            }
        }, handler)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()
        Log.i(TAG, "Started $name ${width}x$height@$fps (capture $captureFps) ${bitrate / 1000} kbps")
    }

    fun requestKeyFrame() {
        if (released) return
        runCatching { encoder.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun setBitrate(bitrate: Int) {
        if (released) return
        runCatching { encoder.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate) }) }
    }

    fun release() {
        if (released) return
        released = true
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        inputSurface.release()
    }

    companion object {
        private const val TAG = "PhoneCam.Encoder"

        /** Prefers a hardware encoder that accepts the size. */
        fun pick(mime: String, width: Int, height: Int): MediaCodecInfo {
            val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                info.isEncoder && mime in info.supportedTypes.map { it.lowercase() } &&
                    runCatching { info.getCapabilitiesForType(mime).videoCapabilities.isSizeSupported(width, height) }.getOrDefault(false)
            }
            return candidates.firstOrNull { it.isHardwareAccelerated && !it.isSoftwareOnly }
                ?: candidates.firstOrNull() ?: error("No $mime encoder for ${width}x$height")
        }

        fun supports(codec: Codec, width: Int, height: Int) =
            runCatching { pick(codec.mime, width, height).isHardwareAccelerated }.getOrDefault(false)
    }
}
