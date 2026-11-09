package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.junit.Assert.*
import org.junit.Test

class HostRuntimeProbeSessionTest {
    private var now = 0L
    private val deliveries = mutableListOf<() -> Unit>()
    private val session = HostRuntimeProbeSession({ now }) { deliveries += it }

    @Test fun anExpiredPositiveReplySettlesAllObserversOnceWithoutWaitingForTheTimer() {
        val results = mutableListOf<HostRuntimeProbeStatus>()
        session.request("resume", false, 1_500, results::add)
        now = 100
        session.request("verify", true, 15_000, results::add)
        now = 15_101
        assertEquals(HostRuntimeProbeTracker.ReplyResult.REJECTED, session.onReply("resume", true))
        assertEquals(HostRuntimeProbeStatus.NOT_RUNNING, session.status)
        assertFalse(session.hasPendingRequest)
        assertFalse(session.onTimeout("resume"))
        assertEquals(HostRuntimeProbeTracker.ReplyResult.REJECTED, session.onReply("resume", true))
        drain()
        assertEquals(listOf(HostRuntimeProbeStatus.NOT_RUNNING, HostRuntimeProbeStatus.NOT_RUNNING), results)
    }

    @Test fun anExpiredNegativeReplyCannotChangeAnOrdinaryTimeoutIntoAVerifiedFailure() {
        val results = mutableListOf<HostRuntimeProbeStatus>()
        session.request("resume", false, 1_500, results::add)
        now = 1_500
        assertEquals(HostRuntimeProbeTracker.ReplyResult.REJECTED, session.onReply("resume", false))
        assertEquals(HostRuntimeProbeStatus.UNCONFIRMED, session.status)
        assertFalse(session.hasPendingRequest)
        assertFalse(session.onTimeout("resume"))
        drain()
        assertEquals(listOf(HostRuntimeProbeStatus.UNCONFIRMED), results)
    }

    @Test fun explicitVerificationUpgradesAResumeProbeAndKeepsBothCallbacks() {
        val results = mutableListOf<Pair<String, HostRuntimeProbeStatus>>()
        val resume = session.request("resume", false, 1_500) { results += "resume" to it }
        now = 100
        val verify = session.request("verify", true, 15_000) { results += "verify" to it }
        assertEquals(resume.nonce, verify.nonce)
        assertFalse(verify.newWindow)
        assertTrue(verify.scheduleTimeout)
        now = 1_500
        assertFalse(session.onTimeout(resume.nonce))
        assertTrue(deliveries.isEmpty())
        now = 15_100
        assertTrue(session.onTimeout(verify.nonce))
        assertTrue(results.isEmpty()) // 与生产一致，回调交给主线程队列执行。
        drain()
        assertEquals(listOf("resume" to HostRuntimeProbeStatus.NOT_RUNNING,
            "verify" to HostRuntimeProbeStatus.NOT_RUNNING), results)
    }

    @Test fun aReplyAfterTheOriginalShortDeadlineConfirmsAllRequestsOnce() {
        val results = mutableListOf<HostRuntimeProbeStatus>()
        session.request("resume", false, 1_500, results::add)
        now = 100
        session.request("verify", true, 15_000, results::add)
        now = 2_000
        assertEquals(HostRuntimeProbeTracker.ReplyResult.CONFIRMED, session.onReply("resume", true))
        assertFalse(session.isPending("resume"))
        assertEquals(HostRuntimeProbeTracker.ReplyResult.REJECTED, session.onReply("resume", true))
        now = 15_100
        assertFalse(session.onTimeout("resume"))
        drain()
        assertEquals(listOf(HostRuntimeProbeStatus.CONFIRMED, HostRuntimeProbeStatus.CONFIRMED), results)
    }

    @Test fun aResumeProbeCannotShortenAnActiveVerificationOrReplaceItsCallback() {
        val results = mutableListOf<HostRuntimeProbeStatus>()
        session.request("verify", true, 15_000, results::add)
        now = 100
        val joined = session.request("resume", false, 1_500, results::add)
        assertEquals("verify", joined.nonce)
        assertFalse(joined.newWindow)
        assertFalse(joined.scheduleTimeout)
        now = 1_600
        assertFalse(session.onTimeout(joined.nonce))
        now = 15_000
        assertTrue(session.onTimeout(joined.nonce))
        drain()
        assertEquals(listOf(HostRuntimeProbeStatus.NOT_RUNNING, HostRuntimeProbeStatus.NOT_RUNNING), results)
    }

    @Test fun repeatedOrdinaryProbesDoNotKeepAnUnresponsiveHostPendingForever() {
        val results = mutableListOf<HostRuntimeProbeStatus>()
        session.request("first", false, 1_500, results::add)
        now = 1_000
        val joined = session.request("second", false, 1_500, results::add)
        assertFalse(joined.scheduleTimeout)
        now = 1_500
        assertTrue(session.onTimeout("first"))
        drain()
        assertEquals(listOf(HostRuntimeProbeStatus.UNCONFIRMED, HostRuntimeProbeStatus.UNCONFIRMED), results)
    }

    @Test fun anExpiredWindowSettlesBeforeItsReplacementAndRejectsLateReplies() {
        val results = mutableListOf<Pair<String, HostRuntimeProbeStatus>>()
        session.request("expired", false, 1_500) { results += "expired" to it }
        now = 2_000
        val replacement = session.request("current", true, 15_000) { results += "current" to it }
        assertTrue(replacement.newWindow)
        assertTrue(replacement.scheduleTimeout)
        assertEquals(HostRuntimeProbeTracker.ReplyResult.REJECTED, session.onReply("expired", true))
        assertFalse(session.onTimeout("expired"))
        assertTrue(session.isPending("current"))
        assertTrue(session.hasPendingRequest)
        session.onHeartbeat()
        drain()
        assertEquals(listOf("expired" to HostRuntimeProbeStatus.UNCONFIRMED,
            "current" to HostRuntimeProbeStatus.CONFIRMED), results)
    }

    @Test fun mergingVerificationPreservesTheAliveWithoutHooksEvidence() {
        val results = mutableListOf<HostRuntimeProbeStatus>()
        session.request("resume", false, 1_500, results::add)
        assertEquals(HostRuntimeProbeTracker.ReplyResult.PENDING, session.onReply("resume", false))
        now = 100
        session.request("verify", true, 15_000, results::add)
        now = 15_100
        assertTrue(session.onTimeout("resume"))
        drain()
        assertEquals(HostRuntimeProbeStatus.NOT_RUNNING, session.status)
        assertEquals(2, results.size)
    }

    private fun drain() {
        val pending = deliveries.toList()
        deliveries.clear()
        pending.forEach { it() }
    }
}
