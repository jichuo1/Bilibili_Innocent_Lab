package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.bilibili.bplus.followinglist.model.GuestDynamicFeedItemFixture
import com.bilibili.bplus.followinglist.model.GuestDynamicLoginRowFixture
import com.bilibili.bplus.followinglist.model.GuestDynamicNoCtorItemFixture
import com.bilibili.bplus.followinglist.other.GuestDynamicForeignStateFixture
import com.bilibili.bplus.followinglist.page.nologin.GuestDynamicInheritedStateFixture
import com.bilibili.bplus.followinglist.page.nologin.GuestDynamicNotLoginStateFixture
import com.bilibili.bplus.followinglist.page.nologin.GuestDynamicPairValueFixture
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestDynamicLoginRowFilterTest {
    private class Harness(
        var guest: Boolean = true,
        stateFailure: Boolean = false
    ) {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        val statuses = mutableMapOf<String, String>()
        val loader = GuestDynamicLoginRowFilterTest::class.java.classLoader!!
        val env = HookEnvironment(
            "tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { key, value -> statuses[key] = value },
            runtimeEvidence = { _, stage, _ -> stages += stage }
        )
        val liveData = MediatorLiveData<Any>()

        init {
            val filter = GuestDynamicLoginRowFilter(
                enabled = true,
                isGuest = {
                    check(!stateFailure)
                    guest
                },
                liveDataClass = LiveData::class.java
            )
            check(filter.install(env))
        }

        /** 模拟宿主对已认证 LiveData 的发布；返回观察者将要看到的值。 */
        fun publish(value: Any?): Any? {
            registrar.invoke(
                "guest_browsing.dynamic-login-row-data",
                receiver = liveData, args = arrayOf(value)
            )
            return value
        }
    }

    fun learnsFromHeadAndRemovesOnlyTheLoginRow() {
        val h = Harness()
        val login = GuestDynamicLoginRowFixture()
        val feed = GuestDynamicFeedItemFixture("t", "c", "u")
        val list = mutableListOf<Any>(login, feed)
        h.publish(GuestDynamicPairValueFixture(list, GuestDynamicNotLoginStateFixture(true)))
        assertEquals(listOf<Any>(feed), list)
        assertEquals("seen", h.statuses["guest_browsing_dynamic_login_row_learned"])
        assertEquals("seen", h.statuses["guest_browsing_dynamic_login_row_applied"])
        assertTrue(FeatureRuntimeStage.APPLIED in h.stages)
    }

    fun inheritedDataStatusStateAuthenticatesOn916Shape() {
        // 9.16.1：结果 state 在 nologin 包内，但 DataStatus 字段声明在父类（Qi.b，非 nologin 包）。
        // 只扫运行时类自身的 getDeclaredFields 会漏判，导致 learned/applied 永不触发、登录条不过滤。
        val h = Harness()
        val login = GuestDynamicLoginRowFixture()
        val feed = GuestDynamicFeedItemFixture("t", "c", "u")
        val list = mutableListOf<Any>(login, feed)
        h.publish(GuestDynamicPairValueFixture(list, GuestDynamicInheritedStateFixture()))
        assertEquals(listOf<Any>(feed), list)
        assertEquals("seen", h.statuses["guest_browsing_dynamic_login_row_learned"])
        assertEquals("seen", h.statuses["guest_browsing_dynamic_login_row_applied"])
        assertTrue(FeatureRuntimeStage.APPLIED in h.stages)
    }

    fun inheritedDataStatusCachesAuthenticationForLaterValues() {
        // 认证后的 LiveData 对后续（含 foreign state 形状）发布仍按已学习类过滤。
        val h = Harness()
        h.publish(GuestDynamicPairValueFixture(
            mutableListOf<Any>(GuestDynamicLoginRowFixture()),
            GuestDynamicInheritedStateFixture()
        ))
        val feed = GuestDynamicFeedItemFixture("a", "b", "c")
        val list = mutableListOf<Any>(GuestDynamicLoginRowFixture(), feed)
        h.publish(GuestDynamicPairValueFixture(list, GuestDynamicNotLoginStateFixture(false)))
        assertEquals(listOf<Any>(feed), list)
        assertTrue(list.all { it is GuestDynamicFeedItemFixture })
    }

    fun removesEveryLearnedRowIncludingDuplicates() {
        val h = Harness()
        h.publish(GuestDynamicPairValueFixture(
            mutableListOf<Any>(GuestDynamicLoginRowFixture()), GuestDynamicNotLoginStateFixture(true)
        ))
        val list = mutableListOf<Any>(
            GuestDynamicLoginRowFixture(), GuestDynamicFeedItemFixture("a", "b", "c"),
            GuestDynamicLoginRowFixture(), GuestDynamicFeedItemFixture("d", "e", "f")
        )
        h.publish(GuestDynamicPairValueFixture(list, GuestDynamicNotLoginStateFixture(false)))
        assertEquals(2, list.size)
        assertTrue(list.all { it is GuestDynamicFeedItemFixture })
    }

    fun loggedInSessionsKeepTheOriginalList() {
        val h = Harness()
        h.guest = false
        val list = mutableListOf<Any>(GuestDynamicLoginRowFixture())
        h.publish(GuestDynamicPairValueFixture(list, GuestDynamicNotLoginStateFixture(true)))
        assertEquals(1, list.size)
        assertNull(h.statuses["guest_browsing_dynamic_login_row_learned"])
        assertNull(h.statuses["guest_browsing_dynamic_login_row_applied"])
    }

    fun headShapeMismatchSkipsLearningUntilALoginRowAppears() {
        val h = Harness()
        // 首个认证值的头元素是推荐流 item：形状不符，本次不学习、不动列表。
        val first = mutableListOf<Any>(GuestDynamicFeedItemFixture("t", "c", "u"))
        h.publish(GuestDynamicPairValueFixture(first, GuestDynamicNotLoginStateFixture(true)))
        assertEquals(1, first.size)
        assertNull(h.statuses["guest_browsing_dynamic_login_row_learned"])
        // 后续值的头部出现登录引导 item 时学习并过滤。
        val second = mutableListOf<Any>(
            GuestDynamicLoginRowFixture(), GuestDynamicFeedItemFixture("t2", "c2", "u2")
        )
        h.publish(GuestDynamicPairValueFixture(second, GuestDynamicNotLoginStateFixture(false)))
        assertEquals(1, second.size)
        assertTrue(second.single() is GuestDynamicFeedItemFixture)
        assertEquals("seen", h.statuses["guest_browsing_dynamic_login_row_learned"])
    }

    fun itemWithoutNoArgConstructorIsRejectedByShape() {
        val h = Harness()
        val list = mutableListOf<Any>(GuestDynamicNoCtorItemFixture(true))
        h.publish(GuestDynamicPairValueFixture(list, GuestDynamicNotLoginStateFixture(true)))
        assertEquals(1, list.size)
        assertNull(h.statuses["guest_browsing_dynamic_login_row_learned"])
    }

    fun foreignStateDoesNotAuthenticateTheLiveData() {
        val h = Harness()
        val list = mutableListOf<Any>(GuestDynamicLoginRowFixture())
        h.publish(GuestDynamicPairValueFixture(list, GuestDynamicForeignStateFixture()))
        assertEquals(1, list.size)
        assertNull(h.statuses["guest_browsing_dynamic_login_row_learned"])
        // 其他 LiveData 发布 nologin state 才会认证并过滤。
        val again = mutableListOf<Any>(GuestDynamicLoginRowFixture())
        h.publish(GuestDynamicPairValueFixture(again, GuestDynamicNotLoginStateFixture(true)))
        assertTrue(again.isEmpty())
    }

    fun nonPairValuesPassThroughUntouched() {
        val h = Harness()
        val plain = Any()
        h.publish(plain)
        h.publish(listOf(GuestDynamicLoginRowFixture()))
        h.publish(GuestDynamicNotLoginStateFixture(true))
        assertNull(h.statuses["guest_browsing_dynamic_login_row_learned"])
    }

    fun nullListFieldSkipsFilteringButStillAuthenticates() {
        val h = Harness()
        // (null, state)：列表字段为 null，不学习、不误判 state，但 LiveData 本身已完成认证。
        h.publish(GuestDynamicPairValueFixture(null, GuestDynamicNotLoginStateFixture(true)))
        assertNull(h.statuses["guest_browsing_dynamic_login_row_learned"])
        val list = mutableListOf<Any>(GuestDynamicLoginRowFixture())
        h.publish(GuestDynamicPairValueFixture(list, GuestDynamicNotLoginStateFixture(true)))
        assertTrue(list.isEmpty())
    }

    fun authenticationCachesTheLiveDataInstance() {
        val h = Harness()
        h.publish(GuestDynamicPairValueFixture(
            mutableListOf<Any>(GuestDynamicLoginRowFixture()),
            GuestDynamicNotLoginStateFixture(true)
        ))
        // 第二次发布的 state 已经换成非 nologin 类型，但 LiveData 实例已缓存认证。
        val list = mutableListOf<Any>(GuestDynamicLoginRowFixture())
        h.publish(GuestDynamicPairValueFixture(list, GuestDynamicForeignStateFixture()))
        assertTrue(list.isEmpty())
    }

    fun immutableListIsLeftUntouchedWithoutCrashing() {
        val h = Harness()
        h.publish(GuestDynamicPairValueFixture(
            mutableListOf<Any>(GuestDynamicLoginRowFixture()),
            GuestDynamicNotLoginStateFixture(true)
        ))
        val frozen = listOf<Any>(GuestDynamicLoginRowFixture())
        h.publish(GuestDynamicPairValueFixture(frozen, GuestDynamicNotLoginStateFixture(true)))
        assertEquals(1, frozen.size)
    }

    fun emptyListDefersLearningToTheNextValue() {
        val h = Harness()
        h.publish(GuestDynamicPairValueFixture(mutableListOf<Any>(), GuestDynamicNotLoginStateFixture(true)))
        assertNull(h.statuses["guest_browsing_dynamic_login_row_learned"])
        val list = mutableListOf<Any>(GuestDynamicLoginRowFixture())
        h.publish(GuestDynamicPairValueFixture(list, GuestDynamicNotLoginStateFixture(true)))
        assertTrue(list.isEmpty())
        assertEquals("seen", h.statuses["guest_browsing_dynamic_login_row_learned"])
    }

    fun guestStateReadFailureLetsTheHostValueThrough() {
        val h = Harness(stateFailure = true)
        val list = mutableListOf<Any>(GuestDynamicLoginRowFixture())
        h.publish(GuestDynamicPairValueFixture(list, GuestDynamicNotLoginStateFixture(true)))
        assertEquals(1, list.size)
        assertNull(h.statuses["guest_browsing_dynamic_login_row_learned"])
        assertTrue(FeatureRuntimeStage.ERROR in h.stages)
    }

    fun unverifiedLiveDataNeverFiltersEvenWithLearnedClass() {
        val h = Harness()
        // 两个不同的 LiveData 实例：只有发布过 nologin state 的那个被认证。
        val verified = h.liveData
        h.publish(GuestDynamicPairValueFixture(
            mutableListOf<Any>(GuestDynamicLoginRowFixture()),
            GuestDynamicNotLoginStateFixture(true)
        ))
        val other = MediatorLiveData<Any>()
        val list = mutableListOf<Any>(GuestDynamicLoginRowFixture())
        h.registrar.invoke(
            "guest_browsing.dynamic-login-row-data",
            receiver = other, args = arrayOf(GuestDynamicPairValueFixture(list, GuestDynamicForeignStateFixture()))
        )
        assertEquals(1, list.size)
        assertFalse(other === verified)
    }

    fun disabledFilterLeavesEveryValueUntouched() {
        val registrar = PlayerPortTestRegistrar()
        val loader = GuestDynamicLoginRowFilterTest::class.java.classLoader!!
        val env = HookEnvironment(
            "tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { _, _ -> }
        )
        GuestDynamicLoginRowFilter(false, { true }, LiveData::class.java).install(env)
        val list = mutableListOf<Any>(GuestDynamicLoginRowFixture())
        registrar.invoke(
            "guest_browsing.dynamic-login-row-data",
            receiver = MediatorLiveData<Any>(),
            args = arrayOf(GuestDynamicPairValueFixture(list, GuestDynamicNotLoginStateFixture(true)))
        )
        assertEquals(1, list.size)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (GuestDynamicLoginRowFilterTest) -> Unit) {
            val s = GuestDynamicLoginRowFilterTest()
            block(s)
        }
        runIsolated { s -> s.learnsFromHeadAndRemovesOnlyTheLoginRow() }
        runIsolated { s -> s.inheritedDataStatusStateAuthenticatesOn916Shape() }
        runIsolated { s -> s.inheritedDataStatusCachesAuthenticationForLaterValues() }
        runIsolated { s -> s.removesEveryLearnedRowIncludingDuplicates() }
        runIsolated { s -> s.loggedInSessionsKeepTheOriginalList() }
        runIsolated { s -> s.headShapeMismatchSkipsLearningUntilALoginRowAppears() }
        runIsolated { s -> s.itemWithoutNoArgConstructorIsRejectedByShape() }
        runIsolated { s -> s.foreignStateDoesNotAuthenticateTheLiveData() }
        runIsolated { s -> s.nonPairValuesPassThroughUntouched() }
        runIsolated { s -> s.nullListFieldSkipsFilteringButStillAuthenticates() }
        runIsolated { s -> s.authenticationCachesTheLiveDataInstance() }
        runIsolated { s -> s.immutableListIsLeftUntouchedWithoutCrashing() }
        runIsolated { s -> s.emptyListDefersLearningToTheNextValue() }
        runIsolated { s -> s.guestStateReadFailureLetsTheHostValueThrough() }
        runIsolated { s -> s.unverifiedLiveDataNeverFiltersEvenWithLearnedClass() }
        runIsolated { s -> s.disabledFilterLeavesEveryValueUntouched() }
    
        GuestFollowEntryShapeCases().onlyTheAttentionClickObjectIsRecognised()
        GuestFollowGroupBindingsCases().verifyAllCases()
        GuestFollowGroupCallGuardCases().verifyAllCases()
        GuestFollowGroupCloneHooksCases().verifyAllCases()
        GuestFollowGroupMenuRelationGuardCases().verifyAllCases()
    }
}

