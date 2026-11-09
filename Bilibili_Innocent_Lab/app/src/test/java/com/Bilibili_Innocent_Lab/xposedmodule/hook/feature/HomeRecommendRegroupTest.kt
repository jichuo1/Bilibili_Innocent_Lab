package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.smallcards.WindowColumnsFixtures
import com.bilibili.pegasus.HolderStyle
import com.bilibili.pegasus.PegasusHolderData
import com.bilibili.pegasus.data.base.PegasusResponse
import com.bilibili.pegasus.data.request.PegasusResponseWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 删卡后避免宿主 avoidEmptyWindow 额外丢弃同组小卡：只在过滤让情况变差时重排，
 * 组大小只取宿主这一页请求时的窗口档位，且只调整宿主已开始凑组的页。
 */
class HomeRecommendRegroupTest {
    private val loader = requireNotNull(javaClass.classLoader)

    private class Card(private val title: String, private val small: Boolean?) : PegasusHolderData() {
        override fun getTitle() = title
        override fun getHolderStyle(): HolderStyle? = when (small) {
            true -> HolderStyle.DOUBLE_COLUMN_SMALL_CARD
            false -> HolderStyle.DOUBLE_COLUMN_LARGE_CARD
            null -> null
        }
        override fun toString() = title
    }

    private fun small(title: String) = Card(title, true)
    private fun large(title: String) = Card(title, false)

    private fun environment(registrar: HookRegistrar, logs: MutableList<String> = mutableListOf()) = HookEnvironment(
        "tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
        { _, text -> logs += text }, { _, _ -> }, { _, _ -> }
    )

    private inner class Harness(names: HomeFeedSmallCards.HostNames = HomeFeedSmallCards.HostNames()) {
        val recorder = PlayerPortTestRegistrar()
        val registrar = ContentFilterTestRegistrar(loader, recorder)
        val logs = mutableListOf<String>()
        init {
            HomeRecommendPurifyFeatureInstaller(
                removeAds = false, removeCmV2 = false, removeBanner = false,
                removePictures = false, removeGamePromotions = false,
                titleFilterEnabled = true, rawTitleKeywords = "blocked",
                removeLive = false, removeCourses = false, removeVertical = false, removeLarge = false,
                minDurationSeconds = 0, maxDurationSeconds = 0,
                points = requireNotNull(VersionAdapter.locateHomeRecommendFeed(loader)),
                smallCardNames = names
            ).install(environment(registrar, logs))
        }

        /** 宿主以外的读取（解析、状态归约等）。 */
        fun read(response: PegasusResponse, source: List<*>) =
            registrar.items("home.recommend.purify.0", response, source)

        /** 宿主 avoidEmptyWindow：先进入凑组入口，再在其中读取这一页。 */
        fun group(response: PegasusResponse, source: List<*>, windowSizeType: Int = 1): List<*> {
            var seen: List<*>? = null
            recorder.invoke(HomeFeedSmallCards.GROUPING_HOOK_ID,
                args = arrayOf(PegasusResponseWrapper(response, windowSizeType)),
                original = { args -> seen = read(response, source); args[0] })
            return requireNotNull(seen)
        }
    }

    @Test fun removingOneCardFromTwoPairsNoLongerCostsTheirPartners() {
        val s1 = small("one"); val s4 = small("four")
        val a = large("A"); val b = large("B")
        val source = listOf(s1, small("blocked two"), a, small("blocked three"), s4, b)
        // 不重排时宿主会把 one 和 four 当作落单小卡一起丢掉；重排后两张凑成一组。
        assertEquals(listOf(a, s1, s4, b), Harness().group(PegasusResponse(), source))
    }

    @Test fun aServerPageThatWasAlreadyIncompleteIsNotReordered() {
        val s1 = small("one"); val a = large("A")
        assertEquals(listOf(s1, a), Harness().group(PegasusResponse(), listOf(s1, a, large("blocked large"))))
    }

    @Test fun anOddRemainderThatCannotBePairedIsLeftForTheHost() {
        val s1 = small("one"); val s3 = small("three"); val s4 = small("four"); val a = large("A")
        // 三张小卡无论怎么排都会剩一张；不为不能改善的结果改变顺序。
        assertEquals(listOf(s1, a, s3, s4), Harness().group(PegasusResponse(), listOf(s1, small("blocked two"), a, s3, s4)))
    }

    @Test fun anUnreadableCardStyleKeepsThePlainFilterResult() {
        val s1 = small("one"); val unknown = Card("unknown", null); val a = large("A"); val s4 = small("four")
        val source = listOf(s1, small("blocked two"), a, unknown, small("blocked three"), s4)
        assertEquals(listOf(s1, a, unknown, s4), Harness().group(PegasusResponse(), source))
    }

    @Test fun readsBeforeTheHostGroupsThePageKeepTheFilterOrderThenSettleOnTheRegroupedList() {
        val h = Harness()
        val response = PegasusResponse()
        val s1 = small("one"); val s4 = small("four"); val a = large("A")
        val source = listOf(s1, small("blocked"), a, small("blocked too"), s4)
        // 先于凑组的读取：组大小未知，只交过滤结果。
        assertEquals(listOf(s1, a, s4), h.read(response, source))
        val grouped = h.group(response, source)
        assertEquals(listOf(a, s1, s4), grouped)
        // 凑组之后的读取与宿主凑组时看到的是同一个实例；过滤只做了一次。
        assertSame(grouped, h.read(response, source))
        assertEquals(1, h.logs.count { it.contains("已过滤") })
        assertEquals(1, h.logs.count { it.contains("重新凑组") })
    }

