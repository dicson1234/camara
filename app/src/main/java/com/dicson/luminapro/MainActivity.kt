package com.dicson.luminapro

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.MotionEvent
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
    private var isUsingFrontCamera = false
    private var livePhotoEnabled = true
    private var isSurfaceReady = false

    private val PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        captureButton = findViewById(R.id.captureButton)
        switchCameraBtn = findViewById(R.id.switchCameraBtn)
        liveIndicator = findViewById(R.id.liveIndicator)

        if (allPermissionsGranted()) {
            initCamera()
        } else {
            ActivityCompat.requestPermissions(this, PERMISSIONS, REQ_PERMISSIONS)
        }

        captureButton.setOnClickListener { takeLivePhoto() }

        switchCameraBtn.setOnClickListener {
            isUsingFrontCamera = !isUsingFrontCamera
            luminaCamera.closeCamera()
            luminaCamera.startBackgroundThread()
            if (isSurfaceReady) {
                luminaCamera.openCamera(
                    viewFinder.holder.surface, videoEncoder.inputSurface,
                    1920, 1080, isUsingFrontCamera
                )
            }
        }

        liveIndicator.setOnClickListener {
            livePhotoEnabled = !livePhotoEnabled
            liveIndicator.text = if (livePhotoEnabled) "LIVE" else "LIVE OFF"
            liveIndicator.alpha = if (livePhotoEnabled) 1f else 0.4f
        }

        // Touch-to-focus
        viewFinder.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
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
                luminaCamera.openCamera(
                    holder.surface, videoEncoder.inputSurface,
                    1920, 1080, isUsingFrontCamera
                )
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                isSurfaceReady = false
            }
        })
    }

    private fun takeLivePhoto() {
        Toast.makeText(this, "Capturando...", Toast.LENGTH_SHORT).show()

        luminaCamera.onPhotoCaptured = { jpegBytes ->
            if (livePhotoEnabled) {
                // Live Photo: esperar 1.5s para capturar el futuro y empaquetar
                val tmpFile = File(cacheDir, "tmp_live.mp4")
                videoEncoder.extractLivePhotoVideo(tmpFile, 1500L) { mp4Bytes ->
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val dcim = File(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                                "Camera"
                            )
                            if (!dcim.exists()) dcim.mkdirs()

                            if (mp4Bytes != null && mp4Bytes.isNotEmpty()) {
                                // Guardar como Motion Photo (Live)
                                val out = File(dcim, "Lumina_Live_${System.currentTimeMillis()}.jpg")
                                LivePhotoBuilder.buildMotionPhoto(jpegBytes, mp4Bytes, out)
                                MediaScannerConnection.scanFile(
                                    this@MainActivity,
                                    arrayOf(out.absolutePath), arrayOf("image/jpeg"), null
                                )
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "¡Live Photo guardada!", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                // Fallback: guardar solo la foto
                                saveStaticPhoto(jpegBytes, dcim)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error guardando Live Photo", e)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Error al guardar", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            } else {
                // Modo normal: solo foto estática de máxima calidad
                CoroutineScope(Dispatchers.IO).launch {
                    val dcim = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                        "Camera"
                    )
                    if (!dcim.exists()) dcim.mkdirs()
                    saveStaticPhoto(jpegBytes, dcim)
                }
            }
        }

        luminaCamera.takePicture()
    }

    private suspend fun saveStaticPhoto(jpegBytes: ByteArray, dcim: File) {
        val out = File(dcim, "Lumina_${System.currentTimeMillis()}.jpg")
        out.writeBytes(jpegBytes)
        MediaScannerConnection.scanFile(
            this@MainActivity,
            arrayOf(out.absolutePath), arrayOf("image/jpeg"), null
        )
        withContext(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, "Foto guardada", Toast.LENGTH_SHORT).show()
        }
    }

    // CORRECCIÓN: Manejar la respuesta de permisos
    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_PERMISSIONS && results.all { it == PackageManager.PERMISSION_GRANTED }) {
            initCamera()
        } else {
            Toast.makeText(this, "Se necesitan permisos de cámara", Toast.LENGTH_LONG).show()
        }
    }

    private fun allPermissionsGranted() = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        luminaCamera.closeCamera()
        videoEncoder.stop()
    }
}
