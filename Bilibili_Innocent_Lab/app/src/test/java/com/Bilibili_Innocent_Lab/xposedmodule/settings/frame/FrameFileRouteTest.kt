package com.Bilibili_Innocent_Lab.xposedmodule.settings.frame

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchMode
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRemoteGateway.SyncResult
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.FrameworkServiceCall
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameFileRouteTest {
    @Test fun `without the no-root channel the pushed framework service is used for every patch mode`() {
        for (mode in NPatchMode.entries) for (standard in listOf(false, true)) {
            assertEquals(
                FrameFileRoute.PUSHED_SERVICE,
                FrameFileRoute.select(noRootSelected = false, mode = mode, standardFrameworkConnected = standard)
            )
        }
    }

    @Test fun `a selected NPatch channel only uses the authenticated pull channel`() {
        assertEquals(FrameFileRoute.PULLED_NPATCH, FrameFileRoute.select(true, NPatchMode.MANAGER, false))
        assertEquals(FrameFileRoute.PULLED_NPATCH, FrameFileRoute.select(true, NPatchMode.UNKNOWN, false))
    }

    @Test fun `a connected root or LSPatch framework keeps the pushed route even with the no-root switch on`() {
        for (mode in NPatchMode.entries) {
            assertEquals(FrameFileRoute.PUSHED_SERVICE, FrameFileRoute.select(true, mode, standardFrameworkConnected = true))
        }
    }

    @Test fun `embedded mode cannot reach the host private store`() {
        assertEquals(FrameFileRoute.UNSUPPORTED_EMBEDDED, FrameFileRoute.select(true, NPatchMode.EMBEDDED, false))
    }

    @Test fun `pull failures reuse the push failure reasons so the settings text and retry stay consistent`() {
        assertEquals(FrameworkServiceCall.Reason.NOT_CONNECTED, FrameFileRoute.failureReason(SyncResult.ManagerMissing))
        assertEquals(FrameworkServiceCall.Reason.NOT_CONNECTED, FrameFileRoute.failureReason(SyncResult.ModuleNotRegistered))
        assertEquals(FrameworkServiceCall.Reason.METADATA_UNAVAILABLE, FrameFileRoute.failureReason(SyncResult.ConnectionTimeout))
        assertEquals(FrameworkServiceCall.Reason.OPERATION_FAILED, FrameFileRoute.failureReason(SyncResult.Failure("x")))
    }
}
