package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostBottomBarFxConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostDockBadgePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostDockFitPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostHomeCardSpacingSpec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostDockClearance
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostDockCorner
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostDockWindow
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostGarbColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSpringAxis
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopFusionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopIslandCompactGesture
import com.Bilibili_Innocent_Lab.xposedmodule.ui.interaction.ElasticSpringAxis
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostDockBadgePolicyTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    fun `only text badges are recognised by their resource name`() {
        assertTrue(HostDockBadgePolicy.isCountBadge("tab_badge_text"))
        assertTrue(HostDockBadgePolicy.isCountBadge("Tab_Badge"))
        // 其他 tab 内的文字与图标绝不能被当成角标染色。
        for (name in listOf("", "tab_text", "tab_title", "tab_icon", "tab_svg", "home_publish_icon", "container")) {
            assertFalse(name, HostDockBadgePolicy.isCountBadge(name))
        }
    }

    fun `the fill is the accent made opaque`() {
        assertEquals(0xFFFF6699.toInt(), HostDockBadgePolicy.style(0xFFFF6699.toInt()).fill)
        assertEquals(0xFF336699.toInt(), HostDockBadgePolicy.style(0x00336699).fill)
    }

    fun `the digits take whichever of black and white contrasts more with the accent`() {
        // 浅色强调色配黑字，深色强调色配白字，且选中的一定是对比更高的那个。
        assertEquals(black, HostDockBadgePolicy.onAccent(0xFFFFFFFF.toInt()))
        assertEquals(black, HostDockBadgePolicy.onAccent(0xFFFFEB3B.toInt()))
        assertEquals(white, HostDockBadgePolicy.onAccent(0xFF000000.toInt()))
        assertEquals(white, HostDockBadgePolicy.onAccent(0xFF1E3A8A.toInt()))
        for (accent in listOf(0xFFFF6699.toInt(), 0xFFFB7299.toInt(), 0xFF00A1D6.toInt(), 0xFF7E57C2.toInt(), 0xFFE53935.toInt())) {
            val chosen = HostDockBadgePolicy.onAccent(accent)
            val other = if (chosen == white) black else white
            assertTrue(HostGarbColors.contrast(chosen, accent) >= HostGarbColors.contrast(other, accent))
        }
    }

    fun `badge accent is off by default and independent from the other bottom bar options`() {
        assertFalse(HostBottomBarFxConfig().badgeAccent)
        val enabled = HostBottomBarFxConfig(badgeAccent = true)
        assertTrue(enabled.badgeAccent)
        // 开关不改变底栏几何：高度档位只取决于既有选项。
        assertEquals(HostBottomBarFxConfig().heightDp, enabled.heightDp, 0f)
        assertEquals(HostBottomBarFxConfig().minHeightDp, enabled.minHeightDp, 0f)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostDockBadgePolicyTest) -> Unit) {
            val s = HostDockBadgePolicyTest()
            block(s)
        }
        runIsolated { s -> s.`only text badges are recognised by their resource name`() }
        runIsolated { s -> s.`the fill is the accent made opaque`() }
        runIsolated { s -> s.`the digits take whichever of black and white contrasts more with the accent`() }
        runIsolated { s -> s.`badge accent is off by default and independent from the other bottom bar options`() }
    
        HostDockFitPolicyCases().verifyAllCases()
        HostGarbColorsCases().verifyAllCases()
        HostHomeCardSpacingSpecCases().verifyAllCases()
        HostPressStateModelCases().verifyAllCases()
        HostSpringAxisCases().verifyAllCases()
        HostTopFusionPolicyCases().verifyAllCases()
        HostTopIslandCompactGestureCases().verifyAllCases()
    }
}

private class HostDockFitPolicyCases {
    fun transparentGestureHandleOnlyLiftsTheDockAboveTheHandle() {
        val edgeToEdge = HostDockClearance(navigationDp = 24f)
        assertEquals(24f + HostDockFitPolicy.HANDLE_GAP_DP, HostDockFitPolicy.bottomDp(edgeToEdge), 0f)
    }

