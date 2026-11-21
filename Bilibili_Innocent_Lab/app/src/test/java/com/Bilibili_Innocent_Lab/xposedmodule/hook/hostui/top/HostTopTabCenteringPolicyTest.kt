package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top

import org.junit.Assert.*
import org.junit.Test

class HostTopTabCenteringPolicyTest {
    private fun plan(count: Int = 2, dock: Int = 600, left: Int = 0, right: Int = 540,
                     paddingLeft: Int = 0, paddingRight: Int = 0, stripLeft: Int = 0, stripRight: Int = 0,
                     marginLeft: Int = 0, marginRight: Int = 0, occupied: Int = 160) =
        HostTopTabCenteringPolicy.plan(count, dock, 0, 0, left, right, paddingLeft, paddingRight,
            stripLeft, stripRight, marginLeft, marginRight, occupied)

    fun `one or two tabs center on full capsule while keeping the right action viewport`() {
        for (count in 1..2) {
            val result = requireNotNull(plan(count))
            assertEquals(HostTopTabCenteringPolicy.Plan(60, 0, 480), result)
            assertEquals(300, result.leftPadding + result.stripWidth / 2)
            assertEquals(540, result.leftPadding + result.stripWidth)
        }
    }
    fun `larger tab counts and empty models restore native layout`() {
        for (count in listOf(0, 3, 4, 12)) assertNull(plan(count))
        assertNull(plan(occupied = 0))
    }
    fun `measured action width is not assumed to be a fixed dp value`() {
        assertEquals(HostTopTabCenteringPolicy.Plan(96, 0, 408), plan(right = 504))
        assertEquals(HostTopTabCenteringPolicy.Plan(24, 0, 552), plan(right = 576))
        assertEquals(HostTopTabCenteringPolicy.Plan(0, 70, 460), plan(left = 70, right = 600))
    }
    fun `existing viewport insets and strip padding stay outside the occupied label region`() {
        val result = requireNotNull(plan(left = 20, right = 540, paddingLeft = 8, paddingRight = 12,
            stripLeft = 18, stripRight = 6, marginLeft = 4, marginRight = 10))
        val stripStart = 20 + result.leftPadding + 4
        val labelCenter = stripStart + 18 + (result.stripWidth - 18 - 6) / 2
        assertEquals(300, labelCenter)
        assertTrue(result.leftPadding >= 8)
        assertTrue(result.rightPadding >= 12)
        assertTrue(stripStart + result.stripWidth + 10 <= 540 - 12)
    }
    fun `wide labels or viewport entirely on one side do not overlap actions`() {
        assertNull(plan(occupied = 481))
        assertNull(plan(right = 260))
        assertNull(plan(left = 340, right = 600))
    }
    fun `odd capsule dimensions center within half a pixel without cumulative drift`() {
        val result = requireNotNull(plan(dock = 601, right = 541))
        assertEquals(300.5f, result.leftPadding + result.stripWidth / 2f, 0f)
        assertEquals(result, plan(dock = 601, right = 541))
    }
    fun `invalid or out of capsule bounds are rejected`() {
        assertNull(plan(left = -1))
        assertNull(plan(right = 601))
        assertNull(plan(paddingLeft = -1))
        assertNull(plan(left = 540, right = 540))
    }
    fun `Compose placement shares the same full capsule center and fit protection`() {
        assertEquals(220, HostTopTabCenteringPolicy.centeredOffset(2, 600, 0, 540, 160))
        assertEquals(200, HostTopTabCenteringPolicy.centeredOffset(2, 600, 20, 540, 160))
        assertNull(HostTopTabCenteringPolicy.centeredOffset(3, 600, 0, 540, 160))
        assertNull(HostTopTabCenteringPolicy.centeredOffset(2, 600, 0, 540, 481))
    }
    fun `tabs stay put while the collapsing AppBar grows the actions padding frame by frame`() {
        // 411dp capsule at 2.75x; the native viewport loses 54dp..92dp of end padding to the actions.
        val density = 2.75f
        val dock = (411 * density).toInt()
        val expanded = dock - (54 * density).toInt()
        val content = (48 * density).toInt()
        // Geometry is the last positioned frame (expanded); layouts below run before it catches up.
        val positions = (0..38).map { step ->
            val viewport = dock - ((54 + step) * density).toInt()
            HostTopTabCenteringPolicy.dockCenteredOffset(1, dock, 0, 0, expanded, viewport, content)
        }
        assertTrue(positions.all { it != null })
        assertEquals("One fixed position through the whole collapse", 1, positions.toSet().size)
        assertEquals(dock / 2f, positions.first()!! + content / 2f, 1f)
        // Expanding again: the stale actions edge is further left than the growing viewport.
        val collapsedEdge = dock - (92 * density).toInt()
        assertEquals(positions.first(),
            HostTopTabCenteringPolicy.dockCenteredOffset(1, dock, 0, 0, collapsedEdge, expanded, content))
    }
    fun `dock centering still declines what the native layout must keep`() {
        assertNull("Three tabs", HostTopTabCenteringPolicy.dockCenteredOffset(3, 1000, 0, 0, 900, 800, 100))
        assertNull("Centered tabs would reach the actions", HostTopTabCenteringPolicy.dockCenteredOffset(2, 1000, 0, 0, 900, 560, 200))
        assertNull("Viewport outside the capsule", HostTopTabCenteringPolicy.dockCenteredOffset(1, 1000, 100, 100, 1000, 950, 100))
        assertNull("Unmeasured viewport", HostTopTabCenteringPolicy.dockCenteredOffset(1, 1000, 0, 0, 900, 0, 100))
        assertEquals("Viewport offset inside the capsule is local", 400,
            HostTopTabCenteringPolicy.dockCenteredOffset(1, 1000, 50, 50, 900, 850, 100))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostTopTabCenteringPolicyTest) -> Unit) {
            val s = HostTopTabCenteringPolicyTest()
            block(s)
        }
        runIsolated { s -> s.`one or two tabs center on full capsule while keeping the right action viewport`() }
        runIsolated { s -> s.`larger tab counts and empty models restore native layout`() }
        runIsolated { s -> s.`measured action width is not assumed to be a fixed dp value`() }
        runIsolated { s -> s.`existing viewport insets and strip padding stay outside the occupied label region`() }
        runIsolated { s -> s.`wide labels or viewport entirely on one side do not overlap actions`() }
        runIsolated { s -> s.`odd capsule dimensions center within half a pixel without cumulative drift`() }
        runIsolated { s -> s.`invalid or out of capsule bounds are rejected`() }
        runIsolated { s -> s.`Compose placement shares the same full capsule center and fit protection`() }
        runIsolated { s -> s.`tabs stay put while the collapsing AppBar grows the actions padding frame by frame`() }
        runIsolated { s -> s.`dock centering still declines what the native layout must keep`() }
    }
}
