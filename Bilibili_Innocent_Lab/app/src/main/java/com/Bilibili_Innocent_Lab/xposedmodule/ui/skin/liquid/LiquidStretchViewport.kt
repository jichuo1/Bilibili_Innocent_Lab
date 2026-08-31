package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EdgeEffect
import android.widget.FrameLayout
import androidx.core.graphics.withRotation
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.NestedScrollingParentHelper
import androidx.core.view.ViewCompat
import androidx.core.widget.EdgeEffectCompat
import com.highcapable.betterandroid.ui.extension.view.parentOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class LiquidStretchEdge {
    NONE,
    TOP,
    BOTTOM
}

internal enum class LiquidStretchUnconsumedAction {
    PULL_AND_CONSUME,
    ABSORB_AND_PROPAGATE,
    PROPAGATE
}

/** 与 Android View 无关的方向、距离和速度收敛规则。 */
internal object LiquidStretchOverscrollPolicy {
    private const val MAX_ABSORB_VELOCITY = 100_000

    fun pullEdge(dyUnconsumed: Int): LiquidStretchEdge = when {
        dyUnconsumed < 0 -> LiquidStretchEdge.TOP
        dyUnconsumed > 0 -> LiquidStretchEdge.BOTTOM
        else -> LiquidStretchEdge.NONE
    }

    fun releaseEdge(
        dy: Int,
        topDistance: Float,
        bottomDistance: Float
    ): LiquidStretchEdge = when {
        dy > 0 && topDistance > 0f -> LiquidStretchEdge.TOP
        dy < 0 && bottomDistance > 0f -> LiquidStretchEdge.BOTTOM
        else -> LiquidStretchEdge.NONE
    }

    fun normalizedDistance(deltaPixels: Int, viewportHeight: Int): Float {
        if (viewportHeight <= 0) return 0f
        return (abs(deltaPixels).toFloat() / viewportHeight.toFloat()).coerceIn(0f, 1f)
    }

    fun displacement(pointerX: Float, viewportWidth: Int, edge: LiquidStretchEdge): Float {
        if (viewportWidth <= 0) return 0.5f
        val fromLeft = (pointerX / viewportWidth.toFloat()).coerceIn(0f, 1f)
        return if (edge == LiquidStretchEdge.BOTTOM) 1f - fromLeft else fromLeft
    }

    fun consumedPixels(
        edge: LiquidStretchEdge,
        consumedDistance: Float,
        viewportHeight: Int
    ): Int {
        val magnitude = (abs(consumedDistance) * viewportHeight.coerceAtLeast(0)).roundToInt()
        return when (edge) {
            LiquidStretchEdge.TOP -> -magnitude
            LiquidStretchEdge.BOTTOM -> magnitude
            LiquidStretchEdge.NONE -> 0
        }
    }

    fun releaseConsumedPixels(
        edge: LiquidStretchEdge,
        consumedDistance: Float,
        viewportHeight: Int
    ): Int {
        val magnitude = (abs(consumedDistance) * viewportHeight.coerceAtLeast(0)).roundToInt()
        return when (edge) {
            LiquidStretchEdge.TOP -> magnitude
            LiquidStretchEdge.BOTTOM -> -magnitude
            LiquidStretchEdge.NONE -> 0
        }
    }

    fun absorbVelocity(velocityY: Float): Int =
        abs(velocityY).roundToInt().coerceIn(1, MAX_ABSORB_VELOCITY)

    fun unconsumedAction(
        isTouch: Boolean,
        hasFlingVelocity: Boolean
    ): LiquidStretchUnconsumedAction = when {
        isTouch -> LiquidStretchUnconsumedAction.PULL_AND_CONSUME
        hasFlingVelocity -> LiquidStretchUnconsumedAction.ABSORB_AND_PROPAGATE
        else -> LiquidStretchUnconsumedAction.PROPAGATE
    }

    fun shouldReleaseOnStop(
        isTouch: Boolean,
        nonTouchAbsorbed: Boolean,
        nonTouchAdjusted: Boolean
    ): Boolean = isTouch || (!nonTouchAbsorbed && nonTouchAdjusted)
}

