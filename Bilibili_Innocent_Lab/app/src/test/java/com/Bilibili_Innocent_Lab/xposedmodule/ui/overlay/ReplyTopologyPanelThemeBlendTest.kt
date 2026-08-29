package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁定作者名弱化色的混合语义：端点、比例、alpha 继承与通道钳制。 */
class ReplyTopologyPanelThemeBlendTest {

    @Test
    fun `blend endpoints return primary and secondary exactly`() {
        val spec = ReplyTopologyPanelTheme.Companion
        assertEquals(0xFF112233.toInt(), spec.blendColor(0xFF112233.toInt(), 0xFFAABBCC.toInt(), 0f))
        assertEquals(0xFFAABBCC.toInt(), spec.blendColor(0xFF112233.toInt(), 0xFFAABBCC.toInt(), 1f))
    }

    @Test
    fun `blend midpoint averages channels`() {
        val spec = ReplyTopologyPanelTheme.Companion
        // 0x000000 与 0xFFFFFF 的中点是中灰
        assertEquals(0xFF808080.toInt(), spec.blendColor(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
    }

    @Test
    fun `blend keeps primary alpha regardless of secondary alpha`() {
        val spec = ReplyTopologyPanelTheme.Companion
        val blended = spec.blendColor(0xFF101010.toInt(), 0x00F0F0F0.toInt(), 0.5f)
        assertEquals(0xFF, blended ushr 24 and 0xFF)
    }

    @Test
    fun `blend fraction is clamped and matches author tint fraction`() {
        val spec = ReplyTopologyPanelTheme.Companion
        val fraction = ReplyTopologyPanelTheme.AUTHOR_TEXT_TOWARD_SECONDARY_FRACTION
        assertTrue(fraction in 0f..1f)
        val clampedLow = spec.blendColor(0xFF000000.toInt(), 0xFF0000FF.toInt(), -1f)
        assertEquals(spec.blendColor(0xFF000000.toInt(), 0xFF0000FF.toInt(), 0f), clampedLow)
        val clampedHigh = spec.blendColor(0xFF000000.toInt(), 0xFF0000FF.toInt(), 2f)
        assertEquals(spec.blendColor(0xFF000000.toInt(), 0xFF0000FF.toInt(), 1f), clampedHigh)
    }

    @Test
    fun `dark theme author color sits between primary and secondary`() {
        // 暗色主题实值：primary E8E8E8 向 secondary B8BBC2 混合 0.4 => D5D6D9
        val spec = ReplyTopologyPanelTheme.Companion
        val author = spec.blendColor(0xFFE8E8E8.toInt(), 0xFFB8BBC2.toInt(), 0.4f)
        assertEquals(0xFFD5D6D9.toInt(), author)
    }
}
