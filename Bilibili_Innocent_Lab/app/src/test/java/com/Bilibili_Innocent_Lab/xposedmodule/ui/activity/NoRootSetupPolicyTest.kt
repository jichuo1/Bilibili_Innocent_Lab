package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HostConfigChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoRootSetupPolicyTest {
    @Test fun `LSPatch uses its own manager without an ineffective NPatch opt in`() {
        val policy = NoRootSetupPolicy.forChannel(HostConfigChannel.LSPATCH)
        assertTrue(policy.lspatch)
        assertFalse(policy.needsOptIn)
        assertEquals("org.lsposed.lspatch", policy.managerPackage)
    }

    @Test fun `NPatch and setup before patching retain explicit opt in`() {
        listOf(HostConfigChannel.NPATCH, HostConfigChannel.STANDARD).forEach { channel ->
            val policy = NoRootSetupPolicy.forChannel(channel)
            assertFalse(policy.lspatch)
            assertTrue(policy.needsOptIn)
            assertEquals("top.nkbe.npatch", policy.managerPackage)
        }
    }
}
