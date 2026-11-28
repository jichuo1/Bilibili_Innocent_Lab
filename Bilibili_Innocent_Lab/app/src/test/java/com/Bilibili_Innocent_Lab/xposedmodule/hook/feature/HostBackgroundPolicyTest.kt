package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPagePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPagePolicy.Kind
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundTheme
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundWorld
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostBottomBarScrubRelease
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostBottomBarTabSlots
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HomeChromeSemanticsPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostStatusBarContrast
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.overlays.HostCopyBubblePlacement
import com.bilibili.lib.ui.util.MultipleThemeUtils
import org.junit.Assert.*
import org.junit.Test

class HostBackgroundPolicyTest {
    fun commentPushSheetsAreNotCommentContentPages() {
        for (name in listOf(
            "com.bilibili.app.comment3.push.CommentPushDialogFragment",
            "com.bilibili.app.comment3.push.CommentPushContainerFragment",
            "com.bilibili.app.comment3.push.ui.moremenu.CommentPushMoreMenuDialogFragment"
        )) assertNull(HostBackgroundPagePolicy.kind(name))
        for (name in listOf(
            "com.bilibili.app.comment3.CommentV3Fragment",
            "com.bilibili.app.comment3.ui.CommentV3MainFragment",
            "com.bilibili.app.comment3.ui.CommentV3DetailFragment",
            "com.bilibili.app.comment3.ui.CommentV3DialogFragment"
        )) assertEquals(Kind.COMMENT, HostBackgroundPagePolicy.kind(name))
    }

    fun embeddedCommentImageViewersKeepTheirMediaComposition() {
        for (name in listOf(
            "com.bilibili.app.comment3.ui.widget.imagecardviewer.CommentImageCardViewerDialogFragment",
            "com.bilibili.app.comment3.ui.widget.imagecardviewer.CommentImageCardFragment",
            "com.bilibili.app.comment3.ui.widget.imageviewer.CommentImageFragment",
            "com.bilibili.app.comment3.ui.widget.imagecardviewer.ui.widget.TouchDispatchObservableFrameLayout",
            "com.bilibili.lib.imageviewer.widget.MediaViewPager"
        )) {
            assertTrue(HostBackgroundPagePolicy.imageViewer(name))
            assertNull(HostBackgroundPagePolicy.kind(name))
        }
        assertFalse(HostBackgroundPagePolicy.imageViewer("com.bilibili.app.comment3.ui.widget.CommentPictureListWidget"))
        assertFalse(HostBackgroundPagePolicy.imageViewer("another.app.ui.widget.imageviewer.CommentImageFragment"))
        assertEquals(Kind.COMMENT, HostBackgroundPagePolicy.kind("com.bilibili.app.comment3.ui.CommentV3DetailFragment"))
    }

    fun storyWindowsAndTheirSubclassesKeepNativeMediaComposition() {
        for (name in listOf("StoryTransparentActivity", "StoryVideoActivity", "StoryActivity")) {
            val activity = "com.bilibili.video.story.$name"
            assertFalse(HostBackgroundPagePolicy.nativeActivity(listOf(activity, "android.app.Activity")))
            assertFalse(HostBackgroundPagePolicy.nativeActivity(listOf("host.StoryVariant", activity, "android.app.Activity")))
            assertFalse(HostBackgroundPagePolicy.mediaDetailActivity(listOf(activity)))
        }
        assertTrue(HostBackgroundPagePolicy.nativeActivity(listOf("com.bilibili.app.history.HistoryActivity")))
    }

    fun mediaDetailsKeepTheirWindowButStillAcceptContentBackgrounds() {
        val detail = "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity"
        assertTrue(HostBackgroundPagePolicy.mediaDetailActivity(listOf("host.DetailVariant", detail, "android.app.Activity")))
        assertTrue(HostBackgroundPagePolicy.nativeActivity(listOf(detail)))
        val playlist = "com.bilibili.ship.theseus.playlist.UnitedPlaylistActivity"
        assertTrue(HostBackgroundPagePolicy.mediaDetailActivity(listOf("host.PlaylistVariant", playlist, "android.app.Activity")))
        assertTrue(HostBackgroundPagePolicy.nativeActivity(listOf(playlist)))
        assertFalse(HostBackgroundPagePolicy.mediaDetailActivity(listOf("another.app.UnitedPlaylistActivity")))
        assertFalse(HostBackgroundPagePolicy.mediaDetailActivity(listOf("tv.danmaku.bili.ui.main2.MainActivityV2")))
        assertFalse(HostBackgroundPagePolicy.mediaDetailActivity(listOf("another.app.UnitedBizDetailsActivity")))
    }

    fun pagesAreRecognizedWithoutDependingOnVideoCards() {
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
        assertEquals(Kind.OPUS, HostBackgroundPagePolicy.kind("com.bilibili.bplus.followinglist.page.opus.OpusDetailFragment"))
        assertEquals(Kind.LIVE, HostBackgroundPagePolicy.kind("com.bilibili.bililive.biz.livehome.indexfeed.ui.LiveHomeFragment"))
        assertEquals(Kind.FEED, HostBackgroundPagePolicy.kind("com.bilibili.pegasus.PegasusFragment"))
        for (name in listOf("com.bilibili.ogv.operation2.PlayerFragment", "com.bilibili.bililive.room.LiveRoomFragment", "com.bilibili.ogv.misc.follow.BangumiFollowDialogFragment", "another.app.BangumiHomeFragment"))
            assertNull(HostBackgroundPagePolicy.kind(name))
    }

