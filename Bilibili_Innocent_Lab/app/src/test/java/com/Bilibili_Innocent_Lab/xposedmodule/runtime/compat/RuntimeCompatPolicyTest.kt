package com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ReceiptQueryFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCompatPolicyTest {

    @Test
    fun communicationCompatibilityAndManagerRoutingPolicies() {
        CommunicationCompatibilityPolicyCases().verifyAllCases()
        ManagerConnectionPolicyCases().verifyAllCases()
    }

    @Test
    fun latestReceiptOutboxBoundsAndAckSemantics() {
        LatestReceiptOutboxCases().verifyAllCases()
    }
}

private class CommunicationCompatibilityPolicyCases {
    fun normalGetsAllSafeConnectionImprovements() {
        assertEquals(1800L, CommunicationCompatibilityPolicy.binderTimeout(false))
        assertEquals(3000L, CommunicationCompatibilityPolicy.broadcastTimeout(false))
        assertEquals(6000L, CommunicationCompatibilityPolicy.managerTimeout(false))
        assertEquals(2, CommunicationCompatibilityPolicy.managerAttempts(false))
    }

    fun compatibilityHasFiniteLargerBudgets() {
        assertEquals(1800L, CommunicationCompatibilityPolicy.binderTimeout(true))
        assertEquals(3000L, CommunicationCompatibilityPolicy.broadcastTimeout(true))
        assertEquals(6000L, CommunicationCompatibilityPolicy.managerTimeout(true))
        assertEquals(2, CommunicationCompatibilityPolicy.managerAttempts(true))
        assertTrue(CommunicationCompatibilityPolicy.BIND_WINDOW_MS < CommunicationCompatibilityPolicy.BIND_COOLDOWN_MS)
    }

    fun malformedAndForgedResponsesNeverTriggerMorePermissiveRetries() {
        ReceiptQueryFailure.entries.filter {
            it !in setOf(ReceiptQueryFailure.TIMEOUT, ReceiptQueryFailure.SEND_FAILED, ReceiptQueryFailure.UNHANDLED)
        }.forEach {
            assertFalse(it.name, CommunicationCompatibilityPolicy.retryReceipt(true, 0, it))
        }
    }

    fun onlyTheFirstUnavailableAttemptCanBeRetried() {
        listOf(ReceiptQueryFailure.TIMEOUT, ReceiptQueryFailure.SEND_FAILED, ReceiptQueryFailure.UNHANDLED).forEach {
            assertTrue(CommunicationCompatibilityPolicy.retryReceipt(true, 0, it))
            assertFalse(CommunicationCompatibilityPolicy.retryReceipt(true, 1, it))
            assertTrue(CommunicationCompatibilityPolicy.retryReceipt(false, 0, it))
        }
    }

    fun undecidedAndDeclinedUsersCannotActivateTheExtraEndpoint() {
        assertFalse(CommunicationCompatibilityPolicy.consentAllows(false, false))
        assertTrue(CommunicationCompatibilityPolicy.consentAllows(true, false))
        assertTrue(CommunicationCompatibilityPolicy.consentAllows(false, true))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (CommunicationCompatibilityPolicyCases) -> Unit) {
            val s = CommunicationCompatibilityPolicyCases()
            block(s)
        }
        runIsolated { s -> s.normalGetsAllSafeConnectionImprovements() }
        runIsolated { s -> s.compatibilityHasFiniteLargerBudgets() }
        runIsolated { s -> s.malformedAndForgedResponsesNeverTriggerMorePermissiveRetries() }
        runIsolated { s -> s.onlyTheFirstUnavailableAttemptCanBeRetried() }
        runIsolated { s -> s.undecidedAndDeclinedUsersCannotActivateTheExtraEndpoint() }
    }
}

private class LatestReceiptOutboxCases {
    fun lateAckCannotDeleteANewerValue() {
        val box = LatestReceiptOutbox<String>()
        box.put("d", 1, 1, "old"); box.put("d", 2, 1, "new")
        box.ack("d", 1)
        assertEquals("new", box.snapshot()["d"]?.value)
        box.ack("d", 2); assertTrue(box.snapshot().isEmpty())
    }

    fun newerProviderAckCanSupersedeOlderBoundCopy() {
        val box = LatestReceiptOutbox<String>()
        box.put("d", 3, 1, "old"); box.ack("d", 4)
        assertTrue(box.snapshot().isEmpty())
    }

