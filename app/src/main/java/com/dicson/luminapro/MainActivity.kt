package com.dicson.luminapro

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
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
    private lateinit var captureRing: View
    private lateinit var switchCameraBtn: View
    private lateinit var liveIndicator: TextView
    private lateinit var flashBtn: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var timerLabel: TextView
    private lateinit var hdrBtn: TextView
    private lateinit var shutterFlash: View
    private lateinit var liveRecording: TextView

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
        captureRing = findViewById(R.id.captureRing)
        switchCameraBtn = findViewById(R.id.switchCameraBtn)
        liveIndicator = findViewById(R.id.liveIndicator)
        flashBtn = findViewById(R.id.flashBtn)
        zoomLabel = findViewById(R.id.zoomLabel)
        timerLabel = findViewById(R.id.timerLabel)
        hdrBtn = findViewById(R.id.hdrBtn)
        shutterFlash = findViewById(R.id.shutterFlash)
        liveRecording = findViewById(R.id.liveRecording)

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
            animateShutterButton()
            if (timerSeconds > 0) {
                Toast.makeText(this, "📸 ${timerSeconds}s...", Toast.LENGTH_SHORT).show()
                captureButton.postDelayed({ takeLivePhoto() }, timerSeconds * 1000L)
            } else {
                takeLivePhoto()
            }
        }

        switchCameraBtn.setOnClickListener {
            if (!cameraInitialized) return@setOnClickListener
            // Animación de rotación
            switchCameraBtn.animate().rotationBy(180f).setDuration(300).start()
            isUsingFrontCamera = !isUsingFrontCamera
            currentZoom = 1.0f
            zoomLabel.text = "1.0x"
            restartCamera()
        }

        liveIndicator.setOnClickListener {
            livePhotoEnabled = !livePhotoEnabled
            liveIndicator.text = if (livePhotoEnabled) "LIVE" else "OFF"
            liveIndicator.alpha = if (livePhotoEnabled) 1f else 0.4f
            // Pulse animation
            liveIndicator.animate().scaleX(1.2f).scaleY(1.2f).setDuration(150)
                .withEndAction { liveIndicator.animate().scaleX(1f).scaleY(1f).setDuration(150).start() }.start()
        }

        flashBtn.setOnClickListener {
            if (!cameraInitialized) return@setOnClickListener
            flashMode = (flashMode + 1) % 3
            flashBtn.text = when (flashMode) { 0 -> "⚡OFF"; 1 -> "⚡ON"; else -> "⚡A" }
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
                showFocusIndicator(event.x, event.y)
            }
            true
        }
    }

    // === ANIMACIONES ===

    private fun animateShutterButton() {
        val scaleDown = ObjectAnimator.ofFloat(captureButton, "scaleX", 1f, 0.85f)
        val scaleDownY = ObjectAnimator.ofFloat(captureButton, "scaleY", 1f, 0.85f)
        val scaleUp = ObjectAnimator.ofFloat(captureButton, "scaleX", 0.85f, 1f)
        val scaleUpY = ObjectAnimator.ofFloat(captureButton, "scaleY", 0.85f, 1f)
        scaleUp.interpolator = OvershootInterpolator(3f)
        scaleUpY.interpolator = OvershootInterpolator(3f)

        val set = AnimatorSet()
        set.play(scaleDown).with(scaleDownY)
        set.play(scaleUp).with(scaleUpY).after(scaleDown)
        scaleDown.duration = 100
        scaleDownY.duration = 100
        scaleUp.duration = 200
        scaleUpY.duration = 200
        set.start()

        // Shutter flash effect
        shutterFlash.visibility = View.VISIBLE
        shutterFlash.alpha = 0.7f
        shutterFlash.animate().alpha(0f).setDuration(200).withEndAction {
            shutterFlash.visibility = View.GONE
        }.start()
    }

    private fun showFocusIndicator(x: Float, y: Float) {
        // Crear un indicador visual temporal de enfoque
        val focusView = View(this).apply {
            setBackgroundResource(R.drawable.focus_ring)
            layoutParams = FrameLayout.LayoutParams(80, 80)
            this.x = x - 40
            this.y = y - 40
            alpha = 0f
            scaleX = 1.5f
            scaleY = 1.5f
        }
        val container = viewFinder.parent as? FrameLayout
        if (container == null) {
            // Si el parent no es FrameLayout, simplemente ignorar la animación
            return
        }
        container.addView(focusView)
        focusView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200)
            .withEndAction {
                focusView.animate().alpha(0f).setDuration(500).setStartDelay(500)
                    .withEndAction { container.removeView(focusView) }.start()
            }.start()
    }

    private fun showLiveRecordingAnimation() {
        liveRecording.visibility = View.VISIBLE
        liveRecording.alpha = 1f
        // Parpadeo
        val blink = ObjectAnimator.ofFloat(liveRecording, "alpha", 1f, 0.3f, 1f)
        blink.duration = 600
        blink.repeatCount = 4
        blink.start()
    }

    private fun hideLiveRecordingAnimation() {
        liveRecording.animate().alpha(0f).setDuration(200).withEndAction {
            liveRecording.visibility = View.GONE
        }.start()
    }

    // === CÁMARA ===

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
        // Primero abrir para detectar el preview size óptimo
        st.setDefaultBufferSize(1920, 1080)
        val previewSurface = Surface(st)
        luminaCamera.openCamera(previewSurface, videoEncoder.inputSurface, 1920, 1080, isUsingFrontCamera)

        // Después de abrir, ajustar la transformación del TextureView
        viewFinder.post {
            configureTransform(viewFinder.width, viewFinder.height)
        }
    }

    /**
     * Ajusta el TextureView para que el preview no se vea con zoom/stretch.
     * Escala el TextureView para que llene la pantalla manteniendo el aspect ratio.
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val previewSize = luminaCamera.detectedPreviewSize
        val matrix = Matrix()

        val previewW = previewSize.height.toFloat() // Rotado 90°
        val previewH = previewSize.width.toFloat()
        val viewW = viewWidth.toFloat()
        val viewH = viewHeight.toFloat()

        val scaleX = viewW / previewW
        val scaleY = viewH / previewH
        val scale = Math.max(scaleX, scaleY) // Llenar toda la pantalla (center-crop)

        val scaledW = previewW * scale
        val scaledH = previewH * scale

        matrix.setScale(scaledW / viewW, scaledH / viewH)
        matrix.postTranslate((viewW - scaledW) / 2f, (viewH - scaledH) / 2f)

        viewFinder.setTransform(matrix)
    }

    private fun restartCamera() {
        luminaCamera.closeCamera()
        luminaCamera.startBackgroundThread()
        val st = viewFinder.surfaceTexture ?: return
        openCameraWithTexture(st)
    }

    // === CAPTURA ===

    private fun takeLivePhoto() {
        Log.i(TAG, "takeLivePhoto: live=$livePhotoEnabled, encoderReady=${videoEncoder.isReady()}, encoderConnected=${luminaCamera.encoderConnected}")

        luminaCamera.onPhotoCaptured = { jpegBytes ->
            Log.i(TAG, "📸 Foto recibida: ${jpegBytes.size} bytes")
            val canLive = livePhotoEnabled && luminaCamera.encoderConnected && videoEncoder.isReady()

            if (canLive) {
                runOnUiThread { showLiveRecordingAnimation() }
                val tmpFile = File(cacheDir, "tmp_live.mp4")
                videoEncoder.extractLivePhotoVideo(tmpFile, 1500L) { mp4Bytes ->
                    runOnUiThread { hideLiveRecordingAnimation() }
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            if (mp4Bytes != null && mp4Bytes.isNotEmpty()) {
                                Log.i(TAG, "🎬 Video capturado: ${mp4Bytes.size} bytes")
                                val liveBytes = LivePhotoBuilder.buildMotionPhotoBytes(jpegBytes, mp4Bytes)
                                saveToGallery(liveBytes, "LuminaMP_${System.currentTimeMillis()}.jpg")
                                showToast("¡Live Photo guardada! ✨")
                            } else {
                                Log.w(TAG, "Video vacío, guardando solo foto")
                                saveToGallery(jpegBytes, "Lumina_${System.currentTimeMillis()}.jpg")
                                showToast("Foto guardada ✨")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error empaquetando Live Photo", e)
                            saveToGallery(jpegBytes, "Lumina_${System.currentTimeMillis()}.jpg")
                            showToast("Foto guardada")
                        }
                    }
                }
            } else {
                // Guardar foto normal directamente
                CoroutineScope(Dispatchers.IO).launch {
                    Log.i(TAG, "💾 Guardando foto estática...")
                    saveToGallery(jpegBytes, "Lumina_${System.currentTimeMillis()}.jpg")
                    showToast("Foto guardada ✨")
                }
            }
        }
        luminaCamera.takePicture()
    }

    private fun saveToGallery(imageBytes: ByteArray, filename: String) {
        Log.i(TAG, "💾 saveToGallery: $filename (${imageBytes.size} bytes)")
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/LuminaPro")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                Log.e(TAG, "❌ MediaStore insert retornó null - ¿permisos?")
                return
            }
            contentResolver.openOutputStream(uri)?.use { os ->
                os.write(imageBytes)
                os.flush()
            } ?: run {
                Log.e(TAG, "❌ openOutputStream retornó null para $uri")
                return
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val update = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                contentResolver.update(uri, update, null, null)
            }
            Log.i(TAG, "✅ Guardada en galería: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error guardando en galería", e)
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
