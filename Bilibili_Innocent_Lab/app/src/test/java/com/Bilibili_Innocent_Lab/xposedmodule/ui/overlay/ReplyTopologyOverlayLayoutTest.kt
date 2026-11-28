package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyTopologyOverlayLayoutTest {

    @Test
    fun verifyAllCases() {
        ReplyTopologyBranchGeometryCases().verifyAllCases()
        ReplyTopologyCompactMotionSpecCases().verifyAllCases()
        ReplyTopologyLoadPriorityCases().verifyAllCases()
        ReplyTopologyOverlayParentCases().verifyAllCases()
        ReplyTopologyPanelThemeBlendCases().verifyAllCases()
        ReplyTopologyTrackLayoutCases().verifyAllCases()
        ReplyTopologyTreeViewportCases().verifyAllCases()
    }
}

private class ReplyTopologyBranchGeometryCases {
    fun aBranchTurnsFromTheCurrentRowTopToItsNodeCenter() {
        val curve = ReplyTopologyBranchGeometry()
        assertTrue(curve.update(101f, 113f, 730f, 834f))
        assertEquals(730f, curve.startY, 0f)
        assertEquals(782f, curve.endY, 0f)
        assertEquals(101f, curve.startX, 0f)
        assertEquals(113f, curve.endX, 0f)
    }

    fun scrollingTranslatesTheWholeCurveWithoutChangingItsShape() {
        val curve = ReplyTopologyBranchGeometry()
        curve.update(101f, 113f, 730f, 834f)
        val before = sample(curve)
        curve.update(101f, 113f, 659f, 763f)
        val after = sample(curve)
        before.zip(after).forEach { (first, second) ->
            assertEquals(first.first, second.first, 0.0001f)
            assertEquals(first.second - 71f, second.second, 0.0002f)
        }
    }

    fun curvesStayInsideTheirOwnRowsAndTheirTwoLaneCoordinates() {
        val curve = ReplyTopologyBranchGeometry()
        for ((top, bottom) in listOf(0f to 66f, 400f to 524f, -80f to 40f, 1_000f to 1_002f)) {
            for ((parentX, nodeX) in listOf(14f to 22f, 70f to 70f, 50f to 42f)) {
                assertTrue(curve.update(parentX, nodeX, top, bottom))
                sample(curve).forEach { (x, y) ->
                    assertTrue(x >= min(parentX, nodeX) - 0.001f && x <= max(parentX, nodeX) + 0.001f)
                    assertTrue(y >= top - 0.001f && y <= (top + bottom) * 0.5f + 0.001f)
                }
            }
        }
    }

    fun aCollapsedDeepLaneStaysVerticalAndDoesNotCreateADiagonal() {
        val curve = ReplyTopologyBranchGeometry()
        assertTrue(curve.update(70f, 70f, 400f, 500f))
        sample(curve).forEach { (x, _) -> assertEquals(70f, x, 0.0001f) }
    }

    fun successiveRowsCanReuseOneBufferWithoutKeepingTheOldOrigin() {
        val curve = ReplyTopologyBranchGeometry()
        curve.update(14f, 22f, 100f, 200f)
        curve.update(42f, 50f, 700f, 820f)
        assertEquals(42f, curve.startX, 0f)
        assertEquals(700f, curve.startY, 0f)
        assertEquals(760f, curve.control1Y, 0f)
        assertEquals(700f, curve.control2Y, 0f)
        assertEquals(50f, curve.endX, 0f)
        assertEquals(760f, curve.endY, 0f)
    }

    fun transientInvalidRowBoundsDoNotProduceACurve() {
        val curve = ReplyTopologyBranchGeometry()
        assertFalse(curve.update(14f, 22f, 100f, 100f))
        assertFalse(curve.update(14f, 22f, 200f, 100f))
        assertFalse(curve.update(14f, 22f, Float.NaN, 200f))
        assertFalse(curve.update(Float.POSITIVE_INFINITY, 22f, 100f, 200f))
    }

