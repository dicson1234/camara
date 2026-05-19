package com.dicson.luminapro.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * CircularVideoEncoder — Búfer circular de video H.264
 *
 * ARQUITECTURA:
 * - drainThread: hilo dedicado que drena frames del encoder (loop bloqueante)
 * - extractThread: hilo separado para extraer video (NO bloquea el drain)
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
        val flags: Int,
        val presentationTimeUs: Long
    )

    private val encoder: MediaCodec
    val inputSurface: Surface
    private val circularBuffer = ConcurrentLinkedDeque<EncodedFrame>()
    private val maxBufferDurationUs = 3_000_000L // 3 segundos
    @Volatile private var isRunning = false
    @Volatile private var hasKeyFrame = false
    @Volatile private var outputFormat: MediaFormat? = null
    private var drainThread: Thread? = null

    init {
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()
        Log.i(TAG, "Encoder creado: ${width}x${height}")
    }

    fun startDraining() {
        if (isRunning) return
        isRunning = true
        drainThread = Thread({
            Log.i(TAG, "Drain loop iniciado")
            val info = MediaCodec.BufferInfo()
            while (isRunning) {
                try {
                    val idx = encoder.dequeueOutputBuffer(info, 10_000)
                    when {
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            outputFormat = encoder.outputFormat
                            Log.i(TAG, "Format: ${outputFormat}")
                        }
                        idx >= 0 -> {
                            val buf = encoder.getOutputBuffer(idx)
                            if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                if (isKey) hasKeyFrame = true
                                if (hasKeyFrame) {
                                    val data = ByteArray(info.size)
                                    buf.position(info.offset)
                                    buf.get(data)
                                    circularBuffer.addLast(EncodedFrame(data, info.flags, info.presentationTimeUs))
                                    trimBuffer()
                                }
                            }
                            encoder.releaseOutputBuffer(idx, false)
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning) Log.w(TAG, "Drain error: ${e.message}")
                }
            }
            Log.i(TAG, "Drain loop terminado")
        }, "EncoderDrain")
        drainThread!!.start()
    }

    private fun trimBuffer() {
        if (circularBuffer.size < 2) return
        val newest = circularBuffer.peekLast()?.presentationTimeUs ?: return
        while (circularBuffer.size > 2) {
            val oldest = circularBuffer.peekFirst() ?: break
            if (newest - oldest.presentationTimeUs > maxBufferDurationUs) {
                circularBuffer.pollFirst()
            } else break
        }
    }

    /**
     * Extrae el video del búfer circular.
     * Ejecuta en un HILO SEPARADO (NO bloquea el drain loop).
     */
    fun extractVideo(outputFile: File, postDelayMs: Long, callback: (ByteArray?) -> Unit) {
        Thread({
            try {
                // Esperar para capturar frames del "futuro"
                Thread.sleep(postDelayMs)

                val fmt = outputFormat
                if (fmt == null) { Log.w(TAG, "Sin formato de encoder"); callback(null); return@Thread }

                val frames = circularBuffer.toList()
                Log.i(TAG, "Extrayendo ${frames.size} frames")
                if (frames.size < 5) { Log.w(TAG, "Muy pocos frames"); callback(null); return@Thread }

                val baseTs = frames.first().presentationTimeUs

                // Crear MP4
                if (outputFile.exists()) outputFile.delete()
                val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val track = muxer.addTrack(fmt)
                muxer.start()

                val info = MediaCodec.BufferInfo()
                for (frame in frames) {
                    info.set(0, frame.data.size, frame.presentationTimeUs - baseTs, frame.flags)
                    muxer.writeSampleData(track, ByteBuffer.wrap(frame.data), info)
                }

                muxer.stop()
                muxer.release()

                val bytes = outputFile.readBytes()
                outputFile.delete()
                Log.i(TAG, "✅ MP4 listo: ${bytes.size} bytes, ${frames.size} frames")
                callback(bytes)
            } catch (e: Exception) {
                Log.e(TAG, "Error extrayendo video", e)
                callback(null)
            }
        }, "VideoExtract").start()
    }

    fun isReady(): Boolean = hasKeyFrame && circularBuffer.size >= 5 && outputFormat != null

    fun stop() {
        isRunning = false
        try { drainThread?.join(1000) } catch (_: Exception) {}
        try { encoder.stop() } catch (_: Exception) {}
        try { encoder.release() } catch (_: Exception) {}
        circularBuffer.clear()
    }
}
