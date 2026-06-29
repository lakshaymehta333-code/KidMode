package com.phonenap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.sqrt

enum class FaceResult { PARENT, KID, UNKNOWN, NO_FACE }

class FaceManager(private val context: Context) {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build()
    )

    private val prefs = PrefsManager(context)

    // ── Live analysis (called from service) ───────────────────────────────────

    suspend fun analyze(bitmap: Bitmap): FaceResult {
        val faces = detector.process(InputImage.fromBitmap(bitmap, 0)).await()
        if (faces.isEmpty()) return FaceResult.NO_FACE

        // Use the largest face
        val face = faces.maxByOrNull { it.boundingBox.width() }
            ?: return FaceResult.NO_FACE

        // Reject extreme angles (not facing camera properly)
        if (abs(face.headEulerAngleY) > 30f || abs(face.headEulerAngleX) > 25f)
            return FaceResult.NO_FACE

        val vec = extractVector(face) ?: return FaceResult.NO_FACE

        val parentVec = prefs.getParentFaceVector()
        val kidVec    = prefs.getKidFaceVector()

        val parentSim = parentVec?.let { cosine(vec, it) } ?: -1f
        val kidSim    = kidVec?.let    { cosine(vec, it) } ?: -1f

        return when {
            parentSim >= THRESHOLD && parentSim >= kidSim -> FaceResult.PARENT
            kidSim    >= THRESHOLD                         -> FaceResult.KID
            parentVec == null && kidVec == null            -> FaceResult.UNKNOWN
            else                                           -> FaceResult.UNKNOWN
        }
    }

    // ── Enrollment ────────────────────────────────────────────────────────────

    suspend fun enrollParent(bitmap: Bitmap): Boolean {
        val vec = detectAndExtract(bitmap) ?: return false
        prefs.saveParentFaceVector(vec)
        return true
    }

    suspend fun enrollKid(bitmap: Bitmap): Boolean {
        val vec = detectAndExtract(bitmap) ?: return false
        prefs.saveKidFaceVector(vec)
        return true
    }

    private suspend fun detectAndExtract(bitmap: Bitmap): FloatArray? {
        val faces = detector.process(InputImage.fromBitmap(bitmap, 0)).await()
        val face = faces.maxByOrNull { it.boundingBox.width() } ?: return null
        return extractVector(face)
    }

    // ── Feature vector from face contour landmarks ────────────────────────────

    private fun extractVector(face: Face): FloatArray? {
        val box = face.boundingBox
        val cx  = box.exactCenterX()
        val cy  = box.exactCenterY()
        val w   = box.width().toFloat().coerceAtLeast(1f)

        val pts = mutableListOf<PointF>()
        listOf(
            FaceContour.FACE,
            FaceContour.LEFT_EYE,          FaceContour.RIGHT_EYE,
            FaceContour.LEFT_EYEBROW_TOP,  FaceContour.RIGHT_EYEBROW_TOP,
            FaceContour.LEFT_EYEBROW_BOTTOM, FaceContour.RIGHT_EYEBROW_BOTTOM,
            FaceContour.NOSE_BRIDGE,       FaceContour.NOSE_BOTTOM,
            FaceContour.UPPER_LIP_TOP,     FaceContour.UPPER_LIP_BOTTOM,
            FaceContour.LOWER_LIP_TOP,     FaceContour.LOWER_LIP_BOTTOM
        ).forEach { type -> face.getContour(type)?.points?.let { pts.addAll(it) } }

        if (pts.size < 20) return null

        // Normalise to face-centre / face-width, then L2-normalise
        val raw = FloatArray(pts.size * 2) { i ->
            if (i % 2 == 0) (pts[i / 2].x - cx) / w
            else             (pts[i / 2].y - cy) / w
        }
        return l2Normalise(raw)
    }

    // ── Math helpers ──────────────────────────────────────────────────────────

    private fun l2Normalise(v: FloatArray): FloatArray {
        val mag = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return if (mag > 0f) FloatArray(v.size) { v[it] / mag } else v
    }

    /** Cosine similarity (dot product of L2-normalised vectors → range -1..1). */
    private fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        return (0 until n).sumOf { (a[it] * b[it]).toDouble() }.toFloat()
    }

    companion object {
        // Tune: 0.80 = strict, 0.75 = lenient. Increase if false positives occur.
        private const val THRESHOLD = 0.78f
    }
}
