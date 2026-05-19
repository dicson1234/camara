package com.dicson.luminapro

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dicson.luminapro.camera.CircularVideoEncoder
import com.dicson.luminapro.camera.LivePhotoBuilder
import com.dicson.luminapro.camera.LuminaCameraManager
import java.io.File
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LuminaPro"
        private const val REQ_PERMS = 10
    }

    private lateinit var luminaCamera: LuminaCameraManager
    private lateinit var videoEncoder: CircularVideoEncoder
    private lateinit var viewFinder: TextureView
    private lateinit var captureButton: View
    private lateinit var switchCameraBtn: View
    private lateinit var liveIndicator: TextView
    private lateinit var flashBtn: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var timerLabel: TextView
    private lateinit var hdrBtn: TextView

    private var isUsingFrontCamera = false
    private var livePhotoEnabled = true
    private var flashMode = 0
    private var timerSeconds = 0
    private var hdrEnabled = false
    private var currentZoom = 1.0f
    private var cameraInitialized = false

    private lateinit var scaleDetector: ScaleGestureDetector

    private fun getRequiredPermissions(): Array<String> {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= 32) perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 33) perms.add("android.permission.READ_MEDIA_IMAGES")
        return perms.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        captureButton = findViewById(R.id.captureButton)
        switchCameraBtn = findViewById(R.id.switchCameraBtn)
        liveIndicator = findViewById(R.id.liveIndicator)
        flashBtn = findViewById(R.id.flashBtn)
        zoomLabel = findViewById(R.id.zoomLabel)
        timerLabel = findViewById(R.id.timerLabel)
        hdrBtn = findViewById(R.id.hdrBtn)

        setupZoom()
        setupButtons()

        if (allPermissionsGranted()) {
            initCamera()
        } else {
            ActivityCompat.requestPermissions(this, getRequiredPermissions(), REQ_PERMS)
        }
    }

    private fun setupZoom() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (!cameraInitialized) return true
                currentZoom = (currentZoom * d.scaleFactor).coerceIn(1.0f, 10.0f)
                luminaCamera.setZoom(currentZoom)
                zoomLabel.text = String.format("%.1fx", currentZoom)
                return true
            }
        })
    }

    private fun setupButtons() {
        captureButton.setOnClickListener {
            if (!cameraInitialized) return@setOnClickListener
            if (timerSeconds > 0) {
                Toast.makeText(this, "Foto en ${timerSeconds}s...", Toast.LENGTH_SHORT).show()
                captureButton.postDelayed({ takeLivePhoto() }, timerSeconds * 1000L)
            } else {
                takeLivePhoto()
            }
        }

        switchCameraBtn.setOnClickListener {
            if (!cameraInitialized) return@setOnClickListener
            isUsingFrontCamera = !isUsingFrontCamera
            currentZoom = 1.0f
            zoomLabel.text = "1.0x"
            restartCamera()
        }

        liveIndicator.setOnClickListener {
            livePhotoEnabled = !livePhotoEnabled
            liveIndicator.text = if (livePhotoEnabled) "LIVE" else "OFF"
            liveIndicator.alpha = if (livePhotoEnabled) 1f else 0.4f
        }

        flashBtn.setOnClickListener {
            if (!cameraInitialized) return@setOnClickListener
            flashMode = (flashMode + 1) % 3
            flashBtn.text = when (flashMode) { 0 -> "⚡OFF"; 1 -> "⚡ON"; else -> "⚡AUTO" }
            luminaCamera.setFlash(flashMode)
        }

        timerLabel.setOnClickListener {
            timerSeconds = when (timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }
            timerLabel.text = if (timerSeconds == 0) "⏱" else "⏱${timerSeconds}s"
        }

        hdrBtn.setOnClickListener {
            if (!cameraInitialized) return@setOnClickListener
            hdrEnabled = !hdrEnabled
            hdrBtn.alpha = if (hdrEnabled) 1f else 0.4f
            luminaCamera.setHdr(hdrEnabled)
        }

        viewFinder.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && !scaleDetector.isInProgress && cameraInitialized) {
                luminaCamera.focusAt(event.x, event.y, v.width, v.height)
            }
            true
        }
    }

    private fun initCamera() {
        luminaCamera = LuminaCameraManager(this)
        luminaCamera.startBackgroundThread()
        videoEncoder = CircularVideoEncoder(1280, 720, 4_000_000, 30)
        videoEncoder.startDraining()
        cameraInitialized = true

        if (viewFinder.isAvailable) {
            openCameraWithTexture(viewFinder.surfaceTexture!!)
        }

        viewFinder.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                openCameraWithTexture(st)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                luminaCamera.closeCamera()
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun openCameraWithTexture(st: SurfaceTexture) {
        st.setDefaultBufferSize(1920, 1080)
        val previewSurface = Surface(st)
        luminaCamera.openCamera(previewSurface, videoEncoder.inputSurface, 1920, 1080, isUsingFrontCamera)
    }

    private fun restartCamera() {
        luminaCamera.closeCamera()
        luminaCamera.startBackgroundThread()
        val st = viewFinder.surfaceTexture ?: return
        openCameraWithTexture(st)
    }

    private fun takeLivePhoto() {
        luminaCamera.onPhotoCaptured = { jpegBytes ->
            if (livePhotoEnabled && videoEncoder.isReady()) {
                val tmpFile = File(cacheDir, "tmp_live.mp4")
                videoEncoder.extractLivePhotoVideo(tmpFile, 1500L) { mp4Bytes ->
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            if (mp4Bytes != null && mp4Bytes.isNotEmpty()) {
                                val liveBytes = LivePhotoBuilder.buildMotionPhotoBytes(jpegBytes, mp4Bytes)
                                // Nombre DEBE contener "MP" antes de .jpg (especificación Google Motion Photo)
                                val filename = "LuminaMP_${System.currentTimeMillis()}.jpg"
                                saveToGallery(liveBytes, filename)
                                showToast("¡Live Photo guardada! ✨")
                            } else {
                                saveToGallery(jpegBytes, "Lumina_${System.currentTimeMillis()}.jpg")
                                showToast("Foto guardada (sin video) ✨")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error guardando Live Photo", e)
                            // Fallback: guardar solo la foto
                            saveToGallery(jpegBytes, "Lumina_${System.currentTimeMillis()}.jpg")
                            showToast("Foto guardada (fallback)")
                        }
                    }
                }
            } else {
                // Modo normal o encoder no listo
                if (livePhotoEnabled && !videoEncoder.isReady()) {
                    Log.w(TAG, "Live Photo activado pero encoder no listo aún")
                }
                CoroutineScope(Dispatchers.IO).launch {
                    saveToGallery(jpegBytes, "Lumina_${System.currentTimeMillis()}.jpg")
                    showToast("Foto guardada ✨")
                }
            }
        }
        luminaCamera.takePicture()
    }

    /**
     * Guarda los bytes directamente en la galería usando MediaStore.
     * Funciona en Android 10+ (scoped storage) y versiones anteriores.
     * Las fotos aparecen INSTANTÁNEAMENTE en Galería, Google Photos, TikTok, WhatsApp.
     */
    private fun saveToGallery(imageBytes: ByteArray, filename: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/LuminaPro")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri: Uri? = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { os ->
                os.write(imageBytes)
                os.flush()
            }
            if (Build.VERSION.SDK_INT >= 29) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(it, values, null, null)
            }
        }
    }

    private suspend fun showToast(msg: String) {
        withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_PERMS) {
            if (allPermissionsGranted()) {
                initCamera()
            } else {
                Toast.makeText(this, "Activa todos los permisos en Ajustes", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun allPermissionsGranted() = getRequiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        if (cameraInitialized) { luminaCamera.closeCamera(); videoEncoder.stop() }
    }
}
