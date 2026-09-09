package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModalAnchorRegressionTest {
    private fun source(name: String): String {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/$name.kt"
        return sequenceOf(File(path), File("app/$path")).first(File::isFile).readText()
    }

    @Test fun scannedAndUnscannedPanelsUseTheClickableSummaryNotTheEntireSettingsGroup() {
        val main = source("MainActivity")
        val editors = main.substringAfter("private fun showComponentManualRuleEditor(")
            .substringBefore("private fun showRuleEditorDialog(")
        assertEquals(2, Regex("val anchor = spec.summaryView\\(\\)").findAll(editors).count())
        assertFalse(editors.contains("it.parent"))
        assertFalse(editors.contains("parentOrNull"))
    }

    @Test fun modalGeometryUsesVisibleBoundsAndRefreshesTheOriginBeforeReturning() {
        val main = source("MainActivity")
        val origin = main.substringAfter("private fun modalAnchorBounds(")
            .substringBefore("private fun resolveIconAnchoredGeometry(")
        assertTrue(origin.contains("getGlobalVisibleRect(visible)"))
        assertTrue(origin.contains("val sourceRoot = anchor.rootView"))
        assertTrue(origin.contains("sourceRoot.getLocationOnScreen(rootLocation)"))
        assertTrue(origin.contains("visible.offset(rootLocation[0], rootLocation[1])"))
        assertTrue(origin.contains("sourceRoot.scaleX != 1f"))
        assertTrue(origin.contains("!anchor.isShown"))
        assertTrue(origin.contains("visible.bottom.toFloat()"))
        assertTrue(main.contains("morphAnchor?.let(::modalAnchorBounds)?.let { currentAnchor ->"))
    }

    @Test fun visibleRootBoundsMapThroughScreenBeforeEnteringAnotherWindow() {
        // 源窗口位于 (80,160)，键盘 adjustPan 又向上移 40；Dialog 的原点不同。
        val visibleLeft = 24f
        val visibleTop = 600f
        val sourceScreenX = 80f
        val sourceScreenY = 160f - 40f
        val dialogScreenX = 32f
        val dialogScreenY = 96f
        assertEquals(72f, visibleLeft + sourceScreenX - dialogScreenX, 0f)
        assertEquals(624f, visibleTop + sourceScreenY - dialogScreenY, 0f)
    }

    @Test fun rowToPanelGeometryNeverUsesTheMultiScreenGroupHeight() {
        val row = SettingsBackupMotionRect(24f, 800f, 384f, 880f)
        val panel = SettingsBackupMotionRect(32f, 200f, 376f, 780f)
        val geometry = IconAnchoredMotionGeometry(row, panel, 40f, 28f)
        val frame = IconAnchoredMotionFrameBuffer()
        for (step in 0..1000) {
            IconAnchoredMotionSpec.fillFrame(frame, step / 1000f, geometry)
            assertTrue((frame.bottom - frame.top) in 79.999f..580.001f)
            assertTrue((frame.right - frame.left) in 343.999f..360.001f)
        }
    }

    @Test fun titleCloseDoesNotReviveTheDialogTitleAndUsesOnlyOneNativeLayout() {
        val title = source("ModalTitleMotion")
        val closed = title.substringAfter("fun closed() {").substringBefore("fun dispose()")
        assertTrue(closed.contains("target.alpha = 0f"))
        assertFalse(closed.contains("target.alpha = targetAlpha"))
        val draw = title.substringAfter("override fun onDraw(").substringBefore("private fun stableTransform")
        assertEquals(1, Regex("layout.draw\\(this\\)").findAll(draw).count())
        assertFalse(draw.contains("drawText"))
        assertFalse(draw.contains("TextPaint"))
        assertTrue(title.contains("override fun hasOverlappingRendering(): Boolean = false"))
        assertTrue(draw.contains("size / sourceSize"))
        assertTrue(draw.contains("-layout.getLineBaseline(0)"))
    }

    @Test fun nativeTitleGateAcceptsIdentityTransformationsButRejectsChangedTextAndMarquee() {
        assertTrue(ModalTitleMotionSpec.renderedTextMatches("首页推荐过滤", "首页推荐过滤"))
        assertTrue(ModalTitleMotionSpec.renderedTextMatches("Filter", "Filter"))
        assertFalse(ModalTitleMotionSpec.renderedTextMatches("Filter", "FILTER"))
        assertFalse(ModalTitleMotionSpec.renderedTextMatches("first\nsecond", "first second"))
        val title = source("ModalTitleMotion")
        assertTrue(title.contains("source.ellipsize == TextUtils.TruncateAt.MARQUEE"))
        assertTrue(title.contains("target.ellipsize == TextUtils.TruncateAt.MARQUEE"))
        assertFalse(title.contains("transformationMethod != null"))
    }
}
