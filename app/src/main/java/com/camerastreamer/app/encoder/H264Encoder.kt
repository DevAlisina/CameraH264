package com.camerastreamer.app.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encodes video frames from a Camera Surface into H.264 (x264/AVC) Annex-B NAL units using
 * Android's hardware-accelerated MediaCodec.
 */
class H264Encoder(
    val width: Int = 1280,
    val height: Int = 720,
    val bitrate: Int = 2_000_000,
    val fps: Int = 30,
    val iFrameInterval: Int = 1
) {
    companion object {
        private const val TAG = "H264Encoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val TIMEOUT_US = 10_000L
    }

    interface FrameListener {
        fun onH264Frame(frameData: ByteArray, isKeyFrame: Boolean)
        fun onSpsPps(spsPpsData: ByteArray)
    }

    var frameListener: FrameListener? = null

    private var mediaCodec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    private val isRunning = AtomicBoolean(false)
    private var encoderThread: Thread? = null

    @Volatile
    var cachedSpsPps: ByteArray? = null
        private set

    /**
     * Initializes and configures the H.264 hardware encoder and creates the input Surface.
     */
    fun start() {
        if (isRunning.get()) return

        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
                // Low-latency and rate control settings
                try {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                } catch (e: Exception) {
                    Log.w(TAG, "CBR mode not supported, falling back to default", e)
                }
                try {
                    setInteger(MediaFormat.KEY_LATENCY, 0) // Realtime low latency
                } catch (_: Exception) {
                }
            }

            val codec = MediaCodec.createEncoderByType(MIME_TYPE)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            // The Surface onto which Camera2 renders
            inputSurface = codec.createInputSurface()
            codec.start()
            mediaCodec = codec
            isRunning.set(true)

            encoderThread = Thread({ drainEncoder() }, "H264EncoderThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            Log.i(TAG, "H264Encoder started: ${width}x${height} @ ${fps}fps, ${bitrate}bps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start H264Encoder", e)
            stop()
            throw e
        }
    }

    /**
     * Requests an immediate IDR Keyframe (sync frame) from the encoder.
     * Crucial when a new client connects so it can decode video immediately.
     */
    fun requestKeyFrame() {
        try {
            mediaCodec?.let { codec ->
                val params = Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
                codec.setParameters(params)
                Log.d(TAG, "Forced key frame requested")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request keyframe", e)
        }
    }

    /**
     * Continuously retrieves encoded NAL units from MediaCodec.
     */
    private fun drainEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning.get()) {
            val codec = mediaCodec ?: break
            try {
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    Log.i(TAG, "Encoder output format changed: $newFormat")
                    extractSpsPpsFromFormat(newFormat)
                } else if (outputBufferIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                        val outData = ByteArray(bufferInfo.size)
                        outputBuffer.get(outData)

                        if (isConfig) {
                            Log.d(TAG, "Received codec config (SPS/PPS) of size ${outData.size}")
                            cachedSpsPps = outData
                            frameListener?.onSpsPps(outData)
                        } else if (bufferInfo.size > 0) {
                            if (isKeyFrame) {
                                // For standalone network stream clients, ensure keyframes carry SPS/PPS
                                val spsPps = cachedSpsPps
                                if (spsPps != null && !containsSpsPps(outData)) {
                                    val fullKeyFrame = ByteArray(spsPps.size + outData.size)
                                    System.arraycopy(spsPps, 0, fullKeyFrame, 0, spsPps.size)
                                    System.arraycopy(outData, 0, fullKeyFrame, spsPps.size, outData.size)
                                    frameListener?.onH264Frame(fullKeyFrame, true)
                                } else {
                                    frameListener?.onH264Frame(outData, true)
                                }
                            } else {
                                frameListener?.onH264Frame(outData, false)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error in drainEncoder", e)
                }
                break
            }
        }
    }

    private fun extractSpsPpsFromFormat(format: MediaFormat) {
        val sps = format.getByteBuffer("csd-0")
        val pps = format.getByteBuffer("csd-1")

        if (sps != null && pps != null) {
            val stream = ByteArrayOutputStream()
            val spsBytes = ByteArray(sps.remaining())
            sps.get(spsBytes)
            val ppsBytes = ByteArray(pps.remaining())
            pps.get(ppsBytes)

            stream.write(spsBytes)
            stream.write(ppsBytes)
            cachedSpsPps = stream.toByteArray()
            cachedSpsPps?.let { frameListener?.onSpsPps(it) }
        }
    }

    private fun containsSpsPps(data: ByteArray): Boolean {
        if (data.size < 5) return false
        // Search for NAL type 7 (SPS)
        for (i in 0 until (data.size - 4).coerceAtMost(32)) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1
            ) {
                val nalType = data[i + 4].toInt() and 0x1F
                if (nalType == 7) return true
            }
        }
        return false
    }

    /**
     * Stops the encoder and cleans up resources.
     */
    fun stop() {
        isRunning.set(false)
        try {
            encoderThread?.interrupt()
            encoderThread?.join(500)
        } catch (_: Exception) {
        }
        encoderThread = null

        try {
            mediaCodec?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaCodec?.release()
        } catch (_: Exception) {
        }
        mediaCodec = null

        try {
            inputSurface?.release()
        } catch (_: Exception) {
        }
        inputSurface = null
        cachedSpsPps = null
        Log.i(TAG, "H264Encoder stopped")
    }
}
