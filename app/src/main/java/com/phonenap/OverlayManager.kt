package com.phonenap

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.util.Calendar

class OverlayManager(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlay: View? = null
    private val handler = Handler(Looper.getMainLooper())

    private val clockTick = object : Runnable {
        override fun run() {
            overlay?.findViewById<TextView>(R.id.tvClock)?.let { tv ->
                val c = Calendar.getInstance()
                tv.text = "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            }
            if (overlay != null) handler.postDelayed(this, 10_000)
        }
    }

    val isShowing: Boolean get() = overlay != null

    fun show() {
        if (overlay != null) return  // already visible
        handler.post {
            try {
                val view = LayoutInflater.from(context).inflate(R.layout.overlay_screen, null)
                val params = makeParams()
                wm.addView(view, params)
                overlay = view
                handler.post(clockTick)

                // Wire up "Scan parent face" button
                view.findViewById<View>(R.id.btnUnlock)?.setOnClickListener {
                    PhoneNapService.instance?.triggerActiveScan()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun hide() {
        handler.post {
            overlay?.let { v ->
                try { wm.removeView(v) } catch (_: Exception) {}
            }
            overlay = null
            handler.removeCallbacks(clockTick)
        }
    }

    @Suppress("DEPRECATION")
    private fun makeParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_FULLSCREEN or
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        PixelFormat.OPAQUE
    ).also {
        it.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
}
