package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SponsorSeekGuardTest {
    fun queuedCloneIsRecheckedAtExecutionAfterAVideoSwitch() {
        val guard = SponsorSeekGuard(); val owner = Any(); val original = Any(); val clone = Any()
        var current = true; var executions = 0
        assertTrue(guard.submit(owner, 20, { current }, { executions++ }) { guard.capture(original, owner, 20) })
        guard.carry(original, clone)
        current = false
        assertFalse(guard.allow(clone))
        assertEquals(0, executions)
    }
    fun userAndOtherPlayerCoroutinesRemainUntouched() {
        val guard = SponsorSeekGuard(); val owner = Any(); val user = Any()
        assertTrue(guard.allow(user))
        assertFalse(guard.submit(owner, 20, { true }, {}) { guard.capture(user, Any(), 20) })
        assertTrue(guard.allow(user))
    }
    fun acceptedRequestIsMarkedOnceAndRejectedScopeNeverInvokesTheHost() {
        val guard = SponsorSeekGuard(); val owner = Any(); val coroutine = Any(); var count = 0
        assertFalse(guard.submit(owner, 20, { false }, {}) { fail("must not invoke") })
        assertTrue(guard.submit(owner, 20, { true }, { count++ }) { guard.capture(coroutine, owner, 20) })
        assertTrue(guard.allow(coroutine)); assertTrue(guard.allow(coroutine))
        assertEquals(1, count)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (SponsorSeekGuardTest) -> Unit) {
            val s = SponsorSeekGuardTest()
            block(s)
        }
        runIsolated { s -> s.queuedCloneIsRecheckedAtExecutionAfterAVideoSwitch() }
        runIsolated { s -> s.userAndOtherPlayerCoroutinesRemainUntouched() }
        runIsolated { s -> s.acceptedRequestIsMarkedOnceAndRejectedScopeNeverInvokesTheHost() }
    
        SponsorSegmentRepositoryCases().verifyAllCases()
        SponsorSegmentsCases().verifyAllCases()
        StorySponsorGateCases().verifyAllCases()
        StorySponsorSessionCases().verifyAllCases()
    }
}

private class SponsorSegmentRepositoryCases {
    private val video = SponsorVideoId("BV14741127BN", 168885122)
    private fun await(latch: CountDownLatch) { assertTrue(latch.await(3, TimeUnit.SECONDS)) }