    private fun sample(curve: ReplyTopologyBranchGeometry): List<Pair<Float, Float>> = (0..20).map { step ->
        val t = step / 20f
        val u = 1f - t
        fun coordinate(start: Float, first: Float, second: Float, end: Float) =
            u * u * u * start + 3f * u * u * t * first + 3f * u * t * t * second + t * t * t * end
        coordinate(curve.startX, curve.control1X, curve.control2X, curve.endX) to
            coordinate(curve.startY, curve.control1Y, curve.control2Y, curve.endY)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologyBranchGeometryCases) -> Unit) {
            val s = ReplyTopologyBranchGeometryCases()
            block(s)
        }
        runIsolated { s -> s.aBranchTurnsFromTheCurrentRowTopToItsNodeCenter() }
        runIsolated { s -> s.scrollingTranslatesTheWholeCurveWithoutChangingItsShape() }
        runIsolated { s -> s.curvesStayInsideTheirOwnRowsAndTheirTwoLaneCoordinates() }
        runIsolated { s -> s.aCollapsedDeepLaneStaysVerticalAndDoesNotCreateADiagonal() }
        runIsolated { s -> s.successiveRowsCanReuseOneBufferWithoutKeepingTheOldOrigin() }
        runIsolated { s -> s.transientInvalidRowBoundsDoNotProduceACurve() }
    }
}

private class ReplyTopologyCompactMotionSpecCases {

    fun `compact height equals header plus status rows`() {
        val spec = ReplyTopologyCompactMotionSpec
        assertEquals(86, spec.COMPACT_HEIGHT_DP)
        assertEquals(
            spec.HEADER_HEIGHT_DP + spec.STATUS_HEIGHT_DP,
            spec.COMPACT_HEIGHT_DP
        )
        // 展开态固定行总高 = 标题 + 透明度行 + 状态行
        assertEquals(
            spec.HEADER_HEIGHT_DP + spec.OPACITY_ROW_HEIGHT_DP + spec.STATUS_HEIGHT_DP,
            120
        )
    }

    fun `height interpolation hits exact endpoints`() {
        val spec = ReplyTopologyCompactMotionSpec
        assertEquals(480, spec.heightAt(0f, 480, 86))
        assertEquals(86, spec.heightAt(1f, 480, 86))
        assertEquals(86, spec.heightAt(0f, 86, 480))
        assertEquals(480, spec.heightAt(1f, 86, 480))
    }

    fun `height interpolation is monotonic and clamped`() {
        val spec = ReplyTopologyCompactMotionSpec
        var previous = spec.heightAt(-0.5f, 86, 480)
        assertEquals(86, previous)
        for (step in 0..100) {
            val value = spec.heightAt(step / 100f, 86, 480)
            assertTrue("height regressed at $step", value >= previous)
            previous = value
        }
        assertEquals(480, previous)
    }

    fun `opacity row height shrinks with panel and keeps status row continuous`() {
        val spec = ReplyTopologyCompactMotionSpec
        // 折叠：透明度行 34 -> 1（插值下限 1px，终态残余由 GONE 消除）；展开：1 -> 34
        assertEquals(34, spec.heightAt(0f, spec.OPACITY_ROW_HEIGHT_DP, 0))
        assertEquals(1, spec.heightAt(1f, spec.OPACITY_ROW_HEIGHT_DP, 0))
        assertEquals(1, spec.heightAt(0f, 0, spec.OPACITY_ROW_HEIGHT_DP))
        assertEquals(spec.OPACITY_ROW_HEIGHT_DP, spec.heightAt(1f, 0, spec.OPACITY_ROW_HEIGHT_DP))
        // 同一进度下，状态行 y = header + opacityRow 高度：折叠端回到标题正下方（1px 残余），
        // 展开端为标题 + 满高，全程无跳变
        val headerPx = 100
        val yCollapsed = headerPx + spec.heightAt(1f, spec.OPACITY_ROW_HEIGHT_DP, 0)
        val yExpanded = headerPx + spec.heightAt(0f, spec.OPACITY_ROW_HEIGHT_DP, 0)
        assertEquals(headerPx + 1, yCollapsed)
        assertEquals(headerPx + spec.OPACITY_ROW_HEIGHT_DP, yExpanded)
    }