    fun artworkAndTranslucentSurfacesAreNotPlainPageFills() {
        assertTrue(HostBackgroundPagePolicy.neutral(0xff17181a.toInt()))
        assertTrue(HostBackgroundPagePolicy.neutral(0xfffafafa.toInt()))
        assertFalse(HostBackgroundPagePolicy.neutral(0xffff6699.toInt()))
        assertFalse(HostBackgroundPagePolicy.neutral(0x88171717.toInt()))
        assertFalse(HostBackgroundPagePolicy.neutral(0x00000000))
    }

    fun nativeWindowsCoverUnlistedPagesAndKeepImmersiveWindows() {
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

    fun composeButtonsAndInvalidGeometryKeepTheirBackgrounds() {
        assertTrue(HostBackgroundPagePolicy.pageRectangle(1080f, 2300f, 1080))
        assertTrue(HostBackgroundPagePolicy.pageRectangle(1080f, 222f, 1080))
        assertTrue(HostBackgroundPagePolicy.pageRectangle(1000f, 80f, 1080))
        assertFalse(HostBackgroundPagePolicy.pageRectangle(140f, 1600f, 1080))
        assertFalse(HostBackgroundPagePolicy.pageRectangle(Float.NaN, 1600f, 1080))
        assertFalse(HostBackgroundPagePolicy.pageRectangle(1080f, Float.POSITIVE_INFINITY, 1080))
        assertFalse(HostBackgroundPagePolicy.pageRectangle(1080f, 1600f, 0))
    }

    fun fixedComposeEdgeChromeIsSeparatedFromScrollingPanels() {
        val density = 2.75f
        assertTrue(HostBackgroundPagePolicy.fixedEdgeChrome(222f, 2340, density, 0f))
        assertTrue(HostBackgroundPagePolicy.fixedEdgeChrome(122f, 2340, density, 2218f))
        assertFalse("A scrolling comment panel must not become fixed chrome", HostBackgroundPagePolicy.fixedEdgeChrome(893f, 2340, density, 1f))
        assertFalse(HostBackgroundPagePolicy.fixedEdgeChrome(1886f, 2340, density, 0f))
        assertFalse(HostBackgroundPagePolicy.fixedEdgeChrome(277f, 2340, density, 0f))
        assertFalse(HostBackgroundPagePolicy.fixedEdgeChrome(80f, 2340, density, 0f))
        assertFalse(HostBackgroundPagePolicy.fixedEdgeChrome(122f, 2340, density, 1000f))
        assertFalse(HostBackgroundPagePolicy.fixedEdgeChrome(Float.NaN, 2340, density, 0f))
    }

    fun wallpaperPagesClearAnyVisibleSystemBarTint() {
        assertTrue(HostBackgroundPagePolicy.systemBarNeedsTransparency(0xff06083d.toInt()))
        assertTrue(HostBackgroundPagePolicy.systemBarNeedsTransparency(0xffffffff.toInt()))
        assertTrue(HostBackgroundPagePolicy.systemBarNeedsTransparency(0x80ffffff.toInt()))
        assertFalse(HostBackgroundPagePolicy.systemBarNeedsTransparency(0x00ffffff))
        assertFalse(HostBackgroundPagePolicy.systemBarNeedsTransparency(0))
    }

    fun nativeComposePanelsKeepBusinessColorsAndThinLines() {
        fun packed(argb: Int) = argb.toLong() shl 32
        assertTrue(HostBackgroundPagePolicy.nativeComposePanel(packed(0xffffffff.toInt()), 211f, 3f))
        assertTrue(HostBackgroundPagePolicy.nativeComposePanel(packed(0xff202123.toInt()), 195f, 3f))
        assertFalse(HostBackgroundPagePolicy.nativeComposePanel(packed(0xffff6699.toInt()), 211f, 3f))
        assertFalse(HostBackgroundPagePolicy.nativeComposePanel(packed(0x88ffffff.toInt()), 211f, 3f))
        assertFalse(HostBackgroundPagePolicy.nativeComposePanel(packed(0xffffffff.toInt()), 3f, 3f))
        assertFalse(HostBackgroundPagePolicy.nativeComposePanel(packed(0xffffffff.toInt()) or 1, 211f, 3f))
        assertFalse(HostBackgroundPagePolicy.nativeComposePanel(packed(0xffffffff.toInt()), Float.NaN, 3f))
    }

    @Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostBackgroundPolicyTest) -> Unit) {
            val s = HostBackgroundPolicyTest()
            block(s)
        }
        runIsolated { s -> s.commentPushSheetsAreNotCommentContentPages() }
        runIsolated { s -> s.embeddedCommentImageViewersKeepTheirMediaComposition() }
        runIsolated { s -> s.storyWindowsAndTheirSubclassesKeepNativeMediaComposition() }
        runIsolated { s -> s.mediaDetailsKeepTheirWindowButStillAcceptContentBackgrounds() }
        runIsolated { s -> s.pagesAreRecognizedWithoutDependingOnVideoCards() }
        runIsolated { s -> s.artworkAndTranslucentSurfacesAreNotPlainPageFills() }
        runIsolated { s -> s.nativeWindowsCoverUnlistedPagesAndKeepImmersiveWindows() }
        runIsolated { s -> s.composeButtonsAndInvalidGeometryKeepTheirBackgrounds() }
        runIsolated { s -> s.fixedComposeEdgeChromeIsSeparatedFromScrollingPanels() }
        runIsolated { s -> s.wallpaperPagesClearAnyVisibleSystemBarTint() }
        runIsolated { s -> s.nativeComposePanelsKeepBusinessColorsAndThinLines() }

        HostBackgroundWorldCases().verifyAllCases()
        HostBottomBarScrubReleaseCases().verifyAllCases()
        HostBottomBarTabSlotsCases().verifyAllCases()
        HostChromeInstallerRefreshCases().verifyAllCases()
        HostChromeThemeCases().verifyAllCases()
        HostContentSemanticClassifierCases().verifyAllCases()
        HostCopyBubblePlacementCases().verifyAllCases()
        HostBackgroundThemeCases().verifyAllCases()
        HostStatusBarContrastCases().verifyAllCases()
    }
}

