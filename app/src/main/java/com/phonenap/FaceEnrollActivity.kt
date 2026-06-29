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
import kotlinx.coroutines.launch

class FaceEnrollActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFaceEnrollBinding
    private var imageCapture: ImageCapture? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFaceEnrollBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvEnrollTitle.text = "Kid Face Scan"
        binding.tvEnrollHint.text  = "Kid should look straight at the camera.\nThis face will trigger the lock."

        startCamera()
        binding.btnCapture.setOnClickListener { captureAndEnroll() }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
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

    private fun captureAndEnroll() {
        binding.btnCapture.isEnabled = false
        binding.tvEnrollHint.text = "Hold still…"

        imageCapture?.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap   = image.toBitmap()
                    val rotation = image.imageInfo.rotationDegrees
                    image.close()
                    lifecycleScope.launch {
                        val fm = FaceManager(this@FaceEnrollActivity)
                        val ok = fm.enrollKid(bitmap, rotation)
                        if (ok) {
                            toast("Face saved!")
                            setResult(RESULT_OK)
                            finish()
                        } else {
                            toast("No face detected — try again")
                            binding.btnCapture.isEnabled = true
                            binding.tvEnrollHint.text = "Make sure your face is clearly visible"
                        }
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    toast("Capture failed: ${e.message}")
                    binding.btnCapture.isEnabled = true
                }
            }
        )
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object
}