    fun `opacity row alpha is in phase with its height`() {
        val spec = ReplyTopologyCompactMotionSpec
        assertEquals(1f, spec.opacityRowAlphaAt(0f, collapsing = true), 1e-6f)
        assertEquals(0f, spec.opacityRowAlphaAt(1f, collapsing = true), 1e-6f)
        assertEquals(0.5f, spec.opacityRowAlphaAt(0.5f, collapsing = true), 1e-6f)
        assertEquals(0f, spec.opacityRowAlphaAt(0f, collapsing = false), 1e-6f)
        assertEquals(1f, spec.opacityRowAlphaAt(1f, collapsing = false), 1e-6f)
        assertEquals(0.5f, spec.opacityRowAlphaAt(0.5f, collapsing = false), 1e-6f)
    }

    fun `opacity row alpha and height stay monotonic across full progress`() {
        val spec = ReplyTopologyCompactMotionSpec
        for (collapsing in booleanArrayOf(true, false)) {
            var prevAlpha = spec.opacityRowAlphaAt(0f, collapsing)
            var prevHeight = spec.heightAt(0f, if (collapsing) 34 else 0, if (collapsing) 0 else 34)
            for (step in 0..100) {
                val progress = step / 100f
                val alpha = spec.opacityRowAlphaAt(progress, collapsing)
                val height = spec.heightAt(progress, if (collapsing) 34 else 0, if (collapsing) 0 else 34)
                assertTrue("alpha out of range", alpha in 0f..1f)
                if (collapsing) {
                    assertTrue("alpha regressed at $step", alpha <= prevAlpha)
                    assertTrue("height regressed at $step", height <= prevHeight)
                } else {
                    assertTrue("alpha regressed at $step", alpha >= prevAlpha)
                    assertTrue("height regressed at $step", height >= prevHeight)
                }
                prevAlpha = alpha
                prevHeight = height
            }
        }
    }

    fun `invalid height relations are rejected`() {
        val spec = ReplyTopologyCompactMotionSpec
        assertThrows(IllegalArgumentException::class.java) { spec.requireHeights(0, 86) }
        assertThrows(IllegalArgumentException::class.java) { spec.requireHeights(-10, 86) }
        assertThrows(IllegalArgumentException::class.java) { spec.requireHeights(480, 0) }
        assertThrows(IllegalArgumentException::class.java) { spec.requireHeights(86, 86) }
        assertThrows(IllegalArgumentException::class.java) { spec.requireHeights(80, 86) }
    }

    fun `valid height relation returns pair and expanded passthrough`() {
        val spec = ReplyTopologyCompactMotionSpec
        val heights = spec.requireHeights(480, 86)
        assertEquals(480, heights.first)
        assertEquals(86, heights.second)
        assertEquals(480, spec.requireExpandedHeight(480, 86))
    }

    fun `durations stay positive`() {
        val spec = ReplyTopologyCompactMotionSpec
        assertTrue(spec.COLLAPSE_DURATION_MS > 0L)
        assertTrue(spec.EXPAND_DURATION_MS > 0L)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologyCompactMotionSpecCases) -> Unit) {
            val s = ReplyTopologyCompactMotionSpecCases()
            block(s)
        }
        runIsolated { s -> s.`compact height equals header plus status rows`() }
        runIsolated { s -> s.`height interpolation hits exact endpoints`() }
        runIsolated { s -> s.`height interpolation is monotonic and clamped`() }
        runIsolated { s -> s.`opacity row height shrinks with panel and keeps status row continuous`() }
        runIsolated { s -> s.`opacity row alpha is in phase with its height`() }
        runIsolated { s -> s.`opacity row alpha and height stay monotonic across full progress`() }
        runIsolated { s -> s.`invalid height relations are rejected`() }
        runIsolated { s -> s.`valid height relation returns pair and expanded passthrough`() }
        runIsolated { s -> s.`durations stay positive`() }
    }
}

