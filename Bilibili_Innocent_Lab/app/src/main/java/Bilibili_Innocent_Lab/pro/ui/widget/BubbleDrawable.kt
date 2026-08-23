package Bilibili_Innocent_Lab.pro.ui.widget

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * 自由复制气泡背景：圆角矩形 + 顶部三角形箭头（合成一个 shape）
 *
 * 用 Path 一次绘制，避免原方案（body GradientDrawable + 独立 arrow View）的布局叠加缺陷——
 * 原方案 body 顶部圆弧会"咬掉"箭头菱形下半的两侧，导致亮色模式下尤为明显的方角露出。
 *
 * 性能与描边优化：
 * 1. Path 缓存 —— bounds 不变时复用同一 Path，避免动画每帧 new Path + 重算坐标的 GC/CPU 开销。
 * 2. 矢量缩放 —— 动画期间通过 [scale] 驱动 canvas.scale 围绕箭头 pivot 缩放，而非 View 级
 *    scaleX/scaleY（后者是 GPU 对已渲染纹理的重采样，1dp 细描边会被重采样成重影/模糊）。
 *    矢量缩放让描边随气泡等比缩放，始终清晰，无重影、无跳变。
 */
class BubbleDrawable(
    private val bubbleColor: Int,
    private val arrowWidthPx: Float,       // 箭头底部宽度（dp 转 px）
    private val arrowHeightPx: Float,      // 箭头向上突出的高度
    private val cornerRadiusPx: Float,     // 气泡四角圆角半径
    private val arrowOffsetPx: Float,      // 箭头中心距气泡左边缘的距离（指向长按评论中心）
    private val strokeColor: Int = 0,      // 描边颜色（0 = 无描边）
    private val strokeWidthPx: Float = 0f  // 描边宽度（px）
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bubbleColor
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = strokeColor
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeJoin = Paint.Join.ROUND   // 圆角连接，避免箭头尖端描边过于尖锐
        strokeCap = Paint.Cap.ROUND
    }

    private val strokeEnabled = strokeWidthPx > 0f && strokeColor != 0

    // 缓存 Path：bounds 不变则复用，避免动画每帧重建
    private val path = Path()
    private var cachedW = -1f
    private var cachedH = -1f
    // 缓存实际箭头中心 x（供缩放 pivot 用）
    private var pivotCx = 0f

    /**
     * 动画驱动的缩放（1f = 正常大小）。
     * 矢量缩放：气泡主体随缩放矢量重绘，无纹理重采样。
     */
    var scale = 1f
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    /**
     * 描边独立透明度（0f~1f）。
     * 动画播放期间快速淡出（描边不参与缩放，避免跳变/重影），动画结束淡入恢复。
     */
    var strokeAlpha = 1f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (field != v) {
                field = v
                strokePaint.alpha = (v * 255f + 0.5f).toInt()
                invalidateSelf()
            }
        }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return

        // bounds 变化才重建 Path（缩放是 canvas 变换，不改变 bounds）
        if (w != cachedW || h != cachedH) {
            rebuildPath(w, h)
            cachedW = w
            cachedH = h
        }

        canvas.save()
        // 围绕箭头 pivot 矢量缩放
        canvas.scale(scale, scale, pivotCx, 0f)
        canvas.drawPath(path, fillPaint)
        // 描边透明时跳过绘制，省一次 drawPath
        if (strokeEnabled && strokePaint.alpha > 0) {
            canvas.drawPath(path, strokePaint)
        }
        canvas.restore()
    }

    private fun rebuildPath(w: Float, h: Float) {
        val aw = arrowWidthPx
        val ah = arrowHeightPx
        val r = cornerRadiusPx.coerceAtMost(minOf(w, h) / 2f)
        // 箭头中心 x：限制在 body 内的合理范围（避免箭头尖端贴到圆角处）
        val arrowCx = arrowOffsetPx.coerceIn(aw / 2f + r * 0.4f, w - aw / 2f - r * 0.4f)
        val arrowLeft = arrowCx - aw / 2f
        val arrowRight = arrowCx + aw / 2f
        pivotCx = arrowCx

        path.reset()
        // 从左上圆角开始，逆时针绕一圈
        path.moveTo(r, 0f)
        // 顶部：到箭头左
        path.lineTo(arrowLeft, 0f)
        // 箭头向上突出（尖端在 y = -ah）
        path.lineTo(arrowCx, -ah)
        // 箭头右
        path.lineTo(arrowRight, 0f)
        // 顶部继续到右上圆角
        path.lineTo(w - r, 0f)
        // 右上圆角
        path.quadTo(w, 0f, w, r)
        // 右边
        path.lineTo(w, h - r)
        // 右下圆角
        path.quadTo(w, h, w - r, h)
        // 底部
        path.lineTo(r, h)
        // 左下圆角
        path.quadTo(0f, h, 0f, h - r)
        // 左边
        path.lineTo(0f, r)
        // 左上圆角
        path.quadTo(0f, 0f, r, 0f)
        path.close()
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /** 切换气泡颜色（亮色/暗色模式切换） */
    fun setBubbleColor(color: Int) {
        fillPaint.color = color
        invalidateSelf()
    }
}
