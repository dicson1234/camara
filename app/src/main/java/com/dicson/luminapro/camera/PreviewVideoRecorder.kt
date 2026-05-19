package com.dicson.luminapro.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import android.view.TextureView
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * PreviewVideoRecorder — Captura video del preview SIN necesitar una Surface extra de cámara.
 *
 * Funciona capturando bitmaps del TextureView a ~12fps y almacenándolos
 * en un búfer circular de ~3 segundos. Cuando se necesita, codifica los
 * bitmaps a H.264/MP4.
 *
 * Compatible con TODOS los dispositivos Android (no depende de hardware level).
 */
class PreviewVideoRecorder {

    companion object {
        private const val TAG = "PreviewRecorder"
        private const val FPS = 12
        private const val MAX_SECONDS = 3
        private const val MAX_FRAMES = FPS * MAX_SECONDS // 36 frames
        private const val VIDEO_W = 640
        private const val VIDEO_H = 480
    }

    // Almacenamos frames como JPEG comprimido para ahorrar RAM (~30KB cada uno vs 1MB raw)
    private data class CompressedFrame(val jpegBytes: ByteArray, val timestampMs: Long)

    private val buffer = ConcurrentLinkedDeque<CompressedFrame>()
    @Volatile private var capturing = false
    private var captureThread: Thread? = null

    /**
     * Inicia la captura continua del preview del TextureView.
     * Llama a getBitmap() a ~12fps y almacena como JPEG comprimido.
     */
    fun startCapturing(textureView: TextureView) {
        if (capturing) return
        capturing = true
        captureThread = Thread({
            Log.i(TAG, "Captura iniciada (${VIDEO_W}x${VIDEO_H} @ ${FPS}fps)")
            val intervalMs = 1000L / FPS
            while (capturing) {
                try {
                    val bmp = textureView.getBitmap(VIDEO_W, VIDEO_H)
                    if (bmp != null) {
                        val baos = ByteArrayOutputStream(40000)
                        bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                        bmp.recycle()
                        buffer.addLast(CompressedFrame(baos.toByteArray(), System.currentTimeMillis()))
                        while (buffer.size > MAX_FRAMES) {
                            buffer.pollFirst()
                        }
                    }
                } catch (e: Exception) {
                    // TextureView might not be ready yet
                }
                try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { break }
            }
            Log.i(TAG, "Captura detenida")
        }, "PreviewCapture")
        captureThread!!.start()
    }

    fun isReady(): Boolean = buffer.size >= 10

    /**
     * Extrae un video MP4 del búfer circular.
     * Espera [postDelayMs] ms adicionales para capturar el "futuro" después del shutter.
     * El callback recibe los bytes del MP4, o null si falla.
     */
    fun extractVideo(tmpDir: File, postDelayMs: Long, callback: (ByteArray?) -> Unit) {
        Thread({
            try {
                // Esperar para capturar frames del "futuro"
                Thread.sleep(postDelayMs)

                // Snapshot del búfer
                val frames = buffer.toList()
                Log.i(TAG, "Extrayendo ${frames.size} frames")
                if (frames.size < 8) {
                    Log.w(TAG, "Insuficientes frames")
                    callback(null)
                    return@Thread
                }

                // Decodificar JPEGs a Bitmaps
                val bitmaps = mutableListOf<Bitmap>()
                for (f in frames) {
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(f.jpegBytes, 0, f.jpegBytes.size)
                    if (bmp != null) bitmaps.add(bmp)
                }

                if (bitmaps.size < 8) {
                    Log.w(TAG, "Solo ${bitmaps.size} bitmaps válidos")
                    bitmaps.forEach { it.recycle() }
                    callback(null)
                    return@Thread
                }

                val outFile = File(tmpDir, "live_${System.currentTimeMillis()}.mp4")
                val mp4Bytes = encodeBitmapsToMp4(bitmaps, outFile)
                bitmaps.forEach { it.recycle() }

                if (mp4Bytes != null && mp4Bytes.size > 1000) {
                    Log.i(TAG, "✅ MP4: ${mp4Bytes.size} bytes, ${bitmaps.size} frames")
                    callback(mp4Bytes)
                } else {
                    Log.w(TAG, "MP4 vacío o muy pequeño")
                    callback(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extrayendo video", e)
                callback(null)
            }
        }, "VideoExtract").start()
    }

    /**
     * Codifica una lista de Bitmaps a un archivo MP4 válido.
     * Usa MediaCodec con Surface input + Canvas para dibujar cada frame.
     */
    private fun encodeBitmapsToMp4(bitmaps: List<Bitmap>, outFile: File): ByteArray? {
        val w = bitmaps[0].width
        val h = bitmaps[0].height

        // Configurar encoder H.264
        val format = MediaFormat.createVideoFormat("video/avc", w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        if (outFile.exists()) outFile.delete()
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIdx = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()
        val frameDurationUs = 1_000_000L / FPS

        try {
            for (i in bitmaps.indices) {
                // Dibujar bitmap en la Surface del encoder
                val canvas: Canvas = inputSurface.lockCanvas(null)
                canvas.drawBitmap(bitmaps[i], null, Rect(0, 0, w, h), null)
                inputSurface.unlockCanvasAndPost(canvas)

                // Draining parcial
                drainEncoder(encoder, muxer, info, trackIdx, muxerStarted) { t, s ->
                    trackIdx = t; muxerStarted = s
                }

                // Pequeña pausa para dar tiempo al encoder
                Thread.sleep(5)
            }

            // Señal de fin
            encoder.signalEndOfInputStream()

            // Drain final
            var timeout = 50
            while (timeout > 0) {
                drainEncoder(encoder, muxer, info, trackIdx, muxerStarted) { t, s ->
                    trackIdx = t; muxerStarted = s
                }
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                Thread.sleep(10)
                timeout--
            }

            if (muxerStarted) { muxer.stop() }
            muxer.release()
            encoder.stop()
            encoder.release()
            inputSurface.release()

            val bytes = outFile.readBytes()
            outFile.delete()
            return bytes

        } catch (e: Exception) {
            Log.e(TAG, "Encode error", e)
            try { muxer.release() } catch (_: Exception) {}
            try { encoder.stop(); encoder.release() } catch (_: Exception) {}
            try { inputSurface.release() } catch (_: Exception) {}
            outFile.delete()
            return null
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec, muxer: MediaMuxer, info: MediaCodec.BufferInfo,
        currentTrack: Int, muxerStarted: Boolean,
        onTrackAdded: (Int, Boolean) -> Unit
    ) {
        var track = currentTrack
        var started = muxerStarted

        while (true) {
            val idx = encoder.dequeueOutputBuffer(info, 5000)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    started = true
                    onTrackAdded(track, started)
                }
                idx >= 0 -> {
                    val buf = encoder.getOutputBuffer(idx) ?: break
                    if (started && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        muxer.writeSampleData(track, buf, info)
                    }
                    encoder.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
                else -> return // INFO_TRY_AGAIN_LATER
            }
        }
    }

    fun stop() {
        capturing = false
        try { captureThread?.interrupt() } catch (_: Exception) {}
        buffer.clear()
    }
}
