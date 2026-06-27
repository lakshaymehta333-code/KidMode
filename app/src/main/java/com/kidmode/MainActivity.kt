package com.kidmode

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.kidmode.databinding.ActivityMainBinding

/**
 * Simple setup screen — just tells the parent how to use Kid Mode.
 * No PIN required anymore; the QS tile and long-press exit are PIN-free.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvReadyBanner.text = "Kid Mode is ready to use!"
        binding.tvPinStatus.text =
            "1. Pull down the notification shade and add the Kid Mode tile.\n\n" +
            "2. Tap the tile to activate — screen goes dark in 20 seconds.\n\n" +
            "3. To exit: tap the tile again, or long-press the top-right corner of the screen."
        binding.tvAdminStatus.text = ""
        binding.tvOverlayStatus.text = ""
        binding.tvGrayscaleHint.text = ""

        // Hide all setup buttons — nothing to configure
        binding.btnSavePin.visibility     = android.view.View.GONE
        binding.etNewPin.visibility       = android.view.View.GONE
        binding.etConfirmPin.visibility   = android.view.View.GONE
        binding.btnEnableAdmin.visibility = android.view.View.GONE
        binding.btnOverlayPermission.visibility = android.view.View.GONE
        binding.tvError.visibility        = android.view.View.GONE

        binding.btnTestKidMode.setOnClickListener {
            startForegroundService(
                Intent(this, KidModeService::class.java).apply {
                    action = KidModeService.ACTION_START
                }
            )
        }
    }
}
