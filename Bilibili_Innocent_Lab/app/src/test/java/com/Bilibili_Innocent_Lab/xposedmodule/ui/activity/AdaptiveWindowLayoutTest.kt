package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveWindowLayoutTest {
    @Test
    fun classifyCoversFoldableAndTabletWindowBreakpoints() {
        val coverPortrait = AdaptiveWindowLayout.classify(widthDp = 393, heightDp = 851)
        assertEquals(WindowWidthClass.COMPACT, coverPortrait.widthClass)
        assertEquals(WindowHeightClass.MEDIUM, coverPortrait.heightClass)

        val splitHalf = AdaptiveWindowLayout.classify(widthDp = 340, heightDp = 800)
        assertEquals(WindowWidthClass.COMPACT, splitHalf.widthClass)
        assertEquals(WindowHeightClass.MEDIUM, splitHalf.heightClass)

        val coverLandscape = AdaptiveWindowLayout.classify(widthDp = 700, heightDp = 380)
        assertEquals(WindowWidthClass.MEDIUM, coverLandscape.widthClass)
        assertEquals(WindowHeightClass.COMPACT, coverLandscape.heightClass)

        val foldableUnfolded = AdaptiveWindowLayout.classify(widthDp = 840, heightDp = 920)
        assertEquals(WindowWidthClass.EXPANDED, foldableUnfolded.widthClass)
        assertEquals(WindowHeightClass.EXPANDED, foldableUnfolded.heightClass)

        val tabletLandscape = AdaptiveWindowLayout.classify(widthDp = 1280, heightDp = 800)
        assertEquals(WindowWidthClass.EXPANDED, tabletLandscape.widthClass)
        assertEquals(WindowHeightClass.MEDIUM, tabletLandscape.heightClass)

        val clampedFromPx = AdaptiveWindowLayout.classifyPx(widthPx = -10, heightPx = 0, density = 0f)
        assertEquals(0, clampedFromPx.widthDp)
        assertEquals(0, clampedFromPx.heightDp)
        assertEquals(WindowWidthClass.COMPACT, clampedFromPx.widthClass)
        assertEquals(WindowHeightClass.COMPACT, clampedFromPx.heightClass)
    }

    @Test
    fun navigationChromeModeSwitchesBetweenBottomBarAndSideRailCleanly() {
        assertEquals(
            NavigationChromeMode.BOTTOM_BAR,
            AdaptiveWindowLayout.navigationChromeMode(AdaptiveWindowLayout.classify(393, 851))
        )
        assertEquals(
            NavigationChromeMode.SIDE_RAIL,
            AdaptiveWindowLayout.navigationChromeMode(AdaptiveWindowLayout.classify(600, 800))
        )
        assertEquals(
            NavigationChromeMode.SIDE_RAIL,
            AdaptiveWindowLayout.navigationChromeMode(AdaptiveWindowLayout.classify(840, 840))
        )
        // 横屏口袋手机 (>=520dp 宽且 340..479dp 高) 让出垂直空间给 SideRail
        assertEquals(
            NavigationChromeMode.SIDE_RAIL,
            AdaptiveWindowLayout.navigationChromeMode(AdaptiveWindowLayout.classify(540, 360))
        )
        // 极矮分屏 (<340dp 高) 退回 BottomBar 避免 SideRail 纵向挤扁
        assertEquals(
            NavigationChromeMode.BOTTOM_BAR,
            AdaptiveWindowLayout.navigationChromeMode(AdaptiveWindowLayout.classify(720, 320))
        )
    }

    @Test
    fun contentWidthsSidePaddingAndClearanceStayCenteredWithoutOverflow() {
        val compact = AdaptiveWindowLayout.classify(393, 851)
        assertEquals(Int.MAX_VALUE, AdaptiveWindowLayout.contentMaxWidthDp(compact))
        assertEquals(Int.MAX_VALUE, AdaptiveWindowLayout.contentMaxWidthPx(compact, density = 3f))
        assertEquals(45, AdaptiveWindowLayout.horizontalContentPaddingPx(1179, Int.MAX_VALUE, minSidePaddingPx = 45))

        val medium = AdaptiveWindowLayout.classify(700, 900)
        assertEquals(680, AdaptiveWindowLayout.contentMaxWidthDp(medium, multiColumn = false))
        assertEquals(760, AdaptiveWindowLayout.contentMaxWidthDp(medium, multiColumn = true))

        val expanded = AdaptiveWindowLayout.classify(960, 900)
        assertEquals(720, AdaptiveWindowLayout.contentMaxWidthDp(expanded, multiColumn = false))
        assertEquals(920, AdaptiveWindowLayout.contentMaxWidthDp(expanded, multiColumn = true))
        assertEquals(2160, AdaptiveWindowLayout.contentMaxWidthPx(expanded, density = 3f, multiColumn = false))
        assertEquals(360, AdaptiveWindowLayout.horizontalContentPaddingPx(2880, 2160, minSidePaddingPx = 45))
        // 视口窄于 maxWidthPx 时退回 minSidePaddingPx，不得出现负内边距或溢出
        assertEquals(45, AdaptiveWindowLayout.horizontalContentPaddingPx(1200, 2160, minSidePaddingPx = 45))

        assertEquals(0, AdaptiveWindowLayout.sideRailClearancePx(NavigationChromeMode.BOTTOM_BAR, 3f))
        assertEquals(294, AdaptiveWindowLayout.sideRailClearancePx(NavigationChromeMode.SIDE_RAIL, 3f))
    }

    @Test
    fun multiColumnGridSpanAndModalConstraintsFollowLceBounds() {
        assertEquals(1, AdaptiveWindowLayout.gridSpanCount(360, minColumnWidthDp = 280, maxSpans = 2))
        assertEquals(1, AdaptiveWindowLayout.gridSpanCount(500, minColumnWidthDp = 280, maxSpans = 2))
        assertEquals(2, AdaptiveWindowLayout.gridSpanCount(600, minColumnWidthDp = 280, maxSpans = 2))
        assertEquals(2, AdaptiveWindowLayout.gridSpanCount(1200, minColumnWidthDp = 280, maxSpans = 2))
        assertEquals(1, AdaptiveWindowLayout.gridSpanCount(900, minColumnWidthDp = 0, maxSpans = 2))

        // LCE 1.6.2 §15.4: 非明确定宽弹窗在大屏上不超过 560dp
        assertEquals(1680, AdaptiveWindowLayout.modalMaxWidthPx(windowWidthPx = 3000, density = 3f))
        assertEquals(960, AdaptiveWindowLayout.modalMaxWidthPx(windowWidthPx = 1152, density = 3f))

        // 弹窗滚动区受 capDp 与 windowFraction 双重限制，且极矮窗口下保持安全下限
        assertEquals(1200, AdaptiveWindowLayout.modalScrollMaxHeightPx(2800, 3f, capDp = 400, windowFraction = 0.55f))
        assertEquals(900, AdaptiveWindowLayout.modalScrollMaxHeightPx(1800, 3f, capDp = 520, windowFraction = 0.50f))
        assertEquals(360, AdaptiveWindowLayout.modalScrollMaxHeightPx(600, 3f, capDp = 520, windowFraction = 0.40f))
        assertEquals(240, AdaptiveWindowLayout.modalScrollMaxHeightPx(240, 3f, capDp = 520, windowFraction = 0.40f))

        // 气泡在 Compact 下 320dp、Medium/Expanded 下 360dp，极窄分屏下安全夹紧
        assertEquals(960f, AdaptiveWindowLayout.bubbleMaxWidthPx(usableWindowWidthPx = 1200, density = 3f), 0.5f)
        assertEquals(1080f, AdaptiveWindowLayout.bubbleMaxWidthPx(usableWindowWidthPx = 2400, density = 3f), 0.5f)
        assertTrue(AdaptiveWindowLayout.bubbleMaxWidthPx(usableWindowWidthPx = 900, density = 3f) <= 828f)
    }
}
