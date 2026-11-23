package com.tunelag.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * Burbuja flotante. Un toque dispara el lag, mantener pulsado la oculta.
 * No toma foco, así el juego no pierde el input.
 */
class FloatingBubble(
    private val ctx: Context,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit
) {

    private val ui = Handler(Looper.getMainLooper())
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val shape = GradientDrawable()

    private var node: TextView? = null
    private var highlighted = false

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun permitted(): Boolean = Settings.canDrawOverlays(ctx)

    fun show() {
        if (node != null || !permitted()) return
        ui.post {
            val v = TextView(ctx).apply {
                text = "LAG"
                setTextColor(Color.WHITE)
                textSize = 11f
                gravity = Gravity.CENTER
                shape.cornerRadius = dp(28).toFloat()
                shape.setColor(if (highlighted) COLOR_ON else COLOR_OFF)
                background = shape
            }
            val lp = WindowManager.LayoutParams(
                dp(56), dp(56),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = ctx.resources.displayMetrics.widthPixels - dp(76)
                y = dp(140)
            }

            var originX = 0f
            var originY = 0f
            var baseX = 0
            var baseY = 0
            var dragged = false
            var consumed = false

            val holdJob = Runnable {
                if (!dragged) {
                    consumed = true
                    v.alpha = 1f
                    onLongPress()
                }
            }

            v.setOnTouchListener { _, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        originX = ev.rawX
                        originY = ev.rawY
                        baseX = lp.x
                        baseY = lp.y
                        dragged = false
                        consumed = false
                        v.alpha = 0.75f
                        ui.postDelayed(holdJob, 550)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (ev.rawX - originX).toInt()
                        val dy = (ev.rawY - originY).toInt()
                        if (!dragged && (abs(dx) > dp(6) || abs(dy) > dp(6))) {
                            dragged = true
                            ui.removeCallbacks(holdJob)
                        }
                        if (dragged) {
                            lp.x = baseX + dx
                            lp.y = baseY + dy
                            try {
                                wm.updateViewLayout(v, lp)
                            } catch (_: Exception) {
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        ui.removeCallbacks(holdJob)
                        v.alpha = 1f
                        if (!dragged && !consumed) onTap()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        ui.removeCallbacks(holdJob)
                        v.alpha = 1f
                        true
                    }
                    else -> false
                }
            }

            try {
                wm.addView(v, lp)
                node = v
            } catch (_: Exception) {
                EventLog.record("no se pudo mostrar la burbuja")
            }
        }
    }

    fun remove() {
        ui.post {
            node?.let {
                try {
                    wm.removeView(it)
                } catch (_: Exception) {
                }
            }
            node = null
        }
    }

    fun markActive(on: Boolean) {
        highlighted = on
        ui.post {
            shape.setColor(if (on) COLOR_ON else COLOR_OFF)
            node?.invalidate()
        }
    }

    private companion object {
        const val COLOR_ON = 0xFFE5484D.toInt()
        const val COLOR_OFF = 0xFF1E9E6A.toInt()
    }
}2