/**
 * 让滚动前景共享同一个 Android 12+ stretch RenderNode，底层 Activity 背景保持静止。
 *
 * 内部滚动容器不再自己绘制 EdgeEffect；它先把未消费距离交给本父层，本父层在完成 child 绘制后
 * 调用 EdgeEffect.draw。viewport 不复制根底图，只在 child 下方绘制有界折射环，因此系统 stretch
 * 仍只作用于折射环、控件和透明高光组成的前景 RenderNode。
 */
@SuppressLint("ViewConstructor")
internal class LiquidStretchViewport private constructor(
    context: Context,
    private val scrollTarget: View,
    private val isStretchAllowed: () -> Boolean,
    private val drawBoundaryUnderlay: (Canvas, View, Float, Float) -> Unit,
    private val drawBoundaryHighlight: (Canvas, View, Float, Float) -> Unit,
    private val onStretchDistance: (Float) -> Unit
) : FrameLayout(context), NestedScrollingParent3 {

    private val nestedParentHelper = NestedScrollingParentHelper(this)
    private val topEffect = EdgeEffect(context)
    private val bottomEffect = EdgeEffect(context)
    private val legacyConsumed = IntArray(2)
    private var pointerX = 0f
    private var lastFlingVelocityY = 0f
    private var nonTouchAbsorbed = false
    private var nonTouchAdjusted = false

    init {
        // 无背景的 ViewGroup 默认会绕过 draw() 直接 dispatchDraw()；必须关闭该快路径，
        // 才能在 child 绘制完成后把 EdgeEffect stretch 应用到这个前景 RenderNode。
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        scrollTarget.overScrollMode = View.OVER_SCROLL_NEVER
        addView(
            scrollTarget,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width > 0 && height > 0) {
            topEffect.setSize(width, height)
            bottomEffect.setSize(width, height)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        pointerX = event.x
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            lastFlingVelocityY = 0f
            if (isAllowedNow()) stopEffectsForTouch()
            else finishStretch()
        }
        return super.dispatchTouchEvent(event)
    }

    override fun draw(canvas: Canvas) {
        if (!isAllowedNow()) {
            super.draw(canvas)
            finishStretch()
            return
        }
        var topDistance = EdgeEffectCompat.getDistance(topEffect)
        var bottomDistance = EdgeEffectCompat.getDistance(bottomEffect)
        onStretchDistance(maxOf(topDistance, bottomDistance))
        // 宽幅实时折射必须先于 child 绘制；否则它会把四边控件重新覆盖成背景，表现为裁剪。
        runCatching { drawBoundaryUnderlay(canvas, this, topDistance, bottomDistance) }
        super.draw(canvas)
        // 控件上方只保留透明高光/焦散，不再重绘实时背景。
        runCatching { drawBoundaryHighlight(canvas, this, topDistance, bottomDistance) }
        var continueDrawing = false
        if (!topEffect.isFinished) {
            continueDrawing = topEffect.draw(canvas) || continueDrawing
        }
        if (!bottomEffect.isFinished) {
            canvas.withRotation(
                degrees = 180f,
                pivotX = width * 0.5f,
                pivotY = height * 0.5f
            ) {
                continueDrawing = bottomEffect.draw(this) || continueDrawing
            }
        }
        topDistance = EdgeEffectCompat.getDistance(topEffect)
        bottomDistance = EdgeEffectCompat.getDistance(bottomEffect)
        onStretchDistance(maxOf(topDistance, bottomDistance))
        if (continueDrawing) postInvalidateOnAnimation()
    }

    override fun onStartNestedScroll(
        child: View,
        target: View,
        axes: Int,
        type: Int
    ): Boolean {
        val allowed = isAllowedNow() && axes and ViewCompat.SCROLL_AXIS_VERTICAL != 0
        if (!allowed) finishStretch()
        return allowed
    }

    override fun onNestedScrollAccepted(
        child: View,
        target: View,
        axes: Int,
        type: Int
    ) {
        nestedParentHelper.onNestedScrollAccepted(child, target, axes, type)
        if (type == ViewCompat.TYPE_NON_TOUCH) {
            nonTouchAbsorbed = false
            nonTouchAdjusted = false
        }
    }

    override fun onStopNestedScroll(target: View, type: Int) {
        nestedParentHelper.onStopNestedScroll(target, type)
        val isTouch = type == ViewCompat.TYPE_TOUCH
        if (LiquidStretchOverscrollPolicy.shouldReleaseOnStop(
                isTouch = isTouch,
                nonTouchAbsorbed = nonTouchAbsorbed,
                nonTouchAdjusted = nonTouchAdjusted
            )
        ) {
            releaseEffects()
        }
        if (!isTouch) {
            lastFlingVelocityY = 0f
            nonTouchAbsorbed = false
            nonTouchAdjusted = false
        }
    }

    override fun onNestedPreScroll(
        target: View,
        dx: Int,
        dy: Int,
        consumed: IntArray,
        type: Int
    ) {
        if (!isAllowedNow() || height <= 0) return
        val edge = LiquidStretchOverscrollPolicy.releaseEdge(
            dy = dy,
            topDistance = EdgeEffectCompat.getDistance(topEffect),
            bottomDistance = EdgeEffectCompat.getDistance(bottomEffect)
        )
        val effect = when (edge) {
            LiquidStretchEdge.TOP -> topEffect
            LiquidStretchEdge.BOTTOM -> bottomEffect
            LiquidStretchEdge.NONE -> return
        }
        val deltaDistance = -LiquidStretchOverscrollPolicy.normalizedDistance(dy, height)
        val released = EdgeEffectCompat.onPullDistance(
            effect,
            deltaDistance,
            LiquidStretchOverscrollPolicy.displacement(pointerX, width, edge)
        )
        consumed[1] += LiquidStretchOverscrollPolicy.releaseConsumedPixels(
            edge,
            released,
            height
        )
        if (type == ViewCompat.TYPE_NON_TOUCH && released != 0f) {
            nonTouchAdjusted = true
        }
        if (EdgeEffectCompat.getDistance(effect) == 0f) effect.onRelease()
        postInvalidateOnAnimation()
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int,
        consumed: IntArray
    ) {
        if (!isAllowedNow() || height <= 0 || dyUnconsumed == 0) return
        val edge = LiquidStretchOverscrollPolicy.pullEdge(dyUnconsumed)
        val effect = when (edge) {
            LiquidStretchEdge.TOP -> {
                if (!bottomEffect.isFinished) bottomEffect.onRelease()
                topEffect
            }
            LiquidStretchEdge.BOTTOM -> {
                if (!topEffect.isFinished) topEffect.onRelease()
                bottomEffect
            }
            LiquidStretchEdge.NONE -> return
        }

        val effectChanged = when (LiquidStretchOverscrollPolicy.unconsumedAction(
            isTouch = type == ViewCompat.TYPE_TOUCH,
            hasFlingVelocity = lastFlingVelocityY != 0f
        )) {
            LiquidStretchUnconsumedAction.PULL_AND_CONSUME -> {
                val pulled = EdgeEffectCompat.onPullDistance(
                    effect,
                    LiquidStretchOverscrollPolicy.normalizedDistance(dyUnconsumed, height),
                    LiquidStretchOverscrollPolicy.displacement(pointerX, width, edge)
                )
                consumed[1] += LiquidStretchOverscrollPolicy.consumedPixels(
                    edge,
                    pulled,
                    height
                )
                true
            }

            LiquidStretchUnconsumedAction.ABSORB_AND_PROPAGATE -> {
                effect.onAbsorb(
                    LiquidStretchOverscrollPolicy.absorbVelocity(lastFlingVelocityY)
                )
                lastFlingVelocityY = 0f
                nonTouchAbsorbed = true
                // 必须保持 consumed 不变，让 NestedScrollView 终止已经撞边的 OverScroller。
                true
            }

            LiquidStretchUnconsumedAction.PROPAGATE -> false
        }
        if (effectChanged) postInvalidateOnAnimation()
    }

    override fun onNestedFling(
        target: View,
        velocityX: Float,
        velocityY: Float,
        consumed: Boolean
    ): Boolean {
        if (isAllowedNow() && velocityY != 0f) lastFlingVelocityY = velocityY
        return false
    }

    override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float): Boolean =
        false

    override fun getNestedScrollAxes(): Int = nestedParentHelper.nestedScrollAxes

    override fun onStartNestedScroll(child: View, target: View, axes: Int): Boolean =
        onStartNestedScroll(child, target, axes, ViewCompat.TYPE_TOUCH)

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int) {
        onNestedScrollAccepted(child, target, axes, ViewCompat.TYPE_TOUCH)
    }

    override fun onStopNestedScroll(target: View) {
        onStopNestedScroll(target, ViewCompat.TYPE_TOUCH)
    }

    override fun onNestedPreScroll(
        target: View,
        dx: Int,
        dy: Int,
        consumed: IntArray
    ) {
        onNestedPreScroll(target, dx, dy, consumed, ViewCompat.TYPE_TOUCH)
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int
    ) {
        legacyConsumed.fill(0)
        onNestedScroll(
            target,
            dxConsumed,
            dyConsumed,
            dxUnconsumed,
            dyUnconsumed,
            type,
            legacyConsumed
        )
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int
    ) {
        onNestedScroll(
            target,
            dxConsumed,
            dyConsumed,
            dxUnconsumed,
            dyUnconsumed,
            ViewCompat.TYPE_TOUCH
        )
    }

    fun finishStretch() {
        val hadEffect = !topEffect.isFinished || !bottomEffect.isFinished
        topEffect.finish()
        bottomEffect.finish()
        lastFlingVelocityY = 0f
        nonTouchAbsorbed = false
        nonTouchAdjusted = false
        onStretchDistance(0f)
        if (hadEffect) invalidate()
    }

    override fun onDetachedFromWindow() {
        finishStretch()
        super.onDetachedFromWindow()
    }

    private fun releaseEffects() {
        var released = false
        if (!topEffect.isFinished) {
            topEffect.onRelease()
            released = true
        }
        if (!bottomEffect.isFinished) {
            bottomEffect.onRelease()
            released = true
        }
        if (released) postInvalidateOnAnimation()
    }

    /** 与 NestedScrollView.stopGlowAnimations 对齐：新手势接管回弹，但不突兀清零形变量。 */
    private fun stopEffectsForTouch() {
        var stopped = false
        if (EdgeEffectCompat.getDistance(topEffect) > 0f) {
            EdgeEffectCompat.onPullDistance(
                topEffect,
                0f,
                LiquidStretchOverscrollPolicy.displacement(
                    pointerX,
                    width,
                    LiquidStretchEdge.TOP
                )
            )
            stopped = true
        }
        if (EdgeEffectCompat.getDistance(bottomEffect) > 0f) {
            EdgeEffectCompat.onPullDistance(
                bottomEffect,
                0f,
                LiquidStretchOverscrollPolicy.displacement(
                    pointerX,
                    width,
                    LiquidStretchEdge.BOTTOM
                )
            )
            stopped = true
        }
        if (stopped) postInvalidateOnAnimation()
    }

    private fun isAllowedNow(): Boolean =
        runCatching(isStretchAllowed).getOrDefault(false)

    companion object {
        fun installAround(
            scrollTarget: View,
            isStretchAllowed: () -> Boolean,
            drawBoundaryUnderlay: (Canvas, View, Float, Float) -> Unit,
            drawBoundaryHighlight: (Canvas, View, Float, Float) -> Unit,
            onStretchDistance: (Float) -> Unit
        ): LiquidStretchViewport? {
            val parent = scrollTarget.parentOrNull() ?: return null
            val index = parent.indexOfChild(scrollTarget).takeIf { it >= 0 } ?: return null
            val originalLayoutParams = scrollTarget.layoutParams
            parent.removeViewAt(index)
            return try {
                val viewport = LiquidStretchViewport(
                    context = scrollTarget.context,
                    scrollTarget = scrollTarget,
                    isStretchAllowed = isStretchAllowed,
                    drawBoundaryUnderlay = drawBoundaryUnderlay,
                    drawBoundaryHighlight = drawBoundaryHighlight,
                    onStretchDistance = onStretchDistance
                )
                parent.addView(viewport, index, originalLayoutParams)
                viewport
            } catch (throwable: Throwable) {
                val temporaryParent = scrollTarget.parentOrNull()
                temporaryParent?.removeView(scrollTarget)
                if (scrollTarget.parent == null) {
                    parent.addView(scrollTarget, index.coerceAtMost(parent.childCount), originalLayoutParams)
                }
                throw throwable
            }
        }
    }
}
