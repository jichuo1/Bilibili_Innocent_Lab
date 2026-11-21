package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostAdmissionLocalIdentityTest {
    fun hostCanVerifyItselfWhenModulePmCannotSeeTheHost() {
        val trust = HostAdmissionLocalIdentity.verify(
            modulePackageName = "com.module",
            hostUid = 10086,
            moduleUid = 10100,
            hostPackageUid = 10086,
            expectedModulePackage = "com.module",
        )
        assertEquals(HostAdmissionLocalIdentity.Trust.Verified(10086), trust)
    }

    fun mismatchOrSameUidIsUnavailableNotDenied() {
        assertEquals(
            HostAdmissionLocalIdentity.Trust.Unavailable,
            HostAdmissionLocalIdentity.verify("other", 1, 2, 1, "com.module"),
        )
        assertEquals(
            HostAdmissionLocalIdentity.Trust.Unavailable,
            HostAdmissionLocalIdentity.verify("com.module", 10086, 10086, 10086, "com.module"),
        )
        assertEquals(
            HostAdmissionLocalIdentity.Trust.Unavailable,
            HostAdmissionLocalIdentity.verify("com.module", 10086, 10100, 10000, "com.module"),
        )
    }

    fun missingUidsAreUnavailableNotDenied() {
        assertEquals(
            HostAdmissionLocalIdentity.Trust.Unavailable,
            HostAdmissionLocalIdentity.verify("com.module", null, 2, 1, "com.module"),
        )
        assertEquals(
            HostAdmissionLocalIdentity.Trust.Unavailable,
            HostAdmissionLocalIdentity.verify("com.module", 1, 2, null, "com.module"),
        )
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostAdmissionLocalIdentityTest) -> Unit) {
            val s = HostAdmissionLocalIdentityTest()
            block(s)
        }
        runIsolated { s -> s.hostCanVerifyItselfWhenModulePmCannotSeeTheHost() }
        runIsolated { s -> s.mismatchOrSameUidIsUnavailableNotDenied() }
        runIsolated { s -> s.missingUidsAreUnavailableNotDenied() }
    
        HostAdmissionRetryPolicyCases().verifyAllCases()
        HostAdmissionRouteClassifierCases().verifyAllCases()
        HostAdmissionRoutePlanCases().verifyAllCases()
        HostAdmissionSourcePolicyCases().verifyAllCases()
        HostAdmissionSourcePolicyTruthTableCases().verifyAllCases()
    }
}

private class HostAdmissionRetryPolicyCases {
    fun repeatedForegroundFailuresHaveBackoffAndAFiniteAttemptBudget() {
        val policy = HostAdmissionRetryPolicy(0L)
        var now = 0L
        repeat(HostAdmissionRetryPolicy.MAX_ATTEMPTS) {
            val delay = checkNotNull(policy.nextDelay(now))
            if (it > 0) assertTrue(delay > 0L)
            now += delay
            assertNotNull(policy.begin(now))
        }
        assertNull(policy.nextDelay(now))
        assertNull(policy.begin(now))
    }

    fun pauseInvalidatesOldResultsAndAFreshForegroundAttemptCanRecover() {
        val policy = HostAdmissionRetryPolicy(0L)
        val old = checkNotNull(policy.begin(0L))
        policy.invalidate()
        assertFalse(policy.accepts(old, 1L))
        val current = checkNotNull(policy.begin(1L))
        assertTrue(policy.accepts(current, 2L))
        assertFalse(policy.accepts(old, 2L))
    }

    fun expiryAndCloseRejectEvenASuccessfulPendingReply() {
        val policy = HostAdmissionRetryPolicy(0L)
        val token = checkNotNull(policy.begin(0L))
        assertFalse(policy.accepts(token, policy.deadline))
        assertNull(policy.nextDelay(policy.deadline))
        policy.close()
        assertFalse(policy.accepts(token, 1L))
        assertNull(policy.begin(1L))
    }

