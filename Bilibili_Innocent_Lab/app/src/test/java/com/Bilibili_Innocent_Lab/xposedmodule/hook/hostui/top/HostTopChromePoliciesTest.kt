package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HostComposeTopTabCentering
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HostKhomeFloatPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HostKhomeTopFloat
import org.junit.Assert.*
import org.junit.Test

class HostTopChromePoliciesTest {
    private fun plan(
        count: Int = 2,
        dock: Int = 600,
        left: Int = 0,
        right: Int = 540,
        paddingLeft: Int = 0,
        paddingRight: Int = 0,
        stripLeft: Int = 0,
        stripRight: Int = 0,
        marginLeft: Int = 0,
        marginRight: Int = 0,
        occupied: Int = 160
    ) = HostTopTabCenteringPolicy.plan(
        count, dock, 0, 0, left, right, paddingLeft, paddingRight,
        stripLeft, stripRight, marginLeft, marginRight, occupied
    )

    @Test
    fun verifyCapsuleWidthTabCenteringAndFadePolicies() {
        // HostTopCapsuleWidthPolicy: phone widths keep their margin
        run {
            assertEquals(16f, HostTopCapsuleWidthPolicy.marginDp(393f, 16f, 0f), 0f)
            assertEquals(16f, HostTopCapsuleWidthPolicy.marginDp(599f, 16f, 0f), 0f)
            assertEquals(0f, HostTopCapsuleWidthPolicy.marginDp(393f, 0f, 0f), 0f)
        }

        // HostTopCapsuleWidthPolicy: wide windows cap and center the capsule
        run {
            val margin = HostTopCapsuleWidthPolicy.marginDp(1280f, 16f, 0f)
            assertEquals(HostTopCapsuleWidthPolicy.MAX_WIDTH_DP, 1280f - margin * 2f, 0.001f)
            assertEquals(20f, HostTopCapsuleWidthPolicy.marginDp(600f, 16f, 0f), 0f)
        }

        // HostTopCapsuleWidthPolicy: cutouts and side navigation widen the margin
        run {
            assertEquals((800f - 560f) / 2f, HostTopCapsuleWidthPolicy.marginDp(800f, 16f, 48f), 0f)
            assertEquals(200f + 4f, HostTopCapsuleWidthPolicy.marginDp(800f, 16f, 200f), 0f)
            assertEquals(30f + 4f, HostTopCapsuleWidthPolicy.marginDp(393f, 16f, 30f), 0f)
            assertEquals(24f + 4f, HostTopCapsuleWidthPolicy.marginDp(393f, 0f, 24f), 0f)
        }

        // HostTopCapsuleWidthPolicy: top capsule stays wider than largest capped bottom bar
        assertTrue(HostTopCapsuleWidthPolicy.MAX_WIDTH_DP > 5 * 96f + 8f)

        // HostTopTabCenteringPolicy: one or two tabs center on full capsule while keeping right action viewport
        for (count in 1..2) {
            val result = requireNotNull(plan(count))
            assertEquals(HostTopTabCenteringPolicy.Plan(60, 0, 480), result)
            assertEquals(300, result.leftPadding + result.stripWidth / 2)
            assertEquals(540, result.leftPadding + result.stripWidth)
        }

        // HostTopTabCenteringPolicy: larger tab counts and empty models restore native layout
        for (count in listOf(0, 3, 4, 12)) assertNull(plan(count))
        assertNull(plan(occupied = 0))

        // HostTopTabCenteringPolicy: measured action width is not assumed fixed dp
        assertEquals(HostTopTabCenteringPolicy.Plan(96, 0, 408), plan(right = 504))
        assertEquals(HostTopTabCenteringPolicy.Plan(24, 0, 552), plan(right = 576))
        assertEquals(HostTopTabCenteringPolicy.Plan(0, 70, 460), plan(left = 70, right = 600))

        // HostTopTabCenteringPolicy: existing viewport insets and strip padding stay outside occupied label region
        run {
            val result = requireNotNull(
                plan(left = 20, right = 540, paddingLeft = 8, paddingRight = 12, stripLeft = 18, stripRight = 6, marginLeft = 4, marginRight = 10)
            )
            val stripStart = 20 + result.leftPadding + 4
            val labelCenter = stripStart + 18 + (result.stripWidth - 18 - 6) / 2
            assertEquals(300, labelCenter)
            assertTrue(result.leftPadding >= 8)
            assertTrue(result.rightPadding >= 12)
            assertTrue(stripStart + result.stripWidth + 10 <= 540 - 12)
        }

        // HostTopTabCenteringPolicy: wide labels or viewport entirely on one side do not overlap actions
        assertNull(plan(occupied = 481))
        assertNull(plan(right = 260))
        assertNull(plan(left = 340, right = 600))

        // HostTopTabCenteringPolicy: odd capsule dimensions center within half pixel without drift
        run {
            val result = requireNotNull(plan(dock = 601, right = 541))
            assertEquals(300.5f, result.leftPadding + result.stripWidth / 2f, 0f)
            assertEquals(result, plan(dock = 601, right = 541))
        }

        // HostTopTabCenteringPolicy: invalid or out of capsule bounds are rejected
        assertNull(plan(left = -1))
        assertNull(plan(right = 601))
        assertNull(plan(paddingLeft = -1))
        assertNull(plan(left = 540, right = 540))

        // HostTopTabCenteringPolicy: Compose placement shares full capsule center and fit protection
        assertEquals(220, HostTopTabCenteringPolicy.centeredOffset(2, 600, 0, 540, 160))
        assertEquals(200, HostTopTabCenteringPolicy.centeredOffset(2, 600, 20, 540, 160))
        assertNull(HostTopTabCenteringPolicy.centeredOffset(3, 600, 0, 540, 160))
        assertNull(HostTopTabCenteringPolicy.centeredOffset(2, 600, 0, 540, 481))

        // HostTopTabCenteringPolicy: tabs stay put while collapsing AppBar grows actions padding frame by frame
        run {
            val density = 2.75f
            val dock = (411 * density).toInt()
            val expanded = dock - (54 * density).toInt()
            val content = (48 * density).toInt()
            val positions = (0..38).map { step ->
                val viewport = dock - ((54 + step) * density).toInt()
                HostTopTabCenteringPolicy.dockCenteredOffset(1, dock, 0, 0, expanded, viewport, content)
            }
            assertTrue(positions.all { it != null })
            assertEquals("One fixed position through the whole collapse", 1, positions.toSet().size)
            assertEquals(dock / 2f, positions.first()!! + content / 2f, 1f)
            val collapsedEdge = dock - (92 * density).toInt()
            assertEquals(
                positions.first(),
                HostTopTabCenteringPolicy.dockCenteredOffset(1, dock, 0, 0, collapsedEdge, expanded, content)
            )
        }

        // HostTopTabCenteringPolicy: dock centering declines what native layout must keep
        assertNull("Three tabs", HostTopTabCenteringPolicy.dockCenteredOffset(3, 1000, 0, 0, 900, 800, 100))
        assertNull("Centered tabs would reach the actions", HostTopTabCenteringPolicy.dockCenteredOffset(2, 1000, 0, 0, 900, 560, 200))
        assertNull("Viewport outside the capsule", HostTopTabCenteringPolicy.dockCenteredOffset(1, 1000, 100, 100, 1000, 950, 100))
        assertNull("Unmeasured viewport", HostTopTabCenteringPolicy.dockCenteredOffset(1, 1000, 0, 0, 900, 0, 100))
        assertEquals("Viewport offset inside the capsule is local", 400, HostTopTabCenteringPolicy.dockCenteredOffset(1, 1000, 50, 50, 900, 850, 100))

        // HostTopTabFadePolicy
        assertEquals(10, HostTopTabFadePolicy.endPadding(10, 900, 920))
        assertEquals(10 + 60, HostTopTabFadePolicy.endPadding(10, 980, 920))
        assertEquals(60, HostTopTabFadePolicy.endPadding(-4, 980, 920))
        assertEquals(1000 - (700 - 10 - 90), HostTopTabFadePolicy.maxScroll(1000, 700, 10, 90))
        assertEquals(0, HostTopTabFadePolicy.maxScroll(500, 700, 0, 90))
        assertEquals(0f, HostTopTabFadePolicy.leadingStrength(0, 48f), 0f)
        assertEquals(.5f, HostTopTabFadePolicy.leadingStrength(24, 48f), 0f)
        assertEquals(1f, HostTopTabFadePolicy.leadingStrength(400, 48f), 0f)
        assertEquals(1f, HostTopTabFadePolicy.trailingStrength(0, 300, 48f), 0f)
        assertEquals(.25f, HostTopTabFadePolicy.trailingStrength(288, 300, 48f), 0f)
        assertEquals(0f, HostTopTabFadePolicy.trailingStrength(300, 300, 48f), 0f)
        assertEquals(0f, HostTopTabFadePolicy.trailingStrength(0, 0, 48f), 0f)
        run {
            val end = 900f
            val radius = 60f
            assertEquals(end, HostTopTabArcShape.trailingBoundary(end, radius, 0f), 0f)
            assertEquals(end - radius + 48f, HostTopTabArcShape.trailingBoundary(end, radius, 36f), 0.001f)
            assertEquals(HostTopTabArcShape.trailingBoundary(end, radius, 36f), HostTopTabArcShape.trailingBoundary(end, radius, -36f), 0f)
            assertEquals(end - radius, HostTopTabArcShape.trailingBoundary(end, radius, 60f), 0f)
            assertEquals(end - radius, HostTopTabArcShape.trailingBoundary(end, radius, 90f), 0f)
            for (dy in listOf(-70f, -30f, 0f, 12f, 59f)) {
                val trailing = HostTopTabArcShape.trailingBoundary(500f, 40f, dy)
                assertEquals(500f - (trailing - 500f), HostTopTabArcShape.leadingBoundary(500f, 40f, dy), 0.001f)
            }
            assertEquals(500f, HostTopTabArcShape.trailingBoundary(500f, 0f, 20f), 0f)
            assertEquals(0f, HostTopTabFadePolicy.leadingStrength(10, 0f), 0f)
            assertEquals(0f, HostTopTabFadePolicy.trailingStrength(0, 10, 0f), 0f)
        }

        // Compose RTL fade direction and right-anchored centering
        run {
            val ltr = HostComposeTopTabCentering.resolveFadeLayout(
                tabLeft = 0, tabWidth = 800, safeLeft = 0, safeRight = 760,
                scrollValue = 0, scrollMax = 300, density = 2f, fontScale = 1f, isRtl = false
            )
            assertEquals(0f, ltr.start, 0f)
            assertEquals(760f - HostTopTabFadePolicy.ACTION_GAP_DP * 2f, ltr.end, 0.01f)
            assertEquals(0f, ltr.leading, 0f)
            assertEquals(1f, ltr.trailing, 0f)

            val rtl = HostComposeTopTabCentering.resolveFadeLayout(
                tabLeft = 0, tabWidth = 800, safeLeft = 120, safeRight = 800,
                scrollValue = 0, scrollMax = 300, density = 2f, fontScale = 1.25f, isRtl = true
            )
            assertEquals(120f + HostTopTabFadePolicy.ACTION_GAP_DP * 2f * 1.25f, rtl.start, 0.01f)
            assertEquals(800f, rtl.end, 0.01f)
            assertEquals(1f, rtl.leading, 0f)
            assertEquals(0f, rtl.trailing, 0f)

            val localX = HostComposeTopTabCentering.resolveCenteredOffset(
                count = 2, dockWidth = 1000, tabLeft = 100, tabWidth = 900,
                safeLeft = 100, safeRight = 1000, viewportWidth = 800, contentWidth = 200, isRtl = true
            )
            assertEquals(200, localX)
        }
    }