    fun aStripAlreadyBelowTheContainerKeepsASolidGapInsteadOfHuggingIt() {
        // 宿主或系统已让出白条：不再叠加导航栏高度，但不能贴着白条。
        val strip = HostDockClearance(reservedBelowDp = 48f, navigationDp = 48f, tappableDp = 48f)
        assertEquals(HostDockFitPolicy.SOLID_GAP_DP, HostDockFitPolicy.bottomDp(strip), 0f)
        val hostStrip = HostDockClearance(reservedBelowDp = 30f, navigationDp = 16f)
        assertEquals(HostDockFitPolicy.SOLID_GAP_DP, HostDockFitPolicy.bottomDp(hostStrip), 0f)
        val partial = HostDockClearance(reservedBelowDp = 10f, navigationDp = 24f)
        assertEquals(14f + HostDockFitPolicy.SOLID_GAP_DP, HostDockFitPolicy.bottomDp(partial), 0f)
    }

    fun edgeToEdgeButtonNavigationClearsTheWholeBar() {
        val buttons = HostDockClearance(navigationDp = 48f, tappableDp = 48f)
        assertEquals(48f + HostDockFitPolicy.SOLID_GAP_DP, HostDockFitPolicy.bottomDp(buttons), 0f)
        assertEquals(HostDockFitPolicy.SOLID_GAP_DP, HostDockFitPolicy.bottomDp(HostDockClearance()), 0f)
    }

    fun roomyScreensKeepTheConfiguredMarginAndFullSize() {
        val config = HostBottomBarFxConfig()
        val fit = HostDockFitPolicy.fit(config, 393f, 5, HostDockClearance())
        assertEquals(16f, fit.marginDp, 0f)
        assertEquals(config.heightDp, fit.heightDp, 0f)
        assertEquals(1f, fit.contentScale, 0f)
    }

    fun cutoutsAndRoundedCornersWidenTheMarginSymmetrically() {
        val fit = HostDockFitPolicy.fit(HostBottomBarFxConfig(), 393f, 5, HostDockClearance(sideLeftDp = 20f, sideRightDp = 6f))
        assertEquals(20f + HostDockWindow.SIDE_GAP_DP, fit.marginDp, 0f)
    }