private class HostBackgroundWorldCases {
    fun galaxyCourseIsContinuousAndDoesNotUseTheOldSinePeriod() {
        val oldPeriod = 20 * Math.PI
        for (y in listOf(-50.0, 0.0, 50.0, 12_500_000.0)) {
            assertEquals(HostBackgroundWorld.galaxyCenter(y - .000001), HostBackgroundWorld.galaxyCenter(y + .000001), .0001)
            assertNotEquals(HostBackgroundWorld.galaxyCenter(y), HostBackgroundWorld.galaxyCenter(y + oldPeriod), .0001)
        }
    }
    fun cloudFieldIsContinuousAtPositiveNegativeAndDistantLatticeBoundaries() {
        for (y in listOf(-100.0, 0.0, 100.0, 12_500_000.0)) {
            val before = HostBackgroundWorld.fractal(.37, y - .000001)
            val after = HostBackgroundWorld.fractal(.37, y + .000001)
            assertEquals(before, after, .0001)
            assertTrue(before in 0.0..1.0)
        }
    }

    fun bandsHaveStableDistinctSeedsBeyondIntegerScrollRange() {
        val bands = listOf(-1L, 0L, 1L, 3_000_000_000L, 3_000_000_001L)
        val seeds = bands.map { HostBackgroundWorld.seed(it, HostBackgroundPreset.STARRY) }
        assertEquals(bands.size, seeds.toSet().size)
        assertEquals(seeds, bands.map { HostBackgroundWorld.seed(it, HostBackgroundPreset.STARRY) })
        assertNotEquals(seeds[1], HostBackgroundWorld.seed(0, HostBackgroundPreset.NEBULA))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostBackgroundWorldCases) -> Unit) {
            val s = HostBackgroundWorldCases()
            block(s)
        }
        runIsolated { s -> s.galaxyCourseIsContinuousAndDoesNotUseTheOldSinePeriod() }
        runIsolated { s -> s.cloudFieldIsContinuousAtPositiveNegativeAndDistantLatticeBoundaries() }
        runIsolated { s -> s.bandsHaveStableDistinctSeedsBeyondIntegerScrollRange() }
    }
}

private class HostBottomBarScrubReleaseCases {
    fun scrubLandedBackOnTheCurrentPageIsNotAnOperation() {
        assertNull(HostBottomBarScrubRelease.selectableTarget(0, scrubbed = true, currentPage = 0))
        assertNull(HostBottomBarScrubRelease.selectableTarget(2, scrubbed = true, currentPage = 2))
    }

    fun scrubLandedOnAnotherPageStillSelectsThatPage() {
        assertEquals(2, HostBottomBarScrubRelease.selectableTarget(2, scrubbed = true, currentPage = 0))
        assertEquals(0, HostBottomBarScrubRelease.selectableTarget(0, scrubbed = true, currentPage = 3))
    }

    fun tapAndInertReleasesKeepTheirOriginalSemantics() {
        // 普通点击（非拖动）：点当前 tab 仍是宿主的刷新行为，照旧交回宿主。
        assertEquals(1, HostBottomBarScrubRelease.selectableTarget(1, scrubbed = false, currentPage = 1))
        assertEquals(0, HostBottomBarScrubRelease.selectableTarget(0, scrubbed = false, currentPage = 0))
        // 纵向弹性回弹、拖动取消与未完成手势：本来就带不回落点页。
        assertNull(HostBottomBarScrubRelease.selectableTarget(null, scrubbed = false, currentPage = 1))
        assertNull(HostBottomBarScrubRelease.selectableTarget(null, scrubbed = true, currentPage = 1))
    }

    fun publishReleaseActivatesOnlyTheActionAndKeepsTheOriginalPage() {
        for (originalPage in listOf(0, 4)) {
            val activated = mutableListOf<Int>()
            val selected = HostBottomBarScrubRelease.activateTarget(2, true, originalPage) {
                activated += it
                false // 宿主发布面板打开，但页面没有切换。
            }
            assertEquals(listOf(2), activated)
            assertEquals(originalPage, selected)
            // 回到原页面后的拖动松手不会再次触发首页刷新或“我的”点击。
            HostBottomBarScrubRelease.activateTarget(selected, true, selected) {
                activated += it
                true
            }
            assertEquals(listOf(2), activated)
        }
    }

