package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SponsorBlockClientTest {
    private val id = SponsorVideoId("BV14741127BN", 1)
    private class Connection(private val status: Int, private val body: InputStream) : HttpURLConnection(URL("https://www.bsbsb.top")) {
        var released = false
        override fun getResponseCode() = status
        override fun getInputStream() = body
        override fun disconnect() { released = true }
        override fun usingProxy() = false
        override fun connect() {}
    }
    private fun fetch(status: Int, body: String = "[]"): SponsorFetchResult {
        val connection = Connection(status, ByteArrayInputStream(body.toByteArray()))
        val result = SponsorBlockClient("test") { connection }.fetch(id, SponsorRequestCancellation())
        assertTrue(connection.released); assertFalse(connection.instanceFollowRedirects)
        return result
    }

    fun onlyTransientHttpFailuresHaveRetryBackoff() {
        assertEquals(SponsorFetchResult.Unavailable("rate-limited", 60_000), fetch(429))
        assertEquals(SponsorFetchResult.Unavailable("http-503", 5_000), fetch(503))
        assertEquals(SponsorFetchResult.Unavailable("http-403"), fetch(403))
        assertEquals(SponsorFetchResult.Unavailable("http-302"), fetch(302))
        assertEquals(SponsorFetchResult.Available(emptyList()), fetch(404))
    }

    fun networkTlsAndInvalidResponsesHaveDistinctReasons() {
        for ((error, result) in listOf(SocketTimeoutException() to SponsorFetchResult.Unavailable("timeout", 2_000),
            UnknownHostException() to SponsorFetchResult.Unavailable("dns-error", 2_000),
            SSLException("private text") to SponsorFetchResult.Unavailable("tls-error"))) {
            assertEquals(result, SponsorBlockClient("test") { throw error }.fetch(id, SponsorRequestCancellation()))
        }
        assertEquals(SponsorFetchResult.Unavailable("invalid-response"), fetch(200, "not-json"))
        assertEquals(SponsorFetchResult.Unavailable("invalid-response"),
            fetch(200, "x".repeat(SponsorBlockClient.MAX_RESPONSE_BYTES + 1)))
    }

    fun cancellationBeforeOpeningDoesNotStartANetworkRequestOrRetry() {
        var opened = false
        val cancellation = SponsorRequestCancellation().apply { cancel() }
        val result = SponsorBlockClient("test") { opened = true; error("must not open") }.fetch(id, cancellation)
        assertFalse(opened); assertEquals(SponsorFetchResult.Unavailable("cancelled"), result)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (SponsorBlockClientTest) -> Unit) {
            val s = SponsorBlockClientTest()
            block(s)
        }
        runIsolated { s -> s.onlyTransientHttpFailuresHaveRetryBackoff() }
        runIsolated { s -> s.networkTlsAndInvalidResponsesHaveDistinctReasons() }
        runIsolated { s -> s.cancellationBeforeOpeningDoesNotStartANetworkRequestOrRetry() }
    
        SponsorCachingSourceCases().verifyAllCases()
        SponsorPlaybackControllerCases().verifyAllCases()
        SponsorPlaybackPolicyCases().verifyAllCases()
        SponsorPlayerBindingsCases().verifyAllCases()
        SponsorRuntimeDiagnosticsCases().verifyAllCases()
    }
}

private class SponsorCachingSourceCases {
    val folder = TemporaryFolder()
    private var time = 10_000_000_000L
    private val video = SponsorVideoId("BV1pVWu6ZEzn", 43486676261)
    private val ad = SponsorSegment("a", 74_636, 103_813, 566_000)

    private class Network(var result: SponsorFetchResult) : SponsorSegmentSource {
        var calls = 0
        override fun fetch(video: SponsorVideoId, cancellation: SponsorRequestCancellation): SponsorFetchResult {
            calls++; return result
        }
    }

    private fun store() = SponsorSegmentStore(folder.root) { time }
    private fun source(network: Network) = SponsorCachingSource(network, store()) { time }
    private fun fetch(source: SponsorSegmentSource, id: SponsorVideoId = video) = source.fetch(id, SponsorRequestCancellation())
    private fun fileOf(id: SponsorVideoId) = File(File(folder.root, SponsorSegmentStore.DIRECTORY), SponsorSegmentStore.keyOf(id) + ".json")

    fun aSuccessfulFetchIsStoredAndAFreshEntryIsServedWithoutTheNetwork() {
        val network = Network(SponsorFetchResult.Available(listOf(ad)))
        val source = source(network)
        assertEquals("", (fetch(source) as SponsorFetchResult.Available).origin)
        time += SponsorCachingSource.FRESH_MS - 1
        val hit = fetch(source) as SponsorFetchResult.Available
        assertEquals(listOf(ad), hit.segments); assertEquals("disk", hit.origin)
        assertEquals(1, network.calls)
    }

    fun aStaleEntryIsRefreshedFromTheNetworkAndOverwritten() {
        val network = Network(SponsorFetchResult.Available(listOf(ad)))
        val source = source(network); fetch(source)
        time += SponsorCachingSource.FRESH_MS
        val changed = ad.copy(endMs = 110_000)
        network.result = SponsorFetchResult.Available(listOf(changed))
        assertEquals(listOf(changed), (fetch(source) as SponsorFetchResult.Available).segments)
        assertEquals(2, network.calls)
        // 刚写入的是新缓存：不再联网，哪怕网络此刻是坏的。
        network.result = SponsorFetchResult.Unavailable("timeout", 2_000)
        assertEquals("disk", (fetch(source) as SponsorFetchResult.Available).origin)
        assertEquals(2, network.calls)
    }

