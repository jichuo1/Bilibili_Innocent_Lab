package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import org.junit.Assert.*
import org.junit.Test

class SponsorPlaybackControllerTest {
    private class Harness(auto: Boolean = false) : SponsorPlayerPort {
        val id = SponsorVideoId("BV14741127BN", 1)
        var identity = id; var foreground = true; var playing = true; var position = 12_000L
        var progressAvailable = true
        var time = 0L; var applied = 0; var cancelled = 0; var queries = 0
        val diagnostics = mutableListOf<String>()
        val detail = mutableMapOf<String, String>()
        var accept = true; var refusal = ""
        var hint = SponsorPlaybackController.Hint.NONE
        lateinit var result: (SponsorFetchResult) -> Unit
        data class Seek(val target: Int, val valid: () -> Boolean, val executing: () -> Unit)
        val seeks = mutableListOf<Seek>()
        val queue = ArrayDeque<() -> Unit>()
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        val controller = SponsorPlaybackController(id, auto, this,
            { _, callback -> queries++; result = callback; SponsorSegmentRepository.Ticket { cancelled++ } },
            { queue.add(it) }, { delay, callback ->
                var active = true
                timers += delay to { if (active) callback() }
                SponsorSegmentRepository.Ticket { active = false }
            }, { time }, { hint = it }, { applied++ }, { event, text -> diagnostics += event; detail[event] = text })
        override fun valid(video: SponsorVideoId) = foreground && identity == video
        var throwing = false
        override fun position() = if (throwing) error("released") else if (progressAvailable) position else -1L
        override fun duration() = if (progressAvailable) 100_000L else 0L
        override fun playing() = if (throwing) error("released") else playing
        override fun seekRefusal() = refusal
        override fun seek(targetMs: Int, valid: () -> Boolean, executing: () -> Unit): Boolean {
            seeks += Seek(targetMs, valid, executing); return accept
        }
        fun deliver() { result(SponsorFetchResult.Available(listOf(SponsorSegment("a", 10_000, 20_000, 100_000)))); drain() }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }
        fun execute(): Boolean {
            val seek = seeks.last()
            if (!seek.valid()) return false
            seek.executing(); controller.seekStarted(seek.target.toLong())
            position = seek.target.toLong(); controller.seekCompleted()
            return true
        }
    }

    @Test fun manualSkipShowsUndoOnlyAfterNativeConfirmationAndUndoDoesNotLoop() {
        val h = Harness(); h.controller.start(); h.deliver()
        assertEquals(SponsorPlaybackController.Hint.SKIP, h.hint)
        h.controller.skip(); assertEquals(0, h.applied)
        assertEquals(SponsorPlaybackController.Hint.NONE, h.hint)
        assertTrue(h.execute()); assertEquals(1, h.applied)
        assertEquals(SponsorPlaybackController.Hint.UNDO, h.hint)
        h.controller.seekCompleted()
        assertEquals(SponsorPlaybackController.Hint.UNDO, h.hint)
        assertEquals(1, h.applied)
        h.controller.undo(); assertTrue(h.execute()); assertEquals(12_000L, h.position)
        assertEquals(SponsorPlaybackController.Hint.SKIP, h.hint)
    }

    @Test fun switchingPartOrLeavingForegroundRejectsAlreadyQueuedSeekAndLateFetch() {
        for (switch in listOf(true, false)) {
            val h = Harness(true); h.controller.start(); h.deliver()
            if (switch) h.identity = h.id.copy(cid = 2) else h.foreground = false
            assertFalse(h.execute())
            h.controller.stop(); h.deliver()
            assertEquals(1, h.cancelled)
            assertEquals(1, h.seeks.size)
            assertEquals(0, h.applied)
            assertEquals(SponsorPlaybackController.Hint.NONE, h.hint)
        }
    }

    @Test fun confirmedUndoHintExpiresAfterSixSecondsAndLateActionCannotSeek() {
        val h = Harness(); h.controller.start(); h.deliver(); h.controller.skip()
        assertEquals(SponsorPlaybackController.Hint.NONE, h.hint)
        assertTrue(h.execute())
        h.time = 6_000; h.controller.refresh()
        assertEquals(SponsorPlaybackController.Hint.UNDO, h.hint)
        h.time = 6_001; h.timers.last().second()
        assertEquals(SponsorPlaybackController.Hint.NONE, h.hint)
        h.controller.undo()
        assertEquals(1, h.seeks.size); assertEquals(1, h.applied)
    }

    @Test fun manualScrubbingBeforeFetchReturnsCannotTriggerAnAutomaticSkip() {
        val h = Harness(true); h.controller.start()
        h.controller.externalSeekStarted(); h.position = 15_000; h.controller.seekCompleted()
        h.deliver()
        assertTrue(h.seeks.isEmpty())
        assertEquals(SponsorPlaybackController.Hint.SKIP, h.hint)
    }

    @Test fun pauseRejectsAutomaticQueuedWorkButManualSkipWhilePausedIsAllowed() {
        val h = Harness(true); h.controller.start(); h.deliver(); h.playing = false
        h.controller.stateChanged(); assertFalse(h.execute())
        h.controller.skip(); h.controller.stateChanged(); assertTrue(h.execute())
        assertEquals(1, h.applied)
    }

    @Test fun timeoutRejectsExecutionWithoutFalselyReportingApplied() {
        val h = Harness(true); h.controller.start(); h.deliver()
        h.time = 4_000; h.timers.single().second()
        assertFalse(h.execute()); assertEquals(0, h.applied)
        assertEquals(SponsorPlaybackController.Hint.SKIP, h.hint)
    }

    @Test fun userSeekCancelsAnAlreadyQueuedSkip() {
        val h = Harness(true); h.controller.start(); h.deliver()
        h.controller.externalSeekStarted()
        assertFalse(h.execute())
        h.position = 16_000; h.controller.seekCompleted(); h.controller.refresh()
        assertEquals(1, h.seeks.size)
        assertEquals(SponsorPlaybackController.Hint.SKIP, h.hint)
    }

    @Test fun missingSeekCompleteUsesAnIndependentDeadlineToRestoreManualControls() {
        val h = Harness(true); h.controller.start(); h.deliver()
        h.controller.externalSeekStarted()
        h.time = 4_000; h.timers.last().second()
        assertEquals(1, h.seeks.size)
        h.playing = false; h.controller.skip()
        assertEquals(2, h.seeks.size)
        assertTrue(h.execute())
    }

    @Test fun userGestureInvalidatesTheGateBeforeTheMainQueueProcessesIt() {
        val h = Harness(true); h.controller.start(); h.deliver()
        h.controller.invalidateExternalSeek()
        assertFalse(h.execute())
        h.controller.externalSeekStarted(); h.position = 16_000; h.controller.seekCompleted()
        assertEquals(0, h.applied)
    }

    @Test fun aDifferentCompletionPositionWithoutAStartEventStillClearsTheOldUndo() {
        val h = Harness(); h.controller.start(); h.deliver(); h.controller.skip(); h.execute()
        h.position = 50_000; h.controller.seekCompleted()
        assertEquals(SponsorPlaybackController.Hint.NONE, h.hint)
        h.controller.undo()
        assertEquals(1, h.seeks.size)
    }

    @Test fun cachedSegmentsCannotUseResidualProgressBeforeTheFirstNativeNotification() {
        val h = Harness(true); h.progressAvailable = false
        h.controller.start(); h.deliver()
        assertTrue(h.seeks.isEmpty())
        assertEquals(SponsorPlaybackController.Hint.NONE, h.hint)
        h.progressAvailable = true; h.controller.refresh()
        assertEquals(1, h.seeks.size)
    }

    @Test fun transientQueryFailureRetriesAndCanRecoverWithinTheSamePlayback() {
        val h = Harness(true); h.controller.start()
        h.result(SponsorFetchResult.Unavailable("network-error", 2_000)); h.drain()
        assertEquals(2_000L, h.timers.single().first)
        assertTrue(h.seeks.isEmpty())
        h.timers.single().second(); assertEquals(2, h.queries)
        h.deliver(); assertEquals(1, h.seeks.size)
        assertTrue(h.diagnostics.contains("query.failed.network-error"))
        assertTrue(h.execute()); assertTrue(h.diagnostics.contains("seek.confirmed"))
    }

    @Test fun persistentFailureStopsAfterThreeAttemptsAndDuplicateResultsDoNotScheduleMoreWork() {
        val h = Harness(); h.controller.start()
        repeat(3) { attempt ->
            h.result(SponsorFetchResult.Unavailable("timeout", 2_000)); h.drain()
            h.result(SponsorFetchResult.Unavailable("timeout", 2_000)); h.drain()
            if (attempt < 2) h.timers.last().second()
        }
        assertEquals(3, h.queries); assertEquals(2, h.timers.size)
        assertTrue(h.seeks.isEmpty())
    }

    @Test fun stoppingOrChangingTheVideoPreventsPendingRetries() {
        for (stop in listOf(true, false)) {
            val h = Harness(); h.controller.start()
            h.result(SponsorFetchResult.Unavailable("network-error", 2_000)); h.drain()
            if (stop) h.controller.stop() else h.identity = h.id.copy(cid = 2)
            h.timers.single().second()
            assertEquals(1, h.queries); assertTrue(h.seeks.isEmpty())
        }
    }

    @Test fun rateLimitWaitsForBackoffButPermanentErrorsAndEmptyResultsDoNotRetry() {
        val limited = Harness(); limited.controller.start()
        limited.result(SponsorFetchResult.Unavailable("rate-limited", 60_000)); limited.drain()
        assertEquals(60_000L, limited.timers.single().first)
        for (result in listOf(SponsorFetchResult.Unavailable("invalid-response"),
            SponsorFetchResult.Unavailable("http-403"), SponsorFetchResult.Available(emptyList()))) {
            val h = Harness(); h.controller.start(); h.result(result); h.drain()
            assertTrue(h.timers.isEmpty()); assertEquals(1, h.queries)
        }
    }

    @Test fun retryDoesNotAcceptALateResultFromThePreviousAttempt() {
        val h = Harness(true); h.controller.start(); val old = h.result
        old(SponsorFetchResult.Unavailable("timeout", 2_000)); h.drain()
        h.timers.single().second()
        old(SponsorFetchResult.Available(listOf(SponsorSegment("old", 10_000, 20_000, 100_000)))); h.drain()
        assertTrue(h.seeks.isEmpty()); h.deliver(); assertEquals(1, h.seeks.size)
    }

    // ---- 诊断：手动提示出现时必须能从日志里读出“为什么没有自动跳过” ----

    @Test fun theManualHintRecordsThatTheRangeWasBlockedByAnExternalSeek() {
        val h = Harness(true); h.controller.start()
        h.controller.externalSeekStarted("call", 15_000); h.position = 15_000; h.controller.seekCompleted()
        h.deliver()
        assertEquals(SponsorPlaybackController.Hint.SKIP, h.hint)
        assertTrue(h.detail.getValue("hint.skip").startsWith("why=blocked.external-seek "))
        assertTrue(h.diagnostics.contains("auto.denied.blocked.external-seek"))
        assertTrue(h.detail.getValue("seek.external.start.call.1").startsWith("toMs=15000 "))
        assertTrue(h.detail.getValue("seek.external.done.1").startsWith("inRange="))
        assertEquals("mode=auto", h.detail["controller.start"])
    }

    @Test fun pausedInsideARangeIsReportedAsPausedNotAsBlocked() {
        val h = Harness(true); h.playing = false; h.controller.start(); h.deliver()
        assertEquals(SponsorPlaybackController.Hint.SKIP, h.hint)
        assertTrue(h.diagnostics.contains("auto.denied.paused"))
        assertTrue(h.detail.getValue("hint.skip").startsWith("why=paused "))
        assertTrue(h.seeks.isEmpty())
    }

    @Test fun manualModeIsReportedAsManualOnly() {
        val h = Harness(false); h.controller.start(); h.deliver()
        assertEquals("mode=manual", h.detail["controller.start"])
        assertTrue(h.diagnostics.contains("auto.denied.manual-only"))
        assertTrue(h.detail.getValue("hint.skip").startsWith("why=manual-only "))
    }

    @Test fun anAutomaticSkipThatWorksLeavesNoDenialAndNamesTheConfirmedKinds() {
        val h = Harness(true); h.controller.start(); h.deliver()
        assertTrue(h.diagnostics.contains("request.auto"))
        assertTrue(h.execute())
        assertEquals("fromMs=12000 toMs=20000", h.detail["seek.confirmed"])
        assertTrue(h.diagnostics.none { it.startsWith("auto.denied.") })
        assertFalse(h.diagnostics.contains("hint.skip")); assertTrue(h.diagnostics.contains("hint.undo"))
        h.controller.undo(); assertTrue(h.execute())
        assertEquals("fromMs=20000 toMs=12000", h.detail["seek.undone"])
        // 撤销之后区间已封锁：手动提示出现，原因是“已经跳过过一次”。
        assertTrue(h.detail.getValue("hint.skip").startsWith("why=blocked.confirmed "))
    }

    @Test fun aManualSkipUsesItsOwnConfirmedEvent() {
        val h = Harness(); h.controller.start(); h.deliver(); h.controller.skip()
        assertTrue(h.diagnostics.contains("request.manual"))
        assertTrue(h.execute())
        assertTrue(h.diagnostics.contains("seek.confirmed.manual")); assertFalse(h.diagnostics.contains("seek.confirmed"))
    }

    @Test fun aRefusedRequestNamesTheGuardsReason() {
        val h = Harness(true); h.accept = false; h.refusal = "not-captured"
        h.controller.start(); h.deliver()
        assertEquals("kind=auto why=not-captured", h.detail["request.rejected"])
        assertTrue(h.detail.getValue("hint.skip").startsWith("why=blocked.failed "))
        h.refusal = ""; h.controller.skip()
        assertEquals("kind=manual why=unknown", h.detail["request.rejected"])
    }

    @Test fun aTimeoutSaysWhetherTheHostEverReleasedTheSeek() {
        val h = Harness(true); h.controller.start(); h.deliver()
        h.time = 4_000; h.timers.single().second()
        assertEquals("kind=auto executed=false", h.detail["request.timeout"])
        val ran = Harness(true); ran.controller.start(); ran.deliver()
        ran.seeks.last().executing(); ran.time = 4_000; ran.timers.single().second()
        assertEquals("kind=auto executed=true", ran.detail["request.timeout"])
    }

    @Test fun anOwnRequestThatCannotBeConfirmedIsReportedSeparatelyFromAnExternalSeek() {
        val mismatch = Harness(true); mismatch.controller.start(); mismatch.deliver()
        mismatch.seeks.last().executing(); mismatch.position = 50_000; mismatch.controller.seekCompleted()
        assertEquals("deltaMs=30000", mismatch.detail["seek.unconfirmed.mismatch"])
        val skipped = Harness(true); skipped.controller.start(); skipped.deliver()
        skipped.position = 20_000; skipped.controller.seekCompleted()
        assertTrue(skipped.diagnostics.contains("seek.unconfirmed.not-executed"))
        val plain = Harness(true); plain.controller.start(); plain.controller.seekCompleted()
        assertTrue(plain.diagnostics.none { it.startsWith("seek.unconfirmed.") })
        assertTrue(plain.diagnostics.contains("seek.external.done.1"))
    }

    @Test fun droppingAnAutomaticRequestBecausePlaybackPausedIsRecorded() {
        val h = Harness(true); h.controller.start(); h.deliver()
        h.playing = false; h.controller.stateChanged()
        assertEquals("executed=false", h.detail["request.dropped.paused"])
    }

    @Test fun externalSeekEventsAreCountedPerSourceAndCapped() {
        val h = Harness(true); h.controller.start(); h.deliver()
        repeat(6) { h.controller.externalSeekStarted("call") }
        h.controller.seekStarted(70_000)
        assertTrue(h.diagnostics.contains("seek.external.start.call.3"))
        assertFalse(h.diagnostics.contains("seek.external.start.call.4"))
        assertTrue(h.detail.getValue("seek.external.start.observer.1").startsWith("toMs=70000 "))
    }

    @Test fun anUnreadablePlayerNeverBreaksTheControlFlowThroughDiagnostics() {
        val h = Harness(true); h.controller.start(); h.deliver()
        h.throwing = true // 宿主已释放播放器：读位置和状态都会抛，诊断不能因此丢掉门禁撤销
        h.controller.externalSeekStarted("call")
        h.throwing = false
        assertEquals("snapshot=unavailable", h.detail["seek.external.start.call.1"])
        assertFalse(h.execute())
    }

    @Test fun queryAvailableNamesTheLocalCacheOriginOnlyWhenThereIsOne() {
        val network = Harness(true); network.controller.start(); network.deliver()
        assertEquals("accepted=1", network.detail["query.available"])
        for (origin in listOf("disk", "disk-stale")) {
            val h = Harness(true); h.controller.start()
            h.result(SponsorFetchResult.Available(listOf(SponsorSegment("a", 10_000, 20_000, 100_000)), origin = origin)); h.drain()
            assertEquals("accepted=1 src=$origin", h.detail["query.available"])
            assertEquals(1, h.seeks.size) // 缓存里的区间照常自动跳过
        }
    }

    @Test fun stoppingRecordsTheSessionEndOnce() {
        val h = Harness(true); h.controller.start(); h.controller.stop(); h.controller.stop()
        assertEquals(1, h.diagnostics.count { it == "session.ended" })
    }
}
