package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPagePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPagePolicy.Kind
import org.junit.Assert.*
import org.junit.Test

class HostBackgroundPagePolicyTest {
    @Test fun mediaDetailsKeepTheirWindowButStillAcceptContentBackgrounds() {
        val detail = "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity"
        assertTrue(HostBackgroundPagePolicy.mediaDetailActivity(listOf("host.DetailVariant", detail, "android.app.Activity")))
        assertTrue(HostBackgroundPagePolicy.nativeActivity(listOf(detail)))
        assertFalse(HostBackgroundPagePolicy.mediaDetailActivity(listOf("tv.danmaku.bili.ui.main2.MainActivityV2")))
        assertFalse(HostBackgroundPagePolicy.mediaDetailActivity(listOf("another.app.UnitedBizDetailsActivity")))
    }

    @Test fun pagesAreRecognizedWithoutDependingOnVideoCards() {
        assertEquals(Kind.COMPOSE, HostBackgroundPagePolicy.kind("com.bilibili.ogv.operation2.BangumiHomeFlowFragmentV5"))
        assertEquals(Kind.COMPOSE, HostBackgroundPagePolicy.kind("com.bilibili.ogv.misc.timeline.OgvTimelineFragment"))
        assertEquals(Kind.PAGE, HostBackgroundPagePolicy.kind("com.bilibili.ogv.operation.entrance.indexpage.BangumiCategoryIndexTabFragment"))
        assertEquals(Kind.PAGE, HostBackgroundPagePolicy.kind("com.bilibili.ogv.operation.entrance.rank.BangumiRankFragment"))
        assertEquals(Kind.PAGE, HostBackgroundPagePolicy.kind("com.bilibili.ogv.misc.editorrecommend.BangumiEditorRecommendFragment"))
        assertEquals(Kind.PAGE, HostBackgroundPagePolicy.kind("com.bilibili.ogv.misc.seasonlist.BangumiSeasonListFragment"))
        assertEquals(Kind.PAGE, HostBackgroundPagePolicy.kind("com.bilibili.ogv.operation.entrance.standalone.BangumiBaseOperationSingleFragment"))
        assertEquals(Kind.PAGE, HostBackgroundPagePolicy.kind("tv.danmaku.bili.ui.rank.RankVideoListFragment"))
        assertEquals(Kind.DYNAMIC, HostBackgroundPagePolicy.kind("com.bilibili.bplus.followinglist.home.mediator.MediatorFragment"))
        assertEquals(Kind.DYNAMIC, HostBackgroundPagePolicy.kind("com.bilibili.bplus.followinglist.quick.consume.VideoQuickConsumeFragment"))
        assertEquals(Kind.LIVE, HostBackgroundPagePolicy.kind("com.bilibili.bililive.biz.livehome.indexfeed.ui.LiveHomeFragment"))
        assertEquals(Kind.FEED, HostBackgroundPagePolicy.kind("com.bilibili.pegasus.PegasusFragment"))
        for (name in listOf("com.bilibili.ogv.operation2.PlayerFragment", "com.bilibili.bililive.room.LiveRoomFragment", "com.bilibili.ogv.misc.follow.BangumiFollowDialogFragment", "another.app.BangumiHomeFragment"))
            assertNull(HostBackgroundPagePolicy.kind(name))
    }

    @Test fun artworkAndTranslucentSurfacesAreNotPlainPageFills() {
        assertTrue(HostBackgroundPagePolicy.neutral(0xff17181a.toInt()))
        assertTrue(HostBackgroundPagePolicy.neutral(0xfffafafa.toInt()))
        assertFalse(HostBackgroundPagePolicy.neutral(0xffff6699.toInt()))
        assertFalse(HostBackgroundPagePolicy.neutral(0x88171717.toInt()))
        assertFalse(HostBackgroundPagePolicy.neutral(0x00000000))
    }

    @Test fun nativeWindowsCoverUnlistedPagesAndKeepImmersiveWindows() {
        for (name in listOf("com.bilibili.app.history.HistoryActivity", "tv.danmaku.bili.ui.main2.MainActivityV2",
                "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity", "com.bilibili.app.comm.list.FavoriteActivity",
                "com.bilibili.lib.ui.BaseAppCompatActivity", "kntr.common.compose.launcher.ComposeContainerActivity"))
            assertTrue(HostBackgroundPagePolicy.nativeActivity(listOf(name, "android.app.Activity")))
        for (name in listOf("com.bilibili.bililive.room.ui.LiveRoomActivity", "com.bilibili.player.PlayerActivity",
                "com.bilibili.bplus.baseplus.activity.ImagesViewerActivity"))
            assertFalse(HostBackgroundPagePolicy.nativeActivity(listOf(name, "android.app.Activity")))
        assertFalse(HostBackgroundPagePolicy.nativeActivity(listOf("another.app.MainActivity"), "another.app"))
        assertFalse(HostBackgroundPagePolicy.nativeActivity(listOf("com.bilibili.player.FullscreenActivity", "com.bilibili.lib.ui.BaseAppCompatActivity")))
        for (name in listOf("CommentV3MainFragment", "CommentV3DetailFragment", "CommentV3DialogFragment"))
            assertEquals(Kind.COMMENT, HostBackgroundPagePolicy.kind("com.bilibili.app.comment3.ui.$name"))
    }

    @Test fun composeButtonsAndInvalidGeometryKeepTheirBackgrounds() {
        assertTrue(HostBackgroundPagePolicy.pageRectangle(1080f, 2300f, 1080))
        assertTrue(HostBackgroundPagePolicy.pageRectangle(1000f, 80f, 1080))
        assertFalse(HostBackgroundPagePolicy.pageRectangle(140f, 60f, 1080))
        assertFalse(HostBackgroundPagePolicy.pageRectangle(Float.NaN, 60f, 1080))
        assertFalse(HostBackgroundPagePolicy.pageRectangle(1080f, Float.POSITIVE_INFINITY, 1080))
        assertFalse(HostBackgroundPagePolicy.pageRectangle(1080f, 0f, 1080))
        assertFalse(HostBackgroundPagePolicy.pageRectangle(1080f, 100f, 0))
    }

    @Test fun nativeComposePanelsKeepBusinessColorsAndThinLines() {
        fun packed(argb: Int) = argb.toLong() shl 32
        assertTrue(HostBackgroundPagePolicy.nativeComposePanel(packed(0xffffffff.toInt()), 211f, 3f))
        assertTrue(HostBackgroundPagePolicy.nativeComposePanel(packed(0xff202123.toInt()), 195f, 3f))
        assertFalse(HostBackgroundPagePolicy.nativeComposePanel(packed(0xffff6699.toInt()), 211f, 3f))
        assertFalse(HostBackgroundPagePolicy.nativeComposePanel(packed(0x88ffffff.toInt()), 211f, 3f))
        assertFalse(HostBackgroundPagePolicy.nativeComposePanel(packed(0xffffffff.toInt()), 3f, 3f))
        assertFalse(HostBackgroundPagePolicy.nativeComposePanel(packed(0xffffffff.toInt()) or 1, 211f, 3f))
        assertFalse(HostBackgroundPagePolicy.nativeComposePanel(packed(0xffffffff.toInt()), Float.NaN, 3f))
    }
}
