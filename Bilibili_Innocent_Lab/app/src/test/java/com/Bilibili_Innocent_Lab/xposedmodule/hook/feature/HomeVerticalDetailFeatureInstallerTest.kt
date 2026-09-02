package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.google.gson.annotations.SerializedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeVerticalDetailFeatureInstallerTest {

    @Test
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

    @Test
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

    @Test
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

    @Test
    fun `uses structured player aid when card route is absent but rejects non ugc player args`() {
        val decision = HomeVerticalDetailRoutePolicy.decide(
            HomeVerticalRouteSnapshot(
                cardGoto = "vertical_av",
                playerAid = 123456789L
            )
        )
        assertEquals(
            "bilibili://video/123456789",
            (decision as HomeVerticalRouteDecision.Rewrite).plan.detailUri
        )

        val nonUgc = HomeVerticalDetailRoutePolicy.decide(
            HomeVerticalRouteSnapshot(
                cardGoto = "vertical_av",
                playerAid = 123456789L,
                playerNonUgc = true
            )
        )
        assertEquals(
            HomeVerticalRouteDecision.Reason.UNSAFE_CONTENT_KIND,
            (nonUgc as HomeVerticalRouteDecision.KeepOriginal).reason
        )
    }

    @Test
    fun `plans only compatible story intent fallback and retargets explicit story activity`() {
        val direct = HomeVerticalDetailRoutePolicy.planIntentFallback(
            HomeVerticalIntentRouteSnapshot(
                dataUri = "bilibili://story_translucent/BV1xx411c7mD?from=feed",
                componentPackage = "tv.danmaku.bili",
                componentClass = "com.bilibili.video.story.StoryTransparentActivity"
            )
        )
        assertEquals("bilibili://video/BV1xx411c7mD?from=feed", direct?.detailUri)
        assertTrue(direct?.retargetToIntentHandler == true)

        val identityFromExtra = HomeVerticalDetailRoutePolicy.planIntentFallback(
            HomeVerticalIntentRouteSnapshot(
                dataUri = "bilibili://story?from=feed",
                targetPackage = "tv.danmaku.bili",
                aid = "123456789"
            )
        )
        assertEquals(
            "bilibili://video/123456789?from=feed",
            identityFromExtra?.detailUri
        )
        assertFalse(identityFromExtra?.retargetToIntentHandler ?: true)

        assertNull(
            HomeVerticalDetailRoutePolicy.planIntentFallback(
                HomeVerticalIntentRouteSnapshot(
                    dataUri = "bilibili://story/BV1xx411c7mD",
                    componentPackage = "com.example.other"
                )
            )
        )
        assertNull(
            HomeVerticalDetailRoutePolicy.planIntentFallback(
                HomeVerticalIntentRouteSnapshot(
                    dataUri = "bilibili://story/123456789",
                    componentPackage = "tv.danmaku.bili",
                    aid = "987654321"
                )
            )
        )
    }

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

    @Test
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
    @Test
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

    /** 宿主同时注册了 story_translucent；它与 story 的身份契约一致，不应整条放行。 */
    @Test
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
    @Test
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
    @Test
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

    @Test
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

    @Test
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

    @Test
    fun `builds a conservative plan for a normal vertical video`() {
        val decision = HomeVerticalDetailRoutePolicy.decide(
            HomeVerticalRouteSnapshot(
                cardGoto = "vertical_av",
                goTo = "vertical_av",
                uri = "bilibili://story/BV1xx411c7mD?from=feed",
                param = "BV1xx411c7mD"
            )
        )

        val plan = (decision as HomeVerticalRouteDecision.Rewrite).plan
        assertEquals(CanonicalHomeVideoId.Kind.BV, plan.identity.kind)
        assertEquals("BV1xx411c7mD", plan.identity.value)
        assertEquals("bilibili://video/BV1xx411c7mD?from=feed", plan.detailUri)
        assertTrue(plan.rewriteCardGoto)
        assertTrue(plan.rewriteGoTo)
        assertTrue(plan.rewriteUri)
    }

    @Test
    fun `keeps unsafe or conflicting vertical routes unchanged`() {
        val advertisement = HomeVerticalDetailRoutePolicy.decide(
            HomeVerticalRouteSnapshot(
                bizType = "AD",
                cardGoto = "vertical_av",
                uri = "bilibili://story/BV1xx411c7mD"
            )
        )
        assertEquals(
            HomeVerticalRouteDecision.Reason.UNSAFE_CONTENT_KIND,
            (advertisement as HomeVerticalRouteDecision.KeepOriginal).reason
        )

        val conflicting = HomeVerticalDetailRoutePolicy.decide(
            HomeVerticalRouteSnapshot(
                cardGoto = "vertical_av",
                uri = "bilibili://story/BV1xx411c7mD",
                param = "BV1Q5411c7mD"
            )
        )
        assertEquals(
            HomeVerticalRouteDecision.Reason.CONFLICTING_VIDEO_ID,
            (conflicting as HomeVerticalRouteDecision.KeepOriginal).reason
        )
    }

    @Test
    fun `accepts aid and BV as complementary identities but rejects same-kind conflicts`() {
        val complementary = HomeVerticalDetailRoutePolicy.decide(
            HomeVerticalRouteSnapshot(
                cardGoto = "vertical_av",
                uri = "bilibili://story/123456789?from=feed&-Arouter=story",
                param = "BV1xx411c7mD"
            )
        )

        val plan = (complementary as HomeVerticalRouteDecision.Rewrite).plan
        assertEquals("bilibili://video/123456789?from=feed", plan.detailUri)
        assertEquals(2, plan.aliases.size)
        assertTrue(
            CanonicalHomeVideoId(CanonicalHomeVideoId.Kind.AID, "123456789") in plan.aliases
        )
        assertTrue(
            CanonicalHomeVideoId(CanonicalHomeVideoId.Kind.BV, "BV1xx411c7mD") in plan.aliases
        )

        val conflictingAid = HomeVerticalDetailRoutePolicy.decide(
            HomeVerticalRouteSnapshot(
                cardGoto = "vertical_av",
                uri = "bilibili://story/123456789?aid=987654321"
            )
        )
        assertEquals(
            HomeVerticalRouteDecision.Reason.CONFLICTING_VIDEO_ID,
            (conflictingAid as HomeVerticalRouteDecision.KeepOriginal).reason
        )
    }

    @Test
    fun `supports query-only story identity and removes forced story hints`() {
        val decision = HomeVerticalDetailRoutePolicy.decide(
            HomeVerticalRouteSnapshot(
                cardGoto = "vertical_av",
                uri = "bilibili://story?aid=123456789&-Arouter=story&from=feed#page"
            )
        )

        val plan = (decision as HomeVerticalRouteDecision.Rewrite).plan
        assertEquals(
            "bilibili://video/123456789?aid=123456789&from=feed#page",
            plan.detailUri
        )
    }

    @Test
    fun `cleans forced story hints from an existing video route`() {
        val decision = HomeVerticalDetailRoutePolicy.decide(
            HomeVerticalRouteSnapshot(
                holderType = "vertical",
                cardGoto = "av",
                goTo = "av",
                uri = "bilibili://video/BV1xx411c7mD?-Atype=story&from=feed"
            )
        )

        val plan = (decision as HomeVerticalRouteDecision.Rewrite).plan
        assertEquals("bilibili://video/BV1xx411c7mD?from=feed", plan.detailUri)
        assertTrue(plan.rewriteUri)
    }

    @Test
    fun `mutates annotated concrete fields behind abstract getters and clears uri cache`() {
        val card = AnnotatedCard(
            cardGoto = "vertical_av",
            goTo = "vertical_av",
            uri = "bilibili://story/BV1xx411c7mD"
        )
        val snapshot = card.snapshot()
        val plan = (HomeVerticalDetailRoutePolicy.decide(snapshot) as
            HomeVerticalRouteDecision.Rewrite).plan
        val result = ConcreteHomeVerticalRouteMutator().apply(
            card,
            snapshot,
            plan,
            readers()
        )

        assertEquals(HomeVerticalMutationResult.APPLIED, result)
        assertEquals("av", card.getCardGoto())
        assertEquals("av", card.getGoTo())
        assertEquals("bilibili://video/BV1xx411c7mD", card.getUri())
    }

    @Test
    fun `rolls back earlier route writes when a later writer fails`() {
        val card = ThrowingUriCard()
        val snapshot = card.snapshot()
        val plan = (HomeVerticalDetailRoutePolicy.decide(snapshot) as
            HomeVerticalRouteDecision.Rewrite).plan
        val result = ConcreteHomeVerticalRouteMutator().apply(
            card,
            snapshot,
            plan,
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
}
