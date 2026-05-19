package com.dicson.luminapro.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * CircularVideoEncoder — Búfer circular de video en RAM
 * Graba continuamente los últimos ~3 segundos de video H.264
 * para empaquetarlos dentro de la Motion Photo al tomar la foto.
 */
class CircularVideoEncoder(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int,
    private val fps: Int
) {
    companion object { private const val TAG = "CircularEncoder" }

    private data class EncodedFrame(
        val data: ByteArray,
        val bufferInfo: MediaCodec.BufferInfo,
        val timestamp: Long
    )

    private val encoder: MediaCodec
    val inputSurface: Surface
    private val circularBuffer = ConcurrentLinkedDeque<EncodedFrame>()
    private val maxBufferDurationUs = 3_000_000L // 3 segundos
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null
    @Volatile private var isRunning = false
    @Volatile private var hasKeyFrame = false
    private var actualOutputFormat: MediaFormat? = null

    init {
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // Keyframe cada segundo para cortes limpios
        }
        encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()
        Log.i(TAG, "Encoder iniciado: ${width}x${height} @ ${bitrate/1000}kbps")
    }

    fun startDraining() {
        if (isRunning) return
        isRunning = true
        encoderThread = HandlerThread("EncoderDrain").apply { start() }
        encoderHandler = Handler(encoderThread!!.looper)
        encoderHandler?.post { drainLoop() }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (isRunning) {
            try {
                val index = encoder.dequeueOutputBuffer(info, 10_000) // 10ms timeout
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        actualOutputFormat = encoder.outputFormat
                        Log.i(TAG, "Formato obtenido: ${actualOutputFormat}")
                    }
                    index >= 0 -> {
                        val buffer = encoder.getOutputBuffer(index) ?: continue
                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            if (isKey) hasKeyFrame = true

                            if (hasKeyFrame) {
                                val data = ByteArray(info.size)
                                buffer.position(info.offset)
                                buffer.get(data, 0, info.size)

                                val frameCopy = MediaCodec.BufferInfo()
                                frameCopy.set(info.offset, info.size, info.presentationTimeUs, info.flags)

                                circularBuffer.addLast(EncodedFrame(data, frameCopy, info.presentationTimeUs))
                                trimBuffer()
                            }
                        }
                        encoder.releaseOutputBuffer(index, false)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) Log.w(TAG, "Drain error: ${e.message}")
            }
        }
    }

    private fun trimBuffer() {
        if (circularBuffer.isEmpty()) return
        val newestTs = circularBuffer.peekLast()?.timestamp ?: return
        while (circularBuffer.size > 2) {
            val oldest = circularBuffer.peekFirst() ?: break
            if (newestTs - oldest.timestamp > maxBufferDurationUs) {
                circularBuffer.pollFirst()
            } else break
        }
    }

    /**
     * Extrae el video del búfer circular como un archivo MP4 válido.
     * Espera [postCaptureDelayMs] para capturar el "futuro" de la Live Photo,
     * luego empaqueta todo en MP4 y devuelve los bytes via callback.
     */
    fun extractLivePhotoVideo(outputFile: File, postCaptureDelayMs: Long, callback: (ByteArray?) -> Unit) {
        val handler = encoderHandler ?: run { callback(null); return }
        // Esperar para capturar el futuro
        handler.postDelayed({
            try {
                val format = actualOutputFormat
                if (format == null || circularBuffer.isEmpty()) {
                    Log.w(TAG, "Sin formato o búfer vacío (format=$format, frames=${circularBuffer.size})")
                    callback(null)
                    return@postDelayed
                }

                // Capturar snapshot de los frames
                val frames = circularBuffer.toList()
                if (frames.size < 3) {
                    Log.w(TAG, "Solo ${frames.size} frames en búfer, insuficiente")
                    callback(null)
                    return@postDelayed
                }

                val baseTimestamp = frames.first().timestamp

                // Crear el MP4 con MediaMuxer
                val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val trackIndex = muxer.addTrack(format)
                muxer.start()

                for (frame in frames) {
                    val buf = ByteBuffer.wrap(frame.data)
                    val info = MediaCodec.BufferInfo()
                    info.set(0, frame.data.size, frame.timestamp - baseTimestamp, frame.bufferInfo.flags)
                    muxer.writeSampleData(trackIndex, buf, info)
                }

                muxer.stop()
                muxer.release()

                val mp4Bytes = outputFile.readBytes()
                Log.i(TAG, "MP4 generado: ${mp4Bytes.size} bytes, ${frames.size} frames")
                callback(mp4Bytes)
            } catch (e: Exception) {
                Log.e(TAG, "Error creando MP4", e)
                callback(null)
            }
        }, postCaptureDelayMs)
    }

    /** Devuelve true si hay suficientes frames para crear una Live Photo */
    fun isReady(): Boolean = hasKeyFrame && circularBuffer.size >= 3 && actualOutputFormat != null

    fun stop() {
        isRunning = false
        try { encoder.stop() } catch (_: Exception) {}
        try { encoder.release() } catch (_: Exception) {}
        try { encoderThread?.quitSafely() } catch (_: Exception) {}
        circularBuffer.clear()
    }
}
