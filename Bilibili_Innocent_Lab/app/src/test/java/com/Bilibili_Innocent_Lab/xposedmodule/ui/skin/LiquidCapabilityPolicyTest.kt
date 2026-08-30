package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidBackendFallbackPlan
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidCapabilityPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.LiquidRenderBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiquidCapabilityPolicyTest {

    @Test
    fun `api 33 hardware tries refraction blur then translucent`() {
        assertEquals(
            listOf(
                LiquidRenderBackend.REFRACTION,
                LiquidRenderBackend.BLUR,
                LiquidRenderBackend.TRANSLUCENT
            ),
            LiquidCapabilityPolicy.candidateOrder(33, hardwareAccelerated = true)
        )
    }

    @Test
    fun `api 31 hardware tries blur then translucent`() {
        assertEquals(
            listOf(LiquidRenderBackend.BLUR, LiquidRenderBackend.TRANSLUCENT),
            LiquidCapabilityPolicy.candidateOrder(31, hardwareAccelerated = true)
        )
    }

    @Test
    fun `api 30 hardware uses translucent`() {
        assertEquals(
            listOf(LiquidRenderBackend.TRANSLUCENT),
            LiquidCapabilityPolicy.candidateOrder(30, hardwareAccelerated = true)
        )
    }

    @Test
    fun `software canvas never selects gpu backends`() {
        listOf(27, 31, 33, 37).forEach { sdk ->
            assertEquals(
                listOf(LiquidRenderBackend.TRANSLUCENT),
                LiquidCapabilityPolicy.candidateOrder(sdk, hardwareAccelerated = false)
            )
        }
    }

    @Test
    fun `fallback plan advances one backend at a time`() {
        val plan = LiquidBackendFallbackPlan(
            listOf(
                LiquidRenderBackend.REFRACTION,
                LiquidRenderBackend.BLUR,
                LiquidRenderBackend.TRANSLUCENT
            )
        )

        assertEquals(LiquidRenderBackend.REFRACTION, plan.current)
        assertEquals(
            LiquidRenderBackend.BLUR,
            plan.advanceAfterFailure(LiquidRenderBackend.REFRACTION)
        )
        assertEquals(
            LiquidRenderBackend.TRANSLUCENT,
            plan.advanceAfterFailure(LiquidRenderBackend.BLUR)
        )
        assertNull(plan.advanceAfterFailure(LiquidRenderBackend.TRANSLUCENT))
    }

    @Test
    fun `stale failure cannot skip current fallback`() {
        val plan = LiquidBackendFallbackPlan(
            listOf(
                LiquidRenderBackend.REFRACTION,
                LiquidRenderBackend.BLUR,
                LiquidRenderBackend.TRANSLUCENT
            )
        )
        plan.advanceAfterFailure(LiquidRenderBackend.REFRACTION)

        assertEquals(
            LiquidRenderBackend.BLUR,
            plan.advanceAfterFailure(LiquidRenderBackend.REFRACTION)
        )
        assertEquals(LiquidRenderBackend.BLUR, plan.current)
    }
}