    fun narrowScreensCompressMarginsBeforeScalingContent() {
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

    fun scaledTiersNeverOverlapSoLargeAndSmallBarsStayDistinct() {
        val normal = HostBottomBarFxConfig()
        val tiers = listOf(normal.copy(compact = true, iconOnly = true), normal.copy(iconOnly = true),
            normal.copy(compact = true), normal)
        for (tier in tiers) assertTrue(tier.minHeightDp < tier.heightDp)
        for ((smaller, larger) in tiers.zipWithNext()) {
            assertTrue(larger.minHeightDp - smaller.heightDp >= 2f)
        }
    }

    fun wideWindowsCapTheCapsuleAndCenterIt() {
        val config = HostBottomBarFxConfig()
        val fit = HostDockFitPolicy.fit(config, 840f, 5, HostDockClearance())
        val width = 840f - fit.marginDp * 2f
        assertEquals(5 * HostDockFitPolicy.maxItemDp(config) + 8f, width, 0.001f)
        assertEquals(1f, fit.contentScale, 0f)
        assertEquals(config.heightDp, fit.heightDp, 0f)
        // 刚到超宽门槛、本来就窄于上限时不改动。
        assertEquals(16f, HostDockFitPolicy.fit(config.copy(), 600f, 6, HostDockClearance()).marginDp, 0f)
    }

    fun phoneWidthsKeepTheirLayoutEvenWithFewTabs() {
        val config = HostBottomBarFxConfig()
        for (tabs in 2..5) assertEquals(16f, HostDockFitPolicy.fit(config, 393f, tabs, HostDockClearance()).marginDp, 0f)
        assertEquals(16f, HostDockFitPolicy.fit(config, 599f, 3, HostDockClearance()).marginDp, 0f)
    }

    fun cappedTiersStayDistinctOnWideWindows() {
        val normal = HostBottomBarFxConfig()
        val widths = listOf(normal, normal.copy(compact = true), normal.copy(iconOnly = true),
            normal.copy(compact = true, iconOnly = true)).map { 1280f - HostDockFitPolicy.fit(it, 1280f, 5, HostDockClearance()).marginDp * 2f }
        for ((larger, smaller) in widths.zipWithNext()) assertTrue(larger > smaller)
    }

    fun officialBarIsNeverResized() {
        val official = HostBottomBarFxConfig(liquidGlass = false)
        val fit = HostDockFitPolicy.fit(official, 240f, 5, HostDockClearance(sideLeftDp = 30f))
        assertEquals(official.heightDp, fit.heightDp, 0f)
        assertEquals(0f, fit.marginDp, 0f)
        assertEquals(1f, fit.contentScale, 0f)
    }

    fun roundedCornerIntrusionFollowsTheArcBelowItsCenter() {
        assertEquals(0f, HostDockCorner.intrusion(100f, 1000f, 950f), 0f)
        assertEquals(0f, HostDockCorner.intrusion(100f, 1000f, 1000f), 0f)
        assertEquals(100f, HostDockCorner.intrusion(100f, 1000f, 1100f), 0.001f)
        assertEquals(100f - 80f, HostDockCorner.intrusion(100f, 1000f, 1060f), 0.001f)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostDockFitPolicyCases) -> Unit) {
            val s = HostDockFitPolicyCases()
            block(s)
        }
        runIsolated { s -> s.transparentGestureHandleOnlyLiftsTheDockAboveTheHandle() }
        runIsolated { s -> s.aStripAlreadyBelowTheContainerKeepsASolidGapInsteadOfHuggingIt() }
        runIsolated { s -> s.edgeToEdgeButtonNavigationClearsTheWholeBar() }
        runIsolated { s -> s.roomyScreensKeepTheConfiguredMarginAndFullSize() }
        runIsolated { s -> s.cutoutsAndRoundedCornersWidenTheMarginSymmetrically() }
        runIsolated { s -> s.narrowScreensCompressMarginsBeforeScalingContent() }
        runIsolated { s -> s.scaledTiersNeverOverlapSoLargeAndSmallBarsStayDistinct() }
        runIsolated { s -> s.wideWindowsCapTheCapsuleAndCenterIt() }
        runIsolated { s -> s.phoneWidthsKeepTheirLayoutEvenWithFewTabs() }
        runIsolated { s -> s.cappedTiersStayDistinctOnWideWindows() }
        runIsolated { s -> s.officialBarIsNeverResized() }
        runIsolated { s -> s.roundedCornerIntrusionFollowsTheArcBelowItsCenter() }
    }
}

private class HostGarbColorsCases {
    fun transparentPixelsAndMonochromeFramesCannotBecomeAnAccent() {
        assertNull(HostGarbColors.dominant(intArrayOf(0x0000AAFF, 0xFFFFFFFF.toInt(), 0xFF000000.toInt())))
        assertFalse(HostGarbColors.chromatic(null))
        assertFalse(HostGarbColors.chromatic(0))
    }

    fun competingArtworkColorsKeepTheDominantHueInsteadOfAveragingToGrey() {
        val blue = 0xFF3366CC.toInt()
        val red = 0xFFCC6633.toInt()
        assertEquals(blue, HostGarbColors.dominant(intArrayOf(blue, blue, red, 0xFFFFFFFF.toInt())))
    }

    fun garbSurfacesUseSevenPercentTintAndRetainDayNightSemantics() {
        val accent = 0xFF6E89FF.toInt()
        for (dark in listOf(false, true)) {
            val plain = HostChromeColors(dark, accent).palette()
            val themed = HostChromeColors(dark, accent, accent).palette()
            assertEquals(HostGarbColors.blend(plain.surface, accent, .07f), themed.surface)
            assertEquals(plain.background, themed.background)
            assertEquals(plain.textPrimary, themed.textPrimary)
            assertTrue(HostGarbColors.contrast(themed.primary, themed.surface) >= 4.5)
            assertTrue(HostGarbColors.contrast(themed.onPrimary, themed.primary) >= 4.5)
        }
    }

