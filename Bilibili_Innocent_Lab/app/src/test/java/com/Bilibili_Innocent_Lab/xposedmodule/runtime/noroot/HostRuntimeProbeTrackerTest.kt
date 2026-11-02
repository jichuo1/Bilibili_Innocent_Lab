package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HostRuntimeProbeTracker.ReplyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRuntimeProbeTrackerTest {

    private var now = 0L
    private val tracker = HostRuntimeProbeTracker { now }

    @Test fun expiredRepliesAreRejectedBeforeTheDelayedTimerRunsWithoutChangingTimeoutEvidence() {
        for (verify in listOf(false, true)) for (installed in listOf(false, true)) for (offset in listOf(0L, 1L)) {
            var time = 0L
            val current = HostRuntimeProbeTracker { time }
            val timeout = if (verify) 15_000L else 1_500L
            assertTrue(current.begin("request", verify, timeout))
            time = timeout + offset
            assertEquals(ReplyResult.REJECTED, current.onReply("request", installed))
            assertEquals(if (verify) HostRuntimeProbeStatus.NOT_RUNNING else HostRuntimeProbeStatus.UNCONFIRMED,
                current.onTimeout("request"))
        }
    }

    @Test fun aReplyImmediatelyBeforeTheDeadlineStillConfirms() {
        tracker.begin("request", true, 15_000)
        now = 14_999
        assertEquals(ReplyResult.CONFIRMED, tracker.onReply("request", true))
        now = 15_000
        assertNull(tracker.onTimeout("request"))
    }

    @Test
    fun `a plain probe that times out stays unconfirmed`() {
        assertTrue(tracker.begin("nonce-1", verify = false, timeoutMs = 1_500L))
        assertEquals(HostRuntimeProbeStatus.PROBING, tracker.status)

        now = 1_500L
        assertEquals(HostRuntimeProbeStatus.UNCONFIRMED, tracker.onTimeout("nonce-1"))
    }

    @Test
    fun `a user verify that times out reports not running`() {
        assertTrue(tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L))

        now = 15_000L
        assertEquals(HostRuntimeProbeStatus.NOT_RUNNING, tracker.onTimeout("nonce-1"))
    }

    @Test
    fun `a reply carrying hooks installed confirms the module is live`() {
        tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L)

        assertEquals(ReplyResult.CONFIRMED, tracker.onReply("nonce-1", hooksInstalled = true))
        assertEquals(HostRuntimeProbeStatus.CONFIRMED, tracker.status)
    }

    @Test
    fun `a reply without hooks is kept pending and times out as not running`() {
        tracker.begin("nonce-1", verify = false, timeoutMs = 1_500L)

        assertEquals(ReplyResult.PENDING, tracker.onReply("nonce-1", hooksInstalled = false))
        assertEquals(HostRuntimeProbeStatus.PROBING, tracker.status)

        now = 1_500L
        assertEquals(HostRuntimeProbeStatus.NOT_RUNNING, tracker.onTimeout("nonce-1"))
    }

    @Test
    fun `a heartbeat settles the probe window`() {
        tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L)

        tracker.onHeartbeat()
        assertEquals(HostRuntimeProbeStatus.CONFIRMED, tracker.status)
        assertFalse(tracker.isPending("nonce-1"))
    }

    @Test
    fun `a reply for another window is ignored`() {
        tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L)

        assertEquals(ReplyResult.REJECTED, tracker.onReply("stale-nonce", hooksInstalled = true))
        assertEquals(HostRuntimeProbeStatus.PROBING, tracker.status)
    }

    @Test
    fun `concurrent probes merge into the in-flight window`() {
        assertTrue(tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L))
        assertFalse(tracker.begin("nonce-2", verify = false, timeoutMs = 1_500L))
    }

    @Test
    fun `a verified failure survives later non-verify probes`() {
        tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L)
        now = 15_000L
        assertEquals(HostRuntimeProbeStatus.NOT_RUNNING, tracker.onTimeout("nonce-1"))

        // 回到设置页的顺手探测既不闪回“确认中”，也不能把“未生效”降级成“未确认”。
        tracker.begin("nonce-2", verify = false, timeoutMs = 1_500L)
        assertEquals(HostRuntimeProbeStatus.NOT_RUNNING, tracker.status)
        now = 16_500L
        assertEquals(HostRuntimeProbeStatus.NOT_RUNNING, tracker.onTimeout("nonce-2"))
    }

    @Test
    fun `a later confirmation clears the verified failure`() {
        tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L)
        now = 15_000L
        tracker.onTimeout("nonce-1")

        tracker.begin("nonce-2", verify = false, timeoutMs = 1_500L)
        assertEquals(ReplyResult.CONFIRMED, tracker.onReply("nonce-2", hooksInstalled = true))
        assertEquals(HostRuntimeProbeStatus.CONFIRMED, tracker.status)
    }

    @Test
    fun `a new probe keeps the previous confirmation until it settles`() {
        tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L)
        tracker.onReply("nonce-1", hooksInstalled = true)

        // 不把已确认闪回“确认中”；只有窗口收不到新证据时才降级。
        tracker.begin("nonce-2", verify = false, timeoutMs = 1_500L)
        assertEquals(HostRuntimeProbeStatus.CONFIRMED, tracker.status)

        now = 15_000L
        assertEquals(HostRuntimeProbeStatus.UNCONFIRMED, tracker.onTimeout("nonce-2"))
    }

    @Test
    fun `a completed verify timer cannot end a later verify window`() {
        tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L)
        tracker.onReply("nonce-1", hooksInstalled = true)
        now = 14_000L
        tracker.begin("nonce-2", verify = true, timeoutMs = 15_000L)

        now = 15_000L
        assertNull(tracker.onTimeout("nonce-1"))
        assertTrue(tracker.isPending("nonce-2"))
        assertEquals(ReplyResult.CONFIRMED, tracker.onReply("nonce-2", hooksInstalled = true))
    }

    @Test
    fun `a delayed timer cannot settle a replacement window even after its deadline`() {
        tracker.begin("nonce-1", verify = false, timeoutMs = 1_500L)
        now = 2_000L
        tracker.begin("nonce-2", verify = true, timeoutMs = 15_000L)

        now = 17_000L
        assertNull(tracker.onTimeout("nonce-1"))
        assertTrue(tracker.isPending("nonce-2"))
        assertEquals(HostRuntimeProbeStatus.NOT_RUNNING, tracker.onTimeout("nonce-2"))
        assertNull(tracker.onTimeout("nonce-2"))
    }

    @Test
    fun `the current timer cannot settle before its deadline`() {
        tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L)

        now = 14_999L
        assertNull(tracker.onTimeout("nonce-1"))
        assertTrue(tracker.isPending("nonce-1"))
        now = 15_000L
        assertEquals(HostRuntimeProbeStatus.NOT_RUNNING, tracker.onTimeout("nonce-1"))
    }

    @Test
    fun `a reply without hooks after a prior confirmation waits for timeout`() {
        tracker.onHeartbeat()
        tracker.begin("nonce-1", verify = false, timeoutMs = 1_500L)

        assertEquals(ReplyResult.PENDING, tracker.onReply("nonce-1", hooksInstalled = false))
        assertEquals(HostRuntimeProbeStatus.CONFIRMED, tracker.status)
        assertTrue(tracker.isPending("nonce-1"))
        now = 1_500L
        assertEquals(HostRuntimeProbeStatus.NOT_RUNNING, tracker.onTimeout("nonce-1"))
    }

    @Test
    fun `a pending reply after prior confirmation can still confirm hook installation`() {
        tracker.onHeartbeat()
        tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L)

        assertEquals(ReplyResult.PENDING, tracker.onReply("nonce-1", hooksInstalled = false))
        now = 2_000L
        assertEquals(ReplyResult.CONFIRMED, tracker.onReply("nonce-1", hooksInstalled = true))
        assertFalse(tracker.isPending("nonce-1"))
        now = 15_000L
        assertNull(tracker.onTimeout("nonce-1"))
        assertEquals(HostRuntimeProbeStatus.CONFIRMED, tracker.status)
    }
}
