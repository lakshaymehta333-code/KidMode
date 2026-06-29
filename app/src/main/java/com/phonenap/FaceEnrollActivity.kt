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

class FaceEnrollActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFaceEnrollBinding
    private var imageCapture: ImageCapture? = null

    private val collected  = mutableListOf<FloatArray>()
    private var capturing  = false

    // Guidance messages shown one per capture
    private val guidance = listOf(
        "Look straight at the camera",
        "Slowly turn your head left",
        "Slowly turn your head right",
        "Tilt your head slightly up",
        "Tilt your head slightly down",
        "Look straight again",
        "Hold still — almost done"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFaceEnrollBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvEnrollTitle.text = "Kid Face Scan"
        binding.tvEnrollHint.text  = "Position the kid's face in the frame"
        binding.progressEnroll.max      = SAMPLE_COUNT
        binding.progressEnroll.progress = 0

        startCamera()

        binding.btnCapture.setOnClickListener {
            if (!capturing) startCapture()
        }
    }

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

    private fun startCapture() {
        capturing = true
        collected.clear()
        binding.btnCapture.isEnabled = false
        captureNext()
    }

    private fun captureNext() {
        val idx = collected.size
        if (idx >= SAMPLE_COUNT) { finishEnrolment(); return }

        binding.tvEnrollHint.text = guidance.getOrElse(idx) { "Hold still" }

        // Short delay so the kid has time to move into the guided position
        binding.root.postDelayed({
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
                                collected.add(vec)
                                binding.progressEnroll.progress = collected.size
                                binding.tvEnrollTitle.text =
                                    "Scanning… ${collected.size}/$SAMPLE_COUNT"
                            }
                            delay(DELAY_BETWEEN_MS)
                            captureNext()
                        }
                    }
                    override fun onError(e: ImageCaptureException) {
                        lifecycleScope.launch {
                            delay(DELAY_BETWEEN_MS)
                            captureNext() // retry same step silently
                        }
                    }
                }
            )
        }, DELAY_BEFORE_CAPTURE_MS)
    }

    private fun finishEnrolment() {
        if (collected.size < MIN_SAMPLES) {
            toast("Only ${collected.size} samples captured — try better lighting")
            binding.btnCapture.isEnabled = true
            capturing = false
            collected.clear()
            binding.progressEnroll.progress = 0
            binding.tvEnrollTitle.text = "Kid Face Scan"
            binding.tvEnrollHint.text  = "Position the kid's face in the frame"
            return
        }

        val avg = FaceManager.averageVectors(collected)
        PrefsManager(this).saveKidFaceVector(avg)

        toast("Face enrolled! (${collected.size} samples captured)")
        setResult(RESULT_OK)
        finish()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val SAMPLE_COUNT          = 7
        private const val MIN_SAMPLES           = 3
        private const val DELAY_BEFORE_CAPTURE_MS = 1200L  // let the kid move into position
        private const val DELAY_BETWEEN_MS        = 300L
    }
}
