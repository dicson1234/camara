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
    private var activePreviewSurface: Surface? = null
    private var activeVideoSurface: Surface? = null
    private var currentCameraId: String? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    var isFrontCamera = false; private set
    @Volatile private var isCapturing = false
    var onPhotoCaptured: ((ByteArray) -> Unit)? = null

    // Zoom y controles
    private var maxZoom = 1.0f
    private var sensorArraySize: Rect? = null

    fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    @SuppressLint("MissingPermission")
    fun openCamera(previewSurface: Surface, videoSurface: Surface, w: Int, h: Int, front: Boolean = false) {
        isFrontCamera = front
        activePreviewSurface = previewSurface
        activeVideoSurface = videoSurface
        val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK

        val camId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
        } ?: return
        currentCameraId = camId

        val chars = cameraManager.getCameraCharacteristics(camId)
        maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
        sensorArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val maxSize = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height } ?: Size(w, h)
        Log.i(TAG, "Sensor: ${maxSize.width}x${maxSize.height}, Zoom max: ${maxZoom}x")

        imageReader?.close()
        imageReader = ImageReader.newInstance(maxSize.width, maxSize.height, ImageFormat.JPEG, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image: Image? = reader.acquireLatestImage()
            if (image == null) return@setOnImageAvailableListener
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                onPhotoCaptured?.invoke(bytes)
            } finally { image.close() }
        }, backgroundHandler)

        cameraManager.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createSession(previewSurface, videoSurface)
            }
            override fun onDisconnected(camera: CameraDevice) { cameraDevice = null; camera.close() }
            override fun onError(camera: CameraDevice, error: Int) { cameraDevice = null; camera.close() }
        }, backgroundHandler)
    }

    private fun createSession(preview: Surface, video: Surface) {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        device.createCaptureSession(listOf(preview, video, reader.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(preview)
                    addTarget(video)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
                session.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) { Log.e(TAG, "Session config failed") }
        }, backgroundHandler)
    }

    /** Zoom digital por pinch gesture */
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
        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
    }

    /** Flash: 0=off, 1=on, 2=auto */
    fun setFlash(mode: Int) {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        when (mode) {
            0 -> {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            1 -> builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
            2 -> builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
        }
        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
    }

    /** HDR: usa SCENE_MODE_HDR si el sensor lo soporta */
    fun setHdr(enabled: Boolean) {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        if (enabled) {
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR)
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
        } else {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        }
        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
    }

    /** Touch-to-focus */
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
        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
    }

    fun takePicture() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return
        if (isCapturing) return
        isCapturing = true

        try {
            val camId = currentCameraId ?: return
            val sensorOri = cameraManager.getCameraCharacteristics(camId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, sensorOri)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                // Copiar zoom actual a la captura
                previewRequestBuilder?.build()?.get(CaptureRequest.SCALER_CROP_REGION)?.let {
                    set(CaptureRequest.SCALER_CROP_REGION, it)
                }
            }.build()

            session.capture(req, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    isCapturing = false
                }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                    Log.e(TAG, "Capture failed: ${f.reason}")
                    isCapturing = false
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera error", e)
            isCapturing = false
        }
    }

    fun closeCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { backgroundThread?.quitSafely() } catch (_: Exception) {}
        captureSession = null; cameraDevice = null; imageReader = null
    }
}