    fun darkBlueAndPaleYellowRemainReadableInBothModes() {
        for (color in listOf(0xFF050B35.toInt(), 0xFFFFF7A0.toInt(), 0xFF00A090.toInt())) {
            for (dark in listOf(false, true)) {
                val palette = HostChromeColors(dark, color, color).palette()
                assertTrue(HostGarbColors.contrast(palette.primary, palette.surface) >= 4.5)
            }
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostGarbColorsCases) -> Unit) {
            val s = HostGarbColorsCases()
            block(s)
        }
        runIsolated { s -> s.transparentPixelsAndMonochromeFramesCannotBecomeAnAccent() }
        runIsolated { s -> s.competingArtworkColorsKeepTheDominantHueInsteadOfAveragingToGrey() }
        runIsolated { s -> s.garbSurfacesUseSevenPercentTintAndRetainDayNightSemantics() }
        runIsolated { s -> s.darkBlueAndPaleYellowRemainReadableInBothModes() }
    }
}

private class HostHomeCardSpacingSpecCases {
    fun defaultsAndUnsupportedLayoutsPreserveOriginalSpacing() {
        val defaults = HostHomeCardSpacingSpec()
        assertFalse(defaults.enabled)
        assertNull(defaults.horizontalMargin(0, true, 0, 0, 3f))
        assertNull(defaults.verticalMargin(0, true, 0, 3f))
        val spacing = HostHomeCardSpacingSpec(8, 12)
        assertNull(spacing.horizontalMargin(-1, true, 0, 0, 3f))
        assertNull(spacing.verticalMargin(-1, true, 0, 3f))
        assertEquals(defaults, HostHomeCardSpacingSpec(-2, 41).normalized())
    }

    fun twoColumnsHaveExactGapIncludingOddPixelsAndNativeDecorations() {
        val spacing = HostHomeCardSpacingSpec(7, 9)
        val density = 2.75f
        repeat(100) {
            val rightOfFirst = spacing.horizontalMargin(0, false, 0, 12, density)!!
            val leftOfSecond = spacing.horizontalMargin(1, true, 0, 15, density)!!
            assertEquals(19, rightOfFirst + 12 + leftOfSecond + 15)
            val top = spacing.verticalMargin(0, true, 20, density)!!
            val bottom = spacing.verticalMargin(0, false, 17, density)!!
            assertEquals(24, top + 20 + bottom + 17)
            assertEquals(19, spacing.horizontalMargin(0, true, 10, 5, density)!! + 10 + 5)
            assertEquals(19, spacing.horizontalMargin(1, false, 10, 5, density)!! + 10 + 5)
        }
    }

    fun zeroGapOverridesNativeWhitespaceAndAxesRemainIndependent() {
        val spacing = HostHomeCardSpacingSpec(0, 40)
        assertEquals(-30, spacing.horizontalMargin(2, true, 20, 10, 3f))
        assertEquals(60, spacing.verticalMargin(2, true, 0, 3f))
        val verticalOnly = HostHomeCardSpacingSpec(verticalDp = 10)
        assertTrue(verticalOnly.enabled)
        assertNull(verticalOnly.horizontalMargin(1, false, 10, 10, 3f))
        assertEquals(15, verticalOnly.verticalMargin(2, false, 0, 3f))
    }

    fun threeAndFourColumnTabletMiddleColumnsKeepSymmetricGap() {
        val spacing = HostHomeCardSpacingSpec(10, 12)
        val density = 2f // gapH = 20, gapV = 24
        val leftRight = spacing.horizontalMargin(0, left = false, padding = 8, decoration = 2, density = density)!! + 2
        val midLeft = spacing.horizontalMargin(3, left = true, padding = 8, decoration = 4, density = density)!! + 4
        val midRight = spacing.horizontalMargin(3, left = false, padding = 8, decoration = 4, density = density)!! + 4
        val rightLeft = spacing.horizontalMargin(1, left = true, padding = 8, decoration = 2, density = density)!! + 2
        assertEquals(20, leftRight + midLeft)
        assertEquals(20, midRight + leftRight) // middle-to-middle in 4-col grid
        assertEquals(20, midRight + rightLeft)
        assertEquals(24, spacing.verticalMargin(3, top = true, decoration = 3, density = density)!! + 3 +
            spacing.verticalMargin(3, top = false, decoration = 5, density = density)!! + 5)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostHomeCardSpacingSpecCases) -> Unit) {
            val s = HostHomeCardSpacingSpecCases()
            block(s)
        }
        runIsolated { s -> s.defaultsAndUnsupportedLayoutsPreserveOriginalSpacing() }
        runIsolated { s -> s.twoColumnsHaveExactGapIncludingOddPixelsAndNativeDecorations() }
        runIsolated { s -> s.zeroGapOverridesNativeWhitespaceAndAxesRemainIndependent() }
        runIsolated { s -> s.threeAndFourColumnTabletMiddleColumnsKeepSymmetricGap() }
    }
}

