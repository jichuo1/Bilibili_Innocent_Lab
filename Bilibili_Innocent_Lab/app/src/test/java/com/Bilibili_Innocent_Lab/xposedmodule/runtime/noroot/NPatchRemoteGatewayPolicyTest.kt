package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NPatchRemoteGatewayPolicyTest {

    @Test
    fun `only the API 102 service descriptor is accepted`() {
        assertTrue(
            NPatchRemoteGateway.acceptsServiceDescriptor(
                "io.github.libxposed.service.IXposedService"
            )
        )
        assertFalse(NPatchRemoteGateway.acceptsServiceDescriptor(null))
        assertFalse(
            NPatchRemoteGateway.acceptsServiceDescriptor(
                "io.github.libxposed.service.ILegacyXposedService"
            )
        )
    }

    @Test
    fun `connection circuit stays open only before its monotonic deadline`() {
        assertFalse(NPatchRemoteGateway.isConnectionCircuitOpen(100L, 0L))
        assertTrue(NPatchRemoteGateway.isConnectionCircuitOpen(100L, 101L))
        assertFalse(NPatchRemoteGateway.isConnectionCircuitOpen(100L, 100L))
        assertFalse(NPatchRemoteGateway.isConnectionCircuitOpen(101L, 100L))
    }
}
