package com.phonenap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Renders:
 *  1. Solid black background everywhere
 *  2. A transparent circle in the centre — camera preview shows through
 *  3. A teal progress arc (ring) just outside the circle
 *
 * Set [progress] (0.0–1.0) to advance the ring.
 * Set [ringColor] to change the ring colour (e.g. green when complete).
 */
class FaceGuideOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // --- Paints ---

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
    }

    // CLEAR erases pixels → makes them transparent → camera shows through
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    // Faint white ring behind the progress arc
    private val ringBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style      = Paint.Style.STROKE
        strokeWidth = 12f
        color      = Color.argb(50, 255, 255, 255)
    }

    // Active progress arc
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style      = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap  = Paint.Cap.ROUND
        color      = Color.parseColor("#00BCD4")   // teal
    }

    // --- Public properties ---

    /** 0.0 = empty, 1.0 = full ring. Setting triggers a redraw. */
    var progress: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    /** Colour of the progress arc. Change to green when enrollment is done. */
    var ringColor: Int = Color.parseColor("#00BCD4")
        set(value) { field = value; ringPaint.color = value; invalidate() }

    // --- Circle geometry (computed once in onSizeChanged) ---

    var circleCx: Float = 0f; private set
    var circleCy: Float = 0f; private set
    var circleRadius: Float = 0f; private set

    init {
        // Hardware layer is required for PorterDuff.Mode.CLEAR to punch a
        // transparent hole through an opaque layer on API 14+.
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        circleCx     = w / 2f
        circleCy     = h / 2f
        circleRadius = minOf(w, h) * 0.38f
    }

    override fun onDraw(canvas: Canvas) {
        if (circleRadius == 0f) return

        // 1. Fill entire canvas with opaque black
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)

        // 2. Punch transparent hole — camera preview visible here
        canvas.drawCircle(circleCx, circleCy, circleRadius, clearPaint)

        // 3. Progress ring just outside the circle
        val ringR = circleRadius + 20f
        val oval  = RectF(
            circleCx - ringR, circleCy - ringR,
            circleCx + ringR, circleCy + ringR
        )
        canvas.drawArc(oval, -90f, 360f, false, ringBgPaint)
        if (progress > 0f) {
            canvas.drawArc(oval, -90f, 360f * progress, false, ringPaint)
        }
    }
}
