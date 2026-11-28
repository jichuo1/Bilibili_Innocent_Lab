package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.os.Bundle
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import com.bilibili.lib.blrouter.BLRouter
import com.bilibili.lib.homepage.mine.MenuGroupV2
import org.junit.Assert.*
import org.junit.Test
import tv.danmaku.bili.ui.main2.mineV2.HomeUserCenterFragmentV2

class GuestMinePrivacyHooksTest {
    private class Account(val s: Long, val name: String, val first: List<String>?, val second: List<String>?) {
        companion object {
            @JvmStatic fun copyAccount(original: Account, replacement: List<String>?): Account =
                Account(original.s, original.name, original.first, replacement)
        }
    }

    private class State(val account: Account?, val label: String, val revision: Int) {
        companion object {
            @JvmStatic fun copyState(original: State, account: Account?, label: String?, revision: Int, mask: Int): State = State(
                if (mask and 1 != 0) original.account else account,
                if (mask and 2 != 0) original.label else requireNotNull(label),
                if (mask and 4 != 0) original.revision else revision
            )
        }
    }

    private data class LegacyAccount(
        val s: Long,
        val label: String,
        val flags: List<String>,
        val p01: Int = 1, val p02: Int = 2, val p03: Int = 3, val p04: Int = 4, val p05: Int = 5,
        val p06: Int = 6, val p07: Int = 7, val p08: Int = 8, val p09: Int = 9, val p10: Int = 10,
        val p11: Int = 11, val p12: Int = 12, val p13: Int = 13, val p14: Int = 14, val p15: Int = 15,
        val p16: Int = 16, val p17: Int = 17, val p18: Int = 18, val p19: Int = 19, val p20: Int = 20,
        val p21: Int = 21, val p22: Int = 22, val p23: Int = 23, val p24: Int = 24, val p25: Int = 25,
        val p26: Int = 26, val p27: Int = 27, val p28: Int = 28, val p29: Int = 29, val p30: Int = 30,
        val p31: Int = 31
    )
    private data class LegacyState(val account: LegacyAccount?, val title: String)

    private class BrokenState(val account: Account?, val label: String) {
        companion object {
            @JvmStatic fun copyState(original: BrokenState, account: Account?, mask: Int): BrokenState =
                BrokenState(if (mask and 1 != 0) original.account else account, "changed")
        }
    }

    private class AmbiguousState(val first: Account?, val second: Account?)

    private class FakeFlow(initial: Any) {
        private var value: Any = initial
        var conflict: ((Any) -> Any)? = null
        fun getValue(): Any = value
        fun setValue(value: Any) { this.value = value }
        fun compareAndSet(expected: Any, replacement: Any): Boolean {
            conflict?.let { change -> conflict = null; value = change(value); return false }
            if (value !== expected) return false
            value = replacement
            return true
        }
    }

    private class ViewModel(val flow: FakeFlow, val other: FakeFlow)
    private class Fragment(private val model: ViewModel) {
        fun model(): ViewModel = model
        fun onViewCreated(view: View?, saved: Bundle?) = Unit
        fun onPageSelected(extras: Map<String, Any>?) = Unit
    }

    private class Source : GuestFollowCountSource {
        var token: Any = Any()
        var count: Int = 0
        private var observer: ((GuestFollowCountSource.Snapshot) -> Unit)? = null
        override fun observe(callback: (GuestFollowCountSource.Snapshot) -> Unit) { observer = callback }
        override fun read(callback: (GuestFollowCountSource.Snapshot) -> Unit) = callback(snapshot())
        override fun accepts(snapshot: GuestFollowCountSource.Snapshot): Boolean = snapshot.token === token
        fun change(value: Int) { count = value; observer?.invoke(snapshot()) }
        private fun snapshot() = GuestFollowCountSource.Snapshot(count, token)
    }

    private fun account(count: Long = 0) = Account(count, "visitor", listOf("first"), listOf("second"))
    private fun state(count: Long = 0, label: String = "unchanged", revision: Int = 8) = State(account(count), label, revision)
    private fun adapter(type: Class<*> = State::class.java): GuestMineFollowStateAdapter? =
        GuestMineFollowStateAdapter.resolve(type, requireNotNull(GuestMineFollowStateAdapter.accountPlan(Account::class.java)))

