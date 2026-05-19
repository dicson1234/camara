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

    var isFrontCamera = false; private set
    @Volatile private var isCapturing = false
    var onPhotoCaptured: ((ByteArray) -> Unit)? = null

    // Zoom y sensor
    private var maxZoom = 1.0f
    private var sensorArraySize: Rect? = null

    // Tamaño de preview óptimo detectado para cada cámara
    var detectedPreviewSize: Size = Size(1920, 1080); private set

    fun startBackgroundThread() {
        backgroundThread?.quitSafely()
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    @SuppressLint("MissingPermission")
    fun openCamera(previewSurface: Surface, videoSurface: Surface?, w: Int, h: Int, front: Boolean = false) {
        isFrontCamera = front
        val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK

        val camId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
        } ?: return
        currentCameraId = camId

        val chars = cameraManager.getCameraCharacteristics(camId)
        maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
        sensorArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

        // Seleccionar el mejor tamaño de preview para este sensor
        val previewSizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)
        detectedPreviewSize = chooseBestPreviewSize(previewSizes, w, h)
        Log.i(TAG, "Preview: ${detectedPreviewSize.width}x${detectedPreviewSize.height}, Front=$front")

        // Tamaño máximo de captura (64MP en Redmi Note 8 Pro)
        val captureSizes = map.getOutputSizes(ImageFormat.JPEG)
        val maxCapture = captureSizes?.maxByOrNull { it.width * it.height } ?: Size(w, h)
        Log.i(TAG, "Captura: ${maxCapture.width}x${maxCapture.height}, Zoom max: ${maxZoom}x")

        imageReader?.close()
        imageReader = ImageReader.newInstance(maxCapture.width, maxCapture.height, ImageFormat.JPEG, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image: Image? = try { reader.acquireLatestImage() } catch (e: Exception) { null }
            if (image == null) return@setOnImageAvailableListener
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                if (bytes.size > 100) { // Validar que no sea un frame vacío
                    onPhotoCaptured?.invoke(bytes)
                }
            } finally {
                image.close()
                isCapturing = false // SIEMPRE liberar el flag aquí
            }
        }, backgroundHandler)

        cameraManager.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createSession(previewSurface, videoSurface)
            }
            override fun onDisconnected(camera: CameraDevice) { cameraDevice = null; camera.close() }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera error: $error")
                cameraDevice = null; camera.close()
            }
        }, backgroundHandler)
    }

    /**
     * Elige el mejor tamaño de preview que coincida con el aspect ratio del visor.
     * Prioriza 1920x1080 para trasera y tamaños 16:9 para frontal.
     */
    private fun chooseBestPreviewSize(sizes: Array<Size>?, targetW: Int, targetH: Int): Size {
        if (sizes == null || sizes.isEmpty()) return Size(targetW, targetH)
        val targetRatio = targetW.toFloat() / targetH.toFloat()
        // Intentar 1920x1080 primero
        sizes.firstOrNull { it.width == 1920 && it.height == 1080 }?.let { return it }
        // Buscar el más cercano al aspect ratio del target con resolución >= 720p
        return sizes
            .filter { it.width >= 1280 && it.height >= 720 }
            .minByOrNull { Math.abs(it.width.toFloat() / it.height.toFloat() - targetRatio) }
            ?: sizes.maxByOrNull { it.width * it.height }
            ?: Size(targetW, targetH)
    }

    private fun createSession(preview: Surface, video: Surface?) {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        val surfaces = mutableListOf(preview, reader.surface)
        if (video != null) surfaces.add(video)

        device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(preview)
                    if (video != null) addTarget(video)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
                try {
                    session.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler)
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting preview", e)
                }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Session config failed")
            }
        }, backgroundHandler)
    }

    fun setZoom(zoomLevel: Float) {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        val sensor = sensorArraySize ?: return
        val clampedZoom = zoomLevel.coerceIn(1.0f, maxZoom)
        val cropW = sensor.width() / clampedZoom
        val cropH = sensor.height() / clampedZoom
        val cropRect = Rect(
            ((sensor.width() - cropW) / 2).toInt(),
            ((sensor.height() - cropH) / 2).toInt(),
            ((sensor.width() + cropW) / 2).toInt(),
            ((sensor.height() + cropH) / 2).toInt()
        )
        builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
        try { session.setRepeatingRequest(builder.build(), null, backgroundHandler) } catch (_: Exception) {}
    }

    fun setFlash(mode: Int) {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        when (mode) {
            0 -> { builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF); builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON) }
            1 -> builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
            2 -> builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
        }
        try { session.setRepeatingRequest(builder.build(), null, backgroundHandler) } catch (_: Exception) {}
    }

    fun setHdr(enabled: Boolean) {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        if (enabled) {
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR)
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
        } else {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        }
        try { session.setRepeatingRequest(builder.build(), null, backgroundHandler) } catch (_: Exception) {}
    }

    fun focusAt(x: Float, y: Float, viewW: Int, viewH: Int) {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        val sensor = sensorArraySize ?: return
        val focusX = (x / viewW * sensor.width()).toInt()
        val focusY = (y / viewH * sensor.height()).toInt()
        val half = sensor.width() / 10
        val rect = Rect(
            (focusX - half).coerceAtLeast(0), (focusY - half).coerceAtLeast(0),
            (focusX + half).coerceAtMost(sensor.width()), (focusY + half).coerceAtMost(sensor.height())
        )
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)))
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)))
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        try { session.setRepeatingRequest(builder.build(), null, backgroundHandler) } catch (_: Exception) {}
    }

    fun takePicture() {
        val device = cameraDevice ?: run { Log.e(TAG, "takePicture: cameraDevice null"); return }
        val session = captureSession ?: run { Log.e(TAG, "takePicture: session null"); return }
        val reader = imageReader ?: run { Log.e(TAG, "takePicture: reader null"); return }
        if (isCapturing) { Log.w(TAG, "Ya capturando, ignorando"); return }
        isCapturing = true

        try {
            val camId = currentCameraId ?: return
            val sensorOri = cameraManager.getCameraCharacteristics(camId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            val captureReq = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, if (isFrontCamera) (360 - sensorOri) % 360 else sensorOri)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)
                // Copiar zoom actual
                previewRequestBuilder?.build()?.get(CaptureRequest.SCALER_CROP_REGION)?.let {
                    set(CaptureRequest.SCALER_CROP_REGION, it)
                }
            }.build()

            session.capture(captureReq, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    Log.i(TAG, "Captura completada OK")
                    // isCapturing se resetea en el ImageReader listener
                }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                    Log.e(TAG, "Captura fallida: reason=${f.reason}")
                    isCapturing = false
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access error", e)
            isCapturing = false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error", e)
            isCapturing = false
        }
    }

    fun closeCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { backgroundThread?.quitSafely() } catch (_: Exception) {}
        captureSession = null; cameraDevice = null; imageReader = null
        isCapturing = false
    }
}
