package com.phonenap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.tasks.await
import kotlin.math.atan2
import kotlin.math.sqrt

enum class FaceResult { KID, NOT_KID, NO_FACE }

/** Result of a single enrollment frame check. */
sealed class EnrollmentCheck {
    object NoFace    : EnrollmentCheck()  // no face detected or eye landmarks missing
    object TooSmall  : EnrollmentCheck()  // "Move closer"
    object TooLarge  : EnrollmentCheck()  // "Move further away"
    object OffCenter : EnrollmentCheck()  // "Centre your face"
    object LowLight  : EnrollmentCheck()  // "Find better lighting"
    data class Valid(val vector: FloatArray) : EnrollmentCheck()
}

/**
 * Face recognition pipeline:
 *
 * 1. Detect face bounding box with ML Kit (ACCURATE + LANDMARK_MODE_ALL).
 * 2. Reject faces smaller than MIN_FACE_PX (too far from camera).
 * 3. Align: rotate bitmap so left/right eyes are horizontal.
 * 4. Crop with 20 % padding — background is discarded entirely.
 * 5. Resize to FACE_SIZE × FACE_SIZE, apply histogram equalisation.
 * 6. Extract L2-normalised LBPH histogram (6×6 grid, 256 bins each).
 * 7. Cosine similarity against every enrolled vector, take the best score.
 * 8. In the service, 3 frames are averaged; fire overlay if avg >= THRESHOLD.
 *
 * Enrollment: 25 samples across 5 poses stored individually (not averaged).
 */
