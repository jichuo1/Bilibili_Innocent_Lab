package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.bilibili.lib.router.Router
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GuestVideoFavoriteHooksTest {
    private val statuses = linkedMapOf<String, String>()
    private val proxy = Router.RouterProxy()
    private val notified = mutableListOf<Boolean>()
    private var scene: String? = FAV_SCENE
    private var extend: String? = """{"oid":"100","sid":"3631785"}"""

    private class FakeStore(var known: Set<Long> = setOf(100L), var succeed: Boolean = true, var added: Boolean = true) :
        GuestVideoFavoriteHooks.FavoriteStore {
        val toggled = mutableListOf<Long>()
        val folderChecks = linkedMapOf("默认收藏夹" to false)
        val savedSelections = mutableListOf<Set<String>>()
        val created = mutableListOf<String>()
        var generation = 0
        override fun sessionGuard(): () -> Boolean {
            val captured = generation
            return { captured == generation }
        }
        override fun metadata(aid: Long) =
            if (aid in known) LocalVirtualLibrary.Video(aid, 1L, "t$aid", 1000L, 0L, 1L) else null
        override fun toggle(video: LocalVirtualLibrary.Video, onSaved: (Boolean) -> Unit, onFailed: () -> Unit) {
            toggled += video.aid
            if (succeed) onSaved(added) else onFailed()
        }
        override fun folders(aid: Long, callback: (List<GuestVideoFavoriteHooks.Folder>) -> Unit) =
            callback(folderChecks.map { GuestVideoFavoriteHooks.Folder(it.key, it.value) })
        override fun save(video: LocalVirtualLibrary.Video, selected: Set<String>, onSaved: () -> Unit, onFailed: () -> Unit) {
            savedSelections += selected
            if (succeed) onSaved() else onFailed()
        }
        override fun createFolder(name: String, onCreated: () -> Unit, onFailed: () -> Unit) {
            created += name
            if (succeed) { folderChecks[name] = false; onCreated() } else onFailed()
        }
    }

    /** Stands in for the dialog: remembers what it was shown and lets the test press its buttons. */
    private class FakePicker(var canShow: Boolean = true) : GuestVideoFavoriteHooks.FolderPicker {
        val shown = mutableListOf<List<GuestVideoFavoriteHooks.Folder>>()
        lateinit var confirm: (Set<String>) -> Unit
        lateinit var create: (String, Set<String>) -> Unit
        override fun show(
            context: android.content.Context?,
            folders: List<GuestVideoFavoriteHooks.Folder>,
            onConfirm: (Set<String>) -> Unit,
            onCreate: (String, Set<String>) -> Unit
        ): Boolean {
            if (!canShow) return false
            shown += folders
            confirm = onConfirm
            create = onCreate
            return true
        }
    }

    @Before fun reset() {
        statuses.clear()
        proxy.opened.clear()
        notified.clear()
        fixtures.privacy.videofavorite.storyrouter.StoryRouter.routed.clear()
        scene = FAV_SCENE
        extend = """{"oid":"100","sid":"3631785"}"""
    }

    private fun environment(registrar: PlayerPortTestRegistrar) =
        HookEnvironment("tv.danmaku.bili", javaClass.classLoader!!, HookPointRegistry(javaClass.classLoader!!), registrar,
            { _, _ -> }, { _, _ -> }, { key, value -> statuses[key] = value })

    private fun hooks(
        store: FakeStore,
        guest: () -> Boolean = { true },
        proxyClass: String = GuestVideoFavoriteHooks.PROXY,
        locate: (ClassLoader) -> GuestVideoFavoriteHooks.StateWriter? = { null },
        picker: GuestVideoFavoriteHooks.FolderPicker? = null,
        router: GuestVideoFavoriteHooks.RouterNames = NO_ROUTER,
        story: GuestVideoFavoriteHooks.StoryStarSource? = null,
        fromStoryStar: Boolean = true,
        page: GuestVideoFavoriteHooks.PageFavoriteSource? = null,
        interaction: GuestVideoFavoriteHooks.InteractionSource? = null,
        snackbar: GuestVideoFavoriteHooks.HostSnackbar? = null
    ) = GuestVideoFavoriteHooks(store, guest, proxyClass, { scene to extend }, locate, { _, saved -> notified += saved }, picker,
        router, story, page, interactionSource = interaction, hostSnackbar = snackbar,
        notifyRemoved = { notified += true }) { fromStoryStar }

    private class FakeInteraction(var action: GuestVideoFavoriteHooks.Interaction, var installs: Boolean = true) :
        GuestVideoFavoriteHooks.InteractionSource {
        override fun install(env: HookEnvironment, loader: ClassLoader) = installs
        override fun current() = action
    }

    private class FakeSnackbar(var installs: Boolean = true, var canShow: Boolean = true) : GuestVideoFavoriteHooks.HostSnackbar {
        val added = mutableListOf<Long>()
        val storySurfaces = mutableListOf<Boolean>()
        var removed = 0
        var modify: (() -> Unit)? = null
        override fun install(env: HookEnvironment, loader: ClassLoader) = installs
        override fun showAdded(context: android.content.Context?, video: LocalVirtualLibrary.Video, story: Boolean,
                               onModify: () -> Unit): Boolean {
            if (!canShow) return false
            added += video.aid
            storySurfaces += story
            modify = onModify
            return true
        }
        override fun showRemoved(context: android.content.Context?): Boolean {
            if (!canShow) return false
            removed++
            return true
        }
    }

    /** Stands in for the host's page repository: records what it was given and answers as the test says. */
    private class FakePage(var installs: Boolean = true, var takes: Boolean = true, var throwsLater: Boolean = false) :
        GuestVideoFavoriteHooks.PageFavoriteSource {
        val given = mutableListOf<String>()
        override fun install(env: HookEnvironment, loader: ClassLoader) = installs
        override fun apply(aid: Long, favorited: Boolean, onFailed: () -> Unit): Boolean {
            if (!takes) return false
            given += "$aid:$favorited"
            if (throwsLater) onFailed()
            return true
        }
    }

    /** Stands in for the Story widget lookup: reports a fixed tap (or none) and records the star state it was told to paint. */
    private class FakeStory(var tap: LocalVirtualLibrary.Video? = LocalVirtualLibrary.Video(555L, 1L, "story video", 1000L, 0L, 1L),
                            var installs: Boolean = true, var longPress: Boolean = false) : GuestVideoFavoriteHooks.StoryStarSource {
        val painted = mutableListOf<Boolean>()
        override fun install(env: HookEnvironment, loader: ClassLoader) = installs
        override fun isLongPress() = longPress
        override fun tapped() = tap?.let { video -> GuestVideoFavoriteHooks.StoryTap(null, video) { painted += it } }
    }

    /** Runs `BLRouter.routeTo(request, null)` through the registered Story hook; the fixture router runs only when the hook did not take over. */
    private fun PlayerPortTestRegistrar.routeTo(uri: String) = fixtures.privacy.videofavorite.storyrouter.StoryRequest(uri).let { request ->
        invoke("guest.video.favorite.story", null, arrayOf(request, null)) {
            fixtures.privacy.videofavorite.storyrouter.StoryRouter.routeTo(request, null as android.content.Context?)
        } as fixtures.privacy.videofavorite.storyrouter.StoryResponse
    }

    /** Runs `open(route)` through the registered hook; the original (recording) open runs only when the hook did not take over. */
    private fun PlayerPortTestRegistrar.openString(route: String) =
        invoke("guest.video.favorite.string", proxy, arrayOf(route)) { proxy.open(route); null }

    @Test fun guestStarTapOnTheLoginRouteBecomesALocalFavoriteAndTheLoginScreenIsNotOpened() {
        val store = FakeStore()
        val registrar = PlayerPortTestRegistrar()
        assertEquals(2, hooks(store).install(environment(registrar)))

        registrar.openString("activity://main/login/")

        assertEquals(listOf(100L), store.toggled)
        assertTrue(proxy.opened.isEmpty())                 // the original open() never ran
        assertEquals(listOf(true), notified)
        assertEquals("seen", statuses["guest_video_favorite_applied"])
        assertEquals("seen", statuses["guest_video_favorite_saved"])
        assertNull(statuses["guest_video_favorite_failed"])
        assertEquals("installed", statuses["guest_video_favorite"]) // the install report is never overwritten
    }

    @Test fun bothLoginRouteSpellingsAreAccepted() {
        val store = FakeStore()
        val registrar = PlayerPortTestRegistrar()
        hooks(store).install(environment(registrar))
        registrar.openString("bilibili://login")
        registrar.openString("activity://main/login")
        registrar.openString("activity://main/login/?from=x")
        assertEquals(listOf(100L, 100L, 100L), store.toggled)
        assertTrue(proxy.opened.isEmpty())
    }

    @Test fun otherScenesKeepTheLoginScreen() {
        val store = FakeStore()
        val registrar = PlayerPortTestRegistrar()
        hooks(store).install(environment(registrar))
        scene = "player.ugc-video-detail.user-action.unlike.click"
        registrar.openString("activity://main/login/")
        scene = null
        registrar.openString("activity://main/login/")
        assertTrue(store.toggled.isEmpty())
        assertEquals(2, proxy.opened.size)                 // the host opened the login screen both times
        assertNull(statuses["guest_video_favorite_applied"])
    }

    @Test fun routesOtherThanLoginAreNeverTouched() {
        val store = FakeStore()
        val registrar = PlayerPortTestRegistrar()
        hooks(store).install(environment(registrar))
        registrar.openString("bilibili://video/100")
        registrar.openString("bilibili://main/favorite")
        assertTrue(store.toggled.isEmpty())
        assertEquals(listOf("bilibili://video/100", "bilibili://main/favorite"), proxy.opened)
    }

    @Test fun anUnknownVideoKeepsTheLoginScreenSoTheTapIsNeverSwallowed() {
        val store = FakeStore(known = emptySet())
        val registrar = PlayerPortTestRegistrar()
        hooks(store).install(environment(registrar))
        registrar.openString("activity://main/login/")
        assertTrue(store.toggled.isEmpty())
        assertEquals(listOf("activity://main/login/"), proxy.opened)
        assertNull(statuses["guest_video_favorite_applied"])
    }

    @Test fun loggedInUsersKeepTheHostBehavior() {
        val store = FakeStore()
        val registrar = PlayerPortTestRegistrar()
        hooks(store, guest = { false }).install(environment(registrar))
        registrar.openString("activity://main/login/")
        assertTrue(store.toggled.isEmpty())
        assertEquals(listOf("activity://main/login/"), proxy.opened)
    }

    @Test fun aFailedLocalWriteIsReportedSeparatelyAndTheGuestIsToldSo() {
        val store = FakeStore(succeed = false)
        val registrar = PlayerPortTestRegistrar()
        hooks(store).install(environment(registrar))
        registrar.openString("activity://main/login/")
        assertTrue(proxy.opened.isEmpty())                 // taken over, then reported as failed, not silently dropped
        assertEquals(listOf(false), notified)
        assertEquals("seen", statuses["guest_video_favorite_failed"])
        assertNull(statuses["guest_video_favorite_saved"])
        assertEquals("seen", statuses["guest_video_favorite_applied"])
    }

    @Test fun installingAloneClaimsNothingWasTakenOver() {
        val registrar = PlayerPortTestRegistrar()
        hooks(FakeStore(), guest = { false }).install(environment(registrar))
        registrar.openString("activity://main/login/")
        assertEquals("installed", statuses["guest_video_favorite"])
        assertNull(statuses["guest_video_favorite_applied"])
        assertNull(statuses["guest_video_favorite_saved"])
    }

    @Test fun missingProxyClassInstallsNothingAndReportsAbsent() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(0, hooks(FakeStore(), proxyClass = "com.bilibili.lib.router.NoSuchProxy").install(environment(registrar)))
        assertEquals("absent", statuses["guest_video_favorite"])
        assertTrue(registrar.hooks.isEmpty())
    }

    @Test fun aProxyWithoutTheExpectedMembersInstallsNothing() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(0, hooks(FakeStore(), proxyClass = "fixtures.privacy.videofavorite.IncompleteProxy").install(environment(registrar)))
        assertEquals("absent", statuses["guest_video_favorite"])
        assertTrue(registrar.hooks.isEmpty())
    }

    @Test fun theStarIsRepaintedFromTheToggleResultAndTheSavedToastIsSkipped() {
        com.bilibili.community.favorite.e.calls.clear()
        val store = FakeStore()
        val registrar = PlayerPortTestRegistrar()
        hooks(store, locate = { GuestVideoFavoriteHooks.locateStateWriter(it) }).install(environment(registrar))

        registrar.openString("activity://main/login/")       // added
        store.added = false
        registrar.openString("activity://main/login/")       // removed again

        assertEquals(listOf("100:true", "100:false"), com.bilibili.community.favorite.e.calls)
        assertTrue(notified.isEmpty())                       // the star colour is the feedback, not a toast
        assertEquals("installed", statuses["guest_video_favorite_state"])
        assertEquals("seen", statuses["guest_video_favorite_repainted"])
    }

    @Test fun whenThePageRepositoryTakesTheStateTheCacheWriterAndTheToastAreSkipped() {
        com.bilibili.community.favorite.e.calls.clear()
        val store = FakeStore()
        val page = FakePage()
        val registrar = PlayerPortTestRegistrar()
        hooks(store, locate = { GuestVideoFavoriteHooks.locateStateWriter(it) }, page = page).install(environment(registrar))

        registrar.openString("activity://main/login/")

        assertEquals(listOf("100:true"), page.given)
        assertTrue(com.bilibili.community.favorite.e.calls.isEmpty())   // n() writes the cache itself, and must not find it already set
        assertTrue(notified.isEmpty())
        assertEquals("installed", statuses["guest_video_favorite_page"])
        assertEquals("seen", statuses["guest_video_favorite_counted"])
        assertNull(statuses["guest_video_favorite_repainted"])
    }

    @Test fun whenNoPageRepositoryKnowsTheVideoTheStarCacheIsWrittenAlone() {
        com.bilibili.community.favorite.e.calls.clear()
        val store = FakeStore()
        val page = FakePage(takes = false)
        val registrar = PlayerPortTestRegistrar()
        hooks(store, locate = { GuestVideoFavoriteHooks.locateStateWriter(it) }, page = page).install(environment(registrar))

        registrar.openString("activity://main/login/")

        assertEquals(listOf("100:true"), com.bilibili.community.favorite.e.calls)
        assertNull(statuses["guest_video_favorite_counted"])
        assertEquals("seen", statuses["guest_video_favorite_repainted"])
    }

    @Test fun aPageRepositoryCallThatFailsLaterFallsBackToTheStarCacheWriter() {
        com.bilibili.community.favorite.e.calls.clear()
        val store = FakeStore()
        val page = FakePage(throwsLater = true)
        val registrar = PlayerPortTestRegistrar()
        hooks(store, locate = { GuestVideoFavoriteHooks.locateStateWriter(it) }, page = page).install(environment(registrar))

        registrar.openString("activity://main/login/")

        assertEquals(listOf("100:true"), com.bilibili.community.favorite.e.calls)
        assertEquals("seen", statuses["guest_video_favorite_repainted"])
    }

    @Test fun aPageSourceThatCannotInstallIsReportedAbsentAndNeverCalled() {
        com.bilibili.community.favorite.e.calls.clear()
        val store = FakeStore()
        val page = FakePage(installs = false)
        val registrar = PlayerPortTestRegistrar()
        hooks(store, locate = { GuestVideoFavoriteHooks.locateStateWriter(it) }, page = page).install(environment(registrar))

        registrar.openString("activity://main/login/")

        assertEquals("absent", statuses["guest_video_favorite_page"])
        assertTrue(page.given.isEmpty())
        assertEquals(listOf("100:true"), com.bilibili.community.favorite.e.calls)
    }

    @Test fun aFailedLocalWriteNeverReachesThePageRepository() {
        val store = FakeStore(succeed = false)
        val page = FakePage()
        val registrar = PlayerPortTestRegistrar()
        hooks(store, locate = { GuestVideoFavoriteHooks.locateStateWriter(it) }, page = page).install(environment(registrar))

        registrar.openString("activity://main/login/")

        assertTrue(page.given.isEmpty())
        assertEquals(listOf(false), notified)
    }

    @Test fun withoutAStateWriterTheGuestStillGetsTheSavedToast() {
        val store = FakeStore()
        val registrar = PlayerPortTestRegistrar()
        hooks(store).install(environment(registrar))

        registrar.openString("activity://main/login/")

        assertEquals(listOf(true), notified)
        assertEquals("absent", statuses["guest_video_favorite_state"])
        assertNull(statuses["guest_video_favorite_repainted"])
    }

    @Test fun aFailedLocalWriteNeverRepaintsTheStar() {
        com.bilibili.community.favorite.e.calls.clear()
        val store = FakeStore(succeed = false)
        val registrar = PlayerPortTestRegistrar()
        hooks(store, locate = { GuestVideoFavoriteHooks.locateStateWriter(it) }).install(environment(registrar))

        registrar.openString("activity://main/login/")

        assertTrue(com.bilibili.community.favorite.e.calls.isEmpty())
        assertEquals(listOf(false), notified)
    }

    @Test fun theStateWriterIsLocatedThroughTheCoroutineClassAndAbsentWhenItIsMissing() {
        val found = GuestVideoFavoriteHooks.locateFavoriteState(javaClass.classLoader!!)
        assertEquals("c", found?.name)
        assertEquals(com.bilibili.community.favorite.e::class.java, found?.declaringClass)
        val empty = object : ClassLoader(null) {}
        assertNull(GuestVideoFavoriteHooks.locateFavoriteState(empty))
    }

    @Test fun withAPickerTheStarTapOpensTheFolderChoiceInsteadOfTogglingTheDefaultFolder() {
        val store = FakeStore()
        val picker = FakePicker()
        val registrar = PlayerPortTestRegistrar()
        hooks(store, picker = picker).install(environment(registrar))

        registrar.openString("activity://main/login/")

        assertEquals(listOf(listOf(GuestVideoFavoriteHooks.Folder("默认收藏夹", false))), picker.shown)
        assertTrue(store.toggled.isEmpty())
        assertTrue(store.savedSelections.isEmpty())          // nothing is written until the guest confirms
        assertTrue(proxy.opened.isEmpty())                   // the login screen never opened
        assertEquals("seen", statuses["guest_video_favorite_applied"])
        assertEquals("seen", statuses["guest_video_favorite_picker"])
        assertNull(statuses["guest_video_favorite_saved"])
    }

    @Test fun recognizedClickTogglesTheDefaultFolderAndUsesTheHostSnackbar() {
        val store = FakeStore()
        val picker = FakePicker()
        val snackbar = FakeSnackbar()
        val registrar = PlayerPortTestRegistrar()
        hooks(store, picker = picker, interaction = FakeInteraction(GuestVideoFavoriteHooks.Interaction.CLICK),
            snackbar = snackbar).install(environment(registrar))

        registrar.openString("activity://main/login/")
        assertEquals(listOf(100L), store.toggled)
        assertEquals(listOf(100L), snackbar.added)
        assertTrue(picker.shown.isEmpty())
        assertTrue(notified.isEmpty())
        assertEquals("seen", statuses["guest_video_favorite_snackbar_shown"])

        store.added = false
        registrar.openString("activity://main/login/")
        assertEquals(1, snackbar.removed)
        assertEquals(listOf(100L, 100L), store.toggled)
    }

    @Test fun recognizedLongPressOpensThePickerWithoutChangingFavorites() {
        val store = FakeStore()
        val picker = FakePicker()
        val registrar = PlayerPortTestRegistrar()
        hooks(store, picker = picker, interaction = FakeInteraction(GuestVideoFavoriteHooks.Interaction.LONG_PRESS))
            .install(environment(registrar))
        registrar.openString("activity://main/login/")
        assertEquals(1, picker.shown.size)
        assertTrue(store.toggled.isEmpty())
        assertTrue(store.savedSelections.isEmpty())
    }

    @Test fun longPressWithNoPickerNeverTogglesAnExistingFavorite() {
        val store = FakeStore()
        val registrar = PlayerPortTestRegistrar()
        hooks(store, picker = FakePicker(canShow = false),
            interaction = FakeInteraction(GuestVideoFavoriteHooks.Interaction.LONG_PRESS)).install(environment(registrar))
        registrar.openString("activity://main/login/")
        assertTrue(store.toggled.isEmpty())
        assertEquals(listOf(false), notified)
    }

    @Test fun aPickerFromAnOldSessionCannotWriteIntoTheNewSession() {
        val store = FakeStore()
        val picker = FakePicker()
        val registrar = PlayerPortTestRegistrar()
        hooks(store, picker = picker, interaction = FakeInteraction(GuestVideoFavoriteHooks.Interaction.LONG_PRESS))
            .install(environment(registrar))
        registrar.openString("activity://main/login/")
        store.generation++
        picker.confirm(setOf("默认收藏夹"))
        picker.create("新夹", emptySet())
        assertTrue(store.savedSelections.isEmpty())
        assertTrue(store.created.isEmpty())
    }

    @Test fun anUnavailableSnackbarFallsBackToLocalFeedbackAfterTheWrite() {
        val store = FakeStore()
        val registrar = PlayerPortTestRegistrar()
        hooks(store, interaction = FakeInteraction(GuestVideoFavoriteHooks.Interaction.CLICK),
            snackbar = FakeSnackbar(canShow = false)).install(environment(registrar))
        registrar.openString("activity://main/login/")
        assertEquals(listOf(100L), store.toggled)
        assertEquals(listOf(true), notified)
    }

    @Test fun confirmingSavesExactlyTheTickedFoldersAndRepaintsTheStarOn() {
        com.bilibili.community.favorite.e.calls.clear()
        val store = FakeStore().apply { folderChecks["旅行"] = false }
        val picker = FakePicker()
        val registrar = PlayerPortTestRegistrar()
        hooks(store, locate = { GuestVideoFavoriteHooks.locateStateWriter(it) }, picker = picker).install(environment(registrar))
        registrar.openString("activity://main/login/")

        picker.confirm(linkedSetOf("默认收藏夹", "旅行"))

        assertEquals(listOf(setOf("默认收藏夹", "旅行")), store.savedSelections)
        assertEquals(listOf("100:true"), com.bilibili.community.favorite.e.calls)
        assertTrue(notified.isEmpty())                       // the dialog closing and the star are the feedback
        assertEquals("seen", statuses["guest_video_favorite_saved"])
        assertEquals("seen", statuses["guest_video_favorite_repainted"])
    }

    @Test fun confirmingWithNothingTickedRemovesTheVideoAndRepaintsTheStarOff() {
        com.bilibili.community.favorite.e.calls.clear()
        val store = FakeStore().apply { folderChecks["默认收藏夹"] = true }
        val picker = FakePicker()
        val registrar = PlayerPortTestRegistrar()
        hooks(store, locate = { GuestVideoFavoriteHooks.locateStateWriter(it) }, picker = picker).install(environment(registrar))
        registrar.openString("activity://main/login/")
        assertEquals(listOf(listOf(GuestVideoFavoriteHooks.Folder("默认收藏夹", true))), picker.shown)   // already favorited, shown ticked

        picker.confirm(emptySet())

        assertEquals(listOf(emptySet<String>()), store.savedSelections)
        assertEquals(listOf("100:false"), com.bilibili.community.favorite.e.calls)
    }

    @Test fun creatingAFolderReopensThePickerWithTheNewFolderTickedAndEarlierTicksKept() {
        val store = FakeStore()
        val picker = FakePicker()
        val registrar = PlayerPortTestRegistrar()
        hooks(store, picker = picker).install(environment(registrar))
        registrar.openString("activity://main/login/")

        picker.create("新夹", setOf("默认收藏夹"))

        assertEquals(listOf("新夹"), store.created)
        assertEquals(2, picker.shown.size)
        assertEquals(
            listOf(GuestVideoFavoriteHooks.Folder("默认收藏夹", true), GuestVideoFavoriteHooks.Folder("新夹", true)),
            picker.shown[1]
        )
        assertTrue(store.savedSelections.isEmpty())          // creating a folder alone does not favorite the video
    }

    @Test fun aPickerThatCannotShowFallsBackToTheDefaultFolderSoTheTapIsNeverLost() {
        val store = FakeStore()
        val registrar = PlayerPortTestRegistrar()
        hooks(store, picker = FakePicker(canShow = false)).install(environment(registrar))

        registrar.openString("activity://main/login/")

        assertEquals(listOf(100L), store.toggled)
        assertEquals(listOf(true), notified)
        assertNull(statuses["guest_video_favorite_picker"])
        assertTrue(proxy.opened.isEmpty())
    }

    @Test fun aFailedFolderWriteIsReportedAndNeverRepaintsTheStar() {
        com.bilibili.community.favorite.e.calls.clear()
        val store = FakeStore()
        val picker = FakePicker()
        val registrar = PlayerPortTestRegistrar()
        hooks(store, locate = { GuestVideoFavoriteHooks.locateStateWriter(it) }, picker = picker).install(environment(registrar))
        registrar.openString("activity://main/login/")
        store.succeed = false

        picker.confirm(setOf("默认收藏夹"))

        assertTrue(com.bilibili.community.favorite.e.calls.isEmpty())
        assertEquals(listOf(false), notified)
        assertEquals("seen", statuses["guest_video_favorite_failed"])
        assertNull(statuses["guest_video_favorite_saved"])
    }

    @Test fun aFailedFolderCreateIsReportedAndDoesNotReopenThePicker() {
        val store = FakeStore()
        val picker = FakePicker()
        val registrar = PlayerPortTestRegistrar()
        hooks(store, picker = picker).install(environment(registrar))
        registrar.openString("activity://main/login/")
        store.succeed = false

        picker.create("新夹", emptySet())

        assertEquals(1, picker.shown.size)
        assertEquals(listOf(false), notified)
        assertEquals("seen", statuses["guest_video_favorite_failed"])
    }

    @Test fun anUnknownVideoStillKeepsTheLoginScreenEvenWithAPicker() {
        val store = FakeStore(known = emptySet())
        val picker = FakePicker()
        val registrar = PlayerPortTestRegistrar()
        hooks(store, picker = picker).install(environment(registrar))

        registrar.openString("activity://main/login/")

        assertTrue(picker.shown.isEmpty())
        assertEquals(listOf("activity://main/login/"), proxy.opened)
    }

    @Test fun storySingleClickTogglesAndUsesTheStorySnackbar() {
        val store = FakeStore()
        val picker = FakePicker()
        val snackbar = FakeSnackbar()
        val story = FakeStory()
        val registrar = PlayerPortTestRegistrar()
        hooks(store, picker = picker, router = STORY_ROUTER, story = story, snackbar = snackbar).install(environment(registrar))

        registrar.routeTo("bilibili://login")
        assertEquals(listOf(555L), store.toggled)
        assertEquals(listOf(true), story.painted)
        assertEquals(listOf(555L), snackbar.added)
        assertEquals(listOf(true), snackbar.storySurfaces)
        assertTrue(picker.shown.isEmpty())

        store.added = false
        registrar.routeTo("bilibili://login")
        assertEquals(listOf(true, false), story.painted)
        assertEquals(1, snackbar.removed)
    }

    @Test fun storyLongPressOpensTheFolderChoiceWithoutToggling() {
        com.bilibili.community.favorite.e.calls.clear()
        val store = FakeStore()
        val picker = FakePicker()
        val story = FakeStory(longPress = true)
        val registrar = PlayerPortTestRegistrar()
        hooks(store, locate = { GuestVideoFavoriteHooks.locateStateWriter(it) }, picker = picker, router = STORY_ROUTER, story = story,
            fromStoryStar = false).install(environment(registrar))

        val response = registrar.routeTo("bilibili://login")

        assertTrue(store.toggled.isEmpty())
        assertEquals(fixtures.privacy.videofavorite.storyrouter.StoryCode.OK, response.code)   // answered, so the caller sees a handled route
        assertTrue(fixtures.privacy.videofavorite.storyrouter.StoryRouter.routed.isEmpty())    // the login screen was never routed to
        assertEquals(1, picker.shown.size)
        assertEquals("seen", statuses["guest_video_favorite_story"])
        assertEquals("seen", statuses["guest_video_favorite_applied"])

        picker.confirm(setOf("默认收藏夹"))
        assertEquals(listOf(setOf("默认收藏夹")), store.savedSelections)
        assertEquals(listOf(true), story.painted)                                                  // the Story item's own star state follows
    }

    @Test fun unfavoritingFromTheStoryPickerPaintsTheStoryStarOff() {
        val story = FakeStory(longPress = true)
        val picker = FakePicker()
        val registrar = PlayerPortTestRegistrar()
        hooks(FakeStore(), picker = picker, router = STORY_ROUTER, story = story).install(environment(registrar))
        registrar.routeTo("bilibili://login")

        picker.confirm(emptySet())

        assertEquals(listOf(false), story.painted)
    }

    @Test fun aLoginRequestThatDoesNotComeFromTheStoryStarIsLeftAlone() {
        val picker = FakePicker()
        val registrar = PlayerPortTestRegistrar()
        hooks(FakeStore(), picker = picker, router = STORY_ROUTER, story = FakeStory(), fromStoryStar = false).install(environment(registrar))

        val response = registrar.routeTo("bilibili://login")

        assertEquals(fixtures.privacy.videofavorite.storyrouter.StoryCode.FOUND_STUB, response.code)
        assertEquals(listOf("bilibili://login"), fixtures.privacy.videofavorite.storyrouter.StoryRouter.routed)
        assertTrue(picker.shown.isEmpty())
        assertNull(statuses["guest_video_favorite_story"])
    }

    @Test fun routesOtherThanLoginAreNeverTouchedByTheStoryHook() {
        val registrar = PlayerPortTestRegistrar()
        hooks(FakeStore(), picker = FakePicker(), router = STORY_ROUTER, story = FakeStory()).install(environment(registrar))

        val response = registrar.routeTo("bilibili://video/100")

        assertEquals(fixtures.privacy.videofavorite.storyrouter.StoryCode.FOUND_STUB, response.code)
        assertEquals(listOf("bilibili://video/100"), fixtures.privacy.videofavorite.storyrouter.StoryRouter.routed)
    }

    @Test fun whenTheTappedVideoCannotBeToldTheStoryLoginScreenStaysSoTheTapIsNeverSwallowed() {
        val picker = FakePicker()
        val registrar = PlayerPortTestRegistrar()
        hooks(FakeStore(), picker = picker, router = STORY_ROUTER, story = FakeStory(tap = null)).install(environment(registrar))

        val response = registrar.routeTo("bilibili://login")

        assertEquals(fixtures.privacy.videofavorite.storyrouter.StoryCode.FOUND_STUB, response.code)
        assertEquals(listOf("bilibili://login"), fixtures.privacy.videofavorite.storyrouter.StoryRouter.routed)
        assertTrue(picker.shown.isEmpty())
        assertEquals("seen", statuses["guest_video_favorite_story_unknown"])
    }

    @Test fun loggedInUsersKeepTheStoryLoginBehavior() {
        val registrar = PlayerPortTestRegistrar()
        hooks(FakeStore(), guest = { false }, picker = FakePicker(), router = STORY_ROUTER, story = FakeStory()).install(environment(registrar))

        val response = registrar.routeTo("bilibili://login")

        assertEquals(fixtures.privacy.videofavorite.storyrouter.StoryCode.FOUND_STUB, response.code)
        assertNull(statuses["guest_video_favorite_story"])
    }

    @Test fun theStoryStarWithoutAPickerTogglesTheDefaultFolderOfTheTappedVideo() {
        val store = FakeStore()
        val story = FakeStory()
        val registrar = PlayerPortTestRegistrar()
        hooks(store, router = STORY_ROUTER, story = story).install(environment(registrar))

        registrar.routeTo("bilibili://login")

        assertEquals(listOf(555L), store.toggled)
        assertEquals(listOf(true), story.painted)
    }

    @Test fun aStorySourceThatCannotInstallLeavesTheStoryStarAloneAndTheRestWorking() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(2, hooks(FakeStore(), router = STORY_ROUTER, story = FakeStory(installs = false)).install(environment(registrar)))
        assertTrue("guest.video.favorite.story" !in registrar.hooks)
    }

    @Test fun anUnknownRouterInstallsNoStoryHookAndTheRestStillWorks() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(2, hooks(FakeStore(), router = NO_ROUTER, story = FakeStory()).install(environment(registrar)))
        assertTrue("guest.video.favorite.story" !in registrar.hooks)
    }

    @Test fun theStoryWidgetIsRecognizedFromAnyFrameOfItsOwnOrItsNestedClasses() {
        fun frame(cls: String) = StackTraceElement(cls, "m", null, -1)
        val widget = GuestVideoFavoriteHooks.STORY_FAVORITE
        assertTrue(GuestVideoFavoriteHooks.fromStoryFavoriteWidget(arrayOf(frame("a.b"), frame(widget))))
        assertTrue(GuestVideoFavoriteHooks.fromStoryFavoriteWidget(arrayOf(frame("$widget\$a"))))
        assertTrue(!GuestVideoFavoriteHooks.fromStoryFavoriteWidget(arrayOf(frame("${widget}Like"), frame("a.b"))))
        assertTrue(!GuestVideoFavoriteHooks.fromStoryFavoriteWidget(emptyArray()))
    }

    @Test fun on884To896BuildsTheStateWriterIsTheSingletonsInstanceMethod() {
        val pkg = "fixtures.privacy.videofavorite.singleton"
        fixtures.privacy.videofavorite.singleton.Repo.calls.clear()

        val writer = GuestVideoFavoriteHooks.locateSingletonWriter(javaClass.classLoader!!, "$pkg.RepoCoroutine")

        assertTrue(writer != null)
        writer!!.write(7L, true)
        writer.write(7L, false)
        assertEquals(listOf("7:true", "7:false"), fixtures.privacy.videofavorite.singleton.Repo.calls)
    }

    @Test fun theSingletonWriterIsNotGuessedWhenTheInstanceFieldIsAmbiguousOrAbsent() {
        val loader = javaClass.classLoader!!
        // Two static fields of the repository's own type: which one is the instance cannot be told.
        assertNull(GuestVideoFavoriteHooks.locateSingletonWriter(loader, "fixtures.privacy.videofavorite.singleton.AmbiguousRepoCoroutine"))
        // The 9.x cache class has a static writer and no instance field of its own type, so it is not a singleton.
        assertNull(GuestVideoFavoriteHooks.locateSingletonWriter(loader))
        assertNull(GuestVideoFavoriteHooks.locateSingletonWriter(object : ClassLoader(null) {}))
    }

    @Test fun theStaticWriterWinsOverTheSingletonWriterWhenBothCouldApply() {
        val writer = GuestVideoFavoriteHooks.locateStateWriter(javaClass.classLoader!!)
        com.bilibili.community.favorite.e.calls.clear()
        writer!!.write(3L, true)
        assertEquals(listOf("3:true"), com.bilibili.community.favorite.e.calls)
    }

    @Test fun favoriteAidParsesOnlyAPositiveOidOnTheFavoriteSceneAndALoginRoute() {
        val aid = GuestVideoFavoriteHooks::favoriteAid
        assertEquals(100L, aid("activity://main/login/", FAV_SCENE, """{"oid":"100"}"""))
        assertEquals(7L, aid("bilibili://login", FAV_SCENE, """{"oid":"7","sid":"1"}"""))
        assertNull(aid("activity://main/login/", FAV_SCENE, null))
        assertNull(aid("activity://main/login/", FAV_SCENE, ""))
        assertNull(aid("activity://main/login/", FAV_SCENE, "not json"))
        assertNull(aid("activity://main/login/", FAV_SCENE, """{"sid":"1"}"""))
        assertNull(aid("activity://main/login/", FAV_SCENE, """{"oid":"abc"}"""))
        assertNull(aid("activity://main/login/", FAV_SCENE, """{"oid":"0"}"""))
        assertNull(aid("activity://main/login/", FAV_SCENE, """{"oid":"-5"}"""))
        assertNull(aid("activity://main/login/", "other.scene", """{"oid":"100"}"""))
        assertNull(aid("bilibili://video/100", FAV_SCENE, """{"oid":"100"}"""))
        assertNull(aid(null, FAV_SCENE, """{"oid":"100"}"""))
    }

    private companion object {
        const val FAV_SCENE = "player.ugc-video-detail.user-action.fav.click"
        const val STORY_PKG = "fixtures.privacy.videofavorite.storyrouter"
        val NO_ROUTER = GuestVideoFavoriteHooks.RouterNames("no.such.Router", "no.such.Request", "no.such.Response", "no.such.Code")
        val STORY_ROUTER = GuestVideoFavoriteHooks.RouterNames(
            "$STORY_PKG.StoryRouter", "$STORY_PKG.StoryRequest", "$STORY_PKG.StoryResponse", "$STORY_PKG.StoryCode")
    }
}
