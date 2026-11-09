package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRecommendPurifyFeatureInstallerTest {
    @Test fun theSameInstalledHomeFilterReadsUpdatedValuesForLaterCards() {
        var rules = com.Bilibili_Innocent_Lab.xposedmodule.hook.config.LiveHomeRules(setOf("alpha"),
            VideoDurationRange(10, 20), VideoPlayCountRange(1, 2_000))
        val installer = HomeRecommendPurifyFeatureInstaller(false, false, false, false, false, true, "alpha",
            false, false, false, false, 10, 20, null, minPlayCount = 1, maxPlayCount = 2_000, liveRules = { rules })
        val method = installer.javaClass.declaredMethods.single { it.name == "shouldRemove" }.apply { isAccessible = true }
        val card = HomeRecommendPurifyFeatureInstaller.Signals(title = "beta", durationSeconds = 15, playCount = 1_000)
        fun removed() = method.invoke(installer, card, null) as Boolean
        assertFalse(removed())
        rules = rules.copy(titles = setOf("beta")); assertTrue(removed())
        rules = rules.copy(titles = emptySet(), duration = VideoDurationRange(30, 40)); assertTrue(removed())
        rules = rules.copy(duration = VideoDurationRange(10, 20), plays = VideoPlayCountRange(5_000, 6_000)); assertTrue(removed())
        rules = rules.copy(plays = VideoPlayCountRange(0, 0)); assertFalse(removed())
    }

    @Test fun `partial response registration cannot report complete success`() {
        val base = requireNotNull(VersionAdapter.locateHomeRecommendFeed(javaClass.classLoader!!))
        val points = base.copy(responseItemGetters = listOf(base.responseItemGetters.first(), base.responseItemGetters.first()))
        val statuses = mutableListOf<Pair<String, String>>()
        val env = environment(statuses).copy(registrar = object : HookRegistrar by TestHookRegistrar {
            override fun adapted(
                id: String,
                point: VersionAdapter.HookPoint,
                exceptionPolicy: HookExceptionPolicy,
                block: ModernMemberHookCreator.() -> Unit
            ) {
                if (id.endsWith(".0")) error("registration failed")
            }
        })
        val result = installer(0, 0, removeAds = true, points = points).install(env)
        assertTrue(result is FeatureInstallResult.Installed && !result.complete)
        assertEquals("partial:1/2", statuses.single().second)
    }
    @Test
    fun missingDurationHasItsOwnFailureWithoutDowngradingTheWorkingAdFilter() {
        val points = requireNotNull(VersionAdapter.locateHomeRecommendFeed(requireNotNull(javaClass.classLoader)))
            .copy(playerArgsGetter = null, playerArgsDurationField = null)
        val records = mutableListOf<FeatureInstallRecord>()
        val env = environment(mutableListOf()).copy(installationEvidence = { records += it })
        FeatureInstallCoordinator(env).installAll(listOf(installer(
            minSeconds = 30, maxSeconds = 0, removeAds = true, points = points
        )))
        assertEquals(true, (records.last { it.id == "home_recommend_ads_removed" }.result as FeatureInstallResult.Installed).complete)
        assertEquals(FeatureSkipReason.MISSING_HOST_STRUCTURE,
            (records.last { it.id == "home_recommend_duration_filter" }.result as FeatureInstallResult.Skipped).reasonCode)
    }

    @Test
    fun missingPlayCountGateHasItsOwnFailureWithoutDowngradingTheWorkingAdFilter() {
        val points = requireNotNull(VersionAdapter.locateHomeRecommendFeed(requireNotNull(javaClass.classLoader)))
            .copy(playerArgsGetter = null, playerArgsDurationField = null)
        val records = mutableListOf<FeatureInstallRecord>()
        val env = environment(mutableListOf()).copy(installationEvidence = { records += it })
        FeatureInstallCoordinator(env).installAll(listOf(installer(
            minSeconds = 0, maxSeconds = 0, minPlayCount = 10_000, maxPlayCount = 0, removeAds = true, points = points
        )))
        assertEquals(true, (records.last { it.id == "home_recommend_ads_removed" }.result as FeatureInstallResult.Installed).complete)
        assertEquals(FeatureSkipReason.MISSING_HOST_STRUCTURE,
            (records.last { it.id == "home_recommend_play_count_filter" }.result as FeatureInstallResult.Skipped).reasonCode)
    }


    private fun environment(statuses: MutableList<Pair<String, String>>) = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = javaClass.classLoader,
        hookPoints = HookPointRegistry(javaClass.classLoader),
        registrar = TestHookRegistrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { channel, status -> statuses += channel to status }
    )

    private fun installer(
        minSeconds: Int,
        maxSeconds: Int,
        minPlayCount: Int = 0,
        maxPlayCount: Int = 0,
        removeAds: Boolean = false,
        removeCmV2: Boolean = false,
        removeBanner: Boolean = false,
        sectionPickEnabled: Boolean = false,
        points: VersionAdapter.HomeRecommendFeedPoints? =
            VersionAdapter.locateHomeRecommendFeed(requireNotNull(javaClass.classLoader))
    ) = HomeRecommendPurifyFeatureInstaller(
        removeAds = removeAds,
        removeCmV2 = removeCmV2,
        removeBanner = removeBanner,
        removePictures = false,
        removeGamePromotions = false,
        titleFilterEnabled = false,
        rawTitleKeywords = "",
        removeLive = false,
        removeCourses = false,
        removeVertical = false,
        removeLarge = false,
        minDurationSeconds = minSeconds,
        maxDurationSeconds = maxSeconds,
        minPlayCount = minPlayCount,
        maxPlayCount = maxPlayCount,
        points = points,
        sectionPickEnabled = sectionPickEnabled
    )

    @Test
    fun `classifies ads pictures and game promotions from explicit signals`() {
        assertTrue(
            HomeRecommendPurifyFeatureInstaller.isAdvertisement(
                HomeRecommendPurifyFeatureInstaller.Signals(bizType = "AD")
            )
        )
        assertTrue(
            HomeRecommendPurifyFeatureInstaller.isPicture(
                HomeRecommendPurifyFeatureInstaller.Signals(uri = "bilibili://opus/123")
            )
        )
        assertTrue(
            HomeRecommendPurifyFeatureInstaller.isGamePromotion(
                HomeRecommendPurifyFeatureInstaller.Signals(goTo = "mini_game")
            )
        )
        assertTrue(
            HomeRecommendPurifyFeatureInstaller.isGamePromotion(
                HomeRecommendPurifyFeatureInstaller.Signals(
                    title = "小游戏试玩",
                    hasAdInfo = true
                )
            )
        )
        assertFalse(
            HomeRecommendPurifyFeatureInstaller.isGamePromotion(
                HomeRecommendPurifyFeatureInstaller.Signals(title = "游戏开发纪录片")
            )
        )
    }

    @Test
    fun `classifies home feed types only from explicit route signals`() {
        assertTrue(
            HomeRecommendPurifyFeatureInstaller.isLive(
                HomeRecommendPurifyFeatureInstaller.Signals(goTo = "live")
            )
        )
        assertTrue(
            HomeRecommendPurifyFeatureInstaller.isCourse(
                HomeRecommendPurifyFeatureInstaller.Signals(uri = "https://www.bilibili.com/cheese/play/ep1")
            )
        )
        assertTrue(
            HomeRecommendPurifyFeatureInstaller.isVertical(
                HomeRecommendPurifyFeatureInstaller.Signals(cardGoto = "vertical_av")
            )
        )
        assertTrue(
            HomeRecommendPurifyFeatureInstaller.isLarge(
                HomeRecommendPurifyFeatureInstaller.Signals(holderType = "large_cover_v9")
            )
        )
        assertFalse(
            HomeRecommendPurifyFeatureInstaller.isLive(
                HomeRecommendPurifyFeatureInstaller.Signals(title = "直播录像")
            )
        )
    }

    @Test
    fun `classifies home banner only from exact structured token`() {
        assertTrue(
            HomeRecommendPurifyFeatureInstaller.isHomeBanner(
                HomeRecommendPurifyFeatureInstaller.Signals(holderType = "banner_v8")
            )
        )
        assertTrue(
            HomeRecommendPurifyFeatureInstaller.isHomeBanner(
                HomeRecommendPurifyFeatureInstaller.Signals(cardType = "CARD_TYPE_BANNER_V8")
            )
        )
        assertFalse(
            HomeRecommendPurifyFeatureInstaller.isHomeBanner(
                HomeRecommendPurifyFeatureInstaller.Signals(holderType = "large_cover_v9")
            )
        )
        assertFalse(
            HomeRecommendPurifyFeatureInstaller.isHomeBanner(
                HomeRecommendPurifyFeatureInstaller.Signals(title = "首页 Banner 推荐")
            )
        )
    }

    @Test
    fun `classifies cm_v2 feed ads only from exact card type`() {
        assertTrue(
            HomeRecommendPurifyFeatureInstaller.isCmV2(
                HomeRecommendPurifyFeatureInstaller.Signals(cardType = "cm_v2")
            )
        )
        assertTrue(
            HomeRecommendPurifyFeatureInstaller.isCmV2(
                HomeRecommendPurifyFeatureInstaller.Signals(cardType = "CARD_TYPE_CM_V2")
            )
        )
        assertFalse(
            HomeRecommendPurifyFeatureInstaller.isCmV2(
                HomeRecommendPurifyFeatureInstaller.Signals(cardType = "small_cover_v2")
            )
        )
        assertFalse(
            HomeRecommendPurifyFeatureInstaller.isCmV2(
                HomeRecommendPurifyFeatureInstaller.Signals(cardType = "ogv_small_cover")
            )
        )
        assertFalse(
            HomeRecommendPurifyFeatureInstaller.isCmV2(
                HomeRecommendPurifyFeatureInstaller.Signals(cardType = "banner_v8")
            )
        )
        assertFalse(
            HomeRecommendPurifyFeatureInstaller.isCmV2(
                HomeRecommendPurifyFeatureInstaller.Signals(cardGoto = "ad_web_s")
            )
        )
    }

    @Test
    fun `cm_v2 switch installs alone and stays independent from ads switch`() {
        val statuses = mutableListOf<Pair<String, String>>()
        val points = requireNotNull(
            VersionAdapter.locateHomeRecommendFeed(requireNotNull(javaClass.classLoader))
        )

        assertEquals(
            FeatureInstallResult.Installed(points.responseItemGetters.size),
            installer(minSeconds = 0, maxSeconds = 0, removeCmV2 = true, points = points)
                .install(environment(statuses))
        )
        assertEquals(listOf("home_recommend_purify_status" to "success"), statuses)
    }

    @Test
    fun `duration-only configuration installs while an empty range stays hook free`() {
        val durationStatuses = mutableListOf<Pair<String, String>>()
        val bannerStatuses = mutableListOf<Pair<String, String>>()
        val disabledStatuses = mutableListOf<Pair<String, String>>()
        val points = requireNotNull(
            VersionAdapter.locateHomeRecommendFeed(requireNotNull(javaClass.classLoader))
        )

        assertEquals(
            FeatureInstallResult.Installed(points.responseItemGetters.size),
            installer(minSeconds = 30, maxSeconds = 0, points = points)
                .install(environment(durationStatuses))
        )
        assertEquals(
            FeatureInstallResult.Skipped("disabled"),
            installer(minSeconds = 0, maxSeconds = 0, points = null)
                .install(environment(disabledStatuses))
        )
        assertEquals(
            FeatureInstallResult.Installed(points.responseItemGetters.size),
            installer(
                minSeconds = 0,
                maxSeconds = 0,
                removeBanner = true,
                points = points
            ).install(environment(bannerStatuses))
        )
        assertEquals(listOf("home_recommend_purify_status" to "success"), durationStatuses)
        assertEquals(listOf("home_recommend_purify_status" to "success"), bannerStatuses)
        assertEquals(listOf("home_recommend_purify_status" to "disabled"), disabledStatuses)
    }

    @Test
    fun `missing duration accessor does not disable an existing content filter`() {
        val statuses = mutableListOf<Pair<String, String>>()
        val points = requireNotNull(
            VersionAdapter.locateHomeRecommendFeed(requireNotNull(javaClass.classLoader))
        ).copy(playerArgsGetter = null, playerArgsDurationField = null)

        assertEquals(
            FeatureInstallResult.Installed(points.responseItemGetters.size, complete = false),
            installer(
                minSeconds = 30,
                maxSeconds = 0,
                removeAds = true,
                points = points
            ).install(environment(statuses))
        )
        assertEquals(
            listOf(
                "home_recommend_purify_status" to
                    "partial:missing-duration-accessor"
            ),
            statuses
        )
    }

    @Test
    fun `shared args getter is invoked once for tag and author dimensions`() {
        val item = ProbeItem()
        val argsGetter = ProbeItem::class.java.getDeclaredMethod("getArgs").apply { isAccessible = true }
        val accessors = accessors(
            holderGetter = ProbeItem::class.java.getDeclaredMethod("getHolderType"),
            tidArgsGetter = argsGetter,
            authorArgsGetter = argsGetter
        )

        val signals = invokeSignals(
            installer(0, 0, points = null, sectionPickEnabled = true),
            item,
            accessors
        )

        assertEquals(1, item.argsCalls)
        assertEquals(101L, signals.tid)
        assertEquals("标签一", signals.tname)
        assertEquals(11L, signals.rid)
        assertEquals("UP 主", signals.upName)
        assertEquals("202", signals.upId)
    }

    @Test
    fun `different args getters are not incorrectly shared`() {
        val item = ProbeItem()
        val accessors = accessors(
            holderGetter = ProbeItem::class.java.getDeclaredMethod("getHolderType"),
            tidArgsGetter = ProbeItem::class.java.getDeclaredMethod("getArgs"),
            authorArgsGetter = ProbeItem::class.java.getDeclaredMethod("getAuthorArgs")
        )

        val signals = invokeSignals(
            installer(0, 0, points = null, sectionPickEnabled = true),
            item,
            accessors
        )

        assertEquals(1, item.argsCalls)
        assertEquals(1, item.authorArgsCalls)
        assertEquals(101L, signals.tid)
        assertEquals("作者参数", signals.upName)
        assertEquals("303", signals.upId)
    }

    @Test
    fun `args read failure remains fail open`() {
        val item = ProbeItem()
        val throwingGetter = ProbeItem::class.java
            .getDeclaredMethod("getThrowingArgs")
            .apply { isAccessible = true }
        val accessors = accessors(
            holderGetter = ProbeItem::class.java.getDeclaredMethod("getHolderType"),
            tidArgsGetter = throwingGetter,
            authorArgsGetter = throwingGetter
        )

        val signals = invokeSignals(
            installer(0, 0, points = null, sectionPickEnabled = true),
            item,
            accessors
        )

        assertEquals(null, signals.tid)
        assertEquals(null, signals.upName)
        assertEquals(null, signals.upId)
    }

    @Test
    fun `actual response hook preserves fail open and independent rule semantics`() {
        val blocked = HomeFeedCard(HomeFeedArgs(tid = 7L, upName = "目标 UP"), title = "普通")
        val noArgs = HomeFeedCard(null, title = "普通")
        val throwingArgs = HomeFeedCard(
            HomeFeedArgs(tid = 7L, upName = "目标 UP"),
            title = "普通",
            throwOnArgs = true
        )
        val titleOnly = HomeFeedCard(null, title = "包含关键词")
        val source = listOf(blocked, noArgs, throwingArgs, titleOnly)
        val recorded = PlayerPortTestRegistrar()
        val points = homeFeedPoints()
        val env = homeFeedEnvironment(recorded)
        val result = HomeRecommendPurifyFeatureInstaller(
            removeAds = false,
            removeCmV2 = false,
            removeBanner = false,
            removePictures = false,
            removeGamePromotions = false,
            titleFilterEnabled = true,
            rawTitleKeywords = "关键词",
            removeLive = false,
            removeCourses = false,
            removeVertical = false,
            removeLarge = false,
            minDurationSeconds = 0,
            maxDurationSeconds = 0,
            points = points,
            rawBlockedTids = "7",
            rawBlockedAuthors = "目标 UP"
        ).install(env)

        assertTrue(result is FeatureInstallResult.Installed)
        val filtered = recorded.invoke("home.recommend.purify.0", HomeFeedResponse(source)) { source }
        assertEquals(listOf(noArgs, throwingArgs), filtered)
        assertEquals(1, blocked.argsCalls)
        assertEquals(1, noArgs.argsCalls)
        assertEquals(1, throwingArgs.argsCalls)
        assertEquals(1, titleOnly.argsCalls)
        assertEquals(4, source.size)
    }

    @Test
    fun `actual response hook keeps original list when unreadable dimensions do not match`() {
        val source = listOf(
            HomeFeedCard(null, title = "普通"),
            HomeFeedCard(HomeFeedArgs(tid = 99L, upName = "其他"), title = "普通", throwOnArgs = true)
        )
        val recorded = PlayerPortTestRegistrar()
        HomeRecommendPurifyFeatureInstaller(
            removeAds = false,
            removeCmV2 = false,
            removeBanner = false,
            removePictures = false,
            removeGamePromotions = false,
            titleFilterEnabled = false,
            rawTitleKeywords = "",
            removeLive = false,
            removeCourses = false,
            removeVertical = false,
            removeLarge = false,
            minDurationSeconds = 0,
            maxDurationSeconds = 0,
            points = homeFeedPoints(),
            rawBlockedTids = "7",
            rawBlockedAuthors = "目标 UP"
        ).install(homeFeedEnvironment(recorded))

        val filtered = recorded.invoke("home.recommend.purify.0", HomeFeedResponse(source)) { source }
        assertSame(source, filtered)
        assertEquals(1, source[0].argsCalls)
        assertEquals(1, source[1].argsCalls)
    }

    @Test
    fun `ai declared dimension drops tagged and already confirmed cards and records the tagged aid`() {
        AiDeclaredVideoRegistry.resetForTest()
        try {
            AiDeclaredVideoRegistry.add(22L)
            val tagged = AiFeedCard("11", aiFeedUri("人工智能-aigc,音乐-aigc"))
            val confirmed = AiFeedCard("22", "bilibili://video/22?cid=1")
            val topicOnly = AiFeedCard("44", aiFeedUri(null))
            val plain = AiFeedCard("33", "bilibili://video/33?cid=1")
            val source = listOf(tagged, confirmed, topicOnly, plain)
            val recorded = PlayerPortTestRegistrar()
            val capabilities = mutableListOf<Pair<String, FeatureInstallResult>>()
            val env = homeFeedEnvironment(recorded).copy(capabilityEvidence = { id, result -> capabilities += id to result })
            val result = HomeRecommendPurifyFeatureInstaller(
                removeAds = false, removeCmV2 = false, removeBanner = false, removePictures = false,
                removeGamePromotions = false, titleFilterEnabled = false, rawTitleKeywords = "",
                removeLive = false, removeCourses = false, removeVertical = false, removeLarge = false,
                minDurationSeconds = 0, maxDurationSeconds = 0,
                points = aiFeedPoints(),
                removeAiDeclared = true
            ).install(env)

            assertTrue(result is FeatureInstallResult.Installed)
            val filtered = recorded.invoke("home.recommend.purify.0", AiFeedResponse(source)) { source }
            assertEquals(listOf(topicOnly, plain), filtered)
            assertTrue(AiDeclaredVideoRegistry.contains(11L))
            assertTrue(capabilities.any {
                it.first == AiDeclaredVideoPolicy.CAPABILITY_HOME && it.second is FeatureInstallResult.Installed
            })
        } finally {
            AiDeclaredVideoRegistry.resetForTest()
        }
    }

    private fun aiFeedPoints() = VersionAdapter.HomeRecommendFeedPoints(
        responseItemGetters = listOf(
            VersionAdapter.HookPoint(AiFeedResponse::class.java.name, "getItems", emptyList())
        ),
        holderTypeGetter = VersionAdapter.HookPoint(AiFeedCard::class.java.name, "getHolderType", emptyList()),
        bizTypeGetter = null,
        adInfoGetter = null,
        cardGotoGetter = null,
        goToGetter = null,
        uriGetter = VersionAdapter.HookPoint(AiFeedCard::class.java.name, "getUri", emptyList()),
        paramGetter = VersionAdapter.HookPoint(AiFeedCard::class.java.name, "getParam", emptyList()),
        titleGetter = null,
        subtitleGetter = null,
        descGetter = null,
        playerArgsGetter = null,
        playerArgsDurationField = null,
        argsGetter = null,
        argsTidGetter = null,
        argsTnameGetter = null,
        argsRidGetter = null,
        argsUpNameGetter = null,
        argsUpIdGetter = null
    )

    /** 与抓包同形：`player_preload` 是 URL 编码 JSON，`qn_feature` 是其中的 JSON 字符串。 */
    private fun aiFeedUri(creationTags: String?): String {
        // 按服务端顺序手工拼（ai_tags 在 creation_tags 前）；org.json 不保证键序。
        val feature = buildString {
            append("{\"ai_tags\":\"人工智能-aigc-其他ai生成内容\"")
            creationTags?.let { append(",\"creation_tags\":").append(org.json.JSONObject.quote(it)) }
            append("}")
        }
        val preload = org.json.JSONObject().put("qn_feature", feature)
        return "bilibili://video/1?cid=2&player_preload=" +
            java.net.URLEncoder.encode(preload.toString(), "UTF-8")
    }

    private fun homeFeedEnvironment(recorded: PlayerPortTestRegistrar) =
        environment(mutableListOf()).copy(registrar = object : HookRegistrar by recorded {
            override fun adapted(
                id: String,
                point: VersionAdapter.HookPoint,
                exceptionPolicy: HookExceptionPolicy,
                block: ModernMemberHookCreator.() -> Unit
            ) {
                recorded.exact(
                    id,
                    Class.forName(point.className),
                    point.methodName,
                    *point.paramClassNames.orEmpty().map { Class.forName(it) }.toTypedArray(),
                    block = block
                )
            }
        })

    private fun homeFeedPoints() = VersionAdapter.HomeRecommendFeedPoints(
        responseItemGetters = listOf(
            VersionAdapter.HookPoint(HomeFeedResponse::class.java.name, "getItems", emptyList())
        ),
        holderTypeGetter = VersionAdapter.HookPoint(HomeFeedCard::class.java.name, "getHolderType", emptyList()),
        bizTypeGetter = null,
        adInfoGetter = null,
        cardGotoGetter = null,
        goToGetter = null,
        uriGetter = null,
        paramGetter = null,
        titleGetter = VersionAdapter.HookPoint(HomeFeedCard::class.java.name, "getTitle", emptyList()),
        subtitleGetter = null,
        descGetter = null,
        playerArgsGetter = null,
        playerArgsDurationField = null,
        argsGetter = VersionAdapter.HookPoint(HomeFeedCard::class.java.name, "getArgs", emptyList()),
        argsTidGetter = VersionAdapter.HookPoint(HomeFeedArgs::class.java.name, "getTid", emptyList()),
        argsTnameGetter = VersionAdapter.HookPoint(HomeFeedArgs::class.java.name, "getTname", emptyList()),
        argsRidGetter = VersionAdapter.HookPoint(HomeFeedArgs::class.java.name, "getRid", emptyList()),
        argsUpNameGetter = VersionAdapter.HookPoint(HomeFeedArgs::class.java.name, "getUpName", emptyList()),
        argsUpIdGetter = VersionAdapter.HookPoint(HomeFeedArgs::class.java.name, "getUpId", emptyList())
    )

    private fun accessors(
        holderGetter: java.lang.reflect.Method,
        tidArgsGetter: java.lang.reflect.Method,
        authorArgsGetter: java.lang.reflect.Method
    ): Any {
        val owner = HomeRecommendPurifyFeatureInstaller::class.java
        val tidClass = Class.forName("${owner.name}\$TidAccessor")
        val authorClass = Class.forName("${owner.name}\$AuthorAccessor")
        val accessorsClass = Class.forName("${owner.name}\$Accessors")
        val argsClass = ProbeArgs::class.java
        fun argsMethod(name: String) = argsClass.getDeclaredMethod(name).apply { isAccessible = true }
        val tid = tidClass.declaredConstructors.single { it.parameterCount == 4 }.apply { isAccessible = true }
            .newInstance(
                tidArgsGetter.apply { isAccessible = true },
                argsMethod("getTid"),
                argsMethod("getTname"),
                argsMethod("getRid")
            )
        val author = authorClass.declaredConstructors.single { it.parameterCount == 3 }.apply { isAccessible = true }
            .newInstance(
                authorArgsGetter.apply { isAccessible = true },
                argsMethod("getUpName"),
                argsMethod("getUpId")
            )
        val constructor = accessorsClass.declaredConstructors.single { it.parameterCount == 16 }
            .apply { isAccessible = true }
        val values = arrayOfNulls<Any>(16)
        values[0] = holderGetter.apply { isAccessible = true }
        values[13] = tid
        values[14] = author
        return constructor.newInstance(*values)
    }

    private fun invokeSignals(
        installer: HomeRecommendPurifyFeatureInstaller,
        item: Any,
        accessors: Any
    ): HomeRecommendPurifyFeatureInstaller.Signals {
        val accessorsClass = accessors.javaClass
        val countsClass = HomeRecommendPurifyFeatureInstaller::class.java.declaredClasses
            .single { it.simpleName == "AdvertisementCounts" }
        val method = HomeRecommendPurifyFeatureInstaller::class.java
            .getDeclaredMethod("signals", Any::class.java, accessorsClass, countsClass, PublishTimeContext::class.java)
            .apply { isAccessible = true }
        return method.invoke(installer, item, accessors, null, null) as HomeRecommendPurifyFeatureInstaller.Signals
    }

    private class ProbeItem {
        var argsCalls = 0
        var authorArgsCalls = 0
        private val args = ProbeArgs(101L, "标签一", 11L, "UP 主", 202L)
        private val authorArgs = ProbeArgs(303L, "作者标签", 33L, "作者参数", 303L)

        fun getHolderType(): String = "small_cover_v2"

        fun getArgs(): ProbeArgs {
            argsCalls += 1
            return args
        }

        fun getAuthorArgs(): ProbeArgs {
            authorArgsCalls += 1
            return authorArgs
        }

        fun getThrowingArgs(): ProbeArgs = error("probe failure")
    }

    private class ProbeArgs(
        private val tid: Long,
        private val tname: String,
        private val rid: Long,
        private val upName: String,
        private val upId: Long
    ) {
        fun getTid(): Long = tid
        fun getTname(): String = tname
        fun getRid(): Long = rid
        fun getUpName(): String = upName
        fun getUpId(): Long = upId
    }
}

private class AiFeedResponse(private val items: List<AiFeedCard>) {
    fun getItems(): List<AiFeedCard> = items
}

private class AiFeedCard(private val param: String, private val uri: String) {
    fun getHolderType(): String = "small_cover_v2"
    fun getParam(): String = param
    fun getUri(): String = uri
}

private class HomeFeedResponse(private val items: List<HomeFeedCard>) {
    fun getItems(): List<HomeFeedCard> = items
}

private class HomeFeedCard(
    private val args: HomeFeedArgs?,
    private val title: String,
    private val throwOnArgs: Boolean = false
) {
    var argsCalls: Int = 0

    fun getHolderType(): String = "small_cover_v2"

    fun getTitle(): String = title

    fun getArgs(): HomeFeedArgs? {
        argsCalls += 1
        if (throwOnArgs) error("feed args failure")
        return args
    }
}

private class HomeFeedArgs(
    private val tid: Long = 0L,
    private val upName: String = "",
    private val tname: String = "",
    private val rid: Long = 0L,
    private val upId: Long = 0L
) {
    fun getTid(): Long = tid
    fun getTname(): String = tname
    fun getRid(): Long = rid
    fun getUpName(): String = upName
    fun getUpId(): Long = upId
}
