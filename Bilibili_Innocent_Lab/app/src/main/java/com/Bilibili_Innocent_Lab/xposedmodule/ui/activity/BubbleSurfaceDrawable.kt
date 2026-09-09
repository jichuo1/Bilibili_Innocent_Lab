package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils

/**
 * 带指向小角的气泡表面。
 *
 * 小角是路径的一部分而不是额外的 View，因此它跟着气泡一起缩放、一起被 alpha 影响，
 * 展开时看起来就是从图标那端"挤"出来的。
 *
 * 尖端对齐图标，根部独立避让圆角，避免贴边时连尖端也一起被推偏。
 */
internal class BubbleSurfaceDrawable(
    private val fillColor: Int,
    private val cornerRadiusPx: Float,
    private val tailHeightPx: Float,
    private val tailHalfWidthPx: Float,
    private val tailEdge: BubbleTailEdge,
    private val tailCenterX: Float,
    private val tailBaseCenterX: Float = tailCenterX,
    private val strokeColor: Int = ColorUtils.setAlphaComponent(android.graphics.Color.WHITE, 0x18),
    private val strokeWidthPx: Float = 0f
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = strokeColor
        strokeWidth = strokeWidthPx
    }
    private val path = Path()
    private val tail = Path()
    private val body = RectF()
    private var pathDirty = true

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        pathDirty = true
    }

    private fun rebuild() {
        val b = bounds
        path.reset()
        pathDirty = false
        if (b.isEmpty) return
        // 小角占掉整体高度的一条，剩下的才是圆角矩形本体。
        val inset = strokeWidthPx / 2f
        body.set(
            b.left.toFloat() + inset,
            if (tailEdge == BubbleTailEdge.TOP) b.top + tailHeightPx + inset else b.top + inset,
            b.right.toFloat() - inset,
            if (tailEdge == BubbleTailEdge.BOTTOM) b.bottom - tailHeightPx - inset else b.bottom - inset
        )
        if (body.width() <= 0f || body.height() <= 0f) return
        val radius = cornerRadiusPx.coerceAtMost(minOf(body.width(), body.height()) / 2f)
        path.addRoundRect(body, radius, radius, Path.Direction.CW)

        // 尖端夹在本体范围内；贴边气泡的小角可能非常靠近圆角，夹一次避免长到圆弧外面。
        val tipX = (b.left + tailCenterX).coerceIn(body.left, body.right)
        val halfWidth = tailHalfWidthPx.coerceAtMost((body.width() / 2f - radius).coerceAtLeast(0f))
        val baseX = (b.left + tailBaseCenterX).coerceIn(
            body.left + radius + halfWidth, body.right - radius - halfWidth
        )
        tail.reset()
        if (tailEdge == BubbleTailEdge.TOP) {
            tail.moveTo(baseX - halfWidth, body.top)
            tail.quadTo(baseX - halfWidth * 0.4f, body.top, tipX, b.top.toFloat() + inset)
            tail.quadTo(baseX + halfWidth * 0.4f, body.top, baseX + halfWidth, body.top)
        } else {
            tail.moveTo(baseX - halfWidth, body.bottom)
            tail.quadTo(baseX - halfWidth * 0.4f, body.bottom, tipX, b.bottom.toFloat() - inset)
            tail.quadTo(baseX + halfWidth * 0.4f, body.bottom, baseX + halfWidth, body.bottom)
        }
        tail.close()
        // 用并集而不是分别绘制：分开画会在本体与小角的接缝上留下一条描边。
        path.op(tail, Path.Op.UNION)
        pathDirty = false
    }

    override fun draw(canvas: Canvas) {
        if (pathDirty) rebuild()
        if (path.isEmpty) return
        canvas.drawPath(path, fillPaint)
        if (strokeWidthPx > 0f) canvas.drawPath(path, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /** 小角尖端在 drawable 自身坐标里的位置，供动画取缩放锚点。 */
    fun tipX(): Float = tailCenterX

    fun tipY(): Float = if (tailEdge == BubbleTailEdge.TOP) 0f else bounds.height().toFloat()
}
