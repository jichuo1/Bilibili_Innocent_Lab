package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.contract.KotlinSourceScanner
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主侧动效位置同步、悬浮栏层级边界、静态底图宿主隔离与滚动偏移转发的局部源码护栏。
 */
class SkinMotionAndScrollRefreshContractTest {

    @Test
    fun modalEntryExitPredictiveBackAndBubbleMotionNotifySkinPosition() {
        val main = SourceContract.read("ui/activity/MainActivity.kt")
        val presenter = SourceContract.read("ui/activity/ModalPresenter.kt")

        assertTrue(
            KotlinSourceScanner.functions(main, "notifyModalSkinPositionChanged").single()
                .contains("notifyPreparedSkinPositionChanged()")
        )
        assertTrue(
            KotlinSourceScanner.functions(main, "installModalLiquidStretch").single()
                .contains("installPreparedLiquidStretch(target, canStretch)")
        )
        val slidingSelection = KotlinSourceScanner.functions(main, "createSkinSlidingSelection").single()
        assertTrue(slidingSelection.contains("LumenSlidingSelection("))
        assertTrue(slidingSelection.contains("notifyPreparedSkinPositionChanged()"))

        val choice = KotlinSourceScanner.functions(
            SourceContract.read("ui/activity/SemanticJevDialogs.kt"),
            "createSlidingChoice"
        ).single()
        assertTrue(choice.contains("createSkinSlidingSelection()"))
        assertTrue(choice.contains("frame.onSelect = onSelected"))
        assertFalse(choice.contains("ValueAnimator") || choice.contains("translationY ="))

        assertTrue(
            SourceContract.read("ui/widget/MaxHeightScrollView.kt")
                .contains("typealias MaxHeightScrollView = com.lumen.coacervation.engine.widget.LumenMaxHeightScrollView")
        )
        assertTrue(
            SourceContract.read("ui/activity/BubbleMotionController.kt")
                .contains("com.lumen.coacervation.engine.motion.modal.BubbleMotionController")
        )
        assertTrue(
            SourceContract.read("ui/activity/IconAnchoredMotionController.kt")
                .contains("com.lumen.coacervation.engine.motion.modal.IconAnchoredMotionController")
        )

        val animate = KotlinSourceScanner.functions(presenter, "animateCenteredEnter").single()
        val entry = animate.after("container.post {\n            LumenAnimator.property(container)\n                .scaleX(1f)")
            .before(".start()")
        assertTrue(entry.after(".setUpdateListener {").before(".withEndAction {").contains("notifyPositionChanged()"))
        assertTrue(entry.after(".withEndAction {").contains("notifyPositionChanged()"))

        val sized = KotlinSourceScanner.functions(presenter, "presentSizedModalDialog").single()
        assertTrue(sized.contains("notifyPositionChanged = ::notifyModalSkinPositionChanged"))
        assertEquals(
            2,
            Regex(Regex.escape("onContentMoved = { notifyModalSkinPositionChanged() }")).findAll(sized).count()
        )
        val sizedStretch = sized.after("styleModalSkinControls(container)")
            .before("val density = resources.displayMetrics.density")
        assertTrue(sizedStretch.contains("LumenPanelScroll.prepare(container)"))
        assertTrue(sizedStretch.contains("installModalLiquidStretch(target)"))

        val sizedProgress = sized.after("container.scaleX = 1f - 0.05f * progress").before("onCancelled = {")
        assertTrue(sizedProgress.contains("notifyModalSkinPositionChanged()"))
        val sizedBounce = sized.after("container.scaleX = 1f - 0.05f * progress")
            .after("onCancelled = {").before("onInvoked = {")
        assertTrue(sizedBounce.contains("notifyModalSkinPositionChanged()"))
        assertTrue(sizedBounce.contains("backdropBlur?.apply(container.alpha)"))

        val exit = KotlinSourceScanner.functions(presenter, "dismissCentered").single()
        assertTrue(exit.after(".setUpdateListener {").before(".let { anim ->").contains("notifyPositionChanged()"))
        assertTrue(
            KotlinSourceScanner.functions(presenter, "dismissAnchoredOrCentered").single()
                .contains("notifyPositionChanged = activity::notifyModalSkinPositionChanged")
        )
        assertTrue(
            KotlinSourceScanner.functions(presenter, "dismissWithAnimation").single()
                .contains("ModalPresenter.dismissAnchoredOrCentered(")
        )

        val centered = KotlinSourceScanner.functions(presenter, "presentCenteredDialog").single()
        val centeredProgress = centered.after("container.scaleX = 1f - 0.05f * progress").before("onCancelled = {")
        assertTrue(centeredProgress.contains("notifyPositionChanged()"))
        val centeredBounce = centered.after("container.scaleX = 1f - 0.05f * progress")
            .after("onCancelled = {").before("onInvoked = {")
        assertTrue(centeredBounce.contains("notifyPositionChanged()"))
        assertTrue(centeredBounce.contains("backdropBlur?.apply(container.alpha)"))
    }