    fun pageSelectionRequiresTheHostToHandleTheClick() {
        assertEquals(3, HostBottomBarScrubRelease.activateTarget(3, true, 0) { true })
        assertEquals(0, HostBottomBarScrubRelease.activateTarget(3, true, 0) { false })
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostBottomBarScrubReleaseCases) -> Unit) {
            val s = HostBottomBarScrubReleaseCases()
            block(s)
        }
        runIsolated { s -> s.scrubLandedBackOnTheCurrentPageIsNotAnOperation() }
        runIsolated { s -> s.scrubLandedOnAnotherPageStillSelectsThatPage() }
        runIsolated { s -> s.tapAndInertReleasesKeepTheirOriginalSemantics() }
        runIsolated { s -> s.publishReleaseActivatesOnlyTheActionAndKeepsTheOriginalPage() }
        runIsolated { s -> s.pageSelectionRequiresTheHostToHandleTheClick() }
    }
}

private class HostBottomBarTabSlotsCases {
    fun hiddenMiddleTabsDoNotConsumeSlotsOrChangeHostIndices() {
        val slots = HostBottomBarTabSlots()
        assertTrue(slots.update(5) { it !in setOf(1, 3) })
        assertEquals(3, slots.count)
        assertEquals(listOf(0, 2, 4), (0 until slots.count).map(slots::hostIndex))
        assertEquals(2, slots.slotOf(4))
        assertEquals(-1, slots.slotOf(3))
        assertEquals(-1, slots.hostIndex(3))
        repeat(100) { assertFalse(slots.update(5) { it != 1 && it != 3 }) }
    }

    fun rebindingCanHideRestoreAndRemoveTabsWithoutKeepingOldSlots() {
        val slots = HostBottomBarTabSlots()
        slots.update(5) { true }
        assertTrue(slots.update(5) { it != 0 && it != 2 })
        assertEquals(listOf(1, 3, 4), (0 until slots.count).map(slots::hostIndex))
        assertTrue(slots.update(5) { true })
        assertEquals(4, slots.slotOf(4))
        // 漫游提前剔除条目时，宿主自己的索引已经压缩。
        assertTrue(slots.update(3) { true })
        assertEquals(listOf(0, 1, 2), (0 until slots.count).map(slots::hostIndex))
        assertTrue(slots.update(3) { false })
        assertEquals(0, slots.count)
        assertEquals(-1, slots.hostIndex(0))
        assertEquals(-1, slots.slotOf(0))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostBottomBarTabSlotsCases) -> Unit) {
            val s = HostBottomBarTabSlotsCases()
            block(s)
        }
        runIsolated { s -> s.hiddenMiddleTabsDoNotConsumeSlotsOrChangeHostIndices() }
        runIsolated { s -> s.rebindingCanHideRestoreAndRemoveTabsWithoutKeepingOldSlots() }
    }
}

private class HostChromeInstallerRefreshCases {
    private fun point(name: String) = HookPoint("host.Home", name, listOf("int"))

    fun `top refresh only accepts new or changed top points`() {
        val top = point("oldTop")
        val installer = HostTopBarFxFeatureInstaller(true, true, null, chromePoints = mapOf("top" to top))
        assertFalse(installer.updateChromePoints(emptyMap()))
        assertFalse(installer.updateChromePoints(mapOf("top" to top, "bottom" to point("bottom"))))
        assertFalse(installer.updateChromePoints(mapOf("bottom_item" to point("item"))))
        val updated = point("newTop")
        assertTrue(installer.updateChromePoints(mapOf("top" to updated)))
        assertFalse(installer.updateChromePoints(mapOf("top" to updated)))
        val layout = point("independentLayout")
        assertTrue(installer.updateChromePoints(mapOf("top_layout" to layout)))
        assertFalse(installer.updateChromePoints(mapOf("top" to updated, "top_layout" to layout)))
        val backdrop = point("secondRow")
        assertTrue(installer.updateChromePoints(mapOf("top_backdrop" to backdrop)))
        assertFalse(installer.updateChromePoints(mapOf("top" to updated, "top_layout" to layout, "top_backdrop" to backdrop)))
    }
    fun `bottom refresh merges new leaf without forgetting root and ignores top`() {
        val bottom = point("bottom")
        val installer = HostBottomBarFxFeatureInstaller(true, true, null, chromePoints = mapOf("bottom" to bottom))
        assertFalse(installer.updateChromePoints(mapOf("top" to point("top"))))
        val item = point("item")
        assertTrue(installer.updateChromePoints(mapOf("bottom_item" to item)))
        assertFalse(installer.updateChromePoints(mapOf("bottom" to bottom, "bottom_item" to item)))
        val updated = point("updatedItem")
        assertTrue(installer.updateChromePoints(mapOf("bottom_item" to updated)))
        assertFalse(installer.updateChromePoints(mapOf("bottom" to bottom, "bottom_item" to updated)))
        val semantics = HomeChromeSemanticsPoint("host.CanonicalKeys", "description")
        assertTrue(installer.updateChromePoints(emptyMap(), semantics))
        assertFalse(installer.updateChromePoints(mapOf("bottom" to bottom, "bottom_item" to updated), semantics))
        assertFalse(installer.updateChromePoints(emptyMap()))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostChromeInstallerRefreshCases) -> Unit) {
            val s = HostChromeInstallerRefreshCases()
            block(s)
        }
        runIsolated { s -> s.`top refresh only accepts new or changed top points`() }
        runIsolated { s -> s.`bottom refresh merges new leaf without forgetting root and ignores top`() }
    }
}