private class GuestFollowEntryShapeCases {
    // The 关注 and 粉丝 clicks are Kotlin data objects; only their toString() survives renaming.
    object AttentionClick { override fun toString() = "ToAttentionClick" }
    object FansClick { override fun toString() = "ToFansClick" }
    class NotAnObject { override fun toString() = "ToAttentionClick" }

    fun onlyTheAttentionClickObjectIsRecognised() {
        assertTrue(GuestMineFollowEntry.isAttentionClick(AttentionClick::class.java))
        assertFalse(GuestMineFollowEntry.isAttentionClick(FansClick::class.java))
        // A class that merely prints the same text but is not an object (no static instance of itself) never counts.
        assertFalse(GuestMineFollowEntry.isAttentionClick(NotAnObject::class.java))
    }
}

private class GuestFollowGroupBindingsCases {
    private class EqualCall {
        override fun equals(other: Any?) = other is EqualCall
        override fun hashCode() = 1
    }

    fun callsRemainBoundToTheirOriginalSessionAndAuthor() {
        var next = 0
        val bindings = GuestFollowGroupBindings<Any>(newMarker = { "marker-${next++}" })
        val first = Any()
        val second = Any()
        val firstMarker = bindings.register(first, 11)
        val call = Any()
        bindings.tag(call, firstMarker)
        val secondMarker = bindings.register(second, 12)
        val secondCall = Any()
        bindings.tag(secondCall, secondMarker)
        assertSame(first, bindings.tagged(call)!!.binding!!.token)
        assertEquals(11L, bindings.tagged(call)!!.binding!!.mid)
        assertSame(second, bindings.tagged(secondCall)!!.binding!!.token)
        assertEquals(12L, bindings.tagged(secondCall)!!.binding!!.mid)
        assertNull(bindings.tagged(Any()))
    }

