package com.kidmode

import android.content.Intent
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.kidmode.databinding.ActivityBoringBinding
import kotlinx.coroutines.*
import java.util.Calendar

/**
 * The boring screen shown during Kid Mode.
 *
 * - Grayscale window filter
 * - Dims brightness 80% → 2% over 20 seconds, then stays dim
 * - Back button blocked
 * - Long-press top-right corner exits Kid Mode immediately (no PIN)
 */
class BoringActivity : AppCompatActivity() {

    companion object {
        private var instance: java.lang.ref.WeakReference<BoringActivity>? = null
        fun dismiss() { instance?.get()?.exitKidMode() }
    }

    private lateinit var binding: ActivityBoringBinding
    private val handler = Handler(Looper.getMainLooper())
    private val scope   = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val clockTick = object : Runnable {
        override fun run() {
            val cal = Calendar.getInstance()
            binding.tvClock.text = "%02d:%02d".format(
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
            )
            handler.postDelayed(this, 10_000)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        instance = java.lang.ref.WeakReference(this)

        binding = ActivityBoringBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        applyGrayscaleFilter()
        startDimSequence()
        startClock()

        // Long-press the invisible top-right corner to exit Kid Mode (no PIN needed)
        binding.parentUnlockArea.setOnLongClickListener {
            exitKidMode()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        KidModeService.boringActivityVisible = true
    }

    override fun onPause() {
        super.onPause()
        KidModeService.boringActivityVisible = false
    }

    override fun onDestroy() {
        handler.removeCallbacks(clockTick)
        scope.cancel()
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Block back — kids cannot exit this way
    }

    // ── Exit ──────────────────────────────────────────────────────────────────

    fun exitKidMode() {
        startService(Intent(this, KidModeService::class.java).apply {
            action = KidModeService.ACTION_STOP
        })
        finishAndRemoveTask()
    }

    // ── Grayscale ─────────────────────────────────────────────────────────────

    private fun applyGrayscaleFilter() {
        val paint = Paint()
        val matrix = ColorMatrix().also { it.setSaturation(0f) }
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        window.decorView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
    }

    // ── 20-second dim sequence ────────────────────────────────────────────────

    private fun startDimSequence() {
        val startBrightness = 0.8f
        val endBrightness   = 0.02f
        val durationMs      = 20_000L
        val startTime       = System.currentTimeMillis()

        scope.launch {
            while (isActive) {
                val elapsed    = System.currentTimeMillis() - startTime
                val progress   = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
                val brightness = startBrightness + (endBrightness - startBrightness) * progress

                val lp = window.attributes
                lp.screenBrightness = brightness
                window.attributes = lp

                if (progress >= 1f) {
                    // Done dimming — remove keep-screen-on and let the OS decide
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    break
                }
                delay(500)
            }
        }
    }

    // ── Clock ─────────────────────────────────────────────────────────────────

    private fun startClock() { handler.post(clockTick) }
}