    fun concurrentConsumersShareOneFetchAndAnAvailableResultIsCached() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val delivered = CountDownLatch(2)
        val calls = AtomicInteger()
        SponsorSegmentRepository(SponsorSegmentSource { _, _ ->
            calls.incrementAndGet(); entered.countDown(); await(release); SponsorFetchResult.Available(emptyList())
        }).use { repo ->
            repo.lookup(video) { delivered.countDown() }; await(entered)
            repo.lookup(video) { delivered.countDown() }; release.countDown(); await(delivered)
            val cached = CountDownLatch(1); repo.lookup(video) { cached.countDown() }; await(cached)
            assertEquals(1, calls.get())
        }
    }
    fun cancellingOneConsumerDoesNotCancelTheOther() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val delivered = CountDownLatch(1)
        val cancelledDeliveries = AtomicInteger()
        SponsorSegmentRepository(SponsorSegmentSource { _, _ ->
            entered.countDown(); await(release); SponsorFetchResult.Available(emptyList())
        }).use { repo ->
            val ticket = repo.lookup(video) { cancelledDeliveries.incrementAndGet() }; await(entered)
            repo.lookup(video) { delivered.countDown() }; ticket.cancel(); release.countDown(); await(delivered)
            assertEquals(0, cancelledDeliveries.get())
        }
    }
    fun hardDeadlineReportsFailureAndLateCompletionCannotReplaceANewFlight() {
        val firstEntered = CountDownLatch(1); val releaseOld = CountDownLatch(1); val oldDone = CountDownLatch(1)
        val expired = CountDownLatch(1); val secondDelivered = CountDownLatch(1); val calls = AtomicInteger()
        SponsorSegmentRepository(SponsorSegmentSource { _, _ ->
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown()
                while (releaseOld.count > 0) try { releaseOld.await() } catch (_: InterruptedException) { }
                oldDone.countDown(); SponsorFetchResult.Available(listOf(SponsorSegment("old", 1, 2, 0)))
            } else SponsorFetchResult.Available(listOf(SponsorSegment("new", 1, 2, 0)))
        }, timeoutMs = 100).use { repo ->
            repo.lookup(video) { assertTrue(it is SponsorFetchResult.Unavailable); expired.countDown() }
            await(firstEntered); await(expired)
            repo.lookup(video) { assertEquals("new", (it as SponsorFetchResult.Available).segments.single().uuid); secondDelivered.countDown() }
            await(secondDelivered); releaseOld.countDown(); await(oldDone)
            val checked = CountDownLatch(1)
            repo.lookup(video) { assertEquals("new", (it as SponsorFetchResult.Available).segments.single().uuid); checked.countDown() }
            await(checked)
        }
    }
    fun unavailableResultsAreNotCachedAndCloseRejectsNewWork() {
        val calls = AtomicInteger(); val repo = SponsorSegmentRepository(SponsorSegmentSource { _, _ ->
            calls.incrementAndGet(); SponsorFetchResult.Unavailable("offline")
        })
        repeat(2) { val done = CountDownLatch(1); repo.lookup(video) { done.countDown() }; await(done) }
        assertEquals(2, calls.get()); repo.close()
        var reason = ""; repo.lookup(video) { reason = (it as SponsorFetchResult.Unavailable).reason }
        assertEquals("closed", reason)
    }
    fun serverRateLimitBacksOffAcrossVideosWithoutDiscardingCachedData() {
        val calls = AtomicInteger(); var clock = 0L
        SponsorSegmentRepository(SponsorSegmentSource { _, _ ->
            calls.incrementAndGet(); SponsorFetchResult.Unavailable("rate-limited")
        }, now = { clock }).use { repo ->
            val done = CountDownLatch(1); repo.lookup(video) { done.countDown() }; await(done)
            repo.lookup(video.copy(cid = 2)) { assertEquals("rate-limited", (it as SponsorFetchResult.Unavailable).reason) }
            assertEquals(1, calls.get())
            clock = 60_001
            val retry = CountDownLatch(1); repo.lookup(video) { retry.countDown() }; await(retry)
            assertEquals(2, calls.get())
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (SponsorSegmentRepositoryCases) -> Unit) {
            val s = SponsorSegmentRepositoryCases()
            block(s)
        }
        runIsolated { s -> s.concurrentConsumersShareOneFetchAndAnAvailableResultIsCached() }
        runIsolated { s -> s.cancellingOneConsumerDoesNotCancelTheOther() }
        runIsolated { s -> s.hardDeadlineReportsFailureAndLateCompletionCannotReplaceANewFlight() }
        runIsolated { s -> s.unavailableResultsAreNotCachedAndCloseRejectsNewWork() }
        runIsolated { s -> s.serverRateLimitBacksOffAcrossVideosWithoutDiscardingCachedData() }
    }
}

private class SponsorSegmentsCases {
    private val id = SponsorVideoId("BV14741127BN", 168885122)
    private fun segment(uuid: String = "abc") = JSONObject().put("UUID", uuid).put("cid", id.cid.toString())
        .put("category", "sponsor").put("actionType", "skip").put("segment", JSONArray(listOf(10.25, 20.5)))
        .put("videoDuration", 100)
    private fun payload(vararg items: JSONObject) = JSONArray().put(JSONObject().put("videoID", id.bvid)
        .put("segments", JSONArray(items.toList()))).toString()

    fun prefixUsesTheBvidOnlyAndTheKnownPublicProtocolVector() {
        assertEquals("5759", id.hashPrefix)
        assertEquals("https://www.bsbsb.top/api/skipSegments/5759", SponsorBlockClient.endpoint(id))
        assertEquals(id.hashPrefix, id.copy(cid = 1).hashPrefix)
        assertThrows(IllegalArgumentException::class.java) { SponsorVideoId("https://evil.invalid", 1) }
    }
    fun exactVideoPartAndActionAreRequired() {
        val good = segment()
        val body = payload(good, segment("other-part").put("cid", "1"), segment("point").put("actionType", "poi"),
            segment("intro").put("category", "intro"))
        assertEquals(listOf(SponsorSegment("abc", 10_250, 20_500, 100_000)), SponsorSegmentParser.parse(body, id))
        assertEquals(SponsorParseSummary(4, 1, 1, 1, 0), SponsorSegmentParser.parseDetailed(body, id).summary)
        assertTrue(SponsorSegmentParser.parse(body, id.copy(bvid = "BV1Q8P7z8Exw")).isEmpty())
    }

