package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.NavigationMotionPhase as MotionState

/**
 * 锚定气泡的展开/收起驱动。
 *
 * 与 [IconAnchoredMotionController] 的分工：那条走 outline 形变（来源行 → 屏幕中央大卡片），
 * 这条走**以来源图标中心为轴心的缩放**（工具栏小图标 → 贴在它旁边的气泡）。气泡不能用 outline
 * 裁剪，因为小角不是圆角矩形、表达不进 `Outline`；而缩放对短行程的气泡本来就是正解，
 * 小角会跟着一起长出来。
 *
 * 打断续接同样复用 [NavigationMotionSession] / [NavigationMotionContinuation] /
 * [NavigationMotionPolicy]，与另外两条动画路径保持同一套语义。
 */
internal class BubbleMotionController(
    private val layer: BubblePanelLayer,
    private val onExpanded: () -> Unit = {},
    private val onClosed: () -> Unit
) {
    // Spec 已包含单调的宽高曲线，公共时钟保持线性，避免二次 easing 让入场过早冲到终点。
    private val enterInterpolator = LinearInterpolator()
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
    private var predictiveStartExpansion = 1f
    private var entryShape = true
    private var entryNotified = false

    var expansion: Float = 0f
        private set

    val isClosing: Boolean
        get() = state == MotionState.CLOSING || state == MotionState.FINISHED

    /** 首帧前压到收起端；轴心每次重取，旋转或输入法改布局后不沿用旧值。 */
    fun prepareFirstFrame() {
        if (state != MotionState.PREPARING_ENTRY) return
        layer.prepare()
        apply(0f)
    }

    fun startEntry() {
        if (state != MotionState.PREPARING_ENTRY) return
        state = MotionState.ENTERING
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
        entryShape = false
        layer.settleExpanded()
        if (!entryNotified) {
            entryNotified = true
            onExpanded()
        }
    }

    fun beginPredictiveBack(): Boolean {
        if (isClosing || predictiveActive) return false
        if (!NavigationMotionPolicy.canNavigate(state, businessBlocked = false)) return false
        cancelAnimator()
        predictiveActive = true
        predictiveStartExpansion = expansion
        state = MotionState.PREDICTIVE_BACK
        session.reset(expansion, SystemClock.uptimeMillis())
        return true
    }

    fun progressPredictiveBack(rawProgress: Float) {
        if (!predictiveActive || isClosing) return
        val mapped = predictiveBackInterpolator.getInterpolation(rawProgress.coerceIn(0f, 1f))
        apply(BubbleMotionSpec.predictiveExpansion(predictiveStartExpansion, mapped))
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
            durationMs = base,
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
        layer.dispose()
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
        // 剩余行程只折算一次；旧路径在打断关闭时连续乘了两次行程，后半段会突然加速。
        val actualDuration = NavigationMotionPolicy.remainingDuration(durationMs, start, target)
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
        layer.applyFrame(clamped, entryShape)
    }

    private fun finish() {
        state = MotionState.FINISHED
        session.invalidate()
        onClosed()
    }
}
