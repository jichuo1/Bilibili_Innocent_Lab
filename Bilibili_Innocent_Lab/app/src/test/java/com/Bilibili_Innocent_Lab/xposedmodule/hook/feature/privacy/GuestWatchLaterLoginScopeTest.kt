package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.bilibili.app.comm.list.common.router.LoginScopeKt
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GuestWatchLaterLoginScopeTest {
    private val statuses = linkedMapOf<String, String>()
    private val registrar = PlayerPortTestRegistrar()

    private fun environment() = javaClass.classLoader!!.let { loader ->
        HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { key, value -> statuses[key] = value })
    }

    private fun login(action: () -> Any?): Any? {
        val args = arrayOf<Any?>(false, null, null, 0L, action)
        return registrar.invoke("guest.watch_later.login_scope", null, args) {
            LoginScopeKt.loginRequire(false, null, null, 0L, action)
        }
    }

    @Before fun reset() {
        LoginScopeKt.loggedIn = false
        LoginScopeKt.loginRoutes = 0
        statuses.clear()
    }

    @Test fun guestRunsTheHostsOwnWatchLaterActionInsteadOfTheLoginPage() {
        var guest = true
        GuestWatchLaterLoginScope { guest }.install(environment())
        val action = tv.danmaku.bili.ui.watchlater.widget.b()

        assertNull(login(action))                // the helper's "ran at once" answer: no sign-in observer
        assertEquals(1, action.runs)
        assertEquals(0, LoginScopeKt.loginRoutes)
        assertEquals("seen", statuses["guest_watch_later_login_scope_applied"])

        // A logged-in user goes through the host helper untouched.
        guest = false
        LoginScopeKt.loggedIn = true
        login(action)
        assertEquals(2, action.runs)
        assertEquals(0, LoginScopeKt.loginRoutes)
    }

    @Test fun otherActionsKeepTheLoginGateForGuests() {
        GuestWatchLaterLoginScope { true }.install(environment())
        var ran = false
        val report: () -> Any? = { ran = true }  // a report/appeal action declared outside the watch-later widget package
        assertNotNull(login(report))
        assertFalse(ran)
        assertEquals(1, LoginScopeKt.loginRoutes)
        assertNull(statuses["guest_watch_later_login_scope_applied"])
    }

    @Test fun onlyClassesDeclaredDirectlyInTheWidgetPackageAreWatchLaterActions() {
        assertTrue(GuestWatchLaterLoginScope.isWatchLaterAction(tv.danmaku.bili.ui.watchlater.widget.b::class.java))
        assertFalse(GuestWatchLaterLoginScope.isWatchLaterAction(Runnable::class.java))
        assertFalse(GuestWatchLaterLoginScope.isWatchLaterAction(Nested.Inner::class.java))
    }

    @Test fun gateIsTheStaticHelperNotItsDefaultBridge() {
        val gate = GuestWatchLaterLoginScope.locateGate(LoginScopeKt::class.java, kotlin.jvm.functions.Function0::class.java)
        assertEquals("loginRequire", gate?.name)
        assertEquals(5, gate?.parameterCount)
        assertNull(GuestWatchLaterLoginScope.locateGate(Nested::class.java, kotlin.jvm.functions.Function0::class.java))
    }

    class Nested { class Inner }
}
