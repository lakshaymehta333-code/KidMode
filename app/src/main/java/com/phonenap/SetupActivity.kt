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

    private var step = 0  // 0=camera, 1=overlay, 2=notify, 3=parent face, 4=kid face, 5=done

    // ── Permission launchers ──────────────────────────────────────────────────

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) nextStep() else toast("Camera permission is required")
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> nextStep() }  // notification is optional — proceed either way

    private val faceEnrollLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) nextStep()
        else toast("No face detected — try again in better lighting")
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PrefsManager(this)

        binding.btnNext.setOnClickListener { doCurrentStep() }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    // ── Step machine ──────────────────────────────────────────────────────────

    private fun refreshUi() {
        val (title, desc, btn) = when (step) {
            0 -> Triple("Camera Permission",
                        "PhoneNap needs the camera to identify who is using the phone.\n\nYour photos are never saved or uploaded.",
                        "Grant Camera")
            1 -> Triple("Draw Over Apps",
                        "PhoneNap needs to show its overlay on top of other apps.\n\nTap below to open Settings and enable 'Display over other apps'.",
                        "Open Settings")
            2 -> Triple("Notifications (optional)",
                        "Allow notifications so PhoneNap can show its background service indicator.",
                        "Allow Notifications")
            3 -> Triple("Scan Parent Face",
                        "Look at the front camera and tap 'Scan'. This face will UNLOCK the phone.",
                        "Scan Parent Face")
            4 -> Triple("Scan Kid Face",
                        "Now hand the phone to your child and tap 'Scan'. This face will LOCK the phone.",
                        "Scan Kid Face")
            5 -> Triple("All Done!",
                        "PhoneNap is now set up. The app will monitor face automatically.\n\nClose this screen.",
                        "Finish")
            else -> Triple("", "", "")
        }
        binding.tvStepTitle.text = title
        binding.tvStepDesc.text  = desc
        binding.btnNext.text     = btn
        binding.tvProgress.text  = "Step ${(step + 1).coerceAtMost(5)} of 5"
    }

    private fun doCurrentStep() {
        when (step) {
            0 -> requestCameraPermission()
            1 -> requestOverlayPermission()
            2 -> requestNotificationPermission()
            3 -> launchFaceEnroll(isParent = true)
            4 -> launchFaceEnroll(isParent = false)
            5 -> finishSetup()
        }
    }

    private fun nextStep() {
        step++
        // Skip notification step on older Android
        if (step == 2 && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) step++
        refreshUi()
    }

    // ── Per-step actions ──────────────────────────────────────────────────────

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            nextStep()
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) { nextStep(); return }
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                   Uri.parse("package:$packageName"))
        )
        // onResume will check again; advance if granted
    }

    override fun onResume() {
        super.onResume()
        // If returning from overlay settings and it's now granted, advance
        if (step == 1 && Settings.canDrawOverlays(this)) nextStep()
        refreshUi()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            nextStep()
        }
    }

    private fun launchFaceEnroll(isParent: Boolean) {
        faceEnrollLauncher.launch(
            Intent(this, FaceEnrollActivity::class.java)
                .putExtra(FaceEnrollActivity.EXTRA_IS_PARENT, isParent)
        )
    }

    private fun finishSetup() {
        prefs.markSetupComplete()
        startForegroundService(this, Intent(this, PhoneNapService::class.java))
        finish()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