private class HostPressStateModelCases {
    private class ViewPressModel(private val tapTimeoutMs: Long = 100L) {
        private var prepressed = false
        private var pressed = false
        private var tapCheckAt = Long.MAX_VALUE
        var clicks = 0

        /** ACTION_DOWN，控件位于滚动容器内（ViewGroup.shouldDelayChildPressedState 默认 true）。 */
        fun down(t: Long) {
            prepressed = true
            pressed = false
            tapCheckAt = t + tapTimeoutMs
        }

        private fun advance(t: Long) {
            if (tapCheckAt <= t) { // CheckForTap 运行
                if (prepressed) { prepressed = false; pressed = true }
                tapCheckAt = Long.MAX_VALUE
            }
        }

        /**
         * cancelLongPress() = removeLongPressCallback() + removeTapCallback()：先让时间走到 [t]
         * （此前到期的 CheckForTap 已经把控件置为 PRESSED），再清掉仍存在的 PREPRESSED 并撤掉 CheckForTap。
         */
        fun cancelLongPress(t: Long) {
            advance(t)
            prepressed = false
            tapCheckAt = Long.MAX_VALUE
        }

        /** ACTION_UP。 */
        fun up(t: Long) {
            advance(t)
            if (pressed || prepressed) clicks += 1
            pressed = false; prepressed = false
        }
    }

    fun aQuickTapClicksWhenNobodyTouchesThePressState() {
        for (held in listOf(30L, 60L, 99L, 100L, 250L)) {
            val v = ViewPressModel(); v.down(0L); v.up(held)
            assertEquals("held=$held", 1, v.clicks)
        }
    }

    fun cancelLongPressBeforeUpSilentlyDropsClicksShorterThanTheTapTimeout() {
        // 模块在 UP 的 before 钩子里清理：与 UP 同一时刻调用 cancelLongPress。
        val quick = ViewPressModel(); quick.down(0L); quick.cancelLongPress(60L); quick.up(60L)
        assertEquals("<100ms 的快点按被 cancelLongPress 吞掉", 0, quick.clicks)
        val edge = ViewPressModel(); edge.down(0L); edge.cancelLongPress(99L); edge.up(99L)
        assertEquals(0, edge.clicks)
    }

    fun slowerTapsSurviveCancelLongPressWhichIsWhyTheFailureLooksRandom() {
        // 同一个控件、同一段代码：按压时长跨过 tapTimeout 就“灵”，没跨过就“不灵”。
        val results = listOf(40L, 80L, 100L, 120L, 200L).map { held ->
            val v = ViewPressModel(); v.down(0L); v.cancelLongPress(held); v.up(held); v.clicks
        }
        assertEquals(listOf(0, 0, 1, 1, 1), results)
    }

    fun theFixedCleanupPathLeavesTheHostPressStateAlone() {
        // 修复后终止事件路径只撤销模块自己的 Runnable：任何时长的点按都点击一次，且 UP 之后不留残余。
        for (held in listOf(20L, 60L, 90L, 110L, 300L)) {
            val v = ViewPressModel(); v.down(0L); v.up(held)
            assertEquals("held=$held", 1, v.clicks)
        }
        val v = ViewPressModel(); v.down(0L); v.up(60L)
        assertFalse("按压结束后没有额外点击", v.clicks > 1)
        assertTrue(v.clicks == 1)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostPressStateModelCases) -> Unit) {
            val s = HostPressStateModelCases()
            block(s)
        }
        runIsolated { s -> s.aQuickTapClicksWhenNobodyTouchesThePressState() }
        runIsolated { s -> s.cancelLongPressBeforeUpSilentlyDropsClicksShorterThanTheTapTimeout() }
        runIsolated { s -> s.slowerTapsSurviveCancelLongPressWhichIsWhyTheFailureLooksRandom() }
        runIsolated { s -> s.theFixedCleanupPathLeavesTheHostPressStateAlone() }
    }
}