private class ReplyTopologyLoadPriorityCases {
    fun visibleRowsPrecedeNearestNeighboursInBothDirections() {
        assertArrayEquals(intArrayOf(40, 41, 42, 39, 43, 38, 44, 37, 45, 36, 46),
            ReplyTopologyLoadPriority.rows(10_000, 40, 42))
        assertArrayEquals(intArrayOf(80, 81, 79, 82, 78, 83, 77, 84, 76, 85),
            ReplyTopologyLoadPriority.rows(10_000, 80, 81))
    }

    fun aMovedViewportDropsOldRowsInsteadOfFinishingOldWorkFirst() {
        val old = ReplyTopologyLoadPriority.rows(10_000, 40, 42)
        val fresh = ReplyTopologyLoadPriority.rows(10_000, 900, 902)
        assertEquals(900, fresh.first())
        assertTrue(fresh.none { it in old })
    }

    fun edgesAndExtremeInputsStayBoundedAndNeverDuplicateRows() {
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4), ReplyTopologyLoadPriority.rows(5, 0, 2))
        assertArrayEquals(intArrayOf(3, 4, 2, 1, 0), ReplyTopologyLoadPriority.rows(5, 3, 4))
        val huge = ReplyTopologyLoadPriority.rows(Int.MAX_VALUE, 0, Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(96, huge.size)
        assertEquals(huge.size, huge.distinct().size)
        assertTrue(ReplyTopologyLoadPriority.rows(0, 0, 0).isEmpty())
        assertTrue(ReplyTopologyLoadPriority.rows(100, -1, -1).isEmpty())
        assertTrue(ReplyTopologyLoadPriority.rows(100, 100, 101).isEmpty())
    }

    fun horizontalNeighboursCannotBeClassifiedAsVisibleAndPartialCardsCan() {
        assertTrue(ReplyTopologyLoadPriority.intersects(-10.0, 20.0, 30.0, 80.0, 100.0, 100.0))
        assertFalse(ReplyTopologyLoadPriority.intersects(110.0, 20.0, 200.0, 80.0, 100.0, 100.0))
        assertFalse(ReplyTopologyLoadPriority.intersects(0.0, 110.0, 80.0, 200.0, 100.0, 100.0))
        assertFalse(ReplyTopologyLoadPriority.intersects(Double.NaN, 0.0, 30.0, 80.0, 100.0, 100.0))
        assertFalse(ReplyTopologyLoadPriority.intersects(0.0, 0.0, -1.0, 80.0, 100.0, 100.0))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologyLoadPriorityCases) -> Unit) {
            val s = ReplyTopologyLoadPriorityCases()
            block(s)
        }
        runIsolated { s -> s.visibleRowsPrecedeNearestNeighboursInBothDirections() }
        runIsolated { s -> s.aMovedViewportDropsOldRowsInsteadOfFinishingOldWorkFirst() }
        runIsolated { s -> s.edgesAndExtremeInputsStayBoundedAndNeverDuplicateRows() }
        runIsolated { s -> s.horizontalNeighboursCannotBeClassifiedAsVisibleAndPartialCardsCan() }
    }
}

private class ReplyTopologyOverlayParentCases {
    fun storyCommentDialogOwnsThePanelAboveItsContent() {
        val decor = Any()
        val content = Any()
        val commentDialog = Any()
        assertSame(commentDialog, replyTopologyOverlayParent(commentDialog, decor, content, true, true, true))
    }

