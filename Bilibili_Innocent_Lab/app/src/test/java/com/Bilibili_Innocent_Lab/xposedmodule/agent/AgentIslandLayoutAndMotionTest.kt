package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.agent.ui.AgentIslandAnimationPolicy as Policy
import com.Bilibili_Innocent_Lab.xposedmodule.agent.ui.AgentIslandGeometry as Geometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentIslandLayoutAndMotionTest {

    @Test
    fun verifyAllCases() {
        AgentIslandAnimationPolicyCases().verifyAllCases()
        AgentIslandGeometryCases().verifyAllCases()
    }
}

private class AgentIslandAnimationPolicyCases {
    fun `animation requires request attachment visibility and enabled system animations`() {
        assertTrue(Policy.shouldAnimate(true, true, true, true, false))
        assertFalse(Policy.shouldAnimate(false, true, true, true, false))
        assertFalse(Policy.shouldAnimate(true, false, true, true, false))
        assertFalse(Policy.shouldAnimate(true, true, false, true, false))
        assertFalse(Policy.shouldAnimate(true, true, true, false, false))
        assertFalse(Policy.shouldAnimate(true, true, true, true, true))
        assertEquals(50L, Policy.FRAME_MS)
    }

    fun `four dots preserve bounded alpha and continuous change over each cycle`() {
        for (frame in 0 until 64) {
            val alphas = (0 until 4).map { Policy.dotAlpha(frame, it) }
            if (frame % 4 == 0) assertEquals(1, alphas.count { it == 255 })
            assertTrue(alphas.all { it in 56..255 })
            assertTrue(alphas.sum() in 533..537)
            assertEquals(alphas, (0 until 4).map { Policy.dotAlpha(frame + 16, it) })
            for (dot in 0 until 4) assertNotEquals(Policy.dotAlpha(frame, dot), Policy.dotAlpha(frame + 1, dot))
        }
    }

    fun `counter rollover and extreme frames retain valid marker output`() {
        for (frame in listOf(Int.MIN_VALUE, -1, 0, 65_535, Int.MAX_VALUE)) {
            if (frame and 3 == 0) assertEquals(1, (0 until 4).count { Policy.dotAlpha(frame, it) == 255 })
            assertTrue((0 until 4).all { Policy.dotAlpha(frame, it) in 56..255 })
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AgentIslandAnimationPolicyCases) -> Unit) {
            val s = AgentIslandAnimationPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`animation requires request attachment visibility and enabled system animations`() }
        runIsolated { s -> s.`four dots preserve bounded alpha and continuous change over each cycle`() }
        runIsolated { s -> s.`counter rollover and extreme frames retain valid marker output`() }
    }
}

private class AgentIslandGeometryCases {
    private val phone = Geometry.Input(1080, 2400, 3f, 90, bottomInsetPx = 72)

    fun `no reported cutout uses a safe centered capsule`() {
        val island = Geometry.collapsed(phone)!!
        assertFalse(island.cameraProjected)
        assertEquals(1080, island.bounds.left + island.bounds.right)
        assertEquals(102, island.bounds.top)
        assertEquals(144, island.bounds.height)
        assertTrue(island.moduleCenterXPx < island.gapStartPx)
        assertTrue(island.agentCenterXPx > island.gapEndPx)
    }

    fun `central narrow top hole aligns icons with horizontal camera projection`() {
        val hole = Geometry.Rect(492, 0, 588, 108)
        val island = Geometry.collapsed(phone.copy(cutouts = listOf(hole)))!!
        assertTrue(island.cameraProjected)
        assertEquals(120, island.bounds.top)
        assertEquals(492 - 12, island.bounds.left + island.gapStartPx)
        assertEquals(588 + 12, island.bounds.left + island.gapEndPx)
        assertTrue(island.bounds.left + island.moduleCenterXPx < hole.left)
        assertTrue(island.bounds.left + island.agentCenterXPx > hole.right)
    }

    fun `left hole wide notch and multiple cutouts use centered fallback`() {
        val cuts = listOf(
            listOf(Geometry.Rect(30, 0, 126, 108)),
            listOf(Geometry.Rect(240, 0, 840, 120)),
            listOf(Geometry.Rect(450, 0, 480, 90), Geometry.Rect(600, 0, 630, 90))
        )
        for (cutouts in cuts) {
            val island = Geometry.collapsed(phone.copy(cutouts = cutouts))!!
            assertFalse(island.cameraProjected)
            assertEquals(1080, island.bounds.left + island.bounds.right)
            assertTrue(island.bounds.top > cutouts.maxOf { it.bottom })
        }
    }

