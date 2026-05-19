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
import com.dicson.luminapro.camera.LivePhotoBuilder
import com.dicson.luminapro.camera.LuminaCameraManager
import com.dicson.luminapro.camera.PreviewVideoRecorder
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LuminaPro"
        private const val REQ = 10
    }

    private lateinit var cam: LuminaCameraManager
    private lateinit var recorder: PreviewVideoRecorder
    private lateinit var viewFinder: TextureView

    private lateinit var captureBtn: View
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
    private var zoom = 1f
    private var ready = false
    private lateinit var scaleDetector: ScaleGestureDetector

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        captureBtn = findViewById(R.id.captureButton)
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

        if (allOk()) initCamera() else ActivityCompat.requestPermissions(this, perms(), REQ)
    }

    private fun perms(): Array<String> {
        val p = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= 32) p.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 33) p.add("android.permission.READ_MEDIA_IMAGES")
        return p.toTypedArray()
    }

    private fun allOk() = perms().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun setupZoom() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (!ready) return true
                zoom = (zoom * d.scaleFactor).coerceIn(1f, 10f)
                cam.zoom(zoom)
                zoomLabel.text = String.format("%.1fx", zoom)
                return true
            }
        })
    }

    private fun setupButtons() {
        captureBtn.setOnClickListener {
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
            cam.flash(flash)
        }

        timerLabel.setOnClickListener {
            timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 }
            timerLabel.text = if (timer == 0) "⏱" else "⏱${timer}s"
        }

        hdrBtn.setOnClickListener {
            if (!ready) return@setOnClickListener
            hdr = !hdr; hdrBtn.alpha = if (hdr) 1f else 0.4f; cam.hdr(hdr)
        }

        viewFinder.setOnTouchListener { v, ev ->
            scaleDetector.onTouchEvent(ev)
            if (ev.action == MotionEvent.ACTION_UP && !scaleDetector.isInProgress && ready) {
                cam.focus(ev.x, ev.y, v.width, v.height)
                showFocusRing(ev.x, ev.y)
            }
            true
        }
    }

    // === ANIMACIONES ===

    private fun animateShutter() {
        val sx = ObjectAnimator.ofFloat(captureBtn, "scaleX", 1f, 0.85f)
        val sy = ObjectAnimator.ofFloat(captureBtn, "scaleY", 1f, 0.85f)
        val ux = ObjectAnimator.ofFloat(captureBtn, "scaleX", 0.85f, 1f).apply { interpolator = OvershootInterpolator(3f) }
        val uy = ObjectAnimator.ofFloat(captureBtn, "scaleY", 0.85f, 1f).apply { interpolator = OvershootInterpolator(3f) }
        AnimatorSet().apply {
            play(sx).with(sy); play(ux).with(uy).after(sx)
            sx.duration = 80; sy.duration = 80; ux.duration = 200; uy.duration = 200; start()
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
            .withEndAction {
                ring.animate().alpha(0f).setDuration(500).setStartDelay(500)
                    .withEndAction { parent.removeView(ring) }.start()
            }.start()
    }

    // === CÁMARA ===

    private fun initCamera() {
        cam = LuminaCameraManager(this)
        cam.startBg()
        recorder = PreviewVideoRecorder()
        ready = true

        viewFinder.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { openCam(st) }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { cam.close(); recorder.stop(); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        if (viewFinder.isAvailable) openCam(viewFinder.surfaceTexture!!)
    }

    private fun openCam(st: SurfaceTexture) {
        st.setDefaultBufferSize(1920, 1080)
        cam.open(Surface(st), useFront)
        // Iniciar captura de preview para Live Photo (desde el TextureView, NO desde la cámara)
        viewFinder.postDelayed({
            recorder.startCapturing(viewFinder)
            configureTransform()
        }, 500)
    }

    private fun configureTransform() {
        val pw = cam.previewSize.height.toFloat()
        val ph = cam.previewSize.width.toFloat()
        val vw = viewFinder.width.toFloat()
        val vh = viewFinder.height.toFloat()
        if (vw == 0f || vh == 0f) return
        val sx = vw / pw; val sy = vh / ph; val s = Math.max(sx, sy)
        val m = Matrix()
        m.setScale(pw * s / vw, ph * s / vh)
        m.postTranslate((vw - pw * s) / 2f, (vh - ph * s) / 2f)
        viewFinder.setTransform(m)
    }

    private fun restartCamera() {
        recorder.stop()
        cam.close()
        cam.startBg()
        viewFinder.surfaceTexture?.let { openCam(it) }
    }

    private fun getRotation(): Int {
        val r = if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: 0
                else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        return when (r) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
    }

    // === CAPTURA ===

    private fun capture() {
        Log.i(TAG, "Capture: live=$liveOn, recorderReady=${recorder.isReady()}")

        cam.onPhoto = { jpegBytes ->
            Log.i(TAG, "📸 Got ${jpegBytes.size}b")

            if (liveOn && recorder.isReady()) {
                // === LIVE PHOTO ===
                runOnUiThread {
                    liveRec.visibility = View.VISIBLE; liveRec.alpha = 1f
                    ObjectAnimator.ofFloat(liveRec, "alpha", 1f, 0.3f, 1f).apply {
                        duration = 500; repeatCount = 4; start()
                    }
                }

                recorder.extractVideo(cacheDir, 1500L) { mp4Bytes ->
                    runOnUiThread {
                        liveRec.animate().alpha(0f).setDuration(200)
                            .withEndAction { liveRec.visibility = View.GONE }.start()
                    }

                    CoroutineScope(Dispatchers.IO).launch {
                        if (mp4Bytes != null && mp4Bytes.size > 1000) {
                            try {
                                Log.i(TAG, "🎬 Building Motion Photo: jpg=${jpegBytes.size} mp4=${mp4Bytes.size}")
                                val motion = LivePhotoBuilder.buildMotionPhotoBytes(jpegBytes, mp4Bytes)
                                save(motion, "LuminaMP_${System.currentTimeMillis()}.jpg")
                                showToast("¡Live Photo! ✨🎬")
                            } catch (e: Exception) {
                                Log.e(TAG, "Motion Photo error", e)
                                save(jpegBytes, "Lumina_${System.currentTimeMillis()}.jpg")
                                showToast("Foto guardada")
                            }
                        } else {
                            Log.w(TAG, "No video, saving static")
                            save(jpegBytes, "Lumina_${System.currentTimeMillis()}.jpg")
                            showToast("Foto guardada ✨")
                        }
                    }
                }
            } else {
                // === FOTO NORMAL ===
                CoroutineScope(Dispatchers.IO).launch {
                    save(jpegBytes, "Lumina_${System.currentTimeMillis()}.jpg")
                    showToast("Foto guardada ✨")
                }
            }
        }

        cam.takePicture(getRotation())
    }

    // === GALERÍA ===

    private fun save(bytes: ByteArray, name: String) {
        Log.i(TAG, "💾 $name (${bytes.size}b)")
        try {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/LuminaPro")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: run { Log.e(TAG, "❌ insert null"); return }
            contentResolver.openOutputStream(uri)?.use { it.write(bytes); it.flush() } ?: run { Log.e(TAG, "❌ stream null"); return }
            if (Build.VERSION.SDK_INT >= 29) {
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            Log.i(TAG, "✅ Saved $uri")
        } catch (e: Exception) { Log.e(TAG, "Save error", e) }
    }

    private suspend fun showToast(m: String) { withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, m, Toast.LENGTH_SHORT).show() } }
    private fun toast(m: String) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }

    override fun onRequestPermissionsResult(c: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (c == REQ && allOk()) initCamera() else if (c == REQ) toast("Activa permisos")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ready) { recorder.stop(); cam.close() }
    }
}