    fun missingRestoredAndEvictedMarkersStayTaggedAndBlocked() {
        var next = 0
        val bindings = GuestFollowGroupBindings<Any>(capacity = 1, newMarker = { "marker-${next++}" })
        val first = bindings.register(Any(), 11)
        val pending = Any()
        bindings.tag(pending, first)
        bindings.register(Any(), 12)
        assertNull(bindings.lookup(first))
        assertNotNull(bindings.tagged(pending))
        assertNull(bindings.tagged(pending)!!.binding)
        val restored = Any()
        bindings.tag(restored, "marker-from-a-previous-process")
        assertNotNull(bindings.tagged(restored))
        assertNull(bindings.tagged(restored)!!.binding)
    }

    fun hostEqualityCannotMixCallsAndRebindingFailsClosed() {
        var next = 0
        val bindings = GuestFollowGroupBindings<Any>(newMarker = { "marker-${next++}" })
        val first = bindings.register(Any(), 11)
        val second = bindings.register(Any(), 12)
        val firstCall = EqualCall()
        val secondCall = EqualCall()
        bindings.tag(firstCall, first)
        bindings.tag(secondCall, second)
        assertEquals(11L, bindings.tagged(firstCall)!!.binding!!.mid)
        assertEquals(12L, bindings.tagged(secondCall)!!.binding!!.mid)
        bindings.tag(firstCall, second)
        assertNull(bindings.tagged(firstCall)!!.binding)
        bindings.tag(firstCall, first)
        assertNull(bindings.tagged(firstCall)!!.binding)
        assertEquals(12L, bindings.tagged(secondCall)!!.binding!!.mid)
    }

