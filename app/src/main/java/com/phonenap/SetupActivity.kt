package com.phonenap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import com.phonenap.databinding.ActivitySetupBinding

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefs: PrefsManager

    // Steps: 0=camera, 1=overlay, 2=notify, 3=kid face, 4=done
    private var step = 0

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) nextStep() else toast("Camera permission is required") }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> nextStep() }

    private val faceEnrollLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) nextStep()
        else toast("No face detected — try again in better lighting")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PrefsManager(this)
        binding.btnNext.setOnClickListener { doCurrentStep() }
    }

    override fun onResume() {
        super.onResume()
        if (step == 1 && Settings.canDrawOverlays(this)) nextStep()
        refreshUi()
    }

    private fun refreshUi() {
        val (title, desc, btn) = when (step) {
            0 -> Triple(
                "Camera Permission",
                "PhoneNap uses the front camera to detect who picked up the phone.\n\nNo photos are saved or uploaded.",
                "Grant Camera"
            )
            1 -> Triple(
                "Draw Over Apps",
                "PhoneNap needs to display its overlay on top of other apps.\n\nTap below to open Settings → 'Display over other apps'.",
                "Open Settings"
            )
            2 -> Triple(
                "Notifications (optional)",
                "Allows PhoneNap to show its background monitoring indicator.",
                "Allow Notifications"
            )
            3 -> Triple(
                "Scan Kid's Face",
                "Hand the phone to your child and tap Scan.\n\nPhoneNap will recognise this face and show the lock screen whenever it's detected.",
                "Scan Kid's Face"
            )
            else -> Triple(
                "All Done!",
                "PhoneNap is now active.\n\nIt monitors faces automatically. Your fingerprint unlocks the overlay when needed.",
                "Finish"
            )
        }
        binding.tvStepTitle.text = title
        binding.tvStepDesc.text  = desc
        binding.btnNext.text     = btn
        binding.tvProgress.text  = "Step ${(step + 1).coerceAtMost(4)} of 4"
    }

    private fun doCurrentStep() {
        when (step) {
            0    -> requestCameraPermission()
            1    -> requestOverlayPermission()
            2    -> requestNotificationPermission()
            3    -> launchFaceEnroll()
            else -> finishSetup()
        }
    }

    private fun nextStep() {
        step++
        if (step == 2 && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) step++
        refreshUi()
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) nextStep()
        else cameraPermLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) { nextStep(); return }
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                             Uri.parse("package:$packageName")))
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else nextStep()
    }

    private fun launchFaceEnroll() {
        faceEnrollLauncher.launch(
            Intent(this, FaceEnrollActivity::class.java)
                .putExtra(FaceEnrollActivity.EXTRA_IS_PARENT, false)
        )
    }

    private fun finishSetup() {
        prefs.markSetupComplete()
        startForegroundService(this, Intent(this, PhoneNapService::class.java))
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
