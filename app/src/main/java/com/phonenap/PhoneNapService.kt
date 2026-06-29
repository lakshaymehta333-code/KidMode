package com.phonenap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.Sensor
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
        const val TAG = "PhoneNapService"
        const val CHANNEL_ID = "phonenap_service"
        const val NOTIF_ID = 42

        // Shared instance — used by OverlayManager to trigger active scan
        @Volatile var instance: PhoneNapService? = null
    }

    // ── Lifecycle boilerplate (needed for CameraX in Service) ─────────────────
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    // ── State ─────────────────────────────────────────────────────────────────
    lateinit var prefs: PrefsManager
    lateinit var overlayManager: OverlayManager
    private lateinit var faceManager: FaceManager
    private lateinit var sensorMgr: SensorManager

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isAnalysing = false

    // ── Motion trigger ────────────────────────────────────────────────────────

    private val motionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            // TYPE_SIGNIFICANT_MOTION auto-unregisters after firing — re-arm immediately
            registerMotionSensor()
            if (!isAnalysing) scope.launch { runFaceScan() }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        prefs         = PrefsManager(this)
        faceManager   = FaceManager(this)
        overlayManager = OverlayManager(this)
        sensorMgr     = getSystemService(SENSOR_SERVICE) as SensorManager

        startForeground(NOTIF_ID, buildNotification())
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        registerMotionSensor()

        // If active before restart, restore overlay
        if (prefs.isActive()) overlayManager.show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        instance = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        cancelMotionSensor()
        overlayManager.hide()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Motion sensor ─────────────────────────────────────────────────────────

    private fun registerMotionSensor() {
        val sensor = sensorMgr.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        sensorMgr.requestTriggerSensor(motionListener, sensor)
    }

    private fun cancelMotionSensor() {
        val sensor = sensorMgr.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        sensorMgr.cancelTriggerSensor(motionListener, sensor)
    }

    // ── Face scan ─────────────────────────────────────────────────────────────

    /** Called automatically on motion, or manually when parent taps "Scan face". */
    suspend fun runFaceScan() {
        isAnalysing = true
        try {
            val bitmap = captureFrame()
            if (bitmap == null) {
                Log.w(TAG, "captureFrame returned null")
                return
            }
            val result = faceManager.analyze(bitmap)
            Log.d(TAG, "Face result: $result")
            when (result) {
                FaceResult.KID     -> {
                    prefs.setActive(true)
                    overlayManager.show()
                }
                FaceResult.NOT_KID,
                FaceResult.NO_FACE -> { /* no action — overlay stays if already shown */ }
            }
        } finally {
            isAnalysing = false
        }
    }

    /** Called by OverlayManager's unlock button — runs a scan immediately. */
    fun triggerActiveScan() {
        if (!isAnalysing) scope.launch { runFaceScan() }
    }

    // ── Camera capture (one-shot) ─────────────────────────────────────────────

    private suspend fun captureFrame(): Bitmap? = suspendCancellableCoroutine { cont ->
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
                            image.close()
                            provider?.unbindAll()
                            cont.resume(bmp)
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
                .apply { description = "PhoneNap is watching for unauthorised use" }
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
