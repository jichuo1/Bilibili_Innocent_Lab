package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import fixtures.privacy.videofavorite.storyrouter.StoryCode
import fixtures.privacy.videofavorite.storyrouter.StoryFragment
import fixtures.privacy.videofavorite.storyrouter.StoryRequest
import fixtures.privacy.videofavorite.storyrouter.StoryResponse
import fixtures.privacy.videofavorite.storyrouter.StoryRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import java.io.IOException
import org.junit.Before
import org.junit.Test

class GuestFavoritesRouteHooksTest {
    private val statuses = linkedMapOf<String, String>()
    private val opened = mutableListOf<Any?>()
    private var kntr = false
    private var canOpen = true
    private var folderPolicy: HookExceptionPolicy? = null
    private val context = Any()
    private val fragment = StoryFragment()
    private val openedFolders = mutableListOf<Triple<Any?, String, Long>>()

    @Before fun reset() {
        statuses.clear()
        opened.clear()
        openedFolders.clear()
        StoryRouter.routed.clear()
        kntr = false
        canOpen = true
        folderPolicy = null
    }

    private fun environment(registrar: PlayerPortTestRegistrar, loader: ClassLoader = javaClass.classLoader!!): HookEnvironment {
        val recording = object : HookRegistrar by registrar {
            override fun adapted(id: String, point: VersionAdapter.HookPoint, exceptionPolicy: HookExceptionPolicy,
                                 block: ModernMemberHookCreator.() -> Unit) {
                assertEquals("guest.favorites.folder_route", id)
                folderPolicy = exceptionPolicy
                val method = StoryRouter::class.java.getDeclaredMethod("routeTo", StoryRequest::class.java, StoryFragment::class.java)
                registrar.hooks[id] = PlayerPortTestRegistrar.Entry(method, ModernMemberHookCreator(method).apply(block))
            }
        }
        return HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), recording,
            { _, _ -> }, { _, _ -> }, { key, value -> statuses[key] = value })
    }

    private fun hooks(guest: () -> Boolean = { true }, names: GuestVideoFavoriteHooks.RouterNames = ROUTER) =
        GuestFavoritesRouteHooks(guest, names, { target -> opened += target; canOpen }, { kntr })

    /** Runs `BLRouter.routeTo(request, context)` through the hook; the fixture router runs only when the hook did not answer. */
    private fun PlayerPortTestRegistrar.routeTo(uri: String, with: Any? = context) = StoryRequest(uri).let { request ->
        invoke("guest.favorites.route", null, arrayOf(request, with)) {
            StoryRouter.routeTo(request, null as android.content.Context?)
        } as StoryResponse
    }

    /** Runs `BLRouter.routeTo(request, fragment)`, the overload a folder card uses, through the folder hook. */
    private fun PlayerPortTestRegistrar.routeToFragment(uri: String, with: Any? = fragment) = StoryRequest(uri).let { request ->
        invoke("guest.favorites.folder_route", null, arrayOf(request, with)) {
            StoryRouter.routeTo(request, null as StoryFragment?)
        } as StoryResponse
    }

    private fun folderHooks(guest: () -> Boolean = { true }, canOpenFolder: Boolean = true) =
        GuestFavoritesRouteHooks(guest, FRAGMENT_ROUTER, { true }, { kntr }, { target, uri, id ->
            openedFolders += Triple(target, uri, id); canOpenFolder })

    @Test fun aGuestTappingAFolderCardOpensItsDetailPageAndIsAnsweredAsHandled() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(2, folderHooks().install(environment(registrar)))
        assertEquals("installed", statuses["guest_favorites_folder_route"])
        val uri = "bilibili://music/playlist/detail/162385528615728?type=11"
        val response = registrar.routeToFragment(uri)
        assertEquals(StoryCode.OK, response.code)
        assertEquals(listOf(Triple<Any?, String, Long>(fragment, uri, 162385528615728L)), openedFolders)
        assertTrue(StoryRouter.routed.isEmpty()) // the router, and so the login rewrite, never ran
        assertEquals("seen", statuses["guest_favorites_folder_route_opened"])
    }

    @Test fun sharedFragmentRoutePropagatesHostErrorsWithoutRetrying() {
        val registrar = PlayerPortTestRegistrar()
        folderHooks().install(environment(registrar))
        assertEquals(HookExceptionPolicy.DELIVER_TO_HOST, folderPolicy)
        var calls = 0
        assertThrows(IOException::class.java) {
            registrar.invoke("guest.favorites.folder_route", null,
                arrayOf(StoryRequest("bilibili://relation/create-group"), fragment)) {
                calls++
                throw IOException("host route failed")
            }
        }
        assertEquals(1, calls)
    }
    @Test fun onlyPlaylistDetailRoutesAreTakenOverByTheFolderHook() {
        val registrar = PlayerPortTestRegistrar()
        folderHooks().install(environment(registrar))
        for (uri in listOf("bilibili://video/100", "bilibili://login", "bilibili://music/playlist/detail/0",
            "bilibili://music/playlist/detail/abc", "bilibili://music/playlist/detail/", "bilibili://music/other/1",
            "bilibili://user_center/favourite")) {
            StoryRouter.routed.clear()
            assertEquals(uri, StoryCode.FOUND_STUB, registrar.routeToFragment(uri).code)
            assertEquals(listOf(uri), StoryRouter.routed)
        }
        assertTrue(openedFolders.isEmpty())
    }

    @Test fun aLoggedInUserKeepsTheHostRouteForFolderCards() {
        val registrar = PlayerPortTestRegistrar()
        folderHooks(guest = { false }).install(environment(registrar))
        assertEquals(StoryCode.FOUND_STUB, registrar.routeToFragment("bilibili://music/playlist/detail/5").code)
        assertTrue(openedFolders.isEmpty())
        assertNull(statuses["guest_favorites_folder_route_opened"])
    }

    @Test fun whenTheDetailPageCannotBeOpenedTheHostRouteStillRuns() {
        val registrar = PlayerPortTestRegistrar()
        folderHooks(canOpenFolder = false).install(environment(registrar))
        assertEquals(StoryCode.FOUND_STUB, registrar.routeToFragment("bilibili://music/playlist/detail/5").code)
        assertEquals(1, openedFolders.size)
        assertNull(statuses["guest_favorites_folder_route_opened"])
    }

    @Test fun aBuildWithoutTheFragmentOverloadStillGetsTheFavoritesRoute() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(1, hooks().install(environment(registrar)))
        assertEquals("absent", statuses["guest_favorites_folder_route"])
        assertEquals("installed", statuses["guest_favorites_route"])
    }

    @Test fun folderIdsAreReadFromThePlaylistDetailLink() {
        assertEquals(7L, GuestFavoritesRouteHooks.folderIdOf("bilibili://music/playlist/detail/7"))
        assertEquals(7L, GuestFavoritesRouteHooks.folderIdOf("bilibili://music/playlist/detail/7/?type=11"))
        assertNull(GuestFavoritesRouteHooks.folderIdOf("bilibili://music/playlist/detail/-1"))
        assertNull(GuestFavoritesRouteHooks.folderIdOf("bilibili://music/playlist/detail/7/8"))
        assertNull(GuestFavoritesRouteHooks.folderIdOf(null))
        assertFalse(GuestFavoritesRouteHooks.openFolder(null, "bilibili://music/playlist/detail/7", 7L))
    }

    @Test fun aGuestTappingFavoritesOpensTheNativePageAndIsAnsweredAsHandled() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(1, hooks().install(environment(registrar)))
        assertEquals("installed", statuses["guest_favorites_route"])

        for (uri in listOf("bilibili://user_center/favourite", "bilibili://main/favorite",
            "bilibili://user_center/favourite?from_spmid=x", "bilibili://user_center/favorite_v2/favorite/folders/")) {
            opened.clear()
            StoryRouter.routed.clear()
            val response = registrar.routeTo(uri)
            assertEquals(uri, StoryCode.OK, response.code)
            assertEquals(uri, listOf<Any?>(context), opened)
            assertTrue(uri, StoryRouter.routed.isEmpty()) // the router, and so the login rewrite, never ran
        }
        assertEquals("seen", statuses["guest_favorites_route_opened"])
    }

    @Test fun otherRoutesAreLeftToTheHost() {
        val registrar = PlayerPortTestRegistrar()
        hooks().install(environment(registrar))
        for (uri in listOf("bilibili://login", "bilibili://user_center/watch_later_v2", "bilibili://main/drawer/history",
            "bilibili://video/100", "bilibili://user_center/wallet")) {
            StoryRouter.routed.clear()
            assertEquals(uri, StoryCode.FOUND_STUB, registrar.routeTo(uri).code)
            assertEquals(listOf(uri), StoryRouter.routed)
        }
        assertTrue(opened.isEmpty())
        assertNull(statuses["guest_favorites_route_opened"])
    }

    @Test fun aBuildWithTheKntrRouteIsLeftToTheMenuHook() {
        kntr = true
        val registrar = PlayerPortTestRegistrar()
        hooks().install(environment(registrar))
        assertEquals(StoryCode.FOUND_STUB, registrar.routeTo("bilibili://user_center/favourite").code)
        assertTrue(opened.isEmpty())
    }

    @Test fun aLoggedInUserKeepsTheHostRoute() {
        val registrar = PlayerPortTestRegistrar()
        hooks(guest = { false }).install(environment(registrar))
        assertEquals(StoryCode.FOUND_STUB, registrar.routeTo("bilibili://user_center/favourite").code)
        assertTrue(opened.isEmpty())
        assertNull(statuses["guest_favorites_route_opened"])
    }

    @Test fun whenThePageCannotBeOpenedTheHostRouteStillRuns() {
        canOpen = false
        val registrar = PlayerPortTestRegistrar()
        hooks().install(environment(registrar))
        assertEquals(StoryCode.FOUND_STUB, registrar.routeTo("bilibili://user_center/favourite").code)
        assertEquals(listOf(context), opened)
        assertNull(statuses["guest_favorites_route_opened"])
    }

    @Test fun aNullContextIsPassedOnSoTheProductionOpenerCanRefuseIt() {
        val registrar = PlayerPortTestRegistrar()
        hooks().install(environment(registrar))
        registrar.routeTo("bilibili://user_center/favourite", with = null)
        assertEquals(listOf<Any?>(null), opened)
        assertFalse(GuestFavoritesRouteHooks.openFavorites(null))
    }

    @Test fun anUnknownRouterInstallsNothingAndReportsAbsent() {
        val registrar = PlayerPortTestRegistrar()
        val none = GuestVideoFavoriteHooks.RouterNames("no.Such.Router", "no.Such.Request", "no.Such.Response", "no.Such.Code")
        assertEquals(0, hooks(names = none).install(environment(registrar)))
        assertEquals("absent", statuses["guest_favorites_route"])
        assertTrue(registrar.hooks.isEmpty())
    }

    private companion object {
        const val PKG = "fixtures.privacy.videofavorite.storyrouter"
        val ROUTER = GuestVideoFavoriteHooks.RouterNames("$PKG.StoryRouter", "$PKG.StoryRequest", "$PKG.StoryResponse", "$PKG.StoryCode")
        val FRAGMENT_ROUTER = ROUTER.copy(fragment = "$PKG.StoryFragment")
    }
}