    fun clonedCallsInheritTheirBindingAndRemainBlockedAfterEviction() {
        val bindings = GuestFollowGroupBindings<Any>(capacity = 1, newMarker = { "test" })
        val token = Any()
        val marker = bindings.register(token, 11)
        val original = Any()
        val clone = Any()
        bindings.tag(original, marker)
        bindings.forward(original, clone)
        assertSame(token, bindings.tagged(clone)!!.binding!!.token)
        bindings.register(Any(), 12)
        val laterClone = Any()
        bindings.forward(clone, laterClone)
        assertNotNull(bindings.tagged(laterClone))
        assertNull(bindings.tagged(laterClone)!!.binding)
        val unrelated = Any()
        bindings.forward(Any(), unrelated)
        assertNull(bindings.tagged(unrelated))
    }
    fun invalidOrReusedMarkersCannotReplaceALiveBinding() {
        val token = Any()
        val bindings = GuestFollowGroupBindings<Any>(capacity = 1, newMarker = { "same" })
        assertThrows(IllegalArgumentException::class.java) { bindings.register(token, 0) }
        val marker = bindings.register(token, 11)
        assertSame(token, bindings.lookup(marker)!!.token)
        val pending = Any()
        bindings.tag(pending, marker)
        val later = bindings.register(Any(), 12)
        assertNotEquals(marker, later)
        assertNull(bindings.tagged(pending)!!.binding)
        assertThrows(IllegalArgumentException::class.java) { GuestFollowGroupBindings<Any>(capacity = 0) }
        assertThrows(IllegalArgumentException::class.java) { GuestFollowGroupBindings<Any>(newMarker = { "" }) }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (GuestFollowGroupBindingsCases) -> Unit) {
            val s = GuestFollowGroupBindingsCases()
            block(s)
        }
        runIsolated { s -> s.callsRemainBoundToTheirOriginalSessionAndAuthor() }
        runIsolated { s -> s.missingRestoredAndEvictedMarkersStayTaggedAndBlocked() }
        runIsolated { s -> s.hostEqualityCannotMixCallsAndRebindingFailsClosed() }
        runIsolated { s -> s.clonedCallsInheritTheirBindingAndRemainBlockedAfterEviction() }
        runIsolated { s -> s.invalidOrReusedMarkersCannotReplaceALiveBinding() }
    }
}

