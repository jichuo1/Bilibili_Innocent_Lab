package com.Bilibili_Innocent_Lab.xposedmodule.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticConfigDeliveryTest {
    private fun published() = inputs().copy(
        frameworkConnectionId = 1L, remoteConnectionId = 1L,
        hostRuntimeReceiptAvailable = true, hostQueryState = DiagnosticHostQueryState.READY,
        hostConfigState = DiagnosticHostConfigState.ACCEPTED, hostConfigGeneration = 42L
    )

    @Test
    fun `successful publication is not a host receipt`() {
        assertEquals(DiagnosticConfigDelivery.HOST_UNAVAILABLE, configDelivery(published().copy(
            hostRuntimeReceiptAvailable = false
        )))
        assertEquals(DiagnosticConfigDelivery.HOST_UNAVAILABLE, configDelivery(published().copy(
            hostQueryState = DiagnosticHostQueryState.INVALID_RESPONSE
        )))
    }

    @Test
    fun `same older and newer host generations are distinct`() {
        assertEquals(DiagnosticConfigDelivery.MATCHED, configDelivery(published()))
        assertEquals(DiagnosticConfigDelivery.HOST_OLDER, configDelivery(published().copy(hostConfigGeneration = 41L)))
        assertEquals(DiagnosticConfigDelivery.HOST_NEWER, configDelivery(published().copy(hostConfigGeneration = 43L)))
    }

    @Test
    fun `old connection failure and pending publication cannot confirm delivery`() {
        listOf(
            published().copy(frameworkConnectionId = 2L),
            published().copy(frameworkConnected = false),
            published().copy(remotePublishPending = true),
            published().copy(remotePublishState = DiagnosticRemotePublishState.FAILED)
        ).forEach { assertEquals(DiagnosticConfigDelivery.NOT_PUBLISHED, configDelivery(it)) }
    }

    @Test
    fun `denial receipt can confirm delivery without claiming authorization`() {
        assertEquals(DiagnosticConfigDelivery.MATCHED, configDelivery(published().copy(
            hostConfigState = DiagnosticHostConfigState.NOT_AUTHORIZED
        )))
        assertEquals(DiagnosticConfigDelivery.HOST_REJECTED, configDelivery(published().copy(
            hostConfigState = DiagnosticHostConfigState.REJECTED
        )))
        assertEquals(DiagnosticConfigDelivery.HOST_NOT_CHECKED, configDelivery(published().copy(
            hostConfigState = DiagnosticHostConfigState.NOT_CHECKED
        )))
    }

    @Test
    fun `NPatch remains an independent publication path`() {
        assertEquals(DiagnosticConfigDelivery.NOT_APPLICABLE, configDelivery(published().copy(
            activationState = DiagnosticActivationState.ACTIVE_NPATCH
        )))
    }
}