    @Test fun pagesTheHostNeverGroupsAreNotReordered() {
        val s1 = small("one"); val s4 = small("four"); val a = large("A")
        val source = listOf(s1, small("blocked"), a, small("blocked too"), s4)
        assertEquals(listOf(s1, a, s4), Harness().read(PegasusResponse(), source))
    }

    @Test fun aWidePageIsRegroupedWithTheColumnsOfItsOwnRequest() {
        val h = Harness(HomeFeedSmallCards.HostNames(windowProfile = WindowColumnsFixtures.WindowProfile::class.java.name))
        val (s1, s2, s3, s4) = (1..4).map { small("s$it") }
        val a = large("A")
        fun page() = listOf(s1, s2, small("blocked x"), a, small("blocked y"), s3, s4)
        // 平板 Medium 档（type 2）三张一组：原页两组都完整；删卡后 s1 s2 与 s3 s4 都凑不满，宿主要丢 4 张。
        // 重排后 s1 s2 s3 凑满一组，只剩 s4。
        assertEquals(listOf(a, s1, s2, s3, s4), h.group(PegasusResponse(), page(), windowSizeType = 2))
        // 同一页若按 Normal 档（两张一组）发出，删卡后反而全部成组：此时按三列重排只会让宿主多丢卡，必须不动。
        assertEquals(listOf(s1, s2, a, s3, s4), h.group(PegasusResponse(), page(), windowSizeType = 1))
    }

    @Test fun theGroupSizeFollowsTheRequestWindowTypeAndFallsBackToNormalLikeTheHost() {
        val registrar = PlayerPortTestRegistrar()
        val cards = requireNotNull(HomeFeedSmallCards.resolve(environment(registrar),
            HomeFeedSmallCards.HostNames(windowProfile = WindowColumnsFixtures.WindowProfile::class.java.name)))
        assertEquals(1, registrar.registrationAttempts.count { it.first == HomeFeedSmallCards.GROUPING_HOOK_ID })
        fun sizeFor(windowSizeType: Int): Int? {
            val response = PegasusResponse()
            assertNull(cards.groupSizeFor(response))
            registrar.invoke(HomeFeedSmallCards.GROUPING_HOOK_ID,
                args = arrayOf(PegasusResponseWrapper(response, windowSizeType)), original = { it[0] })
            return cards.groupSizeFor(response)
        }
        assertEquals(2, sizeFor(1))
        assertEquals(3, sizeFor(2))
        assertEquals(4, sizeFor(3))
        assertEquals(2, sizeFor(99))
    }

    @Test fun onlyTheMostRecentPagesAreRemembered() {
        val registrar = PlayerPortTestRegistrar()
        val cards = requireNotNull(HomeFeedSmallCards.resolve(environment(registrar)))
        val responses = (0..HomeFeedSmallCards.PAGE_CAPACITY).map { PegasusResponse() }
        responses.forEach { response ->
            registrar.invoke(HomeFeedSmallCards.GROUPING_HOOK_ID,
                args = arrayOf(PegasusResponseWrapper(response, 1)), original = { it[0] })
        }
        assertNull(cards.groupSizeFor(responses.first()))
        // 8.x 形状（测试类路径没有窗口档位类）固定两张一组。
        assertEquals(2, cards.groupSizeFor(responses.last()))
        assertNull(cards.groupSizeFor(null))
    }

    @Test fun aGroupingEntryWithTheWrongShapeDisablesRegroupingInsteadOfGuessing() {
        val registrar = PlayerPortTestRegistrar()
        val environment = environment(registrar)
        assertNull(HomeFeedSmallCards.resolve(environment, HomeFeedSmallCards.HostNames(
            windowProfile = WindowColumnsFixtures.WrongWindowProfile::class.java.name
        )))
        assertNull(HomeFeedSmallCards.resolve(environment, HomeFeedSmallCards.HostNames(actions = "missing.ActionsKt")))
        assertNull(HomeFeedSmallCards.resolve(environment, HomeFeedSmallCards.HostNames(
            responseWrapper = PegasusResponse::class.java.name
        )))
        assertEquals(0, registrar.registrationAttempts.size)
    }

    @Test fun theWindowTypeIsReadFromItsOwnLabelOnly() {
        assertEquals(3, HomeFeedSmallCards.windowSizeType("RequestParam(idx=1, windowSizeType=3, rawWindowSizeType=7)"))
        assertEquals(2, HomeFeedSmallCards.windowSizeType("RequestParam(windowSizeType=2)"))
        assertNull(HomeFeedSmallCards.windowSizeType("RequestParam(idx=1, rawWindowSizeType=7)"))
        assertNull(HomeFeedSmallCards.windowSizeType("RequestParam(windowSizeType=, rawWindowSizeType=7)"))
        assertNull(HomeFeedSmallCards.windowSizeType(null))
    }
}
