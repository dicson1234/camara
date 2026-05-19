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

    private val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var reader: ImageReader? = null
    private var camId: String? = null
    private var builder: CaptureRequest.Builder? = null

    var isFront = false; private set
    @Volatile private var busy = false
    var onPhoto: ((ByteArray) -> Unit)? = null

    private var maxZoom = 1f
    private var sensor: Rect? = null
    var previewSize: Size = Size(1920, 1080); private set

    fun startBg() {
        bgThread?.quitSafely()
        bgThread = HandlerThread("CamBG").apply { start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    @SuppressLint("MissingPermission")
    fun open(previewSurface: Surface, front: Boolean) {
        isFront = front
        val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
        } ?: return
        camId = id

        val ch = cm.getCameraCharacteristics(id)
        maxZoom = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        sensor = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

        // Preview
        val pvSizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)
        previewSize = pvSizes?.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: pvSizes?.filter { it.width >= 1280 }?.minByOrNull { Math.abs(it.width.toFloat()/it.height - 16f/9f) }
            ?: pvSizes?.maxByOrNull { it.width * it.height }
            ?: Size(1920, 1080)

        // Capture: 12MP max para seguridad
        val jpSizes = map.getOutputSizes(ImageFormat.JPEG)
        val capSize = jpSizes?.filter { it.width * it.height <= 12_500_000 }?.maxByOrNull { it.width * it.height }
            ?: jpSizes?.maxByOrNull { it.width * it.height } ?: Size(4000, 3000)

        Log.i(TAG, "Open $id: pv=$previewSize cap=$capSize front=$front")

        reader?.close()
        reader = ImageReader.newInstance(capSize.width, capSize.height, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ r ->
                try {
                    val img = r.acquireLatestImage() ?: run { busy = false; return@setOnImageAvailableListener }
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                    img.close(); busy = false
                    Log.i(TAG, "✅ JPEG ${bytes.size}b")
                    if (bytes.size > 1000) onPhoto?.invoke(bytes)
                } catch (e: Exception) { Log.e(TAG, "Img err", e); busy = false }
            }, bgHandler)
        }

        // Solo 2 surfaces: preview + imageReader (compatible con TODOS los dispositivos)
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(c: CameraDevice) {
                device = c
                val rdr = reader ?: return
                c.createCaptureSession(listOf(previewSurface, rdr.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        builder = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(previewSurface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        }
                        try { s.setRepeatingRequest(builder!!.build(), null, bgHandler) } catch (_: Exception) {}
                        Log.i(TAG, "✅ Preview OK")
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) { Log.e(TAG, "❌ Session fail") }
                }, bgHandler)
            }
            override fun onDisconnected(c: CameraDevice) { c.close(); device = null }
            override fun onError(c: CameraDevice, e: Int) { Log.e(TAG, "Cam err $e"); c.close(); device = null }
        }, bgHandler)
    }

    fun zoom(level: Float) {
        val b = builder ?: return; val s = session ?: return; val r = sensor ?: return
        val z = level.coerceIn(1f, maxZoom)
        val cw = r.width()/z; val ch = r.height()/z
        b.set(CaptureRequest.SCALER_CROP_REGION, Rect(
            ((r.width()-cw)/2).toInt(), ((r.height()-ch)/2).toInt(),
            ((r.width()+cw)/2).toInt(), ((r.height()+ch)/2).toInt()))
        try { s.setRepeatingRequest(b.build(), null, bgHandler) } catch (_: Exception) {}
    }

    fun flash(mode: Int) {
        val b = builder ?: return; val s = session ?: return
        when (mode) {
            0 -> { b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF); b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON) }
            1 -> b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
            2 -> b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
        }
        try { s.setRepeatingRequest(b.build(), null, bgHandler) } catch (_: Exception) {}
    }

    fun hdr(on: Boolean) {
        val b = builder ?: return; val s = session ?: return
        if (on) { b.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR); b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE) }
        else b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        try { s.setRepeatingRequest(b.build(), null, bgHandler) } catch (_: Exception) {}
    }

    fun focus(x: Float, y: Float, vw: Int, vh: Int) {
        val b = builder ?: return; val s = session ?: return; val r = sensor ?: return
        val fx = (x/vw*r.width()).toInt(); val fy = (y/vh*r.height()).toInt(); val h = r.width()/10
        val rect = Rect((fx-h).coerceAtLeast(0), (fy-h).coerceAtLeast(0), (fx+h).coerceAtMost(r.width()), (fy+h).coerceAtMost(r.height()))
        b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)))
        b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)))
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        try { s.setRepeatingRequest(b.build(), null, bgHandler) } catch (_: Exception) {}
    }

    fun takePicture(deviceRotation: Int = 0) {
        val d = device ?: return; val s = session ?: return; val r = reader ?: return
        if (busy) return; busy = true
        Log.i(TAG, "📸 Capture...")
        try {
            val id = camId ?: return
            val sOri = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val ori = if (isFront) (sOri + deviceRotation) % 360 else (sOri - deviceRotation + 360) % 360

            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(r.surface)
                set(CaptureRequest.JPEG_QUALITY, 98.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, ori)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                builder?.build()?.get(CaptureRequest.SCALER_CROP_REGION)?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
            }.build()
            s.capture(req, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) { Log.e(TAG, "Fail ${f.reason}"); busy = false }
            }, bgHandler)
        } catch (e: Exception) { Log.e(TAG, "Cap err", e); busy = false }
    }

    fun close() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { bgThread?.quitSafely() } catch (_: Exception) {}
        session = null; device = null; reader = null; busy = false
    }
}