private class HostChromeThemeCases {
    private val accent = 0xFFFF6699.toInt()

    fun manualHostThemeOverridesOppositeSystemMode() {
        assertTrue(HostChromeColors.resolve(true, false, accent).dark)
        assertFalse(HostChromeColors.resolve(false, true, accent).dark)
    }

    fun unavailableHostApiFallsBackToSystemAndBrandAccent() {
        for (systemDark in listOf(false, true)) {
            val colors = HostChromeColors.resolve(null, systemDark, null)
            assertEquals(systemDark, colors.dark)
            assertEquals(accent, colors.accent)
        }
    }

    fun hostAccentSurvivesDayNightAndNeutralSurfacesFollowHostMode() {
        val light = HostChromeColors.resolve(false, true, accent).palette()
        val dark = HostChromeColors.resolve(true, false, accent).palette()
        assertEquals(accent, light.primary)
        assertEquals(accent, dark.primary)
        assertEquals(0xFFFCFBFE.toInt(), light.surface)
        assertEquals(0xFF24252A.toInt(), dark.surface)
        assertEquals(0xFF101114.toInt(), dark.background)
    }

    fun foregroundOnHostAccentRemainsReadableForLightAndDarkSkins() {
        for (color in listOf(accent, 0xFF151515.toInt(), 0xFFFFDD00.toInt())) {
            val palette = HostChromeColors(false, color).palette()
            assertEquals(
                if (color == 0xFF151515.toInt()) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(),
                palette.onPrimary
            )
        }
    }

    fun reusedThemeValuesAndPalettesMatchTheOriginalResolution() {
        for (host in listOf(true, false, null)) for (system in listOf(true, false)) {
            for (accent in listOf(null, this.accent, 0xFF151515.toInt(), 0xFFFFDD00.toInt())) {
                for (garb in listOf(null, this.accent, 0xFF8A8A8A.toInt())) {
                    val base = HostChromeColors(host ?: system, accent ?: this.accent)
                    val expected = garb?.let { base.copy(accent = it, garbAccent = it) } ?: base
                    val actual = HostChromeColors.resolve(host, system, accent, garb)
                    assertEquals(expected, actual)
                    assertEquals(expected.palette(), actual.palette())
                    assertSame(actual, HostChromeColors.resolve(host, system, accent, garb, actual))
                }
            }
        }
    }

    fun stableFramesReuseColorsWhileEveryVisualInputChangeTakesEffectImmediately() {
        val original = HostChromeColors.resolve(false, false, accent)
        val palette = original.palette()
        repeat(1_000) {
            val next = HostChromeColors.resolve(false, false, accent, previous = original)
            assertSame(original, next)
            assertSame(palette, next.palette())
        }
        assertNotSame(original, HostChromeColors.resolve(true, false, accent, previous = original))
        assertNotSame(original, HostChromeColors.resolve(false, false, 0xFFFFDD00.toInt(), previous = original))
        assertNotSame(original, HostChromeColors.resolve(false, false, accent, accent, original))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostChromeThemeCases) -> Unit) {
            val s = HostChromeThemeCases()
            block(s)
        }
        runIsolated { s -> s.manualHostThemeOverridesOppositeSystemMode() }
        runIsolated { s -> s.unavailableHostApiFallsBackToSystemAndBrandAccent() }
        runIsolated { s -> s.hostAccentSurvivesDayNightAndNeutralSurfacesFollowHostMode() }
        runIsolated { s -> s.foregroundOnHostAccentRemainsReadableForLightAndDarkSkins() }
        runIsolated { s -> s.reusedThemeValuesAndPalettesMatchTheOriginalResolution() }
        runIsolated { s -> s.stableFramesReuseColorsWhileEveryVisualInputChangeTakesEffectImmediately() }
    }
}

private class HostContentSemanticClassifierCases {
    fun advertisementRouteRequiresItsOwnAuthorityInsteadOfAnEmbeddedLink() {
        for (uri in listOf("bilibili://ad/1", "https://cm.bilibili.com/a", "HTTP://CM.BILIBILI.COM/a")) {
            assertTrue(uri, HostContentKind.ADVERTISEMENT in HostContentSemanticClassifier.classify(HostContentSignals(uri = uri)))
        }
        for (uri in listOf(
            "bilibili://video/1?target=https://cm.bilibili.com/a",
            "https://notcm.bilibili.com/a",
            "https://cm.bilibili.com.evil.example/a",
            "https://example.com/cm.bilibili.com/a"
        )) {
            assertFalse(uri, HostContentKind.ADVERTISEMENT in HostContentSemanticClassifier.classify(HostContentSignals(uri = uri)))
        }
    }

