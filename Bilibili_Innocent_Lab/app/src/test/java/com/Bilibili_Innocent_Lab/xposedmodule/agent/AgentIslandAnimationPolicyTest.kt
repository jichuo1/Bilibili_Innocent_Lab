package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.agent.ui.AgentIslandAnimationPolicy as Policy
import org.junit.Assert.*
import org.junit.Test

class AgentIslandAnimationPolicyTest {
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

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AgentIslandAnimationPolicyTest) -> Unit) {
            val s = AgentIslandAnimationPolicyTest()
            block(s)
        }
        runIsolated { s -> s.`animation requires request attachment visibility and enabled system animations`() }
        runIsolated { s -> s.`four dots preserve bounded alpha and continuous change over each cycle`() }
        runIsolated { s -> s.`counter rollover and extreme frames retain valid marker output`() }
    }
}
