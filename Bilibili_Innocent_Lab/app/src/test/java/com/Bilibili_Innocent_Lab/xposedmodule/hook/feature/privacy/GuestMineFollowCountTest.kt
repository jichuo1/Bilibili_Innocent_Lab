package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.os.Bundle
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import org.junit.Assert.*
import org.junit.Test

class GuestMineFollowCountTest {
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

    @Test fun copyUpdatesOnlyFollowingAndLeavesOriginalObjectsUntouched() {
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

    @Test fun missingOrWrongStateCopiesAreSafelyIgnored() {
        assertNull(adapter(AmbiguousState::class.java))
        val broken = BrokenState(account(), "original")
        assertNull(adapter(BrokenState::class.java)?.rewrite(broken, 1))
        assertEquals(0L, broken.account!!.s)
        assertSame(broken, adapter(BrokenState::class.java)?.rewrite(broken, 0))
        assertNull(adapter()?.rewrite(state(), -1))
    }

    private inner class Harness(initial: Any = state()) {
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

    @Test fun localChangesRefreshTheTrackedStateAndPreserveConcurrentHostChanges() {
        val h = Harness()
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

    @Test fun incomingHostStatesAreUpdatedOnlyForTheTrackedGuestSession() {
        val h = Harness()
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
        h.source.token = Any() // Session refresh invalidates the cached count immediately.
        val freshSession = state(0)
        h.set(h.flow, freshSession)
        assertSame(freshSession, h.flow.getValue())
        h.source.change(2)
        assertEquals(2L, (h.flow.getValue() as State).account!!.s)
    }

    @Test fun unsupportedBindingIsReportedWithoutChangingTheFlow() {
        val h = Harness()
        h.flow.setValue("unexpected")
        h.registrar.invoke("guest.mine.follow_count.selected", h.fragment, arrayOf(null))
        assertEquals("unavailable", h.statuses["guest_mine_follow_count_binding"])
        assertEquals("unexpected", h.flow.getValue())
    }

    @Test fun incompatibleCopyIsReportedWithoutChangingTheHostState() {
        val original = BrokenState(account(), "original")
        val h = Harness(original)
        h.source.change(1)
        assertSame(original, h.flow.getValue())
        assertEquals("unavailable", h.statuses["guest_mine_follow_count_copy"])
    }
    @Test fun repeatedPageBindingDoesNotRegisterTheSharedFlowHookTwice() {
        val h = Harness()
        h.registrar.invoke("guest.mine.follow_count.selected", h.fragment, arrayOf(null))
        assertEquals(1, h.registrar.registrationAttempts.count { it.first == h.setterId })
    }
}
