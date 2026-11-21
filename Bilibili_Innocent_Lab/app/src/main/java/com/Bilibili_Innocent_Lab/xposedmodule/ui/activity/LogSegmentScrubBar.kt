package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.lumen.coacervation.engine.motion.LumenAnimator

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.ui.widget.TouchGlowRenderer
import kotlin.math.abs
import kotlin.math.roundToInt

/** 触点高光的基础 alpha：与底栏逐字一致。 */
private const val SCRUB_GLOW_BASE_ALPHA = 72

/**
 * 日志详细度档位选择器:与底部导航栏([ModernNavigationBar])共用同一套
 * 手势判定、触点高光与弹簧回弹(ModernNavigation*)。
 *
 * - 轻点档位:等价于点击切换(落点档位即为提交档位)。
 * - 按住拖动:滑块跟手移动、文字颜色随位置连续插值,松手弹簧吸附到最近档位。
 * - 按住不动:触点径向高光 + 滑块按压缩放,松手回弹,与底栏观感一致。
 *
 * 触摸由本控件整体接管(pill 不再各自消费事件),因此宿主要在其容器上
 * 打 [com.Bilibili_Innocent_Lab.xposedmodule.ui.interaction.ElasticInteractionController.EXCLUDED_TAG],
 * 避免与全局长按弹性手势冲突(底栏 dock 同款处理)。
 */
