package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import org.junit.Assert.*
import org.junit.Test

class StorySponsorSessionTest {
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

    @Test fun nothingIsQueriedUntilTheLoadedItemReportsItsFirstProgressTick() {
        val h = Harness()
        h.session.resumed(); h.session.stateChanged()
        assertTrue(h.lookups.isEmpty()); assertNull(h.session.bound)
        h.session.progressTick()
        assertEquals(listOf(SponsorVideoId(bvid, cid)), h.lookups)
        assertEquals(SponsorVideoId(bvid, cid), h.session.bound)
    }

    @Test fun automaticSkipMarksTheRequestExecutingBeforeTheSynchronousCoreSeek() {
        val h = Harness(); h.nativeSeek()
        h.session.progressTick(); h.deliver(range)
        assertEquals(listOf(20_000), h.host.seeks)
        assertEquals(1, h.applied)
        assertEquals(SponsorPlaybackController.Hint.UNDO, h.hint)
    }

    @Test fun cursorAheadOfLoadedMediaNeverQueriesAndAdsAreExcluded() {
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

    @Test fun anItemThatIsPermanentlyNotApplicableIsJudgedOnceInsteadOfOnEveryTick() {
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

    @Test fun anItemThatIsOnlyNotReadyYetIsJudgedAgainOnEveryTick() {
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

    @Test fun itemChangeStopsTheOldControllerAndTheNextItemGetsItsOwnQuery() {
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

    @Test fun leavingTheForegroundCancelsAndReturningRebindsWithoutANewBudgetCharge() {
        val h = Harness(budgetLimit = 1)
        h.session.progressTick()
        h.host.resumed = false; h.session.resumed()
        assertNull(h.session.bound); assertEquals(1, h.cancelled)
        h.host.resumed = true; h.session.resumed()
        assertEquals(SponsorVideoId(bvid, cid), h.session.bound)
        assertEquals(2, h.lookups.size)
        assertFalse("query.budget" in h.diagnostics)
    }

    @Test fun lookupBudgetStopsAFlickBurstBeforeItReachesTheNetwork() {
        val h = Harness(budgetLimit = 1)
        h.session.progressTick()
        h.session.itemChanged()
        h.select("p2", aid + 7, cid + 7)
        h.session.progressTick()
        assertEquals(1, h.lookups.size); assertTrue("query.budget" in h.diagnostics)
        assertEquals(SponsorPlaybackController.Hint.NONE, h.hint)
    }

    @Test fun swapOfTheLoadedMediaInvalidatesTheBoundItemBeforeAnyLateSkip() {
        val h = Harness(); h.nativeSeek()
        h.session.progressTick()
        h.host.loaded = "p2"
        h.session.progressTick(); h.deliver(range)
        assertTrue(h.host.seeks.isEmpty()); assertNull(h.session.bound)
    }

    @Test fun anIntervalEndingAtTheTailOnlyOffersTheManualSkip() {
        val h = Harness(); h.nativeSeek()
        h.session.progressTick()
        h.host.position = 98_000
        h.deliver(SponsorSegment("tail", 90_000, 99_000, 100_000))
        assertTrue(h.host.seeks.isEmpty())
        assertEquals(SponsorPlaybackController.Hint.SKIP, h.hint)
        h.session.skip()
        assertEquals(listOf(99_000), h.host.seeks)
    }

    @Test fun clipOrLogicalTimelinesAreRefusedBecauseTheirPositionsAreNotTheRealOnes() {
        val h = Harness()
        h.host.realDurationOverride = 30_000
        h.session.progressTick()
        assertTrue(h.lookups.isEmpty()); assertTrue("bind.denied.clip" in h.diagnostics)
        h.host.realDurationOverride = null; h.host.realPositionOverride = 2_000
        h.session.progressTick()
        assertTrue(h.lookups.isEmpty())
    }

    @Test fun manualModeShowsTheHintAndWaitsForTheUser() {
        val h = Harness(automatic = false); h.nativeSeek()
        h.session.progressTick(); h.deliver(range)
        assertTrue(h.host.seeks.isEmpty())
        assertEquals(SponsorPlaybackController.Hint.SKIP, h.hint)
        h.session.skip()
        assertEquals(listOf(20_000), h.host.seeks)
    }

    @Test fun aUserSeekIntoTheRangeBlocksItInsteadOfBeingOverridden() {
        val h = Harness(); h.nativeSeek()
        h.session.progressTick()
        h.session.invalidateExternalSeek(15_000); h.session.seekStarted(15_000)
        h.host.position = 15_000; h.session.seekCompleted()
        h.deliver(range)
        assertTrue(h.host.seeks.isEmpty())
    }

    @Test fun stoppingOrADeadPlayerEndsTheSessionAndIgnoresLaterSignals() {
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
}
