package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

class ScrollPositionRefreshContractTest {
    private fun source(relative: String) = SourceContract.read(
        "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/$relative.kt")

    @Test fun actualScrollOffsetsReachTheActiveRendererSynchronously() {
        val scroll = source("activity/SettingsHomeScrollView")
            .after("override fun onScrollChanged(").before("override fun executeKeyEvent(")
        assertTrue(scroll.indexOf("super.onScrollChanged") < scroll.indexOf("onScrollPositionChanged?.invoke(this)"))
        assertTrue(scroll.contains("if (l != oldl || t != oldt)"))
        assertFalse(scroll.contains("post"))
        assertTrue(source("activity/SettingsHomePresenter").contains("onScrollPositionChanged = skinScrollPositionChanged"))
        assertTrue(source("activity/SettingsHomePresenter").contains("it.onScrollPositionChanged = null"))
        assertTrue(source("activity/MainActivity").contains("notifyPreparedSkinScrollPositionChanged(scroll)"))
        assertTrue(source("skin/activity/SkinnedActivity").contains("skinSessionOrNull?.notifyScrollPositionChanged(scrollHost)"))
        assertTrue(source("skin/runtime/ActivitySkinSession").contains("activeEngine.notifyScrollPositionChanged(scrollHost)"))
    }

    @Test fun secondaryPagesForwardActualScrollOffsetsLikeTheHomePage() {
        val bind = source("skin/activity/SkinnedActivity")
            .after("protected fun bindPreparedSkinScrollHost(scrollHost: View)")
            .before("/** 悬浮胶囊下方的内容层")
        assertTrue(bind.contains("setOnScrollChangeListener(View.OnScrollChangeListener"))
        assertTrue(bind.contains("if (x != oldX || y != oldY) notifyPreparedSkinScrollPositionChanged(view)"))
        assertFalse(bind.contains("post"))
        val diagnostics = source("activity/DiagnosticsActivity").after("private fun buildRoot(): View")
            .before("private fun buildToolbar(): View")
        assertTrue(diagnostics.contains("bindPreparedSkinScrollHost(scroll)"))
        val backup = source("activity/SettingsBackupActivity").after("val scrollView = NestedScrollView(this)")
            .before("private fun finishPageStretch()")
        assertTrue(backup.contains("bindPreparedSkinScrollHost(scrollView)"))
        val freeCopy = source("activity/FreeCopyActivity").after("liquidStretchViewport = liquidStretchScrollTarget?.let")
            .before("override fun onDestroy()")
        assertTrue(freeCopy.contains("bindPreparedSkinScrollHost(it)"))
    }

    @Test fun scopedRefreshOnlyInvalidatesOldVisibleDescendantRecords() {
        val scoped = source("skin/liquid/LiquidActivityRenderer")
            .after("override fun notifyScrollPositionChanged(scrollHost: View)")
            .before("override fun notifyPositionChanged()")
        assertTrue(scoped.contains("ScrollSurfaceScope.contains(view, scrollHost)"))
        assertTrue(scoped.contains("footprint.refreshState.shouldRefresh"))
        assertTrue(scoped.contains("view.invalidate()"))
        assertTrue(scoped.contains("!activityVisible"))
        assertTrue(scoped.contains("suppressRealtimeSamplingWhileScrolling()"))
        for (forbidden in listOf(".take()", "footprint.update(", "footprint.hasTransform =", "PixelCopy.request(",
                "rebuildBackdrop(", "createBitmap(", "queueSurfaceRefresh(", "flushSurfaceRefresh(")) {
            assertFalse(forbidden, scoped.contains(forbidden))
        }
    }

    @Test fun windowLateNotificationsFlushOnceAndOnDrawOnlyResetsFlags() {
        val renderer = source("skin/liquid/LiquidActivityRenderer")
        val window = renderer.after("private fun registerRefreshWindow(").before("private fun removeRefreshWindow(")
        assertTrue(window.contains("state?.batch?.beforeDraw()"))
        assertTrue(window.contains("if (state.batch.isAfterPreDraw) flushSurfaceRefresh(root)"))
        assertTrue(window.contains("observer.addOnDrawListener(draw)"))
        val draw = window.after("val draw = ViewTreeObserver.OnDrawListener").before("val state = LiquidWindowRefresh")
        assertTrue(draw.contains("batch?.drawn()"))
        assertFalse(draw.contains("invalidate"))
        assertFalse(draw.contains("flushSurfaceRefresh"))
        assertTrue(renderer.contains("it.removeOnDrawListener(state.draw)"))
        val cleanup = renderer.after("private fun removeRefreshWindow(").before("private fun queueSurfaceRefresh(")
        assertTrue(cleanup.contains("runCatching { it.removeOnDrawListener(state.draw) }.onFailure"))
        assertTrue(cleanup.contains("mainHandler.post"))
        assertTrue(cleanup.contains("runCatching { observer.removeOnDrawListener(state.draw) }"))
        val generic = renderer.after("override fun notifyPositionChanged()").before("private fun invalidateMovedSurfaces()")
        assertTrue(generic.contains("queueSurfaceRefresh(contentChanged = false)"))
        assertFalse(generic.contains("flushSurfaceRefresh"))
    }

    @Test fun materialFallbackUsesTheSameActualScrollScopeWithoutStartingCaptureWork() {
        val renderer = source("skin/material/FrostedMaterialRenderer")
        val notify = renderer.after("override fun notifyScrollPositionChanged(scrollHost: View)")
            .before("private fun onPositionChanged()")
        assertTrue(notify.contains("!lifecycle.canWork"))
        assertTrue(notify.contains("flushPositionChanges(windowRoot, scrollHost)"))
        assertFalse(notify.contains("capture("))
        val flush = renderer.after("private fun flushPositionChanges(").before("fun releaseMemory()")
        assertTrue(flush.contains("ScrollSurfaceScope.contains(view, scrollHost)"))
    }
}
