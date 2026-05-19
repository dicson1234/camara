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

/**
 * LuminaCameraManager v2
 *
 * CORRECCIONES:
 * - acquireLatestImage() con null-check (previene NPE fatal)
 * - Orientación JPEG dinámica desde CameraCharacteristics
 * - No recrea la sesión tras cada captura (solo reinicia repeating request)
 * - Touch-to-focus con coordenadas del sensor
 * - Cierre ordenado de recursos
 */
class LuminaCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "LuminaCamera"
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var imageReader: ImageReader? = null

    private var activePreviewSurface: Surface? = null
    private var activeVideoSurface: Surface? = null
    private var currentCameraId: String? = null

    var isFrontCamera = false
        private set
    @Volatile private var isCapturing = false

    var onPhotoCaptured: ((ByteArray) -> Unit)? = null

    // Request builder reutilizable para el preview
    private var previewRequestBuilder: CaptureRequest.Builder? = null

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
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val maxSize = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height } ?: Size(w, h)
        Log.i(TAG, "Resolución máxima: ${maxSize.width}x${maxSize.height}")

        imageReader?.close()
        imageReader = ImageReader.newInstance(maxSize.width, maxSize.height, ImageFormat.JPEG, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            // CORRECCIÓN: acquireLatestImage() PUEDE retornar null
            val image: Image? = reader.acquireLatestImage()
            if (image == null) {
                Log.w(TAG, "acquireLatestImage retornó null")
                return@setOnImageAvailableListener
            }
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                Log.i(TAG, "Foto capturada: ${bytes.size} bytes")
                onPhotoCaptured?.invoke(bytes)
            } finally {
                image.close()
            }
        }, backgroundHandler)

        cameraManager.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createCaptureSession(previewSurface, videoSurface)
            }
            override fun onDisconnected(camera: CameraDevice) {
                cameraDevice = null
                camera.close()
            }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Error al abrir cámara: $error")
                cameraDevice = null
                camera.close()
            }
        }, backgroundHandler)
    }

    private fun createCaptureSession(previewSurface: Surface, videoSurface: Surface) {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        val targets = listOf(previewSurface, videoSurface, reader.surface)

        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface)
                    addTarget(videoSurface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
                session.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Fallo configuración de sesión")
            }
        }, backgroundHandler)
    }

    /**
     * Touch-to-focus: enfoca en las coordenadas dadas de la pantalla.
     */
    fun focusAt(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        val camId = currentCameraId ?: return
        val chars = cameraManager.getCameraCharacteristics(camId)
        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        val focusX = (x / viewWidth * sensorSize.width()).toInt()
        val focusY = (y / viewHeight * sensorSize.height()).toInt()
        val halfW = sensorSize.width() / 10
        val halfH = sensorSize.height() / 10
        val focusRect = Rect(
            (focusX - halfW).coerceAtLeast(0),
            (focusY - halfH).coerceAtLeast(0),
            (focusX + halfW).coerceAtMost(sensorSize.width()),
            (focusY + halfH).coerceAtMost(sensorSize.height())
        )
        val meteringRect = MeteringRectangle(focusRect, MeteringRectangle.METERING_WEIGHT_MAX)

        builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)

        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
    }

    /**
     * Captura una foto de máxima calidad.
     */
    fun takePicture() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return
        if (isCapturing) return
        isCapturing = true

        try {
            // CORRECCIÓN: Obtener orientación real del sensor
            val camId = currentCameraId ?: return
            val chars = cameraManager.getCameraCharacteristics(camId)
            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            val captureReq = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            }.build()

            // CORRECCIÓN: No detener el repeating, solo hacer una captura adicional
            // Esto evita la pausa negra en pantalla
            session.capture(captureReq, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    Log.i(TAG, "Captura completada (ZSL)")
                    isCapturing = false
                }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                    Log.e(TAG, "Captura falló: ${failure.reason}")
                    isCapturing = false
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error de acceso a cámara", e)
            isCapturing = false
        }
    }

    fun closeCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { backgroundThread?.quitSafely() } catch (_: Exception) {}
        captureSession = null
        cameraDevice = null
        imageReader = null
    }
}
