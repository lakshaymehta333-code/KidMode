package com.phonenap

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.phonenap.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PrefsManager(this)

        binding.btnSetup.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        binding.btnTestScan.setOnClickListener {
            val svc = PhoneNapService.instance
            if (svc != null) svc.triggerActiveScan()
            else android.widget.Toast.makeText(this, "Service not running — complete setup first", android.widget.Toast.LENGTH_SHORT).show()
        }

        binding.btnReset.setOnClickListener {
            prefs.clearAll()
            stopService(Intent(this, PhoneNapService::class.java))
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val setupDone = prefs.isSetupComplete()
        val overlayOk = Settings.canDrawOverlays(this)
        val kidReady  = prefs.hasKidFace()

        binding.tvSetupStatus.text = when {
            !overlayOk -> "⚠ Draw over apps permission required"
            !kidReady  -> "⚠ Kid face not enrolled"
            setupDone  -> "✓ PhoneNap is active and monitoring"
            else       -> "Setup incomplete"
        }

        binding.btnSetup.text = if (setupDone) "Re-run Setup" else "Start Setup"

        // Auto-start service once setup is complete
        if (setupDone && overlayOk) {
            ContextCompat.startForegroundService(
                this, Intent(this, PhoneNapService::class.java)
            )
        }
    }
}
