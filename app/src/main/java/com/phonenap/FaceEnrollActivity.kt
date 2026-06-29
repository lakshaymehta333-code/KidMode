package com.phonenap

import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.phonenap.databinding.ActivityFaceEnrollBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Face enrollment screen — mirrors Android face unlock UX.
 *
 * Flow:
 *  1. Camera preview shows through the circle hole in the black overlay.
 *  2. ImageAnalysis fires continuously; each frame is validated via
 *     [FaceManager.checkForEnrollment].
 *  3. Errors (too small / too large / off-centre / low light) are shown in red.
 *  4. When the face is valid the progress ring starts filling automatically.
 *  5. After [SAMPLE_COUNT] samples the ring turns green and enrollment is saved.
 *
 *  No button required — everything is automatic.
 */
class FaceEnrollActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFaceEnrollBinding
    private val faceManager by lazy { FaceManager(this) }

    private val collected     = mutableListOf<FloatArray>()
    private var lastCaptureMsec = 0L
    private val isProcessing  = AtomicBoolean(false)
    private var enrollmentDone = false

    // ── Guidance text (cycles every 5 samples) ────────────────────────────────
    private val poseGuidance = listOf(
        "Look straight at the camera",        // 0-4
        "Tilt your head slightly left",       // 5-9
        "Tilt your head slightly right",      // 10-14
        "Chin slightly up",                   // 15-19
        "Look straight again — almost done!"  // 20-24
    )

    // ── ImageAnalysis analyzer ────────────────────────────────────────────────

    private val analyzer = ImageAnalysis.Analyzer { imageProxy: ImageProxy ->
        // Drop frames if we are still processing the previous one
        if (isProcessing.getAndSet(true)) {
            imageProxy.close()
            return@Analyzer
        }
        val bitmap   = imageProxy.toBitmap()
        val rotation = imageProxy.imageInfo.rotationDegrees
        imageProxy.close()

        lifecycleScope.launch {
            try {
                processFrame(bitmap, rotation)
            } finally {
                isProcessing.set(false)
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFaceEnrollBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startPulseAnimation()
        startCamera()
    }

    // ── Animation ─────────────────────────────────────────────────────────────

    private fun startPulseAnimation() {
        ObjectAnimator.ofFloat(binding.ivFaceIcon, "alpha", 1f, 0.3f).apply {
            duration      = 1400
            repeatCount   = ObjectAnimator.INFINITE
            repeatMode    = ObjectAnimator.REVERSE
            interpolator  = AccelerateDecelerateInterpolator()
        }.start()
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(ContextCompat.getMainExecutor(this), analyzer) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── Per-frame processing ──────────────────────────────────────────────────

    private suspend fun processFrame(bitmap: Bitmap, rotation: Int) {
        if (enrollmentDone) return

        val check = faceManager.checkForEnrollment(bitmap, rotation)

        withContext(Dispatchers.Main) {
            when (check) {
                is EnrollmentCheck.NoFace    -> showError("No face detected")
                is EnrollmentCheck.TooSmall  -> showError("Move closer")
                is EnrollmentCheck.TooLarge  -> showError("Move further away")
                is EnrollmentCheck.OffCenter -> showError("Centre your face")
                is EnrollmentCheck.LowLight  -> showError("Find better lighting")
                is EnrollmentCheck.Valid     -> {
                    clearError()
                    val now = System.currentTimeMillis()
                    if (now - lastCaptureMsec >= CAPTURE_INTERVAL_MS &&
                            collected.size < SAMPLE_COUNT) {
                        collected.add(check.vector)
                        lastCaptureMsec = now

                        // Update progress ring
                        val progress = collected.size.toFloat() / SAMPLE_COUNT
                        binding.faceGuideOverlay.progress = progress

                        // Update guidance subtitle at each pose boundary
                        val poseIdx = (collected.size - 1) / 5
                        binding.tvSubtitle.text =
                            poseGuidance.getOrElse(poseIdx) { "Hold still" }

                        if (collected.size >= SAMPLE_COUNT) finishEnrolment()
                    }
                }
            }
        }
    }

    // ── Error display ─────────────────────────────────────────────────────────

    private fun showError(msg: String) {
        binding.tvError.text       = msg
        binding.tvError.visibility = View.VISIBLE
    }

    private fun clearError() {
        binding.tvError.visibility = View.INVISIBLE
    }

    // ── Finish ────────────────────────────────────────────────────────────────

    private fun finishEnrolment() {
        if (enrollmentDone) return
        enrollmentDone = true

        if (collected.size < MIN_SAMPLES) {
            Toast.makeText(
                this, "Only ${collected.size} samples — try better lighting",
                Toast.LENGTH_SHORT
            ).show()
            // Reset for retry
            collected.clear()
            lastCaptureMsec = 0L
            binding.faceGuideOverlay.progress = 0f
            binding.faceGuideOverlay.ringColor = Color.parseColor("#00BCD4")
            binding.tvSubtitle.text =
                "Keep your face well-lit and look directly at your phone"
            enrollmentDone = false
            return
        }

        // Success — flash the ring green then exit
        binding.faceGuideOverlay.ringColor = Color.parseColor("#4CAF50")
        binding.tvTitle.text    = "Face saved!"
        binding.tvSubtitle.text = "Setup complete"
        clearError()

        PrefsManager(this).saveKidFaceVectors(collected)

        binding.root.postDelayed({
            setResult(RESULT_OK)
            finish()
        }, 1500)
    }

    companion object {
        private const val SAMPLE_COUNT        = 25
        private const val MIN_SAMPLES         = 15
        private const val CAPTURE_INTERVAL_MS = 400L   // min ms between stored samples
    }
}
