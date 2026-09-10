package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.roundToInt
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.NavigationMotionPhase as MotionState

/**
 * 图标锚点形变的状态机与驱动。
 *
 * 复用 [NavigationMotionSession] / [NavigationMotionContinuation] / [NavigationMotionPolicy]：
 * 打断后按当前值与当前速度做 Hermite 续接，而不是从头缓入——这是弹窗路径与 Activity 路径
 * 唯一必须对齐的东西，否则连点入口或"入场未完就返回"会出现明显的速度断层。
 *
 * @param layer 全屏承载层，形状由它的 outline 表达。
 * @param content 弹窗卡片本体，只被改 alpha 与 elevation，绝不缩放（缩放会把文字压扁）。
 * @param surfaceDrawable 形变期间的表面背景；抵达展开端后摘掉，交还给卡片自己的背景，
 *   否则全屏 layer 的不透明背景会在裁剪关闭后铺满整屏。
 * @param resolveGeometry 每次进入形变时重新解析，旋转/分屏后不沿用旧矩形。
 * @param onClosed 收缩到来源端后真正 dismiss。
 */
internal class IconAnchoredMotionController(
    private val layer: IconAnchoredMotionLayer,
    private val content: View,
    private val surfaceDrawable: Drawable,
    private val resolveGeometry: () -> IconAnchoredMotionGeometry?,
    private val titleMotion: ModalTitleMotion? = null,
    /** 每帧的展开进度；供背景毛玻璃这类"跟着同一个时钟"的附属效果使用，不另开动画。 */
    private val onFrame: (Float) -> Unit = {},
    private val onExpanded: () -> Unit = {},
    private val onClosed: () -> Unit
) {
    private val enterInterpolator = PathInterpolator(
        IconAnchoredMotionSpec.ENTER_EASING_X1,
        IconAnchoredMotionSpec.ENTER_EASING_Y1,
        IconAnchoredMotionSpec.ENTER_EASING_X2,
        IconAnchoredMotionSpec.ENTER_EASING_Y2
    )
    private val closeInterpolator = PathInterpolator(
        IconAnchoredMotionSpec.CLOSE_EASING_X1,
        IconAnchoredMotionSpec.CLOSE_EASING_Y1,
        IconAnchoredMotionSpec.CLOSE_EASING_X2,
        IconAnchoredMotionSpec.CLOSE_EASING_Y2
    )
    private val commitInterpolator = PathInterpolator(
        IconAnchoredMotionSpec.COMMIT_EASING_X1,
        IconAnchoredMotionSpec.COMMIT_EASING_Y1,
        IconAnchoredMotionSpec.COMMIT_EASING_X2,
        IconAnchoredMotionSpec.COMMIT_EASING_Y2
    )
    private val cancelInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val predictiveBackInterpolator = PathInterpolator(0f, 0f, 0f, 1f)

    private val frame = IconAnchoredMotionFrameBuffer()
    private val session = NavigationMotionSession()
    private var animator: ValueAnimator? = null
    private var geometry: IconAnchoredMotionGeometry? = null
    private var contentTiming = IconAnchoredContentTiming.TIMED
    private var state = MotionState.PREPARING_ENTRY
    private var predictiveActive = false
    private var predictiveStartExpansion = 1f
    private var wasInterrupted = false
    private var contentElevation = content.elevation
    private var entryNotified = false

    /**
     * 卡片自己的背景——**描边就在这张 drawable 上**。
     *
     * 形变期间承载层已经按纪律铺了同色同圆角的 `skinModalBackground`，所以给这张背景整体
     * 降 alpha 在视觉上只淡掉描边，填充没有差别；正文的 alpha 仍由 `content.alpha` 单独控制。
     * 每个弹窗的背景都是 `createModalContainer` 现造的实例，不与别处共享。
     */
    private val contentBackground: Drawable? = content.background

    var expansion: Float = 0f
        private set

    /** 已经稳定在展开端；`presentSizedModalDialog` 用它决定是否还要拦触摸。 */
    val isExpanded: Boolean
        get() = state == MotionState.EXPANDED

    /** 已经进入不可逆的关闭链，后续返回一律忽略。 */
    val isClosing: Boolean
        get() = state == MotionState.CLOSING || state == MotionState.FINISHED

    /**
     * 首帧前把画面压到来源端。
     *
     * 必须在 pre-draw 之前完成，否则会先闪一帧完整卡片再跳回图标。
     */
    fun prepareFirstFrame(): Boolean {
        if (state != MotionState.PREPARING_ENTRY) return false
        val resolved = resolveGeometry() ?: return false
        geometry = resolved
        contentTiming = IconAnchoredContentTiming.TIMED
        contentElevation = content.elevation
        content.elevation = 0f
        layer.background = surfaceDrawable
        layer.blockInteraction = true
        titleMotion?.prepare(0f)
        apply(0f)
        return true
    }

    fun startEntry() {
        if (state != MotionState.PREPARING_ENTRY) return
        if (geometry == null) {
            snapToExpanded()
            return
        }
        state = MotionState.ENTERING
        animateTo(
            target = 1f,
            durationMs = IconAnchoredMotionSpec.ENTER_DURATION_MS,
            interpolator = enterInterpolator,
            onEnd = ::settleExpanded
        )
    }

    /** 无来源矩形、系统动画关闭或几何失效时的终态。 */
    fun snapToExpanded() {
        cancelAnimator()
        expansion = 1f
        settleExpanded()
    }

    private fun settleExpanded() {
        titleMotion?.expanded()
        state = MotionState.EXPANDED
        predictiveActive = false
        wasInterrupted = false
        contentTiming = IconAnchoredContentTiming.TIMED
        expansion = 1f
        // 顺序不能反：先恢复卡片，再摘表面并关闭裁剪，中间不能出现"两者都不可见"的一帧。
        content.alpha = 1f
        content.translationX = 0f
        content.translationY = 0f
        content.elevation = contentElevation
        // 描边斜坡本来就收在 1，这里只是把浮点误差钉成整数 255。
        contentBackground?.alpha = 255
        onFrame(1f)
        layer.alpha = 1f
        layer.background = null
        layer.clearShape()
        layer.blockInteraction = false
        if (!entryNotified) {
            entryNotified = true
            onExpanded()
        }
    }

    /** @return true 表示这次返回由形变接管；false 表示调用方继续走原有退场。 */
    fun beginPredictiveBack(): Boolean {
        if (isClosing || predictiveActive) return false
        if (!NavigationMotionPolicy.canNavigate(state, businessBlocked = false)) return false
        val resolved = prepareExitFrame(IconAnchoredContentTiming.PREDICTIVE) ?: return false
        geometry = resolved
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
        apply(predictiveStartExpansion * (1f - mapped))
    }

    fun cancelPredictiveBack() {
        if (!predictiveActive) return
        predictiveActive = false
        state = MotionState.CANCELLING_BACK
        animateTo(
            target = 1f,
            durationMs = IconAnchoredMotionSpec.CANCEL_DURATION_MS,
            interpolator = cancelInterpolator,
            retarget = true,
            onEnd = ::settleExpanded
        )
    }

    /**
     * @param interactiveCommit 手势松手提交（承接已有速度），false 为点击/按键触发的定时关闭。
     * @return true 表示形变接管；false 表示几何不可用，调用方应走原有 scale 退场。
     */
    fun requestClose(interactiveCommit: Boolean): Boolean {
        if (isClosing) return true
        val hadInteractiveStart = predictiveActive
        if (!interactiveCommit || !hadInteractiveStart) {
            val resolved = prepareExitFrame(IconAnchoredContentTiming.TIMED) ?: return false
            geometry = resolved
        }
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
            IconAnchoredMotionSpec.COMMIT_DURATION_MS
        } else {
            IconAnchoredMotionSpec.CLOSE_DURATION_MS
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

    /** 窗口尺寸变化（旋转、分屏、输入法）时旧矩形失效，直接落到稳定端。 */
    fun handleWindowSizeChange() {
        if (isClosing) {
            cancelAnimator()
            apply(0f)
            finish()
            return
        }
        cancelAnimator()
        geometry = null
        snapToExpanded()
    }

    fun cancelMotion() {
        titleMotion?.dispose()
        cancelAnimator()
        session.invalidate()
        state = MotionState.FINISHED
        // 硬关（activeConfirmDialog?.dismiss()）会停在半路，卡片背景不能留着半透明的 alpha：
        // 这张 drawable 属于被关掉的弹窗，但复用同一个 container 的路径会看到残留。
        contentBackground?.alpha = 255
    }

    /**
     * 退场前重新解析几何。
     *
     * 若当前正处于会被打断的相位（入场中 / 回弹中 / 手势中），沿用原几何与原 profile 直到
     * 抵达稳定端点——中途从 TIMED 切到 PREDICTIVE（或反向）会让同一 expansion 对应不同的
     * 正文 alpha，产生可见跳帧。这条纪律与容器形变那套完全一致。
     */
    private fun prepareExitFrame(timing: IconAnchoredContentTiming): IconAnchoredMotionGeometry? {
        if (NavigationMotionPolicy.preserveFrame(state)) {
            wasInterrupted = true
            return geometry
        }
        wasInterrupted = false
        val resolved = resolveGeometry() ?: return null
        contentTiming = timing
        contentElevation = content.elevation.takeIf { it > 0f } ?: contentElevation
        content.elevation = 0f
        layer.background = surfaceDrawable
        layer.blockInteraction = true
        titleMotion?.prepare(expansion)
        return resolved
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
        val current = geometry ?: return
        val clamped = value.coerceIn(0f, 1f)
        expansion = clamped
        session.sample(clamped, SystemClock.uptimeMillis())
        IconAnchoredMotionSpec.fillFrame(frame, clamped, current, contentTiming)
        layer.applyFrame(frame.left, frame.top, frame.right, frame.bottom, frame.radiusPx)
        layer.alpha = frame.surfaceAlpha
        content.alpha = frame.contentAlpha
        contentBackground?.alpha = (frame.strokeAlpha * 255f).roundToInt().coerceIn(0, 255)
        onFrame(clamped)
        content.translationX = frame.contentTranslationXPx
        content.translationY = frame.contentTranslationYPx
        titleMotion?.apply(clamped)
    }

    private fun finish() {
        titleMotion?.closed()
        state = MotionState.FINISHED
        session.invalidate()
        onClosed()
    }
}
