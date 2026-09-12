package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.Assert.*
import org.junit.Test

class ReceiptQueryPolicyTest {
    @Test fun `unhandled ordered broadcast is not a malformed receipt`() {
        val failure = ReceiptQueryPolicy.headerFailure(false, false, false)
        assertEquals(ReceiptQueryFailure.UNHANDLED, failure)
        assertTrue(ReceiptQueryPolicy.isUnavailable(failure))
    }
    @Test fun `handled malformed and nonce mismatches remain validation errors`() {
        assertEquals(ReceiptQueryFailure.MALFORMED_RESPONSE, ReceiptQueryPolicy.headerFailure(true, false, false))
        assertEquals(ReceiptQueryFailure.NONCE_MISMATCH, ReceiptQueryPolicy.headerFailure(true, true, false))
        assertEquals(ReceiptQueryFailure.NONE, ReceiptQueryPolicy.headerFailure(true, true, true))
        assertFalse(ReceiptQueryPolicy.isUnavailable(ReceiptQueryFailure.NONCE_MISMATCH))
        assertFalse(ReceiptQueryPolicy.isUnavailable(ReceiptQueryFailure.DIGEST_MISMATCH))
    }
    @Test fun `timeout and failed sending are unavailable not validation failures`() {
        assertTrue(ReceiptQueryPolicy.isUnavailable(ReceiptQueryFailure.TIMEOUT))
        assertTrue(ReceiptQueryPolicy.isUnavailable(ReceiptQueryFailure.SEND_FAILED))
    }
}
