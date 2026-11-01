package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.StoryDetailSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.StoryPlayableIdentity
import com.bilibili.video.story.StoryDetail
import com.bilibili.video.story.player.StoryPagerPlayer
import com.bilibili.video.story.player.StoryPlayer
import org.junit.Assert.*
import org.junit.Test
import tv.danmaku.biliplayerv2.service.IPlayerCoreService
import tv.danmaku.biliplayerv2.service.Video
import tv.danmaku.videoplayer.coreV2.MediaItem
import tv.danmaku.videoplayer.coreV2.VideoBizType

class SponsorStoryAccessTest {
    private val loader = javaClass.classLoader!!

    private class Params(private val identity: String, display: Video.DisplayParams, kind: VideoBizType = VideoBizType.UGC) :
        Video.PlayableParams(display.getFrom(), kind) {
        private val details = display
        override fun getDisplayParams() = details
        override fun id() = identity
    }

    private fun detail(aid: Long, cid: Long, bvid: String = "", ugc: Boolean = true, playable: Boolean = true,
        ad: Boolean = false, adInfo: Any? = null) = StoryDetail().also { it.setIdentity(aid, cid, bvid, ugc, playable, ad, adInfo) }

    @Test fun everyStoryRoleResolvesDirectlyFromStableNamesAndTypes() {
        val access = requireNotNull(SponsorStoryAccess.resolve(loader))
        assertEquals(SponsorStoryAccess.PLAYER, access.onCreate.declaringClass.name)
        assertEquals("onCreate", access.onCreate.name)
        assertEquals("release", access.release.name)
        assertEquals("onDestroy", access.onDestroy.name)
        assertEquals(listOf(String::class.java), access.pagerConstructor.parameterTypes.toList())
        assertEquals(SponsorStoryAccess.ACTIVITY, access.activityClass.name)
        assertEquals(SponsorCoreApi.CORE_CLASS, access.core.core.name)
    }

    @Test fun cursorPlayableIsReadWithItsIdentityAndAdmissionFlags() {
        val access = requireNotNull(SponsorStoryAccess.resolve(loader))
        val owner = StoryPlayer()
        assertNull(access.playable(owner))
        owner.source.values += Params("p1", Video.DisplayParams(7, 9))
        assertEquals(StoryPlayableIdentity("p1", 7, 9, vupload = true, ugc = true), access.playable(owner))
        owner.source.values[0] = Params("p2", Video.DisplayParams(7, 9, "other"), VideoBizType.PGC)
        assertEquals(StoryPlayableIdentity("p2", 7, 9, vupload = false, ugc = false), access.playable(owner))
        owner.currentFailure = IllegalStateException("host not ready")
        assertNull(access.playable(owner))
    }

    @Test fun loadedMediaIdAndNumbersComeFromTheRawCore() {
        val access = requireNotNull(SponsorStoryAccess.resolve(loader))
        val core = object : IPlayerCoreService {
            override fun getCurrentMediaItem() = MediaItem("p1")
            override fun getCurrentPosition() = 1_234
            override fun getRealDuration() = 55_000
        }
        val owner = StoryPlayer().also { it.core = core }
        assertSame(core, access.coreOf(owner))
        assertEquals("p1", access.core.loadedId(core))
        assertEquals(1_234, access.core.int(access.core.position, core))
        assertEquals(55_000, access.core.int(access.core.realDuration, core))
        val empty = object : IPlayerCoreService {}
        assertNull(access.core.loadedId(empty))
    }

    @Test fun pagerItemSnapshotCarriesEveryAdmissionFieldAndOnlyPagersOfThePlayerCount() {
        val access = requireNotNull(SponsorStoryAccess.resolve(loader))
        val owner = StoryPlayer()
        val pager = StoryPagerPlayer("video").also { it.bind(owner, detail(7, 9, "BV1xx", adInfo = Any())) }
        assertTrue(access.isPagerOf(pager, owner))
        assertFalse(access.isPagerOf(StoryPagerPlayer("space").also { it.bind(StoryPlayer(), null) }, owner))
        assertEquals(StoryDetailSnapshot(7, 9, "BV1xx", ugc = true, playable = true, ad = false, hasAdInfo = true),
            access.detail(pager))
        pager.bind(owner, detail(8, 10, ugc = false, playable = false, ad = true))
        assertEquals(StoryDetailSnapshot(8, 10, "", ugc = false, playable = false, ad = true, hasAdInfo = false),
            access.detail(pager))
    }

    @Test fun placeholderOrUnreadablePagerPagesProduceNoSnapshot() {
        val access = requireNotNull(SponsorStoryAccess.resolve(loader))
        assertNull(access.detail(StoryPagerPlayer("video").also { it.bind(StoryPlayer(), null) }))
        assertNull(access.detail(Any()))
    }

    @Test fun withoutAnyPageOrLifecycleSourceNoActivityIsInvented() {
        val access = requireNotNull(SponsorStoryAccess.resolve(loader))
        assertNull(access.activityOf(StoryPlayer(), null))
        assertNull(access.activityOf(StoryPlayer(), Any()))
    }

    @Test fun missingAnyRequiredHostBoundaryDisablesTheFeature() {
        val required = listOf(SponsorStoryAccess.PLAYER, SponsorStoryAccess.PAGER, SponsorStoryAccess.DETAIL,
            SponsorStoryAccess.ACTIVITY, SponsorStoryAccess.PLAYABLE, SponsorStoryAccess.DISPLAY,
            SponsorStoryAccess.BIZ_TYPE, SponsorCoreApi.CORE_CLASS, SponsorCoreApi.MEDIA_ITEM_CLASS,
            "tv.danmaku.biliplayerv2.service.PlayerProgressObserver",
            "tv.danmaku.biliplayerv2.service.PlayerSeekObserver",
            "tv.danmaku.biliplayerv2.service.PlayerStateObserver",
            "tv.danmaku.biliplayerv2.service.IPlayerReleaseObserver", "androidx.lifecycle.LifecycleOwner")
        for (missing in required) {
            val incomplete = object : ClassLoader(loader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (name == missing) throw ClassNotFoundException(name)
                    return super.loadClass(name, resolve)
                }
            }
            assertNull(missing, SponsorStoryAccess.resolve(incomplete))
        }
    }
}
