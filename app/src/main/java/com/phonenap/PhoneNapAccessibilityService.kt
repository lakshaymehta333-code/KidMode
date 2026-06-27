package com.phonenap

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import kotlinx.coroutines.*
import java.util.Calendar

class PhoneNapAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_ACTIVATE   = "com.phonenap.ACTIVATE"
        const val ACTION_DEACTIVATE = "com.phonenap.DEACTIVATE"

        /** Non-null while the service is alive — used by BiometricUnlockActivity to restore overlay. */
        @Volatile var instance: PhoneNapAccessibilityService? = null
    }

    private lateinit var wm: WindowManager
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var dimJob: Job? = null

    // ── Broadcast receiver ────────────────────────────────────────────────────

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_ACTIVATE          -> activateOverlay()
                ACTION_DEACTIVATE        -> deactivateOverlay()
                Intent.ACTION_SCREEN_ON  -> onScreenOn()
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter().apply {
            addAction(ACTION_ACTIVATE)
            addAction(ACTION_DEACTIVATE)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        // If PhoneNap was active before the service restarted, restore the overlay
        if (PrefsManager.isActive(this)) {
            showBoringScreen()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        scope.cancel()
        removeOverlay()
        super.onDestroy()
    }

    // ── Activate / Deactivate ─────────────────────────────────────────────────

    private fun activateOverlay() {
        PrefsManager.setActive(this, true)
        showBoringScreen()
        refreshTile()
    }

    fun deactivateOverlay() {
        PrefsManager.setActive(this, false)
        dimJob?.cancel()
        removeOverlay()
        refreshTile()
    }

    private fun onScreenOn() {
        if (PrefsManager.isActive(this)) showWakeScreen()
    }

    // ── Show overlays ─────────────────────────────────────────────────────────

    private fun showBoringScreen() {
        removeOverlay()
        val view   = inflate(R.layout.overlay_boring)
        val params = makeParams()
        applyGrayscale(view)
        wm.addView(view, params)
        overlayView   = view
        overlayParams = params
        startClock(view)
        startDim(view, params)
    }

    private fun showWakeScreen() {
        removeOverlay()
        val view   = inflate(R.layout.overlay_wake)
        val params = makeParams()
        applyGrayscale(view)
        view.findViewById<View>(R.id.btnFingerprint).setOnClickListener { requestBiometric() }
        wm.addView(view, params)
        overlayView   = view
        overlayParams = params
        startClock(view)
    }

    // ── Dimming ───────────────────────────────────────────────────────────────

    private fun startDim(view: View, params: WindowManager.LayoutParams) {
        val progressFill  = view.findViewById<View>(R.id.progressFill)
        val progressLabel = view.findViewById<TextView>(R.id.progressLabel)
        val start    = System.currentTimeMillis()
        val duration = 45_000L

        dimJob?.cancel()
        dimJob = scope.launch {
            while (isActive) {
                val elapsed  = System.currentTimeMillis() - start
                val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                val secsLeft = ((duration - elapsed) / 1000).coerceAtLeast(0)

                // Dim screen via overlay brightness (0.8 → 0.01 over 45s)
                params.screenBrightness = 0.8f + (0.01f - 0.8f) * progress
                try { wm.updateViewLayout(view, params) } catch (_: Exception) { break }

                progressFill.scaleX  = progress
                progressFill.pivotX  = 0f
                progressLabel.text   = "Going dark in ${secsLeft}s…"

                if (progress >= 1f) {
                    goToSleep()
                    break
                }
                delay(500)
            }
        }
    }

    private fun goToSleep() {
        removeOverlay()
        // Lock screen — overlay will reappear instantly when kid wakes it (ACTION_SCREEN_ON)
        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }

    // ── Biometric unlock ──────────────────────────────────────────────────────

    private fun requestBiometric() {
        // Hide overlay so BiometricUnlockActivity is visible; restored on failure
        overlayView?.visibility = View.INVISIBLE
        startActivity(
            Intent(this, BiometricUnlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    /** Called by BiometricUnlockActivity on cancel/error to put the overlay back. */
    fun restoreOverlay() {
        overlayView?.visibility = View.VISIBLE
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun inflate(layoutRes: Int): View =
        LayoutInflater.from(this).inflate(layoutRes, null)

    private fun makeParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        PixelFormat.OPAQUE
    ).also { it.screenBrightness = 0.8f }

    private fun applyGrayscale(view: View) {
        val paint = Paint()
        paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().also { it.setSaturation(0f) })
        view.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
    }

    private fun startClock(view: View) {
        val tv = view.findViewById<TextView?>(R.id.tvClock) ?: return
        val tick = object : Runnable {
            override fun run() {
                val c = Calendar.getInstance()
                tv.text = "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
                tv.postDelayed(this, 10_000)
            }
        }
        tv.post(tick)
    }

    private fun removeOverlay() {
        dimJob?.cancel()
        overlayView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlayView   = null
        overlayParams = null
    }

    private fun refreshTile() =
        sendBroadcast(Intent(PhoneNapTileService.ACTION_REFRESH_TILE).setPackage(packageName))
}