private class HostSpringAxisCases {
    fun retargetingKeepsPositionAndVelocityContinuousAndEventuallySettles() {
        val axis = HostSpringAxis()
        repeat(8) { axis.advance(1f / 60f, 1f) }
        val position = axis.value
        val velocity = axis.velocity
        axis.advance(0f, 0f)
        assertEquals(position, axis.value, .00001f)
        assertEquals(velocity, axis.velocity, .00001f)
        repeat(180) { axis.advance(1f / 60f, 0f) }
        assertTrue(axis.atRest(0f, .001f))
    }

    fun configurableStiffnessAndResetKeepTheAdapterReusable() {
        val normal = HostSpringAxis()
        val slow = HostSpringAxis()
        repeat(10) { normal.advance(1f / 60f, 1f); slow.advance(1f / 60f, 1f, 115f, .84f) }
        assertTrue(slow.value < normal.value)
        slow.reset(.4f)
        assertEquals(.4f, slow.value, 0f)
        assertEquals(0f, slow.velocity, 0f)
        repeat(180) { slow.advance(1f / 60f, 1f) }
        assertTrue(slow.atRest(1f, .001f))
    }

    fun migratedTrajectoriesMatchTheOriginalIncludingReversalsAndRestThresholds() {
        for (hz in listOf(60, 90, 120)) {
            val original = ElasticSpringAxis()
            val migrated = HostSpringAxis()
            for ((target, stiffness, damping) in listOf(
                Triple(1f, 115f, .84f), Triple(0f, 88f, .68f),
                Triple(1f, 420f, .8f), Triple(0f, 420f, .8f))) {
                repeat(hz / 3) {
                    original.advance(1f / hz, target, stiffness, damping)
                    migrated.advance(1f / hz, target, stiffness, damping)
                    assertEquals("position at $hz Hz", original.value, migrated.value, .00001f)
                    assertEquals("velocity at $hz Hz", original.velocity, migrated.velocity, .0001f)
                    assertEquals(original.atRest(target, .5f / 871f), migrated.atRest(target, .5f / 871f))
                }
            }
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostSpringAxisCases) -> Unit) {
            val s = HostSpringAxisCases()
            block(s)
        }
        runIsolated { s -> s.retargetingKeepsPositionAndVelocityContinuousAndEventuallySettles() }
        runIsolated { s -> s.configurableStiffnessAndResetKeepTheAdapterReusable() }
        runIsolated { s -> s.migratedTrajectoriesMatchTheOriginalIncludingReversalsAndRestThresholds() }
    }
}

private class HostTopFusionPolicyCases {

    private val density = 2.75f
    private val statusBar = 90
    private val basePadding = 154 // 顶栏胶囊行 44dp + 上下各 6dp，440dpi

    /** 首页收起态：顶栏容器顶边 = 状态栏下沿。 */
    private val collapsedTop = statusBar

    /** 首页展开态实测值。 */
    private val expandedTop = 244

    fun dockingReservesTheWholeHeaderAtTheTopAndNoneAwayFromIt() {
        val range = HostTopFusionPolicy.DOCK_RANGE_DP * density
        assertEquals(1f, HostTopFusionPolicy.dockingProgress(0f, range), 0f)
        assertEquals(1f, HostTopFusionPolicy.dockingProgress(-80f, range), 0f)
        assertEquals(0.5f, HostTopFusionPolicy.dockingProgress(range / 2f, range), 1e-6f)
        assertEquals(0f, HostTopFusionPolicy.dockingProgress(range, range), 0f)
        assertEquals(0f, HostTopFusionPolicy.dockingProgress(Float.POSITIVE_INFINITY, range), 0f)
    }

