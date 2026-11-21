package com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ReceiptQueryFailure
import org.junit.Assert.*
import org.junit.Test

class CommunicationCompatibilityPolicyTest {
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
        ReceiptQueryFailure.entries.filter { it !in setOf(ReceiptQueryFailure.TIMEOUT,
            ReceiptQueryFailure.SEND_FAILED, ReceiptQueryFailure.UNHANDLED) }.forEach {
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

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (CommunicationCompatibilityPolicyTest) -> Unit) {
            val s = CommunicationCompatibilityPolicyTest()
            block(s)
        }
        runIsolated { s -> s.normalGetsAllSafeConnectionImprovements() }
        runIsolated { s -> s.compatibilityHasFiniteLargerBudgets() }
        runIsolated { s -> s.malformedAndForgedResponsesNeverTriggerMorePermissiveRetries() }
        runIsolated { s -> s.onlyTheFirstUnavailableAttemptCanBeRetried() }
        runIsolated { s -> s.undecidedAndDeclinedUsersCannotActivateTheExtraEndpoint() }
    }
}
