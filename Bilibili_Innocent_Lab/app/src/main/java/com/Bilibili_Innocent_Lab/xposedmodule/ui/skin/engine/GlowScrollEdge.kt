package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

internal typealias GlowScrollEdge = com.lumen.coacervation.engine.glow.GlowScrollEdge
internal typealias GlowScrollEdgePolicy = com.lumen.coacervation.engine.glow.GlowScrollEdgePolicy

/**
 * 滚动边缘溶解层：铺满内容容器、叠在内容之上与悬浮栏之下，只在胶囊所在的那条带里把窗口底图
 * 按剖面 × 覆盖度画回来。
 *
 * 只画一次带 [LinearGradient] 遮罩的底图矩形（引擎的 [GlowEngine.drawWindowBackdrop]），
 * 不开离屏层；覆盖度到 1 之后滚动不再重录。铺满容器而不是按带宽布局：胶囊几何变化只需要
 * 重录，不触发任何 requestLayout。
 */
@SuppressLint("ViewConstructor")
internal class GlowScrollEdgeView(
    context: Context,
    val edge: GlowScrollEdge,
    private val tailPx: Float,
    private val engine: () -> GlowEngine?
) : View(context) {
    private val band = RectF()
    private var gradient: Shader? = null
    private var capsuleTop = Float.NaN
    private var capsuleBottom = Float.NaN
    private var gradientHeight = -1

    var coverage: Float = 0f
        set(value) {
            val next = GlowScrollEdgePolicy.quantize(value)
            if (abs(next - field) < GlowScrollEdgePolicy.COVERAGE_QUANTUM * 0.5f) return
            field = next
            invalidate()
        }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    /** 胶囊在本层局部坐标下的竖向范围；NaN 表示胶囊不存在（不溶解）。 */
    fun setCapsule(top: Float, bottom: Float) {
        if (top == capsuleTop && bottom == capsuleBottom) return
        capsuleTop = top
        capsuleBottom = bottom
        gradient = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (coverage <= 0f || width <= 0 || height <= 0 || capsuleTop.isNaN() || capsuleBottom.isNaN()) return
        val glow = engine() ?: return
        val h = height.toFloat()
        if (edge == GlowScrollEdge.TOP) {
            band.set(0f, 0f, width.toFloat(), (capsuleBottom + tailPx).coerceIn(1f, h))
        } else {
            band.set(0f, (capsuleTop - tailPx).coerceIn(0f, h - 1f), width.toFloat(), h)
        }
        val mask = gradient?.takeIf { gradientHeight == height } ?: buildGradient()
        glow.drawWindowBackdrop(canvas, this, band, mask, coverage)
    }

    /** 渐变建在本层局部坐标：TOP 从 y=0（屏幕边）往下，BOTTOM 从 y=height 往上。 */
    private fun buildGradient(): Shader {
        val shader = if (edge == GlowScrollEdge.TOP) {
            val length = band.bottom
            val (positions, alphas) = GlowScrollEdgePolicy.profile(capsuleTop, capsuleBottom, length)
            LinearGradient(0f, 0f, 0f, length, colors(alphas), positions, Shader.TileMode.CLAMP)
        } else {
            val h = height.toFloat()
            val length = h - band.top
            val (positions, alphas) = GlowScrollEdgePolicy.profile(h - capsuleBottom, h - capsuleTop, length)
            LinearGradient(0f, h, 0f, band.top, colors(alphas), positions, Shader.TileMode.CLAMP)
        }
        gradient = shader
        gradientHeight = height
        return shader
    }

    private fun colors(alphas: FloatArray) =
        IntArray(alphas.size) { Color.argb((alphas[it] * 255f).roundToInt(), 0, 0, 0) }
}