class LogSegmentScrubBar(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    private var count = 0
        set(value) { field = value.coerceIn(0, ModernNavigationMotion.MAX_ITEMS) }
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val maximumTravel = dp(ModernNavigationMotion.MAX_TRAVEL_DP)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var colors = ModernNavigationColors(0, 0, 0)
    private var tintSteps = IntArray(33)
    private var onSelect: ((Int) -> Unit)? = null
    private val thumb = View(context)
    private val glow = GlowView(context)
    private val pills = ArrayList<TextView>(ModernNavigationMotion.MAX_ITEMS)
    private val renderedTint = IntArray(ModernNavigationMotion.MAX_ITEMS) { -1 }
    private val boldTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val plainTypeface = Typeface.DEFAULT
    private var renderedNearest = -1
    private val gesture = ModernNavigationGesture()
    private var selected = 0
    private var pagerPosition = 0f
    private var displayedPosition = 0f
    private var touchActive = false
    private var ignorePointers = false
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
    private val rtl: Boolean get() = layoutDirection == LAYOUT_DIRECTION_RTL
    private val slotWidth: Float get() = if (count > 0) width.toFloat() / count else 0f

    init {
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * 注入标签文案、配色、滑块/轨道背景与提交回调。须在加入布局前调用一次;
     * [selectedIndex] 为初始档位(0 = 精简,1 = 完整),不带动画直接对齐。
     */
    internal fun configure(
        labels: List<CharSequence>,
        colors: ModernNavigationColors,
        thumbBackground: Drawable,
        trackBackground: Drawable,
        selectedIndex: Int,
        onSelect: (Int) -> Unit
    ) {
        count = labels.size
        this.colors = colors
        this.onSelect = onSelect
        tintSteps = IntArray(33) {
            ColorUtils.blendARGB(colors.text, colors.selectedText, it / 32f)
        }
        glow.updateColors(colors)
        background = trackBackground
        thumb.background = thumbBackground
        thumb.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        removeAllViews()
        addView(thumb)
        addView(glow)
        pills.clear()
        repeat(count) { index ->
            val pill = PillView(context, index).apply {
                gravity = Gravity.CENTER
                textSize = 14f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(paddingLeft, dp(12f).roundToInt(), paddingRight, dp(12f).roundToInt())
                text = labels[index]
                isClickable = true
                isFocusable = true
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                setOnFocusChangeListener { _, _ -> glow.invalidate() }
                setOnClickListener {
                    if (this@LogSegmentScrubBar.isEnabled && index != selected) submit(index)
                }
                setOnKeyListener { _, code, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) false
                    else {
                        val delta = when (code) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> if (rtl) 1 else -1
                            KeyEvent.KEYCODE_DPAD_RIGHT -> if (rtl) -1 else 1
                            else -> 0
                        }
                        if (delta != 0 && index + delta in 0 until count) {
                            pills[index + delta].requestFocus()
                        } else false
                    }
                }
            }
            pills += pill
            addView(pill)
        }
        selected = selectedIndex.coerceIn(0, count - 1)
        pagerPosition = selected.toFloat()
        displayedPosition = pagerPosition
        applyVisuals()
    }

    private fun submit(index: Int) {
        onSelect?.invoke(index)
        selected = index
        pagerPosition = selected.toFloat()
        for (i in 0 until count) pills.getOrNull(i)?.isSelected = i == selected
        if (!touchActive) reboundTo(pagerPosition)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (count == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val measuredW = resolveSize(dp(200f).roundToInt(), widthMeasureSpec)
        val available = measuredW.toFloat()
        var naturalHeight = 0
        for (i in 0 until count) {
            val pillWidth = ((i + 1) * available / count).roundToInt() - (i * available / count).roundToInt()
            pills[i].measure(
                MeasureSpec.makeMeasureSpec(pillWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            naturalHeight = maxOf(naturalHeight, pills[i].measuredHeight)
        }
        val measuredH = resolveSize(naturalHeight, heightMeasureSpec)
        setMeasuredDimension(measuredW, measuredH)
        for (i in 0 until count) {
            val pillWidth = ((i + 1) * available / count).roundToInt() - (i * available / count).roundToInt()
            pills[i].measure(
                MeasureSpec.makeMeasureSpec(pillWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(measuredH, MeasureSpec.EXACTLY)
            )
        }
        thumb.measure(
            MeasureSpec.makeMeasureSpec((available / count).roundToInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredH, MeasureSpec.EXACTLY)
        )
        glow.measure(
            MeasureSpec.makeMeasureSpec(measuredW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredH, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (count == 0) return
        for (i in 0 until count) {
            val physical = if (rtl) count - 1 - i else i
            val x = (physical * slotWidth).roundToInt()
            pills[i].layout(x, 0, x + pills[i].measuredWidth, pills[i].measuredHeight)
        }
        thumb.layout(0, 0, thumb.measuredWidth, thumb.measuredHeight)
        glow.layout(0, 0, width, height)
        applyVisuals()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) resetInteraction()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = count > 0

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (count == 0 || !isEnabled || width <= 0) return false
        if (event.pointerCount > 1 || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            ignorePointers = true
            cancelTouch()
            return true
        }
        if (ignorePointers && event.actionMasked != MotionEvent.ACTION_DOWN) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                ignorePointers = false
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ignorePointers = false
                touchActive = true
                pointerId = event.getPointerId(0)
                downRawX = event.rawX; downRawY = event.rawY
                downLocalX = event.x; downLocalY = event.y
                // A bounded rebound may have an unclamped spring value; grab the frame actually shown.
                initialIndicator = displayedPosition
                initialOffsetX = translationX; initialOffsetY = translationY
                offsetX = initialOffsetX; offsetY = initialOffsetY
                lastMoveTime = event.eventTime
                stopRebound()
                val selectedLeft = ModernNavigationMotion.physicalSlot(displayedPosition, count, rtl) * slotWidth
                val index = ModernNavigationMotion.indexAt(event.x, width.toFloat(), 0f, count, rtl)
                gesture.begin(index, index >= 0 && event.x >= selectedLeft && event.x <= selectedLeft + slotWidth)
                glowX = event.x; glowY = event.y
                animatePress(1f)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchActive || event.getPointerId(0) != pointerId) { cancelTouch(); return true }
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                gesture.move(dx, dy, slop)
                if (gesture.intent == ModernNavigationIntent.SCRUB) {
                    displayedPosition = ModernNavigationMotion.scrubPosition(initialIndicator, dx, slotWidth, count, rtl)
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
                val releaseIndex = if (localX in 0f..width.toFloat() && localY in 0f..height.toFloat()) {
                    ModernNavigationMotion.indexAt(localX, width.toFloat(), 0f, count, rtl)
                } else -1
                val target = gesture.finish(false, displayedPosition, releaseIndex, count)
                if (event.eventTime - lastMoveTime > 100L) { offsetVelocityX = 0f; offsetVelocityY = 0f }
                if (target != null && target != selected) {
                    onSelect?.invoke(target)
                    selected = target
                    pagerPosition = selected.toFloat()
                    for (i in 0 until count) pills.getOrNull(i)?.isSelected = i == selected
                }
                // 轻点当前档位不重复提交,仅保留按压回弹反馈。
                touchActive = false
                pointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
                animatePress(0f)
                reboundTo(pagerPosition)
            }
            MotionEvent.ACTION_CANCEL -> cancelTouch()
        }
        return true
    }

    private fun cancelTouch() {
        gesture.cancel()
        touchActive = false
        pointerId = MotionEvent.INVALID_POINTER_ID
        offsetVelocityX = 0f; offsetVelocityY = 0f
        parent?.requestDisallowInterceptTouchEvent(false)
        animatePress(0f)
        reboundTo(pagerPosition)
    }

    private fun animatePress(target: Float, after: (() -> Unit)? = null) {
        pressGeneration++
        pressAnimator?.cancel(); pressAnimator = null
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
                    if (indicatorSettling) {
                        displayedPosition = ModernNavigationMotion.position(indicator.value(seconds), count)
                    }
                    applyVisuals()
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (token != reboundGeneration) return
                    reboundAnimator = null
                    offsetX = 0f; offsetY = 0f; offsetVelocityX = 0f; offsetVelocityY = 0f
                    if (indicatorSettling) {
                        displayedPosition = ModernNavigationMotion.position(target, count)
                    }
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

    private fun applyVisuals() {
        if (count == 0) return
        val travel = ModernNavigationMotion.travelClampScale(offsetX, offsetY, maximumTravel)
        translationX = offsetX * travel
        translationY = offsetY * travel
        val lensX = ModernNavigationMotion.physicalSlot(displayedPosition, count, rtl) * slotWidth
        thumb.translationX = lensX
        thumb.scaleX = ModernNavigationMotion.lensScaleX(press)
        thumb.scaleY = ModernNavigationMotion.lensScaleY(press)
        val nearest = displayedPosition.roundToInt().coerceIn(0, count - 1)
        for (i in 0 until count) {
            val step = ((1f - abs(displayedPosition - i)).coerceIn(0f, 1f) * 32f).roundToInt()
            if (renderedTint[i] != step && i < pills.size) {
            renderedTint[i] = step
            pills[i].setTextColor(tintSteps[step])
            }
        }
        if (nearest != renderedNearest) {
            renderedNearest = nearest
            for (i in 0 until count) {
                pills.getOrNull(i)?.typeface = if (i == nearest) boldTypeface else plainTypeface
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
            viewShiftX = translationX - initialOffsetX,
            viewShiftY = translationY - initialOffsetY
        )
    }

    private fun resetInteraction() {
        gesture.cancel()
        touchActive = false; ignorePointers = false
        pointerId = MotionEvent.INVALID_POINTER_ID
        pressGeneration++; pressAnimator?.cancel(); pressAnimator = null
        stopRebound()
        press = 0f; pressVelocity = 0f
        offsetX = 0f; offsetY = 0f; offsetVelocityX = 0f; offsetVelocityY = 0f
        displayedPosition = pagerPosition
        parent?.requestDisallowInterceptTouchEvent(false)
        applyVisuals()
        // press 已归零 => 本帧起光晕不可见，此刻 reset 不构成可见跳变。
        glow.resetGestureState()
    }

    override fun onDetachedFromWindow() {
        resetInteraction()
        super.onDetachedFromWindow()
    }

    /** Pill 只承载文字与无障碍语义;按压/拖动反馈由本控件统一绘制。 */
    private inner class PillView(context: Context, private val index: Int) : AppCompatTextView(context) {
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.Button"
            info.isSelected = selected == index
        }

        override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_SPACE && !event.isCanceled && isEnabled && index != selected) {
                performClick()
                return true
            }
            return super.onKeyUp(keyCode, event)
        }
    }

    /**
     * 触点径向高光。与底栏 `ModernNavigationBar.GlowView` **同一个实现**（共用
     * [AdaptiveGlowPolicy] 几何与 [TouchGlowRenderer] 绘制），不再是复制品。
     * 颜色在 configure 时才可知，因此 renderer 延后到 [updateColors] 创建。
     */
    private inner class GlowView(context: Context) : View(context) {
        private val radius = dp(48f)
        private val config = GlowConfig.create(
            density = density,
            maxTravelPx = maximumTravel,
            travelEpsPx = dp(GlowConfig.TRAVEL_EPS_DP),
            velocityRefPxPerSec = dp(GlowConfig.VELOCITY_REF_DP_PER_SEC),
            edgeBandPx = dp(GlowConfig.EDGE_BAND_DP)
        )
        private val frame = GlowFrame()
        private val state = GlowState()
        private var renderer: TouchGlowRenderer? = null
        private var lastUpdateNanos = 0L
        private var lastOffsetX = 0f
        private var lastOffsetY = 0f
        private val screenLoc = IntArray(2)
        private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val outline = Outline()
        private val outlineRect = Rect()
        private val clip = Path()
        private var clipWidth = 0
        private var clipHeight = 0
        private var clipCorner = Float.NaN

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        /** 轨道背景声明的圆角；未声明（空 outline / RADIUS_UNDEFINED）时按胶囊（h/2）。 */
        private fun trackCorner(): Float {
            val track = this@LogSegmentScrubBar.background
            if (track != null) {
                outline.setEmpty()
                track.getOutline(outline)
                if (outline.getRect(outlineRect) && outline.radius.isFinite() && outline.radius >= 0f) {
                    return outline.radius.coerceAtMost(minOf(width, height) * .5f)
                }
            }
            return height * .5f
        }

        private fun clipPath(corner: Float): Path {
            if (clipWidth != width || clipHeight != height || clipCorner != corner) {
                clipWidth = width; clipHeight = height; clipCorner = corner
                clip.rewind()
                clip.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), corner, corner, Path.Direction.CW)
            }
            return clip
        }

        /** configure 时刷新配色；shader 依赖的 highlight 色在构造期尚不可知。仅配置期调用。 */
        fun updateColors(colors: ModernNavigationColors) {
            renderer = TouchGlowRenderer(colors.highlight, radius)
            focusPaint.apply {
                style = Paint.Style.STROKE
                strokeWidth = dp(1.5f)
                color = ColorUtils.setAlphaComponent(colors.selectedText, 160)
            }
        }

        /** 每个 press/rebound 动画帧与每个 MOVE 调用一次；速度取渲染位移的差分估计。 */
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
            // 触点换算到当前系：glowX/Y 是按下时刻坐标系，bar 自身已平移 viewShift——
            // 与 TouchHighlight 同一修正，避免视图平移被重复计入越界量与 room。
            val touchX = centerX - viewShiftX
            val touchY = centerY - viewShiftY
            frame.centerX = touchX
            frame.centerY = touchY
            frame.boundsWidth = barWidth.toFloat()
            frame.boundsHeight = barHeight.toFloat()
            frame.cornerRadius = trackCorner()
            // 可触达空间：scrub 条贴近屏幕边缘时触点走不满 pileRefPx——
            // 剩余空间交给策略层压缩满额行程，贴屏方向也能堆出完整"集中"。
            getLocationOnScreen(screenLoc)
            val (windowW, windowH) = hostWindowSizePx()
            frame.pileRoomPx = reachablePileRoomPx(
                screenLoc[0], screenLoc[1],
                screenLoc[0] + width, screenLoc[1] + height,
                windowW, windowH,
                touchX, touchY, barWidth.toFloat(), barHeight.toFloat()
            )
            state.update(frame, dt, radius, SCRUB_GLOW_BASE_ALPHA, config)
            invalidate()
        }

        /** 只在不可见边界调用（resetInteraction）。 */
        fun resetGestureState() {
            lastUpdateNanos = 0L
            lastOffsetX = 0f
            lastOffsetY = 0f
            state.reset()
        }

        override fun onDraw(canvas: Canvas) {
            val glowRenderer = renderer
            if (glowRenderer != null && state.shape.visible && width > 0 && height > 0) {
                // 越界堆积时光晕钉在轨道边缘，轮廓外的半边由这里裁掉。
                val save = canvas.save()
                canvas.clipPath(clipPath(trackCorner()))
                glowRenderer.draw(canvas, state.shape)
                canvas.restoreToCount(save)
            }
            for (i in 0 until pills.size) {
                val pill = pills[i]
                if (pill.hasFocus()) {
                    canvas.drawRoundRect(
                        pill.left + dp(2f), pill.top + dp(2f),
                        pill.right - dp(2f), pill.bottom - dp(2f), dp(10f), dp(10f), focusPaint
                    )
                }
            }
        }
    }
}
