package com.phonenap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
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
 * LBPH captures the micro-texture of a face (skin, pores, wrinkles, shadow patterns)
 * in a grid of local histograms. It's more identity-discriminative than geometric
 * contour positions, and needs no external model file.
 *
 * Same person across captures: chi-square similarity ≈ 0.50–0.80
 * Different people:            chi-square similarity ≈ 0.05–0.40
 */
class FaceManager(private val context: Context) {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.10f)
            .build()
    )

    private val prefs = PrefsManager(context)

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun analyze(bitmap: Bitmap, rotation: Int = 0): FaceResult {
        val upright = rotateBitmap(bitmap, rotation)
        val faces   = detector.process(InputImage.fromBitmap(upright, 0)).await()
        if (faces.isEmpty()) return FaceResult.NO_FACE

        val face = faces.maxByOrNull { it.boundingBox.width() } ?: return FaceResult.NO_FACE
        if (abs(face.headEulerAngleY) > 45f || abs(face.headEulerAngleX) > 35f)
            return FaceResult.NO_FACE

        val crop   = cropFace(upright, face) ?: return FaceResult.NO_FACE
        val vec    = extractLBPH(crop)
        val kidVec = prefs.getKidFaceVector() ?: return FaceResult.NO_FACE

        return if (chiSquareSimilarity(vec, kidVec) >= THRESHOLD) FaceResult.KID else FaceResult.NOT_KID
    }

    suspend fun enrollKid(bitmap: Bitmap, rotation: Int = 0): Boolean {
        val upright = rotateBitmap(bitmap, rotation)
        val faces   = detector.process(InputImage.fromBitmap(upright, 0)).await()
        val face    = faces.maxByOrNull { it.boundingBox.width() } ?: return false
        val crop    = cropFace(upright, face) ?: return false
        prefs.saveKidFaceVector(extractLBPH(crop))
        return true
    }

    // ── LBPH ─────────────────────────────────────────────────────────────────

    /**
     * Divides the face into GRID×GRID cells. For each cell, computes an
     * LBP code per pixel (compare against 8 neighbours) and builds a
     * normalised 256-bin histogram. All cell histograms are concatenated.
     * Result: GRID * GRID * 256 floats.
     */
    private fun extractLBPH(faceBitmap: Bitmap): FloatArray {
        // Apply CLAHE-like equalisation to reduce lighting sensitivity
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
                        if (gray[y - 1][x - 1] >= c) code = code or 128
                        if (gray[y - 1][x    ] >= c) code = code or 64
                        if (gray[y - 1][x + 1] >= c) code = code or 32
                        if (gray[y    ][x + 1] >= c) code = code or 16
                        if (gray[y + 1][x + 1] >= c) code = code or 8
                        if (gray[y + 1][x    ] >= c) code = code or 4
                        if (gray[y + 1][x - 1] >= c) code = code or 2
                        if (gray[y    ][x - 1] >= c) code = code or 1
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

    /**
     * Convert to grayscale with histogram equalisation so that
     * dark / bright captures produce comparable feature vectors.
     */
    private fun toGrayscaleEqualised(bmp: Bitmap): Array<IntArray> {
        val h = bmp.height
        val w = bmp.width

        // Step 1: raw grayscale
        val raw = Array(h) { y ->
            IntArray(w) { x ->
                val p = bmp.getPixel(x, y)
                ((p shr 16 and 0xFF) * 299 +
                 (p shr  8 and 0xFF) * 587 +
                 (p        and 0xFF) * 114) / 1000
            }
        }

        // Step 2: histogram equalisation
        val freq = IntArray(256)
        for (row in raw) for (v in row) freq[v]++

        val cdf = IntArray(256)
        cdf[0] = freq[0]
        for (i in 1..255) cdf[i] = cdf[i - 1] + freq[i]
        val cdfMin = cdf.first { it > 0 }
        val total  = h * w

        return Array(h) { y ->
            IntArray(w) { x ->
                ((cdf[raw[y][x]] - cdfMin).toFloat() / (total - cdfMin) * 255).toInt().coerceIn(0, 255)
            }
        }
    }

    /** Returns a score in [0, 1] — 1 means identical histograms. */
    private fun chiSquareSimilarity(a: FloatArray, b: FloatArray): Float {
        var chi = 0.0
        val n   = minOf(a.size, b.size)
        for (i in 0 until n) {
            val sum = a[i] + b[i]
            if (sum > 0f) chi += (a[i] - b[i]).toDouble().pow(2) / sum
        }
        return (1.0 / (1.0 + chi)).toFloat()
    }

    // ── Geometry helpers ──────────────────────────────────────────────────────

    private fun rotateBitmap(bitmap: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return bitmap
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    private fun cropFace(bitmap: Bitmap, face: Face): Bitmap? {
        val box    = face.boundingBox
        val padX   = (box.width()  * 0.25f).toInt()
        val padY   = (box.height() * 0.25f).toInt()
        val left   = (box.left   - padX).coerceAtLeast(0)
        val top    = (box.top    - padY).coerceAtLeast(0)
        val right  = (box.right  + padX).coerceAtMost(bitmap.width)
        val bottom = (box.bottom + padY).coerceAtMost(bitmap.height)
        if (right <= left || bottom <= top) return null
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    companion object {
        private const val FACE_SIZE = 96    // resize face crop to 96×96
        private const val GRID      = 6     // 6×6 cell grid → 36 cells
        private const val BINS      = 256   // LBP histogram bins per cell

        // chi-square similarity threshold
        // same person: ~0.50–0.80 | strangers: ~0.05–0.40
        private const val THRESHOLD = 0.48f
    }
}
