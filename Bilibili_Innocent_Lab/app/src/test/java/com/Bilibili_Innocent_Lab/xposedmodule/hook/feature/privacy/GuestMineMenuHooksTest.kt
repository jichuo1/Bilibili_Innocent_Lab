package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.bilibili.lib.blrouter.BLRouter
import com.bilibili.lib.homepage.mine.MenuGroupV2
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GuestMineMenuHooksTest {
    private val statuses = linkedMapOf<String, String>()
    private val favoritesTarget = GuestMineMenuHooks.FAVORITES_TARGET

    @Before fun reset() {
        statuses.clear()
        BLRouter.registered.clear()
        BLRouter.lookups = 0
    }

    @After fun cleanUp() {
        BLRouter.registered.clear()
    }

    private fun environment(registrar: PlayerPortTestRegistrar, loader: ClassLoader = javaClass.classLoader!!) =
        HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { key, value -> statuses[key] = value })

    private fun PlayerPortTestRegistrar.needLogin(item: MenuGroupV2.Item) =
        invoke("guest.mine.menu.needLogin", item, emptyArray()) { item.needLogin }

    private fun PlayerPortTestRegistrar.uri(item: MenuGroupV2.Item) =
        invoke("guest.mine.menu.uri", item, emptyArray()) { item.uri }

    private fun row(uri: String, needLogin: Int = 1) = MenuGroupV2.Item("row", 1L, uri, needLogin)

    @Test fun guestPassesTheLoginCheckOnLibraryRowsOnly() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(2, GuestMineMenuHooks({ true }).install(environment(registrar)))
        assertEquals("installed", statuses["guest_mine_menu"])

        for (uri in listOf("bilibili://user_center/favourite", "bilibili://user_center/watch_later_v2",
            "bilibili://main/drawer/history", "bilibili://user_center/favourite?from_spmid=x")) {
            assertEquals(uri, 0, registrar.needLogin(row(uri)))
        }
        assertEquals("seen", statuses["guest_mine_menu_login_waived"])

        // A row outside the library keeps the host's own answer, so the wallet still asks a guest to log in.
        assertEquals(1, registrar.needLogin(row("bilibili://user_center/wallet")))
        assertEquals(0, registrar.needLogin(row("bilibili://main/drawer/offline", needLogin = 0)))
    }

    @Test fun loggedInUsersKeepTheHostAnswers() {
        var guest = false
        val registrar = PlayerPortTestRegistrar()
        GuestMineMenuHooks({ guest }).install(environment(registrar))
        BLRouter.registered += favoritesTarget

        assertEquals(1, registrar.needLogin(row("bilibili://user_center/favourite")))
        assertEquals("bilibili://user_center/favourite", registrar.uri(row("bilibili://user_center/favourite")))
        assertNull(statuses["guest_mine_menu_login_waived"])
        assertNull(statuses["guest_mine_menu_favorites_rewritten"])

        guest = true
        assertEquals(0, registrar.needLogin(row("bilibili://user_center/favourite")))
    }

    @Test fun favoritesRowGetsTheRegisteredTargetForAGuest() {
        val registrar = PlayerPortTestRegistrar()
        GuestMineMenuHooks({ true }).install(environment(registrar))
        BLRouter.registered += favoritesTarget

        assertEquals(favoritesTarget, registrar.uri(row("bilibili://user_center/favourite")))
        assertEquals("seen", statuses["guest_mine_menu_favorites_rewritten"])
        assertEquals("installed", statuses["guest_mine_menu"]) // the install report is never overwritten

        // Other library rows and unrelated rows are not rewritten.
        assertEquals("bilibili://user_center/watch_later_v2", registrar.uri(row("bilibili://user_center/watch_later_v2")))
        assertEquals("bilibili://user_center/wallet", registrar.uri(row("bilibili://user_center/wallet")))
    }

    @Test fun favoritesRowKeepsTheHostUriWhereTheTargetIsNotRegistered() {
        val registrar = PlayerPortTestRegistrar()
        GuestMineMenuHooks({ true }).install(environment(registrar))
        // BLRouter.registered stays empty: this build has no such route.
        assertEquals("bilibili://user_center/favourite", registrar.uri(row("bilibili://user_center/favourite")))
        assertNull(statuses["guest_mine_menu_favorites_rewritten"])
        // The login check is a separate gate and still waived.
        assertEquals(0, registrar.needLogin(row("bilibili://user_center/favourite")))
    }

    @Test fun theRouterIsAskedOncePerTargetNotOncePerRow() {
        val registrar = PlayerPortTestRegistrar()
        GuestMineMenuHooks({ true }).install(environment(registrar))
        BLRouter.registered += favoritesTarget
        repeat(5) { registrar.uri(row("bilibili://user_center/favourite")) }
        assertEquals(1, BLRouter.lookups)
    }

    @Test fun theSubstitutedTargetIsStillTreatedAsALibraryRow() {
        // getUri is hooked too, so needLogin may be asked about the item after its URI was already replaced.
        val registrar = PlayerPortTestRegistrar()
        GuestMineMenuHooks({ true }).install(environment(registrar))
        assertEquals(0, registrar.needLogin(row(favoritesTarget)))
    }

    @Test fun installingAloneDoesNotClaimAnyCallWasChanged() {
        val registrar = PlayerPortTestRegistrar()
        GuestMineMenuHooks({ false }).install(environment(registrar))
        registrar.needLogin(row("bilibili://user_center/favourite"))
        registrar.uri(row("bilibili://user_center/favourite"))
        assertEquals("installed", statuses["guest_mine_menu"])
        assertNull(statuses["guest_mine_menu_login_waived"])
        assertNull(statuses["guest_mine_menu_favorites_rewritten"])
    }

    @Test fun missingRouterStillWaivesTheLoginCheckButNeverRewrites() {
        val withoutRouter = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("com.bilibili.lib.blrouter.")) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        val registrar = PlayerPortTestRegistrar()
        assertEquals(2, GuestMineMenuHooks({ true }).install(environment(registrar, withoutRouter)))
        BLRouter.registered += favoritesTarget
        assertEquals("bilibili://user_center/favourite", registrar.uri(row("bilibili://user_center/favourite")))
        assertEquals(0, registrar.needLogin(row("bilibili://user_center/favourite")))
    }

    @Test fun missingItemClassInstallsNothingAndReportsAbsent() {
        val registrar = PlayerPortTestRegistrar()
        val hooks = GuestMineMenuHooks({ true }, "com.bilibili.lib.homepage.mine.NoSuchItem")
        assertEquals(0, hooks.install(environment(registrar)))
        assertEquals("absent", statuses["guest_mine_menu"])
        assertTrue(registrar.hooks.isEmpty())
    }

    @Test fun anItemWithoutNeedLoginIsNotHooked() {
        val registrar = PlayerPortTestRegistrar()
        val hooks = GuestMineMenuHooks({ true }, "fixtures.privacy.minemenu.ItemWithoutNeedLogin")
        assertEquals(0, hooks.install(environment(registrar)))
        assertEquals("absent", statuses["guest_mine_menu"])
        assertTrue(registrar.hooks.isEmpty())
    }
}
