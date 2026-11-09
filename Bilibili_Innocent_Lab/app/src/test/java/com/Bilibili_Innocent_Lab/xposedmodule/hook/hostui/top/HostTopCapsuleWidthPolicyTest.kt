package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTopCapsuleWidthPolicyTest {
    @Test fun phoneWidthsKeepTheirMargin() {
        assertEquals(16f, HostTopCapsuleWidthPolicy.marginDp(393f, 16f, 0f), 0f)
        assertEquals(16f, HostTopCapsuleWidthPolicy.marginDp(599f, 16f, 0f), 0f)
        // Compose 顶栏在手机上不加边距，原生修饰链保持不变。
        assertEquals(0f, HostTopCapsuleWidthPolicy.marginDp(393f, 0f, 0f), 0f)
    }

    @Test fun wideWindowsCapAndCenterTheCapsule() {
        val margin = HostTopCapsuleWidthPolicy.marginDp(1280f, 16f, 0f)
        assertEquals(HostTopCapsuleWidthPolicy.MAX_WIDTH_DP, 1280f - margin * 2f, 0.001f)
        // 刚到门槛即限宽：(600 - 560) / 2 = 20dp。
        assertEquals(20f, HostTopCapsuleWidthPolicy.marginDp(600f, 16f, 0f), 0f)
    }

    @Test fun cutoutsAndSideNavigationWidenTheMargin() {
        // 超宽限宽与侧向避让取较大者。
        assertEquals((800f - 560f) / 2f, HostTopCapsuleWidthPolicy.marginDp(800f, 16f, 48f), 0f)
        assertEquals(200f + 4f, HostTopCapsuleWidthPolicy.marginDp(800f, 16f, 200f), 0f)
        assertEquals(30f + 4f, HostTopCapsuleWidthPolicy.marginDp(393f, 16f, 30f), 0f)
        assertEquals(24f + 4f, HostTopCapsuleWidthPolicy.marginDp(393f, 0f, 24f), 0f)
    }

    @Test fun theTopCapsuleStaysWiderThanTheLargestCappedBottomBar() {
        assertTrue(HostTopCapsuleWidthPolicy.MAX_WIDTH_DP > 5 * 96f + 8f)
    }
}