    @Test
    fun floatingChromeLayeringLifecycleAndStaticBackdropIsolationHold() {
        val home = SourceContract.read("ui/activity/SettingsHomePresenter.kt")
        val target = home.indexOf("backdropTarget.addView(pager, FrameLayout.LayoutParams(-1, -1))")
        val layer = home.indexOf("addView(backdropTarget, FrameLayout.LayoutParams(-1, -1))")
        val dock = home.indexOf("pageLayer.addView(dock")
        val header = home.indexOf("pageLayer.addView(header")
        assertTrue(target in 0 until layer)
        assertTrue(layer < dock && dock < header)
        assertFalse(home.contains("backdropTarget.addView(dock"))
        assertFalse(home.contains("backdropTarget.addView(header"))
        assertTrue(home.contains("skinContentSource(pager)"))

        val position = home.after("pager.onPositionChanged = {").before("}")
        assertTrue(position.contains("floatingChrome?.onContentMoved()"))
        val dispose = KotlinSourceScanner.functions(home, "dispose").single()
        assertTrue(dispose.contains("floatingChrome?.dispose()"))
        assertTrue(home.contains("GlowFloatingChrome(backdropTarget, { activity.glowEngine }, ::edgeCoverage)"))
        assertTrue(home.contains("chrome.attach(dock, GlowScrollEdge.BOTTOM"))
        assertTrue(home.contains("header, GlowScrollEdge.TOP"))
        assertTrue(home.contains("companions = icons, thickenHost = false"))
        assertTrue(home.contains("chrome.attach(dock, GlowScrollEdge.BOTTOM, activity.getColor(R.color.colorTextGray)) { boost ->"))

        val skinned = SourceContract.read("ui/skin/activity/SkinnedActivity.kt")
        val dispatch = skinned.after("override fun dispatchTouchEvent(")
            .before("protected fun clearElasticInteractions()")
        assertTrue(dispatch.contains("lumen.onDispatchTouchEvent(event)"))

        for (name in listOf("SettingsBackupActivity", "DiagnosticsActivity")) {
            val activity = SourceContract.read("ui/activity/$name.kt")
            assertTrue(name, activity.contains("LiquidStaticBackdropHost {"))
        }
        assertFalse(SourceContract.read("ui/activity/MainActivity.kt").contains("LiquidStaticBackdropHost"))
    }

    @Test
    fun scrollObserversAndStretchDispatchForwardSynchronouslyToLumenSession() {
        val scroll = SourceContract.read("ui/activity/SettingsHomeScrollView.kt")
        assertTrue(scroll.contains("LiquidStretchGestureObserver"))
        val scrollDispatch = KotlinSourceScanner.functions(scroll, "dispatchTouchEvent").single()
        assertTrue(
            scrollDispatch.indexOf("observeTouch(event)") in 0 until scrollDispatch.indexOf("super.dispatchTouchEvent(event)")
        )

        val scrollChanged = KotlinSourceScanner.functions(scroll, "onScrollChanged").single()
        assertTrue(scrollChanged.indexOf("super.onScrollChanged") < scrollChanged.indexOf("onScrollPositionChanged?.invoke(this)"))
        assertTrue(scrollChanged.contains("if (l != oldl || t != oldt)"))
        assertFalse(scrollChanged.contains("post"))

        val presenter = SourceContract.read("ui/activity/SettingsHomePresenter.kt")
        assertTrue(presenter.contains("onScrollPositionChanged = skinScrollPositionChanged"))
        assertTrue(presenter.contains("it.onScrollPositionChanged = null"))
        assertTrue(SourceContract.read("ui/activity/MainActivity.kt").contains("notifyPreparedSkinScrollPositionChanged(scroll)"))

        val skinned = SourceContract.read("ui/skin/activity/SkinnedActivity.kt")
        assertTrue(skinned.contains("lumen.notifyScrollPositionChanged(scrollHost)"))
        assertTrue(
            KotlinSourceScanner.functions(skinned, "notifyPreparedSkinPositionChanged").single()
                .contains("lumen.notifyPositionChanged()")
        )
        assertTrue(
            SourceContract.read("ui/activity/SettingsPagePager.kt")
                .contains(": LumenPagePager(")
        )

        val bind = KotlinSourceScanner.functions(skinned, "bindPreparedSkinScrollHost").single()
        assertTrue(bind.contains("setOnScrollChangeListener(View.OnScrollChangeListener"))
        assertTrue(bind.contains("if (x != oldX || y != oldY) notifyPreparedSkinScrollPositionChanged(view)"))
        assertFalse(bind.contains("post"))

        val diagnostics = SourceContract.read("ui/activity/DiagnosticsActivity.kt")
        assertTrue(KotlinSourceScanner.functions(diagnostics, "buildRoot").single().contains("bindPreparedSkinScrollHost(scroll)"))
        val backup = SourceContract.read("ui/activity/SettingsBackupActivity.kt")
            .after("val scrollView = NestedScrollView(this)")
            .before("private fun finishPageStretch()")
        assertTrue(backup.contains("bindPreparedSkinScrollHost(scrollView)"))
        val freeCopy = SourceContract.read("ui/activity/FreeCopyActivity.kt")
            .after("liquidStretchViewport = liquidStretchScrollTarget?.let")
            .before("override fun onDestroy()")
        assertTrue(freeCopy.contains("bindPreparedSkinScrollHost(it)"))
    }
}
