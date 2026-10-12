package com.example.smoothcensor

import android.graphics.RectF

/**
 * 一个检测框。
 *
 * @param label    类别名，例如 "FEMALE_BREAST_EXPOSED"
 * @param score    置信度 0..1
 * @param left/top/right/bottom  归一化坐标 0..1（相对于屏幕）
 */
data class Detection(
    val label: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width get() = right - left
    val height get() = bottom - top
    val area get() = width * height

    fun toRectF(): RectF = RectF(left, top, right, bottom)

    /** 与另一个框的交并比，用于去重 */
    fun iou(other: Detection): Float {
        val ix = maxOf(0f, minOf(right, other.right) - maxOf(left, other.left))
        val iy = maxOf(0f, minOf(bottom, other.bottom) - maxOf(top, other.top))
        val inter = ix * iy
        val union = area + other.area - inter
        return if (union <= 0f) 0f else inter / union
    }
}
