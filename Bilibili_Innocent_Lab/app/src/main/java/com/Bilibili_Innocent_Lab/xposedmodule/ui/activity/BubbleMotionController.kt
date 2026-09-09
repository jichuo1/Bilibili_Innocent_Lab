package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.NavigationMotionPhase as MotionState

/**
 * 锚定气泡的展开/收起驱动。
 *
 * 与 [IconAnchoredMotionController] 的分工：那条走 outline 形变（来源行 → 屏幕中央大卡片），
 * 这条走**以小角尖端为轴心的缩放**（工具栏小图标 → 贴在它旁边的气泡）。气泡不能用 outline
 * 裁剪，因为小角不是圆角矩形、表达不进 `Outline`；而缩放对短行程的气泡本来就是正解，
 * 小角会跟着一起长出来。
 *
 * 打断续接同样复用 [NavigationMotionSession] / [NavigationMotionContinuation] /
 * [NavigationMotionPolicy]，与另外两条动画路径保持同一套语义。
 */
internal class BubbleMotionController(
    private val bubble: View,
    private val pivotXProvider: () -> Float,
    private val pivotYProvider: () -> Float,
    private val onClosed: () -> Unit
) {
    private val enterInterpolator = PathInterpolator(
        BubbleMotionSpec.ENTER_EASING_X1,
        BubbleMotionSpec.ENTER_EASING_Y1,
        BubbleMotionSpec.ENTER_EASING_X2,
        BubbleMotionSpec.ENTER_EASING_Y2
    )
    private val closeInterpolator = PathInterpolator(
        BubbleMotionSpec.CLOSE_EASING_X1,
        BubbleMotionSpec.CLOSE_EASING_Y1,
        BubbleMotionSpec.CLOSE_EASING_X2,
        BubbleMotionSpec.CLOSE_EASING_Y2
    )
    private val commitInterpolator = PathInterpolator(
        BubbleMotionSpec.COMMIT_EASING_X1,
        BubbleMotionSpec.COMMIT_EASING_Y1,
        BubbleMotionSpec.COMMIT_EASING_X2,
        BubbleMotionSpec.COMMIT_EASING_Y2
    )
    private val cancelInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val predictiveBackInterpolator = PathInterpolator(0f, 0f, 0f, 1f)

    private val session = NavigationMotionSession()
    private var animator: ValueAnimator? = null
    private var state = MotionState.PREPARING_ENTRY
    private var predictiveActive = false

    var expansion: Float = 0f
        private set

    val isClosing: Boolean
        get() = state == MotionState.CLOSING || state == MotionState.FINISHED

    /** 首帧前压到收起端；轴心每次重取，旋转或输入法改布局后不沿用旧值。 */
    fun prepareFirstFrame() {
        applyPivot()
        apply(0f)
    }

    fun startEntry() {
        state = MotionState.ENTERING
        applyPivot()
        animateTo(
            target = 1f,
            durationMs = BubbleMotionSpec.ENTER_DURATION_MS,
            interpolator = enterInterpolator,
            onEnd = ::settleExpanded
        )
    }

    fun snapToExpanded() {
        cancelAnimator()
        settleExpanded()
    }

    private fun settleExpanded() {
        state = MotionState.EXPANDED
        predictiveActive = false
        expansion = 1f
        bubble.scaleX = 1f
        bubble.scaleY = 1f
        bubble.alpha = 1f
    }

    fun beginPredictiveBack(): Boolean {
        if (isClosing || predictiveActive) return false
        if (!NavigationMotionPolicy.canNavigate(state, businessBlocked = false)) return false
        cancelAnimator()
        applyPivot()
        predictiveActive = true
        state = MotionState.PREDICTIVE_BACK
        session.reset(expansion, SystemClock.uptimeMillis())
        return true
    }

    fun progressPredictiveBack(rawProgress: Float) {
        if (!predictiveActive || isClosing) return
        val mapped = predictiveBackInterpolator.getInterpolation(rawProgress.coerceIn(0f, 1f))
        apply(1f - mapped)
    }

    fun cancelPredictiveBack() {
        if (!predictiveActive) return
        predictiveActive = false
        state = MotionState.CANCELLING_BACK
        animateTo(
            target = 1f,
            durationMs = BubbleMotionSpec.CANCEL_DURATION_MS,
            interpolator = cancelInterpolator,
            retarget = true,
            onEnd = ::settleExpanded
        )
    }

    /** @return true 表示收起动画已接管；调用方不要再走旧的 scale 退场。 */
    fun requestClose(interactiveCommit: Boolean): Boolean {
        if (isClosing) return true
        val hadInteractiveStart = predictiveActive
        val retarget = NavigationMotionPolicy.preserveFrame(state)
        predictiveActive = false
        cancelAnimator()
        applyPivot()
        state = MotionState.CLOSING
        if (!ValueAnimator.areAnimatorsEnabled() || expansion <= 0.001f) {
            apply(0f)
            finish()
            return true
        }
        val base = if (interactiveCommit && hadInteractiveStart) {
            BubbleMotionSpec.COMMIT_DURATION_MS
        } else {
            BubbleMotionSpec.CLOSE_DURATION_MS
        }
        animateTo(
            target = 0f,
            durationMs = NavigationMotionPolicy.remainingDuration(base, expansion, 0f),
            interpolator = if (interactiveCommit && hadInteractiveStart) {
                commitInterpolator
            } else {
                closeInterpolator
            },
            retarget = retarget,
            onEnd = ::finish
        )
        return true
    }

    fun handleWindowSizeChange() {
        if (isClosing) return
        cancelAnimator()
        snapToExpanded()
    }

    fun cancelMotion() {
        cancelAnimator()
        session.invalidate()
        state = MotionState.FINISHED
    }

    private fun applyPivot() {
        bubble.pivotX = pivotXProvider()
        bubble.pivotY = pivotYProvider()
    }

    private fun animateTo(
        target: Float,
        durationMs: Long,
        interpolator: android.animation.TimeInterpolator,
        retarget: Boolean = false,
        onEnd: () -> Unit
    ) {
        cancelAnimator()
        val start = expansion
        if (!ValueAnimator.areAnimatorsEnabled() || durationMs <= 0L || start == target) {
            apply(target)
            onEnd()
            return
        }
        val actualDuration = if (retarget) {
            NavigationMotionPolicy.remainingDuration(durationMs, start, target)
        } else {
            durationMs
        }
        val now = SystemClock.uptimeMillis()
        val continuation = if (retarget) {
            NavigationMotionContinuation(start, target, session.velocity(now), actualDuration)
        } else {
            null
        }
        session.reset(start, now)
        val token = session.generation
        val delta = target - start
        val created = ValueAnimator.ofFloat(start, target)
        animator = created
        created.duration = actualDuration
        created.interpolator = if (continuation != null) LinearInterpolator() else interpolator
        created.addUpdateListener { valueAnimator ->
            if (session.owns(token) && animator === created) {
                apply(
                    continuation?.value(valueAnimator.animatedFraction)
                        ?: (start + delta * valueAnimator.animatedFraction)
                )
            }
        }
        created.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                val current = session.owns(token) && animator === created
                if (current) animator = null
                if (current && !cancelled) onEnd()
            }
        })
        created.start()
    }

    private fun cancelAnimator() {
        animator?.let {
            animator = null
            it.cancel()
        }
    }

    private fun apply(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        expansion = clamped
        session.sample(clamped, SystemClock.uptimeMillis())
        val scale = BubbleMotionSpec.scale(clamped)
        bubble.scaleX = scale
        bubble.scaleY = scale
        // 气泡的背景与内容是同一个 View，只有一条 alpha；不像居中形变那样存在独立的表面层。
        bubble.alpha = BubbleMotionSpec.surfaceAlpha(clamped)
    }

    private fun finish() {
        state = MotionState.FINISHED
        session.invalidate()
        onClosed()
    }
}
