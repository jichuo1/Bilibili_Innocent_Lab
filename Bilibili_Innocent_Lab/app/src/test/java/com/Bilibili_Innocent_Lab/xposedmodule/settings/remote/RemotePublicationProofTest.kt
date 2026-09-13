package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote
import org.junit.Assert.*
import org.junit.Test
class RemotePublicationProofTest {
    private val identity=PublicationIdentity("0123456789abcdef",1,1,1,"a".repeat(64))
    @Test fun policySourceSettingsAndConnectionChangesBeforeSettlementRejectOldSuccess() {
        val proof=RemotePublicationProof(identity,2,3){true}
        assertTrue(proof.matches(identity,2,3))
        // 模拟 commit 已通过、通知尚未交付时，权威状态分别发生变化。
        listOf(identity.copy(policyEpoch=2),identity.copy(snapshotRevision=2),
            identity.copy(fingerprint="b".repeat(64)),identity.copy(consentRevision=2),
            identity.copy(incarnation="fedcba9876543210")).forEach {
            assertFalse(proof.matches(it,2,3))
        }
        assertFalse(proof.matches(identity,4,3)); assertFalse(proof.matches(identity,2,4))
    }
    @Test fun aCancelledOrExpiredOperationCannotSettleMatchingConsent() {
        var now=1L
        val operation=RemotePublicationAttempt(1,1,3,10,{now},{})
        val proof=RemotePublicationProof(identity,2,3,operation::isActive)
        assertTrue(proof.matches(identity,2,3)); now=11
        assertFalse(proof.matches(identity,2,3))
        now=1;operation.cancel();assertFalse(proof.matches(identity,2,3))
    }
}
