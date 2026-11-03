package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import org.junit.Assert.*
import org.junit.Test

class GuestFollowGroupMenuRelationGuardTest {
    interface Menu { fun choose() }
    class Actions : Menu { override fun choose() = Unit }
    class Attention { @JvmField var special: Int = 0 }
    abstract class Callback { abstract fun onDataSuccess(data: Any?) }
    class MenuCallback(@JvmField val actions: Menu) : Callback() {
        val answers = mutableListOf<Attention>()
        var fail = false
        override fun onDataSuccess(data: Any?) {
            if (fail) error("host callback failed")
            answers += data as Attention
        }
    }
    class OtherCallback : Callback() { override fun onDataSuccess(data: Any?) = Unit }
    class Manager { companion object { @JvmStatic fun getRelation(key: String, uid: String, callback: Callback) = Unit } }

    private val guard = GuestFollowGroupMenuRelationGuard()
    private val port = PlayerPortTestRegistrar()
    private var policy: HookExceptionPolicy? = null
    private val registrar = object : HookRegistrar by port {
        override fun adapted(id: String, point: VersionAdapter.HookPoint, exceptionPolicy: HookExceptionPolicy,
                             block: ModernMemberHookCreator.() -> Unit) {
            policy = exceptionPolicy
            val method = Manager::class.java.getDeclaredMethod("getRelation", String::class.java, String::class.java, Callback::class.java)
            assertEquals(method.declaringClass.name, point.className)
            port.hooks[id] = PlayerPortTestRegistrar.Entry(method, ModernMemberHookCreator(method).apply(block))
        }
    }
    private fun install() = guard.install(registrar, GuestFollowGroupMenuRelationGuard.Types(
        Manager::class.java, Callback::class.java, Attention::class.java, Menu::class.java))
    private fun request(uid: String, callback: Callback, network: () -> Unit) =
        port.invoke("guest.follow_group.menu_relation", args = arrayOf("key", uid, callback)) { network(); null }

    @Test fun localMenuReceivesSpecialStateWithoutCallingTheHostNetwork() {
        install()
        assertEquals(HookExceptionPolicy.DELIVER_TO_HOST, policy)
        val actions = Actions()
        val callback = MenuCallback(actions)
        var network = 0
        guard.within(actions, 42, true, { true }) { request("42", callback) { network++ } }
        assertEquals(0, network)
        assertEquals(1, callback.answers.single().special)
        request("42", callback) { network++ } // a duplicate cannot re-run the original method
        assertEquals(0, network)
        assertEquals(1, callback.answers.size)
    }

    @Test fun delayedOrUnrecognisedCallbacksFailClosedAfterTheSessionChanges() {
        install()
        val actions = Actions()
        val callback = MenuCallback(actions)
        var valid = true
        guard.within(actions, 42, false, { valid }) { Unit }
        valid = false
        var network = 0
        request("42", callback) { network++ }
        request("42", OtherCallback()) { network++ }
        assertEquals(0, network)
        assertTrue(callback.answers.isEmpty())
    }

    @Test fun unrelatedLookupsRunNormallyBeforeAnyLocalMenuIsOpened() {
        install()
        var network = 0
        request("42", OtherCallback()) { network++ }
        assertEquals(1, network)
    }

    @Test fun hostCallbackFailureCannotRetryTheRelationRequest() {
        install()
        val actions = Actions()
        val callback = MenuCallback(actions).apply { fail = true }
        var network = 0
        guard.within(actions, 42, false, { true }) { request("42", callback) { network++ } }
        assertEquals(0, network)
        assertTrue(callback.answers.isEmpty())
    }
}