    fun dockingFollowsDistanceContinuouslyInBothDirectionsWithoutEndpointJumps() {
        val range = HostTopFusionPolicy.DOCK_RANGE_DP * density
        var previous = 1f
        for (i in 0..256) {
            val distance = range * i / 256f
            val progress = HostTopFusionPolicy.dockingProgress(distance, range)
            assertTrue(progress <= previous)
            assertTrue(previous - progress < 0.006f)
            assertEquals(1f - progress, HostTopFusionPolicy.dockingProgress(range - distance, range), 1e-6f)
            previous = progress
        }
        // 两端一像素手势只带来亚像素占位变化，返回/离开顶部不会突然跳一截。
        assertTrue((1f - HostTopFusionPolicy.dockingProgress(1f, range)) * expandedTop < 0.01f)
        assertTrue(HostTopFusionPolicy.dockingProgress(range - 1f, range) * expandedTop < 0.01f)
    }

    fun fadeEndsAboveTheRestingContentSoTheFirstRowStaysSharp() {
        for (containerTop in listOf(collapsedTop, expandedTop)) {
            val restTop = HostTopFusionPolicy.contentRestTop(containerTop, basePadding)
            val height = HostTopFusionPolicy.bandHeight(restTop)
            val hold = HostTopFusionPolicy.holdFraction(statusBar, height)
            val end = HostTopFusionPolicy.fadeEndFraction(restTop, density, statusBar, height)

            val gap = HostTopFusionPolicy.CONTENT_GAP_DP * density
            assertTrue("收口必须在内容静止顶边之上: 容器 $containerTop, 收口 ${end * height}, 顶边 $restTop", end * height <= restTop - gap + 1f)
            // 静止顶边处的权重严格为 0：第一排卡片停在静止位置时完全清晰。
            assertEquals(0f, HostTopFusionPolicy.fadeWeight(restTop / height.toFloat(), hold, end), 1e-6f)
            assertEquals(1f, HostTopFusionPolicy.fadeWeight(0f, hold, end), 0f)
        }
    }

    fun holdKeepsTheWholeStatusBarAtFullStrength() {
        val restTop = HostTopFusionPolicy.contentRestTop(collapsedTop, basePadding)
        val height = HostTopFusionPolicy.bandHeight(restTop)
        val hold = HostTopFusionPolicy.holdFraction(statusBar, height)
        assertEquals(statusBar.toFloat(), hold * height, 1f)
        // 状态栏下沿到渐隐收口之间是干净的单调递减，没有任何满强度平台或跳变。
        val end = HostTopFusionPolicy.fadeEndFraction(restTop, density, statusBar, height)
        assertTrue("渐隐必须有实际长度", end - hold > 0.1f)
    }

    fun bandAlwaysCoversTheFadeEnd() {
        for (containerTop in listOf(0, collapsedTop, expandedTop, 600)) {
            val restTop = HostTopFusionPolicy.contentRestTop(containerTop, basePadding)
            val height = HostTopFusionPolicy.bandHeight(restTop)
            val end = HostTopFusionPolicy.fadeEndFraction(restTop, density, statusBar, height)
            assertTrue("收口必须落在带内: $restTop", end <= 1f)
            assertTrue("带高必须盖过静止顶边", height >= restTop)
        }
    }

    fun degenerateGeometryStaysSane() {
        assertEquals(1f, HostTopFusionPolicy.holdFraction(statusBar, 0), 0f)
        assertEquals(1f, HostTopFusionPolicy.fadeEndFraction(0, density, statusBar, 0), 0f)
        // 状态栏比带子还高时满强度区被夹到整个带子，不会出现越界或倒挂。
        val hold = HostTopFusionPolicy.holdFraction(1000, 100)
        assertEquals(1f, hold, 0f)
        val end = HostTopFusionPolicy.fadeEndFraction(50, density, 1000, 100)
        assertTrue(end >= hold)
    }