private class GuestFollowGroupCallGuardCases {
    fun taggedCallsKeepTheirOriginalSessionAndNeverFallThroughAfterItChanges() {
        val bindings = GuestFollowGroupBindings<Any>(newMarker = { "test" })
        val original = Any()
        var current = original
        var guest = true
        val guard = GuestFollowGroupCallGuard(bindings) { guest && it === current }
        val marker = bindings.register(original, 42)
        val call = Any()
        bindings.tag(call, marker)
        val allowed = guard.decide(call) as GuestFollowGroupCallGuard.Decision.Local
        assertSame(original, allowed.binding.token)
        assertEquals(42L, allowed.binding.mid)
        current = Any()
        assertEquals(GuestFollowGroupCallGuard.Decision.Blocked, guard.decide(call))
        guest = false
        assertEquals(GuestFollowGroupCallGuard.Decision.Blocked, guard.decide(call))
        assertEquals(GuestFollowGroupCallGuard.Decision.Unrelated, guard.decide(Any()))
    }

    fun missingBindingsAndValidationErrorsBlockInsteadOfRunningTheHostCall() {
        val bindings = GuestFollowGroupBindings<Any>(newMarker = { "test" })
        val missing = Any()
        bindings.tag(missing, "restored-from-another-process")
        val guard = GuestFollowGroupCallGuard(bindings) { error("validation unavailable") }
        assertEquals(GuestFollowGroupCallGuard.Decision.Blocked, guard.decide(missing))
        val marker = bindings.register(Any(), 42)
        val call = Any()
        bindings.tag(call, marker)
        assertEquals(GuestFollowGroupCallGuard.Decision.Blocked, guard.decide(call))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (GuestFollowGroupCallGuardCases) -> Unit) {
            val s = GuestFollowGroupCallGuardCases()
            block(s)
        }
        runIsolated { s -> s.taggedCallsKeepTheirOriginalSessionAndNeverFallThroughAfterItChanges() }
        runIsolated { s -> s.missingBindingsAndValidationErrorsBlockInsteadOfRunningTheHostCall() }
    }
}

