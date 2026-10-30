package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeTheme
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostDockLayer
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostGlowView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSpringAxis
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceScope
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceStyle

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.HorizontalScrollView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookLog
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** 保留宿主顶栏的父子关系、尺寸和分类位置，只裁剪外壳并分流当前触摸序列。 */
@SuppressLint("ClickableViewAccessibility")
internal class HostTopIslandBinding private constructor(
    private val dock: ViewGroup,
    private val tabs: ViewGroup?,
    private val density: Float,
    private val glow: HostGlowView?,
    private val backdrop: HostSurfaceScope?,
    glyphColor: Int,
    private val pageActions: HostTopIslandPageActions
) {
    private var progress = 0f
    private var collapsed = false
    private var animating = false
    private var lastFrameNanos = 0L
    private val spring = HostSpringAxis()
    private val childAlphas = WeakHashMap<View, Float>()
    private val location = IntArray(2)
    private var gesture: HostTopIslandGesture? = null
    private var downX = 0f
    private var downY = 0f
    private var bubbleTouch = false
    private var compactGesture: HostTopIslandCompactGesture? = null
    private var nativeScroll: View? = null
    private var scrollOrigin = 0
    private var scrollMoved = false
    private var tabsCanScrollLeft = false
    private var tabsCanScrollRight = false
    private var atTop = true
    private var lastPageCheck = 0L
    private var consumed = false
    private val feedback = HostTopIslandFeedback()
    private var feedbackQueued = false
    private var feedbackFrameNanos = 0L
    private val touchPadding = 8f * density
    private val touchSlop = ViewConfiguration.get(dock.context).scaledTouchSlop.toFloat()
    private var observer: ViewTreeObserver? = null
    private val shell = object : View(dock.context), HostDockLayer {}
    private val preDraw = ViewTreeObserver.OnPreDrawListener { HostThreadGuard.call("host.top.island.predraw", true) { sync(); true } }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = glyphColor
        style = Paint.Style.STROKE
        strokeWidth = 1.55f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = glyphColor }
    private val spinnerBounds = RectF(-7.5f * density, -7.5f * density, 7.5f * density, 7.5f * density)
    private val feedbackFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) = HostThreadGuard.run("host.top.island.feedback") {
            feedbackQueued = false
            if (!input.isAttachedToWindow || !collapsed) { feedbackFrameNanos = 0L; return@run }
            val previous = feedbackFrameNanos
            feedbackFrameNanos = frameTimeNanos
            feedback.advance(if (previous == 0L) 1f / 60f else (frameTimeNanos - previous) / 1e9f)
            updatePageAction()
            input.invalidate()
            ensureFeedbackFrames()
        }
    }
    private val arrowPath = Path().apply {
        moveTo(0f, 8f * density)
        lineTo(0f, -8f * density)
        moveTo(-6f * density, -2f * density)
        lineTo(0f, -8f * density)
        lineTo(6f * density, -2f * density)
    }
    private val refreshPath = Path().apply {
        arcTo(-7.5f * density, -7.5f * density, 7.5f * density, 7.5f * density, 45f, 290f, true)
        moveTo(7.5f * density, -8f * density)
        lineTo(7.5f * density, -2f * density)
        lineTo(1.5f * density, -2f * density)
    }
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val continued = HostThreadGuard.call("host.top.island.frame", false) {
                if (!animating) return
                if (!input.isAttachedToWindow) {
                    settle()
                    return
                }
                val previous = lastFrameNanos
                lastFrameNanos = frameTimeNanos
                val seconds = if (previous == 0L) 1f / 60f else (frameTimeNanos - previous) / 1e9f
                val target = if (collapsed) 1f else 0f
                spring.advance(seconds.coerceAtMost(HostTopIslandMotionSpec.MAX_FRAME_SECONDS), target,
                    if (collapsed) HostTopIslandMotionSpec.COLLAPSE_STIFFNESS else HostTopIslandMotionSpec.EXPAND_STIFFNESS,
                    if (collapsed) HostTopIslandMotionSpec.DAMPING_RATIO else HostTopIslandMotionSpec.EXPAND_DAMPING_RATIO)
                applyProgress(spring.value)
                val travel = (dock.width - dock.height).coerceAtLeast(1).toFloat()
                if (spring.atRest(target, .5f / travel)) settle()
                else Choreographer.getInstance().postFrameCallback(this)
                true
            }
            // 失败的一帧不再续约；animating 留着的话，之后的 animateTo 以为动画还在跑，岛就卡在半途。
            if (!continued) animating = false
        }
    }

    private val input = object : View(dock.context), HostDockLayer {
        override fun onDraw(canvas: Canvas) {
            val shellSave = canvas.save()
            canvas.scale(feedback.scale, feedback.scale, dock.width / 2f, dock.height / 2f + touchPadding)
            canvas.translate(0f, touchPadding)
            if (collapsed && feedback.pressure > 0f) {
                pressPaint.alpha = (24f * feedback.pressure).roundToInt()
                canvas.drawCircle(dock.width / 2f, dock.height / 2f, dock.height / 2f, pressPaint)
            }
            val alpha = HostTopIslandMotionSpec.glyphAlpha(progress)
            if (alpha <= 0f) { canvas.restoreToCount(shellSave); return }
            glyphPaint.alpha = (220f * alpha).roundToInt()
            val save = canvas.save()
            canvas.translate(dock.width / 2f, dock.height / 2f + 1.5f * density * (1f - alpha))
            val scale = .9f + .1f * alpha
            canvas.scale(scale, scale)
            if (feedback.refreshing) {
                canvas.rotate((SystemClock.uptimeMillis() % 1000L) * .36f)
                canvas.drawArc(spinnerBounds, -90f, 250f, false, glyphPaint)
            } else {
                canvas.translate(0f, feedback.arrowOffset(SystemClock.uptimeMillis()) * density)
                canvas.drawPath(if (atTop && !feedback.showTopArrow(SystemClock.uptimeMillis())) refreshPath else arrowPath, glyphPaint)
            }
            canvas.restoreToCount(save)
            canvas.restoreToCount(shellSave)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val copy = MotionEvent.obtain(event)
            return try { copy.offsetLocation(0f, -touchPadding); handleTouch(copy) } finally { copy.recycle() }
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            if (!collapsed) return
            info.className = "android.widget.Button"
            info.isClickable = !feedback.busy
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(EXPAND_ACTION, "展开顶栏"))
            val inset = horizontalInset().roundToInt()
            @Suppress("DEPRECATION")
            info.setBoundsInParent(Rect(x.roundToInt() + inset, (y + touchPadding).roundToInt(),
                x.roundToInt() + width - inset, (y + touchPadding + dock.height).roundToInt()))
            getLocationOnScreen(location)
            info.setBoundsInScreen(Rect(location[0] + inset, location[1] + touchPadding.roundToInt(),
                location[0] + width - inset, location[1] + touchPadding.roundToInt() + dock.height))
        }

        override fun performClick(): Boolean {
            super.performClick()
            if (collapsed && HostTopIslandMotionSpec.glyphAlpha(progress) > .5f && !feedback.busy) {
                val action = pageActions.click()
                when (action) {
                    HostTopIslandPageActions.Action.TOP -> feedback.clicked(HostTopIslandFeedback.Operation.TOP, SystemClock.uptimeMillis())
                    HostTopIslandPageActions.Action.REFRESH -> feedback.clicked(HostTopIslandFeedback.Operation.REFRESH, SystemClock.uptimeMillis())
                    else -> Unit
                }
                if (action == HostTopIslandPageActions.Action.TOP || action == HostTopIslandPageActions.Action.REFRESH)
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                updatePageAction(force = true)
                ensureFeedbackFrames()
                invalidate()
            }
            return true
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            if (action == EXPAND_ACTION && collapsed) { animateTo(false); return true }
            return super.performAccessibilityAction(action, arguments)
        }
    }

    companion object {
        private const val EXPAND_ACTION = 0x01020001
        fun attach(
            dock: ViewGroup,
            tabs: ViewGroup?,
            density: Float,
            glow: HostGlowView?,
            backdrop: HostSurfaceScope?,
            glyphColor: Int,
            pageActions: HostTopIslandPageActions
        ): HostTopIslandBinding? {
            val parent = dock.parent as? ViewGroup ?: return null
            return runCatching {
                HostTopIslandBinding(dock, tabs, density, glow, backdrop, glyphColor, pageActions).also { it.install(parent) }
            }.onFailure { ModernHookLog.info("[BIL] 顶栏收起手势装配失败: $it") }.getOrNull()
        }
    }

    private fun install(parent: ViewGroup) {
        // 每个引擎 Drawable 由一个真实 View 持有；收岛外壳不代画 dock 的背景。
        parent.addView(shell)
        val shellParams = shell.layoutParams
        shellParams.width = dock.width.coerceAtLeast(1)
        shellParams.height = dock.height.coerceAtLeast(1)
        HostTopBarFxController.setConstraintInt(shellParams, "topToTop", 0)
        HostTopBarFxController.setConstraintInt(shellParams, "startToStart", 0)
        shell.layoutParams = shellParams
        shell.translationZ = 11f * density
        shell.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        shell.visibility = View.INVISIBLE
        backdrop?.floating(shell, HostChromeTheme(dock.context).read().dark,
            (dock.height / density / 2f).coerceAtLeast(22f))
        // 让宿主生成 LayoutParams，避免模块和宿主的 ConstraintLayout ClassLoader 不同。
        parent.addView(input)
        val lp = input.layoutParams
        lp.width = dock.width.coerceAtLeast(1)
        lp.height = (dock.height + touchPadding * 2).roundToInt().coerceAtLeast(1)
        HostTopBarFxController.setConstraintInt(lp, "topToTop", 0)
        HostTopBarFxController.setConstraintInt(lp, "startToStart", 0)
        input.layoutParams = lp
        input.translationZ = 12f * density
        input.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        input.contentDescription = "刷新"
        dock.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val inset = horizontalInset().roundToInt()
                val vertical = verticalInset().roundToInt()
                val radius = minOf(view.width - inset * 2, view.height - vertical * 2) / 2f
                outline.setRoundRect(inset, vertical, view.width - inset, view.height - vertical, radius)
            }
        }
        input.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = observe()
            override fun onViewDetachedFromWindow(v: View) {
                observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
                observer = null
                settle()
                gesture = null
                consumed = false
                feedback.reset()
                Choreographer.getInstance().removeFrameCallback(feedbackFrame)
                feedbackQueued = false
                feedbackFrameNanos = 0L
                glow?.resetGestureState()
            }
        })
        observe()
        sync()
    }

    private fun observe() {
        if (observer != null) return
        input.viewTreeObserver.takeIf { it.isAlive }?.let {
            it.addOnPreDrawListener(preDraw)
            observer = it
        }
    }

    /** 宿主 AppBar 位移、横竖屏和主题更新后，输入层与外壳始终同步。 */
    fun sync() {
        val lp = input.layoutParams
        val inputHeight = (dock.height + touchPadding * 2).roundToInt()
        val geometryChanged = lp.width != dock.width || lp.height != inputHeight
        if (dock.width > 0 && dock.height > 0 && geometryChanged) {
            lp.width = dock.width
            lp.height = inputHeight
            input.layoutParams = lp
        }
        input.translationX = dock.x - input.left
        input.translationY = dock.y - input.top - touchPadding
        input.visibility = if (dock.visibility == View.GONE) View.GONE else View.VISIBLE
        val shellParams = shell.layoutParams
        if (shellParams.width != dock.width || shellParams.height != dock.height) {
            shellParams.width = dock.width
            shellParams.height = dock.height
            shell.layoutParams = shellParams
        }
        val shellX = dock.x - shell.left
        val shellY = dock.y - shell.top
        val moved = shell.translationX != shellX || shell.translationY != shellY || shell.scaleX != feedback.scale
        shell.translationX = shellX
        shell.translationY = shellY
        shell.scaleX = feedback.scale
        shell.scaleY = feedback.scale
        shell.visibility = if (collapsed && !animating && dock.visibility != View.GONE) View.VISIBLE else View.INVISIBLE
        backdrop?.shape(dock, horizontalInset(), verticalInset())
        backdrop?.shape(shell, horizontalInset(), verticalInset())
        if (animating || progress > 0f) fadeChildren()
        if (collapsed && !animating) dock.visibility = View.INVISIBLE
        if (geometryChanged) {
            dock.invalidateOutline()
            input.invalidate()
        }
        if (moved) backdrop?.onVisualMovement()
        if (collapsed) updatePageAction()
    }

    fun recolor(color: Int) {
        if (glyphPaint.color == color) return
        glyphPaint.color = color
        pressPaint.color = color
        input.invalidate()
    }

    fun updateMaterial(colors: HostChromeColors) {
        recolor(colors.accent)
        backdrop?.floating(shell, colors.dark, (dock.height / density / 2f).coerceAtLeast(22f))
    }

    private fun updatePageAction(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastPageCheck < 80L) return
        lastPageCheck = now
        val state = pageActions.state()
        val previousLoading = feedback.refreshing
        val previousOperation = feedback.operation
        feedback.updateState(state.atTop, state.refreshing)
        input.contentDescription = when {
            feedback.refreshing -> "正在刷新"
            feedback.operation == HostTopIslandFeedback.Operation.TOP -> "正在回到顶部"
            state.atTop -> "刷新"
            else -> "回到顶部"
        }
        if (atTop != state.atTop || previousLoading != feedback.refreshing || previousOperation != feedback.operation) {
            atTop = state.atTop
            input.invalidate()
        }
        ensureFeedbackFrames()
    }

    private fun ensureFeedbackFrames() {
        if (!feedbackQueued && collapsed && input.isAttachedToWindow && feedback.needsFrame(SystemClock.uptimeMillis())) {
            feedbackQueued = true
            Choreographer.getInstance().postFrameCallback(feedbackFrame)
        } else if (!feedback.needsFrame(SystemClock.uptimeMillis())) feedbackFrameNanos = 0L
    }

    private fun horizontalInset() = HostTopIslandMotionSpec.horizontalInset(
        progress, dock.width.toFloat(), dock.height.toFloat(), density)

    private fun verticalInset() = HostTopIslandMotionSpec.verticalInset(
        progress, dock.width.toFloat(), dock.height.toFloat(), density)

    private fun fadeChildren() {
        val alpha = HostTopIslandMotionSpec.contentAlpha(progress)
        for (index in 0 until dock.childCount) {
            val child = dock.getChildAt(index)
            val original = childAlphas.getOrPut(child) { child.alpha }
            child.alpha = original * alpha
        }
    }

    private fun applyProgress(value: Float) {
        progress = value
        if (value == 0f && !animating) {
            childAlphas.forEach { (child, alpha) -> child.alpha = alpha }
            childAlphas.clear()
        } else {
            fadeChildren()
        }
        sync()
        dock.invalidateOutline()
        dock.invalidate()
        input.invalidate()
        backdrop?.onVisualMovement()
    }

    private fun settle() {
        animating = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        lastFrameNanos = 0L
        val target = if (collapsed) 1f else 0f
        spring.reset(target)
        applyProgress(target)
    }

    private fun animateTo(compact: Boolean, initialVelocity: Float = 0f) {
        val wasAnimating = animating
        collapsed = compact
        dock.visibility = View.VISIBLE
        input.importantForAccessibility = if (compact) View.IMPORTANT_FOR_ACCESSIBILITY_YES
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO
        input.isClickable = compact
        if (!ValueAnimator.areAnimatorsEnabled()) {
            settle()
            return
        }
        // 中途拉回展开只改目标，不清零当前位置和速度。
        if (!wasAnimating) {
            spring.value = progress
            spring.velocity = initialVelocity.coerceIn(-1.5f, 1.5f)
            lastFrameNanos = 0L
            animating = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
        ModernHookLog.info("[BIL] 顶栏灵动岛: ${if (compact) "collapsed" else "expanded"}")
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!dock.isShown && !collapsed) return false
            val inset = horizontalInset()
            // 胶囊/圆球外的区域直接放行，不保留覆盖整条顶栏的隐形触摸墙。
            val vertical = verticalInset()
            val radius = minOf(dock.width - inset * 2f, dock.height - vertical * 2f) / 2f
            val centerX = dock.width / 2f
            val centerY = dock.height / 2f
            val nearestX = event.x.coerceIn(minOf(inset + radius, centerX), maxOf(dock.width - inset - radius, centerX))
            val nearestY = event.y.coerceIn(minOf(vertical + radius, centerY), maxOf(dock.height - vertical - radius, centerY))
            val hitRadius = radius + if (collapsed) 4f * density else touchPadding
            if ((event.x - nearestX) * (event.x - nearestX) +
                (event.y - nearestY) * (event.y - nearestY) > hitRadius * hitRadius) return false
            downX = event.x
            downY = event.y
            bubbleTouch = collapsed
            // 展开收尾仍允许新手势；反向收起保留弹簧速度，不等动画完全静止。
            consumed = false
            gesture = null
            compactGesture = if (bubbleTouch) HostTopIslandCompactGesture(touchSlop,
                maxOf(12f * density, touchSlop * 1.25f)) else null
            if (bubbleTouch) {
                feedback.setPressed(true)
                ensureFeedbackFrames()
                input.invalidate()
                input.parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            val scroll = findScroll(tabs ?: dock) ?: findScroll(dock)
            dock.getLocationOnScreen(location)
            val dockLeft = location[0]
            scroll?.getLocationOnScreen(location)
            val onAction = if (scroll != null) {
                event.x + dockLeft >= location[0] + scroll.width
            } else event.x >= dock.width - 48f * density
            nativeScroll = scroll
            tabsCanScrollLeft = canScrollTabs(scroll, -1)
            tabsCanScrollRight = canScrollTabs(scroll, 1)
            scrollOrigin = scrollPosition(scroll)
            scrollMoved = false
            gesture = HostTopIslandGesture(
                startX = event.x,
                width = dock.width.toFloat(),
                edgeWidth = dock.width * .42f,
                touchSlop = touchSlop,
                collapseDistance = maxOf(8f * density, touchSlop),
                startsOnAction = onAction,
                canScrollLeft = tabsCanScrollLeft,
                canScrollRight = tabsCanScrollRight,
                // 原生先消耗 touchSlop 才移动内容；留出这段接管距离，不能提前抢走分类。
                ignoredScrollDistance = maxOf(10f * density, touchSlop * 1.5f)
            )
            if (event.x <= dock.width * .42f || event.x >= dock.width * .58f)
                input.parent?.requestDisallowInterceptTouchEvent(true)
        }

        if (bubbleTouch) {
            val compact = compactGesture ?: return true
            if (event.pointerCount > 1 || event.actionMasked == MotionEvent.ACTION_CANCEL ||
                event.actionMasked == MotionEvent.ACTION_POINTER_UP) compact.cancel()
            if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) {
                if (compact.move(event.x - downX, event.y - downY) == HostTopIslandCompactGesture.Decision.EXPAND) {
                    bubbleTouch = false
                    consumed = true
                    feedback.setPressed(false)
                    animateTo(false, -gestureVelocity(event))
                }
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                feedback.setPressed(false)
                ensureFeedbackFrames()
                if (event.actionMasked == MotionEvent.ACTION_UP && compact.canClick()) input.performClick()
                bubbleTouch = false
                consumed = false
                compactGesture = null
                input.parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
        if (consumed) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                consumed = false
                input.parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
        if (event.pointerCount > 1 || event.actionMasked == MotionEvent.ACTION_POINTER_UP) gesture?.keepNative()
        var forwarded = false
        if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) {
            // UP 要先决定是否补收起，不能先把它交给宿主触发分类/按钮点击。
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                forward(event)
                forwarded = true
            }
            val delta = scrollPosition(nativeScroll) - scrollOrigin
            val dx = event.x - downX
            val hasRoom = if (dx > 0f) tabsCanScrollLeft else tabsCanScrollRight
            scrollMoved = scrollMoved || hasRoom && delta * dx < 0f && abs(delta) >= maxOf(density, touchSlop * .125f)
        }
        if ((event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) &&
            gesture?.move(event.x - downX, event.y - downY, scrollMoved,
                finishing = event.actionMasked == MotionEvent.ACTION_UP) == HostTopIslandGesture.Decision.COLLAPSE) {
            forward(event, MotionEvent.ACTION_CANCEL)
            glow?.resetGestureState()
            consumed = event.actionMasked != MotionEvent.ACTION_UP
            input.parent?.requestDisallowInterceptTouchEvent(consumed)
            animateTo(true, gestureVelocity(event))
            return true
        }

        if (!forwarded) forward(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) scrollOrigin = scrollPosition(nativeScroll)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            gesture = null
            input.parent?.requestDisallowInterceptTouchEvent(false)
            glow?.resetGestureState()
        } else {
            glow?.updateGesture(1f, 0f, 0f, event.x, event.y, dock.width, dock.height)
        }
        // 即使宿主忽略按钮上的 MOVE，也必须收到后续事件来判断收起。
        return true
    }

    private fun forward(event: MotionEvent, action: Int = event.action) {
        val copy = MotionEvent.obtain(event)
        try {
            copy.action = action
            dock.dispatchTouchEvent(copy)
        } finally {
            copy.recycle()
        }
    }

    private fun findScroll(view: View): View? {
        if (view is HorizontalScrollView || view.canScrollHorizontally(-1) || view.canScrollHorizontally(1)) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findScroll(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun canScrollTabs(scroll: View?, direction: Int): Boolean = when (scroll) {
        is HorizontalScrollView -> if (direction < 0) scroll.scrollX > 0 else scroll.scrollX < scroll.tabScrollRange()
        else -> scroll?.canScrollHorizontally(direction) == true
    }

    private fun scrollPosition(view: View?): Int =
        (view?.scrollX ?: 0) - ((view as? ViewGroup)?.getChildAt(0)?.left ?: 0)

    private fun gestureVelocity(event: MotionEvent): Float {
        val elapsed = (event.eventTime - event.downTime).coerceAtLeast(1L)
        val travel = (dock.width - dock.height).coerceAtLeast(1)
        return abs(event.x - downX) * 1000f / elapsed / travel
    }
}
