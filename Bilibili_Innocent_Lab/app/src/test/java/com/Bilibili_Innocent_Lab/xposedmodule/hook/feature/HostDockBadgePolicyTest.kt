package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostBottomBarFxConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostDockBadgePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostGarbColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostDockBadgePolicyTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test fun `only text badges are recognised by their resource name`() {
        assertTrue(HostDockBadgePolicy.isCountBadge("tab_badge_text"))
        assertTrue(HostDockBadgePolicy.isCountBadge("Tab_Badge"))
        // 其他 tab 内的文字与图标绝不能被当成角标染色。
        for (name in listOf("", "tab_text", "tab_title", "tab_icon", "tab_svg", "home_publish_icon", "container")) {
            assertFalse(name, HostDockBadgePolicy.isCountBadge(name))
        }
    }

    @Test fun `the fill is the accent made opaque`() {
        assertEquals(0xFFFF6699.toInt(), HostDockBadgePolicy.style(0xFFFF6699.toInt()).fill)
        assertEquals(0xFF336699.toInt(), HostDockBadgePolicy.style(0x00336699).fill)
    }

    @Test fun `the digits take whichever of black and white contrasts more with the accent`() {
        // 浅色强调色配黑字，深色强调色配白字，且选中的一定是对比更高的那个。
        assertEquals(black, HostDockBadgePolicy.onAccent(0xFFFFFFFF.toInt()))
        assertEquals(black, HostDockBadgePolicy.onAccent(0xFFFFEB3B.toInt()))
        assertEquals(white, HostDockBadgePolicy.onAccent(0xFF000000.toInt()))
        assertEquals(white, HostDockBadgePolicy.onAccent(0xFF1E3A8A.toInt()))
        for (accent in listOf(0xFFFF6699.toInt(), 0xFFFB7299.toInt(), 0xFF00A1D6.toInt(), 0xFF7E57C2.toInt(), 0xFFE53935.toInt())) {
            val chosen = HostDockBadgePolicy.onAccent(accent)
            val other = if (chosen == white) black else white
            assertTrue(HostGarbColors.contrast(chosen, accent) >= HostGarbColors.contrast(other, accent))
        }
    }

    @Test fun `badge accent is off by default and independent from the other bottom bar options`() {
        assertFalse(HostBottomBarFxConfig().badgeAccent)
        val enabled = HostBottomBarFxConfig(badgeAccent = true)
        assertTrue(enabled.badgeAccent)
        // 开关不改变底栏几何：高度档位只取决于既有选项。
        assertEquals(HostBottomBarFxConfig().heightDp, enabled.heightDp, 0f)
        assertEquals(HostBottomBarFxConfig().minHeightDp, enabled.minHeightDp, 0f)
    }
}