    fun avidMappingMatchesUpstreamVectorsIncludingLargeIdentifiersAndRejectsOutOfRangeValues() {
        val vectors = listOf(2L to "BV1xx411c7mD", 170_001L to "BV17x411w7KC",
            455_017_605L to "BV1Q541167Qg", 882_584_971L to "BV1mK4y1C7Bz",
            80_433_022L to "BV1GJ411x7h7", 713_984_017L to "BV18X4y1N7Yh",
            113_864_347_752_328L to "BV1CDwbeEE46", 117_312_082_479_704L to "BV1FnhC6qEck")
        vectors.forEach { (avid, bvid) -> assertEquals(bvid, SponsorVideoId.bvidFromAvid(avid)) }
        for (invalid in listOf(Long.MIN_VALUE, -1L, 0L, 2_251_799_813_685_248L, Long.MAX_VALUE)) {
            assertNull(SponsorVideoId.bvidFromAvid(invalid))
        }
    }
    fun invalidRangesAndDuplicateContradictionsCannotBecomeSkips() {
        for (range in listOf(listOf(-1, 5), listOf(5, 4), listOf(1), listOf(1, 999_999_999))) {
            assertTrue(SponsorSegmentParser.parse(payload(segment().put("segment", JSONArray(range))), id).isEmpty())
        }
        assertThrows(IllegalArgumentException::class.java) {
            SponsorSegmentParser.parse(payload(segment(), segment().put("segment", JSONArray(listOf(11, 22)))), id)
        }
    }
    fun runtimeDurationChecksRejectEditedMediaAndUnknownPlayerDuration() {
        val item = SponsorSegment("id", 10_000, 20_000, 100_000)
        assertTrue(item.fits(100_000)); assertTrue(item.fits(102_000)); assertFalse(item.fits(103_000))
        assertFalse(item.fits(0)); assertFalse(item.copy(endMs = 110_000).fits(100_000))
    }

    fun malformedRangesAndMissingCidAreReportedWithoutRelaxingPartChecks() {
        val missing = segment("missing").apply { remove("cid") }
        val parsed = SponsorSegmentParser.parseDetailed(payload(missing,
            segment("invalid").put("segment", JSONArray(listOf(20, 10)))), id)
        assertTrue(parsed.segments.isEmpty())
        assertEquals(SponsorParseSummary(2, 0, 0, 1, 1), parsed.summary)
    }

    fun reportedVideoWithLargeCidAcceptsItsActualSponsorAndProducesTheExpectedJump() {
        // 2026-10-09 反馈视频的公开接口向量，离线验证解析与跳转策略，不依赖网络。
        val video = SponsorVideoId("BV1FnhC6qEck", 42_072_543_108L)
        val body = """[{"videoID":"BV1FnhC6qEck","segments":[{
            "UUID":"f64c944a842c28eb2df0b60ae108285d74aa4b4abe6791d178abf85f4f1ca70c7",
            "cid":"42072543108","category":"sponsor","actionType":"skip",
            "segment":[450.415,509.762],"videoDuration":654}]}]"""
        val parsed = SponsorSegmentParser.parseDetailed(body, video)
        assertEquals("de1d", video.hashPrefix)
        assertEquals(SponsorParseSummary(1, 0, 0, 0, 0), parsed.summary)
        assertEquals(1, parsed.segments.size); assertTrue(parsed.segments.single().fits(654_000))
        val policy = SponsorPlaybackPolicy(true).apply { load(parsed.segments); progress(450_415, 654_000, 0) }
        assertEquals(509_762L, policy.request(true, 0)?.targetMs)
        assertTrue(policy.confirm(509_762, 1)); assertTrue(policy.canUndo(2))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (SponsorSegmentsCases) -> Unit) {
            val s = SponsorSegmentsCases()
            block(s)
        }
        runIsolated { s -> s.prefixUsesTheBvidOnlyAndTheKnownPublicProtocolVector() }
        runIsolated { s -> s.exactVideoPartAndActionAreRequired() }
        runIsolated { s -> s.avidMappingMatchesUpstreamVectorsIncludingLargeIdentifiersAndRejectsOutOfRangeValues() }
        runIsolated { s -> s.invalidRangesAndDuplicateContradictionsCannotBecomeSkips() }
        runIsolated { s -> s.runtimeDurationChecksRejectEditedMediaAndUnknownPlayerDuration() }
        runIsolated { s -> s.malformedRangesAndMissingCidAreReportedWithoutRelaxingPartChecks() }
        runIsolated { s -> s.reportedVideoWithLargeCidAcceptsItsActualSponsorAndProducesTheExpectedJump() }
    }
}

