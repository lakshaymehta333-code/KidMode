package com.phonenap

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.phonenap.databinding.ActivityFaceEnrollBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 25-sample multi-pose face enrollment.
 *
 * Poses (5 samples each):
 *   1. Straight   - baseline
 *   2. Left tilt  - lateral head movement
 *   3. Right tilt
 *   4. Chin up
 *   5. Chin down
 *
 * Each sample requires ML Kit to detect both eye landmarks; frames without
 * eyes are silently retried (up to MAX_ATTEMPTS total attempts).
 * All vectors are stored individually — the detector compares against each
 * and takes the best cosine similarity score.
 */
class FaceEnrollActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFaceEnrollBinding
    private var imageCapture: ImageCapture? = null

    private val collected    = mutableListOf<FloatArray>()
    private var capturing    = false
    private var attemptCount = 0   // total capture attempts (including retries)

    // ── Guidance ──────────────────────────────────────────────────────────────

    // 25 messages — one per successful sample
    private val guidance = listOf(
        // Pose 1: straight (samples 1-5)
        "Look straight at the camera",
        "Hold still — straight ahead",
        "Keep looking forward",
        "Good — stay straight",
        "One more — eyes on the camera",
        // Pose 2: turn left (samples 6-10)
        "Slowly turn your head to the LEFT",
        "A little more to the left",
        "Good — hold that left position",
        "Stay turned left",
        "One more — head turned left",
        // Pose 3: turn right (samples 11-15)
        "Now slowly turn your head to the RIGHT",
        "A little more to the right",
        "Good — hold that right position",
        "Stay turned right",
        "One more — head turned right",
        // Pose 4: chin up (samples 16-20)
        "Tilt your head slightly UP",
        "A bit more — chin up",
        "Good — hold that tilt",
        "Stay tilted up",
        "One more — chin up",
        // Pose 5: chin down (samples 21-25)
        "Now tilt your head slightly DOWN",
        "A bit more — chin down",
        "Good — hold that tilt",
        "Stay tilted down",
        "Hold still — almost done!"
    )

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFaceEnrollBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.progressEnroll.max      = SAMPLE_COUNT
        binding.progressEnroll.progress = 0
        binding.tvEnrollTitle.text       = "Kid Face Scan"
        binding.tvEnrollHint.text        = "Position the kid's face in the frame"

        startCamera()

        binding.btnCapture.setOnClickListener {
            if (!capturing) startCapture()
        }
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview  = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageCapture
                )
            } catch (e: Exception) {
                toast("Camera error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── Capture loop ──────────────────────────────────────────────────────────

    private fun startCapture() {
        capturing    = true
        attemptCount = 0
        collected.clear()
        binding.btnCapture.isEnabled = false
        captureNext()
    }

    private fun captureNext() {
        // Hard limit on total attempts to avoid infinite loops
        if (attemptCount >= MAX_ATTEMPTS) {
            finishEnrolment()
            return
        }

        val sampleIdx = collected.size   // 0-based index of next successful sample
        if (sampleIdx >= SAMPLE_COUNT) {
            finishEnrolment()
            return
        }

        // Show guidance for the upcoming sample
        binding.tvEnrollHint.text  = guidance.getOrElse(sampleIdx) { "Hold still" }
        binding.tvEnrollTitle.text = "Scanning... ${sampleIdx}/$SAMPLE_COUNT"

        // Longer pause at each pose boundary so kid has time to move
        val delayMs = if (sampleIdx > 0 && sampleIdx % 5 == 0) DELAY_POSE_CHANGE_MS
                      else DELAY_BETWEEN_MS

        binding.root.postDelayed({
            attemptCount++
            imageCapture?.takePicture(
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bmp      = image.toBitmap()
                        val rotation = image.imageInfo.rotationDegrees
                        image.close()

                        lifecycleScope.launch {
                            val fm  = FaceManager(this@FaceEnrollActivity)
                            val vec = fm.extractVector(bmp, rotation)

                            if (vec != null) {
                                // Good sample: face detected with eye landmarks
                                collected.add(vec)
                                binding.progressEnroll.progress = collected.size
                                binding.tvEnrollTitle.text =
                                    "Scanning... ${collected.size}/$SAMPLE_COUNT"
                            }
                            // If null (no face or no eyes): silently retry same pose

                            delay(50L)
                            captureNext()
                        }
                    }
                    override fun onError(e: ImageCaptureException) {
                        lifecycleScope.launch {
                            delay(DELAY_BETWEEN_MS)
                            captureNext()
                        }
                    }
                }
            )
        }, delayMs)
    }

    // ── Finalise ──────────────────────────────────────────────────────────────

    private fun finishEnrolment() {
        if (collected.size < MIN_SAMPLES) {
            toast("Only ${collected.size} samples — try better lighting or move closer")
            binding.btnCapture.isEnabled = true
            capturing    = false
            attemptCount = 0
            collected.clear()
            binding.progressEnroll.progress = 0
            binding.tvEnrollTitle.text = "Kid Face Scan"
            binding.tvEnrollHint.text  = "Position the kid's face in the frame"
            return
        }

        // Save ALL vectors — detector compares against each individually
        PrefsManager(this).saveKidFaceVectors(collected)

        toast("Face enrolled! (${collected.size} samples across 5 poses)")
        setResult(RESULT_OK)
        finish()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val SAMPLE_COUNT         = 25
        private const val MIN_SAMPLES          = 15    // minimum to accept enrollment
        private const val MAX_ATTEMPTS         = 60    // hard cap to prevent infinite loops
        private const val DELAY_BETWEEN_MS     = 400L  // gap between consecutive frames
        private const val DELAY_POSE_CHANGE_MS = 2000L // pause when switching pose
    }
}