    fun `landscape and lateral cutout respect safe horizontal insets`() {
        val input = Geometry.Input(2400, 1080, 3f, 72, listOf(Geometry.Rect(0, 420, 108, 588)),
            leftInsetPx = 108, rightInsetPx = 36, bottomInsetPx = 60)
        val island = Geometry.collapsed(input)!!
        assertFalse(island.cameraProjected)
        assertTrue(island.bounds.left >= 132)
        assertTrue(island.bounds.right <= 2340)
        assertEquals(84, island.bounds.top)
    }

    fun `expanded panel follows island with bounded width and requested height`() {
        val island = Geometry.collapsed(phone)!!
        val panel = Geometry.panel(phone, island)!!
        assertEquals(island.bounds.bottom + 24, panel.top)
        assertEquals(1032, panel.width)
        assertEquals(720, panel.height)
        assertTrue(panel.bottom <= 2304)
    }

    fun `short viewport caps panel height above bottom inset`() {
        val input = Geometry.Input(400, 320, 1f, 24, bottomInsetPx = 24)
        val island = Geometry.collapsed(input)!!
        val panel = Geometry.panel(input, island, 240)!!
        assertEquals(288, panel.bottom)
        assertEquals(204, panel.height)
    }

    fun `panel clamps an off center anchor and rejects stale bounds after rotation`() {
        val input = phone.copy(cutouts = listOf(Geometry.Rect(402, 0, 498, 108)))
        val island = Geometry.collapsed(input)!!
        val panel = Geometry.panel(input, island)!!
        assertEquals(24, panel.left)
        assertEquals(1056, panel.right)
        assertNull(Geometry.panel(Geometry.Input(320, 1080, 3f, 24), island))
    }

    fun `invalid dimensions densities and insets have no placement`() {
        for (input in listOf(phone.copy(widthPx = 0), phone.copy(heightPx = -1), phone.copy(density = 0f),
            phone.copy(density = Float.NaN), phone.copy(density = Float.POSITIVE_INFINITY),
            phone.copy(leftInsetPx = -1), phone.copy(topInsetPx = 2500), phone.copy(rightInsetPx = 1200),
            Geometry.Input(100, 400, 1f))) assertNull(Geometry.collapsed(input))
    }

    fun `invalid cutout rectangles do not influence placement`() {
        val input = phone.copy(cutouts = listOf(Geometry.Rect(500, 80, 490, 100),
            Geometry.Rect(-40, -40, -1, -1), Geometry.Rect(1500, 0, 1600, 100)))
        assertEquals(Geometry.collapsed(phone), Geometry.collapsed(input))
    }

    fun `too little panel space and invalid requested height return no panel`() {
        val input = Geometry.Input(400, 130, 1f, 24)
        val island = Geometry.collapsed(input)!!
        assertNull(Geometry.panel(input, island))
        assertNull(Geometry.panel(phone, Geometry.collapsed(phone)!!, 0))
    }

    fun `large coordinate and density arithmetic remains bounded without overflow`() {
        val input = Geometry.Input(Int.MAX_VALUE, Int.MAX_VALUE, 1f, 24)
        val island = Geometry.collapsed(input)!!
        val panel = Geometry.panel(input, island, Int.MAX_VALUE)!!
        assertTrue(island.bounds.left >= 0 && island.bounds.right <= input.widthPx)
        assertTrue(panel.top >= 0 && panel.bottom <= input.heightPx - 8)
        assertNull(Geometry.collapsed(input.copy(density = Float.MAX_VALUE)))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AgentIslandGeometryCases) -> Unit) {
            val s = AgentIslandGeometryCases()
            block(s)
        }
        runIsolated { s -> s.`no reported cutout uses a safe centered capsule`() }
        runIsolated { s -> s.`central narrow top hole aligns icons with horizontal camera projection`() }
        runIsolated { s -> s.`left hole wide notch and multiple cutouts use centered fallback`() }
        runIsolated { s -> s.`landscape and lateral cutout respect safe horizontal insets`() }
        runIsolated { s -> s.`expanded panel follows island with bounded width and requested height`() }
        runIsolated { s -> s.`short viewport caps panel height above bottom inset`() }
        runIsolated { s -> s.`panel clamps an off center anchor and rejects stale bounds after rotation`() }
        runIsolated { s -> s.`invalid dimensions densities and insets have no placement`() }
        runIsolated { s -> s.`invalid cutout rectangles do not influence placement`() }
        runIsolated { s -> s.`too little panel space and invalid requested height return no panel`() }
        runIsolated { s -> s.`large coordinate and density arithmetic remains bounded without overflow`() }
    }
}
