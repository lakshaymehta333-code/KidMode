package com.phonenap

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import com.phonenap.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val accOk = isAccessibilityEnabled()
        val bioOk = isBiometricAvailable()

        binding.tvAccessibilityStatus.text =
            if (accOk) "✅ Accessibility Service: Enabled"
            else       "❌ Accessibility Service: Not enabled"

        binding.tvBiometricStatus.text =
            if (bioOk) "✅ Biometric: Ready"
            else       "⚠️  No fingerprint enrolled — go to Settings → Security → Fingerprint"

        binding.btnEnableAccessibility.isEnabled = !accOk
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val target = "$packageName/${PhoneNapAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(target, ignoreCase = true) }
    }

    private fun isBiometricAvailable(): Boolean {
        val bm = BiometricManager.from(this)
        return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
               BiometricManager.BIOMETRIC_SUCCESS
    }
}
