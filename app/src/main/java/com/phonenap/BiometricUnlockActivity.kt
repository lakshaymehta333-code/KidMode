package com.phonenap

import android.content.Intent
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

class BiometricUnlockActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        showBiometricPrompt()
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)

        val prompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    // Deactivate overlay
                    val svc = PhoneNapService.instance
                    if (svc != null) {
                        svc.prefs.setActive(false)
                        svc.overlayManager.hide()
                    }
                    finish()
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    // Leave overlay as-is
                    finish()
                }
                override fun onAuthenticationFailed() { /* keep prompt open */ }
            }
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("PhoneNap — Parent Unlock")
            .setSubtitle("Use your fingerprint to unlock")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        prompt.authenticate(info)
    }
}