    fun syncAndTransportFailuresAreRetryableButActualDenialIsNot() {
        for (reason in listOf(HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED,
            HostAdmissionRouteClassifier.REASON_CONSENT_PENDING, HostAdmissionRouteClassifier.REASON_UNAVAILABLE,
            HostAdmissionRouteClassifier.REASON_RESPONSE_INVALID, HostAdmissionRouteClassifier.REASON_TIMEOUT)) {
            assertTrue(HostAdmissionRouteClassifier.retryable(reason))
        }
        assertFalse(HostAdmissionRouteClassifier.retryable(HostAdmissionRouteClassifier.REASON_DENIED))
        assertFalse(HostAdmissionRouteClassifier.retryable("unknown"))
    }

    fun pauseAndCloseRemoveQueuedDeliveriesAndRejectFurtherPosting() {
        for (close in listOf(false, true)) {
            val queued = mutableListOf<Runnable>()
            val policy = HostAdmissionRetryPolicy(0L) { queued.remove(it) }
            val token = checkNotNull(policy.begin(0L))
            val task = Runnable { fail("cancelled delivery must not run") }
            assertTrue(policy.postDelivery(token, 0L, task) { queued.add(it) })
            if (close) policy.close() else policy.invalidate()
            assertTrue(queued.isEmpty())
            assertFalse(policy.takeDelivery(token, 1L, task))
            assertFalse(policy.postDelivery(token, 1L, task) { queued.add(it) })
            assertTrue(queued.isEmpty())
        }
    }

    fun replacingADeliveryAndPostingFailuresCannotRetainAnOldResult() {
        val queued = mutableListOf<Runnable>()
        val policy = HostAdmissionRetryPolicy(0L) { queued.remove(it) }
        val token = checkNotNull(policy.begin(0L))
        val old = Runnable { }
        val current = Runnable { }
        assertTrue(policy.postDelivery(token, 0L, old) { queued.add(it) })
        assertTrue(policy.postDelivery(token, 0L, current) { queued.add(it) })
        assertEquals(listOf(current), queued)
        assertFalse(policy.takeDelivery(token, 1L, old))
        queued.remove(current)
        assertTrue(policy.takeDelivery(token, 1L, current))
        assertFalse(policy.takeDelivery(token, 1L, current))
        assertFalse(policy.postDelivery(token, 1L, old) { false })
        assertFalse(policy.takeDelivery(token, 1L, old))
        assertFalse(policy.postDelivery(token, 1L, old) { error("scheduler failed") })
        assertFalse(policy.takeDelivery(token, 1L, old))
    }

    fun anExpiredQueuedSuccessCannotBeConsumed() {
        val policy = HostAdmissionRetryPolicy(0L)
        val token = checkNotNull(policy.begin(0L))
        val task = Runnable { }
        assertTrue(policy.postDelivery(token, 0L, task) { true })
        assertFalse(policy.takeDelivery(token, policy.deadline, task))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostAdmissionRetryPolicyCases) -> Unit) {
            val s = HostAdmissionRetryPolicyCases()
            block(s)
        }
        runIsolated { s -> s.repeatedForegroundFailuresHaveBackoffAndAFiniteAttemptBudget() }
        runIsolated { s -> s.pauseInvalidatesOldResultsAndAFreshForegroundAttemptCanRecover() }
        runIsolated { s -> s.expiryAndCloseRejectEvenASuccessfulPendingReply() }
        runIsolated { s -> s.syncAndTransportFailuresAreRetryableButActualDenialIsNot() }
        runIsolated { s -> s.pauseAndCloseRemoveQueuedDeliveriesAndRejectFurtherPosting() }
        runIsolated { s -> s.replacingADeliveryAndPostingFailuresCannotRetainAnOldResult() }
        runIsolated { s -> s.anExpiredQueuedSuccessCannotBeConsumed() }
    }
}

