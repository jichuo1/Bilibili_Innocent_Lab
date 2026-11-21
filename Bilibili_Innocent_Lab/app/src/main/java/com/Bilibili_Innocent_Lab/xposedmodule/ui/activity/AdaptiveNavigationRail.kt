package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowLegibilityPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.widget.TouchGlowRenderer
import com.lumen.coacervation.engine.motion.LumenAnimator
import kotlin.math.abs
import kotlin.math.roundToInt

private const val RAIL_GLOW_BASE_ALPHA = 72
private const val RAIL_LABEL_HALO_DP = 3f
private const val RAIL_LABEL_HALO_ALPHA = 0.55f

/**
 * 大屏/展开态悬浮左侧胶囊 Navigation Rail，与 [ModernNavigationBar] 共用 LCE 弹簧、高光及可读性策略。
 */
@SuppressLint("ViewConstructor")
internal class AdaptiveNavigationRail(
    context: Context,
    titles: List<CharSequence>,
    icons: IntArray,
    private val colors: ModernNavigationColors,
    backgroundFactory: (ModernNavigationSurface, Float) -> Drawable,
    private val onSelect: (Int) -> Unit,
    private val onUserInteraction: () -> Unit,
    private val onVisualMovement: () -> Unit
) : FrameLayout(context) {
    private val count = titles.size.also { require(it in 1..ModernNavigationMotion.MAX_ITEMS && icons.size == it) }
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val inset = dp(ModernNavigationMotion.INSET_DP.toFloat())
    private val maximumTravel = dp(ModernNavigationMotion.MAX_TRAVEL_DP)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val tintSteps = Array(33) {
        ColorStateList.valueOf(ColorUtils.blendARGB(colors.text, colors.selectedText, it / 32f))
    }
    private val renderedTint = IntArray(count) { -1 }
    private var legibilityStep = 0
    private val iconViews = ArrayList<ImageView>(count)
    private val labelViews = ArrayList<TextView>(count)
    private val items = ArrayList<RailItem>(count)
    private val selection = View(context).apply {
        background = backgroundFactory(ModernNavigationSurface.SELECTION, 24f)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val glow = GlowView(context)
    private val gesture = ModernNavigationGesture()
    private var selected = 0
    private var pagerPosition = 0f
    private var displayedPosition = 0f
    private var pendingScrubPage: Int? = null
    private var touchActive = false
    private var ignorePointers = false
    private var interactionNotified = false
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var downRawX = 0f
    private var downRawY = 0f
    private var downLocalX = 0f
    private var downLocalY = 0f
    private var initialIndicator = 0f
    private var initialOffsetX = 0f
    private var initialOffsetY = 0f
    private var lastMoveTime = 0L
    private var press = 0f
    private var pressVelocity = 0f
    private var offsetX = 0f
    private var offsetY = 0f
    private var offsetVelocityX = 0f
    private var offsetVelocityY = 0f
    private var glowX = 0f
    private var glowY = 0f
    private var pressAnimator: ValueAnimator? = null
    private var reboundAnimator: ValueAnimator? = null
    private var pressGeneration = 0L
    private var reboundGeneration = 0L
    private var indicatorSettling = false
    private var disposed = false

    private val contentHeight: Float get() = (height - inset * 2f).coerceAtLeast(0f)
    private val slotHeight: Float get() = contentHeight / count

    init {
        background = backgroundFactory(ModernNavigationSurface.BAR, 32f)
        clipChildren = false
        clipToPadding = false
        clipToOutline = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(32f))
            }
        }
        addView(selection)
        addView(glow)
        repeat(count) { index ->
            val item = RailItem(context, index).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = titles[index]
                setPadding(dp(4f).roundToInt(), dp(4f).roundToInt(), dp(4f).roundToInt(), dp(4f).roundToInt())
                setOnClickListener {
                    if (!disposed && this@AdaptiveNavigationRail.isEnabled) {
                        notifyUserInteraction()
                        pendingScrubPage = null
                        onSelect(index)
                        if (!touchActive) {
                            glowX = this@AdaptiveNavigationRail.width / 2f
                            glowY = top + height / 2f
                            animatePress(.8f) { animatePress(0f) }
                        }
                    }
                }
                setOnFocusChangeListener { _, _ -> glow.invalidate() }
                setOnKeyListener { _, code, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) false
                    else {
                        val delta = when (code) {
                            KeyEvent.KEYCODE_DPAD_UP -> -1
                            KeyEvent.KEYCODE_DPAD_DOWN -> 1
                            else -> 0
                        }
                        if (delta != 0 && index + delta in 0 until count) items[index + delta].requestFocus()
                        else false
                    }
                }
            }
            val icon = ImageView(context).apply {
                setImageResource(icons[index])
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val label = TextView(context).apply {
                text = titles[index]
                textSize = 11f
                gravity = Gravity.CENTER
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            item.addView(icon, LinearLayout.LayoutParams(dp(24f).roundToInt(), dp(24f).roundToInt()))
            item.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3f).roundToInt()
            })
            iconViews += icon
            labelViews += label
            items += item
            addView(item)
        }
        setSelectedPage(0)
        applyVisuals()
    }

    fun setPageProgress(value: Float, notifyPositionChanged: Boolean = true) {
        if (disposed) return
        val previous = pagerPosition
        pagerPosition = ModernNavigationMotion.position(value, count)
        if (touchActive) return
        pendingScrubPage?.let { target ->
            val distance = abs(pagerPosition - target)
            if (distance > .002f && distance <= abs(previous - target) + .01f) return
            pendingScrubPage = null
        }
        indicatorSettling = false
        displayedPosition = pagerPosition
        applyVisuals(notifyPositionChanged)
    }

    fun setSelectedPage(index: Int) {
        if (disposed) return
        selected = index.coerceIn(0, count - 1)
        if (pendingScrubPage != null && pendingScrubPage != selected) {
            pendingScrubPage = null
            indicatorSettling = false
            if (!touchActive) displayedPosition = pagerPosition
        }
        for (i in 0 until count) items.getOrNull(i)?.isSelected = i == selected
        applyVisuals()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredW = resolveSize(dp(AdaptiveWindowLayout.SIDE_RAIL_WIDTH_DP.toFloat()).roundToInt(), widthMeasureSpec)
        val itemWidth = (measuredW - inset * 2f).roundToInt().coerceAtLeast(0)
        var naturalSlotHeight = dp(56f).roundToInt()
        for (i in 0 until count) {
            items[i].measure(
                MeasureSpec.makeMeasureSpec(itemWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            naturalSlotHeight = maxOf(naturalSlotHeight, items[i].measuredHeight + dp(4f).roundToInt())
        }
        val desiredH = (naturalSlotHeight * count + inset * 2f).roundToInt()
        val measuredH = resolveSize(desiredH, heightMeasureSpec)
        setMeasuredDimension(measuredW, measuredH)
        val available = (measuredH - inset * 2f).coerceAtLeast(0f)
        for (i in 0 until count) {
            val h = ((i + 1) * available / count).roundToInt() - (i * available / count).roundToInt()
            items[i].measure(
                MeasureSpec.makeMeasureSpec(itemWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h.coerceAtLeast(0), MeasureSpec.EXACTLY)
            )
        }
        selection.measure(
            MeasureSpec.makeMeasureSpec(itemWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((available / count).roundToInt().coerceAtLeast(0), MeasureSpec.EXACTLY)
        )
        glow.measure(
            MeasureSpec.makeMeasureSpec(measuredW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredH, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val leftInset = inset.roundToInt()
        for (i in 0 until count) {
            val y = (inset + i * slotHeight).roundToInt()
            items[i].layout(leftInset, y, leftInset + items[i].measuredWidth, y + items[i].measuredHeight)
        }
        selection.layout(leftInset, leftInset, leftInset + selection.measuredWidth, leftInset + selection.measuredHeight)
        glow.layout(0, 0, width, height)
        applyVisuals()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) resetInteraction()
        invalidateOutline()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (disposed || !isEnabled || height <= 0) return false
        if (event.pointerCount > 1 || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            ignorePointers = true
            cancelTouch()
            return true
        }
        if (ignorePointers && event.actionMasked != MotionEvent.ACTION_DOWN) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) ignorePointers = false
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ignorePointers = false
                touchActive = true
                interactionNotified = false
                pointerId = event.getPointerId(0)
                downRawX = event.rawX; downRawY = event.rawY
                downLocalX = event.x; downLocalY = event.y
                initialIndicator = displayedPosition
                initialOffsetX = translationX; initialOffsetY = translationY
                offsetX = initialOffsetX; offsetY = initialOffsetY
                lastMoveTime = event.eventTime
                pendingScrubPage = null
                stopRebound()
                val selectedTop = inset + displayedPosition * slotHeight
                val index = indexAtY(event.y)
                // 把纵向拖动映射为 gesture 识别期望的主轴位移。
                gesture.begin(index, event.y >= selectedTop && event.y <= selectedTop + slotHeight)
                glowX = event.x; glowY = event.y
                animatePress(1f)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchActive || event.getPointerId(0) != pointerId) { cancelTouch(); return true }
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                // 纵向 Rail 中 dy 驱动 scrub，dx 为正交轴。
                if (gesture.move(dy, dx, slop)) notifyUserInteraction()
                if (gesture.intent == ModernNavigationIntent.SCRUB && slotHeight > 0f) {
                    displayedPosition = ModernNavigationMotion.scrubPosition(initialIndicator, dy, slotHeight, count, false)
                }
                val factor = ModernNavigationMotion.displacementScale(dx, dy, maximumTravel, dp(48f))
                val nextX = initialOffsetX + dx * factor
                val nextY = initialOffsetY + dy * factor
                val bounded = ModernNavigationMotion.travelClampScale(nextX, nextY, maximumTravel)
                val elapsed = (event.eventTime - lastMoveTime).coerceAtLeast(1L)
                offsetVelocityX = ((nextX * bounded - offsetX) * 1000f / elapsed).coerceIn(-dp(240f), dp(240f))
                offsetVelocityY = ((nextY * bounded - offsetY) * 1000f / elapsed).coerceIn(-dp(240f), dp(240f))
                offsetX = nextX * bounded; offsetY = nextY * bounded
                lastMoveTime = event.eventTime
                glowX = downLocalX + dx
                glowY = downLocalY + dy
                applyVisuals()
            }
            MotionEvent.ACTION_UP -> {
                if (!touchActive) return true
                val localX = downLocalX + event.rawX - downRawX
                val localY = downLocalY + event.rawY - downRawY
                val releaseIndex = if (localX in 0f..width.toFloat() && localY in 0f..height.toFloat())
                    indexAtY(localY) else -1
                val scrubbed = gesture.intent == ModernNavigationIntent.SCRUB
                val target = gesture.finish(false, displayedPosition, releaseIndex, count)
                if (event.eventTime - lastMoveTime > 100L) { offsetVelocityX = 0f; offsetVelocityY = 0f }
                if (target != null) {
                    if (scrubbed) {
                        notifyUserInteraction()
                        pendingScrubPage = target.takeIf { abs(pagerPosition - it) > .002f }
                        onSelect(target)
                    } else items[target].performClick()
                }
                touchActive = false
                pointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
                animatePress(0f)
                reboundTo(if (scrubbed && target != null) target.toFloat() else pagerPosition)
            }
            MotionEvent.ACTION_CANCEL -> cancelTouch()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun indexAtY(localY: Float): Int =
        if (slotHeight <= 0f) -1 else ((localY - inset) / slotHeight).toInt().coerceIn(0, count - 1)

    private fun notifyUserInteraction() {
        if (!touchActive || !interactionNotified) {
            interactionNotified = true
            onUserInteraction()
        }
    }

    private fun cancelTouch() {
        gesture.cancel()
        touchActive = false
        pointerId = MotionEvent.INVALID_POINTER_ID
        pendingScrubPage = null
        offsetVelocityX = 0f; offsetVelocityY = 0f
        parent?.requestDisallowInterceptTouchEvent(false)
        animatePress(0f)
        reboundTo(pagerPosition)
    }

    private fun animatePress(target: Float, after: (() -> Unit)? = null) {
        pressGeneration++
        pressAnimator?.cancel(); pressAnimator = null
        if (disposed) return
        if (!isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            press = target; pressVelocity = 0f; applyVisuals(); after?.invoke(); return
        }
        val token = pressGeneration
        val spring = ModernNavigationSpring(press, target, pressVelocity)
        val animation = LumenAnimator.ofFloat(0f, ModernNavigationMotion.SPRING_DURATION_MS / 1000f).apply {
            duration = ModernNavigationMotion.SPRING_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (token == pressGeneration) {
                    val seconds = it.animatedFraction * ModernNavigationMotion.SPRING_DURATION_MS / 1000f
                    press = spring.value(seconds).coerceIn(0f, 1f)
                    pressVelocity = spring.velocity(seconds)
                    applyVisuals()
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (token != pressGeneration) return
                    pressAnimator = null; press = target; pressVelocity = 0f
                    applyVisuals(); after?.invoke()
                }
            })
        }
        pressAnimator = animation
        animation.start()
    }

    private fun reboundTo(target: Float) {
        stopRebound()
        if (disposed) return
        if (!isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            offsetX = 0f; offsetY = 0f; offsetVelocityX = 0f; offsetVelocityY = 0f
            displayedPosition = ModernNavigationMotion.position(target, count)
            applyVisuals(); return
        }
        val token = reboundGeneration
        val x = ModernNavigationSpring(offsetX, 0f, offsetVelocityX)
        val y = ModernNavigationSpring(offsetY, 0f, offsetVelocityY)
        val indicator = ModernNavigationSpring(displayedPosition, target)
        indicatorSettling = true
        val animation = LumenAnimator.ofFloat(0f, 1f).apply {
            duration = ModernNavigationMotion.SPRING_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (token == reboundGeneration) {
                    val seconds = it.animatedFraction * ModernNavigationMotion.SPRING_DURATION_MS / 1000f
                    offsetX = x.value(seconds); offsetY = y.value(seconds)
                    offsetVelocityX = x.velocity(seconds); offsetVelocityY = y.velocity(seconds)
                    if (indicatorSettling) displayedPosition = ModernNavigationMotion.position(indicator.value(seconds), count)
                    applyVisuals()
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (token != reboundGeneration) return
                    reboundAnimator = null
                    offsetX = 0f; offsetY = 0f; offsetVelocityX = 0f; offsetVelocityY = 0f
                    if (indicatorSettling) displayedPosition = ModernNavigationMotion.position(target, count)
                    indicatorSettling = false
                    applyVisuals()
                }
            })
        }
        reboundAnimator = animation
        animation.start()
    }

    private fun stopRebound() {
        reboundGeneration++
        reboundAnimator?.cancel(); reboundAnimator = null
        indicatorSettling = false
    }

    private fun applyVisuals(notifyPositionChanged: Boolean = true) {
        if (disposed) return
        var moved = false
        val travel = ModernNavigationMotion.travelClampScale(offsetX, offsetY, maximumTravel)
        val x = offsetX * travel
        val y = offsetY * travel
        if (translationX != x) { translationX = x; moved = true }
        if (translationY != y) { translationY = y; moved = true }
        val lensY = displayedPosition * slotHeight
        // 纵向 Rail 挤压轴与水平 Bar 正交：主轴为 Y，辅助膨大在 X。
        val scaleX = ModernNavigationMotion.lensScaleY(press)
        val scaleY = ModernNavigationMotion.lensScaleX(press)
        if (selection.translationY != lensY) { selection.translationY = lensY; moved = true }
        if (selection.scaleX != scaleX) { selection.scaleX = scaleX; moved = true }
        if (selection.scaleY != scaleY) { selection.scaleY = scaleY; moved = true }
        for (i in 0 until count) {
            val step = ((1f - abs(displayedPosition - i)).coerceIn(0f, 1f) * 32f).roundToInt()
            if (renderedTint[i] != step && i < iconViews.size) {
                renderedTint[i] = step
                iconViews[i].imageTintList = tintSteps[step]
                labelViews[i].setTextColor(tintSteps[step])
            }
        }
        glow.updateGesture(
            press = press,
            offsetX = offsetX,
            offsetY = offsetY,
            centerX = glowX,
            centerY = glowY,
            barWidth = width,
            barHeight = height,
            viewShiftX = x - initialOffsetX,
            viewShiftY = y - initialOffsetY
        )
        if (moved && notifyPositionChanged) onVisualMovement()
    }

    fun setLegibility(boost: Float, haloColor: Int) {
        if (disposed) return
        val step = (boost.coerceIn(0f, 1f) * 32f).roundToInt()
        if (step == legibilityStep) return
        legibilityStep = step
        val amount = step / 32f
        val text = GlowLegibilityPolicy.foreground(colors.text, amount)
        val selectedText = GlowLegibilityPolicy.foreground(
            colors.selectedText, amount, GlowLegibilityPolicy.ACCENT_FOREGROUND_PUSH
        )
        for (i in tintSteps.indices) {
            tintSteps[i] = ColorStateList.valueOf(ColorUtils.blendARGB(text, selectedText, i / 32f))
        }
        renderedTint.fill(-1)
        val halo = ColorUtils.setAlphaComponent(haloColor, (RAIL_LABEL_HALO_ALPHA * amount * 255f).roundToInt())
        for (label in labelViews) {
            if (step == 0) label.setShadowLayer(0f, 0f, 0f, 0) else label.setShadowLayer(dp(RAIL_LABEL_HALO_DP), 0f, 0f, halo)
        }
        applyVisuals(notifyPositionChanged = false)
    }

    private fun resetInteraction() {
        gesture.cancel()
        touchActive = false; ignorePointers = false
        pointerId = MotionEvent.INVALID_POINTER_ID
        pendingScrubPage = null
        pressGeneration++; pressAnimator?.cancel(); pressAnimator = null
        stopRebound()
        press = 0f; pressVelocity = 0f
        offsetX = 0f; offsetY = 0f; offsetVelocityX = 0f; offsetVelocityY = 0f
        displayedPosition = pagerPosition
        parent?.requestDisallowInterceptTouchEvent(false)
        applyVisuals()
        glow.resetGestureState()
    }

    fun dispose() {
        resetInteraction()
        disposed = true
    }

    override fun onDetachedFromWindow() {
        resetInteraction()
        super.onDetachedFromWindow()
    }

    private inner class RailItem(context: Context, private val index: Int) : LinearLayout(context) {
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.Button"
            info.isSelected = selected == index
        }

        override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_SPACE && !event.isCanceled) return performClick()
            return super.onKeyUp(keyCode, event)
        }
    }

    private inner class GlowView(context: Context) : View(context) {
        private val radius = dp(64f)
        private val renderer = TouchGlowRenderer(colors.highlight, radius)
        private val config = GlowConfig.create(
            density = density,
            maxTravelPx = maximumTravel,
            travelEpsPx = dp(GlowConfig.TRAVEL_EPS_DP),
            velocityRefPxPerSec = dp(GlowConfig.VELOCITY_REF_DP_PER_SEC),
            edgeBandPx = dp(GlowConfig.EDGE_BAND_DP),
            continuousEdgePile = true
        )
        private val frame = GlowFrame()
        private val state = GlowState()
        private var lastUpdateNanos = 0L
        private var lastOffsetX = 0f
        private var lastOffsetY = 0f
        private val screenLoc = IntArray(2)
        private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
            color = ColorUtils.setAlphaComponent(colors.selectedText, 160)
        }

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        fun updateGesture(
            press: Float,
            offsetX: Float,
            offsetY: Float,
            centerX: Float,
            centerY: Float,
            barWidth: Int,
            barHeight: Int,
            viewShiftX: Float = 0f,
            viewShiftY: Float = 0f
        ) {
            val now = System.nanoTime()
            val dt = if (lastUpdateNanos == 0L) GlowState.DEFAULT_DT_SECONDS
            else ((now - lastUpdateNanos).coerceAtLeast(0L)) / 1_000_000_000f
            lastUpdateNanos = now
            val elapsed = dt.coerceAtLeast(GlowState.MIN_DT_SECONDS)
            frame.press = press
            frame.offsetX = offsetX
            frame.offsetY = offsetY
            frame.velocityX = (offsetX - lastOffsetX) / elapsed
            frame.velocityY = (offsetY - lastOffsetY) / elapsed
            lastOffsetX = offsetX
            lastOffsetY = offsetY
            val touchX = centerX - viewShiftX
            val touchY = centerY - viewShiftY
            frame.centerX = touchX
            frame.centerY = touchY
            frame.boundsWidth = barWidth.toFloat()
            frame.boundsHeight = barHeight.toFloat()
            frame.cornerRadius = dp(32f)
            getLocationOnScreen(screenLoc)
            val (windowW, windowH) = hostWindowSizePx()
            frame.pileRoomPx = reachablePileRoomPx(
                screenLoc[0], screenLoc[1],
                screenLoc[0] + width, screenLoc[1] + height,
                windowW, windowH,
                touchX, touchY, barWidth.toFloat(), barHeight.toFloat()
            )
            state.update(frame, dt, radius, RAIL_GLOW_BASE_ALPHA, config)
            invalidate()
        }

        fun resetGestureState() {
            lastUpdateNanos = 0L
            lastOffsetX = 0f
            lastOffsetY = 0f
            state.reset()
        }

        override fun onDraw(canvas: Canvas) {
            renderer.draw(canvas, state.shape)
            for (i in 0 until items.size) {
                val item = items[i]
                if (item.hasFocus()) canvas.drawRoundRect(
                    item.left + dp(3f), item.top + dp(3f),
                    item.right - dp(3f), item.bottom - dp(3f), dp(18f), dp(18f), focusPaint
                )
            }
        }
    }
}
