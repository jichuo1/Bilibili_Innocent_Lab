package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeTheme
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeArtwork
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostGarbHeroBinding
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostDockLayer
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostGlowView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceScope
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceStyle
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostCallPassthrough
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassMaterial

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.widget.LumenSpring
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 宿主底栏专属合成容器图层。
 *
 * selectionView 和 glowView 分别持有引擎选中表面与触控柔光。
 * 宿主手势只计算布局、预览位置和原生点击目标；回弹轨迹由 LumenSpring 求值。
 * backdrop 统一管理内容采样、主题更新和表面生命周期。
 */
@SuppressLint("ViewConstructor")
internal class HostBottomBarDockLayer(
    context: Context,
    private val config: HostBottomBarFxConfig,
    private val tabHost: ViewGroup,
    private val container: ViewGroup?,
    palette: LumenPalette,
    isDark: Boolean,
    private val backdrop: HostSurfaceScope?,
    private val requestSanitization: () -> Unit,
    private val theme: HostChromeTheme = HostChromeTheme(context)
) : FrameLayout(context), HostDockLayer {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val inset = dp(HostNavigationMotion.INSET_DP.toFloat())
    private val maximumTravel = dp(HostNavigationMotion.MAX_TRAVEL_DP)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val publishViewId = resources.getIdentifier("home_publish_icon", "id", context.packageName)
    private val artworkId = resources.getIdentifier("tab_background", "id", context.packageName)
    private val hero = HostGarbHeroBinding(tabHost, dimEnabled = config.mineGarbDim)
    private val heroLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        HostThreadGuard.run("host.bottom.hero.layout") { hero.invalidate() }
    }
    private var materialColors = HostChromeColors(isDark, palette.primary)
    private val shadow = if (config.liquidGlass && config.appearance.material != HostGlassMaterial.PRISM)
        HostBottomBarShadow(tabHost, config.heightDp / 2f) else null

    // 1. 独立大胶囊晶体滑块 (硬件加速子 View)
    val selectionView = View(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (config.liquidGlass) backdrop?.surface(this, HostSurfaceStyle.selection(isDark))
    }

    // 2. 独立自适应柔光 (Glow View)
    val glowView = HostGlowView(context, palette.primary, density, maximumTravel)

    // 3. 物理状态与弹簧
    private var selectedIndex = 0
    private var pagerPosition = 0f
    private var displayedPosition = 0f
    private var press = 0f
    private var pressVelocity = 0f
    private var pressGeneration = 0L
    private var reboundGeneration = 0L
    private var pressAnimator: ValueAnimator? = null
    private var reboundAnimator: ValueAnimator? = null
    private var indicatorSettling = false
    private var disposed = false

    // 4. 2D 弹性手势与滑块拖动 (Scrub)
    private val gesture = HostNavigationGesture()
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
    private var offsetX = 0f
    private var offsetY = 0f
    private var offsetVelocityX = 0f
    private var offsetVelocityY = 0f
    private var lastMoveTime = 0L
    private var glowX = 0f
    private var glowY = 0f
    private val screenLoc = IntArray(2)
    private val hostClicks = HostCallPassthrough()

    private val rtl: Boolean get() = layoutDirection == LAYOUT_DIRECTION_RTL
    private val tabSlots = HostBottomBarTabSlots()
    private val participates: (Int) -> Boolean = { container?.getChildAt(it)?.visibility != View.GONE }
    private val count: Int get() = tabSlots.count.coerceAtLeast(1)
    private val contentWidth: Float get() = (width - inset * 2f).coerceAtLeast(0f)
    private val slotWidth: Float get() = if (count > 0) contentWidth / count else 0f

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener { HostThreadGuard.call("host.bottom.dock.predraw", true) {
        if (disposed) return@OnPreDrawListener true
        refreshTabSlots()
        refreshMaterial()
        shadow?.sync(materialColors.dark)
        if (config.liquidGlass) hero.refresh(materialColors.garbAccent != null)
        // 装扮异步图片/切页可只重置 visibility，不触发布局或普通 Tab 背景检查。
        if (config.liquidGlass && artworkId != 0) tabHost.findViewById<View>(artworkId)?.let(HostChromeArtwork::clear)
        // 宿主会在按压/切页/重建 tab 时重新挂上官方背景并重新布局：这里逐帧只做 O(items) 空判，
        // 命中才请求一次合并后的整树清理；不再每帧全树递归 + 资源名解析（快速切换掉帧的来源）。
        if (hostReappliedArtifacts()) requestSanitization()
        HostBottomBarFxController.alignTabContent(tabHost, container, density, inset.roundToInt(), config, materialColors)
        // 同步外部切页
        val detected = detectSelectedTab()
        if (!touchActive && !indicatorSettling && detected != selectedIndex) {
            selectedIndex = detected
            // 外部切页只同步透镜；再次 performClick 当前宿主页会触发刷新。
            reboundTo(null, scrubbed = false)
        }
        true
    } }

    private fun refreshMaterial() {
        val colors = theme.read()
        val changed = colors != materialColors
        backdrop?.updatePalette(colors)
        if (config.liquidGlass && (changed || backdrop?.owns(tabHost) != true)) {
            backdrop?.floating(tabHost, colors.dark, config.heightDp / 2f)
        }
        if (config.liquidGlass && (changed || backdrop?.owns(selectionView) != true)) {
            backdrop?.surface(selectionView, HostSurfaceStyle.selection(colors.dark))
        }
        if (!changed) return
        materialColors = colors
        glowView.recolor(colors.accent)
    }
    /** 直属于底栏的 tab 项是否被宿主重新挂上了官方背景/按压态（不带资源查询，O(items)）。 */
    private fun hostReappliedArtifacts(): Boolean {
        val c = container ?: return false
        for (i in 0 until c.childCount) {
            val tab = c.getChildAt(i)
            if (tab.background != null || tab.foreground != null || tab.isPressed) return true
        }
        return false
    }

    init {
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO

        addView(selectionView)
        addView(glowView)

        // 初次加载对齐选中项
        tabSlots.update(container?.childCount ?: 0, participates)
        val initial = detectSelectedTab()
        selectedIndex = initial
        pagerPosition = initial.toFloat()
        displayedPosition = initial.toFloat()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredW = MeasureSpec.getSize(widthMeasureSpec)
        val measuredH = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(measuredW, measuredH)
        HostThreadGuard.run("host.bottom.dock.measure") {
            refreshTabSlots(requestMeasure = false)
            val availableW = (measuredW - inset * 2f).coerceAtLeast(0f)
            val itemW = if (count > 0) (availableW / count).roundToInt() else 0
            val itemH = (measuredH - inset * 2f).roundToInt().coerceAtLeast(0)

            selectionView.measure(
                MeasureSpec.makeMeasureSpec(itemW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(itemH, MeasureSpec.EXACTLY)
            )
            glowView.measure(
                MeasureSpec.makeMeasureSpec(measuredW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(measuredH, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        HostThreadGuard.run("host.bottom.dock.layout") {
            val topInset = inset.roundToInt()
            selectionView.layout(topInset, topInset, topInset + selectionView.measuredWidth, topInset + selectionView.measuredHeight)
            glowView.layout(0, 0, width, height)
            applyVisuals()
        }
    }

    /** 通过 View 属性同步位移和缩放，只有位置变化才通知引擎更新采样。 */
    private fun applyVisuals() {
        if (disposed || width <= 0 || height <= 0) return
        var moved = false

        // 1. 底栏 2D 弹性拖拽形变
        val travel = HostNavigationMotion.travelClampScale(offsetX, offsetY, maximumTravel)
        val x = offsetX * travel
        val y = offsetY * travel
        if (tabHost.translationX != x) {
            tabHost.translationX = x
            moved = true
        }
        if (tabHost.translationY != y) {
            tabHost.translationY = y
            moved = true
        }

        // 2. 指示表面的位移与呼吸形变，与原生图标同步。
        val lensX = HostNavigationMotion.physicalSlot(displayedPosition, count, rtl) * slotWidth
        val scaleX = HostNavigationMotion.lensScaleX(press)
        val scaleY = HostNavigationMotion.lensScaleY(press)

        if (selectionView.translationX != lensX) {
            selectionView.translationX = lensX
            moved = true
        }
        if (selectionView.scaleX != scaleX) {
            selectionView.scaleX = scaleX
            moved = true
        }
        if (selectionView.scaleY != scaleY) {
            selectionView.scaleY = scaleY
            moved = true
        }

        // 3. 柔光动效自适应位置
        if (config.touchGlow) {
            glowView.updateGesture(
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
        }

        // 4. 实时透镜：只有真的动了才通知重采样（对齐模块 moved 门控的 onVisualMovement）。
        if (moved) backdrop?.onVisualMovement()
        shadow?.sync(materialColors.dark)
    }

    /**
     * 宿主 View 触摸监听的入口：手势状态归模块，由守卫兜底；松手时替宿主点击 tab，那段的异常
     * 属于宿主，手势收尾后原样抛回。
     */
    fun dispatchTouch(event: MotionEvent, tabIndex: Int = -1): Boolean {
        val handled = HostThreadGuard.call("host.bottom.touch", false) { handleTouch(event, tabIndex) }
        hostClicks.rethrow()
        return handled
    }

    /** 把点击交还宿主，拖动只预览页面并在松手时决定落点。 */
    private fun handleTouch(event: MotionEvent, tabIndex: Int = -1): Boolean {
        if (disposed || !isEnabled || width <= 0) return false
        refreshTabSlots()
        if (tabSlots.count == 0 || tabIndex >= 0 && tabSlots.slotOf(tabIndex) < 0) return false
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

        val rawX = event.rawX
        val rawY = event.rawY
        getLocationOnScreen(screenLoc)
        val localX = rawX - screenLoc[0]
        val localY = rawY - screenLoc[1]

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ignorePointers = false
                touchActive = true
                pointerId = event.getPointerId(0)
                downRawX = rawX
                downRawY = rawY
                downLocalX = localX
                downLocalY = localY
                initialIndicator = displayedPosition
                initialOffsetX = tabHost.translationX
                initialOffsetY = tabHost.translationY
                offsetX = initialOffsetX
                offsetY = initialOffsetY
                lastMoveTime = event.eventTime
                stopRebound()

                val selectedLeft = inset + HostNavigationMotion.physicalSlot(displayedPosition, count, rtl) * slotWidth
                val inSelection = localX >= selectedLeft && localX <= selectedLeft + slotWidth
                val index = if (tabIndex >= 0) tabSlots.slotOf(tabIndex)
                else HostNavigationMotion.indexAt(localX, contentWidth, inset, count, rtl)

                gesture.begin(index, inSelection)
                glowX = localX
                glowY = localY
                animatePress(1f)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchActive || event.getPointerId(0) != pointerId) {
                    cancelTouch()
                    return true
                }
                val dx = rawX - downRawX
                val dy = rawY - downRawY
                if (gesture.move(dx, dy, slop)) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }

                if (gesture.intent == HostNavigationIntent.SCRUB) {
                    displayedPosition = HostNavigationMotion.scrubPosition(initialIndicator, dx, slotWidth, count, rtl)
                }

                // 2D 弹性形变
                val factor = HostNavigationMotion.displacementScale(dx, dy, maximumTravel, dp(48f))
                val nextX = initialOffsetX + dx * factor
                val nextY = initialOffsetY + dy * factor
                val bounded = HostNavigationMotion.travelClampScale(nextX, nextY, maximumTravel)
                val elapsed = (event.eventTime - lastMoveTime).coerceAtLeast(1L)
                offsetVelocityX = ((nextX * bounded - offsetX) * 1000f / elapsed).coerceIn(-dp(240f), dp(240f))
                offsetVelocityY = ((nextY * bounded - offsetY) * 1000f / elapsed).coerceIn(-dp(240f), dp(240f))
                offsetX = nextX * bounded
                offsetY = nextY * bounded
                lastMoveTime = event.eventTime

                glowX = downLocalX + dx
                glowY = downLocalY + dy
                applyVisuals()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!touchActive) return true
                val releaseLocalX = downLocalX + event.rawX - downRawX
                val releaseLocalY = downLocalY + event.rawY - downRawY
                val releaseIndex = if (releaseLocalX in 0f..width.toFloat() && releaseLocalY in 0f..height.toFloat()) {
                    HostNavigationMotion.indexAt(releaseLocalX, contentWidth, inset, count, rtl)
                } else -1
                val scrubbed = gesture.intent == HostNavigationIntent.SCRUB
                val target = gesture.finish(false, displayedPosition, releaseIndex, count)

                if (event.eventTime - lastMoveTime > 100L) {
                    offsetVelocityX = 0f
                    offsetVelocityY = 0f
                }
                touchActive = false
                pointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
                animatePress(0f)
                reboundTo(target, scrubbed, userInitiated = true)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelTouch()
                return true
            }
        }
        return false
    }

    private fun cancelTouch() {
        gesture.cancel()
        touchActive = false
        pointerId = MotionEvent.INVALID_POINTER_ID
        offsetVelocityX = 0f
        offsetVelocityY = 0f
        parent?.requestDisallowInterceptTouchEvent(false)
        animatePress(0f)
        reboundTo(null, scrubbed = false)
    }

    /** 460ms 阻尼简谐物理弹簧 (按压呼吸过渡) */
    private fun animatePress(target: Float, after: (() -> Unit)? = null) {
        pressGeneration++
        pressAnimator?.cancel()
        pressAnimator = null
        if (disposed) return
        // 系统关闭动画或未附着时直接落值，不创建动画。
        if (!isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            press = target
            pressVelocity = 0f
            applyVisuals()
            after?.invoke()
            return
        }

        val token = pressGeneration
        val spring = LumenSpring(press, target, pressVelocity)
        pressAnimator = ValueAnimator.ofFloat(0f, HostNavigationMotion.SPRING_DURATION_MS / 1000f).apply {
            duration = HostNavigationMotion.SPRING_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                HostThreadGuard.run("host.bottom.press.frame") {
                    if (token == pressGeneration) {
                        val seconds = it.animatedFraction * HostNavigationMotion.SPRING_DURATION_MS / 1000f
                        press = spring.value(seconds).coerceIn(0f, 1f)
                        pressVelocity = spring.velocity(seconds)
                        applyVisuals()
                    }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = HostThreadGuard.run("host.bottom.press.end") {
                    if (token != pressGeneration) return@run
                    pressAnimator = null
                    press = target
                    pressVelocity = 0f
                    applyVisuals()
                    after?.invoke()
                }
            })
            start()
        }
    }

    /** 2D 弹性回弹与指示透镜物理弹簧到位 */
    private fun reboundTo(target: Int?, scrubbed: Boolean, userInitiated: Boolean = false) {
        stopRebound()
        if (disposed) return
        val token = ++reboundGeneration

        val startPos = displayedPosition
        indicatorSettling = true
        // 发布按钮是动作，不是页面；不能把它记成选中页，否则下一帧同步会误点原页面（首页即刷新）。
        selectedIndex = HostBottomBarScrubRelease.activateTarget(target, scrubbed, selectedIndex, ::clickTab)
        pagerPosition = selectedIndex.toFloat()
        val endPos = pagerPosition.coerceIn(0f, (count - 1).toFloat())
        val isClick = target != null && !scrubbed && abs(endPos - startPos) > 0.005f

        // 按压闪光只跟随用户点击：外部同步切页（宿主自己翻页/双次点击同页）不播 0.8→0 双段动画，
        // 快速切换时少一条 920ms 的动画链。
        if (isClick && userInitiated) {
            glowX = inset + (selectedIndex + 0.5f) * slotWidth
            glowY = height / 2f
            animatePress(0.8f) { animatePress(0f) }
        }

        // 系统关闭动画或未附着时直接落值。
        if (!isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            offsetX = 0f
            offsetY = 0f
            offsetVelocityX = 0f
            offsetVelocityY = 0f
            displayedPosition = endPos
            indicatorSettling = false
            applyVisuals()
            return
        }

        val springX = LumenSpring(offsetX, 0f, offsetVelocityX)
        val springY = LumenSpring(offsetY, 0f, offsetVelocityY)
        val indicatorSpring = LumenSpring(startPos, endPos)

        reboundAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = HostNavigationMotion.SPRING_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                HostThreadGuard.run("host.bottom.rebound.frame") {
                    if (token != reboundGeneration) return@run
                    val seconds = it.animatedFraction * HostNavigationMotion.SPRING_DURATION_MS / 1000f

                    offsetX = springX.value(seconds)
                    offsetY = springY.value(seconds)
                    offsetVelocityX = springX.velocity(seconds)
                    offsetVelocityY = springY.velocity(seconds)

                    if (indicatorSettling) {
                        displayedPosition = HostNavigationMotion.position(indicatorSpring.value(seconds), count)
                    }
                    applyVisuals()
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = HostThreadGuard.run("host.bottom.rebound.end") {
                    if (token != reboundGeneration) return@run
                    reboundAnimator = null
                    offsetX = 0f
                    offsetY = 0f
                    offsetVelocityX = 0f
                    offsetVelocityY = 0f
                    displayedPosition = endPos
                    indicatorSettling = false
                    applyVisuals()
                }
            })
            start()
        }
    }

    private fun stopRebound() {
        reboundGeneration++
        reboundAnimator?.cancel()
        reboundAnimator = null
        indicatorSettling = false
    }

    /** 返回是否激活页面；发布按钮只执行宿主动作，滑块随后回到原页面。 */
    private fun clickTab(index: Int): Boolean {
        val c = container ?: return false
        val hostIndex = tabSlots.hostIndex(index)
        if (hostIndex !in 0 until c.childCount) return false
        val tab = c.getChildAt(hostIndex)
        if (!tab.isShown || !tab.isEnabled) return false
        val publish = if (publishViewId != 0) tab.findViewById<View>(publishViewId) else null
        if (publish?.isShown == true) {
            if (!publish.isEnabled) return false
            // HomeTabPublishView 自身实现 OnTouchListener，发布入口只接收完整 DOWN/UP，没有 OnClickListener。
            val listener = publish as? View.OnTouchListener ?: return false
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, publish.width / 2f, publish.height / 2f, 0)
            hostClicks.host {
                try {
                    listener.onTouch(publish, event)
                    event.action = MotionEvent.ACTION_UP
                    listener.onTouch(publish, event)
                } finally {
                    event.recycle()
                }
            }
            return false
        }
        // 第一次点击抛异常就停，不再往子 View 补点；异常由 dispatchTouch 抛回。
        hostClicks.host {
            if (tab.performClick()) return true
            (tab as? ViewGroup)?.let { vg ->
                for (i in 0 until vg.childCount) {
                    val child = vg.getChildAt(i)
                    if (child.isShown && child.isEnabled && child.performClick()) return true
                }
            }
        }
        return false
    }

    private fun detectSelectedTab(): Int {
        val c = container ?: return selectedIndex
        for (slot in 0 until tabSlots.count) {
            val child = c.getChildAt(tabSlots.hostIndex(slot))
            if (child.isSelected || child.isActivated) return slot
        }
        return selectedIndex.coerceIn(0, count - 1)
    }

    private fun refreshTabSlots(requestMeasure: Boolean = true) {
        if (!tabSlots.update(container?.childCount ?: 0, participates)) return
        // 隐藏/重建可能发生在宿主单项绑定之后，不能延用旧手势和旧槽位宽度。
        selectedIndex = detectSelectedTab()
        pagerPosition = selectedIndex.toFloat()
        resetInteraction()
        selectionView.visibility = if (tabSlots.count == 0) View.INVISIBLE else View.VISIBLE
        if (requestMeasure) requestLayout()
    }

    /** 尺寸变化与视图分离时回到静止姿态。 */
    private fun resetInteraction() {
        gesture.cancel()
        touchActive = false
        ignorePointers = false
        pointerId = MotionEvent.INVALID_POINTER_ID
        pressGeneration++
        pressAnimator?.cancel()
        pressAnimator = null
        stopRebound()
        press = 0f
        pressVelocity = 0f
        offsetX = 0f
        offsetY = 0f
        offsetVelocityX = 0f
        offsetVelocityY = 0f
        displayedPosition = pagerPosition
        parent?.requestDisallowInterceptTouchEvent(false)
        applyVisuals()
        // press 已归零 => 本帧起光晕不可见，此刻 reset 不构成可见跳变。
        glowView.resetGestureState()
    }

    fun dispose() {
        if (disposed) return
        resetInteraction()
        disposed = true
        shadow?.detach()
        backdrop?.close()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        HostThreadGuard.run("host.bottom.dock.size") {
            if (w != oldw || h != oldh) {
                resetInteraction()
                if (isAttachedToWindow) backdrop?.revalidate()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        HostThreadGuard.run("host.bottom.dock.attach") {
            disposed = false
            hero.invalidate()
            container?.viewTreeObserver?.addOnPreDrawListener(preDrawListener)
            container?.viewTreeObserver?.addOnGlobalLayoutListener(heroLayoutListener)
            backdrop?.attach(tabHost)
        }
    }

    override fun onDetachedFromWindow() {
        HostThreadGuard.run("host.bottom.dock.detach") {
            container?.viewTreeObserver?.removeOnPreDrawListener(preDrawListener)
            container?.viewTreeObserver?.removeOnGlobalLayoutListener(heroLayoutListener)
            hero.restore()
            resetInteraction()
            backdrop?.detach()
            shadow?.detach()
        }
        super.onDetachedFromWindow()
    }
}