private class HostAdmissionRouteClassifierCases {
    fun disabledRevisionRequiresABoundExplicitCloseResponse() {
        assertEquals(42L, HostAdmissionRouteClassifier.disabledRevision("manager_sync_required", true, 42L))
        assertEquals(0L, HostAdmissionRouteClassifier.disabledRevision("manager_sync_required", false, 42L))
        assertEquals(0L, HostAdmissionRouteClassifier.disabledRevision("denied", true, 42L))
        assertEquals(0L, HostAdmissionRouteClassifier.disabledRevision("stale", true, 42L))
        assertEquals(0L, HostAdmissionRouteClassifier.disabledRevision("granted", true, 42L))
        assertEquals(0L, HostAdmissionRouteClassifier.disabledRevision("manager_sync_required", true, -1L))
    }
    fun npatchRequiresFreshAdmissionWhileStandardFrameworkKeepsValidatedFallback() {
        fun allowed(npatch: Boolean) = HostAdmissionRouteClassifier.allowRemoteConfigFallback(
            false, null, true, true, 1L, nPatchRuntime = npatch)
        assertFalse(allowed(true))
        assertTrue(allowed(false))
    }
    fun endpointStatusesAreClassifiedExhaustively() {
        val expected = mapOf(
            "prepared" to HostAdmissionRouteClassifier.StatusClass.PREPARED,
            "granted" to HostAdmissionRouteClassifier.StatusClass.GRANTED,
            "denied" to HostAdmissionRouteClassifier.StatusClass.AUTHORITY_DENIED,
            "consent_commit_failed" to HostAdmissionRouteClassifier.StatusClass.CONSENT_PENDING,
            "manager_sync_required" to HostAdmissionRouteClassifier.StatusClass.SYNC_REQUIRED,
            "embedded_mode_unsupported" to HostAdmissionRouteClassifier.StatusClass.AUTHORITY_DENIED,
            "identity_rejected" to HostAdmissionRouteClassifier.StatusClass.UNAVAILABLE,
            "protocol_rejected" to HostAdmissionRouteClassifier.StatusClass.UNAVAILABLE,
            "storage_failed" to HostAdmissionRouteClassifier.StatusClass.UNAVAILABLE,
            "stale" to HostAdmissionRouteClassifier.StatusClass.UNAVAILABLE,
            "busy" to HostAdmissionRouteClassifier.StatusClass.UNAVAILABLE,
            null to HostAdmissionRouteClassifier.StatusClass.UNAVAILABLE,
            "unexpected" to HostAdmissionRouteClassifier.StatusClass.UNAVAILABLE,
        )
        expected.forEach { (status, kind) ->
            assertEquals(status, kind, HostAdmissionRouteClassifier.classify(status))
        }
    }

    fun identityRejectedWithEmptyNonceIsUnavailableNotDenied() {
        val outcome = HostAdmissionRouteClassifier.prepare("identity_rejected", envelopeValid = false)
        assertEquals(HostAdmissionRouteClassifier.Outcome.UNAVAILABLE, outcome)
        assertEquals(
            HostAdmissionRouteClassifier.REASON_UNAVAILABLE,
            HostAdmissionRouteClassifier.reason(outcome)
        )
        assertFalse(HostAdmissionRouteClassifier.latchesDenial("admission_timeout"))
        assertFalse(HostAdmissionRouteClassifier.latchesDenial("admission_unavailable"))
        assertFalse(HostAdmissionRouteClassifier.latchesDenial(""))
    }

    fun termsDenialIsDistinctFromRecoverableSyncAndCommitFailures() {
        assertEquals(HostAdmissionRouteClassifier.Outcome.AUTHORITY_DENIED,
            HostAdmissionRouteClassifier.prepare("embedded_mode_unsupported", envelopeValid = true))
        assertEquals(HostAdmissionRouteClassifier.Outcome.AUTHORITY_DENIED,
            HostAdmissionRouteClassifier.confirm("embedded_mode_unsupported", envelopeValid = true, successBinding = false))
        assertEquals(
            HostAdmissionRouteClassifier.Outcome.AUTHORITY_DENIED,
            HostAdmissionRouteClassifier.prepare("denied", envelopeValid = true)
        )
        assertEquals(
            HostAdmissionRouteClassifier.Outcome.CONSENT_PENDING,
            HostAdmissionRouteClassifier.prepare("consent_commit_failed", envelopeValid = true)
        )
        assertEquals(
            HostAdmissionRouteClassifier.Outcome.SYNC_REQUIRED,
            HostAdmissionRouteClassifier.prepare("manager_sync_required", envelopeValid = true)
        )
        assertTrue(HostAdmissionRouteClassifier.latchesDenial(HostAdmissionRouteClassifier.REASON_DENIED))
        assertFalse(
            HostAdmissionRouteClassifier.allowRemoteConfigFallback(
                sawDenied = true,
                normalFailure = null,
                authorized = true,
                moduleVersionMatches = true,
                generation = 1L,
            )
        )
    }

