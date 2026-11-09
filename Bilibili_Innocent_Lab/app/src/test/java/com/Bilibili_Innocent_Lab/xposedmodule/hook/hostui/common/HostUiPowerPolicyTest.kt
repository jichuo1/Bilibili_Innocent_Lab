package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostUiPowerPolicyTest {
    @After fun restore() = HostUiPowerPolicy.resetForTests()

    @Test fun `power saving is on by default and the switch turns both paths off together`() {
        assertTrue(HostUiPowerPolicy.current.retainsGpuFrames)
        assertTrue(HostUiPowerPolicy.current.shortcutsCardRendering)
        HostUiPowerPolicy.configure(false)
        assertFalse(HostUiPowerPolicy.current.retainsGpuFrames)
        assertFalse(HostUiPowerPolicy.current.shortcutsCardRendering)
        HostUiPowerPolicy.configure(true)
        assertTrue(HostUiPowerPolicy.current.retainsGpuFrames)
    }

    @Test fun `retention needs the switch, API 32 and GPU backing`() {
        assertEquals(32, HostUiPowerPolicy.RETAINED_GPU_FRAME_SDK)
        assertTrue(HostUiPowerPolicy.canRetainGpuFrame(true, 32) { true })
        assertTrue(HostUiPowerPolicy.canRetainGpuFrame(true, 36) { true })
        assertFalse(HostUiPowerPolicy.canRetainGpuFrame(false, 36) { true })
        assertFalse(HostUiPowerPolicy.canRetainGpuFrame(true, 31) { true })
        assertFalse(HostUiPowerPolicy.canRetainGpuFrame(true, 36) { false })
    }

    @Test fun `the GPU probe is only evaluated when the cheaper conditions hold`() {
        var probes = 0
        HostUiPowerPolicy.canRetainGpuFrame(false, 36) { probes++; true }
        HostUiPowerPolicy.canRetainGpuFrame(true, 31) { probes++; true }
        assertEquals(0, probes)
        HostUiPowerPolicy.canRetainGpuFrame(true, 32) { probes++; true }
        assertEquals(1, probes)
    }
}
