package com.phonenap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.pow

enum class FaceResult { KID, NOT_KID, NO_FACE }

/**
 * Face recognition using LBPH (Local Binary Pattern Histogram).
 *
 * During enrollment, 7–10 frames are captured from different angles; their
 * LBPH histograms are averaged and saved.  At detection time, 3 frames are
 * compared against the average; the best similarity is used.
 *
 * Threshold is calibrated:
 *   same person (averaged enrolment vs live)  →  ~0.15–0.35
 *   different person                          →  ~0.02–0.11
 *   threshold = 0.13 → safe gap in the middle
 *
 * Increase THRESHOLD if too many false positives (strangers trigger overlay).
 * Decrease THRESHOLD if the kid is not being recognised.
 */
class FaceManager(private val context: Context) {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // eye-open probs
            .setMinFaceSize(0.08f)
            .build()
    )

    private val prefs = PrefsManager(context)

    // ── Detection ─────────────────────────────────────────────────────────────

    /**
     * Analyse a single captured bitmap.
     * Called by the service; pass the camera's rotation degrees.
     */
    suspend fun analyze(bitmap: Bitmap, rotation: Int = 0): FaceResult {
        val upright = rotateBitmap(bitmap, rotation)
        val faces   = detector.process(InputImage.fromBitmap(upright, 0)).await()
        if (faces.isEmpty()) return FaceResult.NO_FACE

        val face = faces.maxByOrNull { it.boundingBox.width() } ?: return FaceResult.NO_FACE

        // Reject extreme head angles
        if (abs(face.headEulerAngleY) > 50f || abs(face.headEulerAngleX) > 40f)
            return FaceResult.NO_FACE

        // Simple liveness: require at least one eye to be open
        val leftOpen  = face.leftEyeOpenProbability  ?: 1f
        val rightOpen = face.rightEyeOpenProbability ?: 1f
        if (leftOpen < 0.2f && rightOpen < 0.2f) {
            Log.d(TAG, "Both eyes closed — likely sleeping or photo; skipping")
            return FaceResult.NO_FACE
        }

        val crop   = cropFace(upright, face) ?: return FaceResult.NO_FACE
        val vec    = extractLBPH(crop)
        val kidVec = prefs.getKidFaceVector() ?: run {
            Log.w(TAG, "No enrolled kid face found — enrol first!")
            return FaceResult.NO_FACE
        }

        val sim = chiSquareSimilarity(vec, kidVec)
        Log.d(TAG, "LBPH similarity=%.4f  threshold=%.4f  → %s".format(
            sim, THRESHOLD, if (sim >= THRESHOLD) "KID ✓" else "NOT KID"))

        return if (sim >= THRESHOLD) FaceResult.KID else FaceResult.NOT_KID
    }

    // ── Enrolment helper ──────────────────────────────────────────────────────

    /**
     * Extract an LBPH vector from a face in the bitmap.
     * Returns null if no face is detected.
     * Called repeatedly by FaceEnrollActivity to build up multiple samples.
     */
    suspend fun extractVector(bitmap: Bitmap, rotation: Int = 0): FloatArray? {
        val upright = rotateBitmap(bitmap, rotation)
        val faces   = detector.process(InputImage.fromBitmap(upright, 0)).await()
        val face    = faces.maxByOrNull { it.boundingBox.width() } ?: return null
        val crop    = cropFace(upright, face) ?: return null
        return extractLBPH(crop)
    }

    // ── LBPH ─────────────────────────────────────────────────────────────────

    /**
     * Divide the face into GRID×GRID cells.
     * For each pixel, compute an 8-bit LBP code from its 8 neighbours.
     * Accumulate a normalised 256-bin histogram per cell.
     * Concatenate → GRID*GRID*256 floats.
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
                        val c = gray[y][x]
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
                if (count > 0) {
                    for (i in 0 until BINS) hist[base + i] = cellHist[i] / count
                }
            }
        }
        return hist
    }

    /** Grayscale conversion with histogram equalisation (reduces lighting sensitivity). */
    private fun toGrayscaleEqualised(bmp: Bitmap): Array<IntArray> {
        val h = bmp.height; val w = bmp.width

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
        val cdf    = IntArray(256).also { it[0] = freq[0]; for (i in 1..255) it[i] = it[i-1] + freq[i] }
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

    /** Chi-square similarity: 1.0 = identical, 0.0 = nothing in common. */
    private fun chiSquareSimilarity(a: FloatArray, b: FloatArray): Float {
        var chi = 0.0
        val n   = minOf(a.size, b.size)
        for (i in 0 until n) {
            val sum = a[i] + b[i]
            if (sum > 0f) chi += (a[i] - b[i]).toDouble().pow(2) / sum
        }
        return (1.0 / (1.0 + chi)).toFloat()
    }

    private fun rotateBitmap(bitmap: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return bitmap
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    private fun cropFace(bitmap: Bitmap, face: Face): Bitmap? {
        val box    = face.boundingBox
        val padX   = (box.width()  * 0.25f).toInt()
        val padY   = (box.height() * 0.30f).toInt()
        val left   = (box.left   - padX).coerceAtLeast(0)
        val top    = (box.top    - padY).coerceAtLeast(0)
        val right  = (box.right  + padX).coerceAtMost(bitmap.width)
        val bottom = (box.bottom + padY).coerceAtMost(bitmap.height)
        if (right <= left || bottom <= top) return null
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    companion object {
        private const val TAG       = "FaceManager"
        private const val FACE_SIZE = 96
        private const val GRID      = 6
        private const val BINS      = 256

        /**
         * Tune this if needed:
         * • Too many false positives (wrong people trigger) → raise toward 0.18
         * • Kid not being recognised → lower toward 0.10
         */
        const val THRESHOLD = 0.13f

        /** Average multiple LBPH histograms → robust enrolled template. */
        fun averageVectors(vecs: List<FloatArray>): FloatArray {
            require(vecs.isNotEmpty())
            val size = vecs[0].size
            val avg  = FloatArray(size)
            for (vec in vecs) for (i in vec.indices) avg[i] += vec[i]
            for (i in avg.indices) avg[i] /= vecs.size
            return avg
        }
    }
}
