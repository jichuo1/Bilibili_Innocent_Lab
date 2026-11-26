package com.Bilibili_Innocent_Lab.xposedmodule.agent.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentTaskState
import io.github.jichuo1.meguviz.android.runtime.core.FrameDriver
import io.github.jichuo1.meguviz.android.runtime.platform.BudgetFrameDriver
import io.github.jichuo1.meguviz.android.runtime.platform.MainThreadDispatcher
import kotlin.math.ceil
import kotlin.math.floor

/** 小型公开悬浮窗的绘制内容；图标、画笔、测量和矩形均在布局更新时缓存。 */
internal class AgentIslandView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(12, 16, 24) }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(114, 210, 255) }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)
    }
    private val background = RectF()
    private val glyphBounds = Rect()
    private val dotX = FloatArray(4)
    private val dotY = FloatArray(4)
    private val moduleIcon = context.getDrawable(R.mipmap.ic_launcher)?.mutate()
    private val operating = context.getString(R.string.agent_island_operating)
    private val operatingShort = context.getString(R.string.agent_island_operating_short)
    private val caption = "Agent"
    private val captionWidth = captionPaint.measureText(caption)
    private val shortWidth = captionPaint.measureText(operatingShort)
    private val captionHeight = captionPaint.fontMetrics.let { it.descent - it.ascent }
    private var placement: AgentIslandGeometry.Placement? = null
    private var agentX = 0f
    private var captions = 0
    private var phase = ""
    private var running = false
    private var requestedAnimation = false
    private var closed = false
    private var ready = false
    private var frameScheduled = false
    private var frame = 0
    private var lastAdvanceNanos = 0L
    private val frameIntervalNanos = AgentIslandAnimationPolicy.FRAME_MS * 1_000_000L
    private val frameDriver: FrameDriver = BudgetFrameDriver(MainThreadDispatcher(), true)

    private val frameTick = object : FrameDriver.Callback {
        override fun onFrame(frameTimeNanos: Long) {
            frameScheduled = false
            if (!mayAnimate()) {
                lastAdvanceNanos = 0L
                invalidateGlyph()
                return
            }
            if (lastAdvanceNanos == 0L || frameTimeNanos - lastAdvanceNanos >= frameIntervalNanos) {
                lastAdvanceNanos = frameTimeNanos
                frame = (frame + 1) and 0xFFFF
                invalidateGlyph()
            }
            scheduleFrame()
        }
    }

    init {
        isClickable = true
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ready = true
    }

    fun update(state: AgentTaskState, placement: AgentIslandGeometry.Placement) {
        if (closed) return
        if (this.placement != placement) {
            this.placement = placement
            cacheGeometry(placement)
            invalidate()
        }
        if (phase != state.phase || running != state.running) {
            phase = state.phase
            running = state.running
            contentDescription = operating + "，" + AgentStatusText.tip(context, state)
        }
        reconcileAnimation()
    }

    fun setAnimating(enabled: Boolean) {
        if (closed) return
        requestedAnimation = enabled
        reconcileAnimation()
    }

    fun close() {
        closed = true
        requestedAnimation = false
        cancelFrame()
        setOnClickListener(null)
    }

    private fun cacheGeometry(value: AgentIslandGeometry.Placement) {
        val width = value.bounds.width
        val height = value.bounds.height
        background.set(0f, 0f, width.toFloat(), height.toFloat())
        val halfIcon = 11f * density
        moduleIcon?.setBounds((value.moduleCenterXPx - halfIcon).toInt(), (value.iconCenterYPx - halfIcon).toInt(),
            (value.moduleCenterXPx + halfIcon).toInt(), (value.iconCenterYPx + halfIcon).toInt())
        agentX = value.agentCenterXPx.toFloat()
        val availableWidth = width - value.gapEndPx - 8f * density
        captions = when {
            captionWidth <= availableWidth && shortWidth <= availableWidth && captionHeight <= 12f * density -> 2
            captionWidth <= availableWidth && captionHeight <= 19f * density -> 1
            else -> 0
        }
        val centerY = when (captions) { 2 -> 11f * density; 1 -> 14f * density; else -> value.iconCenterYPx.toFloat() }
        val offset = 5f * density
        dotX[0] = agentX - offset
        dotY[0] = centerY - offset
        dotX[1] = agentX + offset
        dotY[1] = centerY - offset
        dotX[2] = agentX + offset
        dotY[2] = centerY + offset
        dotX[3] = agentX - offset
        dotY[3] = centerY + offset
        val extent = offset + 2.5f * density
        glyphBounds.set(floor(agentX - extent).toInt(), floor(centerY - extent).toInt(),
            ceil(agentX + extent).toInt(), ceil(centerY + extent).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (placement == null) return
        canvas.drawRoundRect(background, background.height() / 2f, background.height() / 2f, backgroundPaint)
        moduleIcon?.draw(canvas)
        val animate = mayAnimate()
        for (dot in 0 until 4) {
            glyphPaint.alpha = if (animate) AgentIslandAnimationPolicy.dotAlpha(frame, dot) else 190
            canvas.drawCircle(dotX[dot], dotY[dot], 2f * density, glyphPaint)
        }
        if (captions == 2) {
            canvas.drawText(caption, agentX, 31f * density, captionPaint)
            canvas.drawText(operatingShort, agentX, 43f * density, captionPaint)
        } else if (captions == 1) canvas.drawText(caption, agentX, 43f * density, captionPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        reconcileAnimation()
    }

    override fun onDetachedFromWindow() {
        cancelFrame()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (ready) reconcileAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (ready) reconcileAnimation()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
    }

    private fun mayAnimate(): Boolean = AgentIslandAnimationPolicy.shouldAnimate(requestedAnimation && running,
        isAttachedToWindow, windowVisibility == VISIBLE && visibility == VISIBLE && isShown,
        ValueAnimator.areAnimatorsEnabled(), closed)

    private fun reconcileAnimation() {
        if (mayAnimate()) scheduleFrame() else { cancelFrame(); invalidateGlyph() }
    }

    private fun scheduleFrame() {
        if (frameScheduled || !mayAnimate()) return
        frameScheduled = frameDriver.request(frameTick)
    }

    private fun cancelFrame() {
        frameDriver.remove(frameTick)
        frameScheduled = false
        lastAdvanceNanos = 0L
    }

    @Suppress("DEPRECATION")
    private fun invalidateGlyph() {
        invalidate(glyphBounds.left, glyphBounds.top, glyphBounds.right, glyphBounds.bottom)
    }
}
