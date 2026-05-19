package com.dicson.luminapro.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface

class LuminaCameraManager(private val context: Context) {

    companion object { private const val TAG = "LuminaCamera" }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var currentCameraId: String? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    var encoderConnected = false; private set

    var isFrontCamera = false; private set
    @Volatile private var isCapturing = false
    var onPhotoCaptured: ((ByteArray) -> Unit)? = null

    private var maxZoom = 1.0f
    private var sensorRect: Rect? = null
    var previewSize: Size = Size(1920, 1080); private set

    fun startBackgroundThread() {
        bgThread?.quitSafely()
        bgThread = HandlerThread("CamBG").apply { start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    @SuppressLint("MissingPermission")
    fun openCamera(previewSurface: Surface, videoSurface: Surface?, front: Boolean) {
        isFrontCamera = front
        val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK

        val camId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
        } ?: run { Log.e(TAG, "No camera $facing"); return }
        currentCameraId = camId

        val chars = cameraManager.getCameraCharacteristics(camId)
        maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
        sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

        // Preview size
        val sizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)
        previewSize = pickPreviewSize(sizes)

        // Capture size: máximo 12MP para compatibilidad amplia
        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)
        val captureSize = jpegSizes
            ?.filter { it.width * it.height <= 12_500_000 }
            ?.maxByOrNull { it.width * it.height }
            ?: jpegSizes?.maxByOrNull { it.width * it.height }
            ?: Size(4000, 3000)
        Log.i(TAG, "Camera $camId: preview=$previewSize, capture=$captureSize, front=$front")

