package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModalMotionRefinementTest {
    @Test fun bubbleAxesHaveIndependentProfilesAndExactEndpoints() {
        for (entry in listOf(true, false)) {
            assertEquals(0f, BubbleMotionSpec.scaleX(0f, entry), 0f)
            assertEquals(0f, BubbleMotionSpec.scaleY(0f, entry), 0f)
            assertEquals(1f, BubbleMotionSpec.scaleX(1f, entry), 0f)
            assertEquals(1f, BubbleMotionSpec.scaleY(1f, entry), 0f)
            assertTrue(BubbleMotionSpec.scaleX(.5f, entry) > BubbleMotionSpec.scaleY(.5f, entry))
        }
    }

    @Test fun entryHasNoOvershootAndSettlesPrecisely() {
        var maxX = 0f
        var maxY = 0f
        for (step in 0..1000) {
            val x = BubbleMotionSpec.scaleX(step / 1000f, true)
            val y = BubbleMotionSpec.scaleY(step / 1000f, true)
            assertTrue(x in 0f..1f)
            assertTrue(y in 0f..1f)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
        }
        assertEquals(1f, maxX, 0f)
        assertEquals(1f, maxY, 0f)
        assertTrue(BubbleMotionSpec.ENTER_DURATION_MS in 240L..250L)
    }

    @Test fun settledCloseShrinksBothAxesWithoutRebound() {
        var lastX = 1f
        var lastY = 1f
        for (step in 1000 downTo 0) {
            val x = BubbleMotionSpec.scaleX(step / 1000f, false)
            val y = BubbleMotionSpec.scaleY(step / 1000f, false)
            assertTrue(x <= lastX && y <= lastY)
            assertTrue(x in 0f..1f && y in 0f..1f)
            lastX = x
            lastY = y
        }
    }

    @Test fun axisProfilesRemainContinuousAcrossGrowthAndReboundBoundaries() {
        for (entry in listOf(true, false)) {
            for (boundary in listOf(.60f, .70f, .78f, .86f, 1f)) {
                assertTrue(kotlin.math.abs(BubbleMotionSpec.scaleX(boundary - .00001f, entry) -
                    BubbleMotionSpec.scaleX(boundary + .00001f, entry)) < .0001f)
                assertTrue(kotlin.math.abs(BubbleMotionSpec.scaleY(boundary - .00001f, entry) -
                    BubbleMotionSpec.scaleY(boundary + .00001f, entry)) < .0001f)
            }
        }
    }

    @Test fun matchingTitlesCanMoveButRelatedDifferentTitlesCannot() {
        assertTrue(ModalTitleMotionSpec.matches("首页推荐过滤", "首页推荐过滤"))
        assertFalse(ModalTitleMotionSpec.matches(" Portrait content filters ", "Portrait content filters"))
        assertFalse(ModalTitleMotionSpec.matches("自定义首页组件", "隐藏首页组件规则"))
        assertFalse(ModalTitleMotionSpec.matches("首页推荐过滤\n已选择 2 项", "首页推荐过滤"))
        assertFalse(ModalTitleMotionSpec.matches("首页推荐过滤", "首页推荐过滤？"))
        assertFalse(ModalTitleMotionSpec.matches("", ""))
    }

    @Test fun titleBaselineAndSizeInterpolationHasExactClampedEndpoints() {
        assertEquals(24f, ModalTitleMotionSpec.interpolate(24f, 160f, 0f), 0f)
        assertEquals(160f, ModalTitleMotionSpec.interpolate(24f, 160f, 1f), 0f)
        assertEquals(92f, ModalTitleMotionSpec.interpolate(24f, 160f, .5f), 0f)
        assertEquals(24f, ModalTitleMotionSpec.interpolate(24f, 160f, -1f), 0f)
        assertEquals(160f, ModalTitleMotionSpec.interpolate(24f, 160f, 2f), 0f)
    }

    private fun source(name: String): String {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/$name.kt"
        return sequenceOf(File(path), File("app/$path")).first(File::isFile).readText()
    }

    @Test fun missingSnapshotUsesSameAnchoredRuleEditorWithoutChangingSelectionRules() {
        // 这两个窗口原来靠"到下一个函数为止"划界，已经被搬迁悄悄撑破过一次：
        // showRecommendVideoDurationRangeDialog 外移后分隔符失配，substringBefore
        // 返回整段剩余源码，断言变成在半份文件里找字符串——照样通过，护栏没了。
        val fallback = SettingsUiSource.function("showComponentManualRuleEditor")
        assertTrue(fallback.contains("spec.summaryView()"))
        assertTrue(fallback.contains("spec.currentRules(), anchor"))
        val editor = SettingsUiSource.function("showRuleEditorDialog")
        assertTrue(editor.contains("presentModalDialog(dialog, container, anchor)"))
        assertFalse(fallback.contains("remove("))
        assertFalse(fallback.contains("clear("))
    }

    @Test fun titleOverlayIsOptionalRestoredAndNeverReflowsPerFrame() {
        val title = source("ModalTitleMotion")
        // 目标标题必须独占一行；来源允许是"标题 \n 摘要"的合成 TextView，
        // 由渲染后的首行复核（见 ModalTitleHandoffTest 的首行配对用例）。
        assertTrue(title.contains("targetLayout.lineCount != 1"))
        assertTrue(title.contains("renderedTitleLine("))
        assertTrue(title.contains("getEllipsisCount(0) != 0"))
        // 来源行只淡文字颜色，**不能**动 View 的 alpha：那会把 ripple 一起变透明并冻结它的
        // 动画，等形变结束才补播一次高光（见 ModalTitleHandoffTest 的 ripple 用例）。
        assertFalse(title.contains("source.alpha ="))
        assertTrue(title.contains("sourceTextColors.withAlpha("))
        assertTrue(title.contains("source.setTextColor(sourceTextColors)"))
        assertTrue(title.contains("target.alpha = targetAlpha"))
        val draw = title.substringAfter("override fun onDraw(").substringBefore("companion object")
        for (forbidden in listOf("requestLayout", "Bitmap", "find(", "TextPaint(", "textSize =")) {
            assertFalse(forbidden, draw.contains(forbidden))
        }
        val controller = source("IconAnchoredMotionController")
        assertTrue(controller.contains("titleMotion?.prepare(0f)"))
        assertTrue(controller.contains("titleMotion?.prepare(expansion)"))
        assertTrue(controller.contains("titleMotion?.apply(clamped)"))
        assertTrue(controller.contains("titleMotion?.expanded()"))
        assertTrue(controller.contains("titleMotion?.closed()"))
        assertTrue(controller.contains("titleMotion?.dispose()"))
        assertEquals(1, Regex("NavigationMotionPolicy.remainingDuration\\(").findAll(controller).count())
    }

    @Test fun reversalKeepsEntryShapeUntilStableEndpoint() {
        val controller = source("BubbleMotionController")
        val close = controller.substringAfter("fun requestClose(").substringBefore("fun handleWindowSizeChange")
        assertFalse(close.contains("entryShape ="))
        assertTrue(controller.contains("layer.applyFrame(clamped, entryShape)"))
        val layer = source("BubblePanelLayer")
        assertTrue(layer.contains("BubbleMotionSpec.scaleX(progress, entryShape)"))
        assertTrue(layer.contains("BubbleMotionSpec.scaleY(progress, entryShape)"))
    }
}
