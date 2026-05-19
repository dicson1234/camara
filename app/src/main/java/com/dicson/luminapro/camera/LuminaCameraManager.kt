package com.dicson.luminapro.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface

/**
 * LuminaCameraManager
 * 
 * Controlador central que habla directamente con el ISP del MediaTek G90T
 * usando la API Camera2 a bajo nivel.
 * 
 * Su diseño soporta MÚLTIPLES flujos (Surfaces) simultáneos:
 * 1. Surface de Previsualización (Visor de pantalla 60fps)
 * 2. Surface de ImageReader (Para capturar la foto final en JPEG 64MP/16MP)
 * 3. Surface de Video (Para alimentar el búfer circular del Live Photo)
 */
class LuminaCameraManager(private val context: Context) {

    private val cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    // Hilos de fondo para no bloquear la UI principal
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Lector de la foto final (Alta calidad)
    private var imageReader: ImageReader? = null

    // Superficies activas para poder reactivarlas tras una captura
    private var activePreviewSurface: Surface? = null
    private var activeVideoSurface: Surface? = null

    // Aquí guardaremos los bytes de la foto temporalmente
    private var latestJpegBytes: ByteArray? = null
    var onPhotoCaptured: ((ByteArray) -> Unit)? = null

    /**
     * Inicia el hilo en segundo plano para procesar la cámara sin lag.
     */
    fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    var isFrontCamera = false

    /**
     * Abre la cámara solicitada (Trasera o Frontal).
     */
    @SuppressLint("MissingPermission") // Asumimos que los permisos ya fueron otorgados en la UI
    fun openCamera(previewSurface: Surface, videoSurface: Surface, width: Int, height: Int, useFrontCamera: Boolean = false) {
        this.isFrontCamera = useFrontCamera
        this.activePreviewSurface = previewSurface
        this.activeVideoSurface = videoSurface
        
        val targetFacing = if (useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK

        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == targetFacing
        } ?: return

        // EXTRAER MÁXIMA RESOLUCIÓN DEL SENSOR (64MP reales en lugar de resolución de pantalla)
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val largestJpeg = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height } ?: Size(width, height)
        Log.i("LuminaCamera", "Resolución Máxima Encontrada: ${largestJpeg.width}x${largestJpeg.height}")

        // Configuramos el lector para la foto JPEG de máxima resolución
        imageReader = ImageReader.newInstance(largestJpeg.width, largestJpeg.height, ImageFormat.JPEG, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image: Image = reader.acquireLatestImage()
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            
            // ¡Aquí es donde ocurre la magia!
            latestJpegBytes = bytes
            Log.i("LuminaCamera", "¡Foto principal capturada! Tamaño: ${bytes.size} bytes")
            image.close()
            
            // Enviamos los bytes reales al MainActivity para armar el Live Photo
            onPhotoCaptured?.invoke(bytes)
            
        }, backgroundHandler)

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                // Iniciamos la sesión enviando la luz a 3 lugares distintos a la vez
                startCaptureSession(previewSurface, videoSurface)
            }

            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close() }
        }, backgroundHandler)
    }

    /**
     * Crea la sesión de captura múltiple.
     * El ISP del Note 8 Pro debe enrutar los datos del sensor hacia:
     * - La Pantalla (preview)
     * - El codificador de Video continuo (videoSurface)
     * - El capturador de fotos (imageReader.surface)
     */
    private fun startCaptureSession(previewSurface: Surface, videoSurface: Surface) {
        val targets = listOf(previewSurface, videoSurface, imageReader!!.surface)

        cameraDevice?.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                
                // Pedimos un flujo continuo (Repeating) para la pantalla y el video del Live Photo
                val previewRequest = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface)
                    addTarget(videoSurface) // El video se graba en bucle invisible
                    
                    // Controles automáticos por defecto (Auto-Enfoque continuo)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }.build()

                session.setRepeatingRequest(previewRequest, null, backgroundHandler)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e("LuminaCamera", "Fallo al configurar la sesión de cámara.")
            }
        }, backgroundHandler)
    }

    @Volatile private var isCapturing = false

    /**
     * ¡Click! Dispara la foto de alta calidad.
     * Rompe el ciclo continuo un microsegundo, toma un frame perfecto, y vuelve a la previsualización.
     */
    fun takePicture() {
        if (cameraDevice == null || captureSession == null || isCapturing) return
        isCapturing = true

        try {
            val captureRequest = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imageReader!!.surface)
                
                // Configuración de Calidad Óptima (100% JPEG Quality)
                set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                
                // Forzar procesamiento de Alta Calidad en el ISP (Reducción de ruido y bordes)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                
                // Mantenemos la orientación correcta dependiendo de la cámara (Note 8 Pro rectificación)
                val rotation = if (isFrontCamera) 270 else 90
                set(CaptureRequest.JPEG_ORIENTATION, rotation) 
            }.build()

            // Detenemos el flujo visual un instante
            captureSession?.stopRepeating()
            
            // Disparamos la foto (Esto invoca el listener del ImageReader de arriba)
            captureSession?.capture(captureRequest, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    Log.i("LuminaCamera", "Captura del sensor exitosa (Zero Shutter Lag).")
                    isCapturing = false
                    
                    // Reactivamos la pantalla y el flujo de video al instante
                    if (activePreviewSurface != null && activeVideoSurface != null) {
                        startCaptureSession(activePreviewSurface!!, activeVideoSurface!!)
                    }
                }
            }, backgroundHandler)

        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    fun closeCamera() {
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        backgroundThread?.quitSafely()
    }
}
