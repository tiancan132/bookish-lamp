package com.example.smoothcensor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.View

/**
 * 遮挡层：一个铺满屏幕的透明 View，在上面画黑块。
 *
 * 它自己被声明为 FLAG_NOT_TOUCHABLE，所以不会挡住你的点击。
 */
class OverlayView(context: Context) : View(context) {

    private val paints = HashMap<String, Paint>()

    /** 要画的框（归一化坐标） */
    private var boxes: List<Detection> = emptyList()

    /** 是否处于调试模式（画边框而不是实心块） */
    var debugMode: Boolean = false

    init {
        // 透明背景，允许点击穿透由 WindowManager 的 flag 控制
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun updateBoxes(newBoxes: List<Detection>) {
        boxes = newBoxes
        postInvalidate()
    }

    /** 不同部位用不同颜色，方便调试时区分 */
    private fun paintFor(label: String): Paint {
        return paints.getOrPut(label) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = if (debugMode) Paint.Style.STROKE else Paint.Style.FILL
                strokeWidth = if (debugMode) 4f else 0f
                color = if (debugMode) colorFor(label) else Color.BLACK
            }
        }
    }

    private fun colorFor(label: String): Int = when {
        label.contains("EXPOSED") -> Color.RED
        label.contains("COVERED") -> Color.CYAN
        label.contains("FACE") -> Color.YELLOW
        else -> Color.GREEN
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (boxes.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()

        for (det in boxes) {
            val rect = RectF(
                det.left * w,
                det.top * h,
                det.right * w,
                det.bottom * h,
            )
            // 稍微外扩一点，确保盖严实
            rect.inset(-2f, -2f)
            canvas.drawRect(rect, paintFor(det.label))
        }
    }

    companion object {
        /** 悬浮窗的布局参数 */
        fun layoutParams(): android.view.WindowManager.LayoutParams {
            return android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // 关键：不获取焦点、不拦截触摸
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
        }
    }
}