    fun `accumulation preserves independent evidence order and existing destination kinds`() {
        val kinds = linkedSetOf(HostContentKind.PICTURE)
        HostContentSemanticClassifier.classifyInto(HostContentSignals(fromSourceType = 2L), kinds)
        HostContentSemanticClassifier.classifyInto(HostContentSignals(relateCardTypeValue = 4), kinds)
        HostContentSemanticClassifier.classifyInto(HostContentSignals(cardType = "LIVE"), kinds)
        HostContentSemanticClassifier.classifyInto(HostContentSignals(cardType = "UNKNOWN"), kinds)
        assertEquals(
            listOf(
                HostContentKind.PICTURE, HostContentKind.ADVERTISEMENT,
                HostContentKind.SPECIAL, HostContentKind.GAME, HostContentKind.LIVE
            ),
            kinds.toList()
        )
        // 后续调用不得污染之前返回的分类快照。
        val snapshot = HostContentSemanticClassifier.classify(HostContentSignals(cardType = "LIVE"))
        HostContentSemanticClassifier.classifyInto(HostContentSignals(cardType = "AD"), kinds)
        assertEquals(setOf(HostContentKind.LIVE), snapshot)
    }

    fun `banner still checks all five allowed evidence slots with exact normalization`() {
        val factories = listOf<(String?) -> HostContentSignals>(
            { HostContentSignals(holderType = it) }, { HostContentSignals(bizType = it) },
            { HostContentSignals(cardType = it) }, { HostContentSignals(cardGoto = it) },
            { HostContentSignals(goTo = it) }
        )
        for (factory in factories) {
            assertTrue(HostContentSemanticClassifier.isHomeBanner(factory(" card_type_banner_v8 ")))
            assertFalse(HostContentSemanticClassifier.isHomeBanner(factory("BANNER_V8_EXTRA")))
            assertFalse(HostContentSemanticClassifier.isHomeBanner(factory(null)))
        }
        assertFalse(
            HostContentSemanticClassifier.isHomeBanner(
                HostContentSignals(cardCase = "BANNER_V8", relateCardType = "BANNER_V8")
            )
        )
    }

    fun `combines exact enum route and commercial evidence`() {
        val kinds = HostContentSemanticClassifier.classify(
            HostContentSignals(
                cardType = "CARD_TYPE_CM_V2",
                goTo = "vertical_av",
                uri = "bilibili://game_center/detail?id=1",
                hasCommercialPayload = true
            )
        )
        assertTrue(HostContentKind.ADVERTISEMENT in kinds)
        assertTrue(HostContentKind.VERTICAL in kinds)
        assertTrue(HostContentKind.GAME in kinds)
    }

    fun `ordinary title text alone does not become live or game content`() {
        val kinds = HostContentSemanticClassifier.classify(
            HostContentSignals(
                cardType = "AV",
                title = "直播技术与游戏开发纪录片"
            )
        )
        assertFalse(HostContentKind.LIVE in kinds)
        assertFalse(HostContentKind.GAME in kinds)
    }

    fun `cm_v2 feed ads match exact card type only`() {
        assertTrue(HostContentSemanticClassifier.isCmV2(HostContentSignals(cardType = "cm_v2")))
        assertTrue(HostContentSemanticClassifier.isCmV2(HostContentSignals(cardType = "CARD_TYPE_CM_V2")))
        assertTrue(HostContentKind.ADVERTISEMENT in HostContentSemanticClassifier.classify(HostContentSignals(cardType = "cm_v2")))
        assertFalse(HostContentSemanticClassifier.isCmV2(HostContentSignals(cardType = "small_cover_v2", cardGoto = "ad_web_s")))
        assertFalse(HostContentSemanticClassifier.isCmV2(HostContentSignals(cardType = "ogv_small_cover", hasAdInfo = true)))
        assertFalse(HostContentSemanticClassifier.isCmV2(HostContentSignals(cardType = "banner_v8")))
    }

    fun `unknown signals remain fail open`() {
        assertEquals(
            emptySet<HostContentKind>(),
            HostContentSemanticClassifier.classify(HostContentSignals(cardType = "FUTURE_CARD_V99"))
        )
    }

    fun `related promotions keep specific kinds and also map to special fallback`() {
        val sourcePromotion = HostContentSemanticClassifier.classify(HostContentSignals(fromSourceType = 2L))
        val resourcePromotion = HostContentSemanticClassifier.classify(HostContentSignals(relateCardType = "RELATE_CARD_TYPE_RESOURCE"))
        val gamePromotion = HostContentSemanticClassifier.classify(HostContentSignals(relateCardTypeValue = 4))

        assertTrue(HostContentKind.ADVERTISEMENT in sourcePromotion)
        assertTrue(HostContentKind.SPECIAL in sourcePromotion)
        assertTrue(HostContentKind.ADVERTISEMENT in resourcePromotion)
        assertTrue(HostContentKind.SPECIAL in resourcePromotion)
        assertTrue(HostContentKind.GAME in gamePromotion)
        assertTrue(HostContentKind.SPECIAL in gamePromotion)
        assertTrue(HostContentKind.SPECIAL in HostContentSemanticClassifier.classify(HostContentSignals(relateCardTypeValue = 3)))
        assertTrue(HostContentKind.SPECIAL in HostContentSemanticClassifier.classify(HostContentSignals(relateCardTypeValue = 5)))
        assertTrue(HostContentKind.SPECIAL in HostContentSemanticClassifier.classify(HostContentSignals(relateCardTypeValue = 10)))
        setOf("RESOURCE", "CM", "GAME", "SPECIAL").forEach { type ->
            assertTrue(HostContentKind.SPECIAL in HostContentSemanticClassifier.classify(HostContentSignals(relateCardType = type)))
        }
        assertEquals(
            emptySet<HostContentKind>(),
            HostContentSemanticClassifier.classify(HostContentSignals(fromSourceType = 99L, relateCardTypeValue = 99))
        )
    }

