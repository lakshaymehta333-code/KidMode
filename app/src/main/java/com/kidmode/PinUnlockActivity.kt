package com.kidmode

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.kidmode.databinding.ActivityPinUnlockBinding

/**
 * Numeric keypad that the parent uses to disable Kid Mode.
 * A wrong PIN vibrates and clears the entry field.
 */
class PinUnlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPinUnlockBinding
    private var entered = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        binding = ActivityPinUnlockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNumpad()

        binding.btnCancel.setOnClickListener { finish() }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Allow cancel via back — parent may have mistyped and want to dismiss.
        finish()
    }

    // ── Numpad ────────────────────────────────────────────────────────────────

    private fun setupNumpad() {
        val digitButtons = listOf(
            binding.btn0, binding.btn1, binding.btn2, binding.btn3, binding.btn4,
            binding.btn5, binding.btn6, binding.btn7, binding.btn8, binding.btn9
        )
        digitButtons.forEachIndexed { digit, btn ->
            btn.setOnClickListener { appendDigit(digit.toString()) }
        }

        binding.btnBackspace.setOnClickListener {
            if (entered.isNotEmpty()) {
                entered = entered.dropLast(1)
                refreshDisplay()
            }
        }

        binding.btnConfirm.setOnClickListener { verify() }
    }

    private fun appendDigit(d: String) {
        if (entered.length >= 6) return
        entered += d
        refreshDisplay()
        binding.tvError.visibility = View.INVISIBLE
    }

    private fun refreshDisplay() {
        binding.tvPinDots.text = "●".repeat(entered.length)
    }

    // ── Verification ─────────────────────────────────────────────────────────

    private fun verify() {
        if (PrefsManager.checkPin(this, entered)) {
            // Stop Kid Mode service (restores grayscale, clears state).
            startService(Intent(this, KidModeService::class.java).apply {
                action = KidModeService.ACTION_STOP
            })
            // Dismiss the boring screen (it lives in a separate task).
            BoringActivity.dismiss()
            finish()
        } else {
            entered = ""
            refreshDisplay()
            binding.tvError.visibility = View.VISIBLE
            vibrate()
        }
    }

    // ── Haptic feedback ───────────────────────────────────────────────────────

    private fun vibrate() {
        val effect = VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VibratorManager::class.java)
            vm.defaultVibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(effect)
        }
    }
}
