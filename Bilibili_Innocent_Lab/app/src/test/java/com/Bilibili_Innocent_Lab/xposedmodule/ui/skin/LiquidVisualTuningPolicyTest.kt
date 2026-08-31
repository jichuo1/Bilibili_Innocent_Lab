package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidVisualTuningPolicy
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidVisualTuningPolicyTest {

    @Test
    fun `ambient washes stay subtle in light and dark palettes`() {
        listOf(false, true).forEach { dark ->
            val tuning = LiquidVisualTuningPolicy.resolve(dark)

            assertTrue(tuning.primaryWashAlpha in 1..25)
            assertTrue(tuning.secondaryWashAlpha in 1..20)
            assertTrue(tuning.secondaryWashAlpha <= tuning.primaryWashAlpha)
            assertTrue(tuning.saturation in 1f..1.08f)
        }
    }

    @Test
    fun `gpu glass is lighter than translucent fallback`() {
        listOf(false, true).forEach { dark ->
            val tuning = LiquidVisualTuningPolicy.resolve(dark)

            assertTrue(tuning.cardGlassAlpha < tuning.cardFallbackAlpha)
            assertTrue(tuning.modalGlassAlpha < tuning.modalFallbackAlpha)
            assertTrue(tuning.motionGlassAlpha < tuning.motionFallbackAlpha)
            assertTrue(tuning.cardGlassAlpha <= 0.24f)
            assertTrue(tuning.modalGlassAlpha <= 0.38f)
            assertTrue(tuning.cardFallbackAlpha < 0.8f)
            assertTrue(tuning.modalFallbackAlpha < 0.95f)
        }
    }

    @Test
    fun `modal remains more opaque than card`() {
        listOf(false, true).forEach { dark ->
            val tuning = LiquidVisualTuningPolicy.resolve(dark)

            assertTrue(tuning.modalGlassAlpha > tuning.cardGlassAlpha)
            assertTrue(tuning.modalFallbackAlpha > tuning.cardFallbackAlpha)
            assertTrue(tuning.motionGlassAlpha > tuning.cardGlassAlpha)
            assertTrue(tuning.motionGlassAlpha <= tuning.modalGlassAlpha)
            assertTrue(tuning.motionFallbackAlpha > tuning.cardFallbackAlpha)
            assertTrue(tuning.motionFallbackAlpha < tuning.modalFallbackAlpha)
        }
    }
}