    fun `related card checks every explicit type before semantic fallback`() {
        assertTrue(VideoRelateFilterFeatureInstaller.matchesAnyType(types = linkedSetOf("AV", "CM"), hiddenTypes = setOf("CM")))
        assertTrue(VideoRelateFilterFeatureInstaller.matchesAnyType(types = setOf("RELATE_CARD_TYPE_VERTICAL_AV"), hiddenTypes = setOf("VERTICAL")))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostContentSemanticClassifierCases) -> Unit) {
            val s = HostContentSemanticClassifierCases()
            block(s)
        }
        runIsolated { s -> s.advertisementRouteRequiresItsOwnAuthorityInsteadOfAnEmbeddedLink() }
        runIsolated { s -> s.`accumulation preserves independent evidence order and existing destination kinds`() }
        runIsolated { s -> s.`banner still checks all five allowed evidence slots with exact normalization`() }
        runIsolated { s -> s.`combines exact enum route and commercial evidence`() }
        runIsolated { s -> s.`ordinary title text alone does not become live or game content`() }
        runIsolated { s -> s.`cm_v2 feed ads match exact card type only`() }
        runIsolated { s -> s.`unknown signals remain fail open`() }
        runIsolated { s -> s.`related promotions keep specific kinds and also map to special fallback`() }
        runIsolated { s -> s.`related card checks every explicit type before semantic fallback`() }
    }
}

private class HostCopyBubblePlacementCases {
    fun keepsLeadingAlignmentAndRightAndLeftMargins() {
        assertEquals(84f, HostCopyBubblePlacement.left(100, 800, 1080, 16, 8), 0f)
        assertEquals(272f, HostCopyBubblePlacement.left(950, 800, 1080, 16, 8), 0f)
        assertEquals(8f, HostCopyBubblePlacement.left(0, 800, 1080, 16, 8), 0f)
        assertEquals(128f, HostCopyBubblePlacement.left(40, 800, 1080, 16, 8, safeLeft = 120, safeRight = 60), 0f)
        assertEquals(212f, HostCopyBubblePlacement.left(950, 800, 1080, 16, 8, safeLeft = 40, safeRight = 60), 0f)
    }

    fun leavesTheOriginalBottomTwentyPercentClearAndFallsBackForStaleAnchors() {
        assertEquals(554f, HostCopyBubblePlacement.top(500, 50, true, 200, 2340, 4, 8), 0f)
        assertEquals(1496f, HostCopyBubblePlacement.top(1800, 50, true, 300, 2340, 4, 8), 0f)
        assertEquals(1572f, HostCopyBubblePlacement.top(2200, 50, true, 300, 2340, 4, 8), 0f)
        assertEquals(990f, HostCopyBubblePlacement.top(3000, 50, false, 200, 2340, 4, 8), 0f)
        assertEquals(138f, HostCopyBubblePlacement.top(20, 40, true, 200, 2340, 4, 8, safeTop = 130, safeBottom = 96), 0f)
        assertEquals(1432f, HostCopyBubblePlacement.top(2200, 50, true, 300, 2340, 4, 8, safeTop = 100, safeBottom = 600), 0f)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostCopyBubblePlacementCases) -> Unit) {
            val s = HostCopyBubblePlacementCases()
            block(s)
        }
        runIsolated { s -> s.keepsLeadingAlignmentAndRightAndLeftMargins() }
        runIsolated { s -> s.leavesTheOriginalBottomTwentyPercentClearAndFallsBackForStaleAnchors() }
    }
}

private class HostBackgroundThemeCases {
    private val loader = javaClass.classLoader!!
    private fun environment(registrar: PlayerPortTestRegistrar, process: String = "tv.danmaku.bili", hostLoader: ClassLoader = loader) =
        HookEnvironment(process, hostLoader, HookPointRegistry(hostLoader), registrar,
            logInfo = { _, _ -> }, logError = { _, _ -> }, reportStatus = { _, _ -> })

