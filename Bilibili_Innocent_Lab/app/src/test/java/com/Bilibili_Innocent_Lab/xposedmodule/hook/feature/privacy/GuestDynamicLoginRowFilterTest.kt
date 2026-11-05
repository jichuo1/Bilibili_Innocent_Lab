package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.bilibili.bplus.followinglist.model.GuestDynamicFeedItemFixture
import com.bilibili.bplus.followinglist.model.GuestDynamicLoginRowFixture
import com.bilibili.bplus.followinglist.model.GuestDynamicNoCtorItemFixture
import com.bilibili.bplus.followinglist.other.GuestDynamicForeignStateFixture
import com.bilibili.bplus.followinglist.page.nologin.GuestDynamicNotLoginStateFixture
import com.bilibili.bplus.followinglist.page.nologin.GuestDynamicPairValueFixture
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
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

    @Test fun learnsFromHeadAndRemovesOnlyTheLoginRow() {
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

    @Test fun removesEveryLearnedRowIncludingDuplicates() {
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

    @Test fun loggedInSessionsKeepTheOriginalList() {
        val h = Harness()
        h.guest = false
        val list = mutableListOf<Any>(GuestDynamicLoginRowFixture())
        h.publish(GuestDynamicPairValueFixture(list, GuestDynamicNotLoginStateFixture(true)))
        assertEquals(1, list.size)
        assertNull(h.statuses["guest_browsing_dynamic_login_row_learned"])
        assertNull(h.statuses["guest_browsing_dynamic_login_row_applied"])
    }

    @Test fun headShapeMismatchSkipsLearningUntilALoginRowAppears() {
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

    @Test fun itemWithoutNoArgConstructorIsRejectedByShape() {
        val h = Harness()
        val list = mutableListOf<Any>(GuestDynamicNoCtorItemFixture(true))
        h.publish(GuestDynamicPairValueFixture(list, GuestDynamicNotLoginStateFixture(true)))
        assertEquals(1, list.size)
        assertNull(h.statuses["guest_browsing_dynamic_login_row_learned"])
    }

    @Test fun foreignStateDoesNotAuthenticateTheLiveData() {
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

    @Test fun nonPairValuesPassThroughUntouched() {
        val h = Harness()
        val plain = Any()
        h.publish(plain)
        h.publish(listOf(GuestDynamicLoginRowFixture()))
        h.publish(GuestDynamicNotLoginStateFixture(true))
        assertNull(h.statuses["guest_browsing_dynamic_login_row_learned"])
    }

    @Test fun nullListFieldSkipsFilteringButStillAuthenticates() {
        val h = Harness()
        // (null, state)：列表字段为 null，不学习、不误判 state，但 LiveData 本身已完成认证。
        h.publish(GuestDynamicPairValueFixture(null, GuestDynamicNotLoginStateFixture(true)))
        assertNull(h.statuses["guest_browsing_dynamic_login_row_learned"])
        val list = mutableListOf<Any>(GuestDynamicLoginRowFixture())
        h.publish(GuestDynamicPairValueFixture(list, GuestDynamicNotLoginStateFixture(true)))
        assertTrue(list.isEmpty())
    }

    @Test fun authenticationCachesTheLiveDataInstance() {
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

    @Test fun immutableListIsLeftUntouchedWithoutCrashing() {
        val h = Harness()
        h.publish(GuestDynamicPairValueFixture(
            mutableListOf<Any>(GuestDynamicLoginRowFixture()),
            GuestDynamicNotLoginStateFixture(true)
        ))
        val frozen = listOf<Any>(GuestDynamicLoginRowFixture())
        h.publish(GuestDynamicPairValueFixture(frozen, GuestDynamicNotLoginStateFixture(true)))
        assertEquals(1, frozen.size)
    }

    @Test fun emptyListDefersLearningToTheNextValue() {
        val h = Harness()
        h.publish(GuestDynamicPairValueFixture(mutableListOf<Any>(), GuestDynamicNotLoginStateFixture(true)))
        assertNull(h.statuses["guest_browsing_dynamic_login_row_learned"])
        val list = mutableListOf<Any>(GuestDynamicLoginRowFixture())
        h.publish(GuestDynamicPairValueFixture(list, GuestDynamicNotLoginStateFixture(true)))
        assertTrue(list.isEmpty())
        assertEquals("seen", h.statuses["guest_browsing_dynamic_login_row_learned"])
    }

    @Test fun guestStateReadFailureLetsTheHostValueThrough() {
        val h = Harness(stateFailure = true)
        val list = mutableListOf<Any>(GuestDynamicLoginRowFixture())
        h.publish(GuestDynamicPairValueFixture(list, GuestDynamicNotLoginStateFixture(true)))
        assertEquals(1, list.size)
        assertNull(h.statuses["guest_browsing_dynamic_login_row_learned"])
        assertTrue(FeatureRuntimeStage.ERROR in h.stages)
    }

    @Test fun unverifiedLiveDataNeverFiltersEvenWithLearnedClass() {
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

    @Test fun disabledFilterLeavesEveryValueUntouched() {
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
}
