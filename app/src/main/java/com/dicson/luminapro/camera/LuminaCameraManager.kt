package com.dicson.luminapro.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
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
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var currentCameraId: String? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var activePreviewSurface: Surface? = null
    private var encoderSurface: Surface? = null
    var encoderConnected = false; private set

    var isFrontCamera = false; private set
    @Volatile private var isCapturing = false
    var onPhotoCaptured: ((ByteArray) -> Unit)? = null

    private var maxZoom = 1.0f
    private var sensorArraySize: Rect? = null
    var detectedPreviewSize: Size = Size(1920, 1080); private set

    fun startBackgroundThread() {
        backgroundThread?.quitSafely()
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    @SuppressLint("MissingPermission")
    fun openCamera(previewSurface: Surface, videoSurface: Surface?, w: Int, h: Int, front: Boolean = false) {
        isFrontCamera = front
        activePreviewSurface = previewSurface
        encoderSurface = videoSurface
        val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK

        val camId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
        } ?: run { Log.e(TAG, "No camera found for facing=$facing"); return }
        currentCameraId = camId

        val chars = cameraManager.getCameraCharacteristics(camId)
        maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
        sensorArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        Log.i(TAG, "HW Level: $hwLevel (0=LIMITED, 1=FULL, 2=LEGACY, 3=L3)")

        // Preview size
        val previewSizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)
        detectedPreviewSize = chooseBestPreviewSize(previewSizes, w, h)

        // Captura: usar resolución más segura en dispositivos LIMITED
        val captureSizes = map.getOutputSizes(ImageFormat.JPEG)
        val maxCapture = if (hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL) {
            captureSizes?.maxByOrNull { it.width * it.height } ?: Size(w, h)
        } else {
            // En LIMITED, usar máximo 12MP para evitar problemas con múltiples surfaces
            captureSizes?.filter { it.width * it.height <= 12_000_000 }
                ?.maxByOrNull { it.width * it.height }
                ?: captureSizes?.maxByOrNull { it.width * it.height }
                ?: Size(w, h)
        }
        Log.i(TAG, "Preview: ${detectedPreviewSize}, Capture: ${maxCapture}, Zoom: ${maxZoom}x")

        imageReader?.close()
        imageReader = ImageReader.newInstance(maxCapture.width, maxCapture.height, ImageFormat.JPEG, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            var image: Image? = null
            try {
                image = reader.acquireLatestImage()
                if (image == null) { Log.w(TAG, "acquireLatestImage null"); isCapturing = false; return@setOnImageAvailableListener }
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                Log.i(TAG, "✅ Foto capturada: ${bytes.size} bytes (${maxCapture.width}x${maxCapture.height})")
                if (bytes.size > 1000) {
                    onPhotoCaptured?.invoke(bytes)
                } else {
                    Log.w(TAG, "Foto demasiado pequeña, descartada")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando imagen", e)
            } finally {
                image?.close()
                isCapturing = false
            }
        }, backgroundHandler)

        cameraManager.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                Log.i(TAG, "✅ Cámara abierta: $camId")
                cameraDevice = camera
                createSession(previewSurface, videoSurface)
            }
            override fun onDisconnected(camera: CameraDevice) {
                Log.w(TAG, "Cámara desconectada")
                cameraDevice = null; camera.close()
            }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "❌ Error abriendo cámara: $error")
                cameraDevice = null; camera.close()
            }
        }, backgroundHandler)
    }

    private fun chooseBestPreviewSize(sizes: Array<Size>?, targetW: Int, targetH: Int): Size {
        if (sizes == null || sizes.isEmpty()) return Size(targetW, targetH)
        sizes.firstOrNull { it.width == 1920 && it.height == 1080 }?.let { return it }
        val targetRatio = targetW.toFloat() / targetH.toFloat()
        return sizes
            .filter { it.width >= 1280 && it.height >= 720 }
            .minByOrNull { Math.abs(it.width.toFloat() / it.height.toFloat() - targetRatio) }
            ?: sizes.maxByOrNull { it.width * it.height }
            ?: Size(targetW, targetH)
    }

    private fun createSession(preview: Surface, video: Surface?) {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return

        // PRIMERO: intentar con 3 surfaces (preview + encoder + imageReader)
        // Si falla, usar solo 2 (preview + imageReader)
        val surfacesWith3 = mutableListOf(preview, reader.surface)
        if (video != null) surfacesWith3.add(video)

        try {
            device.createCaptureSession(surfacesWith3, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    encoderConnected = video != null
                    Log.i(TAG, "✅ Sesión configurada (${surfacesWith3.size} surfaces, encoder=$encoderConnected)")
                    startPreview(session, preview, video)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.w(TAG, "⚠️ Sesión con ${surfacesWith3.size} surfaces falló, intentando con 2...")
                    encoderConnected = false
                    // Fallback: solo preview + imageReader
                    createFallbackSession(preview)
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error creando sesión", e)
            createFallbackSession(preview)
        }
    }

    private fun createFallbackSession(preview: Surface) {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        try {
            device.createCaptureSession(listOf(preview, reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    encoderConnected = false
                    Log.i(TAG, "✅ Sesión fallback (2 surfaces) OK")
                    startPreview(session, preview, null)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.e(TAG, "❌ Sesión fallback TAMBIÉN falló")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error en sesión fallback", e)
        }
    }

    private fun startPreview(session: CameraCaptureSession, preview: Surface, video: Surface?) {
        val device = cameraDevice ?: return
        previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(preview)
            if (video != null && encoderConnected) addTarget(video)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
        try {
            session.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler)
            Log.i(TAG, "✅ Preview iniciado")
        } catch (e: Exception) {
            Log.e(TAG, "Error en preview", e)
        }
    }

    fun setZoom(zoomLevel: Float) {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        val sensor = sensorArraySize ?: return
        val z = zoomLevel.coerceIn(1.0f, maxZoom)
        val cw = sensor.width() / z; val ch = sensor.height() / z
        builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(
            ((sensor.width() - cw) / 2).toInt(), ((sensor.height() - ch) / 2).toInt(),
            ((sensor.width() + cw) / 2).toInt(), ((sensor.height() + ch) / 2).toInt()
        ))
        try { session.setRepeatingRequest(builder.build(), null, backgroundHandler) } catch (_: Exception) {}
    }

    fun setFlash(mode: Int) {
        val b = previewRequestBuilder ?: return; val s = captureSession ?: return
        when (mode) {
            0 -> { b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF); b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON) }
            1 -> b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
            2 -> b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
        }
        try { s.setRepeatingRequest(b.build(), null, backgroundHandler) } catch (_: Exception) {}
    }

    fun setHdr(enabled: Boolean) {
        val b = previewRequestBuilder ?: return; val s = captureSession ?: return
        if (enabled) { b.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR); b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE) }
        else { b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO) }
        try { s.setRepeatingRequest(b.build(), null, backgroundHandler) } catch (_: Exception) {}
    }

    fun focusAt(x: Float, y: Float, viewW: Int, viewH: Int) {
        val b = previewRequestBuilder ?: return; val s = captureSession ?: return; val sensor = sensorArraySize ?: return
        val fx = (x / viewW * sensor.width()).toInt(); val fy = (y / viewH * sensor.height()).toInt()
        val half = sensor.width() / 10
        val rect = Rect((fx-half).coerceAtLeast(0), (fy-half).coerceAtLeast(0), (fx+half).coerceAtMost(sensor.width()), (fy+half).coerceAtMost(sensor.height()))
        b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)))
        b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)))
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        try { s.setRepeatingRequest(b.build(), null, backgroundHandler) } catch (_: Exception) {}
    }

    fun takePicture() {
        val device = cameraDevice ?: run { Log.e(TAG, "❌ takePicture: device null"); return }
        val session = captureSession ?: run { Log.e(TAG, "❌ takePicture: session null"); return }
        val reader = imageReader ?: run { Log.e(TAG, "❌ takePicture: reader null"); return }
        if (isCapturing) { Log.w(TAG, "⚠️ Ya capturando"); return }
        isCapturing = true
        Log.i(TAG, "📸 Capturando foto...")

        try {
            val camId = currentCameraId ?: return
            val sensorOri = cameraManager.getCameraCharacteristics(camId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val jpegOri = if (isFrontCamera) (360 - sensorOri) % 360 else sensorOri

            val captureReq = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.JPEG_QUALITY, 98.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, jpegOri)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                previewRequestBuilder?.build()?.get(CaptureRequest.SCALER_CROP_REGION)?.let {
                    set(CaptureRequest.SCALER_CROP_REGION, it)
                }
            }.build()

            session.capture(captureReq, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    Log.i(TAG, "✅ onCaptureCompleted")
                }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                    Log.e(TAG, "❌ onCaptureFailed: ${f.reason}")
                    isCapturing = false
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception en capture", e)
            isCapturing = false
        }
    }

    fun closeCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { backgroundThread?.quitSafely() } catch (_: Exception) {}
        captureSession = null; cameraDevice = null; imageReader = null; isCapturing = false; encoderConnected = false
    }
}
