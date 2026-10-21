package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top

import org.junit.Assert.*
import org.junit.Test

class HostTopTabCenteringPolicyTest {
    private fun plan(count: Int = 2, dock: Int = 600, left: Int = 0, right: Int = 540,
                     paddingLeft: Int = 0, paddingRight: Int = 0, stripLeft: Int = 0, stripRight: Int = 0,
                     marginLeft: Int = 0, marginRight: Int = 0, occupied: Int = 160) =
        HostTopTabCenteringPolicy.plan(count, dock, 0, 0, left, right, paddingLeft, paddingRight,
            stripLeft, stripRight, marginLeft, marginRight, occupied)

    @Test fun `one or two tabs center on full capsule while keeping the right action viewport`() {
        for (count in 1..2) {
            val result = requireNotNull(plan(count))
            assertEquals(HostTopTabCenteringPolicy.Plan(60, 0, 480), result)
            assertEquals(300, result.leftPadding + result.stripWidth / 2)
            assertEquals(540, result.leftPadding + result.stripWidth)
        }
    }
    @Test fun `larger tab counts and empty models restore native layout`() {
        for (count in listOf(0, 3, 4, 12)) assertNull(plan(count))
        assertNull(plan(occupied = 0))
    }
    @Test fun `measured action width is not assumed to be a fixed dp value`() {
        assertEquals(HostTopTabCenteringPolicy.Plan(96, 0, 408), plan(right = 504))
        assertEquals(HostTopTabCenteringPolicy.Plan(24, 0, 552), plan(right = 576))
        assertEquals(HostTopTabCenteringPolicy.Plan(0, 70, 460), plan(left = 70, right = 600))
    }
    @Test fun `existing viewport insets and strip padding stay outside the occupied label region`() {
        val result = requireNotNull(plan(left = 20, right = 540, paddingLeft = 8, paddingRight = 12,
            stripLeft = 18, stripRight = 6, marginLeft = 4, marginRight = 10))
        val stripStart = 20 + result.leftPadding + 4
        val labelCenter = stripStart + 18 + (result.stripWidth - 18 - 6) / 2
        assertEquals(300, labelCenter)
        assertTrue(result.leftPadding >= 8)
        assertTrue(result.rightPadding >= 12)
        assertTrue(stripStart + result.stripWidth + 10 <= 540 - 12)
    }
    @Test fun `wide labels or viewport entirely on one side do not overlap actions`() {
        assertNull(plan(occupied = 481))
        assertNull(plan(right = 260))
        assertNull(plan(left = 340, right = 600))
    }
    @Test fun `odd capsule dimensions center within half a pixel without cumulative drift`() {
        val result = requireNotNull(plan(dock = 601, right = 541))
        assertEquals(300.5f, result.leftPadding + result.stripWidth / 2f, 0f)
        assertEquals(result, plan(dock = 601, right = 541))
    }
    @Test fun `invalid or out of capsule bounds are rejected`() {
        assertNull(plan(left = -1))
        assertNull(plan(right = 601))
        assertNull(plan(paddingLeft = -1))
        assertNull(plan(left = 540, right = 540))
    }
    @Test fun `Compose placement shares the same full capsule center and fit protection`() {
        assertEquals(220, HostTopTabCenteringPolicy.centeredOffset(2, 600, 0, 540, 160))
        assertEquals(200, HostTopTabCenteringPolicy.centeredOffset(2, 600, 20, 540, 160))
        assertNull(HostTopTabCenteringPolicy.centeredOffset(3, 600, 0, 540, 160))
        assertNull(HostTopTabCenteringPolicy.centeredOffset(2, 600, 0, 540, 481))
    }
}
