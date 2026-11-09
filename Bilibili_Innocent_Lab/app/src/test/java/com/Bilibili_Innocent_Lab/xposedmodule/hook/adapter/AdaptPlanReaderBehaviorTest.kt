package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlanCatalog.Flag
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlanCatalog.Gate
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlanCatalog.NonZero
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlanCatalog.Text
import com.Bilibili_Innocent_Lab.xposedmodule.hook.config.HookConfigSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.config.SnapshotHookConfigSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.BlockUpdateFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentFilterFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentPurifyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentSectionFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentTopologyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DynamicTabsFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences as P
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureSkipReason
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FullNumberFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HomeBannerFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HomeRecommendPurifyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HomeTopBarFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HomeVerticalDetailFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineVipFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PausedAdFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PgcAutoActivityPopupFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerInteractiveOverlayFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortraitFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualityFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerStatusBarFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SectionPickFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SplashAdFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.StoryPurifyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.TeenagersModeFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.VideoRelateFilterFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Constructor
import java.util.Random

/**
 * 目录里每个「有条件读取」的安装器，对着真实安装器类验证：
 * **目录判定该读取者不需要定位结果时，安装器自己也是关闭状态，不读定位点、不注册任何 Hook。**
 *
 * 做法：用与 HookEntry 相同的配置键和默认值构造真实安装器（定位点给 null），在随机配置下安装；
 * 目录闸门为假时，结果必须是 `Skipped(disabled)` 且注册数为 0。若某个安装器还有目录没登记的启用键，
 * 随机配置总会出现「目录说不需要、安装器却在往下走」的组合，测试因此失败——这是目录漏登记的行为证据，
 * 与 `AdaptPlanWiringContractTest` 的源码核对互相印证。
 */
class AdaptPlanReaderBehaviorTest {
    private class CountingRegistrar : HookRegistrar {
        var calls = 0
        override fun first(id: String, className: String, methodName: String, block: ModernMemberHookCreator.() -> Unit) { calls++ }
        override fun all(id: String, className: String, methodName: String, block: ModernMemberHookCreator.() -> Unit) { calls++ }
        override fun exact(id: String, owner: Class<*>, methodName: String, vararg parameterTypes: Class<*>, block: ModernMemberHookCreator.() -> Unit) { calls++ }
        override fun adapted(id: String, point: VersionAdapter.HookPoint, exceptionPolicy: HookExceptionPolicy, block: ModernMemberHookCreator.() -> Unit) { calls++ }
        override fun constructor(id: String, constructor: Constructor<*>, block: ModernMemberHookCreator.() -> Unit) { calls++ }
    }

