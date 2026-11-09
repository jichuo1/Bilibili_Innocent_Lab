package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostAdmissionSourcePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostAdmissionContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.ModernFrameworkStatus
import io.github.libxposed.service.XposedService
import org.junit.Assert.*
import org.junit.Test

class HostConfigChannelTest {
    private val npatch = ModernFrameworkStatus(true, true, "NPatch", 102, properties = XposedService.PROP_CAP_REMOTE)
    private val lspatch = npatch.copy(name = "LSPatch")
    private val root = npatch.copy(name = "LSPosed", properties = XposedService.PROP_CAP_REMOTE or XposedService.PROP_CAP_SYSTEM)

    @Test fun anotherPatchManagerCannotChangeInstalledLspatchAdmission() {
        val selected = HostConfigChannels.select(npatch, false, true)
        assertEquals(HostConfigChannel.LSPATCH, selected)
        assertEquals(selected, HostConfigChannels.select(lspatch, false, true))
        assertEquals(HostAdmissionContract.DIRECT, HostAdmissionSourcePolicy.select(
            noRootSelected = false, normalNoRootRevision = 0, expectedFingerprint = "current",
            normalFingerprint = null, allowDirect = true, directEnabled = false,
            nPatchChannel = selected == HostConfigChannel.NPATCH, lspatchChannel = selected == HostConfigChannel.LSPATCH))
    }

    @Test fun anotherPatchManagerCannotBypassInstalledNpatchDisableGate() {
        val selected = HostConfigChannels.select(lspatch, true, false)
        assertEquals(HostConfigChannel.NPATCH, selected)
        assertEquals(selected, HostConfigChannels.select(npatch, true, false))
        assertNull(HostAdmissionSourcePolicy.select(false, 0, "current", null, true, false,
            nPatchChannel = selected == HostConfigChannel.NPATCH, lspatchChannel = selected == HostConfigChannel.LSPATCH))
    }

    @Test fun rootServiceRetainsPriorityOverBothInstalledPatchMarkers() {
        for ((npatch, lspatch) in listOf(true to false, false to true, true to true)) {
            val selected = HostConfigChannels.select(root, npatch, lspatch)
            assertEquals(HostConfigChannel.STANDARD, selected)
        }
    }

    @Test fun unavailableServicesUseInstalledProfilesAndUnknownHostsKeepExistingServiceRouting() {
        val unavailable = npatch.copy(connected = false, capable = false)
        assertEquals(HostConfigChannel.LSPATCH, HostConfigChannels.select(unavailable, false, true))
        assertEquals(HostConfigChannel.NPATCH, HostConfigChannels.select(unavailable, true, false))
        assertEquals(HostConfigChannel.STANDARD, HostConfigChannels.select(unavailable, false, false))
        assertEquals(HostConfigChannel.NPATCH, HostConfigChannels.select(npatch, false, false))
        assertEquals(HostConfigChannel.LSPATCH, HostConfigChannels.select(lspatch, false, false))
    }
}
