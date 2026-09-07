package com.Bilibili_Innocent_Lab.xposedmodule.ui.widget

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/** 复用现有气泡 Path，将箭头翻到左下方；箭头包含在 View 尺寸内。 */
class GithubUpdateBadgeDrawable(color: Int, private val density: Float) : Drawable() {
    private val bubble = BubbleDrawable(color, 6f*density, 3f*density, 5f*density, 5f*density)
    private var bodyHeight = 0
    override fun onBoundsChange(bounds: Rect) {
        bodyHeight = (bounds.height() - 3f*density).toInt().coerceAtLeast(0)
        bubble.setBounds(0, 0, bounds.width(), bodyHeight)
    }
    override fun draw(canvas: Canvas) {
        if (bodyHeight <= 0) return
        val checkpoint = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top + bodyHeight.toFloat())
        canvas.scale(1f, -1f)
        bubble.draw(canvas)
        canvas.restoreToCount(checkpoint)
    }
    override fun setAlpha(alpha: Int) { bubble.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { bubble.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
