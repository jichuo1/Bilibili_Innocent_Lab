package com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ReceiptQueryFailure
import org.junit.Assert.*
import org.junit.Test

class CommunicationCompatibilityPolicyTest {
    @Test fun normalGetsAllSafeConnectionImprovements() {
        assertEquals(1800L, CommunicationCompatibilityPolicy.binderTimeout(false))
        assertEquals(3000L, CommunicationCompatibilityPolicy.broadcastTimeout(false))
        assertEquals(6000L, CommunicationCompatibilityPolicy.managerTimeout(false))
        assertEquals(2, CommunicationCompatibilityPolicy.managerAttempts(false))
    }
    @Test fun compatibilityHasFiniteLargerBudgets() {
        assertEquals(1800L, CommunicationCompatibilityPolicy.binderTimeout(true))
        assertEquals(3000L, CommunicationCompatibilityPolicy.broadcastTimeout(true))
        assertEquals(6000L, CommunicationCompatibilityPolicy.managerTimeout(true))
        assertEquals(2, CommunicationCompatibilityPolicy.managerAttempts(true))
        assertTrue(CommunicationCompatibilityPolicy.BIND_WINDOW_MS < CommunicationCompatibilityPolicy.BIND_COOLDOWN_MS)
    }
    @Test fun malformedAndForgedResponsesNeverTriggerMorePermissiveRetries() {
        ReceiptQueryFailure.entries.filter { it !in setOf(ReceiptQueryFailure.TIMEOUT,
            ReceiptQueryFailure.SEND_FAILED, ReceiptQueryFailure.UNHANDLED) }.forEach {
            assertFalse(it.name, CommunicationCompatibilityPolicy.retryReceipt(true, 0, it))
        }
    }
    @Test fun onlyTheFirstUnavailableAttemptCanBeRetried() {
        listOf(ReceiptQueryFailure.TIMEOUT, ReceiptQueryFailure.SEND_FAILED, ReceiptQueryFailure.UNHANDLED).forEach {
            assertTrue(CommunicationCompatibilityPolicy.retryReceipt(true, 0, it))
            assertFalse(CommunicationCompatibilityPolicy.retryReceipt(true, 1, it))
            assertTrue(CommunicationCompatibilityPolicy.retryReceipt(false, 0, it))
        }
    }
    @Test fun undecidedAndDeclinedUsersCannotActivateTheExtraEndpoint() {
        assertFalse(CommunicationCompatibilityPolicy.consentAllows(false, false))
        assertTrue(CommunicationCompatibilityPolicy.consentAllows(true, false))
        assertTrue(CommunicationCompatibilityPolicy.consentAllows(false, true))
    }
}