private class StorySponsorGateCases {
    private val aid = 117_312_082_479_704L
    private val cid = 42_072_543_108L
    private val bvid = "BV1FnhC6qEck"

    private fun playable(id: String = "p1", aid: Long = this.aid, cid: Long = this.cid,
        vupload: Boolean = true, ugc: Boolean = true) = StoryPlayableIdentity(id, aid, cid, vupload, ugc)

    private fun detail(aid: Long = this.aid, cid: Long = this.cid, bvid: String = this.bvid, ugc: Boolean = true,
        playable: Boolean = true, ad: Boolean = false, adInfo: Boolean = false) =
        StoryDetailSnapshot(aid, cid, bvid, ugc, playable, ad, adInfo)

    private fun deny(playable: StoryPlayableIdentity?, loaded: String?, details: List<StoryDetailSnapshot>) =
        (StorySponsorGate.evaluate(playable, loaded, details) as StorySponsorVerdict.Deny).reason

    fun ordinaryUgcWithMatchingLoadedMediaAndPagerItemIsAllowed() {
        val verdict = StorySponsorGate.evaluate(playable(), "p1", listOf(detail()))
        assertEquals(StorySponsorVerdict.Allow(SponsorVideoId(bvid, cid), "p1"), verdict)
    }

    fun emptyBvidOnTheDetailFallsBackToTheIdDerivedFromAid() {
        assertTrue(StorySponsorGate.evaluate(playable(), "p1", listOf(detail(bvid = ""))) is StorySponsorVerdict.Allow)
    }

    fun cursorAheadOfTheLoadedMediaIsRejected() {
        assertEquals("cursor-mismatch", deny(playable(id = "next"), "p1", listOf(detail())))
        assertEquals("cursor-mismatch", deny(playable(), null, listOf(detail())))
        assertEquals("cursor-mismatch", deny(playable(id = ""), "", listOf(detail())))
        assertEquals("no-playable", deny(null, "p1", listOf(detail())))
    }

    fun playableLevelAdmissionAloneNeverEnoughSoMissingDetailFailsClosed() {
        assertEquals("no-detail", deny(playable(), "p1", emptyList()))
        // 另一页的详情不能顶替当前条目。
        assertEquals("no-detail", deny(playable(), "p1", listOf(detail(aid = aid + 1))))
        assertEquals("no-detail", deny(playable(), "p1", listOf(detail(cid = cid + 1))))
    }

    fun nonUgcPlayableAndInvalidIdentityAreRejectedBeforeAnyDetailLookup() {
        assertEquals("non-ugc", deny(playable(vupload = false), "p1", listOf(detail())))
        assertEquals("non-ugc", deny(playable(ugc = false), "p1", listOf(detail())))
        assertEquals("invalid-identity", deny(playable(aid = 0), "p1", listOf(detail())))
        assertEquals("invalid-identity", deny(playable(cid = 0), "p1", listOf(detail())))
    }

    fun adsGameCardsAndPreviewsThatShareTheUgcPlayableShapeAreRejectedByTheDetail() {
        assertEquals("ad", deny(playable(), "p1", listOf(detail(ad = true))))
        // 自然广告：goto 仍是 vertical_av，但带广告信息。
        assertEquals("ad", deny(playable(), "p1", listOf(detail(adInfo = true))))
        // vertical_ad_av / vertical_game / vertical_preview_av：goto 不是 vertical_av。
        assertEquals("non-ugc-detail", deny(playable(), "p1", listOf(detail(ugc = false))))
        // blockingWall 非空：条目被挡住，不是可播放视频；解锁后可能变好，所以单独成因。
        assertEquals("blocked-detail", deny(playable(), "p1", listOf(detail(playable = false))))
    }

