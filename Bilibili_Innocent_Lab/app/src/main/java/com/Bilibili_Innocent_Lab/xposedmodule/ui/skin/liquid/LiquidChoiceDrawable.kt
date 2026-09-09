package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils

/** Native CompoundButton continues to own state, accessibility, touch and thumb animation. */
internal class LiquidChoiceDrawable(
    private val width: Int,
    private val height: Int,
    private val density: Float,
    private val surface: Int,
    private val accent: Int,
    private val onAccent: Int,
    private val outline: Int,
    private val checkbox: Boolean = false,
    private val thumb: Boolean = false
) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val tick = Path()
    private var visualState = LiquidControlStyle.resolve(true, false, false, false)
    private var drawableAlpha = 255
    private var radius = 0f

    override fun getIntrinsicWidth() = width
    override fun getIntrinsicHeight() = height
    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val next = LiquidControlStyle.resolve(android.R.attr.state_enabled in state,
            android.R.attr.state_checked in state, android.R.attr.state_pressed in state,
            android.R.attr.state_focused in state)
        if (next == visualState) return false
        visualState = next
        updatePaints()
        invalidateSelf()
        return true
    }
    override fun onBoundsChange(bounds: Rect) {
        val inset = if (checkbox) 3f * density else density
        rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        radius = if (checkbox) 4f * density else rect.height() / 2f
        tick.reset()
        tick.moveTo(rect.left + rect.width() * .22f, rect.top + rect.height() * .51f)
        tick.lineTo(rect.left + rect.width() * .43f, rect.top + rect.height() * .72f)
        tick.lineTo(rect.left + rect.width() * .79f, rect.top + rect.height() * .29f)
        updatePaints()
    }
    private fun updatePaints() {
        val selected = visualState.selected && !thumb
        val base = if (selected) accent else surface
        val alpha = LiquidControlStyle.fillAlpha(visualState)
        fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom.coerceAtLeast(rect.top + 1f),
            ColorUtils.setAlphaComponent(ColorUtils.blendARGB(base, Color.WHITE, .22f), alpha),
            ColorUtils.setAlphaComponent(base, if (thumb) 220 else alpha), Shader.TileMode.CLAMP)
        edge.color = if (visualState.emphasized || selected) accent else outline
        edge.strokeWidth = (if (visualState.emphasized) 1.8f else 1f) * density
        mark.color = onAccent
        mark.strokeWidth = 2f * density
    }
    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        val alpha = drawableAlpha * LiquidControlStyle.opacity(visualState) / 255
        fill.alpha = alpha
        edge.alpha = alpha
        mark.alpha = alpha
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, edge)
        if (checkbox && visualState.selected) canvas.drawPath(tick, mark)
    }
    override fun setAlpha(alpha: Int) { drawableAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun getAlpha() = drawableAlpha
    override fun setColorFilter(filter: ColorFilter?) {
        fill.colorFilter = filter; edge.colorFilter = filter; mark.colorFilter = filter
        invalidateSelf()
    }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