    fun sameWindowCommentLayerIsNotTrappedBelowActivityContent() {
        val decor = Any()
        val content = Any()
        assertSame(decor, replyTopologyOverlayParent(decor, decor, content, true, true, true))
        assertSame(content, replyTopologyOverlayParent(null, decor, content, false, false, false))
    }

    fun detachedOrDifferentWindowSourceCannotFallBehindTheCommentDialog() {
        val decor = Any()
        val content = Any()
        assertNull(replyTopologyOverlayParent(Any(), decor, content, true, false, true))
        assertNull(replyTopologyOverlayParent(Any(), decor, content, true, true, false))
        assertNull(replyTopologyOverlayParent(null, decor, content, true, true, true))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologyOverlayParentCases) -> Unit) {
            val s = ReplyTopologyOverlayParentCases()
            block(s)
        }
        runIsolated { s -> s.storyCommentDialogOwnsThePanelAboveItsContent() }
        runIsolated { s -> s.sameWindowCommentLayerIsNotTrappedBelowActivityContent() }
        runIsolated { s -> s.detachedOrDifferentWindowSourceCannotFallBehindTheCommentDialog() }
    }
}

private class ReplyTopologyPanelThemeBlendCases {

    fun `blend endpoints return primary and secondary exactly`() {
        val spec = ReplyTopologyPanelTheme.Companion
        assertEquals(0xFF112233.toInt(), spec.blendColor(0xFF112233.toInt(), 0xFFAABBCC.toInt(), 0f))
        assertEquals(0xFFAABBCC.toInt(), spec.blendColor(0xFF112233.toInt(), 0xFFAABBCC.toInt(), 1f))
    }

    fun `blend midpoint averages channels`() {
        val spec = ReplyTopologyPanelTheme.Companion
        // 0x000000 与 0xFFFFFF 的中点是中灰
        assertEquals(0xFF808080.toInt(), spec.blendColor(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
    }

    fun `blend keeps primary alpha regardless of secondary alpha`() {
        val spec = ReplyTopologyPanelTheme.Companion
        val blended = spec.blendColor(0xFF101010.toInt(), 0x00F0F0F0.toInt(), 0.5f)
        assertEquals(0xFF, blended ushr 24 and 0xFF)
    }

    fun `blend fraction is clamped and matches author tint fraction`() {
        val spec = ReplyTopologyPanelTheme.Companion
        val fraction = ReplyTopologyPanelTheme.AUTHOR_TEXT_TOWARD_SECONDARY_FRACTION
        assertTrue(fraction in 0f..1f)
        val clampedLow = spec.blendColor(0xFF000000.toInt(), 0xFF0000FF.toInt(), -1f)
        assertEquals(spec.blendColor(0xFF000000.toInt(), 0xFF0000FF.toInt(), 0f), clampedLow)
        val clampedHigh = spec.blendColor(0xFF000000.toInt(), 0xFF0000FF.toInt(), 2f)
        assertEquals(spec.blendColor(0xFF000000.toInt(), 0xFF0000FF.toInt(), 1f), clampedHigh)
    }

    fun `dark theme author color is softened then tinted toward accent pink`() {
        val spec = ReplyTopologyPanelTheme.Companion
        // 暗色主题实值：primary E8E8E8 向 secondary B8BBC2 混 0.4 => D5D6D9，
        // 再向 accent FB7299 混 0.35 => E2B3C3
        val softened = spec.blendColor(0xFFE8E8E8.toInt(), 0xFFB8BBC2.toInt(), 0.4f)
        assertEquals(0xFFD5D6D9.toInt(), softened)
        val author = spec.blendColor(softened, 0xFFFB7299.toInt(), 0.35f)
        assertEquals(0xFFE2B3C3.toInt(), author)
    }

    fun `author color keeps visible separation from primary text`() {
        val spec = ReplyTopologyPanelTheme.Companion
        val author = spec.blendColor(
            spec.blendColor(0xFFE8E8E8.toInt(), 0xFFB8BBC2.toInt(), 0.4f),
            0xFFFB7299.toInt(),
            spec.AUTHOR_TEXT_TOWARD_ACCENT_FRACTION
        )
        val authorR = author shr 16 and 0xFF
        val authorG = author shr 8 and 0xFF
        // 粉调可辨识：红绿通道差显著（正文 E8E8E8 的红绿差为 0）
        assertTrue("author tint not visible: R-G=${authorR - authorG}", authorR - authorG >= 40)
        // alpha 保持不透明
        assertEquals(0xFF, author ushr 24 and 0xFF)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologyPanelThemeBlendCases) -> Unit) {
            val s = ReplyTopologyPanelThemeBlendCases()
            block(s)
        }
        runIsolated { s -> s.`blend endpoints return primary and secondary exactly`() }
        runIsolated { s -> s.`blend midpoint averages channels`() }
        runIsolated { s -> s.`blend keeps primary alpha regardless of secondary alpha`() }
        runIsolated { s -> s.`blend fraction is clamped and matches author tint fraction`() }
        runIsolated { s -> s.`dark theme author color is softened then tinted toward accent pink`() }
        runIsolated { s -> s.`author color keeps visible separation from primary text`() }
    }
}