    fun transportTimeoutWithValidRemotePrefAllowsFallback() {
        assertTrue(
            HostAdmissionRouteClassifier.allowRemoteConfigFallback(
                sawDenied = false,
                normalFailure = null,
                authorized = true,
                moduleVersionMatches = true,
                generation = 1L,
            )
        )
        assertFalse(
            HostAdmissionRouteClassifier.allowRemoteConfigFallback(
                sawDenied = false,
                normalFailure = "remote_group_unavailable",
                authorized = true,
                moduleVersionMatches = true,
                generation = 1L,
            )
        )
        assertFalse(
            HostAdmissionRouteClassifier.allowRemoteConfigFallback(
                sawDenied = false,
                normalFailure = null,
                authorized = true,
                moduleVersionMatches = false,
                generation = 1L,
            )
        )
    }

    fun malformedEnvelopeIsUnavailableSoOtherRoutesCanContinue() {
        assertEquals(
            HostAdmissionRouteClassifier.Outcome.UNAVAILABLE,
            HostAdmissionRouteClassifier.prepare("prepared", envelopeValid = false)
        )
        assertFalse(
            HostAdmissionRouteClassifier.latchesDenial(HostAdmissionRouteClassifier.REASON_UNAVAILABLE)
        )
    }

    fun confirmGrantedRequiresBindingButDoesNotLatchDenial() {
        assertEquals(
            HostAdmissionRouteClassifier.Outcome.CONTINUE,
            HostAdmissionRouteClassifier.confirm("granted", envelopeValid = true, successBinding = true)
        )
        assertEquals(
            HostAdmissionRouteClassifier.Outcome.UNAVAILABLE,
            HostAdmissionRouteClassifier.confirm("granted", envelopeValid = true, successBinding = false)
        )
        assertEquals(
            HostAdmissionRouteClassifier.Outcome.UNAVAILABLE,
            HostAdmissionRouteClassifier.confirm("identity_rejected", envelopeValid = false, successBinding = false)
        )
        assertEquals(
            HostAdmissionRouteClassifier.Outcome.UNAVAILABLE,
            HostAdmissionRouteClassifier.confirm("stale", envelopeValid = true, successBinding = false)
        )
        assertFalse(HostAdmissionRouteClassifier.latchesDenial(HostAdmissionRouteClassifier.REASON_UNAVAILABLE))
    }

    fun invalidNegativeResponsesCannotLatchDenialOrSync() {
        for (status in listOf("denied", "manager_sync_required", "consent_commit_failed")) {
            assertEquals(HostAdmissionRouteClassifier.Outcome.UNAVAILABLE,
                HostAdmissionRouteClassifier.prepare(status, envelopeValid = false))
            assertEquals(HostAdmissionRouteClassifier.Outcome.UNAVAILABLE,
                HostAdmissionRouteClassifier.confirm(status, envelopeValid = false, successBinding = false))
        }
        assertEquals(HostAdmissionRouteClassifier.Outcome.AUTHORITY_DENIED,
            HostAdmissionRouteClassifier.confirm("denied", envelopeValid = true, successBinding = false))
    }

