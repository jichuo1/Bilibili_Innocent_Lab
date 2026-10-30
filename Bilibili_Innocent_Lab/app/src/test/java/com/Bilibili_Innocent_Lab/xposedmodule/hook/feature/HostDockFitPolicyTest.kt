package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostBottomBarFxConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostDockFitPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostDockClearance
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostDockCorner
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostDockWindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostDockFitPolicyTest {
    @Test fun transparentGestureHandleOnlyLiftsTheDockAboveTheHandle() {
        val edgeToEdge = HostDockClearance(navigationDp = 24f)
        assertEquals(24f + HostDockFitPolicy.HANDLE_GAP_DP, HostDockFitPolicy.bottomDp(edgeToEdge), 0f)
    }

    @Test fun aStripAlreadyBelowTheContainerKeepsASolidGapInsteadOfHuggingIt() {
        // 宿主或系统已让出白条：不再叠加导航栏高度，但不能贴着白条。
        val strip = HostDockClearance(reservedBelowDp = 48f, navigationDp = 48f, tappableDp = 48f)
        assertEquals(HostDockFitPolicy.SOLID_GAP_DP, HostDockFitPolicy.bottomDp(strip), 0f)
        val hostStrip = HostDockClearance(reservedBelowDp = 30f, navigationDp = 16f)
        assertEquals(HostDockFitPolicy.SOLID_GAP_DP, HostDockFitPolicy.bottomDp(hostStrip), 0f)
        val partial = HostDockClearance(reservedBelowDp = 10f, navigationDp = 24f)
        assertEquals(14f + HostDockFitPolicy.SOLID_GAP_DP, HostDockFitPolicy.bottomDp(partial), 0f)
    }

    @Test fun edgeToEdgeButtonNavigationClearsTheWholeBar() {
        val buttons = HostDockClearance(navigationDp = 48f, tappableDp = 48f)
        assertEquals(48f + HostDockFitPolicy.SOLID_GAP_DP, HostDockFitPolicy.bottomDp(buttons), 0f)
        assertEquals(HostDockFitPolicy.SOLID_GAP_DP, HostDockFitPolicy.bottomDp(HostDockClearance()), 0f)
    }

    @Test fun roomyScreensKeepTheConfiguredMarginAndFullSize() {
        val config = HostBottomBarFxConfig()
        val fit = HostDockFitPolicy.fit(config, 393f, 5, HostDockClearance())
        assertEquals(16f, fit.marginDp, 0f)
        assertEquals(config.heightDp, fit.heightDp, 0f)
        assertEquals(1f, fit.contentScale, 0f)
    }

    @Test fun cutoutsAndRoundedCornersWidenTheMarginSymmetrically() {
        val fit = HostDockFitPolicy.fit(HostBottomBarFxConfig(), 393f, 5, HostDockClearance(sideLeftDp = 20f, sideRightDp = 6f))
        assertEquals(20f + HostDockWindow.SIDE_GAP_DP, fit.marginDp, 0f)
    }

    @Test fun narrowScreensCompressMarginsBeforeScalingContent() {
        val config = HostBottomBarFxConfig()
        val needed = 5 * HostDockFitPolicy.LABELED_ITEM_DP + 8f
        val compressed = HostDockFitPolicy.fit(config, needed + 20f, 5, HostDockClearance())
        assertEquals(10f, compressed.marginDp, 0.001f)
        assertEquals(1f, compressed.contentScale, 0f)

        val scaled = HostDockFitPolicy.fit(config, 280f, 5, HostDockClearance())
        assertEquals(HostDockFitPolicy.MIN_MARGIN_DP, scaled.marginDp, 0f)
        assertEquals((280f - 16f) / needed, scaled.contentScale, 0.001f)
        assertEquals(config.heightDp * scaled.contentScale, scaled.heightDp, 0.001f)

        val clamped = HostDockFitPolicy.fit(config, 200f, 5, HostDockClearance())
        assertEquals(HostDockFitPolicy.MIN_LABELED_SCALE, clamped.contentScale, 0f)
        assertEquals(config.minHeightDp, clamped.heightDp, 0f)
    }

    @Test fun scaledTiersNeverOverlapSoLargeAndSmallBarsStayDistinct() {
        val normal = HostBottomBarFxConfig()
        val tiers = listOf(normal.copy(compact = true, iconOnly = true), normal.copy(iconOnly = true),
            normal.copy(compact = true), normal)
        for (tier in tiers) assertTrue(tier.minHeightDp < tier.heightDp)
        for ((smaller, larger) in tiers.zipWithNext()) {
            assertTrue(larger.minHeightDp - smaller.heightDp >= 2f)
        }
    }

    @Test fun wideWindowsCapTheCapsuleAndCenterIt() {
        val config = HostBottomBarFxConfig()
        val fit = HostDockFitPolicy.fit(config, 840f, 5, HostDockClearance())
        val width = 840f - fit.marginDp * 2f
        assertEquals(5 * HostDockFitPolicy.maxItemDp(config) + 8f, width, 0.001f)
        assertEquals(1f, fit.contentScale, 0f)
        assertEquals(config.heightDp, fit.heightDp, 0f)
        // 刚到超宽门槛、本来就窄于上限时不改动。
        assertEquals(16f, HostDockFitPolicy.fit(config.copy(), 600f, 6, HostDockClearance()).marginDp, 0f)
    }

    @Test fun phoneWidthsKeepTheirLayoutEvenWithFewTabs() {
        val config = HostBottomBarFxConfig()
        for (tabs in 2..5) assertEquals(16f, HostDockFitPolicy.fit(config, 393f, tabs, HostDockClearance()).marginDp, 0f)
        assertEquals(16f, HostDockFitPolicy.fit(config, 599f, 3, HostDockClearance()).marginDp, 0f)
    }

    @Test fun cappedTiersStayDistinctOnWideWindows() {
        val normal = HostBottomBarFxConfig()
        val widths = listOf(normal, normal.copy(compact = true), normal.copy(iconOnly = true),
            normal.copy(compact = true, iconOnly = true)).map { 1280f - HostDockFitPolicy.fit(it, 1280f, 5, HostDockClearance()).marginDp * 2f }
        for ((larger, smaller) in widths.zipWithNext()) assertTrue(larger > smaller)
    }

    @Test fun officialBarIsNeverResized() {
        val official = HostBottomBarFxConfig(liquidGlass = false)
        val fit = HostDockFitPolicy.fit(official, 240f, 5, HostDockClearance(sideLeftDp = 30f))
        assertEquals(official.heightDp, fit.heightDp, 0f)
        assertEquals(0f, fit.marginDp, 0f)
        assertEquals(1f, fit.contentScale, 0f)
    }

    @Test fun roundedCornerIntrusionFollowsTheArcBelowItsCenter() {
        assertEquals(0f, HostDockCorner.intrusion(100f, 1000f, 950f), 0f)
        assertEquals(0f, HostDockCorner.intrusion(100f, 1000f, 1000f), 0f)
        assertEquals(100f, HostDockCorner.intrusion(100f, 1000f, 1100f), 0.001f)
        assertEquals(100f - 80f, HostDockCorner.intrusion(100f, 1000f, 1060f), 0.001f)
    }
}
