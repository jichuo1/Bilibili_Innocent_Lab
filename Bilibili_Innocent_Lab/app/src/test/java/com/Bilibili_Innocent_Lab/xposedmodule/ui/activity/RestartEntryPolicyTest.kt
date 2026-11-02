package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RestartRoute
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RestartRoutePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RootRestartAccess
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.ShizukuAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartEntryPolicyTest {
    @Test
    fun `stopped Shizuku exposes recovery without becoming a restart capability`() {
        val stopped = ShizukuAvailability.Snapshot(ShizukuAvailability.Status.STOPPED)
        assertTrue(canOpenRestartControls(false, RestartRoute.SHIZUKU, stopped))
        assertFalse(stopped.recommended)
        assertEquals(RestartRoute.MANUAL, RestartRoutePolicy.preferred(
            RootRestartAccess.Snapshot(RootRestartAccess.Status.MISSING), stopped))
    }

    @Test
    fun `unsettled checks never expose privileged controls`() {
        RestartRoute.entries.forEach { route ->
            ShizukuAvailability.Status.entries.forEach { status ->
                assertFalse(canOpenRestartControls(true, route, ShizukuAvailability.Snapshot(status)))
            }
        }
    }

    @Test
    fun `existing authorization and unavailable service boundaries remain intact`() {
        ShizukuAvailability.Status.entries.forEach { status ->
            val expected = status in setOf(ShizukuAvailability.Status.READY,
                ShizukuAvailability.Status.UNAUTHORIZED, ShizukuAvailability.Status.DENIED,
                ShizukuAvailability.Status.STOPPED)
            assertEquals(expected, canOpenRestartControls(false, RestartRoute.SHIZUKU,
                ShizukuAvailability.Snapshot(status)))
        }
        assertFalse(canOpenRestartControls(false, RestartRoute.SHIZUKU, null))
        assertFalse(canOpenRestartControls(false, null, null))
        assertFalse(canOpenRestartControls(false, RestartRoute.MANUAL, null))
        assertTrue(canOpenRestartControls(false, RestartRoute.ROOT, null))
    }
}