    fun fadeWeightHoldsThenDecaysMonotonicallyToZero() {
        val hold = 0.2f
        val end = 0.9f
        assertEquals(1f, HostTopFusionPolicy.fadeWeight(0f, hold, end), 0f)
        assertEquals(1f, HostTopFusionPolicy.fadeWeight(hold, hold, end), 0f)
        assertEquals(0f, HostTopFusionPolicy.fadeWeight(end, hold, end), 1e-6f)
        assertEquals(0f, HostTopFusionPolicy.fadeWeight(1f, hold, end), 0f)

        var previous = 1f
        var fraction = 0f
        while (fraction <= 1f) {
            val weight = HostTopFusionPolicy.fadeWeight(fraction, hold, end)
            assertTrue("单调 @ $fraction", weight <= previous + 1e-6f)
            // C¹ 收口：两端导数都为 0，与上下两侧相接时才不会有折线。
            assertTrue("无跳变 @ $fraction", previous - weight < 0.03f)
            previous = weight
            fraction += 1f / 256f
        }
        assertEquals(0f, previous, 1e-6f)
    }

    fun fadeWeightIsASmoothstepSymmetricAboutTheMidpoint() {
        val hold = 0f
        val end = 1f
        assertEquals(0.5f, HostTopFusionPolicy.fadeWeight(0.5f, hold, end), 1e-6f)
        for (u in listOf(0.1f, 0.25f, 0.4f)) {
            assertEquals(
                HostTopFusionPolicy.fadeWeight(u, hold, end),
                1f - HostTopFusionPolicy.fadeWeight(1f - u, hold, end),
                1e-6f
            )
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostTopFusionPolicyCases) -> Unit) {
            val s = HostTopFusionPolicyCases()
            block(s)
        }
        runIsolated { s -> s.dockingReservesTheWholeHeaderAtTheTopAndNoneAwayFromIt() }
        runIsolated { s -> s.dockingFollowsDistanceContinuouslyInBothDirectionsWithoutEndpointJumps() }
        runIsolated { s -> s.fadeEndsAboveTheRestingContentSoTheFirstRowStaysSharp() }
        runIsolated { s -> s.holdKeepsTheWholeStatusBarAtFullStrength() }
        runIsolated { s -> s.bandAlwaysCoversTheFadeEnd() }
        runIsolated { s -> s.degenerateGeometryStaysSane() }
        runIsolated { s -> s.fadeWeightHoldsThenDecaysMonotonicallyToZero() }
        runIsolated { s -> s.fadeWeightIsASmoothstepSymmetricAboutTheMidpoint() }
    }
}

private class HostTopIslandCompactGestureCases {
    fun tapJitterRemainsAClick() {
        val gesture = HostTopIslandCompactGesture(8f, 12f)
        gesture.move(3f, 2f)
        assertTrue(gesture.canClick())
    }

    fun bothHorizontalDirectionsExpandWithoutClicking() {
        for (dx in listOf(-14f, 14f)) {
            val gesture = HostTopIslandCompactGesture(8f, 12f)
            assertEquals(HostTopIslandCompactGesture.Decision.EXPAND, gesture.move(dx, 4f))
            assertFalse(gesture.canClick())
        }
    }

    fun draggingBackToTheStartDoesNotRefresh() {
        val gesture = HostTopIslandCompactGesture(8f, 12f)
        gesture.move(10f, 0f)
        gesture.move(0f, 0f)
        assertFalse(gesture.canClick())
    }

    fun verticalDragAndMultitouchCannotExpandOrClick() {
        for (vertical in listOf(true, false)) {
            val gesture = HostTopIslandCompactGesture(8f, 12f)
            if (vertical) gesture.move(2f, 20f) else gesture.cancel()
            assertEquals(HostTopIslandCompactGesture.Decision.CANCEL, gesture.move(40f, 0f))
            assertFalse(gesture.canClick())
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostTopIslandCompactGestureCases) -> Unit) {
            val s = HostTopIslandCompactGestureCases()
            block(s)
        }
        runIsolated { s -> s.tapJitterRemainsAClick() }
        runIsolated { s -> s.bothHorizontalDirectionsExpandWithoutClicking() }
        runIsolated { s -> s.draggingBackToTheStartDoesNotRefresh() }
        runIsolated { s -> s.verticalDragAndMultitouchCannotExpandOrClick() }
    }
}
