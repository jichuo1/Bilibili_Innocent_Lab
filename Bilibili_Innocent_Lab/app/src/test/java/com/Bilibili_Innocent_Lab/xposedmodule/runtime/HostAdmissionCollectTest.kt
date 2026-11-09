package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import java.util.concurrent.LinkedBlockingQueue
import org.junit.Assert.*
import org.junit.Test

class HostAdmissionCollectTest {
    @Test fun explicitClosePreemptsAGrantWithoutTurningOrdinarySyncFailuresIntoPermanentDenials() {
        val closed = HostAdmissionClient.Result(reason = HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED, disabledRevision = 42L)
        val results = listOf(
            HostAdmissionCollect().accept(closed, true).accept(granted(), true),
            HostAdmissionCollect().accept(granted(), true).accept(closed, false)
        )
        for (result in results) {
            assertTrue(result.sawDenied)
            assertTrue(result.fallbackBlocked)
            assertNull(result.grant)
            assertEquals(42L, result.failure.disabledRevision)
        }
    }
    @Test fun aGenericDenialCannotEraseTheCurrentCloseRevision() {
        val closed = HostAdmissionClient.Result(reason = HostAdmissionRouteClassifier.REASON_DENIED, disabledRevision = 42L)
        val first = HostAdmissionCollect().accept(closed, acceptGrant = true).accept(denied(), acceptGrant = true)
        val second = HostAdmissionCollect().accept(denied(), acceptGrant = true).accept(closed, acceptGrant = true)
        assertEquals(42L, first.failure.disabledRevision)
        assertEquals(42L, second.failure.disabledRevision)
        assertNull(first.grant)
        assertNull(second.grant)
    }
    private fun unavailable() = HostAdmissionClient.Result()
    private fun denied() = HostAdmissionClient.Result(reason = HostAdmissionRouteClassifier.REASON_DENIED)
    private fun timeout() = HostAdmissionClient.Result(reason = "admission_timeout")
    private fun granted(): HostAdmissionClient.Result {
        val snapshot = RemoteHookConfigSnapshot(
            generation = 1L,
            moduleVersionCode = 1L,
            deliveryEnabled = true,
            noRootRevision = 0L,
            decision = UserTermsDecision.ACCEPTED,
            values = emptyMap(),
        )
        return HostAdmissionClient.Result(
            HostAdmissionClient.Grant(snapshot, "remote_config_transport_fallback"),
            reason = "",
        )
    }

    @Test fun firstGrantWinsInsideTheWindow() {
        val collect = HostAdmissionCollect()
            .accept(unavailable(), acceptGrant = true)
            .accept(granted(), acceptGrant = true)
        assertNotNull(collect.grant)
        assertFalse(collect.sawDenied)
    }

    @Test fun authorityDenialDiscardsALaterGrant() {
        val collect = HostAdmissionCollect()
            .accept(denied(), acceptGrant = true)
            .accept(granted(), acceptGrant = true)
        assertTrue(collect.sawDenied)
        assertNull(collect.grant)
        assertEquals(HostAdmissionRouteClassifier.REASON_DENIED, collect.failure.reason)
    }

    @Test fun timeoutDrainLatchesADenyThatAlreadyArrived() {
        val queue = LinkedBlockingQueue<HostAdmissionClient.Result>()
        queue.offer(denied())
        queue.offer(granted())
        val collect = HostAdmissionCollect()
            .accept(timeout(), acceptGrant = true)
            .drain(queue, acceptGrant = false)
        assertTrue(collect.sawDenied)
        assertNull(collect.grant)
        assertTrue(queue.isEmpty())
    }

    @Test fun anAlreadyQueuedDenialPreemptsTheFastGrantPath() {
        val queue = LinkedBlockingQueue<HostAdmissionClient.Result>().apply { offer(denied()) }
        val result = HostAdmissionCollect().accept(granted(), true).drain(queue, acceptGrant = false)
        assertTrue(result.sawDenied)
        assertTrue(result.fallbackBlocked)
        assertNull(result.grant)
    }

    @Test fun timeoutDrainIgnoresLateGrantsWhenNobodyDenied() {
        val queue = LinkedBlockingQueue<HostAdmissionClient.Result>()
        queue.offer(granted())
        val collect = HostAdmissionCollect()
            .accept(unavailable(), acceptGrant = true)
            .drain(queue, acceptGrant = false)
        assertFalse(collect.sawDenied)
        assertNull(collect.grant)
        assertNull(collect.failure.grant)
        assertEquals(HostAdmissionRouteClassifier.REASON_UNAVAILABLE, collect.failure.reason)
    }

    @Test fun syncFailureSurvivesTransportFailureWithoutBecomingTermsDenial() {
        val sync = HostAdmissionClient.Result(reason = HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED)
        val collect = HostAdmissionCollect().accept(sync, true).accept(unavailable(), true).accept(timeout(), true)
        assertFalse(collect.sawDenied)
        assertTrue(collect.fallbackBlocked)
        assertEquals(sync.reason, collect.failure.reason)
    }

    @Test fun freshGrantCanRecoverAnotherRoutesSyncFailureButNotRealDenial() {
        val sync = HostAdmissionClient.Result(reason = HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED)
        val recovered = HostAdmissionCollect().accept(sync, true).accept(granted(), true)
        assertNotNull(recovered.grant)
        assertFalse(recovered.sawDenied)
        val refused = recovered.accept(denied(), true).accept(granted(), true)
        assertTrue(refused.sawDenied)
        assertNull(refused.grant)
    }

    @Test fun lateSyncResponseBlocksFallbackAndDiagnosticCountIsBounded() {
        val sync = HostAdmissionClient.Result(reason = HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED,
            diagnostics = listOf(HostAdmissionDiagnostic("provider", "prepare", "manager_sync_required", true)))
        val queue = LinkedBlockingQueue<HostAdmissionClient.Result>().apply { offer(sync); offer(granted()) }
        var collect = HostAdmissionCollect().drain(queue, acceptGrant = false)
        assertTrue(collect.fallbackBlocked)
        assertNull(collect.grant)
        repeat(8) { collect = collect.accept(sync, false) }
        assertEquals(4, collect.diagnostics.size)
    }

    @Test fun transportOnlyRetryCannotEraseAnEarlierAuthorityBlock() {
        val state = HostAdmissionFallbackState()
        for (reason in listOf(HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED, HostAdmissionRouteClassifier.REASON_DENIED)) {
            state.observe(HostAdmissionClient.Result(reason = reason))
            state.observe(unavailable())
            state.observe(granted())
            val next = state.newAttempt().accept(timeout(), true)
            assertTrue(next.fallbackBlocked)
            assertEquals(reason, next.failure.reason)
        }
        state.observe(HostAdmissionClient.Result(reason = HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED))
        assertEquals(HostAdmissionRouteClassifier.REASON_DENIED, state.newAttempt().failure.reason)
        val identity = com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationIdentity("publication-instance", 1, 1, 1, "a".repeat(64))
        val fresh = granted().let { it.copy(grant = it.grant!!.copy(source = HostAdmissionContract.NORMAL, identity = identity)) }
        state.observe(fresh)
        assertFalse(state.newAttempt().fallbackBlocked)
    }
}