    fun anyNetworkFailureFallsBackToAnOlderEntry() {
        val network = Network(SponsorFetchResult.Available(listOf(ad)))
        val source = source(network); fetch(source)
        for (reason in listOf("timeout", "dns-error", "tls-error", "network-error", "http-503", "rate-limited", "busy")) {
            network.result = SponsorFetchResult.Unavailable(reason, 2_000)
            time += SponsorCachingSource.FRESH_MS
            val fallback = fetch(source) as SponsorFetchResult.Available
            assertEquals(reason, "disk-stale", fallback.origin); assertEquals(listOf(ad), fallback.segments)
        }
    }

    fun anEntryPastTheStaleLimitIsDeletedNotServed() {
        val network = Network(SponsorFetchResult.Available(listOf(ad)))
        val source = source(network); fetch(source)
        network.result = SponsorFetchResult.Unavailable("network-error", 2_000)
        time += SponsorCachingSource.STALE_MAX_MS + 1
        assertTrue(fetch(source) is SponsorFetchResult.Unavailable)
        assertFalse(fileOf(video).exists())
    }

    fun anEntryFromTheFutureAfterAClockRollbackIsDeletedNotServed() {
        val network = Network(SponsorFetchResult.Available(listOf(ad)))
        val source = source(network); fetch(source)
        network.result = SponsorFetchResult.Unavailable("network-error", 2_000)
        time -= 3_600_000 // 倒退 1 小时：在容忍范围内被解析，但缓存年龄为负，不可信
        assertTrue(fetch(source) is SponsorFetchResult.Unavailable)
        assertFalse(fileOf(video).exists())
    }

    fun theNetworkSayingThereAreNoSegmentsDropsTheOldEntry() {
        val network = Network(SponsorFetchResult.Available(listOf(ad)))
        val source = source(network); fetch(source)
        time += SponsorCachingSource.FRESH_MS
        network.result = SponsorFetchResult.Available(emptyList())
        assertTrue((fetch(source) as SponsorFetchResult.Available).segments.isEmpty())
        assertFalse(fileOf(video).exists())
        network.result = SponsorFetchResult.Unavailable("timeout", 2_000)
        assertTrue("the removed entry must not come back", fetch(source) is SponsorFetchResult.Unavailable)
    }

    fun aVideoWithoutSegmentsLeavesNothingOnDisk() {
        val other = video.copy(cid = 9)
        fetch(source(Network(SponsorFetchResult.Available(emptyList()))), other)
        assertFalse(fileOf(other).exists())
    }

    fun aCancelledRequestNeverFallsBackToTheCache() {
        val network = Network(SponsorFetchResult.Available(listOf(ad)))
        val source = source(network); fetch(source)
        time += SponsorCachingSource.FRESH_MS
        network.result = SponsorFetchResult.Unavailable("cancelled")
        assertEquals("cancelled", (fetch(source) as SponsorFetchResult.Unavailable).reason)
    }

    fun withoutAStoreOrWithAnUnwritableOneTheNetworkResultPassesThroughUnchanged() {
        val result = SponsorFetchResult.Available(listOf(ad))
        assertSame(result, fetch(SponsorCachingSource(Network(result), null) { time }))
        val broken = SponsorCachingSource(Network(result), SponsorSegmentStore(folder.newFile("occupied")) { time }) { time }
        assertSame(result, fetch(broken))
    }

    fun theRepositoryDeliversTheDiskOriginToItsListeners() {
        fetch(source(Network(SponsorFetchResult.Available(listOf(ad)))))
        val repository = SponsorSegmentRepository(source(Network(SponsorFetchResult.Unavailable("timeout", 2_000))))
        try {
            var delivered: SponsorFetchResult? = null
            val latch = CountDownLatch(1)
            repository.lookup(video) { delivered = it; latch.countDown() }
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals("disk", (delivered as SponsorFetchResult.Available).origin)
        } finally { repository.close() }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (SponsorCachingSourceCases) -> Unit) {
            val s = SponsorCachingSourceCases()
            s.folder.create()
            try {
                block(s)
            } finally {
                s.folder.delete()
            }
        }
        runIsolated { s -> s.aSuccessfulFetchIsStoredAndAFreshEntryIsServedWithoutTheNetwork() }
        runIsolated { s -> s.aStaleEntryIsRefreshedFromTheNetworkAndOverwritten() }
        runIsolated { s -> s.anyNetworkFailureFallsBackToAnOlderEntry() }
        runIsolated { s -> s.anEntryPastTheStaleLimitIsDeletedNotServed() }
        runIsolated { s -> s.anEntryFromTheFutureAfterAClockRollbackIsDeletedNotServed() }
        runIsolated { s -> s.theNetworkSayingThereAreNoSegmentsDropsTheOldEntry() }
        runIsolated { s -> s.aVideoWithoutSegmentsLeavesNothingOnDisk() }
        runIsolated { s -> s.aCancelledRequestNeverFallsBackToTheCache() }
        runIsolated { s -> s.withoutAStoreOrWithAnUnwritableOneTheNetworkResultPassesThroughUnchanged() }
        runIsolated { s -> s.theRepositoryDeliversTheDiskOriginToItsListeners() }
    }
}

