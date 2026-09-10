package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModalTitleHandoffTest {
    @Test fun sourceHandoffKeepsPositionBaselineAndSizeExactlyAtTheSource() {
        for (progress in listOf(-1f, 0f, .03f, .06f, .10f, .12f)) {
            val motion = ModalTitleMotionSpec.motionProgress(progress)
            assertEquals(0f, motion, 0f)
            assertEquals(37f, ModalTitleMotionSpec.interpolate(37f, 173f, motion), 0f)
            assertEquals(541f, ModalTitleMotionSpec.interpolate(541f, 297f, motion), 0f)
            assertEquals(16f, ModalTitleMotionSpec.interpolate(16f, 19f, motion), 0f)
        }
    }

    @Test fun targetHandoffOnlyStartsAfterPositionBaselineAndSizeHaveSettled() {
        for (progress in listOf(.85f, .90f, .925f, .99f, 1f, 2f)) {
            val motion = ModalTitleMotionSpec.motionProgress(progress)
            assertEquals(1f, motion, 0f)
            assertEquals(173f, ModalTitleMotionSpec.interpolate(37f, 173f, motion), 0f)
            assertEquals(297f, ModalTitleMotionSpec.interpolate(541f, 297f, motion), 0f)
            assertEquals(19f, ModalTitleMotionSpec.interpolate(16f, 19f, motion), 0f)
        }
    }

    @Test fun movementBetweenHandoffsIsMonotonicBoundedAndSmoothAtBothStops() {
        var previous = 0f
        for (step in 0..1000) {
            val motion = ModalTitleMotionSpec.motionProgress(step / 1000f)
            assertTrue(motion in 0f..1f)
            assertTrue(motion >= previous)
            previous = motion
        }
        assertEquals(.5f, ModalTitleMotionSpec.motionProgress((.12f + .85f) / 2f), .000001f)
        val epsilon = .0001f
        for (boundary in listOf(.12f, .85f)) {
            val before = ModalTitleMotionSpec.motionProgress(boundary - epsilon)
            val at = ModalTitleMotionSpec.motionProgress(boundary)
            val after = ModalTitleMotionSpec.motionProgress(boundary + epsilon)
            assertTrue(abs(at - before) / epsilon < .01f)
            assertTrue(abs(after - at) / epsilon < .01f)
        }
    }

    @Test fun nativeTitlesAndOverlayHaveComplementaryOwnershipWithoutAVisibilityGap() {
        for (step in -10..1010) {
            val progress = step / 1000f
            val source = ModalTitleMotionSpec.sourceWeight(progress)
            val target = ModalTitleMotionSpec.targetWeight(progress)
            val overlay = ModalTitleMotionSpec.overlayWeight(progress)
            assertTrue(source in 0f..1f && target in 0f..1f && overlay in 0f..1f)
            assertEquals("No frame may lose or double its logical title opacity", 1f,
                source + target + overlay, .000001f)
            assertEquals("Native titles at different locations must not appear together", 0f,
                source * target, 0f)
        }
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), weights(0f), 0f)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), weights(1f), 0f)
        assertArrayEquals(floatArrayOf(.5f, 0f, .5f), weights(.06f), .000001f)
        assertArrayEquals(floatArrayOf(0f, .5f, .5f), weights(.925f), .000001f)
    }

    @Test fun travelingTitleHasOneDrawingOwnerAndNativeTitlesStayHidden() {
        for (step in 120..850) {
            assertArrayEquals(floatArrayOf(0f, 0f, 1f), weights(step / 1000f), 0f)
        }
    }

    @Test fun handoffWeightsAreContinuousAndDoNotJumpAtTheTravelBoundaries() {
        for (boundary in listOf(0f, .12f, .85f, 1f)) {
            val before = weights(boundary - .00001f)
            val after = weights(boundary + .00001f)
            for (index in before.indices) assertTrue(abs(before[index] - after[index]) < .0001f)
        }
    }

    @Test fun interruptedEntryCloseAndGestureCancellationReuseTheSameTitleFrame() {
        for (start in listOf(.03f, .12f, .35f, .70f, .85f, .925f, .99f)) {
            for (target in listOf(0f, 1f)) {
                for (velocity in listOf(-4f, 0f, 4f)) {
                    val continuation = NavigationMotionContinuation(start, target, velocity, 240L)
                    assertArrayEquals(frame(start), frame(continuation.value(0f)), 0f)
                    assertArrayEquals(frame(target), frame(continuation.value(1f)), .000001f)
                }
            }
            // Visiting either endpoint must not select a different entry/exit title profile.
            val before = frame(start)
            frame(0f)
            frame(1f)
            assertArrayEquals(before, frame(start), 0f)
        }
    }

    @Test fun nativeLayoutOffsetsIncludePaddingLinePositionAndOwnScrollingExactlyOnce() {
        assertEquals(104f, ModalTitleMotionSpec.layoutOffset(100f, 12f, -3f, 5f), 0f)
        assertEquals(92.25f, ModalTitleMotionSpec.layoutOffset(80f, 8.5f, 7f, 3.25f), 0f)
        assertEquals(-43f, ModalTitleMotionSpec.layoutOffset(-60f, 12f, 9f, 4f), 0f)
        assertEquals(27f, ModalTitleMotionSpec.layoutOffset(0f, 5f, 26f, 4f), 0f)
    }

    @Test fun targetHandoffWaitsUntilBothContentProfilesAreOpaqueAndUntranslated() {
        val geometry = IconAnchoredMotionGeometry(
            collapsedBounds = SettingsBackupMotionRect(24f, 840f, 384f, 900f),
            expandedBounds = SettingsBackupMotionRect(32f, 160f, 368f, 780f),
            collapsedRadiusPx = 30f,
            expandedRadiusPx = 28f,
            contentTravelCapPx = 20f
        )
        val frame = IconAnchoredMotionFrameBuffer()
        for (timing in IconAnchoredContentTiming.entries) {
            for (progress in listOf(.85f, .90f, .925f, 1f)) {
                assertEquals(1f, IconAnchoredMotionSpec.contentFraction(progress, timing), 0f)
                IconAnchoredMotionSpec.fillFrame(frame, progress, geometry, timing)
                assertEquals(1f, frame.contentAlpha, 0f)
                assertEquals(0f, frame.contentTranslationXPx, 0f)
                assertEquals(0f, frame.contentTranslationYPx, 0f)
            }
        }
    }

    /**
     * 来源行把"标题 + \n + 摘要"塞进同一个 TextView 时，首行仍能配对。
     *
     * 回归："推荐标题关键词"这个填写面板的入口行文案与面板标题**完全同名**
     * （`home_recommend_title_rules` == `home_recommend_title_dialog_title`），
     * 但入口是 `标题 + "\n" + 摘要` 的单个 TextView（`ruleSummaryText` /
     * `ComponentPickerSurface.refreshSummary` 都这么拼），整段比较永远配不上，
     * 于是这类填写面板全都拿不到文字平移，只有容器形变。
     */
    @Test fun aMergedTitleAndSummaryRowStillPairsOnItsFirstLine() {
        assertTrue(ModalTitleMotionSpec.titleLineMatches("推荐标题关键词", "推荐标题关键词"))
        assertTrue(ModalTitleMotionSpec.titleLineMatches(
            "推荐标题关键词\n当前未配置关键词，点击编辑", "推荐标题关键词"))
        assertTrue(ModalTitleMotionSpec.titleLineMatches(
            "推荐标题关键词\n当前关键词：竖屏\n第三行", "推荐标题关键词"))
        // 放宽的只是"标题在哪"，不是配对的严格程度。
        assertFalse(ModalTitleMotionSpec.titleLineMatches("评论关键词", "编辑评论关键词"))
        assertFalse(ModalTitleMotionSpec.titleLineMatches("自定义首页组件", "首页组件隐藏规则"))
        assertFalse(ModalTitleMotionSpec.titleLineMatches("推荐标题关键词？\n摘要", "推荐标题关键词"))
        assertFalse(ModalTitleMotionSpec.titleLineMatches(" 推荐标题关键词\n摘要", "推荐标题关键词"))
        // 首行只是标题的前缀不算：必须紧跟换行。
        assertFalse(ModalTitleMotionSpec.titleLineMatches("推荐标题关键词过滤\n摘要", "推荐标题关键词"))
        assertFalse(ModalTitleMotionSpec.titleLineMatches("推荐标题关键词 摘要", "推荐标题关键词"))
        assertFalse(ModalTitleMotionSpec.titleLineMatches("", ""))
        assertFalse(ModalTitleMotionSpec.titleLineMatches("\n摘要", ""))
        // 严格的整段比较保持不变，仍是目标标题那一侧的判据。
        assertFalse(ModalTitleMotionSpec.matches("推荐标题关键词\n摘要", "推荐标题关键词"))
    }

    /** 首行必须取自 Layout 的真实行边界：软换行时首行不等于标题，那种行不该配对。 */
    @Test fun renderedFirstLineComesFromTheLayoutNotFromSplittingTheRawString() {
        val text = "推荐标题关键词\n当前未配置关键词，点击编辑"
        val hardBreak = text.indexOf('\n') + 1
        assertEquals("推荐标题关键词",
            ModalTitleMotionSpec.renderedTitleLine(text, 0, hardBreak))
        // 软换行：Layout 把一行折成两行，首行不含换行符也不等于标题。
        assertEquals("推荐标题关",
            ModalTitleMotionSpec.renderedTitleLine("推荐标题关键词", 0, 5))
        assertFalse(ModalTitleMotionSpec.matches(
            ModalTitleMotionSpec.renderedTitleLine("推荐标题关键词", 0, 5), "推荐标题关键词"))
        // 越界索引不得抛异常，动画降级成只做容器形变即可。
        assertEquals("", ModalTitleMotionSpec.renderedTitleLine("abc", 5, 2))
        assertEquals("abc", ModalTitleMotionSpec.renderedTitleLine("abc", -3, 99))
        assertEquals("", ModalTitleMotionSpec.renderedTitleLine("", 0, 0))
    }

    /** 只搬首行：摘要不能跟着飞，也不能被整段绘制带出来。 */
    @Test fun onlyTheFirstLineIsDrawnByTheTravelingOverlay() {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/ModalTitleMotion.kt"
        val code = sequenceOf(java.io.File(path), java.io.File("app/$path"))
            .first(java.io.File::isFile).readText()
        val draw = code.substringAfter("override fun onDraw(").substringBefore("private fun stableTransform")
        assertTrue(draw.contains("clipRect("))
        assertTrue(draw.contains("layout.getLineTop(0)"))
        assertTrue(draw.contains("layout.getLineBottom(0)"))
        // 裁剪必须发生在进入 layout 坐标系之后、绘制之前。
        assertTrue(draw.indexOf("-layout.getLineBaseline(0)") < draw.indexOf("clipRect("))
        assertTrue(draw.indexOf("clipRect(") < draw.indexOf("layout.draw(this)"))
        // 目标标题仍必须独占一行，来源才允许多行。
        assertTrue(code.contains("targetLayout.lineCount != 1 || layout.lineCount < 1"))
        assertTrue(code.contains("titleLineMatches(source.textToString(), title)"))
        assertTrue(code.contains("matches(target.textToString(), title)"))
    }

    private fun weights(progress: Float): FloatArray = floatArrayOf(
        ModalTitleMotionSpec.sourceWeight(progress),
        ModalTitleMotionSpec.targetWeight(progress),
        ModalTitleMotionSpec.overlayWeight(progress)
    )

    private fun frame(progress: Float): FloatArray = floatArrayOf(
        ModalTitleMotionSpec.motionProgress(progress),
        ModalTitleMotionSpec.sourceWeight(progress),
        ModalTitleMotionSpec.targetWeight(progress),
        ModalTitleMotionSpec.overlayWeight(progress)
    )
}