private class GuestFollowGroupCloneHooksCases {
    private class Call : Cloneable {
        public override fun clone(): Call = Call()
    }

    private class UnsupportedCall

    private fun registrar(port: PlayerPortTestRegistrar, type: Class<*>): HookRegistrar =
        object : HookRegistrar by port {
            override fun all(id: String, className: String, methodName: String, block: ModernMemberHookCreator.() -> Unit) {
                assertEquals(type.name, className)
                type.declaredMethods.filter { it.name == methodName }.forEach { method ->
                    port.hooks[method.returnType.name] = PlayerPortTestRegistrar.Entry(method, ModernMemberHookCreator(method).apply(block))
                }
            }
        }

    fun copiesRetainTheOriginalBindingAcrossBothCloneEntrypoints() {
        val bindings = GuestFollowGroupBindings<Any>(capacity = 1, newMarker = { "test" })
        val token = Any()
        val marker = bindings.register(token, 42)
        val source = Call()
        bindings.tag(source, marker)
        val port = PlayerPortTestRegistrar()
        assertTrue(GuestFollowGroupCloneHooks.install(registrar(port, Call::class.java), Call::class.java, bindings))
        val first = Call()
        assertSame(first, port.invoke(Call::class.java.name, source) { first })
        assertSame(token, bindings.tagged(first)?.binding?.token)
        val second = Call()
        assertSame(second, port.invoke(Any::class.java.name, first) { second })
        assertEquals(42L, bindings.tagged(second)?.binding?.mid)
        bindings.register(Any(), 43) // evict the original marker while a copied call is still in flight
        val expired = Call()
        assertSame(expired, port.invoke(Call::class.java.name, second) { expired })
        assertNotNull(bindings.tagged(expired))
        assertNull(bindings.tagged(expired)?.binding)
    }

