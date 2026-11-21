package com.Bilibili_Innocent_Lab.xposedmodule.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticConfigDeliveryTest {
    private fun published() = inputs().copy(
        frameworkConnectionId = 1L, remoteConnectionId = 1L,
        hostRuntimeReceiptAvailable = true, hostQueryState = DiagnosticHostQueryState.READY,
        hostConfigState = DiagnosticHostConfigState.ACCEPTED, hostConfigGeneration = 42L
    )

    fun `successful publication is not a host receipt`() {
        assertEquals(DiagnosticConfigDelivery.HOST_UNAVAILABLE, configDelivery(published().copy(
            hostRuntimeReceiptAvailable = false
        )))
        assertEquals(DiagnosticConfigDelivery.HOST_UNAVAILABLE, configDelivery(published().copy(
            hostQueryState = DiagnosticHostQueryState.INVALID_RESPONSE
        )))
    }

    fun `same older and newer host generations are distinct`() {
        assertEquals(DiagnosticConfigDelivery.MATCHED, configDelivery(published()))
        assertEquals(DiagnosticConfigDelivery.HOST_OLDER, configDelivery(published().copy(hostConfigGeneration = 41L)))
        assertEquals(DiagnosticConfigDelivery.HOST_NEWER, configDelivery(published().copy(hostConfigGeneration = 43L)))
    }

    fun `old connection failure and pending publication cannot confirm delivery`() {
        listOf(
            published().copy(frameworkConnectionId = 2L),
            published().copy(frameworkConnected = false),
            published().copy(remotePublishPending = true),
            published().copy(remotePublishState = DiagnosticRemotePublishState.FAILED)
        ).forEach { assertEquals(DiagnosticConfigDelivery.NOT_PUBLISHED, configDelivery(it)) }
    }

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

    fun `NPatch selection never compares the host admission with the bypassed standard publisher`() {
        // 2026-09-30 TB320FC：NPatch 已选中、宿主 admission 存在但对不上标准发布权威，
        // 旧实现得出 HOST_OLDER（“宿主仍使用代次 X，模块已提交代次 0”），实际投递是通的。
        val npatchSelected = published().copy(
            noRootDesiredEnabled = true,
            hostAdmissionPresent = true,
            hostAdmissionCurrent = false,
            remotePublishState = DiagnosticRemotePublishState.FAILED,
            remoteGeneration = 0L
        )
        assertEquals(DiagnosticConfigDelivery.NOT_APPLICABLE, configDelivery(npatchSelected))
        // 未选 NPatch 的既有语义不变：admission 对不上仍是 HOST_OLDER。
        assertEquals(
            DiagnosticConfigDelivery.HOST_OLDER,
            configDelivery(npatchSelected.copy(noRootDesiredEnabled = false))
        )
        // 兼容模式直连另有自己的判据，不被 NPatch 选中遮住。
        assertEquals(
            DiagnosticConfigDelivery.DIRECT_STALE,
            configDelivery(
                npatchSelected.copy(
                    hostConfigSource = "module_direct",
                    hostRuntimeReceiptAvailable = true,
                    hostQueryState = DiagnosticHostQueryState.READY,
                    hostConfigState = DiagnosticHostConfigState.ACCEPTED
                )
            )
        )
    }

    fun `NPatch remains an independent publication path`() {
        assertEquals(DiagnosticConfigDelivery.NOT_APPLICABLE, configDelivery(published().copy(
            activationState = DiagnosticActivationState.ACTIVE_NPATCH
        )))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (DiagnosticConfigDeliveryTest) -> Unit) {
            val s = DiagnosticConfigDeliveryTest()
            block(s)
        }
        runIsolated { s -> s.`successful publication is not a host receipt`() }
        runIsolated { s -> s.`same older and newer host generations are distinct`() }
        runIsolated { s -> s.`old connection failure and pending publication cannot confirm delivery`() }
        runIsolated { s -> s.`denial receipt can confirm delivery without claiming authorization`() }
        runIsolated { s -> s.`NPatch selection never compares the host admission with the bypassed standard publisher`() }
        runIsolated { s -> s.`NPatch remains an independent publication path`() }
    }
}
