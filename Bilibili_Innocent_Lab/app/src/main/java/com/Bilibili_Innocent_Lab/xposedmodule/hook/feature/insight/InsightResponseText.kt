package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.view.View
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import io.github.jichuo1.meguviz.android.extensions.toPx
import com.lumen.coacervation.engine.motion.LumenAnimator
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** 面板拥有的动效；正文只在增量到达时重新布局，动画帧仅重绘透明度。 */
internal class InsightResponseText(private val view: TextView, private val follow: () -> Unit = {}) : AutoCloseable {
    private val reveal = InsightTextReveal()
    private val dots = WaitingDots()
    private var animator: ValueAnimator? = null
    private var waiting = false
    private var closed = false
    private var received = false
    private var waitingLabel = ""
    private var content: CharSequence = ""
    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) { close() }
    }
    init { view.addOnAttachStateChangeListener(detach) }
    fun begin(label: String) {
        if (closed) return
        waitingLabel = label; waiting = true; received = false; reveal.clear()
        view.text = label
        val size = 32.toPx(view.context)
        dots.setBounds(0, 0, size, 16.toPx(view.context))
        view.compoundDrawablePadding = 8.toPx(view.context)
        view.setCompoundDrawablesRelative(null, null, dots, null)
        animate()
    }
    fun preview(text: String) {
        if (closed) return
        if (text.isEmpty()) { begin(waitingLabel); return }
        waiting = false; received = true
        view.setCompoundDrawablesRelative(null, null, null, null)
        show(text, animated = true)
    }
    fun complete(text: CharSequence) {
        if (closed) return
        waiting = false; view.setCompoundDrawablesRelative(null, null, null, null)
        show(text, animated = received)
    }
    fun fail(text: String) {
        if (closed) return
        waiting = false; received = false; reveal.clear(); stop()
        view.setCompoundDrawablesRelative(null, null, null, null); view.text = text
    }
    private fun show(text: CharSequence, animated: Boolean) {
        content = text
        val now = SystemClock.uptimeMillis()
        val spans = if (animated && ValueAnimator.areAnimatorsEnabled()) reveal.update(text.toString(), now) else emptyList()
        view.text = SpannableStringBuilder(text).apply {
            spans.forEach { glyph -> setSpan(object : CharacterStyle() {
                override fun updateDrawState(paint: TextPaint) {
                    paint.alpha = (paint.alpha * glyph.alpha(SystemClock.uptimeMillis())).roundToInt()
                }
            }, glyph.start, glyph.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        follow()
        if (spans.isEmpty()) stop() else animate()
    }
    private fun animate() {
        if (closed || animator != null || !ValueAnimator.areAnimatorsEnabled()) return
        animator = LumenAnimator.ofFloat(0f, 1f).apply {
            duration = 1200; repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { value -> HostThreadGuard.run("insight.response-frame") {
                if (!ValueAnimator.areAnimatorsEnabled()) {
                    if (!waiting) { view.text = content; reveal.clear() }
                    stop(); return@run
                }
                if (waiting) { dots.phase = value.animatedValue as Float; dots.invalidateSelf() }
                else { view.invalidate(); if (!reveal.active(SystemClock.uptimeMillis())) stop() }
            } }
            start()
        }
    }
    private fun stop() {
        animator?.let { it.removeAllUpdateListeners(); it.cancel() }; animator = null
    }
    override fun close() {
        if (closed) return
        closed = true; stop(); reveal.clear()
        view.setCompoundDrawablesRelative(null, null, null, null)
        view.removeOnAttachStateChangeListener(detach)
    }
    private inner class WaitingDots : Drawable() {
        var phase = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            paint.color = view.currentTextColor
            val radius = bounds.height() * .13f
            for (index in 0..2) {
                val pulse = ((sin((phase * 2 - index * .35) * PI) + 1) * .5).toFloat()
                paint.alpha = (255 * (.25f + .75f * pulse)).roundToInt()
                val x = bounds.left + bounds.width() * (index + .5f) / 3
                val y = bounds.exactCenterY() - radius * pulse
                canvas.drawCircle(x, y, radius, paint)
            }
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Android") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
