package com.dicson.luminapro

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.media.MediaScannerConnection
import com.dicson.luminapro.camera.CircularVideoEncoder
import com.dicson.luminapro.camera.LivePhotoBuilder
import com.dicson.luminapro.camera.LuminaCameraManager
import java.io.File
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var cameraManager: LuminaCameraManager
    private lateinit var videoEncoder: CircularVideoEncoder
    private lateinit var viewFinder: SurfaceView
    private lateinit var captureButton: View
    private lateinit var switchCameraBtn: View
    private var isUsingFrontCamera = false

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

        if (allPermissionsGranted()) {
            startCameraSystem()
        } else {
            ActivityCompat.requestPermissions(this, PERMISSIONS, 10)
        }

        captureButton.setOnClickListener {
            takeLivePhoto()
        }

        switchCameraBtn.setOnClickListener {
            // RECTIFICACIÓN: Alternar cámara y reiniciar la sesión de Live Photo
            isUsingFrontCamera = !isUsingFrontCamera
            cameraManager.closeCamera()
            cameraManager.openCamera(viewFinder.holder.surface, videoEncoder.inputSurface, 1920, 1080, isUsingFrontCamera)
        }
    }

    private fun startCameraSystem() {
        cameraManager = LuminaCameraManager(this)
        cameraManager.startBackgroundThread()

        // Configuramos el buffer de video a 1080p, 10Mbps, 30fps para el Live Photo
        videoEncoder = CircularVideoEncoder(1920, 1080, 10000000, 30)
        videoEncoder.startDraining()

        viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // Al iniciar, le damos a la API Camera2 nuestras dos superficies
                cameraManager.openCamera(holder.surface, videoEncoder.inputSurface, 1920, 1080, isUsingFrontCamera)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
    }

    private fun takeLivePhoto() {
        Toast.makeText(this, "Capturando Foto 64MP...", Toast.LENGTH_SHORT).show()
        
        // Configuramos qué hacer cuando el sensor capture la foto real de 64MP
        cameraManager.onPhotoCaptured = { realJpegBytes ->
            // En vez de guardar inmediatamente, le pedimos al codificador que capture
            // el FUTURO (1.5 segundos extras de grabación) para lograr el verdadero efecto de Movimiento
            val mp4File = File(getExternalFilesDir(Environment.DIRECTORY_DCIM), "temp_video.mp4")
            
            videoEncoder.extractLivePhotoVideo(mp4File, 1500L) { mp4Bytes ->
                if (mp4Bytes != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            // Guardar en la carpeta PÚBLICA de la cámara para que TikTok y la Galería lo vean
                            val publicDcimDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
                            if (!publicDcimDir.exists()) publicDcimDir.mkdirs()
                            
                            val finalLivePhoto = File(publicDcimDir, "Lumina_Live_${System.currentTimeMillis()}.jpg")
                            
                            // Empaquetamos la foto REAL y el video (3s total)
                            LivePhotoBuilder.buildMotionPhoto(realJpegBytes, mp4Bytes, finalLivePhoto)
                            
                            // Alertar al sistema operativo para que la imagen aparezca al instante en la Galería
                            MediaScannerConnection.scanFile(this@MainActivity, arrayOf(finalLivePhoto.absolutePath), arrayOf("image/jpeg"), null)
                            
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "¡Live Photo Mágica Guardada!", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Log.e("LuminaPro", "Error al guardar Live Photo", e)
                        }
                    }
                }
            }
        }
        
        // Dispara el Zero Shutter Lag en la API Camera2
        cameraManager.takePicture()
    }

    private fun allPermissionsGranted() = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.closeCamera()
        videoEncoder.stop()
    }
}
