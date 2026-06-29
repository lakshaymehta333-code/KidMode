package com.phonenap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.IBinder
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.*
import kotlin.coroutines.resume

class PhoneNapService : Service(), LifecycleOwner {

    companion object {
        const val TAG        = "PhoneNapService"
        const val CHANNEL_ID = "phonenap_service"
        const val NOTIF_ID   = 42

        private const val LOW_LIGHT_LUX     = 8f   // below this → fire overlay (too dark to scan)
        private const val FRAMES_PER_SCAN   = 3    // capture N frames, use best result
        private const val FRAME_INTERVAL_MS = 350L // gap between burst frames

        @Volatile var instance: PhoneNapService? = null
    }

    // ── Lifecycle (required for CameraX in Service) ───────────────────────────
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    // ── State ─────────────────────────────────────────────────────────────────
    lateinit var prefs: PrefsManager
    lateinit var overlayManager: OverlayManager
    private lateinit var faceManager: FaceManager
    private lateinit var sensorMgr: SensorManager

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isAnalysing = false

    // ── Ambient light ─────────────────────────────────────────────────────────
    private var ambientLux = 50f // default: assume adequate light

    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) { ambientLux = e.values[0] }
        override fun onAccuracyChanged(s: Sensor, a: Int) {}
    }

    // ── Motion trigger ────────────────────────────────────────────────────────
    private val motionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            registerMotionSensor()
            if (!isAnalysing) scope.launch { runFaceScan() }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        prefs          = PrefsManager(this)
        faceManager    = FaceManager(this)
        overlayManager = OverlayManager(this)
        sensorMgr      = getSystemService(SENSOR_SERVICE) as SensorManager

        startForeground(NOTIF_ID, buildNotification())
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        registerLightSensor()
        registerMotionSensor()
        startPeriodicScan()

        if (prefs.isActive()) overlayManager.show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        instance = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        sensorMgr.unregisterListener(lightListener)
        cancelMotionSensor()
        overlayManager.hide()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Periodic scan fallback ────────────────────────────────────────────────

    private fun startPeriodicScan() {
        scope.launch {
            while (true) {
                delay(20_000L)
                if (!isAnalysing && !prefs.isActive()) runFaceScan()
            }
        }
    }

    // ── Sensors ───────────────────────────────────────────────────────────────

    private fun registerLightSensor() {
        val s = sensorMgr.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return
        sensorMgr.registerListener(lightListener, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun registerMotionSensor() {
        val s = sensorMgr.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        sensorMgr.requestTriggerSensor(motionListener, s)
    }

    private fun cancelMotionSensor() {
        val s = sensorMgr.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        sensorMgr.cancelTriggerSensor(motionListener, s)
    }

    // ── Face scan ─────────────────────────────────────────────────────────────

    suspend fun runFaceScan() {
        isAnalysing = true
        try {
            // Low-light guard: if it's too dark, fire overlay immediately (safe default)
            if (ambientLux < LOW_LIGHT_LUX) {
                Log.d(TAG, "Low light (${ambientLux} lux) — firing overlay as safe default")
                prefs.setActive(true)
                overlayManager.show()
                return
            }

            // Capture FRAMES_PER_SCAN frames and take the highest-confidence result
            var bestResult = FaceResult.NO_FACE
            var kidSeen    = 0

            repeat(FRAMES_PER_SCAN) { frameIdx ->
                if (frameIdx > 0) delay(FRAME_INTERVAL_MS)

                val (bmp, rot) = captureFrame() ?: run {
                    Log.w(TAG, "Frame $frameIdx: capture returned null")
                    return@repeat
                }

                val result = faceManager.analyze(bmp, rot)
                Log.d(TAG, "Frame $frameIdx → $result")

                when (result) {
                    FaceResult.KID     -> kidSeen++
                    FaceResult.NOT_KID -> { /* counted below */ }
                    FaceResult.NO_FACE -> { /* no face, skip */ }
                }
                bestResult = result
            }

            // Need at least 2 out of 3 frames to confirm kid (reduces false positives)
            val confirmed = kidSeen >= (FRAMES_PER_SCAN / 2 + 1)
            Log.d(TAG, "Kid seen in $kidSeen/$FRAMES_PER_SCAN frames → confirmed=$confirmed")

            if (confirmed) {
                prefs.setActive(true)
                overlayManager.show()
            }
        } finally {
            isAnalysing = false
        }
    }

    fun triggerActiveScan() {
        if (!isAnalysing) scope.launch { runFaceScan() }
    }

    // ── Camera capture ────────────────────────────────────────────────────────

    private suspend fun captureFrame(): Pair<Bitmap, Int>? = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            var provider: ProcessCameraProvider? = null
            try {
                provider = future.get()
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, capture)

                capture.takePicture(
                    ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val bmp = image.toBitmap()
                            val rot = image.imageInfo.rotationDegrees
                            image.close()
                            provider?.unbindAll()
                            cont.resume(Pair(bmp, rot))
                        }
                        override fun onError(e: ImageCaptureException) {
                            Log.e(TAG, "Capture error: ${e.message}")
                            provider?.unbindAll()
                            cont.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera setup failed: ${e.message}")
                provider?.unbindAll()
                cont.resume(null)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "PhoneNap", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "PhoneNap is monitoring face automatically" }
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PhoneNap Active")
            .setContentText("Monitoring face automatically")
            .setSmallIcon(R.drawable.ic_tile)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
