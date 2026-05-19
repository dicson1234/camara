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
import android.view.WindowManager
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
        private const val REQ = 10
    }

    private lateinit var camera: LuminaCameraManager
    private lateinit var encoder: CircularVideoEncoder
    private lateinit var viewFinder: TextureView
    private lateinit var captureButton: View
    private lateinit var captureRing: View
    private lateinit var switchBtn: View
    private lateinit var liveLabel: TextView
    private lateinit var flashBtn: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var timerLabel: TextView
    private lateinit var hdrBtn: TextView
    private lateinit var shutterFlash: View
    private lateinit var liveRec: TextView

    private var useFront = false
    private var liveOn = true
    private var flash = 0
    private var timer = 0
    private var hdr = false
    private var zoom = 1.0f
    private var ready = false

    private lateinit var scaleDetector: ScaleGestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        captureButton = findViewById(R.id.captureButton)
        captureRing = findViewById(R.id.captureRing)
        switchBtn = findViewById(R.id.switchCameraBtn)
        liveLabel = findViewById(R.id.liveIndicator)
        flashBtn = findViewById(R.id.flashBtn)
        zoomLabel = findViewById(R.id.zoomLabel)
        timerLabel = findViewById(R.id.timerLabel)
        hdrBtn = findViewById(R.id.hdrBtn)
        shutterFlash = findViewById(R.id.shutterFlash)
        liveRec = findViewById(R.id.liveRecording)

        setupZoom()
        setupButtons()

        if (allPermsOk()) initCamera()
        else ActivityCompat.requestPermissions(this, perms(), REQ)
    }

    private fun perms(): Array<String> {
        val p = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= 32) p.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 33) p.add("android.permission.READ_MEDIA_IMAGES")
        return p.toTypedArray()
    }

    private fun allPermsOk() = perms().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun setupZoom() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (!ready) return true
                zoom = (zoom * d.scaleFactor).coerceIn(1f, 10f)
                camera.setZoom(zoom)
                zoomLabel.text = String.format("%.1fx", zoom)
                return true
            }
        })
    }

    private fun setupButtons() {
        captureButton.setOnClickListener {
            if (!ready) return@setOnClickListener
            animateShutter()
            if (timer > 0) {
                toast("📸 ${timer}s...")
                it.postDelayed({ capture() }, timer * 1000L)
            } else capture()
        }

        switchBtn.setOnClickListener {
            if (!ready) return@setOnClickListener
            switchBtn.animate().rotationBy(180f).setDuration(300).start()
            useFront = !useFront; zoom = 1f; zoomLabel.text = "1.0x"
            restartCamera()
        }

        liveLabel.setOnClickListener {
            liveOn = !liveOn
            liveLabel.text = if (liveOn) "LIVE" else "OFF"
            liveLabel.alpha = if (liveOn) 1f else 0.4f
            liveLabel.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100)
                .withEndAction { liveLabel.animate().scaleX(1f).scaleY(1f).setDuration(100).start() }.start()
        }

        flashBtn.setOnClickListener {
            if (!ready) return@setOnClickListener
            flash = (flash + 1) % 3
            flashBtn.text = when (flash) { 0 -> "⚡OFF"; 1 -> "⚡ON"; else -> "⚡A" }
            camera.setFlash(flash)
        }

        timerLabel.setOnClickListener {
            timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 }
            timerLabel.text = if (timer == 0) "⏱" else "⏱${timer}s"
        }

        hdrBtn.setOnClickListener {
            if (!ready) return@setOnClickListener
            hdr = !hdr; hdrBtn.alpha = if (hdr) 1f else 0.4f; camera.setHdr(hdr)
        }

        viewFinder.setOnTouchListener { v, ev ->
            scaleDetector.onTouchEvent(ev)
            if (ev.action == MotionEvent.ACTION_UP && !scaleDetector.isInProgress && ready) {
                camera.focusAt(ev.x, ev.y, v.width, v.height)
                showFocusRing(ev.x, ev.y)
            }
            true
        }
    }

    // === ANIMACIONES ===

    private fun animateShutter() {
        val sx = ObjectAnimator.ofFloat(captureButton, "scaleX", 1f, 0.85f)
        val sy = ObjectAnimator.ofFloat(captureButton, "scaleY", 1f, 0.85f)
        val ux = ObjectAnimator.ofFloat(captureButton, "scaleX", 0.85f, 1f).apply { interpolator = OvershootInterpolator(3f) }
        val uy = ObjectAnimator.ofFloat(captureButton, "scaleY", 0.85f, 1f).apply { interpolator = OvershootInterpolator(3f) }
        AnimatorSet().apply {
            play(sx).with(sy); play(ux).with(uy).after(sx)
            sx.duration = 80; sy.duration = 80; ux.duration = 200; uy.duration = 200
            start()
        }
        shutterFlash.visibility = View.VISIBLE; shutterFlash.alpha = 0.6f
        shutterFlash.animate().alpha(0f).setDuration(200).withEndAction { shutterFlash.visibility = View.GONE }.start()
    }

    private fun showFocusRing(x: Float, y: Float) {
        val ring = View(this).apply {
            setBackgroundResource(R.drawable.focus_ring)
            layoutParams = FrameLayout.LayoutParams(80, 80)
            this.x = x - 40; this.y = y - 40; alpha = 0f; scaleX = 1.5f; scaleY = 1.5f
        }
        val parent = viewFinder.parent as? FrameLayout ?: return
        parent.addView(ring)
        ring.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200)
            .withEndAction { ring.animate().alpha(0f).setDuration(500).setStartDelay(500)
                .withEndAction { parent.removeView(ring) }.start() }.start()
    }

    // === CÁMARA ===

    private fun initCamera() {
        camera = LuminaCameraManager(this)
        camera.startBackgroundThread()
        encoder = CircularVideoEncoder(1280, 720, 4_000_000, 30)
        encoder.startDraining()
        ready = true

        viewFinder.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { openCamera(st) }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { camera.closeCamera(); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        if (viewFinder.isAvailable) openCamera(viewFinder.surfaceTexture!!)
    }

    private fun openCamera(st: SurfaceTexture) {
        st.setDefaultBufferSize(1920, 1080)
        camera.openCamera(Surface(st), encoder.inputSurface, useFront)
        viewFinder.postDelayed({ configureTransform() }, 300)
    }

    private fun configureTransform() {
        val pw = camera.previewSize.height.toFloat() // rotado 90°
        val ph = camera.previewSize.width.toFloat()
        val vw = viewFinder.width.toFloat()
        val vh = viewFinder.height.toFloat()
        if (vw == 0f || vh == 0f) return

        val sx = vw / pw; val sy = vh / ph
        val scale = Math.max(sx, sy)
        val matrix = Matrix()
        matrix.setScale(pw * scale / vw, ph * scale / vh)
        matrix.postTranslate((vw - pw * scale) / 2f, (vh - ph * scale) / 2f)
        viewFinder.setTransform(matrix)
    }

    private fun restartCamera() {
        camera.closeCamera()
        camera.startBackgroundThread()
        viewFinder.surfaceTexture?.let { openCamera(it) }
    }

    /**
     * Obtiene la rotación actual del dispositivo en grados (0, 90, 180, 270)
     */
    private fun getDeviceRotation(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: 0
                       else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        return when (rotation) {
            android.view.Surface.ROTATION_0 -> 0
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    // === CAPTURA ===

    private fun capture() {
        val deviceRotation = getDeviceRotation()
        Log.i(TAG, "Capture: live=$liveOn, encoderReady=${encoder.isReady()}, encoderConnected=${camera.encoderConnected}")

        camera.onPhotoCaptured = { jpegBytes ->
            Log.i(TAG, "📸 Got ${jpegBytes.size} bytes")

            val canLive = liveOn && camera.encoderConnected && encoder.isReady()

            if (canLive) {
                // === LIVE PHOTO ===
                runOnUiThread {
                    liveRec.visibility = View.VISIBLE; liveRec.alpha = 1f
                    ObjectAnimator.ofFloat(liveRec, "alpha", 1f, 0.3f, 1f).apply {
                        duration = 500; repeatCount = 4; start()
                    }
                }

                val tmp = File(cacheDir, "tmp_mp4_${System.currentTimeMillis()}.mp4")
                encoder.extractVideo(tmp, 1500L) { mp4Bytes ->
                    runOnUiThread {
                        liveRec.animate().alpha(0f).setDuration(200)
                            .withEndAction { liveRec.visibility = View.GONE }.start()
                    }

                    // SIEMPRE guardar algo, pase lo que pase
                    CoroutineScope(Dispatchers.IO).launch {
                        if (mp4Bytes != null && mp4Bytes.size > 1000) {
                            try {
                                Log.i(TAG, "🎬 Building Motion Photo: jpeg=${jpegBytes.size}, mp4=${mp4Bytes.size}")
                                val motion = LivePhotoBuilder.buildMotionPhotoBytes(jpegBytes, mp4Bytes)
                                saveToGallery(motion, "LuminaMP_${System.currentTimeMillis()}.jpg")
                                showToast("¡Live Photo! ✨")
                            } catch (e: Exception) {
                                Log.e(TAG, "Motion Photo build failed", e)
                                saveToGallery(jpegBytes, "Lumina_${System.currentTimeMillis()}.jpg")
                                showToast("Foto guardada")
                            }
                        } else {
                            Log.w(TAG, "No video data, saving static photo")
                            saveToGallery(jpegBytes, "Lumina_${System.currentTimeMillis()}.jpg")
                            showToast("Foto guardada ✨")
                        }
                    }
                }
            } else {
                // === FOTO NORMAL ===
                CoroutineScope(Dispatchers.IO).launch {
                    saveToGallery(jpegBytes, "Lumina_${System.currentTimeMillis()}.jpg")
                    showToast("Foto guardada ✨")
                }
            }
        }

        camera.takePicture(deviceRotation)
    }

    // === GALERÍA ===

    private fun saveToGallery(bytes: ByteArray, name: String) {
        Log.i(TAG, "💾 Saving: $name (${bytes.size} bytes)")
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/LuminaPro")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) { Log.e(TAG, "❌ insert null"); return }

            contentResolver.openOutputStream(uri)?.use { it.write(bytes); it.flush() }
                ?: run { Log.e(TAG, "❌ stream null"); return }

            if (Build.VERSION.SDK_INT >= 29) {
                contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            }
            Log.i(TAG, "✅ Saved: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Save error", e)
        }
    }

    private suspend fun showToast(msg: String) {
        withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    // === PERMISOS ===

    override fun onRequestPermissionsResult(code: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (code == REQ && allPermsOk()) initCamera()
        else if (code == REQ) toast("Activa los permisos en Ajustes")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ready) { camera.closeCamera(); encoder.stop() }
    }
}