    private inner class FollowHarness(initial: Any = state()) {
        val registrar = PlayerPortTestRegistrar()
        val source = Source()
        var guest = true
        val loader = javaClass.classLoader!!
        val statuses = mutableMapOf<String, String>()
        val env = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { key, value -> statuses[key] = value })
        val flow = FakeFlow(initial)
        val other = FakeFlow("unrelated")
        val fragment = Fragment(ViewModel(flow, other))
        val setterId = "guest.mine.follow_count.flow.${FakeFlow::class.java.name}"
        init {
            GuestMineFollowCount(source, { guest }, GuestMineFollowCount.Names(
                Fragment::class.java.name, ViewModel::class.java.name, Account::class.java.name, FakeFlow::class.java.name
            )).install(env)
            registrar.invoke("guest.mine.follow_count.created", fragment, arrayOf(null, null))
        }
        fun set(flow: FakeFlow, next: Any) = registrar.invoke(setterId, flow, arrayOf(next)) { args ->
            flow.setValue(requireNotNull(args[0])); null
        }
    }

    @Test
    fun verifiesGuestMineFollowCountRewriteFlowAndSessionIsolation() {
        run {
            val original = state()
            val result = requireNotNull(adapter()?.rewrite(original, 3)) as State
            assertNotSame(original, result)
            assertNotSame(original.account, result.account)
            assertEquals(0L, original.account!!.s)
            assertEquals(3L, result.account!!.s)
            assertSame(original.account.name, result.account.name)
            assertSame(original.account.first, result.account.first)
            assertSame(original.account.second, result.account.second)
            assertSame(original.label, result.label)
            assertEquals(original.revision, result.revision)
            assertSame(result, adapter()?.rewrite(result, 3))
        }
        run {
            val original = LegacyAccount(4, "legacy", listOf("same"), p01 = 91, p31 = 93)
            val state = LegacyState(original, "title")
            val plan = requireNotNull(GuestMineFollowStateAdapter.accountPlan(LegacyAccount::class.java))
            assertEquals(2, plan.defaultMasks)
            val a = requireNotNull(GuestMineFollowStateAdapter.resolve(LegacyState::class.java, plan))
            val result = requireNotNull(a.rewrite(state, 7)) as LegacyState
            assertEquals(original.copy(s = 7), result.account)
            assertSame(original.flags, result.account!!.flags)
            assertSame(state.title, result.title)
            assertEquals(4L, original.s)
        }
        run {
            assertNull(adapter(AmbiguousState::class.java))
            val broken = BrokenState(account(), "original")
            assertNull(adapter(BrokenState::class.java)?.rewrite(broken, 1))
            assertEquals(0L, broken.account!!.s)
            assertSame(broken, adapter(BrokenState::class.java)?.rewrite(broken, 0))
            assertNull(adapter()?.rewrite(state(), -1))
        }
        run {
            val h = FollowHarness()
            h.source.change(1)
            assertEquals(1L, (h.flow.getValue() as State).account!!.s)
            h.flow.conflict = { current -> (current as State).let { State(it.account, "host update", 99) } }
            h.source.change(2)
            val latest = h.flow.getValue() as State
            assertEquals(2L, latest.account!!.s)
            assertEquals("host update", latest.label)
            assertEquals(99, latest.revision)
            assertEquals("seen", h.statuses["guest_mine_follow_count_applied"])
        }
        run {
            val h = FollowHarness()
            h.source.change(1)
            val incoming = state(0, "fresh")
            h.set(h.flow, incoming)
            assertEquals(0L, incoming.account!!.s)
            assertEquals(1L, (h.flow.getValue() as State).account!!.s)
            h.set(h.other, "other value")
            assertEquals("other value", h.other.getValue())
            h.guest = false
            val signedIn = state(7)
            h.set(h.flow, signedIn)
            assertSame(signedIn, h.flow.getValue())
            h.guest = true
            h.source.token = Any()
            val freshSession = state(0)
            h.set(h.flow, freshSession)
            assertSame(freshSession, h.flow.getValue())
            h.source.change(2)
            assertEquals(2L, (h.flow.getValue() as State).account!!.s)
        }
        run {
            val h = FollowHarness()
            h.flow.setValue("unexpected")
            h.registrar.invoke("guest.mine.follow_count.selected", h.fragment, arrayOf(null))
            assertEquals("unavailable", h.statuses["guest_mine_follow_count_binding"])
            assertEquals("unexpected", h.flow.getValue())
        }
        run {
            val original = BrokenState(account(), "original")
            val h = FollowHarness(original)
            h.source.change(1)
            assertSame(original, h.flow.getValue())
            assertEquals("unavailable", h.statuses["guest_mine_follow_count_copy"])
        }
        run {
            val h = FollowHarness()
            h.registrar.invoke("guest.mine.follow_count.selected", h.fragment, arrayOf(null))
            assertEquals(1, h.registrar.registrationAttempts.count { it.first == h.setterId })
        }
    }

    @Test
    fun verifiesGuestMineMenuRoutingAndOverlayGuards() {
        val favoritesTarget = GuestMineMenuHooks.FAVORITES_TARGET
        fun row(uri: String, needLogin: Int = 1) = MenuGroupV2.Item("row", 1L, uri, needLogin)
        fun PlayerPortTestRegistrar.needLogin(item: MenuGroupV2.Item) =
            invoke("guest.mine.menu.needLogin", item, emptyArray()) { item.needLogin }
        fun PlayerPortTestRegistrar.uri(item: MenuGroupV2.Item) =
            invoke("guest.mine.menu.uri", item, emptyArray()) { item.uri }
        fun PlayerPortTestRegistrar.toggle(fragment: HomeUserCenterFragmentV2, isLogin: Boolean) =
            invoke("guest.mine.overlay.v2", fragment, arrayOf(isLogin, true)) { fragment.Vf(isLogin, true); null }

        fun withMenuEnv(loader: ClassLoader = javaClass.classLoader!!, block: (MutableMap<String, String>, HookEnvironment, PlayerPortTestRegistrar) -> Unit) {
            val statuses = linkedMapOf<String, String>()
            BLRouter.registered.clear()
            BLRouter.lookups = 0
            val registrar = PlayerPortTestRegistrar()
            val env = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
                { _, _ -> }, { _, _ -> }, { key, value -> statuses[key] = value })
            try {
                block(statuses, env, registrar)
            } finally {
                BLRouter.registered.clear()
            }
        }

        // Menu hooks cases.
        withMenuEnv { statuses, env, registrar ->
            assertEquals(2, GuestMineMenuHooks({ true }).install(env))
            assertEquals("installed", statuses["guest_mine_menu"])
            for (uri in listOf("bilibili://user_center/favourite", "bilibili://user_center/watch_later_v2",
                "bilibili://main/drawer/history", "bilibili://user_center/favourite?from_spmid=x")) {
                assertEquals(uri, 0, registrar.needLogin(row(uri)))
            }
            assertEquals("seen", statuses["guest_mine_menu_login_waived"])
            assertEquals(1, registrar.needLogin(row("bilibili://user_center/wallet")))
            assertEquals(0, registrar.needLogin(row("bilibili://main/drawer/offline", needLogin = 0)))
        }
        withMenuEnv { statuses, env, registrar ->
            var guest = false
            GuestMineMenuHooks({ guest }).install(env)
            BLRouter.registered += favoritesTarget
            assertEquals(1, registrar.needLogin(row("bilibili://user_center/favourite")))
            assertEquals("bilibili://user_center/favourite", registrar.uri(row("bilibili://user_center/favourite")))
            assertNull(statuses["guest_mine_menu_login_waived"])
            assertNull(statuses["guest_mine_menu_favorites_rewritten"])
            guest = true
            assertEquals(0, registrar.needLogin(row("bilibili://user_center/favourite")))
        }
        withMenuEnv { statuses, env, registrar ->
            GuestMineMenuHooks({ true }).install(env)
            BLRouter.registered += favoritesTarget
            assertEquals(favoritesTarget, registrar.uri(row("bilibili://user_center/favourite")))
            assertEquals("seen", statuses["guest_mine_menu_favorites_rewritten"])
            assertEquals("installed", statuses["guest_mine_menu"])
            assertEquals("bilibili://user_center/watch_later_v2", registrar.uri(row("bilibili://user_center/watch_later_v2")))
            assertEquals("bilibili://user_center/wallet", registrar.uri(row("bilibili://user_center/wallet")))
        }
        withMenuEnv { statuses, env, registrar ->
            GuestMineMenuHooks({ true }).install(env)
            assertEquals("bilibili://user_center/favourite", registrar.uri(row("bilibili://user_center/favourite")))
            assertNull(statuses["guest_mine_menu_favorites_rewritten"])
            assertEquals(0, registrar.needLogin(row("bilibili://user_center/favourite")))
        }
        withMenuEnv { _, env, registrar ->
            GuestMineMenuHooks({ true }).install(env)
            BLRouter.registered += favoritesTarget
            repeat(5) { registrar.uri(row("bilibili://user_center/favourite")) }
            assertEquals(1, BLRouter.lookups)
        }
        withMenuEnv { _, env, registrar ->
            GuestMineMenuHooks({ true }).install(env)
            assertEquals(0, registrar.needLogin(row(favoritesTarget)))
        }
        withMenuEnv { statuses, env, registrar ->
            GuestMineMenuHooks({ false }).install(env)
            registrar.needLogin(row("bilibili://user_center/favourite"))
            registrar.uri(row("bilibili://user_center/favourite"))
            assertEquals("installed", statuses["guest_mine_menu"])
            assertNull(statuses["guest_mine_menu_login_waived"])
            assertNull(statuses["guest_mine_menu_favorites_rewritten"])
        }
        val withoutRouter = TestRenamingClassLoader(parent = javaClass.classLoader!!, hiddenPrefixes = setOf("com.bilibili.lib.blrouter."))
        withMenuEnv(withoutRouter) { _, env, registrar ->
            assertEquals(2, GuestMineMenuHooks({ true }).install(env))
            BLRouter.registered += favoritesTarget
            assertEquals("bilibili://user_center/favourite", registrar.uri(row("bilibili://user_center/favourite")))
            assertEquals(0, registrar.needLogin(row("bilibili://user_center/favourite")))
        }
        withMenuEnv { statuses, env, registrar ->
            assertEquals(0, GuestMineMenuHooks({ true }, "com.bilibili.lib.homepage.mine.NoSuchItem").install(env))
            assertEquals("absent", statuses["guest_mine_menu"])
            assertTrue(registrar.hooks.isEmpty())
        }
        withMenuEnv { statuses, env, registrar ->
            assertEquals(0, GuestMineMenuHooks({ true }, "fixtures.privacy.minemenu.ItemWithoutNeedLogin").install(env))
            assertEquals("absent", statuses["guest_mine_menu"])
            assertTrue(registrar.hooks.isEmpty())
        }

        // Overlay hooks cases.
        withMenuEnv { statuses, env, registrar ->
            var guest = true
            assertEquals(1, GuestMineOverlayHooks({ guest }).install(env))
            val fragment = HomeUserCenterFragmentV2()
            registrar.toggle(fragment, isLogin = false)
            assertEquals(0, fragment.overlayCalls)
            assertEquals("seen", statuses["guest_mine_overlay_applied"])
            assertEquals("installed", statuses["guest_mine_overlay"])
            guest = false
            registrar.toggle(fragment, isLogin = false)
            assertEquals(1, fragment.overlayCalls)
            registrar.toggle(fragment, isLogin = true)
            assertEquals(2, fragment.overlayCalls)
        }
        withMenuEnv { statuses, env, registrar ->
            GuestMineOverlayHooks({ false }).install(env)
            assertEquals("installed", statuses["guest_mine_overlay"])
            registrar.toggle(HomeUserCenterFragmentV2(), isLogin = false)
            assertNull(statuses["guest_mine_overlay_applied"])
        }
        withMenuEnv { _, env, registrar ->
            GuestMineOverlayHooks({ true }).install(env)
            assertEquals(listOf("Vf"), registrar.hooks.values.map { it.member.name })
        }
        withMenuEnv { statuses, env, registrar ->
            assertEquals(0, GuestMineOverlayHooks({ true }, "tv.danmaku.bili.ui.main2.mineV2.NoSuchFragment").install(env))
            assertEquals("absent", statuses["guest_mine_overlay"])
            assertTrue(registrar.hooks.isEmpty())
        }
        withMenuEnv { statuses, env, registrar ->
            assertEquals(0, GuestMineOverlayHooks({ true }, "fixtures.privacy.mineoverlay.AmbiguousMineFragment").install(env))
            assertEquals("absent", statuses["guest_mine_overlay"])
            assertTrue(registrar.hooks.isEmpty())
        }
    }
}
