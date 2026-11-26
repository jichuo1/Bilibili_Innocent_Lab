package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹窗动画（锚点形变的内容平移、无锚点的缩放）会移动卡片里玻璃表面的屏幕位置，而属性动画
 * 不触发滚动回调、不重录子 View 显示列表。不逐帧通知皮肤的话，按钮的采样停在动画中途的原点，
 * 动画结束约半秒后才被无关刷新补上，表现为"弹窗打开半秒后按钮光影跳一下"
 * （2026-09-26 真机：形变路径插桩确认原点 2273 → 2236，修复后录屏 0 跳变）。
 */
class ModalSkinPositionContractTest {
    private val main = LceMotionSource.read("ui/activity/MainActivity.kt")
    private val presenter = LceMotionSource.read("ui/activity/ModalPresenter.kt")

    fun defaultModalEntryNotifiesSkinEveryFrameAndAtTheEnd() {
        val animate = presenter.after("internal fun animateCenteredEnter(").before("internal fun dismissCentered(")
        val entry = animate.after("container.post {\n            LumenAnimator.property(container)\n                .scaleX(1f)")
            .before(".start()")
        val update = entry.after(".setUpdateListener {").before(".withEndAction {")
        assertTrue(update.contains("notifyPositionChanged()"))
        assertTrue(entry.after(".withEndAction {").contains("notifyPositionChanged()"))
        assertTrue(
            presenter.after("internal fun MainActivity.presentSizedModalDialog(")
                .contains("notifyPositionChanged = ::notifyModalSkinPositionChanged")
        )
        assertTrue(
            main.after("internal fun notifyModalSkinPositionChanged()").before("internal fun modalSurfaceBackground(")
                .contains("notifyPreparedSkinPositionChanged()")
        )
    }

    fun sharedExitAnimationNotifiesSkinEveryFrame() {
        val exit = presenter.after("internal fun dismissCentered(").before("internal fun presentCenteredDialog(")
        assertTrue(exit.after(".setUpdateListener {").before(".let { anim ->").contains("notifyPositionChanged()"))
        assertTrue(
            presenter.after("internal fun dismissAnchoredOrCentered(").before("internal fun registerAnchoredCloser(")
                .contains("notifyPositionChanged = activity::notifyModalSkinPositionChanged")
        )
        assertTrue(
            presenter.after("internal fun MainActivity.dismissWithAnimation(").before("internal fun MainActivity.createModalContainer(")
                .contains("ModalPresenter.dismissAnchoredOrCentered(")
        )
    }

    fun predictiveBackScaleAndBounceNotifySkin() {
        val centered = presenter.after("internal fun presentCenteredDialog(").before("internal fun surfaceBounds(")
        val centeredProgress = centered.after("container.scaleX = 1f - 0.05f * progress").before("onCancelled = {")
        assertTrue(centeredProgress.contains("notifyPositionChanged()"))
        val centeredBounce = centered.after("container.scaleX = 1f - 0.05f * progress")
            .after("onCancelled = {").before("onInvoked = {")
        assertTrue(centeredBounce.contains("notifyPositionChanged()"))
        assertTrue(centeredBounce.contains("backdropBlur?.apply(container.alpha)"))

        val sized = presenter.after("internal fun MainActivity.presentSizedModalDialog(")
        val progress = sized.after("container.scaleX = 1f - 0.05f * progress").before("onCancelled = {")
        assertTrue(progress.contains("notifyModalSkinPositionChanged()"))
        val bounce = sized.after("container.scaleX = 1f - 0.05f * progress")
            .after("onCancelled = {").before("onInvoked = {")
        assertTrue(bounce.contains("notifyModalSkinPositionChanged()"))
        // 黏性监听器：回弹显式设置时必须带上原本隐式沿用的模糊与压暗推进。
        assertTrue(bounce.contains("backdropBlur?.apply(container.alpha)"))
    }


    /** 锚点形变每帧平移卡片内容：平移写入之后必须通知皮肤（真机实证的跳变来源）。 */
    fun anchoredMorphNotifiesSkinAfterMovingContent() {
        val motion = LceMotionSource.read("ui/activity/IconAnchoredMotionController.kt")
        val frame = motion.after("content.translationX = frame.contentTranslationXPx").before("titleMotion?.apply(clamped)")
        assertTrue(frame.contains("onContentMoved()"))
        val settle = motion.after("private fun settleExpanded()").before("content.elevation = contentElevation")
        assertTrue(settle.indexOf("content.translationY = 0f") < settle.indexOf("onContentMoved()"))
        assertTrue(presenter.contains("onContentMoved = { notifyModalSkinPositionChanged() }"))
    }

    /**
     * 弹窗窗口里的玻璃移动/形变不触发 Liquid 的实时采样抑制：被截的主窗口内容是静止的，弹窗形变层也不在
     * 主窗口截图里。抑制会让每次开关弹窗整组失效两次，解除那次真机实测主页面一帧 37ms、弹窗被拖到 40ms。
     * 主窗口自己的滚动与二级页形变仍按原规则抑制。
     */
    fun dialogWindowMotionDoesNotSuppressMainWindowSampling() {
        val liquid = LceMotionSource.engine("core/liquid/LiquidActivityRenderer.kt")
        val flush = liquid.after("private fun flushSurfaceRefresh(").before("private fun isSurfacePotentiallyVisible(")
        assertTrue(flush.contains("if (originChanged && windowRoot === mainWindowRoot) surfaceMoved = true"))
        val register = liquid.after("internal fun registerSurfaceView(").before("footprint.update(bounds, radiusPx, originX, originY)")
        assertTrue(register.contains("view is LiquidMotionSurfaceFrameProvider && view.rootView === boundRoot?.rootView"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (ModalSkinPositionContractTest) -> Unit) {
            val s = ModalSkinPositionContractTest()
            block(s)
        }
        runIsolated { s -> s.defaultModalEntryNotifiesSkinEveryFrameAndAtTheEnd() }
        runIsolated { s -> s.sharedExitAnimationNotifiesSkinEveryFrame() }
        runIsolated { s -> s.predictiveBackScaleAndBounceNotifySkin() }
        runIsolated { s -> s.anchoredMorphNotifiesSkinAfterMovingContent() }
        runIsolated { s -> s.dialogWindowMotionDoesNotSuppressMainWindowSampling() }
    }
}