        imageReader?.close()
        imageReader = ImageReader.newInstance(captureSize.width, captureSize.height, ImageFormat.JPEG, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            try {
                val image = reader.acquireLatestImage() ?: run { isCapturing = false; return@setOnImageAvailableListener }
                val buf = image.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                image.close()
                isCapturing = false
                Log.i(TAG, "✅ JPEG: ${bytes.size} bytes")
                if (bytes.size > 1000) onPhotoCaptured?.invoke(bytes)
            } catch (e: Exception) {
                Log.e(TAG, "ImageReader error", e)
                isCapturing = false
            }
        }, bgHandler)

        cameraManager.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(cam: CameraDevice) {
                cameraDevice = cam
                createSession(cam, previewSurface, videoSurface)
            }
            override fun onDisconnected(cam: CameraDevice) { cam.close(); cameraDevice = null }
            override fun onError(cam: CameraDevice, err: Int) {
                Log.e(TAG, "Camera error $err"); cam.close(); cameraDevice = null
            }
        }, bgHandler)
    }

    private fun pickPreviewSize(sizes: Array<Size>?): Size {
        if (sizes == null) return Size(1920, 1080)
        return sizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: sizes.filter { it.width >= 1280 }.minByOrNull { Math.abs(it.width.toFloat() / it.height - 16f / 9f) }
            ?: sizes.maxByOrNull { it.width * it.height }
            ?: Size(1920, 1080)
    }

    private fun createSession(dev: CameraDevice, preview: Surface, video: Surface?) {
        val reader = imageReader ?: return

        // Intentar con 3 surfaces. Si falla, usar 2.
        val all = mutableListOf(preview, reader.surface)
        if (video != null) all.add(video)

        try {
            dev.createCaptureSession(all, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    encoderConnected = video != null
                    captureSession = s
                    Log.i(TAG, "✅ Session OK: ${all.size} surfaces, encoder=$encoderConnected")
                    startPreview(s, dev, preview, video)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.w(TAG, "⚠️ ${all.size} surfaces falló")
                    if (video != null) {
                        // Fallback: solo 2 surfaces
                        encoderConnected = false
                        dev.createCaptureSession(listOf(preview, reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s2: CameraCaptureSession) {
                                captureSession = s2
                                Log.i(TAG, "✅ Fallback 2 surfaces OK")
                                startPreview(s2, dev, preview, null)
                            }
                            override fun onConfigureFailed(s2: CameraCaptureSession) {
                                Log.e(TAG, "❌ Fallback también falló")
                            }
                        }, bgHandler)
                    }
                }
            }, bgHandler)
        } catch (e: Exception) {
            Log.e(TAG, "createSession error", e)
        }
    }

    private fun startPreview(session: CameraCaptureSession, dev: CameraDevice, preview: Surface, video: Surface?) {
        previewBuilder = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(preview)
            if (video != null && encoderConnected) addTarget(video)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
        try {
            session.setRepeatingRequest(previewBuilder!!.build(), null, bgHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Preview error", e)
        }
    }

    fun setZoom(level: Float) {
        val b = previewBuilder ?: return; val s = captureSession ?: return; val r = sensorRect ?: return
        val z = level.coerceIn(1f, maxZoom)
        val cw = r.width() / z; val ch = r.height() / z
        b.set(CaptureRequest.SCALER_CROP_REGION, Rect(
            ((r.width() - cw) / 2).toInt(), ((r.height() - ch) / 2).toInt(),
            ((r.width() + cw) / 2).toInt(), ((r.height() + ch) / 2).toInt()
        ))
        try { s.setRepeatingRequest(b.build(), null, bgHandler) } catch (_: Exception) {}
    }

    fun setFlash(mode: Int) {
        val b = previewBuilder ?: return; val s = captureSession ?: return
        when (mode) {
            0 -> { b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF); b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON) }
            1 -> b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
            2 -> b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
        }
        try { s.setRepeatingRequest(b.build(), null, bgHandler) } catch (_: Exception) {}
    }

    fun setHdr(on: Boolean) {
        val b = previewBuilder ?: return; val s = captureSession ?: return
        if (on) { b.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR); b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE) }
        else b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        try { s.setRepeatingRequest(b.build(), null, bgHandler) } catch (_: Exception) {}
    }

    fun focusAt(x: Float, y: Float, vw: Int, vh: Int) {
        val b = previewBuilder ?: return; val s = captureSession ?: return; val r = sensorRect ?: return
        val fx = (x / vw * r.width()).toInt(); val fy = (y / vh * r.height()).toInt()
        val h = r.width() / 10
        val rect = Rect((fx-h).coerceAtLeast(0), (fy-h).coerceAtLeast(0), (fx+h).coerceAtMost(r.width()), (fy+h).coerceAtMost(r.height()))
        b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)))
        b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)))
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        try { s.setRepeatingRequest(b.build(), null, bgHandler) } catch (_: Exception) {}
    }

    /**
     * Calcula la orientación JPEG correcta.
     * - Trasera: sensorOrientation (usualmente 90°)
     * - Frontal: Espejado para que no salga invertida
     */
    private fun getJpegOrientation(deviceRotation: Int): Int {
        val camId = currentCameraId ?: return 90
        val sensorOri = cameraManager.getCameraCharacteristics(camId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        return if (isFrontCamera) {
            // Frontal: compensar orientación del sensor + rotación del dispositivo
            (sensorOri + deviceRotation) % 360
        } else {
            // Trasera: compensar orientación del sensor + rotación del dispositivo
            (sensorOri - deviceRotation + 360) % 360
        }
    }

    fun takePicture(deviceRotation: Int = 0) {
        val dev = cameraDevice ?: run { Log.e(TAG, "No device"); return }
        val ses = captureSession ?: run { Log.e(TAG, "No session"); return }
        val rdr = imageReader ?: run { Log.e(TAG, "No reader"); return }
        if (isCapturing) { Log.w(TAG, "Busy"); return }
        isCapturing = true
        Log.i(TAG, "📸 Taking picture... front=$isFrontCamera")

        try {
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(rdr.surface)
                set(CaptureRequest.JPEG_QUALITY, 98.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation(deviceRotation))
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                // Copiar zoom del preview
                previewBuilder?.build()?.get(CaptureRequest.SCALER_CROP_REGION)?.let {
                    set(CaptureRequest.SCALER_CROP_REGION, it)
                }
            }.build()

            ses.capture(req, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    Log.i(TAG, "✅ Capture completed")
                }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                    Log.e(TAG, "❌ Capture failed: ${f.reason}")
                    isCapturing = false
                }
            }, bgHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Capture exception", e)
            isCapturing = false
        }
    }

    fun closeCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { bgThread?.quitSafely() } catch (_: Exception) {}
        captureSession = null; cameraDevice = null; imageReader = null
        isCapturing = false; encoderConnected = false
    }
}
