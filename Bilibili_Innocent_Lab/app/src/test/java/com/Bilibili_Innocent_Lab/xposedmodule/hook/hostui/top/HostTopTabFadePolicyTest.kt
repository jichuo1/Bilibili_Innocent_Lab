package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top

import org.junit.Assert.assertEquals
import org.junit.Test

class HostTopTabFadePolicyTest {
    @Test fun aStripPastTheDividerGainsExactlyTheOverflowAsEndPadding() {
        assertEquals(10, HostTopTabFadePolicy.endPadding(10, 900, 920))
        assertEquals(10 + 60, HostTopTabFadePolicy.endPadding(10, 980, 920))
        assertEquals(60, HostTopTabFadePolicy.endPadding(-4, 980, 920))
    }

    @Test fun theScrollLimitExcludesBothPaddingsLikeTheNativeStrip() {
        // canScrollHorizontally 会少算 paddingRight；尾部这段仍要算作能滚。
        assertEquals(1000 - (700 - 10 - 90), HostTopTabFadePolicy.maxScroll(1000, 700, 10, 90))
        assertEquals(0, HostTopTabFadePolicy.maxScroll(500, 700, 0, 90))
    }

    @Test fun theFirstTabIsWholeUntilTheRowScrolls() {
        assertEquals(0f, HostTopTabFadePolicy.leadingStrength(0, 48f), 0f)
        assertEquals(.5f, HostTopTabFadePolicy.leadingStrength(24, 48f), 0f)
        assertEquals(1f, HostTopTabFadePolicy.leadingStrength(400, 48f), 0f)
    }

    @Test fun theLastTabIsWholeOnceTheRowReachesItsEnd() {
        assertEquals(1f, HostTopTabFadePolicy.trailingStrength(0, 300, 48f), 0f)
        assertEquals(.25f, HostTopTabFadePolicy.trailingStrength(288, 300, 48f), 0f)
        assertEquals(0f, HostTopTabFadePolicy.trailingStrength(300, 300, 48f), 0f)
        assertEquals(0f, HostTopTabFadePolicy.trailingStrength(0, 0, 48f), 0f)
    }

    @Test fun theBoundaryFollowsTheCapsuleEndCapInsteadOfAVerticalLine() {
        val end = 900f
        val radius = 60f
        // 行中线落在分割边缘，越靠上下越往里收，上下极点收到端帽圆心列。
        assertEquals(end, HostTopTabArcShape.trailingBoundary(end, radius, 0f), 0f)
        assertEquals(end - radius + 48f, HostTopTabArcShape.trailingBoundary(end, radius, 36f), 0.001f)
        assertEquals(HostTopTabArcShape.trailingBoundary(end, radius, 36f),
            HostTopTabArcShape.trailingBoundary(end, radius, -36f), 0f)
        assertEquals(end - radius, HostTopTabArcShape.trailingBoundary(end, radius, 60f), 0f)
        assertEquals(end - radius, HostTopTabArcShape.trailingBoundary(end, radius, 90f), 0f)
    }

    @Test fun theLeadingBoundaryMirrorsTheTrailingOne() {
        for (dy in listOf(-70f, -30f, 0f, 12f, 59f)) {
            val trailing = HostTopTabArcShape.trailingBoundary(500f, 40f, dy)
            assertEquals(500f - (trailing - 500f), HostTopTabArcShape.leadingBoundary(500f, 40f, dy), 0.001f)
        }
        assertEquals(500f, HostTopTabArcShape.trailingBoundary(500f, 0f, 20f), 0f)
    }

    @Test fun aZeroFadeLengthNeverMasks() {
        assertEquals(0f, HostTopTabFadePolicy.leadingStrength(10, 0f), 0f)
        assertEquals(0f, HostTopTabFadePolicy.trailingStrength(0, 10, 0f), 0f)
    }
}
