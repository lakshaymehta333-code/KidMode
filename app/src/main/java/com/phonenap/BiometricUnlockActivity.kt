package com.phonenap

import android.content.Intent
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Transparent activity shown over the accessibility overlay.
 * Presents a biometric prompt — success turns off PhoneNap,
 * cancel/error restores the overlay.
 */
class BiometricUnlockActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // No layout needed — just the system biometric dialog
        setContentView(R.layout.activity_biometric)
        showBiometricPrompt()
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                // Turn off PhoneNap
                sendBroadcast(
                    Intent(PhoneNapAccessibilityService.ACTION_DEACTIVATE)
                        .setPackage(packageName)
                )
                finish()
            }

            override fun onAuthenticationFailed() {
                // Wrong biometric — BiometricPrompt lets them retry automatically
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Cancelled or too many failures — restore overlay
                PhoneNapAccessibilityService.instance?.restoreOverlay()
                finish()
            }
        }

        val prompt = BiometricPrompt(this, executor, callback)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("PhoneNap — Parent Unlock")
            .setSubtitle("Verify your fingerprint to turn off PhoneNap")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        prompt.authenticate(promptInfo)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Block back button — parent must authenticate
        PhoneNapAccessibilityService.instance?.restoreOverlay()
        super.onBackPressed()
    }
}