    fun denialsThatAreAPropertyOfTheItemAreFinalAndTheOnesThatAreJustNotReadyAreNot() {
        fun final(playable: StoryPlayableIdentity?, loaded: String?, details: List<StoryDetailSnapshot>) =
            (StorySponsorGate.evaluate(playable, loaded, details) as StorySponsorVerdict.Deny).final
        val permanent = listOf(
            final(playable(vupload = false), "p1", listOf(detail())),
            final(playable(ugc = false), "p1", listOf(detail())),
            final(playable(aid = 0), "p1", listOf(detail())),
            final(playable(aid = Long.MAX_VALUE), "p1", listOf(detail(aid = Long.MAX_VALUE))),
            final(playable(), "p1", listOf(detail(ad = true))),
            final(playable(), "p1", listOf(detail(adInfo = true))),
            final(playable(), "p1", listOf(detail(ugc = false))),
            final(playable(), "p1", listOf(detail(bvid = "BV14741127BN")))
        )
        assertTrue(permanent.all { it })
        val notReady = listOf(
            final(null, "p1", listOf(detail())),
            final(playable(id = "next"), "p1", listOf(detail())),
            final(playable(), null, listOf(detail())),
            final(playable(), "p1", emptyList()),
            final(playable(), "p1", listOf(detail(playable = false)))
        )
        assertTrue(notReady.none { it })
    }

    fun everyPagerItemMatchingTheCursorMustPassTheGate() {
        assertEquals("ad", deny(playable(), "p1", listOf(detail(), detail(ad = true))))
        assertTrue(StorySponsorGate.evaluate(playable(), "p1",
            listOf(detail(), detail(aid = aid + 1, ad = true))) is StorySponsorVerdict.Allow)
    }

    fun aBvidThatDisagreesWithTheAidIsRejected() {
        assertEquals("bvid-mismatch", deny(playable(), "p1", listOf(detail(bvid = "BV14741127BN"))))
        assertEquals("invalid-aid", deny(playable(aid = Long.MAX_VALUE), "p1", listOf(detail(aid = Long.MAX_VALUE))))
    }

    fun lookupBudgetAdmitsALimitedNumberPerWindowAndRecovers() {
        var now = 0L
        val budget = StoryLookupBudget(3, 60_000) { now }
        assertTrue(budget.tryAcquire()); assertTrue(budget.tryAcquire()); assertTrue(budget.tryAcquire())
        assertFalse(budget.tryAcquire())
        now = 59_999; assertFalse(budget.tryAcquire())
        now = 60_000
        repeat(3) { assertTrue(budget.tryAcquire()) }
        assertFalse(budget.tryAcquire())
    }

    fun verifyAllCases() {
        fun runIsolated(block: (StorySponsorGateCases) -> Unit) {
            val s = StorySponsorGateCases()
            block(s)
        }
        runIsolated { s -> s.ordinaryUgcWithMatchingLoadedMediaAndPagerItemIsAllowed() }
        runIsolated { s -> s.emptyBvidOnTheDetailFallsBackToTheIdDerivedFromAid() }
        runIsolated { s -> s.cursorAheadOfTheLoadedMediaIsRejected() }
        runIsolated { s -> s.playableLevelAdmissionAloneNeverEnoughSoMissingDetailFailsClosed() }
        runIsolated { s -> s.nonUgcPlayableAndInvalidIdentityAreRejectedBeforeAnyDetailLookup() }
        runIsolated { s -> s.adsGameCardsAndPreviewsThatShareTheUgcPlayableShapeAreRejectedByTheDetail() }
        runIsolated { s -> s.denialsThatAreAPropertyOfTheItemAreFinalAndTheOnesThatAreJustNotReadyAreNot() }
        runIsolated { s -> s.everyPagerItemMatchingTheCursorMustPassTheGate() }
        runIsolated { s -> s.aBvidThatDisagreesWithTheAidIsRejected() }
        runIsolated { s -> s.lookupBudgetAdmitsALimitedNumberPerWindowAndRecovers() }
    }
}

private class StorySponsorSessionCases {
    private val aid = 117_312_082_479_704L
    private val cid = 42_072_543_108L
    private val bvid = "BV1FnhC6qEck"

