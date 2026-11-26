package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidMotionMappingContractTest {
    fun modalControlsUseSharedSelectionAndStretchGeometry() {
        val main = SourceContract.read("ui/activity/MainActivity.kt")
            .after("internal fun createSkinSlidingSelection(").before("internal fun createGitHubMenuRow(")
        assertTrue(main.contains("LumenSlidingSelection("))
        assertTrue(main.contains("notifyPreparedSkinPositionChanged()"))
        val choice = SourceContract.read("ui/activity/SemanticJevDialogs.kt")
            .after("fun MainActivity.createSlidingChoice(").before("/** 各过滤面的开关键")
        assertTrue(choice.contains("createSkinSlidingSelection()"))
        assertTrue(choice.contains("frame.onSelect = onSelected"))
        assertFalse(choice.contains("ValueAnimator") || choice.contains("translationY ="))
        val renderer = LceMotionSource.engine("core/liquid/LiquidActivityRenderer.kt")
        assertTrue(renderer.contains("LiquidStretchSampling()"))
        assertTrue(renderer.contains("stretchCoordinates.resolve(host, root, stretchSampling)"))
    }

    fun preparedModalsDelegateBoundedScrollAndStretchToTheEngine() {
        val presenter = SourceContract.read("ui/activity/MainActivity.kt")
            .after("stylePreparedSkinControls(container)").before("val density = resources.displayMetrics.density")
        assertTrue(presenter.contains("LumenPanelScroll.prepare(container)"))
        assertTrue(presenter.contains("installPreparedLiquidStretch(target)"))
        val bounded = SourceContract.read("ui/widget/MaxHeightScrollView.kt")
        assertTrue(bounded.contains("typealias MaxHeightScrollView = com.lumen.coacervation.engine.widget.LumenMaxHeightScrollView"))
    }

    fun mappingAndMaskShareTheRecordedFullTransform() {
        val renderer = LceMotionSource.engine("core/liquid/LiquidActivityRenderer.kt")
        assertTrue(renderer.contains("surfaceCoordinates.sourceToTarget(screenTransform, root, surfaceToBackdrop)"))
        assertTrue(renderer.contains("surfaceCoordinates.localToScreen(view, footprint.screenTransform)"))
        assertTrue(renderer.contains("SamplingMatrixMath.equal(entry.value.screenTransform, refreshSurfaceTransform)"))
        val feedback = LceMotionSource.engine("core/liquid/LiquidFeedbackSuppressor.kt")
        val mask = feedback.after("fun buildSuppressionMask(").before("fun sanitizeRealtimeCapture(")
        assertTrue(mask.contains("surfaceToCapture.setValues(footprint.screenTransform)"))
        assertFalse(mask.contains("Matrix()"))
        assertFalse(mask.contains("Path()"))
    }

    fun edgePullWithoutScrollSuppressesCaptureUntilTheExistingQuietRelease() {
        val renderer = LceMotionSource.engine("core/liquid/LiquidActivityRenderer.kt")
        val stretch = renderer.after("private fun onStretchDistanceChanged(").before("override fun onTrimMemory(")
        assertTrue(stretch.contains("stretchWindows.updateForScrollHost(scrollHost, distance, next, nextDir)"))
        assertTrue(stretch.contains("if (state.distance > 0f) suppressRealtimeSamplingWhileScrolling()"))
        val settle = renderer.after("private fun onScrollSettleCheck(").before("private fun clearScrollSuppression(")
        assertTrue(settle.contains("stretchOpticalIntensity > 1f"))
        assertTrue(settle.contains("LiquidRealtimeCapturePolicy.WAKE_SETTLE_FRAMES"))
        val viewport = LceMotionSource.engine("core/liquid/LiquidStretchViewport.kt")
        assertTrue(viewport.contains("if (nativeStretch) super.draw(canvas)"))
        assertTrue(stretch.contains("flushSurfaceRefresh(windowRoot)"))
        assertTrue(stretch.contains("if (windowRoot === boundRoot?.rootView)"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (LiquidMotionMappingContractTest) -> Unit) {
            val s = LiquidMotionMappingContractTest()
            block(s)
        }
        runIsolated { s -> s.modalControlsUseSharedSelectionAndStretchGeometry() }
        runIsolated { s -> s.preparedModalsDelegateBoundedScrollAndStretchToTheEngine() }
        runIsolated { s -> s.mappingAndMaskShareTheRecordedFullTransform() }
        runIsolated { s -> s.edgePullWithoutScrollSuppressesCaptureUntilTheExistingQuietRelease() }
    }
}
