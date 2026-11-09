package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.danmaku.bili.ui.main2.mineV2.HomeUserCenterFragmentV2

class GuestMineOverlayHooksTest {
    private val statuses = linkedMapOf<String, String>()

    @Before fun resetStatuses() {
        statuses.clear()
    }

    private fun environment(registrar: PlayerPortTestRegistrar, loader: ClassLoader = javaClass.classLoader!!) =
        HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { key, value -> statuses[key] = value })

    private fun PlayerPortTestRegistrar.toggle(fragment: HomeUserCenterFragmentV2, isLogin: Boolean) =
        invoke("guest.mine.overlay.v2", fragment, arrayOf(isLogin, true)) { fragment.Vf(isLogin, true); null }

    @Test fun guestSkipsTheForceLoginOverlayAndLoggedInUsersKeepTheHostBehavior() {
        var guest = true
        val registrar = PlayerPortTestRegistrar()
        assertEquals(1, GuestMineOverlayHooks({ guest }).install(environment(registrar)))
        val fragment = HomeUserCenterFragmentV2()

        registrar.toggle(fragment, isLogin = false)
        assertEquals(0, fragment.overlayCalls)        // the overlay switch never ran
        assertEquals("seen", statuses["guest_mine_overlay_applied"])
        assertEquals("installed", statuses["guest_mine_overlay"]) // the install report is not overwritten

        guest = false
        registrar.toggle(fragment, isLogin = false)
        assertEquals(1, fragment.overlayCalls)        // a real logged-out account still gets the host's overlay
        registrar.toggle(fragment, isLogin = true)
        assertEquals(2, fragment.overlayCalls)
    }

    @Test fun installingAloneDoesNotClaimTheOverlayWasSkipped() {
        val registrar = PlayerPortTestRegistrar()
        GuestMineOverlayHooks({ false }).install(environment(registrar))
        assertEquals("installed", statuses["guest_mine_overlay"])
        registrar.toggle(HomeUserCenterFragmentV2(), isLogin = false)
        assertNull(statuses["guest_mine_overlay_applied"])
    }

    @Test fun onlyTheRealToggleIsHookedAmongShapeDecoys() {
        val registrar = PlayerPortTestRegistrar()
        GuestMineOverlayHooks({ true }).install(environment(registrar))
        // Decoys differ by return type (Xf), arity (Wf, onPageSelected) and static-ness (Yf).
        assertEquals(listOf("Vf"), registrar.hooks.values.map { it.member.name })
    }

    @Test fun missingHostClassInstallsNothingAndReportsAbsent() {
        val registrar = PlayerPortTestRegistrar()
        val hooks = GuestMineOverlayHooks({ true }, "tv.danmaku.bili.ui.main2.mineV2.NoSuchFragment")
        assertEquals(0, hooks.install(environment(registrar)))
        assertEquals("absent", statuses["guest_mine_overlay"])
        assertTrue(registrar.hooks.isEmpty())
    }

    @Test fun ambiguousShapeFailsClosedInsteadOfGuessing() {
        val registrar = PlayerPortTestRegistrar()
        val hooks = GuestMineOverlayHooks({ true }, "fixtures.privacy.mineoverlay.AmbiguousMineFragment")
        assertEquals(0, hooks.install(environment(registrar)))
        assertEquals("absent", statuses["guest_mine_overlay"])
        assertTrue(registrar.hooks.isEmpty())
    }
}