    private class Host : StorySponsorHost {
        var alive = true; var resumed = true; var state = 4
        var position = 12_000L; var duration = 100_000L
        // 普通条目的真实值就是逻辑值；只有裁剪/逻辑时间轴的条目两者才不同。
        var realPositionOverride: Long? = null; var realDurationOverride: Long? = null
        var playable: StoryPlayableIdentity? = null; var loaded: String? = null
        var details: List<StoryDetailSnapshot> = emptyList()
        var onSeek: (Int) -> Unit = {}
        val seeks = mutableListOf<Int>()
        var playableReads = 0; var detailReads = 0
        override fun alive() = alive
        override fun resumed() = resumed
        override fun state() = state
        override fun position() = position
        override fun duration() = duration
        override fun realPosition() = realPositionOverride ?: position
        override fun realDuration() = realDurationOverride ?: duration
        override fun playable(): StoryPlayableIdentity? { playableReads++; return playable }
        override fun loadedId() = loaded
        override fun details(): List<StoryDetailSnapshot> { detailReads++; return details }
        override fun seek(targetMs: Int) { seeks += targetMs; onSeek(targetMs) }
    }

    private inner class Harness(automatic: Boolean = true, budgetLimit: Int = 12) {
        val host = Host()
        var time = 0L
        val lookups = mutableListOf<SponsorVideoId>()
        val callbacks = mutableListOf<(SponsorFetchResult) -> Unit>()
        var cancelled = 0
        var applied = 0
        var hint = SponsorPlaybackController.Hint.NONE
        val diagnostics = mutableListOf<String>()
        val queue = ArrayDeque<() -> Unit>()
        val order = mutableListOf<String>()
        val session = StorySponsorSession(host, automatic,
            { video, callback -> lookups += video; callbacks += callback; SponsorSegmentRepository.Ticket { cancelled++ } },
            StoryLookupBudget(budgetLimit, 60_000) { time },
            { queue.add(it) },
            { _, _ -> SponsorSegmentRepository.Ticket { } },
            { time }, { hint = it }, { applied++ }, { event, _ -> diagnostics += event })

        init { select("p1", aid, cid) }

        fun select(id: String, aid: Long, cid: Long, ugc: Boolean = true, ad: Boolean = false, adInfo: Boolean = false) {
            host.playable = StoryPlayableIdentity(id, aid, cid, vupload = true, ugc = true)
            host.loaded = id
            host.details = listOf(StoryDetailSnapshot(aid, cid, SponsorVideoId.bvidFromAvid(aid).orEmpty(), ugc, true, ad, adInfo))
        }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }
        fun deliver(vararg segments: SponsorSegment) {
            callbacks.last()(SponsorFetchResult.Available(segments.toList())); drain()
        }
        /** 模拟宿主：seek 同步触发 onSeekStart，随后 onSeekComplete。 */
        fun nativeSeek() {
            host.onSeek = { target ->
                order += "seekTo"
                session.invalidateExternalSeek(target.toLong()); session.seekStarted(target.toLong())
                host.position = target.toLong(); session.seekCompleted()
            }
        }
    }

    private val range = SponsorSegment("a", 10_000, 20_000, 100_000)

    fun nothingIsQueriedUntilTheLoadedItemReportsItsFirstProgressTick() {
        val h = Harness()
        h.session.resumed(); h.session.stateChanged()
        assertTrue(h.lookups.isEmpty()); assertNull(h.session.bound)
        h.session.progressTick()
        assertEquals(listOf(SponsorVideoId(bvid, cid)), h.lookups)
        assertEquals(SponsorVideoId(bvid, cid), h.session.bound)
    }

    fun automaticSkipMarksTheRequestExecutingBeforeTheSynchronousCoreSeek() {
        val h = Harness(); h.nativeSeek()
        h.session.progressTick(); h.deliver(range)
        assertEquals(listOf(20_000), h.host.seeks)
        assertEquals(1, h.applied)
        assertEquals(SponsorPlaybackController.Hint.UNDO, h.hint)
    }

    fun cursorAheadOfLoadedMediaNeverQueriesAndAdsAreExcluded() {
        val h = Harness()
        h.host.loaded = "previous"
        h.session.progressTick()
        assertTrue(h.lookups.isEmpty()); assertTrue("bind.denied.cursor-mismatch" in h.diagnostics)
        val ad = Harness(); ad.select("p1", aid, cid, ad = true)
        ad.session.progressTick()
        assertTrue(ad.lookups.isEmpty()); assertTrue("bind.denied.ad" in ad.diagnostics)
        val game = Harness(); game.select("p1", aid, cid, ugc = false)
        game.session.progressTick()
        assertTrue(game.lookups.isEmpty()); assertTrue("bind.denied.non-ugc-detail" in game.diagnostics)
        val natural = Harness(); natural.select("p1", aid, cid, adInfo = true)
        natural.session.progressTick()
        assertTrue(natural.lookups.isEmpty())
    }

    fun anItemThatIsPermanentlyNotApplicableIsJudgedOnceInsteadOfOnEveryTick() {
        val h = Harness(); h.select("p1", aid, cid, ad = true)
        repeat(30) { h.session.progressTick() }
        assertEquals(1, h.host.playableReads); assertEquals(1, h.host.detailReads)
        assertTrue(h.lookups.isEmpty()); assertNull(h.session.bound)
        // 换条目后重新判断：新条目是普通投稿就正常绑定。
        h.session.itemChanged()
        h.select("p2", aid + 7, cid + 7)
        h.session.progressTick()
        assertEquals(2, h.host.playableReads)
        assertEquals(SponsorVideoId(SponsorVideoId.bvidFromAvid(aid + 7)!!, cid + 7), h.session.bound)
    }

    fun anItemThatIsOnlyNotReadyYetIsJudgedAgainOnEveryTick() {
        val h = Harness()
        h.host.loaded = "previous"
        repeat(3) { h.session.progressTick() }
        assertEquals(3, h.host.playableReads)
        // 详情还没到：同样每次都重判，到了就绑定。
        val late = Harness(); late.host.details = emptyList()
        repeat(3) { late.session.progressTick() }
        assertEquals(3, late.host.detailReads); assertNull(late.session.bound)
        late.select("p1", aid, cid)
        late.session.progressTick()
        assertEquals(SponsorVideoId(bvid, cid), late.session.bound)
        // 被挡住（需要解锁）的条目解锁后也要能重新绑定。
        val wall = Harness(); wall.host.details = listOf(StoryDetailSnapshot(aid, cid, bvid, true, false, false, false))
        repeat(2) { wall.session.progressTick() }
        assertNull(wall.session.bound)
        wall.host.details = listOf(StoryDetailSnapshot(aid, cid, bvid, true, true, false, false))
        wall.session.progressTick()
        assertEquals(SponsorVideoId(bvid, cid), wall.session.bound)
    }

    fun itemChangeStopsTheOldControllerAndTheNextItemGetsItsOwnQuery() {
        val h = Harness(); h.nativeSeek()
        h.session.progressTick(); h.deliver(range)
        assertEquals(1, h.lookups.size)
        h.session.itemChanged()
        assertNull(h.session.bound); assertEquals(SponsorPlaybackController.Hint.NONE, h.hint)
        // 滑动中游标已变、媒体尚未装载：没有进度回调就不绑定。
        h.select("p2", aid + 7, cid + 7)
        h.session.stateChanged()
        assertEquals(1, h.lookups.size)
        h.session.progressTick()
        assertEquals(2, h.lookups.size)
        assertEquals(SponsorVideoId(SponsorVideoId.bvidFromAvid(aid + 7)!!, cid + 7), h.session.bound)
    }

    fun leavingTheForegroundCancelsAndReturningRebindsWithoutANewBudgetCharge() {
        val h = Harness(budgetLimit = 1)
        h.session.progressTick()
        h.host.resumed = false; h.session.resumed()
        assertNull(h.session.bound); assertEquals(1, h.cancelled)
        h.host.resumed = true; h.session.resumed()
        assertEquals(SponsorVideoId(bvid, cid), h.session.bound)
        assertEquals(2, h.lookups.size)
        assertFalse("query.budget" in h.diagnostics)
    }

    fun lookupBudgetStopsAFlickBurstBeforeItReachesTheNetwork() {
        val h = Harness(budgetLimit = 1)
        h.session.progressTick()
        h.session.itemChanged()
        h.select("p2", aid + 7, cid + 7)
        h.session.progressTick()
        assertEquals(1, h.lookups.size); assertTrue("query.budget" in h.diagnostics)
        assertEquals(SponsorPlaybackController.Hint.NONE, h.hint)
    }

    fun swapOfTheLoadedMediaInvalidatesTheBoundItemBeforeAnyLateSkip() {
        val h = Harness(); h.nativeSeek()
        h.session.progressTick()
        h.host.loaded = "p2"
        h.session.progressTick(); h.deliver(range)
        assertTrue(h.host.seeks.isEmpty()); assertNull(h.session.bound)
    }

    fun anIntervalEndingAtTheTailOnlyOffersTheManualSkip() {
        val h = Harness(); h.nativeSeek()
        h.session.progressTick()
        h.host.position = 98_000
        h.deliver(SponsorSegment("tail", 90_000, 99_000, 100_000))
        assertTrue(h.host.seeks.isEmpty())
        assertEquals(SponsorPlaybackController.Hint.SKIP, h.hint)
        h.session.skip()
        assertEquals(listOf(99_000), h.host.seeks)
    }

    fun clipOrLogicalTimelinesAreRefusedBecauseTheirPositionsAreNotTheRealOnes() {
        val h = Harness()
        h.host.realDurationOverride = 30_000
        h.session.progressTick()
        assertTrue(h.lookups.isEmpty()); assertTrue("bind.denied.clip" in h.diagnostics)
        h.host.realDurationOverride = null; h.host.realPositionOverride = 2_000
        h.session.progressTick()
        assertTrue(h.lookups.isEmpty())
    }

    fun manualModeShowsTheHintAndWaitsForTheUser() {
        val h = Harness(automatic = false); h.nativeSeek()
        h.session.progressTick(); h.deliver(range)
        assertTrue(h.host.seeks.isEmpty())
        assertEquals(SponsorPlaybackController.Hint.SKIP, h.hint)
        h.session.skip()
        assertEquals(listOf(20_000), h.host.seeks)
    }

    fun aUserSeekIntoTheRangeBlocksItInsteadOfBeingOverridden() {
        val h = Harness(); h.nativeSeek()
        h.session.progressTick()
        h.session.invalidateExternalSeek(15_000); h.session.seekStarted(15_000)
        h.host.position = 15_000; h.session.seekCompleted()
        h.deliver(range)
        assertTrue(h.host.seeks.isEmpty())
    }

    fun stoppingOrADeadPlayerEndsTheSessionAndIgnoresLaterSignals() {
        val h = Harness()
        h.session.progressTick()
        h.host.alive = false
        h.session.progressTick()
        assertNull(h.session.bound); assertEquals(1, h.cancelled)
        h.host.alive = true
        h.session.progressTick(); h.session.stateChanged(); h.session.resumed()
        assertEquals(1, h.lookups.size)
        h.session.stop()
    }

    fun verifyAllCases() {
        fun runIsolated(block: (StorySponsorSessionCases) -> Unit) {
            val s = StorySponsorSessionCases()
            block(s)
        }
        runIsolated { s -> s.nothingIsQueriedUntilTheLoadedItemReportsItsFirstProgressTick() }
        runIsolated { s -> s.automaticSkipMarksTheRequestExecutingBeforeTheSynchronousCoreSeek() }
        runIsolated { s -> s.cursorAheadOfLoadedMediaNeverQueriesAndAdsAreExcluded() }
        runIsolated { s -> s.anItemThatIsPermanentlyNotApplicableIsJudgedOnceInsteadOfOnEveryTick() }
        runIsolated { s -> s.anItemThatIsOnlyNotReadyYetIsJudgedAgainOnEveryTick() }
        runIsolated { s -> s.itemChangeStopsTheOldControllerAndTheNextItemGetsItsOwnQuery() }
        runIsolated { s -> s.leavingTheForegroundCancelsAndReturningRebindsWithoutANewBudgetCharge() }
        runIsolated { s -> s.lookupBudgetStopsAFlickBurstBeforeItReachesTheNetwork() }
        runIsolated { s -> s.swapOfTheLoadedMediaInvalidatesTheBoundItemBeforeAnyLateSkip() }
        runIsolated { s -> s.anIntervalEndingAtTheTailOnlyOffersTheManualSkip() }
        runIsolated { s -> s.clipOrLogicalTimelinesAreRefusedBecauseTheirPositionsAreNotTheRealOnes() }
        runIsolated { s -> s.manualModeShowsTheHintAndWaitsForTheUser() }
        runIsolated { s -> s.aUserSeekIntoTheRangeBlocksItInsteadOfBeingOverridden() }
        runIsolated { s -> s.stoppingOrADeadPlayerEndsTheSessionAndIgnoresLaterSignals() }
    }
}