    fun celestialPresetsOverrideReadsAndPreserveTheSavedTheme() {
        MultipleThemeUtils.savedTheme = 8
        MultipleThemeUtils.followSystem = true
        for (preset in listOf(HostBackgroundPreset.STARRY, HostBackgroundPreset.NEBULA, HostBackgroundPreset.METEOR)) {
            val registrar = PlayerPortTestRegistrar()
            assertEquals(2, HostBackgroundTheme.install(environment(registrar), preset))
            assertEquals(1, registrar.invoke("host_background.theme", args = arrayOf(null)) {
                MultipleThemeUtils.getCurrentThemeId(null)
            })
            assertEquals(false, registrar.invoke("host_background.theme_follow_system", args = arrayOf(null)) {
                MultipleThemeUtils.isNightFollowSystem(null)
            })
            assertEquals(8, MultipleThemeUtils.getCurrentThemeId(null))
            assertTrue(MultipleThemeUtils.isNightFollowSystem(null))
        }
        for (preset in HostBackgroundPreset.entries.filterNot { it.isCelestial }) {
            val registrar = PlayerPortTestRegistrar()
            assertEquals(0, HostBackgroundTheme.install(environment(registrar), preset))
            assertTrue(registrar.hooks.isEmpty())
        }
    }

    fun webProcessUsesTheSamePolicyButUnrelatedProcessesRemainUntouched() {
        val web = PlayerPortTestRegistrar()
        assertEquals(2, HostBackgroundTheme.install(environment(web, "tv.danmaku.bili:web"), HostBackgroundPreset.STARRY))
        val other = PlayerPortTestRegistrar()
        assertEquals(0, HostBackgroundTheme.install(environment(other, "another.app"), HostBackgroundPreset.STARRY))
        assertTrue(other.hooks.isEmpty())
    }

    fun missingThemeApiLeavesNoHooksInstalled() {
        val unknown = object : ClassLoader(loader) {
            override fun loadClass(name: String): Class<*> {
                if (name == "com.bilibili.lib.ui.util.MultipleThemeUtils") throw ClassNotFoundException(name)
                return super.loadClass(name)
            }
        }
        val registrar = PlayerPortTestRegistrar()
        assertEquals(0, HostBackgroundTheme.install(environment(registrar, hostLoader = unknown), HostBackgroundPreset.STARRY))
        assertTrue(registrar.hooks.isEmpty())
    }

    fun originalThemeReadFailureIsNotSilenced() {
        val registrar = PlayerPortTestRegistrar()
        HostBackgroundTheme.install(environment(registrar), HostBackgroundPreset.STARRY)
        val failure = IllegalStateException("Native theme read failed")
        try {
            registrar.invoke("host_background.theme", args = arrayOf(null)) { throw failure }
            fail("Native failure must still be delivered")
        } catch (caught: IllegalStateException) { assertSame(failure, caught) }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostBackgroundThemeCases) -> Unit) {
            val s = HostBackgroundThemeCases()
            block(s)
        }
        runIsolated { s -> s.celestialPresetsOverrideReadsAndPreserveTheSavedTheme() }
        runIsolated { s -> s.webProcessUsesTheSamePolicyButUnrelatedProcessesRemainUntouched() }
        runIsolated { s -> s.missingThemeApiLeavesNoHooksInstalled() }
        runIsolated { s -> s.originalThemeReadFailureIsNotSilenced() }
    }
}

private class HostStatusBarContrastCases {
    private val view = View(null)

    fun applyAndRestorePreserveOriginalAndAdoptLateHostChanges() {
        var lightStatus = false
        var writes = 0
        val contrast = HostStatusBarContrast({ lightStatus }, { _, light -> writes++; lightStatus = light })

        contrast.apply(view, darkContent = false)
        assertTrue(lightStatus)
        assertEquals(1, writes)

        // 幂等重复写入不触发多余控制器写操作。
        contrast.apply(view, darkContent = false)
        assertEquals(1, writes)

        contrast.restore(view)
        assertFalse(lightStatus)
        assertEquals(2, writes)

        // 若宿主切页时晚到覆盖了状态栏前景，再次 apply 应采纳宿主新值并在恢复时还原到新值。
        contrast.apply(view, darkContent = true)
        assertFalse(lightStatus)
        lightStatus = true
        contrast.apply(view, darkContent = true)
        assertFalse(lightStatus)
        contrast.restore(view)
        assertTrue(lightStatus)
    }

    fun externalOverwriteBeforeRestoreIsNotClobberedAndMissingControllerFailsOpen() {
        var lightStatus = false
        val contrast = HostStatusBarContrast({ lightStatus }, { _, light -> lightStatus = light })

        contrast.apply(view, darkContent = false)
        assertTrue(lightStatus)
        lightStatus = false // 宿主已主动接管。
        contrast.apply(view, darkContent = false)
        assertTrue(lightStatus)
        contrast.apply(view, darkContent = true)
        assertFalse(lightStatus)
        // 恢复前若状态又被宿主改成与 appliedLightStatus 不同的值，则不覆盖。
        lightStatus = true
        contrast.restore(view)
        assertTrue(lightStatus)

        var writes = 0
        val nullController = HostStatusBarContrast({ null }, { _, _ -> writes++ })
        nullController.apply(view, darkContent = false)
        nullController.restore(view)
        assertEquals(0, writes)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostStatusBarContrastCases) -> Unit) {
            val s = HostStatusBarContrastCases()
            block(s)
        }
        runIsolated { s -> s.applyAndRestorePreserveOriginalAndAdoptLateHostChanges() }
        runIsolated { s -> s.externalOverwriteBeforeRestoreIsNotClobberedAndMissingControllerFailsOpen() }
    }
}