private class ReplyTopologyTrackLayoutCases {
    fun ringsStayInsideTheReservedColumnForNarrowAndLargeFontWindows() {
        for (width in listOf(0f, 1f, 40f, 80f, 120f, 200f, 280f, 440f)) {
            for (density in listOf(0.75f, 1f, 2.75f, 4f)) {
                for (font in listOf(1f, 1.5f, 2.5f)) {
                    val layout = ReplyTopologyTrackLayout.resolve(width * density, density, font)
                    val outer = layout.ringRadius + layout.strokeWidth * 0.5f
                    for (depth in listOf(0, 1, 7, 100, 5_000, Int.MAX_VALUE)) {
                        assertTrue(layout.laneX(depth) - outer >= -0.001f)
                        assertTrue(layout.laneX(depth) + outer <= layout.trackWidth + 0.001f)
                    }
                    assertTrue(layout.trackWidth <= layout.width)
                    assertTrue(layout.maxLane in 0..7)
                }
            }
        }
    }

    fun narrowAndLargeFontLayoutsReduceCapacityBeforeTheyReduceTextSpace() {
        val normal = ReplyTopologyTrackLayout.resolve(280f, 1f, 1f)
        val narrow = ReplyTopologyTrackLayout.resolve(160f, 1f, 1f)
        val enlarged = ReplyTopologyTrackLayout.resolve(280f, 1f, 2f)
        assertEquals(7, normal.maxLane)
        assertTrue(narrow.maxLane < normal.maxLane)
        assertTrue(enlarged.maxLane < normal.maxLane)
        assertTrue(narrow.width - narrow.trackWidth >= narrow.width * 0.65f)
    }

    fun compressionIsOnlyAVisualDecisionAndHugeDepthDoesNotOverflow() {
        val layout = ReplyTopologyTrackLayout.resolve(280f, 1f, 1f)
        assertFalse(layout.compresses(7))
        assertTrue(layout.compresses(8))
        assertEquals(layout.laneX(8), layout.laneX(Int.MAX_VALUE), 0f)
    }

    fun panelSizeNeverExceedsAResizedParentEvenWhenMinimumIsLarger() {
        for (available in listOf(1, 80, 180, 280, 800)) {
            val result = ReplyTopologyPanelSizing.dimension(available, 1f, 0.86f, 280, 440)
            assertTrue(result in 1..available)
        }
        assertEquals(180, ReplyTopologyPanelSizing.dimension(180, 1f, 0.86f, 280, 440))
        assertTrue(ReplyTopologyPanelSizing.dimension(440, 1f, 0.86f, 280, 440) > 180)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologyTrackLayoutCases) -> Unit) {
            val s = ReplyTopologyTrackLayoutCases()
            block(s)
        }
        runIsolated { s -> s.ringsStayInsideTheReservedColumnForNarrowAndLargeFontWindows() }
        runIsolated { s -> s.narrowAndLargeFontLayoutsReduceCapacityBeforeTheyReduceTextSpace() }
        runIsolated { s -> s.compressionIsOnlyAVisualDecisionAndHugeDepthDoesNotOverflow() }
        runIsolated { s -> s.panelSizeNeverExceedsAResizedParentEvenWhenMinimumIsLarger() }
    }
}

