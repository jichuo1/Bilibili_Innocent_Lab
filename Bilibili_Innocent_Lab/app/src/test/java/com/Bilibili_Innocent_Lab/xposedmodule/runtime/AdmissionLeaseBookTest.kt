package com.Bilibili_Innocent_Lab.xposedmodule.runtime
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationIdentity
import org.junit.Assert.*
import org.junit.Test
class AdmissionLeaseBookTest {
    private var now = 10L
    private val book = AdmissionLeaseBook { now }
    private val identity = PublicationIdentity("0123456789abcdef", 1, 1, 1, "a".repeat(64))
    private fun lease(challenge: String = "c") = AdmissionLeaseBook.Lease(10001, 20, "nonce", challenge, "module_direct", identity, 100)
    @Test fun confirmationIsSingleUseAndBoundToProcessAndNonce() {
        assertTrue(book.prepare(lease()))
        assertNull(book.consume(10002,20,"nonce","c",identity))
        assertNull(book.consume(10001,21,"nonce","c",identity))
        assertNull(book.consume(10001,20,"forged","c",identity))
        assertNotNull(book.consume(10001,20,"nonce","c",identity))
        assertNull(book.consume(10001,20,"nonce","c",identity))
    }
    @Test fun elapsedDeadlineRejectsLateCompletion() {
        book.prepare(lease()); now = 101
        assertNull(book.consume(10001,20,"nonce","c",identity))
        assertFalse(book.prepare(lease()))
    }
    @Test fun anyChangedConsentPolicySnapshotOrPublisherInvalidatesThePreparedIdentity() {
        listOf(identity.copy(consentRevision=2), identity.copy(policyEpoch=2), identity.copy(snapshotRevision=2),
            identity.copy(incarnation="fedcba9876543210"), identity.copy(fingerprint="b".repeat(64))).forEachIndexed { i, changed ->
            val c = "c$i"; assertTrue(book.prepare(lease(c)))
            assertNull(book.consume(10001,20,"nonce",c,changed))
            assertNull(book.consume(10001,20,"nonce",c,identity))
        }
    }
    @Test fun capacityIsBoundedAndExpiredRequestsAreReclaimed() {
        repeat(64) { assertTrue(book.prepare(lease("c$it"))) }
        assertFalse(book.prepare(lease("overflow"))); now = 101
        assertTrue(book.prepare(lease("fresh").copy(deadline=200)))
    }
    @Test fun duplicateChallengesCannotReplaceAnotherProcess() {
        book.prepare(lease()); assertFalse(book.prepare(lease().copy(pid=22)))
        assertNotNull(book.consume(10001,20,"nonce","c",identity))
    }
}