    fun recoverableAuthorityFailuresStillBlockOldRemoteFallback() {
        for (reason in listOf(HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED,
            HostAdmissionRouteClassifier.REASON_CONSENT_PENDING, HostAdmissionRouteClassifier.REASON_CONFIG_INVALID)) {
            assertFalse(HostAdmissionRouteClassifier.latchesDenial(reason))
            assertTrue(HostAdmissionRouteClassifier.retryable(reason))
            assertFalse(HostAdmissionRouteClassifier.allowRemoteConfigFallback(false, null, true, true, 1L,
                fallbackBlocked = HostAdmissionRouteClassifier.blocksFallback(reason)))
        }
        assertFalse(HostAdmissionRouteClassifier.retryable(HostAdmissionRouteClassifier.REASON_DENIED))
        assertFalse(HostAdmissionRouteClassifier.allowRemoteConfigFallback(false, null, false, true, 1L))
        assertFalse(HostAdmissionRouteClassifier.allowRemoteConfigFallback(false, null, true, true, 0L))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostAdmissionRouteClassifierCases) -> Unit) {
            val s = HostAdmissionRouteClassifierCases()
            block(s)
        }
        runIsolated { s -> s.disabledRevisionRequiresABoundExplicitCloseResponse() }
        runIsolated { s -> s.npatchRequiresFreshAdmissionWhileStandardFrameworkKeepsValidatedFallback() }
        runIsolated { s -> s.endpointStatusesAreClassifiedExhaustively() }
        runIsolated { s -> s.identityRejectedWithEmptyNonceIsUnavailableNotDenied() }
        runIsolated { s -> s.termsDenialIsDistinctFromRecoverableSyncAndCommitFailures() }
        runIsolated { s -> s.transportTimeoutWithValidRemotePrefAllowsFallback() }
        runIsolated { s -> s.malformedEnvelopeIsUnavailableSoOtherRoutesCanContinue() }
        runIsolated { s -> s.confirmGrantedRequiresBindingButDoesNotLatchDenial() }
        runIsolated { s -> s.invalidNegativeResponsesCannotLatchDenialOrSync() }
        runIsolated { s -> s.recoverableAuthorityFailuresStillBlockOldRemoteFallback() }
    }
}

private class HostAdmissionRoutePlanCases {
    fun binderIsRequiredOnlyFromApi29() {
        assertFalse(HostAdmissionRoutePlan.binderRequired(28))
        assertTrue(HostAdmissionRoutePlan.binderRequired(29))
        assertTrue(HostAdmissionRoutePlan.binderRequired(37))
    }

    fun localFailureStartsBroadcast() {
        assertEquals(
            HostAdmissionRoutePlan.FollowUp.START_BROADCAST,
            HostAdmissionRoutePlan.followUp(
                completedRoute = HostAdmissionRoutePlan.LOCAL,
                broadcastStarted = false,
            ),
        )
        assertEquals(
            HostAdmissionRoutePlan.FollowUp.NONE,
            HostAdmissionRoutePlan.followUp(
                completedRoute = HostAdmissionRoutePlan.LOCAL,
                broadcastStarted = true,
            ),
        )
    }

    fun hangingProviderOrBinderDoesNotBlockOtherFallbacks() {
        assertEquals(
            HostAdmissionRoutePlan.FollowUp.NONE,
            HostAdmissionRoutePlan.followUp(
                completedRoute = HostAdmissionRoutePlan.PROVIDER,
                broadcastStarted = false,
            ),
        )
        assertEquals(
            HostAdmissionRoutePlan.FollowUp.NONE,
            HostAdmissionRoutePlan.followUp(
                completedRoute = HostAdmissionRoutePlan.BINDER,
                broadcastStarted = false,
            ),
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostAdmissionRoutePlanCases) -> Unit) {
            val s = HostAdmissionRoutePlanCases()
            block(s)
        }
        runIsolated { s -> s.binderIsRequiredOnlyFromApi29() }
        runIsolated { s -> s.localFailureStartsBroadcast() }
        runIsolated { s -> s.hangingProviderOrBinderDoesNotBlockOtherFallbacks() }
    }
}

private class HostAdmissionSourcePolicyCases {
    fun lspatchUsesCurrentStandardDocumentRegardlessOfOldNpatchSelectionOrMirror() {
        for (selected in listOf(false, true)) for (revision in listOf(0L, 7L)) {
            assertEquals(HostAdmissionContract.DIRECT, HostAdmissionSourcePolicy.select(
                selected, revision, "current", "old", true, false, lspatchChannel = true))
            assertNull(HostAdmissionSourcePolicy.select(
                selected, revision, "current", "current", false, false, lspatchChannel = true))
        }
    }

