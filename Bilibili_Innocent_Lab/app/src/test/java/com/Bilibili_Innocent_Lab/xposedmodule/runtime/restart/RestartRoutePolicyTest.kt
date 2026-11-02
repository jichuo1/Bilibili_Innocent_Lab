package com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart

import org.junit.Assert.*
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootDisplayState

class RestartRoutePolicyTest {
    private val root = RootRestartAccess.Snapshot(RootRestartAccess.Status.READY, "/system/bin/su")
    private val absent = RootRestartAccess.Snapshot(RootRestartAccess.Status.MISSING)
    private val shizuku = ShizukuAvailability.Snapshot(ShizukuAvailability.Status.READY, 2000)
    private val missing = ShizukuAvailability.Snapshot(ShizukuAvailability.Status.MISSING)

    @Test fun rootPrecedesShizukuWithoutAnyFrameworkNameOrNoRootFlag() {
        for (status in listOf(RootRestartAccess.Status.CANDIDATE, RootRestartAccess.Status.READY)) {
            for (permission in ShizukuAvailability.Status.entries) {
                assertEquals(RestartRoute.ROOT, RestartRoutePolicy.preferred(root.copy(status = status), shizuku.copy(status = permission)))
            }
        }
    }
    @Test fun rootFailureOffersShizukuBeforeManual() {
        for (status in listOf(RootRestartAccess.Status.MISSING, RootRestartAccess.Status.DENIED, RootRestartAccess.Status.UNKNOWN)) {
            assertEquals(RestartRoute.SHIZUKU, RestartRoutePolicy.preferred(root.copy(status = status), shizuku))
            assertEquals(RestartRoute.MANUAL, RestartRoutePolicy.preferred(root.copy(status = status), missing))
        }
    }
    @Test fun pendingRootBlocksFallbackButConfirmedRootDoesNotWaitForLowerPriorityChecks() {
        assertNull(RestartRoutePolicy.preferred(null, shizuku))
        assertEquals(RestartRoute.ROOT, RestartRoutePolicy.preferred(root, null))
        assertNull(RestartRoutePolicy.preferred(null, null))
    }
    @Test fun selectedConfigAndPendingDisableRemainIndependentOfRootPriority() {
        for (state in NoRootDisplayState.entries) {
            assertTrue(RestartRoutePolicy.usesNoRootConfig(true, state))
            assertTrue(RestartRoutePolicy.usesNoRootConfig(false, state, nPatchCarrier = true))
            assertEquals(state in setOf(NoRootDisplayState.DISABLE_RESTART_REQUIRED,
                NoRootDisplayState.DISABLE_RESTART_REQUIRED_ACTIVE), RestartRoutePolicy.usesNoRootConfig(false, state))
            assertEquals(RestartRoute.ROOT, RestartRoutePolicy.preferred(root, shizuku))
        }
    }
    @Test fun explicitShizukuAuthorizationIsAnOptionButDeniedOrStoppedIsNotAReadyFallback() {
        assertEquals(RestartRoute.SHIZUKU, RestartRoutePolicy.preferred(absent, shizuku.copy(status = ShizukuAvailability.Status.UNAUTHORIZED)))
        for (status in listOf(ShizukuAvailability.Status.DENIED, ShizukuAvailability.Status.STOPPED, ShizukuAvailability.Status.UNSUPPORTED)) {
            assertEquals(RestartRoute.MANUAL, RestartRoutePolicy.preferred(absent, shizuku.copy(status = status)))
        }
    }
}
