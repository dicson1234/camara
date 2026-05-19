package com.dicson.luminapro

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.ImageView
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
        private const val REQ_PERMISSIONS = 10
    }

    private lateinit var luminaCamera: LuminaCameraManager
    private lateinit var videoEncoder: CircularVideoEncoder
    private lateinit var viewFinder: SurfaceView
    private lateinit var captureButton: View
    private lateinit var switchCameraBtn: View
    private lateinit var liveIndicator: TextView
    private lateinit var flashBtn: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var timerLabel: TextView
    private lateinit var hdrBtn: TextView
    private lateinit var gridOverlay: View

    private var isUsingFrontCamera = false
    private var livePhotoEnabled = true
    private var isSurfaceReady = false
    private var flashMode = 0 // 0=off, 1=on, 2=auto
    private var showGrid = false
    private var timerSeconds = 0 // 0, 3, 10
    private var hdrEnabled = false

    // Pinch-to-zoom
    private lateinit var scaleDetector: ScaleGestureDetector
    private var currentZoom = 1.0f

    private fun getRequiredPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT <= 32) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add("android.permission.READ_MEDIA_IMAGES")
        }
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
        gridOverlay = findViewById(R.id.gridOverlay)

        setupZoomGesture()
        setupButtons()

        if (allPermissionsGranted()) {
            initCamera()
        } else {
            ActivityCompat.requestPermissions(this, getRequiredPermissions(), REQ_PERMISSIONS)
        }
    }

    private fun setupZoomGesture() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                currentZoom *= detector.scaleFactor
                currentZoom = currentZoom.coerceIn(1.0f, 10.0f)
                luminaCamera.setZoom(currentZoom)
                zoomLabel.text = String.format("%.1fx", currentZoom)
                zoomLabel.visibility = View.VISIBLE
                return true
            }
        })
    }

    private fun setupButtons() {
        captureButton.setOnClickListener {
            if (timerSeconds > 0) {
                Toast.makeText(this, "Foto en ${timerSeconds}s...", Toast.LENGTH_SHORT).show()
                captureButton.postDelayed({ takeLivePhoto() }, timerSeconds * 1000L)
            } else {
                takeLivePhoto()
            }
        }

        switchCameraBtn.setOnClickListener {
            isUsingFrontCamera = !isUsingFrontCamera
            currentZoom = 1.0f
            zoomLabel.text = "1.0x"
            luminaCamera.closeCamera()
            luminaCamera.startBackgroundThread()
            if (isSurfaceReady) {
                luminaCamera.openCamera(viewFinder.holder.surface, videoEncoder.inputSurface, 1920, 1080, isUsingFrontCamera)
            }
        }

        liveIndicator.setOnClickListener {
            livePhotoEnabled = !livePhotoEnabled
            liveIndicator.text = if (livePhotoEnabled) "LIVE" else "OFF"
            liveIndicator.alpha = if (livePhotoEnabled) 1f else 0.4f
        }

        flashBtn.setOnClickListener {
            flashMode = (flashMode + 1) % 3
            val label = when (flashMode) { 0 -> "⚡OFF"; 1 -> "⚡ON"; else -> "⚡AUTO" }
            flashBtn.text = label
            luminaCamera.setFlash(flashMode)
        }

        timerLabel.setOnClickListener {
            timerSeconds = when (timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }
            timerLabel.text = if (timerSeconds == 0) "⏱" else "⏱${timerSeconds}s"
        }

        hdrBtn.setOnClickListener {
            hdrEnabled = !hdrEnabled
            hdrBtn.alpha = if (hdrEnabled) 1f else 0.4f
            luminaCamera.setHdr(hdrEnabled)
        }

        gridOverlay.setOnClickListener {
            showGrid = !showGrid
            gridOverlay.visibility = if (showGrid) View.VISIBLE else View.GONE
        }

        // Touch-to-focus + zoom
        viewFinder.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && event.pointerCount == 1) {
                luminaCamera.focusAt(event.x, event.y, v.width, v.height)
            }
            true
        }
    }

    private fun initCamera() {
        luminaCamera = LuminaCameraManager(this)
        luminaCamera.startBackgroundThread()
        videoEncoder = CircularVideoEncoder(1920, 1080, 8_000_000, 30)
        videoEncoder.startDraining()

        viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                isSurfaceReady = true
                luminaCamera.openCamera(holder.surface, videoEncoder.inputSurface, 1920, 1080, isUsingFrontCamera)
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { isSurfaceReady = false }
        })
    }

    private fun takeLivePhoto() {
        Toast.makeText(this, "📸 Capturando...", Toast.LENGTH_SHORT).show()

        luminaCamera.onPhotoCaptured = { jpegBytes ->
            if (livePhotoEnabled) {
                val tmpFile = File(cacheDir, "tmp_live.mp4")
                videoEncoder.extractLivePhotoVideo(tmpFile, 1500L) { mp4Bytes ->
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            if (mp4Bytes != null && mp4Bytes.isNotEmpty()) {
                                val out = createOutputFile("Lumina_Live")
                                LivePhotoBuilder.buildMotionPhoto(jpegBytes, mp4Bytes, out)
                                notifyGallery(out)
                                showToast("¡Live Photo guardada! ✨")
                            } else {
                                saveStaticJpeg(jpegBytes)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error guardando", e)
                            showToast("Error al guardar")
                        }
                    }
                }
            } else {
                CoroutineScope(Dispatchers.IO).launch { saveStaticJpeg(jpegBytes) }
            }
        }
        luminaCamera.takePicture()
    }

    private fun createOutputFile(prefix: String): File {
        val dcim = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "LuminaPro")
        if (!dcim.exists()) dcim.mkdirs()
        return File(dcim, "${prefix}_${System.currentTimeMillis()}.jpg")
    }

    private suspend fun saveStaticJpeg(bytes: ByteArray) {
        val out = createOutputFile("Lumina")
        out.writeBytes(bytes)
        notifyGallery(out)
        showToast("Foto guardada ✨")
    }

    private fun notifyGallery(file: File) {
        MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
    }

    private suspend fun showToast(msg: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_PERMISSIONS) {
            if (allPermissionsGranted()) {
                initCamera()
            } else {
                // Mostrar los permisos que faltan específicamente
                val denied = getRequiredPermissions().filter {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }
                Toast.makeText(this, "Permisos necesarios: ${denied.size} pendientes", Toast.LENGTH_LONG).show()
                // Reintentar
                ActivityCompat.requestPermissions(this, denied.toTypedArray(), REQ_PERMISSIONS)
            }
        }
    }

    private fun allPermissionsGranted() = getRequiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::luminaCamera.isInitialized) luminaCamera.closeCamera()
        if (::videoEncoder.isInitialized) videoEncoder.stop()
    }
}
