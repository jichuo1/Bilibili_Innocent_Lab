package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRemoteGateway.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class NPatchRemoteFailureClassificationTest {
    @Test fun `a provider that answered without a service means the module was not accepted`() {
        assertEquals(
            SyncResult.ModuleNotRegistered,
            NPatchRemoteGateway.classifyFailure(NPatchRemoteGateway.ManagerUnavailableException(rejected = true))
        )
    }

    @Test fun `a missing or unreachable provider still means the manager is missing`() {
        assertEquals(
            SyncResult.ManagerMissing,
            NPatchRemoteGateway.classifyFailure(NPatchRemoteGateway.ManagerUnavailableException())
        )
        assertEquals(
            SyncResult.ManagerMissing,
            NPatchRemoteGateway.classifyFailure(
                NPatchRemoteGateway.ManagerUnavailableException(SecurityException("platform denied the provider"))
            )
        )
    }

    @Test fun `an explicit security denial means the module was not accepted`() {
        assertEquals(SyncResult.ModuleNotRegistered, NPatchRemoteGateway.classifyFailure(SecurityException("denied")))
    }

    @Test fun `unrelated failures keep their type name as the code`() {
        assertEquals(SyncResult.Failure("IllegalArgumentException"),
            NPatchRemoteGateway.classifyFailure(IllegalArgumentException("bad")))
        assertEquals(SyncResult.Failure("remote_read_only"),
            NPatchRemoteGateway.classifyFailure(UnsupportedOperationException()))
    }

    @Test fun `a rejected connection is retried once and keeps its classification`() {
        var calls = 0
        val error = assertThrows(NPatchRemoteGateway.ManagerUnavailableException::class.java) {
            NPatchRemoteGateway.connectWithRetry({ true }) {
                calls++
                throw NPatchRemoteGateway.ManagerUnavailableException(rejected = true)
            }
        }
        assertEquals(2, calls)
        assertTrue(error.rejected)
        assertEquals(SyncResult.ModuleNotRegistered, NPatchRemoteGateway.classifyFailure(error))
    }
}
