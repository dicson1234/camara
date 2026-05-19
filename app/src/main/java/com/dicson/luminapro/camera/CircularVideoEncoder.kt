package com.dicson.luminapro.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

class CircularVideoEncoder(
    private val width: Int, private val height: Int,
    bitRate: Int, frameRate: Int
) {
    companion object {
        private const val TAG = "CircularVideo"
        private const val MIME_TYPE = "video/avc"
        private const val MAX_BUFFER_DURATION_US = 3_000_000L
    }

    private var encoder: MediaCodec
    val inputSurface: Surface
    private val frameBuffer = mutableListOf<FrameData>()
    @Volatile private var isRecording = false
    @Volatile private var isStopped = false
    private var actualOutputFormat: MediaFormat? = null

    data class FrameData(val buffer: ByteBuffer, val info: MediaCodec.BufferInfo)

    init {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()
    }

    fun startDraining() {
        if (isRecording || isStopped) return
        isRecording = true
        Thread({
            val bufferInfo = MediaCodec.BufferInfo()
            try {
                while (isRecording && !isStopped) {
                    val id: Int
                    try { id = encoder.dequeueOutputBuffer(bufferInfo, 10_000) }
                    catch (e: IllegalStateException) { break }

                    when {
                        id == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            actualOutputFormat = encoder.outputFormat
                        }
                        id >= 0 -> {
                            val data = encoder.getOutputBuffer(id)
                            if (data != null && bufferInfo.size > 0) {
                                val clone = ByteBuffer.allocateDirect(bufferInfo.size)
                                data.position(bufferInfo.offset)
                                data.limit(bufferInfo.offset + bufferInfo.size)
                                clone.put(data)
                                clone.flip()
                                val ci = MediaCodec.BufferInfo().apply {
                                    set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                                }
                                synchronized(frameBuffer) {
                                    frameBuffer.add(FrameData(clone, ci))
                                    purgeOldFrames()
                                }
                            }
                            try { encoder.releaseOutputBuffer(id, false) }
                            catch (e: IllegalStateException) { break }
                        }
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Drain error", e) }
        }, "CircularEncoder-Drain").start()
    }

    private fun purgeOldFrames() {
        if (frameBuffer.size < 2) return
        val newest = frameBuffer.last().info.presentationTimeUs
        val it = frameBuffer.iterator()
        while (it.hasNext()) {
            val f = it.next()
            if (newest - f.info.presentationTimeUs > MAX_BUFFER_DURATION_US) it.remove()
            else break
        }
    }

    fun extractLivePhotoVideo(outputFile: File, delayMs: Long = 1500, cb: (ByteArray?) -> Unit) {
        Thread({
            try {
                Thread.sleep(delayMs)
                cb(saveToMp4(outputFile))
            } catch (e: Exception) {
                Log.e(TAG, "Extract error", e)
                cb(null)
            }
        }, "LivePhoto-Extract").start()
    }

    private fun saveToMp4(outputFile: File): ByteArray? {
        val fmt = actualOutputFormat ?: return null
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val track = muxer.addTrack(fmt)
            muxer.start(); started = true
            val frames: List<FrameData>
            synchronized(frameBuffer) { frames = ArrayList(frameBuffer) }
            if (frames.isEmpty()) return null
            val t0 = frames.first().info.presentationTimeUs
            for (f in frames) {
                if ((f.info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) continue
                f.buffer.rewind()
                val ni = MediaCodec.BufferInfo().apply {
                    set(0, f.info.size, f.info.presentationTimeUs - t0, f.info.flags)
                }
                muxer.writeSampleData(track, f.buffer, ni)
            }
            Log.i(TAG, "Packed ${frames.size} frames")
        } finally {
            if (started) try { muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }
        return if (outputFile.exists() && outputFile.length() > 0) outputFile.readBytes() else null
    }

    fun stop() {
        isRecording = false; isStopped = true
        try { encoder.stop() } catch (_: Exception) {}
        try { encoder.release() } catch (_: Exception) {}
    }
}
