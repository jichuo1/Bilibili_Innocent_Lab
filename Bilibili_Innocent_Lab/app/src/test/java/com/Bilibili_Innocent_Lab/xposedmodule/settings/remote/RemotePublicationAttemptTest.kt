package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote

import org.junit.Assert.*
import org.junit.Test

class RemotePublicationAttemptTest {
    @Test fun onlyOneCurrentResultIsDeliveredAndCancellationDoesNotPretendRollback() {
        var now=1L; val results=mutableListOf<RemoteHookConfigPublishResult>()
        val attempt=RemotePublicationAttempt(1,2,3,10,{now},{results.add(it)})
        val success=RemoteHookConfigPublishResult.Success(1,true)
        assertTrue(attempt.complete(success)); assertFalse(attempt.complete(success)); assertEquals(1,results.size)
        val cancelled=RemotePublicationAttempt(2,2,3,10,{now},{results.add(it)})
        cancelled.cancel(); assertFalse(cancelled.complete(success)); assertEquals(1,results.size)
        val late=RemotePublicationAttempt(3,2,3,10,{now},{results.add(it)})
        now=11; assertFalse(late.isActive()); assertFalse(late.complete(success)); assertEquals(1,results.size)
    
        RemotePublicationProofCases().verifyAllCases()
        ServiceCandidateGateCases().verifyAllCases()
    }
}

private class RemotePublicationProofCases {
    private val identity=PublicationIdentity("0123456789abcdef",1,1,1,"a".repeat(64))
    fun policySourceSettingsAndConnectionChangesBeforeSettlementRejectOldSuccess() {
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
    fun aCancelledOrExpiredOperationCannotSettleMatchingConsent() {
        var now=1L
        val operation=RemotePublicationAttempt(1,1,3,10,{now},{})
        val proof=RemotePublicationProof(identity,2,3,operation::isActive)
        assertTrue(proof.matches(identity,2,3)); now=11
        assertFalse(proof.matches(identity,2,3))
        now=1;operation.cancel();assertFalse(proof.matches(identity,2,3))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (RemotePublicationProofCases) -> Unit) {
            val s = RemotePublicationProofCases()
            block(s)
        }
        runIsolated { s -> s.policySourceSettingsAndConnectionChangesBeforeSettlementRejectOldSuccess() }
        runIsolated { s -> s.aCancelledOrExpiredOperationCannotSettleMatchingConsent() }
    }
}

private class ServiceCandidateGateCases {
    fun deathOfOldActiveServiceDoesNotCancelValidationOfItsReplacement() {
        val gate=ServiceCandidateGate<Any>(); val a=Any(); val b=Any()
        gate.arrive(a); val pausedB=gate.arrive(b)
        gate.remove(a)
        assertTrue(gate.matches(b,pausedB))
    }
    fun deadPendingCandidateCannotBePromotedAndLateOldDeathIsHarmless() {
        val gate=ServiceCandidateGate<Any>(); val a=Any(); val b=Any()
        val ta=gate.arrive(a); val tb=gate.arrive(b)
        assertFalse(gate.matches(a,ta)); gate.remove(b)
        assertFalse(gate.matches(b,tb))
        val fresh=gate.arrive(b); gate.remove(a)
        assertTrue(gate.matches(b,fresh))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ServiceCandidateGateCases) -> Unit) {
            val s = ServiceCandidateGateCases()
            block(s)
        }
        runIsolated { s -> s.deathOfOldActiveServiceDoesNotCancelValidationOfItsReplacement() }
        runIsolated { s -> s.deadPendingCandidateCannotBePromotedAndLateOldDeathIsHarmless() }
    }
}