    fun countAndTotalBytesAreBoundedWithoutDiscardingOtherChannels() {
        val box = LatestReceiptOutbox<String>(2, 8)
        assertTrue(box.put("a", 1, 4, "a")); assertTrue(box.put("b", 2, 4, "b"))
        assertFalse(box.put("c", 3, 1, "c")); assertFalse(box.put("a", 4, 5, "big"))
        assertEquals("a", box.snapshot()["a"]?.value)
        assertTrue(box.put("a", 5, 3, "small")); assertEquals(2, box.snapshot().size)
    }

    fun failedOrRejectedDeliveryRetainsOnlyLatestValueForTheNextEvent() {
        val box = LatestReceiptOutbox<String>()
        repeat(100) { box.put("d", it.toLong() + 1, 1, "$it") }
        assertEquals(1, box.snapshot().size); assertEquals("99", box.snapshot()["d"]?.value)
        assertFalse(box.put("d", 1, 1, "stale"))
    }

    fun invalidSizesAndRevisionsCannotBypassBounds() {
        val box = LatestReceiptOutbox<String>(1, 10)
        assertFalse(box.put("d", 0, 1, "x")); assertFalse(box.put("d", 1, -1, "x"))
        assertFalse(box.put("d", 1, Int.MAX_VALUE, "x"))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (LatestReceiptOutboxCases) -> Unit) {
            val s = LatestReceiptOutboxCases()
            block(s)
        }
        runIsolated { s -> s.lateAckCannotDeleteANewerValue() }
        runIsolated { s -> s.newerProviderAckCanSupersedeOlderBoundCopy() }
        runIsolated { s -> s.countAndTotalBytesAreBoundedWithoutDiscardingOtherChannels() }
        runIsolated { s -> s.failedOrRejectedDeliveryRetainsOnlyLatestValueForTheNextEvent() }
        runIsolated { s -> s.invalidSizesAndRevisionsCannotBypassBounds() }
    }
}

private class ManagerConnectionPolicyCases {
    fun eachAdaptedRootFamilyUsesItsActualPushProtocol() {
        listOf("LSPosed", "LSPosed-Irena", "Irena", "Vector").forEach {
            assertEquals(
                it,
                ManagerConnectionPolicy.Route.FRAMEWORK_PUSH,
                ManagerConnectionPolicy.route(it, false, ManagerConnectionPolicy.packages)
            )
        }
    }

    fun lspatchPullNeverMeansNPatchTakeover() {
        assertEquals(ManagerConnectionPolicy.Route.LSPATCH_PULL, ManagerConnectionPolicy.route("LSPatch", false, ManagerConnectionPolicy.packages))
        assertEquals(ManagerConnectionPolicy.Route.SELECTED_NPATCH, ManagerConnectionPolicy.route("", true, ManagerConnectionPolicy.packages))
        assertEquals(ManagerConnectionPolicy.Route.LSPATCH_PULL, ManagerConnectionPolicy.route("LSPatch", true, ManagerConnectionPolicy.packages))
        // NPatch embed 可与唯一安装的 LSPatch 管理器并存；仅安装列表不能抢占已选择的通道。
        assertEquals(ManagerConnectionPolicy.Route.SELECTED_NPATCH, ManagerConnectionPolicy.route("", true, setOf("org.lsposed.lspatch")))
    }

    fun absentRootServiceDoesNotSelectAnInstalledNPatch() {
        assertEquals(ManagerConnectionPolicy.Route.FRAMEWORK_PUSH, ManagerConnectionPolicy.route("", false, setOf("top.nkbe.npatch")))
        assertEquals(ManagerConnectionPolicy.Route.AMBIGUOUS, ManagerConnectionPolicy.route("", false, ManagerConnectionPolicy.packages))
    }

    fun onlyUnambiguousLspatchCanBeActivelyDiscoveredWithoutMetadata() {
        assertEquals(ManagerConnectionPolicy.Route.LSPATCH_PULL, ManagerConnectionPolicy.route("", false, setOf("org.lsposed.lspatch")))
        assertEquals(ManagerConnectionPolicy.Route.FRAMEWORK_PUSH, ManagerConnectionPolicy.route("unknown", false, emptySet()))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ManagerConnectionPolicyCases) -> Unit) {
            val s = ManagerConnectionPolicyCases()
            block(s)
        }
        runIsolated { s -> s.eachAdaptedRootFamilyUsesItsActualPushProtocol() }
        runIsolated { s -> s.lspatchPullNeverMeansNPatchTakeover() }
        runIsolated { s -> s.absentRootServiceDoesNotSelectAnInstalledNPatch() }
        runIsolated { s -> s.onlyUnambiguousLspatchCanBeActivelyDiscoveredWithoutMetadata() }
    }
}