private class ReplyTopologyTreeViewportCases {
    fun pinchKeepsTheWorldPointUnderTheFingersStationary() {
        val view = ReplyTopologyTreeViewport()
        view.pan(30.0, -60.0)
        val x = view.worldX(170.0)
        val y = view.worldY(210.0)
        assertTrue(view.zoom(2.3, 170.0, 210.0))
        assertEquals(x, view.worldX(170.0), 0.0000001)
        assertEquals(y, view.worldY(210.0), 0.0000001)
    }

    fun panningCanGoPastTheContentAndBackWithoutLayoutIntegerLimits() {
        val view = ReplyTopologyTreeViewport()
        assertTrue(view.pan(-9_000_000_000.0, 8_000_000_000.0))
        assertEquals(-9_000_000_000.0, view.x, 0.0)
        assertTrue(view.pan(9_000_000_000.0, -8_000_000_000.0))
        assertEquals(0.0, view.x, 0.0)
        assertEquals(0.0, view.y, 0.0)
    }

    fun entireDeepTreeCanFitAtOverviewScaleAndCoordinatesRemainFinite() {
        val view = ReplyTopologyTreeViewport()
        assertTrue(view.fit(1_400_000.0, 700_000.0, 400.0, 700.0, 10.0))
        assertTrue(view.scale < 0.01)
        assertTrue(view.screenX(1_400_000.0) <= 400.0)
        assertTrue(view.screenY(700_000.0) <= 700.0)
        assertTrue(view.zoom(1_000_000.0, 200.0, 300.0))
        assertEquals(ReplyTopologyTreeViewport.MAX_SCALE, view.scale, 0.0)
    }

    fun invalidGestureInputDoesNotCorruptTheExistingViewport() {
        val view = ReplyTopologyTreeViewport()
        view.pan(10.0, 20.0)
        assertFalse(view.pan(Double.NaN, 1.0))
        assertFalse(view.zoom(0.0, 50.0, 50.0))
        assertFalse(view.zoom(Double.NaN, 50.0, 50.0))
        assertFalse(view.place(Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0))
        assertEquals(10.0, view.x, 0.0)
        assertEquals(20.0, view.y, 0.0)
        assertEquals(1.0, view.scale, 0.0)
    }

    fun viewportRoundTripRemainsPreciseAroundLargeWorldCoordinates() {
        val view = ReplyTopologyTreeViewport()
        view.place(10_000_000.0, 8_000_000.0, 100.0, 200.0, 0.75)
        assertEquals(10_000_000.0, view.worldX(view.screenX(10_000_000.0)), 0.000001)
        assertEquals(8_000_000.0, view.worldY(view.screenY(8_000_000.0)), 0.000001)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologyTreeViewportCases) -> Unit) {
            val s = ReplyTopologyTreeViewportCases()
            block(s)
        }
        runIsolated { s -> s.pinchKeepsTheWorldPointUnderTheFingersStationary() }
        runIsolated { s -> s.panningCanGoPastTheContentAndBackWithoutLayoutIntegerLimits() }
        runIsolated { s -> s.entireDeepTreeCanFitAtOverviewScaleAndCoordinatesRemainFinite() }
        runIsolated { s -> s.invalidGestureInputDoesNotCorruptTheExistingViewport() }
        runIsolated { s -> s.viewportRoundTripRemainsPreciseAroundLargeWorldCoordinates() }
    }
}