    fun npatchPrefersCurrentDirectDocumentWithoutCompatibilityModeOrRemoteMirror() {
        for (revision in listOf(0L, 7L)) {
            for (fingerprint in listOf(null, "old", "current")) {
                assertEquals("module_direct", HostAdmissionSourcePolicy.select(
                    true, revision, "current", fingerprint, true, false, nPatchChannel = true))
            }
        }
    }

    fun oldNPatchDocumentCannotBeUsedAfterTurningOffItsSelectedSource() {
        assertEquals("manager",HostAdmissionSourcePolicy.select(true,7,"x","x",false,false))
        assertNull(HostAdmissionSourcePolicy.select(false,7,"x","x",true,false))
        assertNull(HostAdmissionSourcePolicy.select(true,0,"x","x",true,false))
    }

    fun modeOffRejectsDirectButKeepsCurrentNormalDocument() {
        for (mode in listOf(false,true)) assertEquals("manager",HostAdmissionSourcePolicy.select(false,0,"x","x",true,mode))
        assertEquals("module_direct",HostAdmissionSourcePolicy.select(false,0,"new","old",true,true))
        assertNull(HostAdmissionSourcePolicy.select(false,0,"new","old",true,false))
        assertNull(HostAdmissionSourcePolicy.select(false,0,"new","old",false,true))
    }

    fun noRootSwitchIsTheMasterSwitchForTheNpatchChannel() {
        // B1 回归防线：关闭态 + NPatch 通道，NORMAL 与 DIRECT 都不再发放授权。
        for (revision in listOf(0L, 7L)) {
            for (direct in listOf(false, true)) {
                assertNull(
                    "revision=$revision direct=$direct",
                    HostAdmissionSourcePolicy.select(
                        noRootSelected = false,
                        normalNoRootRevision = revision,
                        expectedFingerprint = "x",
                        normalFingerprint = "x",
                        allowDirect = true,
                        directEnabled = direct,
                        nPatchChannel = true
                    )
                )
            }
        }
        // 开启态不受影响。
        assertEquals(
            "manager",
            HostAdmissionSourcePolicy.select(
                noRootSelected = true,
                normalNoRootRevision = 7,
                expectedFingerprint = "x",
                normalFingerprint = "x",
                allowDirect = false,
                directEnabled = false,
                nPatchChannel = true
            )
        )
    }

    fun unknownChannelFallsBackToTheDocumentKind() {
        // 框架元数据未就绪时（nPatchChannel=false）退回文档判据，LSPosed 不能被误伤。
        assertEquals(
            "manager",
            HostAdmissionSourcePolicy.select(
                noRootSelected = false,
                normalNoRootRevision = 0,
                expectedFingerprint = "x",
                normalFingerprint = "x",
                allowDirect = false,
                directEnabled = false,
                nPatchChannel = false
            )
        )
        // 免 Root 文档在关闭态下仍然被拒（不依赖框架元数据）。
        assertNull(
            HostAdmissionSourcePolicy.select(
                noRootSelected = false,
                normalNoRootRevision = 7,
                expectedFingerprint = "x",
                normalFingerprint = "x",
                allowDirect = true,
                directEnabled = true,
                nPatchChannel = false
            )
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostAdmissionSourcePolicyCases) -> Unit) {
            val s = HostAdmissionSourcePolicyCases()
            block(s)
        }
        runIsolated { s -> s.lspatchUsesCurrentStandardDocumentRegardlessOfOldNpatchSelectionOrMirror() }
        runIsolated { s -> s.npatchPrefersCurrentDirectDocumentWithoutCompatibilityModeOrRemoteMirror() }
        runIsolated { s -> s.oldNPatchDocumentCannotBeUsedAfterTurningOffItsSelectedSource() }
        runIsolated { s -> s.modeOffRejectsDirectButKeepsCurrentNormalDocument() }
        runIsolated { s -> s.noRootSwitchIsTheMasterSwitchForTheNpatchChannel() }
        runIsolated { s -> s.unknownChannelFallsBackToTheDocumentKind() }
    }
}

