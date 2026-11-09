package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import org.junit.Assert.*
import org.junit.Test

class StorySponsorGateTest {
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

    @Test fun ordinaryUgcWithMatchingLoadedMediaAndPagerItemIsAllowed() {
        val verdict = StorySponsorGate.evaluate(playable(), "p1", listOf(detail()))
        assertEquals(StorySponsorVerdict.Allow(SponsorVideoId(bvid, cid), "p1"), verdict)
    }

    @Test fun emptyBvidOnTheDetailFallsBackToTheIdDerivedFromAid() {
        assertTrue(StorySponsorGate.evaluate(playable(), "p1", listOf(detail(bvid = ""))) is StorySponsorVerdict.Allow)
    }

    @Test fun cursorAheadOfTheLoadedMediaIsRejected() {
        assertEquals("cursor-mismatch", deny(playable(id = "next"), "p1", listOf(detail())))
        assertEquals("cursor-mismatch", deny(playable(), null, listOf(detail())))
        assertEquals("cursor-mismatch", deny(playable(id = ""), "", listOf(detail())))
        assertEquals("no-playable", deny(null, "p1", listOf(detail())))
    }

    @Test fun playableLevelAdmissionAloneNeverEnoughSoMissingDetailFailsClosed() {
        assertEquals("no-detail", deny(playable(), "p1", emptyList()))
        // 另一页的详情不能顶替当前条目。
        assertEquals("no-detail", deny(playable(), "p1", listOf(detail(aid = aid + 1))))
        assertEquals("no-detail", deny(playable(), "p1", listOf(detail(cid = cid + 1))))
    }

    @Test fun nonUgcPlayableAndInvalidIdentityAreRejectedBeforeAnyDetailLookup() {
        assertEquals("non-ugc", deny(playable(vupload = false), "p1", listOf(detail())))
        assertEquals("non-ugc", deny(playable(ugc = false), "p1", listOf(detail())))
        assertEquals("invalid-identity", deny(playable(aid = 0), "p1", listOf(detail())))
        assertEquals("invalid-identity", deny(playable(cid = 0), "p1", listOf(detail())))
    }

    @Test fun adsGameCardsAndPreviewsThatShareTheUgcPlayableShapeAreRejectedByTheDetail() {
        assertEquals("ad", deny(playable(), "p1", listOf(detail(ad = true))))
        // 自然广告：goto 仍是 vertical_av，但带广告信息。
        assertEquals("ad", deny(playable(), "p1", listOf(detail(adInfo = true))))
        // vertical_ad_av / vertical_game / vertical_preview_av：goto 不是 vertical_av。
        assertEquals("non-ugc-detail", deny(playable(), "p1", listOf(detail(ugc = false))))
        // blockingWall 非空：条目被挡住，不是可播放视频；解锁后可能变好，所以单独成因。
        assertEquals("blocked-detail", deny(playable(), "p1", listOf(detail(playable = false))))
    }

    @Test fun denialsThatAreAPropertyOfTheItemAreFinalAndTheOnesThatAreJustNotReadyAreNot() {
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

    @Test fun everyPagerItemMatchingTheCursorMustPassTheGate() {
        assertEquals("ad", deny(playable(), "p1", listOf(detail(), detail(ad = true))))
        assertTrue(StorySponsorGate.evaluate(playable(), "p1",
            listOf(detail(), detail(aid = aid + 1, ad = true))) is StorySponsorVerdict.Allow)
    }

    @Test fun aBvidThatDisagreesWithTheAidIsRejected() {
        assertEquals("bvid-mismatch", deny(playable(), "p1", listOf(detail(bvid = "BV14741127BN"))))
        assertEquals("invalid-aid", deny(playable(aid = Long.MAX_VALUE), "p1", listOf(detail(aid = Long.MAX_VALUE))))
    }

    @Test fun lookupBudgetAdmitsALimitedNumberPerWindowAndRecovers() {
        var now = 0L
        val budget = StoryLookupBudget(3, 60_000) { now }
        assertTrue(budget.tryAcquire()); assertTrue(budget.tryAcquire()); assertTrue(budget.tryAcquire())
        assertFalse(budget.tryAcquire())
        now = 59_999; assertFalse(budget.tryAcquire())
        now = 60_000
        repeat(3) { assertTrue(budget.tryAcquire()) }
        assertFalse(budget.tryAcquire())
    }
}