    @Test
    fun verifyTopBarInsetsFusionFadeAndKhomeChurnPolicies() {
        // topBarStatusInsetTrustsNonNullInsetsZeroAndUsesDisplayCutoutMax
        assertEquals(
            0,
            HostTopBarFxController.selectStatusBarInset(
                hasInsets = true, statusBarTop = 0, displayCutoutTop = 0,
                legacySystemWindowInsetTop = 0, resourceStatusBarHeight = 96, density = 3f, isAtLeastApi30 = true
            )
        )
        assertEquals(
            128,
            HostTopBarFxController.selectStatusBarInset(
                hasInsets = true, statusBarTop = 72, displayCutoutTop = 128,
                legacySystemWindowInsetTop = 72, resourceStatusBarHeight = 96, density = 3f, isAtLeastApi30 = true
            )
        )
        assertEquals(
            96,
            HostTopBarFxController.selectStatusBarInset(
                hasInsets = false, statusBarTop = 0, displayCutoutTop = 0, resourceStatusBarHeight = 96, density = 3f
            )
        )
        assertEquals(
            72,
            HostTopBarFxController.selectStatusBarInset(
                hasInsets = false, statusBarTop = 0, displayCutoutTop = 0, resourceStatusBarHeight = 0, density = 3f
            )
        )

        // topFusionFadeShrinksAndTracksLiveDensity
        val tall = HostTopFusionPolicy.bandHeight(240)
        val short = HostTopFusionPolicy.bandHeight(120)
        assertTrue(short < tall)
        val endNormalDensity = HostTopFusionPolicy.fadeEndFraction(160, density = 2f, statusBarInset = 60, bandHeight = tall)
        val endHighDensity = HostTopFusionPolicy.fadeEndFraction(160, density = 4f, statusBarInset = 60, bandHeight = tall)
        assertTrue(endHighDensity < endNormalDensity)

        // khomeChurnTrackerIgnoresTargetChangesAndTripsOnlyOnHostRollbacks
        val tracker = HostKhomeTopFloat.ChurnTracker(
            limit = HostKhomeFloatPolicy.CHURN_LIMIT,
            windowMs = HostKhomeFloatPolicy.CHURN_WINDOW_MS
        )
        assertFalse(tracker.record(targetOffset = 180, pageSignature = 11L, wroteLayout = true, nowMs = 100L))
        assertFalse(tracker.record(targetOffset = 210, pageSignature = 11L, wroteLayout = true, nowMs = 120L))
        assertFalse(tracker.record(targetOffset = 210, pageSignature = 22L, wroteLayout = true, nowMs = 140L))
        assertEquals(0, tracker.count)
        assertFalse(tracker.record(targetOffset = 210, pageSignature = 22L, wroteLayout = true, nowMs = 200L))
        assertEquals(1, tracker.count)
        assertFalse(tracker.record(targetOffset = 210, pageSignature = 22L, wroteLayout = true, nowMs = 2_000L))
        assertEquals(1, tracker.count)
        for (step in 2..HostKhomeFloatPolicy.CHURN_LIMIT) {
            assertFalse(tracker.record(targetOffset = 210, pageSignature = 22L, wroteLayout = true, nowMs = 2_010L + step * 10L))
        }
        assertTrue(tracker.record(targetOffset = 210, pageSignature = 22L, wroteLayout = true, nowMs = 2_150L))
    }
}
