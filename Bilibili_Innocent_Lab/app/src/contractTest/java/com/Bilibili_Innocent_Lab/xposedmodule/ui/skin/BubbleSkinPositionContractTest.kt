package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleSkinPositionContractTest {
    fun rowTravelAndEndpointRestorationNotifyOnlyAfterWritingTheFrame() {
        val controller = LceMotionSource.read("ui/activity/BubbleMotionController.kt")
        val frame = controller.after("private fun apply(").before("private fun finish(")
        assertTrue(frame.indexOf("layer.applyFrame(clamped, entryShape)") < frame.indexOf("onContentMoved()"))
        val settle = controller.after("private fun settleExpanded()").before("fun beginPredictiveBack(")
        assertTrue(settle.indexOf("layer.settleExpanded()") < settle.indexOf("onContentMoved()"))
        val presenter = LceMotionSource.read("ui/activity/ModalPresenter.kt")
            .after("val bubbleController = if (bubbleLayer != null)").before("val titleMotion =")
        assertTrue(presenter.contains("onContentMoved = { notifyModalSkinPositionChanged() }"))
        assertTrue(
            LceMotionSource.read("ui/activity/MainActivity.kt")
                .after("internal fun notifyModalSkinPositionChanged()").before("internal fun modalSurfaceBackground(")
                .contains("notifyPreparedSkinPositionChanged()")
        )
    }

    fun unnotifiedWindowPanIsDetectedBeforeTheSurfaceBatchGate() {
        val renderer = LceMotionSource.engine("core/liquid/LiquidActivityRenderer.kt")
        val window = renderer.after("private fun registerRefreshWindow(").before("private fun removeRefreshWindow(")
        assertTrue(window.contains("surfaceCoordinates.localToScreen(root, refreshRootTransform)"))
        assertTrue(window.contains("state.position.update(refreshRootTransform)"))
        assertTrue(window.indexOf("state.position.update(refreshRootTransform)") < window.indexOf("flushSurfaceRefresh(root)"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (BubbleSkinPositionContractTest) -> Unit) {
            val s = BubbleSkinPositionContractTest()
            block(s)
        }
        runIsolated { s -> s.rowTravelAndEndpointRestorationNotifyOnlyAfterWritingTheFrame() }
        runIsolated { s -> s.unnotifiedWindowPanIsDetectedBeforeTheSurfaceBatchGate() }
    }
}