private class SponsorPlaybackControllerCases {
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

    fun manualSkipShowsUndoOnlyAfterNativeConfirmationAndUndoDoesNotLoop() {
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

    fun switchingPartOrLeavingForegroundRejectsAlreadyQueuedSeekAndLateFetch() {
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

    fun confirmedUndoHintExpiresAfterSixSecondsAndLateActionCannotSeek() {
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

    fun manualScrubbingBeforeFetchReturnsCannotTriggerAnAutomaticSkip() {
        val h = Harness(true); h.controller.start()
        h.controller.externalSeekStarted(); h.position = 15_000; h.controller.seekCompleted()
        h.deliver()
        assertTrue(h.seeks.isEmpty())
        assertEquals(SponsorPlaybackController.Hint.SKIP, h.hint)
    }

    fun pauseRejectsAutomaticQueuedWorkButManualSkipWhilePausedIsAllowed() {
        val h = Harness(true); h.controller.start(); h.deliver(); h.playing = false
        h.controller.stateChanged(); assertFalse(h.execute())
        h.controller.skip(); h.controller.stateChanged(); assertTrue(h.execute())
        assertEquals(1, h.applied)
    }

    fun timeoutRejectsExecutionWithoutFalselyReportingApplied() {
        val h = Harness(true); h.controller.start(); h.deliver()
        h.time = 4_000; h.timers.single().second()
        assertFalse(h.execute()); assertEquals(0, h.applied)
        assertEquals(SponsorPlaybackController.Hint.SKIP, h.hint)
    }

    fun userSeekCancelsAnAlreadyQueuedSkip() {
        val h = Harness(true); h.controller.start(); h.deliver()
        h.controller.externalSeekStarted()
        assertFalse(h.execute())
        h.position = 16_000; h.controller.seekCompleted(); h.controller.refresh()
        assertEquals(1, h.seeks.size)
        assertEquals(SponsorPlaybackController.Hint.SKIP, h.hint)
    }

    fun missingSeekCompleteUsesAnIndependentDeadlineToRestoreManualControls() {
        val h = Harness(true); h.controller.start(); h.deliver()
        h.controller.externalSeekStarted()
        h.time = 4_000; h.timers.last().second()
        assertEquals(1, h.seeks.size)
        h.playing = false; h.controller.skip()
        assertEquals(2, h.seeks.size)
        assertTrue(h.execute())
    }

    fun userGestureInvalidatesTheGateBeforeTheMainQueueProcessesIt() {
        val h = Harness(true); h.controller.start(); h.deliver()
        h.controller.invalidateExternalSeek()
        assertFalse(h.execute())
        h.controller.externalSeekStarted(); h.position = 16_000; h.controller.seekCompleted()
        assertEquals(0, h.applied)
    }

    fun aDifferentCompletionPositionWithoutAStartEventStillClearsTheOldUndo() {
        val h = Harness(); h.controller.start(); h.deliver(); h.controller.skip(); h.execute()
        h.position = 50_000; h.controller.seekCompleted()
        assertEquals(SponsorPlaybackController.Hint.NONE, h.hint)
        h.controller.undo()
        assertEquals(1, h.seeks.size)
    }

    fun cachedSegmentsCannotUseResidualProgressBeforeTheFirstNativeNotification() {
        val h = Harness(true); h.progressAvailable = false
        h.controller.start(); h.deliver()
        assertTrue(h.seeks.isEmpty())
        assertEquals(SponsorPlaybackController.Hint.NONE, h.hint)
        h.progressAvailable = true; h.controller.refresh()
        assertEquals(1, h.seeks.size)
    }

    fun transientQueryFailureRetriesAndCanRecoverWithinTheSamePlayback() {
        val h = Harness(true); h.controller.start()
        h.result(SponsorFetchResult.Unavailable("network-error", 2_000)); h.drain()
        assertEquals(2_000L, h.timers.single().first)
        assertTrue(h.seeks.isEmpty())
        h.timers.single().second(); assertEquals(2, h.queries)
        h.deliver(); assertEquals(1, h.seeks.size)
        assertTrue(h.diagnostics.contains("query.failed.network-error"))
        assertTrue(h.execute()); assertTrue(h.diagnostics.contains("seek.confirmed"))
    }

    fun persistentFailureStopsAfterThreeAttemptsAndDuplicateResultsDoNotScheduleMoreWork() {
        val h = Harness(); h.controller.start()
        repeat(3) { attempt ->
            h.result(SponsorFetchResult.Unavailable("timeout", 2_000)); h.drain()
            h.result(SponsorFetchResult.Unavailable("timeout", 2_000)); h.drain()
            if (attempt < 2) h.timers.last().second()
        }
        assertEquals(3, h.queries); assertEquals(2, h.timers.size)
        assertTrue(h.seeks.isEmpty())
    }

    fun stoppingOrChangingTheVideoPreventsPendingRetries() {
        for (stop in listOf(true, false)) {
            val h = Harness(); h.controller.start()
            h.result(SponsorFetchResult.Unavailable("network-error", 2_000)); h.drain()
            if (stop) h.controller.stop() else h.identity = h.id.copy(cid = 2)
            h.timers.single().second()
            assertEquals(1, h.queries); assertTrue(h.seeks.isEmpty())
        }
    }

    fun rateLimitWaitsForBackoffButPermanentErrorsAndEmptyResultsDoNotRetry() {
        val limited = Harness(); limited.controller.start()
        limited.result(SponsorFetchResult.Unavailable("rate-limited", 60_000)); limited.drain()
        assertEquals(60_000L, limited.timers.single().first)
        for (result in listOf(SponsorFetchResult.Unavailable("invalid-response"),
            SponsorFetchResult.Unavailable("http-403"), SponsorFetchResult.Available(emptyList()))) {
            val h = Harness(); h.controller.start(); h.result(result); h.drain()
            assertTrue(h.timers.isEmpty()); assertEquals(1, h.queries)
        }
    }

    fun retryDoesNotAcceptALateResultFromThePreviousAttempt() {
        val h = Harness(true); h.controller.start(); val old = h.result
        old(SponsorFetchResult.Unavailable("timeout", 2_000)); h.drain()
        h.timers.single().second()
        old(SponsorFetchResult.Available(listOf(SponsorSegment("old", 10_000, 20_000, 100_000)))); h.drain()
        assertTrue(h.seeks.isEmpty()); h.deliver(); assertEquals(1, h.seeks.size)
    }

    // ---- 诊断：手动提示出现时必须能从日志里读出“为什么没有自动跳过” ----

    fun theManualHintRecordsThatTheRangeWasBlockedByAnExternalSeek() {
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

    fun pausedInsideARangeIsReportedAsPausedNotAsBlocked() {
        val h = Harness(true); h.playing = false; h.controller.start(); h.deliver()
        assertEquals(SponsorPlaybackController.Hint.SKIP, h.hint)
        assertTrue(h.diagnostics.contains("auto.denied.paused"))
        assertTrue(h.detail.getValue("hint.skip").startsWith("why=paused "))
        assertTrue(h.seeks.isEmpty())
    }

    fun manualModeIsReportedAsManualOnly() {
        val h = Harness(false); h.controller.start(); h.deliver()
        assertEquals("mode=manual", h.detail["controller.start"])
        assertTrue(h.diagnostics.contains("auto.denied.manual-only"))
        assertTrue(h.detail.getValue("hint.skip").startsWith("why=manual-only "))
    }

    fun anAutomaticSkipThatWorksLeavesNoDenialAndNamesTheConfirmedKinds() {
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

    fun aManualSkipUsesItsOwnConfirmedEvent() {
        val h = Harness(); h.controller.start(); h.deliver(); h.controller.skip()
        assertTrue(h.diagnostics.contains("request.manual"))
        assertTrue(h.execute())
        assertTrue(h.diagnostics.contains("seek.confirmed.manual")); assertFalse(h.diagnostics.contains("seek.confirmed"))
    }

    fun aRefusedRequestNamesTheGuardsReason() {
        val h = Harness(true); h.accept = false; h.refusal = "not-captured"
        h.controller.start(); h.deliver()
        assertEquals("kind=auto why=not-captured", h.detail["request.rejected"])
        assertTrue(h.detail.getValue("hint.skip").startsWith("why=blocked.failed "))
        h.refusal = ""; h.controller.skip()
        assertEquals("kind=manual why=unknown", h.detail["request.rejected"])
    }

    fun aTimeoutSaysWhetherTheHostEverReleasedTheSeek() {
        val h = Harness(true); h.controller.start(); h.deliver()
        h.time = 4_000; h.timers.single().second()
        assertEquals("kind=auto executed=false", h.detail["request.timeout"])
        val ran = Harness(true); ran.controller.start(); ran.deliver()
        ran.seeks.last().executing(); ran.time = 4_000; ran.timers.single().second()
        assertEquals("kind=auto executed=true", ran.detail["request.timeout"])
    }

    fun anOwnRequestThatCannotBeConfirmedIsReportedSeparatelyFromAnExternalSeek() {
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

    fun droppingAnAutomaticRequestBecausePlaybackPausedIsRecorded() {
        val h = Harness(true); h.controller.start(); h.deliver()
        h.playing = false; h.controller.stateChanged()
        assertEquals("executed=false", h.detail["request.dropped.paused"])
    }

    fun externalSeekEventsAreCountedPerSourceAndCapped() {
        val h = Harness(true); h.controller.start(); h.deliver()
        repeat(6) { h.controller.externalSeekStarted("call") }
        h.controller.seekStarted(70_000)
        assertTrue(h.diagnostics.contains("seek.external.start.call.3"))
        assertFalse(h.diagnostics.contains("seek.external.start.call.4"))
        assertTrue(h.detail.getValue("seek.external.start.observer.1").startsWith("toMs=70000 "))
    }

    fun anUnreadablePlayerNeverBreaksTheControlFlowThroughDiagnostics() {
        val h = Harness(true); h.controller.start(); h.deliver()
        h.throwing = true // 宿主已释放播放器：读位置和状态都会抛，诊断不能因此丢掉门禁撤销
        h.controller.externalSeekStarted("call")
        h.throwing = false
        assertEquals("snapshot=unavailable", h.detail["seek.external.start.call.1"])
        assertFalse(h.execute())
    }

    fun queryAvailableNamesTheLocalCacheOriginOnlyWhenThereIsOne() {
        val network = Harness(true); network.controller.start(); network.deliver()
        assertEquals("accepted=1", network.detail["query.available"])
        for (origin in listOf("disk", "disk-stale")) {
            val h = Harness(true); h.controller.start()
            h.result(SponsorFetchResult.Available(listOf(SponsorSegment("a", 10_000, 20_000, 100_000)), origin = origin)); h.drain()
            assertEquals("accepted=1 src=$origin", h.detail["query.available"])
            assertEquals(1, h.seeks.size) // 缓存里的区间照常自动跳过
        }
    }

    fun stoppingRecordsTheSessionEndOnce() {
        val h = Harness(true); h.controller.start(); h.controller.stop(); h.controller.stop()
        assertEquals(1, h.diagnostics.count { it == "session.ended" })
    }

    fun verifyAllCases() {
        fun runIsolated(block: (SponsorPlaybackControllerCases) -> Unit) {
            val s = SponsorPlaybackControllerCases()
            block(s)
        }
        runIsolated { s -> s.manualSkipShowsUndoOnlyAfterNativeConfirmationAndUndoDoesNotLoop() }
        runIsolated { s -> s.switchingPartOrLeavingForegroundRejectsAlreadyQueuedSeekAndLateFetch() }
        runIsolated { s -> s.confirmedUndoHintExpiresAfterSixSecondsAndLateActionCannotSeek() }
        runIsolated { s -> s.manualScrubbingBeforeFetchReturnsCannotTriggerAnAutomaticSkip() }
        runIsolated { s -> s.pauseRejectsAutomaticQueuedWorkButManualSkipWhilePausedIsAllowed() }
        runIsolated { s -> s.timeoutRejectsExecutionWithoutFalselyReportingApplied() }
        runIsolated { s -> s.userSeekCancelsAnAlreadyQueuedSkip() }
        runIsolated { s -> s.missingSeekCompleteUsesAnIndependentDeadlineToRestoreManualControls() }
        runIsolated { s -> s.userGestureInvalidatesTheGateBeforeTheMainQueueProcessesIt() }
        runIsolated { s -> s.aDifferentCompletionPositionWithoutAStartEventStillClearsTheOldUndo() }
        runIsolated { s -> s.cachedSegmentsCannotUseResidualProgressBeforeTheFirstNativeNotification() }
        runIsolated { s -> s.transientQueryFailureRetriesAndCanRecoverWithinTheSamePlayback() }
        runIsolated { s -> s.persistentFailureStopsAfterThreeAttemptsAndDuplicateResultsDoNotScheduleMoreWork() }
        runIsolated { s -> s.stoppingOrChangingTheVideoPreventsPendingRetries() }
        runIsolated { s -> s.rateLimitWaitsForBackoffButPermanentErrorsAndEmptyResultsDoNotRetry() }
        runIsolated { s -> s.retryDoesNotAcceptALateResultFromThePreviousAttempt() }
        runIsolated { s -> s.theManualHintRecordsThatTheRangeWasBlockedByAnExternalSeek() }
        runIsolated { s -> s.pausedInsideARangeIsReportedAsPausedNotAsBlocked() }
        runIsolated { s -> s.manualModeIsReportedAsManualOnly() }
        runIsolated { s -> s.anAutomaticSkipThatWorksLeavesNoDenialAndNamesTheConfirmedKinds() }
        runIsolated { s -> s.aManualSkipUsesItsOwnConfirmedEvent() }
        runIsolated { s -> s.aRefusedRequestNamesTheGuardsReason() }
        runIsolated { s -> s.aTimeoutSaysWhetherTheHostEverReleasedTheSeek() }
        runIsolated { s -> s.anOwnRequestThatCannotBeConfirmedIsReportedSeparatelyFromAnExternalSeek() }
        runIsolated { s -> s.droppingAnAutomaticRequestBecausePlaybackPausedIsRecorded() }
        runIsolated { s -> s.externalSeekEventsAreCountedPerSourceAndCapped() }
        runIsolated { s -> s.anUnreadablePlayerNeverBreaksTheControlFlowThroughDiagnostics() }
        runIsolated { s -> s.queryAvailableNamesTheLocalCacheOriginOnlyWhenThereIsOne() }
        runIsolated { s -> s.stoppingRecordsTheSessionEndOnce() }
    }
}

private class SponsorPlaybackPolicyCases {
    private val ad = SponsorSegment("a", 10_000, 20_000, 100_000)
    private fun policy(auto: Boolean = true) = SponsorPlaybackPolicy(auto).apply {
        load(listOf(ad)); progress(12_000, 100_000, 0)
    }

    fun tailGuardKeepsIntervalsThatEndAtTheVideoEndManualOnly() {
        val tail = SponsorSegment("t", 90_000, 99_000, 100_000)
        fun guarded(guard: Long) = SponsorPlaybackPolicy(true, guard).apply { load(listOf(tail)); progress(95_000, 100_000, 0) }
        assertNull(guarded(1_500).request(true, 0))
        assertEquals(99_000L, guarded(1_500).request(true, 0, manual = true)?.targetMs)
        assertEquals(99_000L, guarded(500).request(true, 0)?.targetMs)
        assertEquals(99_000L, guarded(0).request(true, 0)?.targetMs)
        // 区间终点离结尾刚好等于保护值时放行，严格小于才拦。
        assertEquals(20_000L, SponsorPlaybackPolicy(true, 80_000).apply { load(listOf(ad)); progress(12_000, 100_000, 0) }
            .request(true, 0)?.targetMs)
    }

    fun manualModeAndPausedPlaybackNeverAutomaticallySeek() {
        assertNull(policy(false).request(true, 0))
        assertNull(policy().request(false, 0))
        assertEquals(20_000L, policy(false).request(false, 0, manual = true)?.targetMs)
    }

    fun overlappingSegmentsMergeAndUnknownOrChangedDurationDisablesSkips() {
        val p = policy()
        p.load(listOf(ad, ad.copy(uuid = "b", startMs = 19_000, endMs = 25_000)))
        assertEquals(25_000L, p.request(true, 0)?.targetMs)
        for (duration in listOf(0L, 110_000L, 15_000L)) {
            val invalid = policy(); invalid.progress(12_000, duration, 0)
            assertNull(invalid.request(true, 0))
        }
    }

    fun queuedRequestIsNotAppliedUntilTargetIsConfirmed() {
        val p = policy(); val request = p.request(true, 0)!!
        assertFalse(p.canUndo(1))
        assertNull(p.request(true, 1))
        assertFalse(p.confirm(12_000, 1))
        assertEquals(request, p.pending)
        assertTrue(p.confirm(20_000, 2))
        assertTrue(p.canUndo(3))
    }

    fun undoRestoresTheOriginWithoutAnAutomaticSkipLoop() {
        val p = policy(); p.request(true, 0); p.confirm(20_000, 1)
        val undo = p.requestUndo(2)!!
        assertEquals(12_000L, undo.targetMs)
        assertTrue(p.confirm(12_000, 3))
        p.progress(12_000, 100_000, 4)
        assertNull(p.request(true, 4))
        assertNotNull(p.request(true, 4, manual = true))
    }

    fun userScrubbingCancelsQueuedWorkAndPreservesTheirChosenPosition() {
        val p = policy(); p.request(true, 0)
        p.externalSeekStart(0)
        assertNull(p.pending)
        assertNull(p.request(true, 1))
        p.externalSeekComplete(15_000)
        assertNull(p.request(true, 2))
        assertNotNull(p.request(true, 2, manual = true))
    }

    fun timeoutAndStaleFailureCannotOverwriteANewerRequest() {
        val p = policy(); val old = p.request(true, 0)!!
        p.progress(12_000, 100_000, 4_000)
        assertNull(p.pending)
        assertFalse(p.confirm(20_000, 4_001))
        val retry = p.request(true, 4_002, manual = true)!!
        p.fail(old.serial)
        assertEquals(retry, p.pending)
        p.fail(retry.serial)
        assertNull(p.pending)
    }

    fun undoExpiresAndOnlyActiveRangesCanBeManuallySkipped() {
        val p = policy(); p.request(true, 0); p.confirm(20_000, 1)
        assertNull(p.requestUndo(6_002))
        p.progress(30_000, 100_000, 6_003)
        assertNull(p.request(true, 6_003, manual = true))
    }

    fun changedDurationCancelsAPendingOrUndoableSkipEvenWhenTargetStillFits() {
        val pending = policy(); pending.request(true, 0)
        pending.progress(12_000, 110_000, 1)
        assertNull(pending.pending)
        val undo = policy(); undo.request(true, 0); undo.confirm(20_000, 1)
        undo.progress(20_000, 110_000, 2)
        assertFalse(undo.canUndo(2))
    }

    fun missingSeekCompleteRecoversManualControlsWithoutResumingAutomaticSkips() {
        val p = policy(); p.externalSeekStart(0)
        assertNull(p.request(true, 3_999, manual = true))
        assertNull(p.request(true, 4_000))
        assertNotNull(p.request(false, 4_000, manual = true))
    }

    /** 诊断判定与真正的自动请求必须同源：能自动跳 ⇔ 没有拒绝原因。 */
    private fun assertAgrees(p: SponsorPlaybackPolicy, playing: Boolean, now: Long, expected: String?) {
        assertEquals(expected, p.autoDenial(playing, now))
        if (expected == null) assertNotNull(p.request(playing, now)) else assertNull(p.request(playing, now))
    }

    fun autoDenialNamesTheReasonAndAgreesWithTheRealAutomaticRequest() {
        assertAgrees(policy(), true, 0, null)
        assertAgrees(policy(false), true, 0, "manual-only")
        assertAgrees(policy(), false, 0, "paused")
        // 区间外没有“拒绝”可言（返回 null），当然也不会发请求。
        val outside = policy(); outside.progress(50_000, 100_000, 0)
        assertNull(outside.autoDenial(true, 0)); assertNull(outside.request(true, 0))
    }

    fun autoDenialDistinguishesPendingSeekingSuspendedAndTailGuard() {
        val pending = policy(); pending.request(true, 0)
        assertAgrees(pending, true, 1, "pending")
        val scrub = policy(); scrub.externalSeekStart(0)
        assertAgrees(scrub, true, 1, "seeking")
        val lost = policy(); lost.externalSeekStart(0)
        assertAgrees(lost, true, 4_000, "suspended")
        val tail = SponsorSegment("t", 90_000, 99_000, 100_000)
        val guarded = SponsorPlaybackPolicy(true, 1_500).apply { load(listOf(tail)); progress(95_000, 100_000, 0) }
        assertAgrees(guarded, true, 0, "tail-guard")
    }

    fun blockedRangesReportTheirFirstCause() {
        val drag = policy(); drag.externalSeekStart(0); drag.externalSeekComplete(15_000)
        assertAgrees(drag, true, 1, "blocked.external-seek")

        val replay = policy(); replay.request(true, 0); replay.confirm(20_000, 1); replay.progress(12_000, 100_000, 2)
        assertAgrees(replay, true, 2, "blocked.confirmed")

        val failed = policy(); failed.request(true, 0)!!.let { failed.fail(it.serial) }
        assertAgrees(failed, true, 1, "blocked.failed")

        val interrupted = policy(); interrupted.request(true, 0); interrupted.externalSeekStart(1)
        interrupted.externalSeekComplete(30_000); interrupted.progress(12_000, 100_000, 2)
        assertAgrees(interrupted, true, 2, "blocked.interrupted")
    }

    fun autoDenialHasNoSideEffectsOnThePendingRequest() {
        val p = policy(); val request = p.request(true, 0)!!
        repeat(3) { p.autoDenial(true, 1) }
        assertEquals(request, p.pending)
        assertTrue(p.confirm(20_000, 2))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (SponsorPlaybackPolicyCases) -> Unit) {
            val s = SponsorPlaybackPolicyCases()
            block(s)
        }
        runIsolated { s -> s.tailGuardKeepsIntervalsThatEndAtTheVideoEndManualOnly() }
        runIsolated { s -> s.manualModeAndPausedPlaybackNeverAutomaticallySeek() }
        runIsolated { s -> s.overlappingSegmentsMergeAndUnknownOrChangedDurationDisablesSkips() }
        runIsolated { s -> s.queuedRequestIsNotAppliedUntilTargetIsConfirmed() }
        runIsolated { s -> s.undoRestoresTheOriginWithoutAnAutomaticSkipLoop() }
        runIsolated { s -> s.userScrubbingCancelsQueuedWorkAndPreservesTheirChosenPosition() }
        runIsolated { s -> s.timeoutAndStaleFailureCannotOverwriteANewerRequest() }
        runIsolated { s -> s.undoExpiresAndOnlyActiveRangesCanBeManuallySkipped() }
        runIsolated { s -> s.changedDurationCancelsAPendingOrUndoableSkipEvenWhenTargetStillFits() }
        runIsolated { s -> s.missingSeekCompleteRecoversManualControlsWithoutResumingAutomaticSkips() }
        runIsolated { s -> s.autoDenialNamesTheReasonAndAgreesWithTheRealAutomaticRequest() }
        runIsolated { s -> s.autoDenialDistinguishesPendingSeekingSuspendedAndTailGuard() }
        runIsolated { s -> s.blockedRangesReportTheirFirstCause() }
        runIsolated { s -> s.autoDenialHasNoSideEffectsOnThePendingRequest() }
    }
}

private class SponsorPlayerBindingsCases {
    fun containerAssociationDoesNotRequireTheBadNetworkServiceOrAssumeTheForegroundPage() {
        val bindings = SponsorPlayerBindings(); val owner = Any(); val wrapper = Any(); val core = Any()
        val activity = Any(); val other = Any()
        bindings.wrapper(owner, wrapper, core); bindings.ready(owner, bindings.begin(owner))
        assertNull(bindings.select(activity))
        assertTrue(bindings.container(wrapper, activity))
        assertSame(owner, bindings.select(activity)!!.first)
        assertNull(bindings.select(other))
    }

    fun contextAndWrapperEventsCanArriveInEitherOrder() {
        for (contextFirst in listOf(true, false)) {
            val bindings = SponsorPlayerBindings(); val owner = Any(); val wrapper = Any(); val core = Any(); val activity = Any()
            if (contextFirst) assertFalse(bindings.container(core, activity))
            bindings.wrapper(owner, wrapper, core)
            if (!contextFirst) assertTrue(bindings.container(core, activity))
            bindings.ready(owner, bindings.begin(owner))
            assertSame(owner, bindings.select(activity)!!.first)
        }
    }

    fun staleResumeAndReleaseCannotActivateOrInvalidateANewerGeneration() {
        val bindings = SponsorPlayerBindings(); val owner = Any(); val wrapper = Any(); val activity = Any()
        bindings.wrapper(owner, wrapper, Any()); bindings.activity(owner, activity)
        val old = bindings.begin(owner); val current = bindings.begin(owner)
        bindings.ready(owner, old); assertNull(bindings.select(activity))
        bindings.ready(owner, current); bindings.release(owner, old)
        assertNotNull(bindings.select(activity))
        bindings.release(owner, current); assertNull(bindings.select(activity))
    }

    fun replacingAContainerRequiresANewExplicitAssociationAndInvalidatesTheOldSession() {
        val bindings = SponsorPlayerBindings(); val owner = Any(); val old = Any(); val next = Any(); val activity = Any()
        bindings.wrapper(owner, old, Any()); bindings.activity(owner, activity)
        val epoch = bindings.begin(owner); bindings.ready(owner, epoch)
        val candidate = bindings.select(activity)!!.second
        assertTrue(bindings.current(owner, candidate, epoch, old, activity))
        bindings.wrapper(owner, next, Any())
        assertNull(bindings.select(activity)); assertFalse(bindings.current(owner, candidate, epoch, old, activity))
        bindings.container(next, activity)
        assertSame(next, bindings.select(activity)!!.second.wrapper.get())
        bindings.release(owner, epoch); assertNotNull(bindings.select(activity))
    }

    fun aSharedRawCoreDoesNotBindTwoOwnersToAnUnrelatedActivity() {
        val bindings = SponsorPlayerBindings(); val first = Any(); val second = Any(); val core = Any()
        val firstWrapper = Any(); val secondWrapper = Any(); val activity = Any()
        bindings.wrapper(first, firstWrapper, core); bindings.wrapper(second, secondWrapper, core)
        bindings.ready(first, bindings.begin(first)); bindings.ready(second, bindings.begin(second))
        assertFalse(bindings.container(core, activity)); assertNull(bindings.select(activity))
        assertTrue(bindings.container(secondWrapper, activity)); assertSame(second, bindings.select(activity)!!.first)
    }

    fun destroyingTheActivityRemovesBothLiveAndPendingAssociations() {
        val bindings = SponsorPlayerBindings(); val owner = Any(); val wrapper = Any(); val core = Any(); val activity = Any()
        bindings.container(core, activity); bindings.destroy(activity)
        bindings.wrapper(owner, wrapper, core); bindings.ready(owner, bindings.begin(owner))
        assertNull(bindings.select(activity))
        bindings.activity(owner, activity); assertNotNull(bindings.select(activity))
        bindings.destroy(activity); assertNull(bindings.select(activity))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (SponsorPlayerBindingsCases) -> Unit) {
            val s = SponsorPlayerBindingsCases()
            block(s)
        }
        runIsolated { s -> s.containerAssociationDoesNotRequireTheBadNetworkServiceOrAssumeTheForegroundPage() }
        runIsolated { s -> s.contextAndWrapperEventsCanArriveInEitherOrder() }
        runIsolated { s -> s.staleResumeAndReleaseCannotActivateOrInvalidateANewerGeneration() }
        runIsolated { s -> s.replacingAContainerRequiresANewExplicitAssociationAndInvalidatesTheOldSession() }
        runIsolated { s -> s.aSharedRawCoreDoesNotBindTwoOwnersToAnUnrelatedActivity() }
        runIsolated { s -> s.destroyingTheActivityRemovesBothLiveAndPendingAssociations() }
    }
}

private class SponsorRuntimeDiagnosticsCases {
    fun repeatedProgressAndFailuresWriteOnlyOncePerEvent() {
        val logs = mutableListOf<Triple<String, String, Boolean>>()
        val diagnostics = SponsorRuntimeDiagnostics { key, text, error -> logs += Triple(key, text, error) }
        repeat(1000) { diagnostics.record("progress.observed"); diagnostics.record("query.failed.timeout") }
        assertEquals(2, logs.size)
        assertFalse(logs.first().third); assertTrue(logs.last().third)
        assertNotEquals(logs.first().first, logs.last().first)
    }

    fun eventCountAndMessageLengthHaveHardBudgets() {
        val logs = mutableListOf<String>()
        val diagnostics = SponsorRuntimeDiagnostics { _, text, _ -> logs += text }
        repeat(100) { diagnostics.record("event$it", "x".repeat(200)) }
        assertEquals(32, logs.size); assertTrue(logs.all { it.length < 210 })
    }

    fun eachSessionRecordsItsOwnEventsUnderUniqueKeys() {
        val logs = mutableListOf<Triple<String, String, Boolean>>()
        val diagnostics = SponsorRuntimeDiagnostics { key, text, error -> logs += Triple(key, text, error) }
        val first = diagnostics.session(); val second = diagnostics.session()
        first("seek.confirmed", "a"); first("seek.confirmed", "b"); second("seek.confirmed", "c")
        assertEquals(2, logs.size)
        // 宿主 logInfo 按 key 去重，两个会话的同名事件必须是不同的 key，否则第二个视频的日志会被吞掉。
        assertNotEquals(logs[0].first, logs[1].first)
        assertTrue(logs[0].second.contains("#1 seek.confirmed a")); assertTrue(logs[1].second.contains("#2 seek.confirmed c"))
        diagnostics.record("seek.confirmed")
        assertEquals(3, logs.size)
        assertNotEquals(logs[2].first, logs[0].first); assertNotEquals(logs[2].first, logs[1].first)
        first("query.failed.timeout", "")
        assertTrue(logs.last().third)
    }

    fun sessionAndProcessLineBudgetsAreHard() {
        val logs = mutableListOf<String>()
        val diagnostics = SponsorRuntimeDiagnostics { _, text, _ -> logs += text }
        val one = diagnostics.session()
        repeat(100) { one("event$it", "x".repeat(200)) }
        assertEquals(SponsorRuntimeDiagnostics.SESSION_EVENTS, logs.size); assertTrue(logs.all { it.length < 230 })
        logs.clear()
        repeat(200) { index -> val session = diagnostics.session(); repeat(10) { session("e$it", "$index") } }
        assertEquals(SponsorRuntimeDiagnostics.PROCESS_LINES - SponsorRuntimeDiagnostics.SESSION_EVENTS, logs.size)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (SponsorRuntimeDiagnosticsCases) -> Unit) {
            val s = SponsorRuntimeDiagnosticsCases()
            block(s)
        }
        runIsolated { s -> s.repeatedProgressAndFailuresWriteOnlyOncePerEvent() }
        runIsolated { s -> s.eventCountAndMessageLengthHaveHardBudgets() }
        runIsolated { s -> s.eachSessionRecordsItsOwnEventsUnderUniqueKeys() }
        runIsolated { s -> s.sessionAndProcessLineBudgetsAreHard() }
    }
}