private class HostAdmissionSourcePolicyTruthTableCases {
    private data class Input(
        val noRootSelected: Boolean, val noRootDocument: Boolean, val fingerprintMatches: Boolean,
        val allowDirect: Boolean, val directEnabled: Boolean, val nPatch: Boolean, val lspatch: Boolean
    )

    private val all: List<Input> = buildList {
        val flags = listOf(false, true)
        for (selected in flags) for (document in flags) for (fingerprint in flags) for (allow in flags)
            for (enabled in flags) for (nPatch in flags) for (lspatch in flags)
                add(Input(selected, document, fingerprint, allow, enabled, nPatch, lspatch))
    }

    private fun Input.select() = HostAdmissionSourcePolicy.select(
        noRootSelected = noRootSelected,
        normalNoRootRevision = if (noRootDocument) 7L else 0L,
        expectedFingerprint = "current",
        normalFingerprint = if (fingerprintMatches) "current" else "old",
        allowDirect = allowDirect,
        directEnabled = directEnabled,
        nPatchChannel = nPatch,
        lspatchChannel = lspatch
    )

    /** 改动前（main）的实现，原样保留作为标准通道的对照基准。 */
    private fun Input.legacy(): String? = when {
        noRootSelected == noRootDocument && fingerprintMatches -> HostAdmissionContract.NORMAL
        allowDirect && directEnabled -> HostAdmissionContract.DIRECT
        else -> null
    }

    fun theTableCoversEveryCombination() {
        assertEquals(128, all.size)
        assertEquals(128, all.toSet().size)
    }

    fun lspatchOnlyEverGrantsTheDirectSourceAndOnlyWhenAsked() {
        for (input in all.filter { it.lspatch }) {
            assertEquals(input.toString(),
                if (input.allowDirect) HostAdmissionContract.DIRECT else null, input.select())
        }
    }

    fun theNoRootSwitchIsTotalForNPatchAndForAnyNoRootDocument() {
        for (input in all.filter { !it.lspatch && !it.noRootSelected && (it.nPatch || it.noRootDocument) }) {
            assertNull(input.toString(), input.select())
        }
    }

    fun nPatchPrefersTheCurrentDirectDocumentWhenSwitchedOn() {
        for (input in all.filter { !it.lspatch && it.nPatch && it.noRootSelected && it.allowDirect }) {
            assertEquals(input.toString(), HostAdmissionContract.DIRECT, input.select())
        }
    }

    fun theStandardChannelBehavesExactlyAsBeforeExceptForTheClosedNoRootDocument() {
        for (input in all.filter { !it.lspatch && !it.nPatch }) {
            val closedNoRootDocument = !input.noRootSelected && input.noRootDocument
            if (closedNoRootDocument) assertNull(input.toString(), input.select())
            else assertEquals(input.toString(), input.legacy(), input.select())
        }
    }

    fun grantsAreAlwaysJustifiedByTheInputs() {
        for (input in all) {
            val result = input.select()
            assertTrue(input.toString(), result in setOf(null, HostAdmissionContract.NORMAL, HostAdmissionContract.DIRECT))
            // A normal grant needs a document that matches the current publication.
            if (result == HostAdmissionContract.NORMAL) assertTrue(input.toString(), input.fingerprintMatches)
            // A direct grant is only ever issued to a host that asked for it.
            if (result == HostAdmissionContract.DIRECT) assertTrue(input.toString(), input.allowDirect)
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostAdmissionSourcePolicyTruthTableCases) -> Unit) {
            val s = HostAdmissionSourcePolicyTruthTableCases()
            block(s)
        }
        runIsolated { s -> s.theTableCoversEveryCombination() }
        runIsolated { s -> s.lspatchOnlyEverGrantsTheDirectSourceAndOnlyWhenAsked() }
        runIsolated { s -> s.theNoRootSwitchIsTotalForNPatchAndForAnyNoRootDocument() }
        runIsolated { s -> s.nPatchPrefersTheCurrentDirectDocumentWhenSwitchedOn() }
        runIsolated { s -> s.theStandardChannelBehavesExactlyAsBeforeExceptForTheClosedNoRootDocument() }
        runIsolated { s -> s.grantsAreAlwaysJustifiedByTheInputs() }
    }
}
