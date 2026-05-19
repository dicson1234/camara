package com.dicson.luminapro.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

/**
 * CircularVideoEncoder
 * 
 * Este es el "Cerebro de la Máquina del Tiempo".
 * Utiliza el hardware nativo (GPU) para codificar video en H.264 de manera ultra eficiente.
 * A diferencia de una grabación normal, no escribe a disco constantemente. En cambio,
 * guarda los últimos "X" segundos en la memoria RAM (Búfer Circular).
 * Si el búfer se llena, borra el fotograma más viejo para hacer espacio al nuevo.
 */
class CircularVideoEncoder(width: Int, height: Int, bitRate: Int, frameRate: Int) {

    private val MIME_TYPE = "video/avc" // H.264 Avanzado
    private val MAX_BUFFER_DURATION_US = 3000000L // 3 Segundos de Live Photo (1.5 antes, 1.5 después)

    private var encoder: MediaCodec
    val inputSurface: Surface // Esta es la superficie que le daremos al LuminaCameraManager

    // Búfer en memoria RAM
    private val frameBuffer = mutableListOf<FrameData>()
    @Volatile private var isRecording = false
    private var actualOutputFormat: MediaFormat? = null

    // Estructura para guardar cada cuadro comprimido
    data class FrameData(val buffer: ByteBuffer, val info: MediaCodec.BufferInfo)

    init {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // Un frame clave cada segundo

        encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        
        // Creamos la entrada. Todo lo que la cámara dibuje aquí, será comprimido mágicamente.
        inputSurface = encoder.createInputSurface()
        encoder.start()
    }

    /**
     * Hilo principal de extracción.
     * Debe llamarse en un thread separado. Constantemente saca los fotogramas ya comprimidos
     * por el MediaCodec y los mete en nuestro arreglo en RAM.
     */
    fun startDraining() {
        if (isRecording) return // Previene la creación de múltiples hilos devoradores de RAM
        isRecording = true
        Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isRecording) {
                val outputBufferId = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                
                if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    actualOutputFormat = encoder.outputFormat
                } else if (outputBufferId >= 0) {
                    val encodedData = encoder.getOutputBuffer(outputBufferId)

                    if (encodedData != null && bufferInfo.size != 0) {
                        // Clonamos el buffer a la RAM porque el MediaCodec va a reusar el original
                        val cloneBuffer = ByteBuffer.allocate(bufferInfo.size)
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        cloneBuffer.put(encodedData)
                        cloneBuffer.flip()

                        val cloneInfo = MediaCodec.BufferInfo().apply {
                            set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                        }

                        // Añadir al búfer circular de manera sincronizada
                        synchronized(frameBuffer) {
                            frameBuffer.add(FrameData(cloneBuffer, cloneInfo))
                            purgeOldFrames()
                        }
                    }
                    encoder.releaseOutputBuffer(outputBufferId, false)
                }
            }
        }.start()
    }

    /**
     * Mantiene la memoria RAM controlada. Borra los cuadros que tengan más de 3 segundos de antigüedad.
     */
    private fun purgeOldFrames() {
        if (frameBuffer.isEmpty()) return
        val newestTimeUs = frameBuffer.last().info.presentationTimeUs
        
        val iterator = frameBuffer.iterator()
        while (iterator.hasNext()) {
            val frame = iterator.next()
            if (newestTimeUs - frame.info.presentationTimeUs > MAX_BUFFER_DURATION_US) {
                iterator.remove() // Desechamos el pasado lejano
            } else {
                break
            }
        }
    }

    /**
     * ¡Magia Extrema! Al disparar la foto, no guardamos el video inmediatamente.
     * Esperamos un tiempo (ej. 1.5s) para seguir grabando y así tener el "Antes y el Después" real.
     */
    fun extractLivePhotoVideo(outputFile: File, postShutterDelayMs: Long = 1500, onVideoReady: (ByteArray?) -> Unit) {
        Thread {
            try {
                // Dejamos que el buffer siga llenándose con los fotogramas posteriores a la foto
                Thread.sleep(postShutterDelayMs)
                val bytes = saveToMp4Internal(outputFile)
                onVideoReady(bytes)
            } catch (e: Exception) {
                Log.e("CircularVideo", "Fallo capturando el futuro", e)
                onVideoReady(null)
            }
        }.start()
    }

    private fun saveToMp4Internal(outputFile: File): ByteArray {
        val formatToUse = actualOutputFormat ?: encoder.outputFormat
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1

        try {
            trackIndex = muxer.addTrack(formatToUse)
            muxer.start()

            synchronized(frameBuffer) {
                val startTimeUs = frameBuffer.firstOrNull()?.info?.presentationTimeUs ?: 0L
                
                for (frame in frameBuffer) {
                    if ((frame.info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        frame.buffer.rewind() 
                        
                        val normalizedInfo = MediaCodec.BufferInfo().apply {
                            set(
                                frame.info.offset,
                                frame.info.size,
                                frame.info.presentationTimeUs - startTimeUs,
                                frame.info.flags
                            )
                        }
                        muxer.writeSampleData(trackIndex, frame.buffer, normalizedInfo)
                    }
                }
                Log.i("CircularVideo", "Empaquetados ${frameBuffer.size} frames en el MP4.")
            }
        } finally {
            try {
                muxer.stop()
                muxer.release()
            } catch (e: Exception) {
                Log.e("CircularVideo", "Muxer release error", e)
            }
        }
        
        return outputFile.readBytes()
    }

    fun stop() {
        isRecording = false
        encoder.stop()
        encoder.release()
    }
}
