package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidMemoryPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidMemoryPolicyTest {

    @Test
    fun `running critical releases graphics`() {
        assertTrue(LiquidMemoryPolicy.shouldReleaseGraphics(15))
    }

    @Test
    fun `ui hidden does not permanently downgrade renderer`() {
        listOf(5, 10, 14, 16, 20, 40, 60, 79).forEach { level ->
            assertFalse(LiquidMemoryPolicy.shouldReleaseGraphics(level))
        }
    }

    @Test
    fun `complete pressure releases graphics`() {
        assertTrue(LiquidMemoryPolicy.shouldReleaseGraphics(80))
        assertTrue(LiquidMemoryPolicy.shouldReleaseGraphics(81))
        assertTrue(LiquidMemoryPolicy.shouldReleaseGraphics(100))
    }
}