    fun unrelatedClonesRemainUnchangedAndInvalidCopiesAreNotReturned() {
        val bindings = GuestFollowGroupBindings<Any>(newMarker = { "test" })
        val port = PlayerPortTestRegistrar()
        assertTrue(GuestFollowGroupCloneHooks.install(registrar(port, Call::class.java), Call::class.java, bindings))
        val unrelated = Call()
        val copy = Call()
        assertSame(copy, port.invoke(Call::class.java.name, unrelated) { copy })
        assertNull(bindings.tagged(copy))
        bindings.tag(unrelated, bindings.register(Any(), 42))
        assertNull(port.invoke(Any::class.java.name, unrelated) { Any() })
    }

    fun aThrowingLocalCloneCannotBeRetriedWithoutItsMarker() {
        val bindings = GuestFollowGroupBindings<Any>(newMarker = { "test" })
        val source = Call()
        bindings.tag(source, bindings.register(Any(), 42))
        val port = PlayerPortTestRegistrar()
        assertTrue(GuestFollowGroupCloneHooks.install(registrar(port, Call::class.java), Call::class.java, bindings))
        var attempts = 0
        assertNull(port.invoke(Call::class.java.name, source) {
            attempts++
            throw IllegalStateException("clone failed")
        })
        assertEquals(1, attempts)
    }
    fun unsupportedCloneShapesAreNotInstalled() {
        val port = PlayerPortTestRegistrar()
        val bindings = GuestFollowGroupBindings<Any>()
        assertFalse(GuestFollowGroupCloneHooks.install(registrar(port, UnsupportedCall::class.java), UnsupportedCall::class.java, bindings))
        assertTrue(port.hooks.isEmpty())
    }

    fun verifyAllCases() {
        fun runIsolated(block: (GuestFollowGroupCloneHooksCases) -> Unit) {
            val s = GuestFollowGroupCloneHooksCases()
            block(s)
        }
        runIsolated { s -> s.copiesRetainTheOriginalBindingAcrossBothCloneEntrypoints() }
        runIsolated { s -> s.unrelatedClonesRemainUnchangedAndInvalidCopiesAreNotReturned() }
        runIsolated { s -> s.aThrowingLocalCloneCannotBeRetriedWithoutItsMarker() }
        runIsolated { s -> s.unsupportedCloneShapesAreNotInstalled() }
    }
}

private class GuestFollowGroupMenuRelationGuardCases {
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

    fun localMenuReceivesSpecialStateWithoutCallingTheHostNetwork() {
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

    fun delayedOrUnrecognisedCallbacksFailClosedAfterTheSessionChanges() {
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

    fun unrelatedLookupsRunNormallyBeforeAnyLocalMenuIsOpened() {
        install()
        var network = 0
        request("42", OtherCallback()) { network++ }
        assertEquals(1, network)
    }

    fun hostCallbackFailureCannotRetryTheRelationRequest() {
        install()
        val actions = Actions()
        val callback = MenuCallback(actions).apply { fail = true }
        var network = 0
        guard.within(actions, 42, false, { true }) { request("42", callback) { network++ } }
        assertEquals(0, network)
        assertTrue(callback.answers.isEmpty())
    }

    fun verifyAllCases() {
        fun runIsolated(block: (GuestFollowGroupMenuRelationGuardCases) -> Unit) {
            val s = GuestFollowGroupMenuRelationGuardCases()
            block(s)
        }
        runIsolated { s -> s.localMenuReceivesSpecialStateWithoutCallingTheHostNetwork() }
        runIsolated { s -> s.delayedOrUnrecognisedCallbacksFailClosedAfterTheSessionChanges() }
        runIsolated { s -> s.unrelatedLookupsRunNormallyBeforeAnyLocalMenuIsOpened() }
        runIsolated { s -> s.hostCallbackFailureCannotRetryTheRelationRequest() }
    }
}
