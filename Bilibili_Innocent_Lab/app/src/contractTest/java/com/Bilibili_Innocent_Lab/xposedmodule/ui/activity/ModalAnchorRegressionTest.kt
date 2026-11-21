package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before

class ModalAnchorRegressionTest {
    private fun source(name: String): String {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/$name.kt"
        return LceMotionSource.read(path)
    }

    fun scannedAndUnscannedPanelsUseTheClickableSummaryNotTheEntireSettingsGroup() {
        // 原来是"从手填回退到规则编辑器为止"的窗口，实际覆盖的是**两个**入口：
        // 手填规则回退 + 组件选择弹窗，各取一次锚点，所以下面期望 2 次。
        // 改成按函数精确取这两个，既不依赖声明顺序，也不依赖它们还在哪个文件里。
        val editors = SettingsUiSource.function("showComponentManualRuleEditor") +
            SettingsUiSource.function("showComponentPickerDialog")
        assertEquals(2, Regex("val anchor = spec.summaryView\\(\\)").findAll(editors).count())
        assertFalse(editors.contains("it.parent"))
        assertFalse(editors.contains("parentOrNull"))
    }

    fun modalGeometryUsesVisibleBoundsAndRefreshesTheOriginBeforeReturning() {
        val origin = SettingsUiSource.function("modalAnchorBounds")
        assertTrue(origin.contains("getGlobalVisibleRect(visible)"))
        assertTrue(origin.contains("val sourceRoot = anchor.rootView"))
        assertTrue(origin.contains("sourceRoot.getLocationOnScreen(rootLocation)"))
        assertTrue(origin.contains("visible.offset(rootLocation[0], rootLocation[1])"))
        assertTrue(origin.contains("sourceRoot.scaleX != 1f"))
        assertTrue(origin.contains("!anchor.isShown"))
        assertTrue(origin.contains("visible.bottom.toFloat()"))
        // 这里钉的是"每次形变都重新解析来源"，不是某一行的写法：来源解析收进了
        // `resolveAnchorOnScreen()`（为了让"另一张弹窗里的 ⓘ"也能当锚点），
        // 但**实时 View 仍然优先、仍然每次重取**，缓存来源会让旋转后用上陈旧矩形。
        val present = SettingsUiSource.function("presentSizedModalDialog")
        assertTrue(present.contains("resolveAnchorOnScreen()?.let { currentAnchor ->"))
        assertTrue(present.contains("morphAnchor?.let(::modalAnchorBounds) ?: capturedAnchorBounds"))
    }

    fun visibleRootBoundsMapThroughScreenBeforeEnteringAnotherWindow() {
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

    fun rowToPanelGeometryNeverUsesTheMultiScreenGroupHeight() {
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

    fun titleCloseDoesNotReviveTheDialogTitleAndUsesOnlyOneNativeLayout() {
        val title = source("ModalTitleMotion")
        val closed = title.after("fun closed() {").before("fun dispose()")
        assertTrue(closed.contains("target.alpha = 0f"))
        assertFalse(closed.contains("target.alpha = targetAlpha"))
        val draw = title.after("override fun onDraw(").before("private fun stableTransform")
        assertEquals(1, Regex("layout.draw\\(this\\)").findAll(draw).count())
        assertFalse(draw.contains("drawText"))
        assertFalse(draw.contains("TextPaint"))
        assertTrue(title.contains("override fun hasOverlappingRendering(): Boolean = false"))
        assertTrue(draw.contains("size / sourceSize"))
        assertTrue(draw.contains("-layout.getLineBaseline(0)"))
    }

    fun nativeTitleGateAcceptsIdentityTransformationsButRejectsChangedTextAndMarquee() {
        assertTrue(ModalTitleMotionSpec.renderedTextMatches("首页推荐过滤", "首页推荐过滤"))
        assertTrue(ModalTitleMotionSpec.renderedTextMatches("Filter", "Filter"))
        assertFalse(ModalTitleMotionSpec.renderedTextMatches("Filter", "FILTER"))
        assertFalse(ModalTitleMotionSpec.renderedTextMatches("first\nsecond", "first second"))
        val title = source("ModalTitleMotion")
        assertTrue(title.contains("source.ellipsize == TextUtils.TruncateAt.MARQUEE"))
        assertTrue(title.contains("target.ellipsize == TextUtils.TruncateAt.MARQUEE"))
        assertFalse(title.contains("transformationMethod != null"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (ModalAnchorRegressionTest) -> Unit) {
            val s = ModalAnchorRegressionTest()
            block(s)
        }
        runIsolated { s -> s.scannedAndUnscannedPanelsUseTheClickableSummaryNotTheEntireSettingsGroup() }
        runIsolated { s -> s.modalGeometryUsesVisibleBoundsAndRefreshesTheOriginBeforeReturning() }
        runIsolated { s -> s.visibleRootBoundsMapThroughScreenBeforeEnteringAnotherWindow() }
        runIsolated { s -> s.rowToPanelGeometryNeverUsesTheMultiScreenGroupHeight() }
        runIsolated { s -> s.titleCloseDoesNotReviveTheDialogTitleAndUsesOnlyOneNativeLayout() }
        runIsolated { s -> s.nativeTitleGateAcceptsIdentityTransformationsButRejectsChangedTextAndMarquee() }
    }
}