class FaceManager(private val context: Context) {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.08f)
            .build()
    )

    private val prefs = PrefsManager(context)

    // ── Detection ─────────────────────────────────────────────────────────────

    /**
     * Returns cosine similarity (0.0-1.0) against the best matching enrolled
     * vector, or null if no usable face was found in the frame.
     * Called per-frame in the service; the service averages multiple scores.
     */
    suspend fun analyzeScore(bitmap: Bitmap, rotation: Int = 0): Float? {
        val upright = rotateBitmap(bitmap, rotation)
        val faces   = detector.process(InputImage.fromBitmap(upright, 0)).await()
        if (faces.isEmpty()) {
            Log.d(TAG, "No face detected")
            return null
        }

        val face = faces.maxByOrNull { it.boundingBox.width() } ?: return null

        // Step 1: reject if face is too small (kid too far away)
        val box = face.boundingBox
        if (box.width() < MIN_FACE_PX || box.height() < MIN_FACE_PX) {
            Log.d(TAG, "Face too small: ${box.width()}x${box.height()} px — kid too far")
            return null
        }

        // Liveness: at least one eye must be open
        val leftOpen  = face.leftEyeOpenProbability  ?: 1f
        val rightOpen = face.rightEyeOpenProbability ?: 1f
        if (leftOpen < 0.2f && rightOpen < 0.2f) {
            Log.d(TAG, "Both eyes closed — skipping (liveness check)")
            return null
        }

        // Steps 2-6: align, crop, normalise, extract LBPH
        val aligned = alignAndCrop(upright, face) ?: return null
        val vec     = extractLBPH(aligned)

        val kidVecs = prefs.getKidFaceVectors()
        if (kidVecs.isEmpty()) {
            Log.w(TAG, "No enrolled vectors — enrol kid first")
            return null
        }

        // Step 7: compare against ALL enrolled vectors, keep the best score
        val bestSim = kidVecs.maxOf { cosineSimilarity(vec, it) }
        Log.d(TAG, "Best cosine sim=%.4f  threshold=%.2f  -> %s".format(
            bestSim, THRESHOLD, if (bestSim >= THRESHOLD) "KID" else "NOT KID"))
        return bestSim
    }

    /** Convenience wrapper returning the enum used by legacy call-sites. */
    suspend fun analyze(bitmap: Bitmap, rotation: Int = 0): FaceResult {
        val score = analyzeScore(bitmap, rotation) ?: return FaceResult.NO_FACE
        return if (score >= THRESHOLD) FaceResult.KID else FaceResult.NOT_KID
    }

    // ── Enrollment helper ──────────────────────────────────────────────────────

    /**
     * Extract a single L2-normalised LBPH vector from the largest face in
     * [bitmap]. Returns null if no face or no eye landmarks are detected
     * (eye landmarks required for alignment during enrollment).
     */
    suspend fun extractVector(bitmap: Bitmap, rotation: Int = 0): FloatArray? {
        val upright = rotateBitmap(bitmap, rotation)
        val faces   = detector.process(InputImage.fromBitmap(upright, 0)).await()
        val face    = faces.maxByOrNull { it.boundingBox.width() } ?: run {
            Log.d(TAG, "extractVector: no face detected")
            return null
        }

        // During enrollment require both eye landmarks for proper alignment
        if (face.getLandmark(FaceLandmark.LEFT_EYE) == null ||
            face.getLandmark(FaceLandmark.RIGHT_EYE) == null) {
            Log.d(TAG, "extractVector: eye landmarks missing — skipping sample")
            return null
        }

        val aligned = alignAndCrop(upright, face) ?: run {
            Log.d(TAG, "extractVector: alignAndCrop returned null")
            return null
        }
        return extractLBPH(aligned)
    }

    // ── Steps 2-3: Alignment + Crop ───────────────────────────────────────────

    /**
     * 1. Rotate the bitmap so left/right eye landmarks are exactly horizontal.
     * 2. Crop face region with 20% padding — no background leaks through.
     */
    private fun alignAndCrop(bitmap: Bitmap, face: Face): Bitmap? {
        val leftEye  = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position

        var workBmp = bitmap
        if (leftEye != null && rightEye != null) {
            val dx    = rightEye.x - leftEye.x
            val dy    = rightEye.y - leftEye.y
            val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()

            if (Math.abs(angle) > 1f) {
                val cx = (leftEye.x + rightEye.x) / 2f
                val cy = (leftEye.y + rightEye.y) / 2f
                val m  = Matrix().apply { postRotate(-angle, cx, cy) }
                workBmp = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            }
        }

        // Crop with 20% padding — bounding box is a good approximation even
        // after small rotations; padding provides the needed buffer
        val box    = face.boundingBox
        val padX   = (box.width()  * 0.20f).toInt()
        val padY   = (box.height() * 0.20f).toInt()
        val left   = (box.left   - padX).coerceAtLeast(0)
        val top    = (box.top    - padY).coerceAtLeast(0)
        val right  = (box.right  + padX).coerceAtMost(workBmp.width)
        val bottom = (box.bottom + padY).coerceAtMost(workBmp.height)
        if (right <= left || bottom <= top) return null

        return Bitmap.createBitmap(workBmp, left, top, right - left, bottom - top)
    }

    // ── Steps 4-6: Normalise + LBPH ──────────────────────────────────────────

    /**
     * Resize to FACE_SIZE^2, histogram-equalise, compute LBPH over GRID×GRID
     * cells (256 bins each), then L2-normalise so cosine sim == dot product.
     */
    private fun extractLBPH(faceBitmap: Bitmap): FloatArray {
        val sized = Bitmap.createScaledBitmap(faceBitmap, FACE_SIZE, FACE_SIZE, true)
        val gray  = toGrayscaleEqualised(sized)

        val cellW = FACE_SIZE / GRID
        val cellH = FACE_SIZE / GRID
        val hist  = FloatArray(GRID * GRID * BINS)

        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                val cellHist = FloatArray(BINS)
                var count    = 0

                val yStart = gy * cellH + 1
                val yEnd   = ((gy + 1) * cellH - 1).coerceAtMost(FACE_SIZE - 1)
                val xStart = gx * cellW + 1
                val xEnd   = ((gx + 1) * cellW - 1).coerceAtMost(FACE_SIZE - 1)

                for (y in yStart until yEnd) {
                    for (x in xStart until xEnd) {
                        val c    = gray[y][x]
                        var code = 0
                        if (gray[y-1][x-1] >= c) code = code or 128
                        if (gray[y-1][x  ] >= c) code = code or 64
                        if (gray[y-1][x+1] >= c) code = code or 32
                        if (gray[y  ][x+1] >= c) code = code or 16
                        if (gray[y+1][x+1] >= c) code = code or 8
                        if (gray[y+1][x  ] >= c) code = code or 4
                        if (gray[y+1][x-1] >= c) code = code or 2
                        if (gray[y  ][x-1] >= c) code = code or 1
                        cellHist[code]++
                        count++
                    }
                }

                val base = (gy * GRID + gx) * BINS
                if (count > 0) for (i in 0 until BINS) hist[base + i] = cellHist[i] / count
            }
        }

        // L2-normalise: makes cosine similarity == dot product
        var normSq = 0.0
        for (v in hist) normSq += v * v
        val norm = sqrt(normSq).toFloat()
        if (norm > 1e-10f) for (i in hist.indices) hist[i] /= norm

        return hist
    }

    /** Grayscale + histogram equalisation — compensates for lighting changes. */
    private fun toGrayscaleEqualised(bmp: Bitmap): Array<IntArray> {
        val h = bmp.height
        val w = bmp.width

        val raw = Array(h) { y ->
            IntArray(w) { x ->
                val p = bmp.getPixel(x, y)
                ((p shr 16 and 0xFF) * 299 +
                 (p shr  8 and 0xFF) * 587 +
                 (p        and 0xFF) * 114) / 1000
            }
        }

        val freq   = IntArray(256)
        for (row in raw) for (v in row) freq[v]++
        val cdf    = IntArray(256).also { a ->
            a[0] = freq[0]; for (i in 1..255) a[i] = a[i-1] + freq[i]
        }
        val cdfMin = cdf.first { it > 0 }
        val total  = h * w

        return Array(h) { y ->
            IntArray(w) { x ->
                if (total == cdfMin) raw[y][x]
                else ((cdf[raw[y][x]] - cdfMin).toFloat() / (total - cdfMin) * 255)
                    .toInt().coerceIn(0, 255)
            }
        }
    }

    // ── Cosine similarity ─────────────────────────────────────────────────────

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot   = 0.0
        var normA = 0.0
        var normB = 0.0
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            dot   += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom < 1e-10) 0f else (dot / denom).toFloat()
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    // ── Enrollment frame check ─────────────────────────────────────────────────

    /**
     * Used by FaceEnrollActivity's ImageAnalysis loop.
     * Validates the face against the on-screen circle geometry, checks lighting,
     * requires eye landmarks, and — if all checks pass — returns a ready-to-store
     * LBPH vector wrapped in [EnrollmentCheck.Valid].
     *
     * Circle is defined as a fixed fraction of the image short-edge so that it
     * matches the [FaceGuideOverlay] circle (circleRadius = minOf(w,h) * 0.38).
     */
    suspend fun checkForEnrollment(bitmap: Bitmap, rotation: Int = 0): EnrollmentCheck {
        val upright = rotateBitmap(bitmap, rotation)
        val faces   = detector.process(InputImage.fromBitmap(upright, 0)).await()
        val face    = faces.maxByOrNull { it.boundingBox.width() }
            ?: return EnrollmentCheck.NoFace

        // Circle in image-space (mirrors FaceGuideOverlay proportions)
        val circleR  = minOf(upright.width, upright.height) * 0.38f
        val circleCx = upright.width  / 2f
        val circleCy = upright.height / 2f

        val box      = face.boundingBox
        val faceSize = maxOf(box.width(), box.height()).toFloat()
        val dx       = box.exactCenterX() - circleCx
        val dy       = box.exactCenterY() - circleCy
        val dist     = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        val circleD  = circleR * 2f

        // Size out-of-range
        if (faceSize < circleD * 0.70f) return EnrollmentCheck.TooSmall
        if (faceSize > circleD * 0.95f) return EnrollmentCheck.TooLarge

        // Off-centre
        if (dist > circleR * 0.15f) return EnrollmentCheck.OffCenter

        // Ideal fill range 75–90 %
        if (faceSize < circleD * 0.75f) return EnrollmentCheck.TooSmall
        if (faceSize > circleD * 0.90f) return EnrollmentCheck.TooLarge

        // Lighting check on raw face crop
        val rawCrop = cropFace(upright, face)
        if (rawCrop != null && !hasAdequateLighting(rawCrop)) return EnrollmentCheck.LowLight

        // Eye landmarks required for alignment
        if (face.getLandmark(FaceLandmark.LEFT_EYE)  == null ||
            face.getLandmark(FaceLandmark.RIGHT_EYE) == null)
            return EnrollmentCheck.NoFace

        val aligned = alignAndCrop(upright, face) ?: return EnrollmentCheck.NoFace
        return EnrollmentCheck.Valid(extractLBPH(aligned))
    }

    /** Returns true if the average pixel brightness of [bmp] is >= 40 (0–255). */
    private fun hasAdequateLighting(bmp: Bitmap): Boolean {
        var total = 0L
        var count = 0
        // Sample every ~5th pixel for speed
        val step = maxOf(1, minOf(bmp.width, bmp.height) / 20)
        for (y in 0 until bmp.height step step) {
            for (x in 0 until bmp.width step step) {
                val p = bmp.getPixel(x, y)
                total += ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
                count++
            }
        }
        return count == 0 || (total / count) >= 40
    }

    private fun rotateBitmap(bitmap: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return bitmap
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    companion object {
        private const val TAG         = "FaceManager"
        private const val FACE_SIZE   = 112    // standard FaceNet input size
        private const val GRID        = 6
        private const val BINS        = 256
        private const val MIN_FACE_PX = 100    // reject bounding box < this

        /**
         * Cosine similarity threshold.
         *   Same person (well-lit, aligned)   -> 0.78-0.95
         *   Different person                  -> 0.30-0.60
         *   0.75 gives a safe gap between the two clusters.
         * Raise if strangers still trigger; lower if kid is missed.
         */
        const val THRESHOLD = 0.75f
    }
}