    private fun environment(registrar: HookRegistrar): HookEnvironment = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = javaClass.classLoader,
        hookPoints = HookPointRegistry(javaClass.classLoader),
        registrar = registrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { _, _ -> }
    )

    private fun HookConfigSource.flag(key: String, default: Boolean = false) = getBoolean(key, default)
    private fun HookConfigSource.text(key: String) = getString(key, "")
    private fun HookConfigSource.number(key: String) = getInt(key, 0)

    /** 与 HookEntry 对应安装器的构造参数一致（配置键、默认值）；定位点一律为 null。 */
    private val factories: Map<String, (HookConfigSource) -> FeatureInstaller> = mapOf(
        "PausedAdFeatureInstaller" to { c -> PausedAdFeatureInstaller(c.flag(HookEntry.PREF_ENABLED, true), null) },
        "HomeBannerFeatureInstaller" to { c -> HomeBannerFeatureInstaller(c.flag(HookEntry.PREF_BANNER_ENABLED, true), null) { true } },
        "HomeTopBarFeatureInstaller" to { c ->
            HomeTopBarFeatureInstaller(c.flag(P.HIDE_HOME_GAME_MENU), c.flag(P.HIDE_HOME_SEARCH_DEFAULT_WORD), null)
        },
        "MineVipFeatureInstaller" to { c ->
            MineVipFeatureInstaller(c.flag(P.HIDE_MINE_VIP), c.flag(P.KEEP_MINE_VIP_SPACE), null)
        },
        "BlockUpdateFeatureInstaller" to { c -> BlockUpdateFeatureInstaller(c.flag(P.BLOCK_APP_UPDATE), null) },
        "DynamicTabsFeatureInstaller" to { c ->
            DynamicTabsFeatureInstaller(c.flag(P.HIDE_DYNAMIC_CITY_TAB), c.flag(P.HIDE_DYNAMIC_SCHOOL_TAB), c.flag(P.PREFER_DYNAMIC_VIDEO_TAB), null)
        },
        "FullNumberFeatureInstaller" to { c -> FullNumberFeatureInstaller(c.flag(P.SHOW_FULL_NUMBERS), null) },
        "PlayerPortraitFeatureInstaller" to { c -> PlayerPortraitFeatureInstaller(c.flag(P.HIDE_PLAYER_PORTRAIT_CONTROL), null) },
        "PlayerInteractiveOverlayFeatureInstaller" to { c ->
            PlayerInteractiveOverlayFeatureInstaller(c.flag(P.HIDE_PLAYER_INTERACTIVE_OVERLAYS), null)
        },
        "PgcAutoActivityPopupFeatureInstaller" to { c ->
            PgcAutoActivityPopupFeatureInstaller(c.flag(P.HIDE_PGC_AUTO_ACTIVITY_POPUP), null)
        },
        "PlayerStatusBarFeatureInstaller" to { c -> PlayerStatusBarFeatureInstaller(c.flag(P.TRANSPARENT_PLAYER_STATUS_BAR), null) },
        "HomeVerticalDetailFeatureInstaller" to { c -> HomeVerticalDetailFeatureInstaller(c.flag(P.HOME_VERTICAL_OPEN_DETAIL), null) },
        "HomeRecommendPurifyFeatureInstaller" to { c ->
            HomeRecommendPurifyFeatureInstaller(
                removeAds = c.flag(P.REMOVE_HOME_RECOMMEND_ADS),
                removeCmV2 = c.flag(P.REMOVE_HOME_RECOMMEND_CM_V2),
                removeBanner = c.flag(HookEntry.PREF_BANNER_ENABLED, true),
                removePictures = c.flag(P.REMOVE_HOME_RECOMMEND_PICTURES),
                removeGamePromotions = c.flag(P.REMOVE_HOME_RECOMMEND_GAME_PROMOTIONS),
                titleFilterEnabled = c.flag(P.HOME_RECOMMEND_TITLE_FILTER_ENABLED),
                rawTitleKeywords = c.text(P.HOME_RECOMMEND_TITLE_FILTER_KEYWORDS),
                removeLive = c.flag(P.REMOVE_HOME_RECOMMEND_LIVE),
                removeCourses = c.flag(P.REMOVE_HOME_RECOMMEND_COURSES),
                removeVertical = c.flag(P.REMOVE_HOME_RECOMMEND_VERTICAL),
                removeLarge = c.flag(P.REMOVE_HOME_RECOMMEND_LARGE),
                minDurationSeconds = c.number(P.RECOMMEND_VIDEO_MIN_DURATION_SECONDS),
                maxDurationSeconds = c.number(P.RECOMMEND_VIDEO_MAX_DURATION_SECONDS),
                points = null,
                removePgc = c.flag(P.REMOVE_HOME_RECOMMEND_PGC),
                removeSpecialCards = c.flag(P.REMOVE_HOME_RECOMMEND_SPECIAL_CARDS),
                removeComic = c.flag(P.REMOVE_HOME_RECOMMEND_COMIC),
                rawBlockedTids = c.text(P.HOME_RECOMMEND_BLOCKED_TIDS),
                rawBlockedAuthors = c.text(P.HOME_RECOMMEND_BLOCKED_AUTHORS),
                sectionPickEnabled = c.flag(P.HOME_RECOMMEND_SECTION_PICK_ENABLED),
                minPlayCount = c.number(P.RECOMMEND_VIDEO_MIN_PLAY_COUNT),
                maxPlayCount = c.number(P.RECOMMEND_VIDEO_MAX_PLAY_COUNT),
                removeAiDeclared = c.flag(P.BLOCK_AI_DECLARED_VIDEOS),
                aiDeclaredStrongMode = c.flag(P.BLOCK_AI_DECLARED_VIDEOS_STRONG_MODE),
                rawPublishTimeRule = c.text(P.RECOMMEND_VIDEO_PUBLISH_TIME_RULE)
            )
        },
        "SectionPickFeatureInstaller" to { c -> SectionPickFeatureInstaller(c.flag(P.HOME_RECOMMEND_SECTION_PICK_ENABLED), null, null) },
        "VideoRelateFilterFeatureInstaller" to { c ->
            VideoRelateFilterFeatureInstaller(
                hiddenTypes = buildSet {
                    if (c.flag(P.REMOVE_RELATE_COMMERCIAL)) add("CM")
                    if (c.flag(P.REMOVE_RELATE_GAME)) add("GAME")
                    if (c.flag(P.REMOVE_RELATE_LIVE)) add("LIVE")
                    if (c.flag(P.REMOVE_RELATE_COURSE)) add("COURSE")
                    if (c.flag(P.REMOVE_RELATE_SPECIAL)) add("SPECIAL")
                },
                minDurationSeconds = c.number(P.RECOMMEND_VIDEO_MIN_DURATION_SECONDS),
                maxDurationSeconds = c.number(P.RECOMMEND_VIDEO_MAX_DURATION_SECONDS),
                matchingEnhancementEnabled = c.flag(P.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED),
                strongModeEnabled = c.flag(P.VIDEO_RELATE_STRONG_MODE_ENABLED),
                reasonFilterEnabled = c.flag(P.VIDEO_RELATE_REASON_FILTER_ENABLED),
                rawReasonKeywords = c.text(P.VIDEO_RELATE_REASON_FILTER_KEYWORDS),
                points = null,
                rawBlockedAuthors = c.text(P.VIDEO_RELATE_BLOCKED_AUTHORS),
                rawBlockedTags = c.text(P.VIDEO_RELATE_BLOCKED_TAGS),
                sectionPickEnabled = c.flag(P.HOME_RECOMMEND_SECTION_PICK_ENABLED),
                rawPickedTagIds = c.text(P.HOME_RECOMMEND_BLOCKED_TIDS),
                minPlayCount = c.number(P.RECOMMEND_VIDEO_MIN_PLAY_COUNT),
                maxPlayCount = c.number(P.RECOMMEND_VIDEO_MAX_PLAY_COUNT),
                rawPublishTimeRule = c.text(P.RECOMMEND_VIDEO_PUBLISH_TIME_RULE)
            )
        },
        "StoryPurifyFeatureInstaller" to { c ->
            StoryPurifyFeatureInstaller(
                c.flag(P.REMOVE_STORY_ADS), c.flag(P.REMOVE_STORY_LIVE), c.flag(P.REMOVE_STORY_GAMES),
                c.flag(P.REMOVE_STORY_BANGUMI), c.flag(P.REMOVE_STORY_COURSES), c.flag(P.REMOVE_STORY_SHORT_DRAMA),
                c.flag(P.REMOVE_STORY_SHOPPING), c.flag(P.REMOVE_STORY_MOVIES), c.flag(P.REMOVE_STORY_DOCUMENTARIES),
                c.flag(P.REMOVE_STORY_TV), c.flag(P.REMOVE_STORY_VARIETY), c.flag(P.REMOVE_STORY_MUSIC), null
            )
        },
        "PlayerQualityFeatureInstaller" to { c -> PlayerQualityFeatureInstaller(c.number(P.PLAYER_DEFAULT_QUALITY_QN), null) },
        "TeenagersModeFeatureInstaller" to { c -> TeenagersModeFeatureInstaller(c.flag(P.BLOCK_TEENAGERS_MODE_PROMPT), null) },
        "CommentPurifyFeatureInstaller" to { c ->
            CommentPurifyFeatureInstaller(
                c.flag(P.REMOVE_COMMENT_SEARCH_LINKS), c.flag(P.REMOVE_COMMENT_EMPTY_GUIDE), c.flag(P.REMOVE_COMMENT_VOTE_WIDGETS),
                c.flag(P.REMOVE_COMMENT_FOLLOW_BUTTONS), c.flag(P.REMOVE_COMMENT_QOE), c.flag(P.REMOVE_COMMENT_OPERATIONS),
                c.flag(P.BLOCK_COMMENT_QUICK_REPLY), null
            )
        },
        "CommentFilterFeatureInstaller" to { c ->
            CommentFilterFeatureInstaller(
                keywordFilterEnabled = c.flag(P.COMMENT_KEYWORD_FILTER_ENABLED),
                rawKeywords = c.text(P.COMMENT_FILTER_KEYWORDS),
                minimumLevelFilterEnabled = c.flag(P.COMMENT_MIN_LEVEL_FILTER_ENABLED),
                minimumLevel = c.getInt(P.COMMENT_MIN_LEVEL, CommentFilterFeatureInstaller.DEFAULT_MIN_LEVEL),
                removeAtOnlyComments = c.flag(P.REMOVE_AT_ONLY_COMMENTS),
                userFilterEnabled = c.flag(P.COMMENT_USER_FILTER_ENABLED),
                rawUserRules = c.text(P.COMMENT_USER_FILTER_RULES),
                points = null
            )
        },
        "CommentTopologyFeatureInstaller" to { c -> CommentTopologyFeatureInstaller(c.flag(P.REPLY_TOPOLOGY_ENABLED), null, null, null) },
        "CommentSectionFeatureInstaller" to { c -> CommentSectionFeatureInstaller(c.flag(P.HIDE_COMMENT_SECTION), null) },
        "SplashAdFeatureInstaller" to { c -> SplashAdFeatureInstaller(c.flag(P.PURIFY_SPLASH_ADS), null) }
    )

    private val booleanRefines = setOf(P.KEEP_MINE_VIP_SPACE)

    private val readers: List<Pair<String, AdaptPlanCatalog.Reader>> = AdaptPlanCatalog.specs
        .flatMap { spec -> spec.readers.filter { it.gate is Gate.AnyOf } }
        .map { it.installer.substringBefore('.') to it }
        .distinctBy { it.first }

    /** 每个键的取值：false/空/0 是关闭，其余是开启；从属键（关键词、规则、阈值）随机取值。 */
    private fun randomConfig(random: Random, density: Int): SnapshotHookConfigSource {
        val values = linkedMapOf<String, Any>()
        for ((_, reader) in readers) {
            val gate = (reader.gate as Gate.AnyOf).signals
            for (signal in gate) {
                val on = random.nextInt(10) < density
                values[signal.keys.single()] = when (signal) {
                    is Flag -> on
                    is Text -> if (on) "rule" else ""
                    is NonZero -> if (on) 80 else 0
                }
            }
            for (key in reader.refines) values.putIfAbsent(key, when {
                key in booleanRefines -> random.nextBoolean()
                key == P.COMMENT_MIN_LEVEL -> random.nextInt(7)
                else -> if (random.nextBoolean()) "a,b" else ""
            })
        }
        return SnapshotHookConfigSource(values)
    }

    private fun assertInertWhenCatalogSaysNotNeeded(config: HookConfigSource, context: String) {
        for ((name, reader) in readers) {
            if (reader.active(config)) continue
            val registrar = CountingRegistrar()
            val result = factories.getValue(name)(config).install(environment(registrar))
            assertTrue("$context $name: 目录说不需要，安装器却返回 $result", result is FeatureInstallResult.Skipped)
            assertEquals("$context $name: ${(result as FeatureInstallResult.Skipped).reason}", FeatureSkipReason.DISABLED, result.reasonCode)
            assertEquals("$context $name 不应注册任何 Hook", 0, registrar.calls)
        }
    }

    @Test fun `every conditional reader has a real installer to check against`() {
        assertEquals(readers.map { it.first }.toSet(), factories.keys)
        assertTrue(readers.size >= 23)
    }

    @Test fun `all switches off leaves every conditional installer disabled`() {
        val off = SnapshotHookConfigSource(buildMap {
            for ((_, reader) in readers) for (signal in (reader.gate as Gate.AnyOf).signals) {
                put(signal.keys.single(), when (signal) { is Flag -> false; is Text -> ""; is NonZero -> 0 })
            }
        })
        assertInertWhenCatalogSaysNotNeeded(off, "all-off")
    }

    @Test fun `random configurations never make an unneeded reader install`() {
        val random = Random(20261029L)
        repeat(400) { round -> assertInertWhenCatalogSaysNotNeeded(randomConfig(random, density = 1 + round % 4), "round=$round") }
    }

    @Test fun `switches that cannot start an installer alone are a small documented over-approximation`() {
        // 目录取超集：单个开关打开后安装器仍然关闭的组合（需要配套关键词/规则/判定器才生效）。
        // 这是安全方向的多定位；记录下来，数量不许悄悄变多。
        val overBroad = sortedSetOf<String>()
        for ((name, reader) in readers) for (signal in (reader.gate as Gate.AnyOf).signals) {
            val key = signal.keys.single()
            val values = buildMap<String, Any> {
                for ((_, other) in readers) for (s in (other.gate as Gate.AnyOf).signals) {
                    put(s.keys.single(), when (s) { is Flag -> false; is Text -> ""; is NonZero -> 0 })
                }
                put(key, when (signal) { is Flag -> true; is Text -> "rule"; is NonZero -> 80 })
            }
            val config = SnapshotHookConfigSource(values)
            if (!reader.active(config)) continue
            val result = factories.getValue(name)(config).install(environment(CountingRegistrar()))
            if (result is FeatureInstallResult.Skipped && result.reasonCode == FeatureSkipReason.DISABLED) overBroad += "$name/$key"
        }
        println("over-approximated switches (${overBroad.size}): $overBroad")
        assertTrue("过度放宽的开关太多: $overBroad", overBroad.size <= 12)
    }
}
