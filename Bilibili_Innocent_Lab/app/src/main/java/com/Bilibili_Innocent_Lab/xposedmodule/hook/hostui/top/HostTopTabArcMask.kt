package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import androidx.core.graphics.withTranslation
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 顶栏标签两端的渐隐边界不是竖直硬线，而是与外围胶囊同半径的端帽圆弧：
 * 右侧边界像胶囊右端向右凸，左侧像左端向左凸；行中线处正好落在分割边缘，越靠上下越往里收。
 * 每个水平条带沿自身的圆弧边界做水平羽化，View 与 Compose 两条路径共用。
 */
internal object HostTopTabArcShape {
    /** 端帽圆心以下/以上 [dy] 处，圆弧相对圆心的水平外凸量；超出半径的条带贴到圆心列。 */
    fun bulge(radius: Float, dy: Float): Float {
        if (radius <= 0f) return 0f
        val d = abs(dy)
        return if (d >= radius) 0f else sqrt(radius * radius - d * d)
    }

    /** 右侧边界：端帽圆心在 [end] 左侧一个半径处，行中线上边界与 [end] 重合。 */
    fun trailingBoundary(end: Float, radius: Float, dy: Float): Float = end - radius + bulge(radius, dy)

    /** 左侧边界与右侧镜像。 */
    fun leadingBoundary(start: Float, radius: Float, dy: Float): Float = start + radius - bulge(radius, dy)
}

/** 在已隔离的图层内用 DST_OUT 擦除标签内容；调用方负责 saveLayer 或硬件层。 */
internal class HostTopTabArcMask {
    private val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    // 单位渐变固定不变，靠画布变换映射到每个条带：不重建原生着色器。CLAMP 让边界外侧完全擦除。
    private val trailingShader = LinearGradient(0f, 0f, 1f, 0f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
    private val leadingShader = LinearGradient(0f, 0f, 1f, 0f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)

    /**
     * [top]..[bottom] 是要处理的纵向范围，[left]..[right] 是可擦除的横向范围，坐标与 [canvas] 一致。
     * [centerY]/[radius] 是外围胶囊的中线与端帽半径；[leading]/[trailing] 是两侧实际羽化长度，0 表示该侧不渐隐。
     * [band] 为条带高度（约 1dp），圆弧在条带内取中点近似。
     */
    fun draw(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, centerY: Float, radius: Float,
             start: Float, end: Float, leading: Float, trailing: Float, band: Float) {
        if (leading <= 0f && trailing <= 0f || bottom <= top || right <= left) return
        // 端帽以外的上下区域边界是同一条竖线，各合成一个条带；只有端帽内按 band 逐条取样。
        // 条带边界对齐整数像素：无抗锯齿的矩形落在小数行上会叠擦或漏擦一行，渐隐里出现细横纹。
        val capTop = floor(centerY - radius).coerceIn(top, bottom)
        val capBottom = ceil(centerY + radius).coerceIn(top, bottom)
        if (capTop > top) strip(canvas, left, right, top, capTop, centerY, radius, start, end, leading, trailing)
        val step = band.roundToInt().coerceAtLeast(1).toFloat()
        var y = capTop
        while (y < capBottom) {
            val next = minOf(capBottom, y + step)
            strip(canvas, left, right, y, next, centerY, radius, start, end, leading, trailing)
            y = next
        }
        if (capBottom < bottom) strip(canvas, left, right, capBottom, bottom, centerY, radius, start, end, leading, trailing)
        paint.shader = null
    }

    private fun strip(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float, centerY: Float, radius: Float,
                      start: Float, end: Float, leading: Float, trailing: Float) {
        // 条带外侧整段的 dy 至少为半径时，取样点落在端帽外，边界即圆心列。
        val dy = (top + bottom) / 2f - centerY
        if (trailing > 0f) {
            val boundary = HostTopTabArcShape.trailingBoundary(end, radius, dy)
            erase(canvas, trailingShader, boundary - trailing, trailing, maxOf(left, boundary - trailing), right, top, bottom)
        }
        if (leading > 0f) {
            val boundary = HostTopTabArcShape.leadingBoundary(start, radius, dy)
            erase(canvas, leadingShader, boundary, leading, left, minOf(right, boundary + leading), top, bottom)
        }
    }

    private fun erase(canvas: Canvas, shader: LinearGradient, from: Float, length: Float,
                      left: Float, right: Float, top: Float, bottom: Float) {
        if (right <= left || length <= 0f) return
        paint.shader = shader
        canvas.withTranslation(from, 0f) {
            scale(length, 1f)
            drawRect((left - from) / length, top, (right - from) / length, bottom, paint)
        }
    }
}
