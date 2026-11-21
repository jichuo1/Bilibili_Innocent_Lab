package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.google.gson.annotations.SerializedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeVerticalDetailFeatureInstallerTest {

    fun `layer status compares only the installed layers with the expected landings`() {
        assertEquals("success", HomeVerticalDetailFeatureInstaller.layerStatus(5, 5))
        assertEquals("partial:4/5", HomeVerticalDetailFeatureInstaller.layerStatus(4, 5))
        assertEquals("success", HomeVerticalDetailFeatureInstaller.layerStatus(0, 0))
    }

    fun `rewrites only a valid story route and preserves query`() {
        assertEquals(
            "bilibili://video/BV1xx411c7mD?from=feed#page",
            HomeVerticalDetailFeatureInstaller.normalizeVideoRouteUri(
                "bilibili://story/BV1xx411c7mD?from=feed#page"
            )
        )
        assertNull(
            HomeVerticalDetailFeatureInstaller.normalizeVideoRouteUri(
                "bilibili://video/BV1xx411c7mD"
            )
        )
        assertNull(
            HomeVerticalDetailFeatureInstaller.normalizeVideoRouteUri(
                "bilibili://story/not-a-video"
            )
        )
        assertEquals(
            "bilibili://video/BV1xx411c7mD?from=feed#page",
            HomeVerticalDetailFeatureInstaller.normalizeVideoRouteUri(
                "bilibili://story/BV1xx411c7mD?from=feed&-Arouter=story&-Atype=story#page"
            )
        )
    }

    fun `normalizes every identified video route but preserves story roots and conflicts`() {
        assertEquals(
            "bilibili://video/123456789?aid=123456789&from=search#page",
            HomeVerticalDetailFeatureInstaller.normalizeVideoRouteUri(
                "bilibili://story?aid=123456789&-%41router=story&from=search#page"
            )
        )
        assertEquals(
            "bilibili://video/BV1xx411c7mD?from=dynamic",
            HomeVerticalDetailFeatureInstaller.normalizeVideoRouteUri(
                "bilibili://video/BV1xx411c7mD?-Atype=story&from=dynamic"
            )
        )
        assertNull(HomeVerticalDetailFeatureInstaller.normalizeVideoRouteUri("bilibili://story"))
        assertEquals(
            "bilibili://video/BV1xx411c7mD?from=feed",
            HomeVerticalDetailFeatureInstaller.normalizeVideoRouteUri(
                "bilibili://story_translucent/BV1xx411c7mD?from=feed&-Atype=story"
            )
        )
        assertNull(
            HomeVerticalDetailFeatureInstaller.normalizeVideoRouteUri(
                "bilibili://story_translucent"
            )
        )
        assertNull(
            HomeVerticalDetailFeatureInstaller.normalizeVideoRouteUri(
                "bilibili://story/123456789?aid=987654321"
            )
        )
        assertNull(
            HomeVerticalDetailFeatureInstaller.normalizeVideoRouteUri(
                "https://www.bilibili.com/video/BV1xx411c7mD"
            )
        )
    }

    fun `fails closed when the unit android jar exposes no activity launch hook`() {
        val statuses = mutableListOf<Pair<String, String>>()
        val environment = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = javaClass.classLoader,
            hookPoints = HookPointRegistry(javaClass.classLoader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { channel, status -> statuses += channel to status }
        )

        val result = HomeVerticalDetailFeatureInstaller(
            enabled = true,
            points = null
        ).install(environment)

        assertEquals(
            FeatureInstallResult.Skipped("no-safe-activity-launch-hook-point"),
            result
        )
        assertEquals(
            listOf(
                "home_vertical_detail_status" to "no-safe-activity-launch-hook-point"
            ),
            statuses
        )
    }

    fun `builds complete united launch contract only for numeric story with cid`() {
        val plan = planOf(
            HomeVerticalActivityLaunchSnapshot(
                dataUri = "bilibili://story/123456789?from_spmid=main.1.0.0&" +
                    "player_preload=%7B%22cid%22%3A987654321%7D&-Arouter=story",
                componentPackage = "tv.danmaku.bili",
                aid = "123456789",
                preloadCid = 987654321L
            ),
            HomeVerticalDetailBackend.UNITED
        ) as HomeVerticalActivityLaunchPlan.United

        assertEquals(
            "bilibili://united_video/123456789?from_spmid=main.1.0.0&" +
                "aid=123456789&bvid=",
            plan.detailUri
        )
        assertEquals("bilibili://united_video/123456789", plan.targetUrl)
        assertEquals(123456789L, plan.aid)
        assertEquals(987654321L, plan.cid)
    }

    /** 每条放行都必须给出可归因的有界原因，不允许静默返回。 */
    fun `reports a bounded reason for every united skip`() {
        assertEquals(
            HomeVerticalLaunchSkip.MISSING_PRELOAD_CID,
            skipOf(
                HomeVerticalActivityLaunchSnapshot(
                    dataUri = "bilibili://story/123456789",
                    preloadCid = null
                ),
                HomeVerticalDetailBackend.UNITED
            )
        )
        assertEquals(
            HomeVerticalLaunchSkip.BV_ONLY_UNITED,
            skipOf(
                HomeVerticalActivityLaunchSnapshot(
                    dataUri = "bilibili://story/BV1xx411c7mD",
                    preloadCid = 987654321L
                ),
                HomeVerticalDetailBackend.UNITED
            )
        )
        assertEquals(
            HomeVerticalLaunchSkip.NOT_STORY_ROUTE,
            skipOf(
                HomeVerticalActivityLaunchSnapshot(dataUri = "bilibili://video/123456789"),
                HomeVerticalDetailBackend.UNITED
            )
        )
        assertEquals(
            HomeVerticalLaunchSkip.NO_DATA_URI,
            skipOf(
                HomeVerticalActivityLaunchSnapshot(dataUri = null),
                HomeVerticalDetailBackend.UNITED
            )
        )
        assertEquals(
            HomeVerticalLaunchSkip.CROSS_PACKAGE,
            skipOf(
                HomeVerticalActivityLaunchSnapshot(
                    dataUri = "bilibili://story/123456789",
                    componentPackage = "com.example.other",
                    preloadCid = 1L
                ),
                HomeVerticalDetailBackend.UNITED
            )
        )
        assertEquals(
            HomeVerticalLaunchSkip.IDENTITY_CONFLICT,
            skipOf(
                HomeVerticalActivityLaunchSnapshot(
                    dataUri = "bilibili://story/123456789",
                    aid = "987654321",
                    preloadCid = 1L
                ),
                HomeVerticalDetailBackend.UNITED
            )
        )
    }

    /**
     * issue #9：开启"竖屏视频进入普通详情页"后，在普通详情页里点播放器右下角"竖屏"，页面会闪回
     * 当前视频的详情页而进不了竖屏。
     *
     * 根因（9.13.0 / 9.14.0 反汇编）：那颗按钮经 `StoryEntranceService` 用
     * `RouteRequest(bilibili://story/{aid}).requestCode(1101)` 启动 Story，被启动边界当成首页卡片点击
     * 又改写成"再开一个详情页"。两种启动形态（共享 / 不共享播放器）的 extras 都带
     * `from_spmid = united.player-video-detail.0.0`，所以判据只认来源埋点。
     */
    fun `lets the detail page vertical switch button reach the story page`() {
        val detailSpmid = "united.player-video-detail.0.0"
        // 详情页发起的 Story 启动带 avid/cid extra；身份与 cid 都齐全，旧实现会照改写。
        val fromDetailPage = HomeVerticalActivityLaunchSnapshot(
            dataUri = "bilibili://story/123456789",
            componentPackage = "tv.danmaku.bili",
            avid = "123456789",
            preloadCid = 987654321L,
            fromSpmid = detailSpmid
        )
        HomeVerticalDetailBackend.entries.forEach { backend ->
            assertEquals(
                backend.name,
                HomeVerticalLaunchSkip.DETAIL_PAGE_VERTICAL_SWITCH,
                skipOf(fromDetailPage, backend)
            )
            assertNull(backend.name, planOf(fromDetailPage, backend))
        }
        // story_translucent 与无 cid 的形态同样是详情页来源，同样放行（不能因缺 cid 反而走到别的原因）。
        assertEquals(
            HomeVerticalLaunchSkip.DETAIL_PAGE_VERTICAL_SWITCH,
            skipOf(
                fromDetailPage.copy(dataUri = "bilibili://story_translucent/123456789", preloadCid = null),
                HomeVerticalDetailBackend.UNITED
            )
        )
        // 内联了 35 KB DASH manifest 的超长 URI 也是先判来源、后判长度。
        assertEquals(
            HomeVerticalLaunchSkip.DETAIL_PAGE_VERTICAL_SWITCH,
            skipOf(
                fromDetailPage.copy(dataUri = "bilibili://story/123456789?player_preload=" + "x".repeat(300_000)),
                HomeVerticalDetailBackend.UNITED
            )
        )
    }

    /** 放行只针对详情页播放器这一个来源；首页卡片、搜索、动态等入口的替换一律不受影响。 */
    fun `keeps replacing story launches from every other origin`() {
        val base = HomeVerticalActivityLaunchSnapshot(
            dataUri = "bilibili://story/123456789",
            componentPackage = "tv.danmaku.bili",
            aid = "123456789",
            preloadCid = 987654321L
        )
        listOf(
            null,
            "",
            "main.homepage-gateway.0.0",
            "main.homepage-gateway.card.click",
            "united.relate-recommend.0.0",
            "search.search-result.0.0",
            "dynamic.homepage.0.0",
            // 同一命名空间下的事件名（宿主埋点里有几百条）不是"竖屏按钮"，不能被放行。
            "united.player-video-detail.banner.0.click",
            "united.player-video-detail.caching.button.click",
            "united.player-video-detail.0.0.pv",
            "united.player-video-detail",
            "xunited.player-video-detail.0.0",
            "UNITED.PLAYER-VIDEO-DETAIL.0.0"
        ).forEach { origin ->
            assertTrue(
                "origin=$origin",
                planOf(base.copy(fromSpmid = origin), HomeVerticalDetailBackend.UNITED)
                    is HomeVerticalActivityLaunchPlan.United
            )
            assertTrue(
                "origin=$origin",
                planOf(base.copy(fromSpmid = origin), HomeVerticalDetailBackend.LEGACY)
                    is HomeVerticalActivityLaunchPlan.Legacy
            )
        }
    }

    fun `detail page origin predicate is an exact match not a prefix`() {
        assertTrue(HomeVerticalDetailRoutePolicy.isDetailPageVerticalSwitch("united.player-video-detail.0.0"))
        assertFalse(HomeVerticalDetailRoutePolicy.isDetailPageVerticalSwitch(null))
        assertFalse(HomeVerticalDetailRoutePolicy.isDetailPageVerticalSwitch(""))
        assertFalse(HomeVerticalDetailRoutePolicy.isDetailPageVerticalSwitch("united.player-video-detail"))
        assertFalse(HomeVerticalDetailRoutePolicy.isDetailPageVerticalSwitch("united.player-video-detail.0.0.pv"))
        assertFalse(HomeVerticalDetailRoutePolicy.isDetailPageVerticalSwitch("united.player-video-detail.banner.0.click"))
        assertFalse(HomeVerticalDetailRoutePolicy.isDetailPageVerticalSwitch("main.homepage-gateway.0.0"))
    }

    // ---- 后端可用性：8.84.0 里 VideoDetailsActivity 只是空壳（只有 attachBaseContext，无 onCreate）----

    @Suppress("unused")
    private open class DeclaresOnCreate {
        open fun onCreate(savedInstanceState: android.os.Bundle?) = Unit
    }

    @Suppress("unused")
    private class InheritsOnCreate : DeclaresOnCreate()

    @Suppress("unused")
    private class OnlyAttachBaseContext {
        fun attachBaseContext(base: android.content.Context?) = Unit
    }

    fun `legacy backend needs its own onCreate while united does not`() {
        assertTrue(HomeVerticalDetailBackend.LEGACY.requiresOnCreate)
        assertFalse(HomeVerticalDetailBackend.UNITED.requiresOnCreate)
        assertTrue(HomeVerticalDetailFeatureInstaller.declaresOnCreate(DeclaresOnCreate::class.java))
        // 空壳（8.84.0 的 VideoDetailsActivity 形状）与"只继承、不声明"都不算能承载详情页。
        assertFalse(HomeVerticalDetailFeatureInstaller.declaresOnCreate(OnlyAttachBaseContext::class.java))
        assertFalse(HomeVerticalDetailFeatureInstaller.declaresOnCreate(InheritsOnCreate::class.java))
    }

    /** 宿主同时注册了 story_translucent；它与 story 的身份契约一致，不应整条放行。 */
    fun `handles story translucent on both backends`() {
        val legacy = planOf(
            HomeVerticalActivityLaunchSnapshot(
                dataUri = "bilibili://story_translucent/BV1xx411c7mD?from=feed&-Atype=story"
            ),
            HomeVerticalDetailBackend.LEGACY
        ) as HomeVerticalActivityLaunchPlan.Legacy
        assertEquals("bilibili://video/BV1xx411c7mD?from=feed", legacy.detailUri)

        val united = planOf(
            HomeVerticalActivityLaunchSnapshot(
                dataUri = "bilibili://story_translucent/123456789",
                preloadCid = 987654321L
            ),
            HomeVerticalDetailBackend.UNITED
        ) as HomeVerticalActivityLaunchPlan.United
        assertEquals(123456789L, united.aid)
        assertEquals(987654321L, united.cid)
    }

    /** 宿主注册了裸 bilibili://story；身份来自查询串或 Intent 结构化字段时同样要接管。 */
    fun `recovers identity from query string and intent extras without a path token`() {
        val fromQuery = planOf(
            HomeVerticalActivityLaunchSnapshot(
                dataUri = "bilibili://story?aid=123456789",
                preloadCid = 987654321L
            ),
            HomeVerticalDetailBackend.UNITED
        ) as HomeVerticalActivityLaunchPlan.United
        assertEquals(123456789L, fromQuery.aid)

        val fromExtras = planOf(
            HomeVerticalActivityLaunchSnapshot(
                dataUri = "bilibili://story",
                aid = "123456789",
                preloadCid = 987654321L
            ),
            HomeVerticalDetailBackend.UNITED
        ) as HomeVerticalActivityLaunchPlan.United
        assertEquals(123456789L, fromExtras.aid)

        assertEquals(
            HomeVerticalLaunchSkip.NO_IDENTITY,
            skipOf(
                HomeVerticalActivityLaunchSnapshot(
                    dataUri = "bilibili://story",
                    preloadCid = 987654321L
                ),
                HomeVerticalDetailBackend.UNITED
            )
        )
    }

    /**
     * 回归锁：宿主 9.9.0 实测的真实 Story URI 长 35,657 字符，`player_preload` 内联了完整
     * DASH manifest。旧上限 4096/8192 会把每一条带预加载的 URI 整条丢弃，且因为拒绝发生在
     * 静默的入口门禁上，失败连日志都没有。
     */
    fun `handles a real world story uri with an inlined dash manifest`() {
        val dashPadding = "%22base_url%22%3A%22https%3A%2F%2Fupos-sz-mirrorcos.bilivideo.com" +
            "%2Fupgcxcode%2F90%2F37%2F41429503790%2F41429503790-1-100022.m4s%22%2C"
        val preload = "%7B%22expire_time%22%3A1788360455%2C%22cid%22%3A41429503790%2C" +
            "%22video_codecid%22%3A7%2C%22dash%22%3A%7B" +
            dashPadding.repeat(120) +
            "%22end%22%3A1%7D%7D"
        val uri = "bilibili://story/117184055543713?story_item=%7B%7D&player_height=4660&" +
            "player_preload=$preload"
        assertTrue(uri.length > 8_192)
        assertTrue(HomeVerticalDetailRoutePolicy.isStrictStoryVideoRoute(uri))

        val plan = planOf(
            HomeVerticalActivityLaunchSnapshot(
                dataUri = uri,
                componentPackage = "tv.danmaku.bili",
                preloadCid = 41429503790L
            ),
            HomeVerticalDetailBackend.UNITED
        ) as HomeVerticalActivityLaunchPlan.United
        assertEquals(117184055543713L, plan.aid)
        assertEquals(41429503790L, plan.cid)

        // 入口门禁不再设长度上限；超长仍要有可归因的原因，而不是静默消失。
        val oversized = "bilibili://story/117184055543713?x=" + "a".repeat(300_000)
        assertTrue(HomeVerticalDetailRoutePolicy.isStrictStoryVideoRoute(oversized))
        assertEquals(
            HomeVerticalLaunchSkip.ROUTE_TOO_LONG,
            skipOf(
                HomeVerticalActivityLaunchSnapshot(dataUri = oversized, preloadCid = 1L),
                HomeVerticalDetailBackend.UNITED
            )
        )
    }

    fun `falls back to legacy detail activity contract without united cid`() {
        val plan = planOf(
            HomeVerticalActivityLaunchSnapshot(
                dataUri = "bilibili://story/BV1xx411c7mD?from=feed&-Atype=story",
                targetPackage = "tv.danmaku.bili"
            ),
            HomeVerticalDetailBackend.LEGACY
        ) as HomeVerticalActivityLaunchPlan.Legacy

        assertEquals("bilibili://video/BV1xx411c7mD?from=feed", plan.detailUri)
    }

    fun `parses bounded preload cid and only sanitizes forced story hints`() {
        assertEquals(
            987654321L,
            HomeVerticalDetailRoutePolicy.parsePlayerPreloadCid(
                "{\"cid\":987654321,\"quality\":80}"
            )
        )
        assertNull(HomeVerticalDetailRoutePolicy.parsePlayerPreloadCid("{\"cid\":0}"))
        assertNull(HomeVerticalDetailRoutePolicy.parsePlayerPreloadCid("not-json"))
        assertEquals(
            "bilibili://story/123456789?from=feed#page",
            HomeVerticalDetailRoutePolicy.sanitizeIntentHandlerUri(
                "bilibili://story/123456789?-%41router=story&from=feed&-Atype=story#page"
            )
        )
        assertNull(
            HomeVerticalDetailRoutePolicy.sanitizeIntentHandlerUri(
                "https://www.bilibili.com/video/BV1xx411c7mD?-Atype=story"
            )
        )
    }

    fun `mutates annotated concrete fields behind abstract getters and clears uri cache`() {
        val card = AnnotatedCard(
            cardGoto = "vertical_av",
            goTo = "vertical_av",
            uri = "bilibili://story/BV1xx411c7mD"
        )
        val snapshot = card.snapshot()
        val result = ConcreteHomeVerticalRouteMutator().apply(
            card,
            snapshot,
            rewritePlan("bilibili://video/BV1xx411c7mD", rewriteGoTo = true),
            readers()
        )

        assertEquals(HomeVerticalMutationResult.APPLIED, result)
        assertEquals("av", card.getCardGoto())
        assertEquals("av", card.getGoTo())
        assertEquals("bilibili://video/BV1xx411c7mD", card.getUri())
    }

    fun `rolls back earlier route writes when a later writer fails`() {
        val card = ThrowingUriCard()
        val snapshot = card.snapshot()
        val result = ConcreteHomeVerticalRouteMutator().apply(
            card,
            snapshot,
            rewritePlan("bilibili://video/BV1xx411c7mD", rewriteGoTo = false),
            readers()
        )

        assertEquals(HomeVerticalMutationResult.ROLLED_BACK, result)
        assertEquals("vertical_av", card.getCardGoto())
        assertEquals("bilibili://story/BV1xx411c7mD", card.getUri())
    }

    private fun AbstractRouteCard.snapshot(): HomeVerticalRouteSnapshot =
        HomeVerticalRouteSnapshot(
            cardGoto = getCardGoto(),
            goTo = getGoTo(),
            uri = getUri(),
            param = getParam()
        )

    private fun planOf(
        snapshot: HomeVerticalActivityLaunchSnapshot,
        backend: HomeVerticalDetailBackend
    ): HomeVerticalActivityLaunchPlan? =
        (HomeVerticalDetailRoutePolicy.planActivityLaunch(snapshot, backend)
            as? HomeVerticalActivityLaunchOutcome.Planned)?.plan

    private fun skipOf(
        snapshot: HomeVerticalActivityLaunchSnapshot,
        backend: HomeVerticalDetailBackend
    ): HomeVerticalLaunchSkip? =
        (HomeVerticalDetailRoutePolicy.planActivityLaunch(snapshot, backend)
            as? HomeVerticalActivityLaunchOutcome.Skipped)?.reason

    /**
     * 直接构造改写计划。原先经 `decide()` 生成，但该函数在单一写入点重构后已成死代码并
     * 于本次清理删除；直接构造反而让这两个用例真正隔离测试变异器本身。
     */
    private fun rewritePlan(
        detailUri: String,
        rewriteGoTo: Boolean
    ): HomeVerticalRoutePlan = HomeVerticalRoutePlan(
        identity = CanonicalHomeVideoId(CanonicalHomeVideoId.Kind.BV, "BV1xx411c7mD"),
        detailUri = detailUri,
        rewriteCardGoto = true,
        rewriteGoTo = rewriteGoTo,
        rewriteUri = true
    )

    private fun readers(): HomeVerticalReadAccessors = HomeVerticalReadAccessors(
        cardGoto = AbstractRouteCard::class.java.getMethod("getCardGoto"),
        goTo = AbstractRouteCard::class.java.getMethod("getGoTo"),
        uri = AbstractRouteCard::class.java.getMethod("getUri")
    )

    private abstract class AbstractRouteCard {
        abstract fun getCardGoto(): String
        abstract fun getGoTo(): String
        abstract fun getUri(): String?
        abstract fun getParam(): String?
    }

    private class AnnotatedCard(
        cardGoto: String,
        goTo: String,
        uri: String
    ) : AbstractRouteCard() {
        @field:SerializedName("card_goto")
        private var storedCardGoto: String = cardGoto

        @field:SerializedName("goto")
        private var storedGoTo: String = goTo

        @field:SerializedName("uri")
        private var storedUri: String = uri

        @Suppress("unused")
        private var stringUriCache: String? = uri

        override fun getCardGoto(): String = storedCardGoto
        override fun getGoTo(): String = storedGoTo
        override fun getUri(): String = stringUriCache ?: storedUri
        override fun getParam(): String = "BV1xx411c7mD"
    }

    private class ThrowingUriCard : AbstractRouteCard() {
        @field:SerializedName("card_goto")
        private var storedCardGoto: String = "vertical_av"

        private val originalUri = "bilibili://story/BV1xx411c7mD"

        override fun getCardGoto(): String = storedCardGoto
        override fun getGoTo(): String = "av"
        override fun getUri(): String = originalUri
        override fun getParam(): String = "BV1xx411c7mD"

        @Suppress("UNUSED_PARAMETER")
        fun setUri(value: String) {
            error("simulated host setter failure")
        }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HomeVerticalDetailFeatureInstallerTest) -> Unit) {
            val s = HomeVerticalDetailFeatureInstallerTest()
            block(s)
        }
        runIsolated { s -> s.`layer status compares only the installed layers with the expected landings`() }
        runIsolated { s -> s.`rewrites only a valid story route and preserves query`() }
        runIsolated { s -> s.`normalizes every identified video route but preserves story roots and conflicts`() }
        runIsolated { s -> s.`fails closed when the unit android jar exposes no activity launch hook`() }
        runIsolated { s -> s.`builds complete united launch contract only for numeric story with cid`() }
        runIsolated { s -> s.`reports a bounded reason for every united skip`() }
        runIsolated { s -> s.`lets the detail page vertical switch button reach the story page`() }
        runIsolated { s -> s.`keeps replacing story launches from every other origin`() }
        runIsolated { s -> s.`detail page origin predicate is an exact match not a prefix`() }
        runIsolated { s -> s.`legacy backend needs its own onCreate while united does not`() }
        runIsolated { s -> s.`handles story translucent on both backends`() }
        runIsolated { s -> s.`recovers identity from query string and intent extras without a path token`() }
        runIsolated { s -> s.`handles a real world story uri with an inlined dash manifest`() }
        runIsolated { s -> s.`falls back to legacy detail activity contract without united cid`() }
        runIsolated { s -> s.`parses bounded preload cid and only sanitizes forced story hints`() }
        runIsolated { s -> s.`mutates annotated concrete fields behind abstract getters and clears uri cache`() }
        runIsolated { s -> s.`rolls back earlier route writes when a later writer fails`() }
    }
}
