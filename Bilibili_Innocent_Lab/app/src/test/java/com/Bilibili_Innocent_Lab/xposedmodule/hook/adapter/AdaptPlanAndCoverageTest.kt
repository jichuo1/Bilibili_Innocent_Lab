package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptAssistGate.Attempt
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlanCatalog.Flag
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlanCatalog.Gate
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlanCatalog.NonZero
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlanCatalog.Signal
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Constructor
import java.util.Random

class AdaptPlanAndCoverageTest {

    @Test
    fun `adapt coverage planner timings and story resource access contracts`() {
        fun plan(vararg families: PointFamily) = AdaptPlan(1L, families.toSet(), emptySet())

        val coverage = AdaptCoverage(
            mapOf(
                PointFamily.FULL_NUMBERS to Attempt.FOUND,
                PointFamily.BANNER to Attempt.CONCLUSIVE_MISSING,
                PointFamily.COMMENT_FILTER to Attempt.RETRYABLE,
                PointFamily.STORY_FEED to Attempt.NOT_REQUESTED
            )
        )
        val json = coverage.toJson()
        assertEquals(setOf("full_numbers", "banner", "comment_filter"), json.keys().asSequence().toSet())
        val restored = AdaptCoverage.fromJson(JSONObject(json.toString()))
        assertEquals(coverage, restored)
        assertEquals(Attempt.NOT_REQUESTED, restored.attempt(PointFamily.STORY_FEED))
        assertEquals(setOf(PointFamily.FULL_NUMBERS, PointFamily.BANNER, PointFamily.COMMENT_FILTER), restored.attempted)

        val malformed = AdaptCoverage.fromJson(
            JSONObject()
                .put("future_family", "FOUND")
                .put("full_numbers", "DONE")
                .put("banner", 3)
                .put("story_feed", "FOUND")
        )
        assertEquals(Attempt.FOUND, malformed.attempt(PointFamily.STORY_FEED))
        assertEquals(Attempt.NOT_REQUESTED, malformed.attempt(PointFamily.FULL_NUMBERS))
        assertEquals(Attempt.NOT_REQUESTED, malformed.attempt(PointFamily.BANNER))
        assertEquals(setOf(PointFamily.STORY_FEED), malformed.attempted)

        assertEquals(PointFamily.entries.size, PointFamily.entries.map { it.key }.toSet().size)
        for (family in PointFamily.entries) assertSame(family, PointFamily.fromKey(family.key))
        assertNull(PointFamily.fromKey("nope"))

        val liveCov = AdaptCoverage(mapOf(PointFamily.FULL_NUMBERS to Attempt.CONCLUSIVE_MISSING))
        val cachedCov = AdaptCoverage(mapOf(PointFamily.FULL_NUMBERS to Attempt.FOUND, PointFamily.BANNER to Attempt.FOUND))
        val merged = requireNotNull(AdaptCoverage.merge(liveCov, cachedCov))
        assertEquals(Attempt.CONCLUSIVE_MISSING, merged.attempt(PointFamily.FULL_NUMBERS))
        assertEquals(Attempt.FOUND, merged.attempt(PointFamily.BANNER))
        assertSame(cachedCov, AdaptCoverage.merge(null, cachedCov))
        assertSame(liveCov, AdaptCoverage.merge(liveCov, null))
        assertNull(AdaptCoverage.merge(null, null))

        val required = plan(PointFamily.FULL_NUMBERS, PointFamily.BANNER, PointFamily.STORY_FEED, PointFamily.MINE_VIP)
        assertEquals(required.required, AdaptPlanner.missing(null, required))
        assertEquals(required.required, AdaptPlanner.missing(AdaptCoverage.EMPTY, required))
        val partialCov = AdaptCoverage(
            mapOf(
                PointFamily.FULL_NUMBERS to Attempt.FOUND,
                PointFamily.BANNER to Attempt.CONCLUSIVE_MISSING,
                PointFamily.STORY_FEED to Attempt.RETRYABLE
            )
        )
        assertEquals(setOf(PointFamily.MINE_VIP), AdaptPlanner.missing(partialCov, required))
        assertEquals(emptySet<PointFamily>(), AdaptPlanner.missing(partialCov, plan(PointFamily.FULL_NUMBERS)))
        assertEquals(emptySet<PointFamily>(), AdaptPlanner.missing(null, plan()))
        val before = AdaptCoverage(mapOf(PointFamily.FULL_NUMBERS to Attempt.FOUND))
        assertTrue(AdaptPlanner.missing(before, plan(PointFamily.FULL_NUMBERS)).isEmpty())
        assertEquals(setOf(PointFamily.SPLASH_ADS), AdaptPlanner.missing(before, plan(PointFamily.FULL_NUMBERS, PointFamily.SPLASH_ADS)))

        val skippedCalls = mutableListOf<PointFamily>()
        val skipRun = SyncLocatorRun(plan(PointFamily.BANNER), observer = { family, _, _ -> skippedCalls += family })
        var evaluated = false
        assertNull(skipRun.locate(PointFamily.STORY_FEED) { evaluated = true; "x" })
        assertFalse(evaluated)
        assertEquals(Attempt.NOT_REQUESTED, skipRun.attemptOf(PointFamily.STORY_FEED))
        assertTrue(skippedCalls.isEmpty())
        assertEquals(Attempt.NOT_REQUESTED, skipRun.coverage().attempt(PointFamily.STORY_FEED))

        val outcomes = mutableMapOf<PointFamily, Attempt>()
        val outcomeRun = SyncLocatorRun(
            plan(PointFamily.BANNER, PointFamily.STORY_FEED, PointFamily.MINE_VIP, PointFamily.FULL_NUMBERS),
            observer = { family, elapsed, outcome ->
                assertTrue(elapsed >= 0)
                outcomes[family] = outcome
            }
        )
        assertEquals("b", outcomeRun.locate(PointFamily.BANNER) { "b" })
        assertNull(outcomeRun.locate<String>(PointFamily.STORY_FEED) { null })
        assertEquals("", outcomeRun.locate<String>(PointFamily.MINE_VIP, found = { it.isNotEmpty() }) { "" })
        assertNull(outcomeRun.locate<String>(PointFamily.FULL_NUMBERS) { throw IllegalStateException("temporary") })
        assertEquals(Attempt.FOUND, outcomeRun.attemptOf(PointFamily.BANNER))
        assertEquals(Attempt.CONCLUSIVE_MISSING, outcomeRun.attemptOf(PointFamily.STORY_FEED))
        assertEquals(Attempt.CONCLUSIVE_MISSING, outcomeRun.attemptOf(PointFamily.MINE_VIP))
        assertEquals(Attempt.RETRYABLE, outcomeRun.attemptOf(PointFamily.FULL_NUMBERS))
        assertEquals(outcomeRun.coverage().attempted.associateWith(outcomeRun::attemptOf), outcomes)
        val linkage = SyncLocatorRun(plan(PointFamily.BANNER))
        assertNull(linkage.locate<String>(PointFamily.BANNER) { throw NoClassDefFoundError("x") })
        assertEquals(Attempt.RETRYABLE, linkage.attemptOf(PointFamily.BANNER))

        val timings = LocateTimings()
        val timingRun = SyncLocatorRun(plan(PointFamily.BANNER, PointFamily.STORY_FEED, PointFamily.MINE_VIP), observer = timings)
        timingRun.locate(PointFamily.BANNER) { "a" }
        timingRun.locate<String>(PointFamily.STORY_FEED) { null }
        assertEquals(2, timings.familyCount)
        assertTrue(timings.totalNanos >= 0)
        timings.located(PointFamily.MINE_VIP, 9_000_000L, Attempt.FOUND)
        assertEquals(3, timings.familyCount)
        assertTrue(timings.summary(top = 1).contains("slowest=[mine_vip=9000us]"))
        assertTrue(timings.summary().startsWith("families=3/${PointFamily.entries.size}"))

        val vmRun = SyncLocatorRun(plan(PointFamily.BANNER))
        try {
            vmRun.locate<String>(PointFamily.BANNER) { throw StackOverflowError() }
            fail("StackOverflowError 必须继续抛出")
        } catch (_: StackOverflowError) {
            assertEquals(Attempt.NOT_REQUESTED, vmRun.attemptOf(PointFamily.BANNER))
        }

        val prevCov = AdaptCoverage(mapOf(PointFamily.BANNER to Attempt.FOUND))
        val carryRun = SyncLocatorRun(plan(PointFamily.STORY_FEED))
        var carryEvaluated = false
        assertEquals("old", carryRun.locateOrCarry(PointFamily.BANNER, "old", prevCov) { carryEvaluated = true; "new" })
        assertFalse(carryEvaluated)
        assertNull(carryRun.locateOrCarry(PointFamily.MINE_VIP, "stale", prevCov) { "new" })
        assertNull(carryRun.locateOrCarry(PointFamily.BANNER, "legacy", null) { "new" })
        assertEquals("fresh", carryRun.locateOrCarry(PointFamily.STORY_FEED, "old", prevCov) { "fresh" })
        assertEquals(Attempt.FOUND, carryRun.attemptOf(PointFamily.STORY_FEED))

        StoryQualityResourceAccessCases().verifyAllCases()
    }

    @Test
    fun `adapt plan catalog dependencies signals and monotonicity invariants`() {
        val catalogCases = AdaptPlanCatalogCases()
        catalogCases.verifyAllCases()
    }

    @Test
    fun `adapt plan conditional readers stay inert when catalog disables them`() {
        val readerCases = AdaptPlanReaderCases()
        readerCases.verifyAllCases()
    }
}

private class AdaptPlanCatalogCases {
    private class Group(val name: String, val signals: List<Signal>, val families: Set<PointFamily>)

    private fun flags(vararg keys: String) = keys.map { Flag(it) }

    private val rangeSignals: List<Signal> = listOf(
        NonZero(P.RECOMMEND_VIDEO_MIN_DURATION_SECONDS), NonZero(P.RECOMMEND_VIDEO_MAX_DURATION_SECONDS),
        NonZero(P.RECOMMEND_VIDEO_MIN_PLAY_COUNT), NonZero(P.RECOMMEND_VIDEO_MAX_PLAY_COUNT),
        Text(P.RECOMMEND_VIDEO_PUBLISH_TIME_RULE)
    )

    private val groups: List<Group> = listOf(
        Group("暂停页广告", listOf(Flag(HookEntry.PREF_ENABLED, true)), setOf(PointFamily.PAUSE)),
        Group("首页轮播", listOf(Flag(HookEntry.PREF_BANNER_ENABLED, true)), setOf(PointFamily.BANNER, PointFamily.HOME_RECOMMEND_FEED)),
        Group("我的 VIP", flags(P.HIDE_MINE_VIP), setOf(PointFamily.MINE_VIP)),
        Group("拦截更新", flags(P.BLOCK_APP_UPDATE), setOf(PointFamily.BLOCK_UPDATE)),
        Group("动态页签", flags(P.HIDE_DYNAMIC_CITY_TAB, P.HIDE_DYNAMIC_SCHOOL_TAB, P.PREFER_DYNAMIC_VIDEO_TAB), setOf(PointFamily.DYNAMIC_TABS)),
        Group("完整数字", flags(P.SHOW_FULL_NUMBERS), setOf(PointFamily.FULL_NUMBERS)),
        Group("播放器竖屏控件", flags(P.HIDE_PLAYER_PORTRAIT_CONTROL), setOf(PointFamily.PLAYER_PORTRAIT)),
        Group("播放器互动层", flags(P.HIDE_PLAYER_INTERACTIVE_OVERLAYS), setOf(PointFamily.PLAYER_INTERACTIVE_OVERLAYS)),
        Group("影视活动半屏", flags(P.HIDE_PGC_AUTO_ACTIVITY_POPUP), setOf(PointFamily.PGC_AUTO_ACTIVITY_POPUP)),
        Group("透明状态栏", flags(P.TRANSPARENT_PLAYER_STATUS_BAR), setOf(PointFamily.PLAYER_STATUS_BAR)),
        Group("首页竖屏详情", flags(P.HOME_VERTICAL_OPEN_DETAIL), setOf(PointFamily.HOME_RECOMMEND_FEED)),
        Group(
            "首页推荐净化",
            flags(
                P.REMOVE_HOME_RECOMMEND_ADS, P.REMOVE_HOME_RECOMMEND_CM_V2, P.REMOVE_HOME_RECOMMEND_PICTURES,
                P.REMOVE_HOME_RECOMMEND_GAME_PROMOTIONS, P.HOME_RECOMMEND_TITLE_FILTER_ENABLED,
                P.REMOVE_HOME_RECOMMEND_LIVE, P.REMOVE_HOME_RECOMMEND_PGC, P.REMOVE_HOME_RECOMMEND_COMIC,
                P.REMOVE_HOME_RECOMMEND_SPECIAL_CARDS, P.REMOVE_HOME_RECOMMEND_COURSES,
                P.REMOVE_HOME_RECOMMEND_VERTICAL, P.REMOVE_HOME_RECOMMEND_LARGE, P.BLOCK_AI_DECLARED_VIDEOS,
                P.BLOCK_AI_DECLARED_VIDEOS_STRONG_MODE
            ) + listOf(Text(P.HOME_RECOMMEND_BLOCKED_TIDS), Text(P.HOME_RECOMMEND_BLOCKED_AUTHORS)),
            setOf(PointFamily.HOME_RECOMMEND_FEED)
        ),
        Group("版块挑选（共享首页推荐与相关推荐）", flags(P.HOME_RECOMMEND_SECTION_PICK_ENABLED), setOf(PointFamily.HOME_RECOMMEND_FEED, PointFamily.VIDEO_RELATE)),
        Group("视频语义过滤（共享首页推荐与相关推荐）", flags(P.VIDEO_SEMANTIC_FILTER_ENABLED), setOf(PointFamily.HOME_RECOMMEND_FEED, PointFamily.VIDEO_RELATE)),
        Group("推荐区间/发布时间规则（共享首页推荐与相关推荐）", rangeSignals, setOf(PointFamily.HOME_RECOMMEND_FEED, PointFamily.VIDEO_RELATE)),
        Group(
            "相关推荐过滤",
            flags(
                P.REMOVE_RELATE_COMMERCIAL, P.REMOVE_RELATE_GAME, P.REMOVE_RELATE_LIVE, P.REMOVE_RELATE_COURSE,
                P.REMOVE_RELATE_SPECIAL, P.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED,
                P.VIDEO_RELATE_STRONG_MODE_ENABLED, P.VIDEO_RELATE_REASON_FILTER_ENABLED
            ) + listOf(Text(P.VIDEO_RELATE_BLOCKED_AUTHORS), Text(P.VIDEO_RELATE_BLOCKED_TAGS)),
            setOf(PointFamily.VIDEO_RELATE)
        ),
        Group(
            "Story 净化",
            flags(
                P.REMOVE_STORY_ADS, P.REMOVE_STORY_LIVE, P.REMOVE_STORY_GAMES,
                P.REMOVE_STORY_BANGUMI, P.REMOVE_STORY_COURSES, P.REMOVE_STORY_SHORT_DRAMA,
                P.REMOVE_STORY_SHOPPING, P.REMOVE_STORY_MOVIES, P.REMOVE_STORY_DOCUMENTARIES,
                P.REMOVE_STORY_TV, P.REMOVE_STORY_VARIETY, P.REMOVE_STORY_MUSIC
            ),
            setOf(PointFamily.STORY_FEED)
        ),
        Group("默认画质", listOf(NonZero(P.PLAYER_DEFAULT_QUALITY_QN)), setOf(PointFamily.PLAYER_QUALITY)),
        Group("青少年模式提示", flags(P.BLOCK_TEENAGERS_MODE_PROMPT), setOf(PointFamily.TEENAGERS_MODE)),
        Group(
            "评论区净化",
            flags(
                P.REMOVE_COMMENT_SEARCH_LINKS, P.REMOVE_COMMENT_EMPTY_GUIDE, P.REMOVE_COMMENT_VOTE_WIDGETS,
                P.REMOVE_COMMENT_FOLLOW_BUTTONS, P.REMOVE_COMMENT_QOE, P.REMOVE_COMMENT_OPERATIONS,
                P.BLOCK_COMMENT_QUICK_REPLY
            ),
            setOf(PointFamily.COMMENT_PURIFY)
        ),
        Group(
            "评论过滤",
            flags(
                P.COMMENT_KEYWORD_FILTER_ENABLED, P.COMMENT_MIN_LEVEL_FILTER_ENABLED, P.REMOVE_AT_ONLY_COMMENTS,
                P.COMMENT_USER_FILTER_ENABLED, P.COMMENT_SEMANTIC_FILTER_ENABLED
            ),
            setOf(PointFamily.COMMENT_FILTER)
        ),
        Group("回复脉络", flags(P.REPLY_TOPOLOGY_ENABLED), setOf(PointFamily.COMMENT_TOPOLOGY)),
        Group("评论区折叠", flags(P.HIDE_COMMENT_SECTION), setOf(PointFamily.COMMENT_SECTION)),
        Group("开屏广告", flags(P.PURIFY_SPLASH_ADS), setOf(PointFamily.SPLASH_ADS))
    )

    private val alwaysWithReason: Map<PointFamily, String> = mapOf(
        PointFamily.COMMENT_LOW to "评论自由复制 runtime flag",
        PointFamily.COMMENT_HIGH to "评论自由复制 runtime flag",
        PointFamily.MINE_ENTRY to "MINE_COMPONENTS 的定位依赖",
        PointFamily.HOME_TOP_BAR to "首页标签居中无开关",
        PointFamily.HOME_TABS to "首页 Tab 扫描面",
        PointFamily.HOME_COMPONENTS to "首页组件扫描面",
        PointFamily.MINE_COMPONENTS to "我的页扫描面",
        PointFamily.MINE_ACCOUNT_MINE to "我的页扫描面",
        PointFamily.BOTTOM_BAR to "底栏扫描面"
    )

    private fun allSignals(): List<Signal> = groups.flatMap { it.signals }.distinctBy { it.keys.single() }

    private fun offValue(signal: Signal): Any = when (signal) {
        is Flag -> false
        is Text -> ""
        is NonZero -> signal.off
    }

    private fun onValue(signal: Signal): Any = when (signal) {
        is Flag -> true
        is Text -> "rule"
        is NonZero -> signal.off + 80
    }

    private fun allOff(): MutableMap<String, Any> =
        allSignals().associateTo(linkedMapOf()) { it.keys.single() to offValue(it) }

    private fun plan(values: Map<String, Any>, revision: Long = 7L) =
        AdaptPlanner.build(SnapshotHookConfigSource(values, revision))

    fun verifyAllCases() {
        for (family in PointFamily.entries) {
            val spec = requireNotNull(AdaptPlanCatalog.spec(family)) { "$family 没有登记读取者" }
            assertTrue("$family 读取者为空", spec.readers.isNotEmpty())
            assertTrue("$family 缺依据", spec.evidence.isNotBlank())
        }
        assertEquals(alwaysWithReason.keys, AdaptPlanCatalog.alwaysFamilies)
        for (family in alwaysWithReason.keys) {
            val always = AdaptPlanCatalog.spec(family)!!.readers.filter { it.gate == Gate.Always }
            assertTrue("$family 没有无条件读取者", always.isNotEmpty())
            assertTrue("$family 无说明", always.all { it.note.isNotBlank() })
        }

        fun latest(family: PointFamily) = AdaptPlanCatalog.spec(family)!!.readers.filter { it.gate == Gate.Always }.map { it.latest }.toSet()
        assertEquals(setOf(AdaptPlanCatalog.Latest.RUNTIME_FLAG), latest(PointFamily.COMMENT_LOW))
        assertEquals(setOf(AdaptPlanCatalog.Latest.RUNTIME_FLAG), latest(PointFamily.COMMENT_HIGH))
        for (family in listOf(
            PointFamily.HOME_TABS, PointFamily.HOME_COMPONENTS, PointFamily.MINE_COMPONENTS,
            PointFamily.MINE_ACCOUNT_MINE, PointFamily.BOTTOM_BAR, PointFamily.HOME_TOP_BAR
        )) assertEquals(family.name, setOf(AdaptPlanCatalog.Latest.RESIDENT_SCAN), latest(family))
        for (spec in AdaptPlanCatalog.specs) for (reader in spec.readers.filter { it.gate is Gate.AnyOf }) {
            assertEquals("${spec.family}/${reader.installer}", AdaptPlanCatalog.Latest.INSTALL_CHAIN, reader.latest)
        }

        assertEquals(setOf(PointFamily.MINE_ENTRY), AdaptPlanCatalog.spec(PointFamily.MINE_COMPONENTS)!!.dependsOn)
        assertEquals(setOf(PointFamily.MINE_COMPONENTS, PointFamily.MINE_ENTRY), AdaptPlanCatalog.closure(setOf(PointFamily.MINE_COMPONENTS)))

        val offPlan = plan(allOff())
        assertEquals(alwaysWithReason.keys, offPlan.required)
        assertEquals(alwaysWithReason.keys, offPlan.always)
        assertEquals(PointFamily.entries.size - alwaysWithReason.size, offPlan.pruned.size)
        assertEquals(7L, offPlan.configRevision)
        assertTrue("最小配置确实裁掉了定位器", offPlan.pruned.size >= 20)

        val defaultPlan = plan(emptyMap())
        assertEquals(alwaysWithReason.keys + setOf(PointFamily.PAUSE, PointFamily.BANNER, PointFamily.HOME_RECOMMEND_FEED), defaultPlan.required)
        assertEquals(listOf("PausedAdFeatureInstaller"), defaultPlan.requestedBy(PointFamily.PAUSE))
        assertTrue("HomeRecommendPurifyFeatureInstaller" in defaultPlan.requestedBy(PointFamily.HOME_RECOMMEND_FEED))

        for (group in groups) for (signal in group.signals) {
            val values = allOff()
            values[signal.keys.single()] = onValue(signal)
            assertEquals("${group.name}/${signal.keys.single()}", alwaysWithReason.keys + group.families, plan(values).required)
        }

        val onlyVertical = allOff().also { it[P.HOME_VERTICAL_OPEN_DETAIL] = true }
        assertTrue(PointFamily.HOME_RECOMMEND_FEED in plan(onlyVertical).required)
        assertFalse(PointFamily.VIDEO_RELATE in plan(onlyVertical).required)
        val onlyPick = allOff().also { it[P.HOME_RECOMMEND_SECTION_PICK_ENABLED] = true }
        assertTrue(plan(onlyPick).required.containsAll(setOf(PointFamily.HOME_RECOMMEND_FEED, PointFamily.VIDEO_RELATE)))
        val onlyTopology = allOff().also { it[P.REPLY_TOPOLOGY_ENABLED] = true }
        val topologyRequired = plan(onlyTopology).required
        assertTrue(PointFamily.COMMENT_TOPOLOGY in topologyRequired)
        assertFalse(PointFamily.COMMENT_FILTER in topologyRequired)
        assertFalse(PointFamily.COMMENT_PURIFY in topologyRequired)

        val random = Random(20261029L)
        val signals = allSignals()
        repeat(150) { round ->
            val values = allOff()
            val density = random.nextInt(4)
            for (signal in signals) if (random.nextInt(8) < density) values[signal.keys.single()] = onValue(signal)
            val expected = linkedSetOf<PointFamily>().apply {
                addAll(alwaysWithReason.keys)
                for (group in groups) if (group.signals.any { it.active(SnapshotHookConfigSource(values)) }) addAll(group.families)
            }
            val p = plan(values)
            assertEquals("round=$round", expected, p.required)
            assertTrue(p.required.containsAll(p.always))
        }

        val monoRandom = Random(42L)
        repeat(40) {
            val values = allOff()
            var previous = plan(values).required
            for (signal in signals.shuffled(monoRandom)) {
                values[signal.keys.single()] = onValue(signal)
                val next = plan(values).required
                assertTrue(next.containsAll(previous))
                previous = next
            }
            assertEquals(PointFamily.entries.toSet(), previous)
        }

        val wrongValues = allOff()
        wrongValues[P.SHOW_FULL_NUMBERS] = "true"
        wrongValues[P.PLAYER_DEFAULT_QUALITY_QN] = 80L
        wrongValues[P.HOME_RECOMMEND_BLOCKED_TIDS] = 5
        assertEquals(alwaysWithReason.keys, plan(wrongValues).required)

        val failOpenPlan = AdaptPlanner.build(SnapshotHookConfigSource(allOff()), specOf = { null })
        assertEquals(PointFamily.entries.toSet(), failOpenPlan.required)
        assertEquals(PointFamily.entries.toSet(), failOpenPlan.always)
        assertEquals(listOf("unmapped(fail-open)"), failOpenPlan.requestedBy(PointFamily.PLAYER_QUALITY))

        val source = object : HookConfigSource {
            var reads = 0
            override val revision: Long = 99L
            override fun getBoolean(key: String, defaultValue: Boolean): Boolean { reads++; return false }
            override fun getInt(key: String, defaultValue: Int): Int { reads++; return 0 }
            override fun getLong(key: String, defaultValue: Long): Long = error("计划不读 Long")
            override fun getString(key: String, defaultValue: String): String { reads++; return "" }
        }
        val derivedPlan = AdaptPlanner.build(source)
        assertEquals(99L, derivedPlan.configRevision)
        assertTrue(source.reads > 0)
        assertEquals(alwaysWithReason.keys, derivedPlan.required)

        val full = AdaptPlan.full(5L)
        assertEquals(PointFamily.entries.toSet(), full.required)
        assertEquals(alwaysWithReason.keys, full.always)
        assertSame(full, full.including(setOf(PointFamily.BLOCK_UPDATE)))
        val minimal = plan(allOff())
        val grown = minimal.including(setOf(PointFamily.BLOCK_UPDATE, PointFamily.PLAYER_QUALITY))
        assertEquals(minimal.required + setOf(PointFamily.BLOCK_UPDATE, PointFamily.PLAYER_QUALITY), grown.required)
        assertEquals(minimal.configRevision, grown.configRevision)
        assertEquals(listOf("explicit-flag"), grown.requestedBy(PointFamily.BLOCK_UPDATE))

        assertTrue(PointFamily.BLOCK_UPDATE in plan(allOff().also { it[P.BLOCK_APP_UPDATE] = true }).required)
        assertTrue(PointFamily.COMMENT_TOPOLOGY in plan(allOff().also { it[P.REPLY_TOPOLOGY_ENABLED] = true }).required)
        assertTrue(PointFamily.PLAYER_QUALITY in plan(allOff().also { it[P.PLAYER_DEFAULT_QUALITY_QN] = 80 }).required)
    }
}

private class AdaptPlanReaderCases {
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

    private val factories: Map<String, (HookConfigSource) -> FeatureInstaller> = mapOf(
        "PausedAdFeatureInstaller" to { c -> PausedAdFeatureInstaller(c.flag(HookEntry.PREF_ENABLED, true), null) },
        "HomeBannerFeatureInstaller" to { c -> HomeBannerFeatureInstaller(c.flag(HookEntry.PREF_BANNER_ENABLED, true), null) { true } },
        "HomeTopBarFeatureInstaller" to { c -> HomeTopBarFeatureInstaller(c.flag(P.HIDE_HOME_GAME_MENU), c.flag(P.HIDE_HOME_SEARCH_DEFAULT_WORD), null) },
        "MineVipFeatureInstaller" to { c -> MineVipFeatureInstaller(c.flag(P.HIDE_MINE_VIP), c.flag(P.KEEP_MINE_VIP_SPACE), null) },
        "BlockUpdateFeatureInstaller" to { c -> BlockUpdateFeatureInstaller(c.flag(P.BLOCK_APP_UPDATE), null) },
        "DynamicTabsFeatureInstaller" to { c -> DynamicTabsFeatureInstaller(c.flag(P.HIDE_DYNAMIC_CITY_TAB), c.flag(P.HIDE_DYNAMIC_SCHOOL_TAB), c.flag(P.PREFER_DYNAMIC_VIDEO_TAB), null) },
        "FullNumberFeatureInstaller" to { c -> FullNumberFeatureInstaller(c.flag(P.SHOW_FULL_NUMBERS), null) },
        "PlayerPortraitFeatureInstaller" to { c -> PlayerPortraitFeatureInstaller(c.flag(P.HIDE_PLAYER_PORTRAIT_CONTROL), null) },
        "PlayerInteractiveOverlayFeatureInstaller" to { c -> PlayerInteractiveOverlayFeatureInstaller(c.flag(P.HIDE_PLAYER_INTERACTIVE_OVERLAYS), null) },
        "PgcAutoActivityPopupFeatureInstaller" to { c -> PgcAutoActivityPopupFeatureInstaller(c.flag(P.HIDE_PGC_AUTO_ACTIVITY_POPUP), null) },
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
            for (key in reader.refines) values.putIfAbsent(
                key,
                when {
                    key in booleanRefines -> random.nextBoolean()
                    key == P.COMMENT_MIN_LEVEL -> random.nextInt(7)
                    else -> if (random.nextBoolean()) "a,b" else ""
                }
            )
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

    fun verifyAllCases() {
        assertEquals(readers.map { it.first }.toSet(), factories.keys)
        assertTrue(readers.size >= 23)

        val off = SnapshotHookConfigSource(
            buildMap {
                for ((_, reader) in readers) for (signal in (reader.gate as Gate.AnyOf).signals) {
                    put(signal.keys.single(), when (signal) { is Flag -> false; is Text -> ""; is NonZero -> 0 })
                }
            }
        )
        assertInertWhenCatalogSaysNotNeeded(off, "all-off")

        val random = Random(20261029L)
        repeat(400) { round -> assertInertWhenCatalogSaysNotNeeded(randomConfig(random, density = 1 + round % 4), "round=$round") }

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
        assertTrue("过度放宽的开关太多: $overBroad", overBroad.size <= 12)
    }
}

private class StoryQualityResourceAccessCases {
    enum class DrmType { No, WideVine }
    enum class CanWatchReason { CAN_WATCH_QN_REASON_DEFAULT, CAN_WATCH_QN_REASON_VIP_QN_TRY_WATCH, UNKNOWN }
    enum class LimitWatchReason { LIMIT_WATCH_QN_REASON_DEFAULT, LIMIT_WATCH_QN_REASON_CTCC_HOME_NETWORK_DEVICE_LIMIT, UNRECOGNIZED }
    class PlayIndex(@JvmField var mQuality: Int, var available: Boolean = true) {
        @JvmField var mNeedLogin = false
        @JvmField var mNeedVip = false
        @JvmField var mDrmType: DrmType? = DrmType.No
        @JvmField var qualityInvalid = false
        @JvmField var canWatchReason: CanWatchReason? = null
        @JvmField var limitWatchReason: LimitWatchReason? = null
    }
    class VodIndex(@JvmField var mVodList: ArrayList<PlayIndex>)
    class Resource(@JvmField var mVodIndex: VodIndex) {
        var from = SOURCE_FROM_FLASH
        var resolved = 0
        var hdr = false
        var dolby = false
        var vivid = false
        var drm = false
        var helperCalls = 0
        var writes = 0
        var pairOverride: Pair<Int, Int>? = null
        var throwHelper = false
        var throwWriteOnce = false
        var ignoreWrite = false
        var wrongWriteOnce = false
        var failReadBackOnce = false
        var awaitingReadBack = false
        var dashQns: Set<Int> = emptySet()
        fun sourceFrom() = from
        fun getPlayIndex(): PlayIndex? = mVodIndex.mVodList.getOrNull(resolved)
        fun getResolvedIndex(): Int {
            if (awaitingReadBack && failReadBackOnce) {
                failReadBackOnce = false
                throw IllegalStateException("readback fixture")
            }
            return resolved
        }
        fun setResolvedIndex(index: Int) {
            writes++
            if (ignoreWrite) return
            resolved = if (wrongWriteOnce) 0 else index
            wrongWriteOnce = false
            awaitingReadBack = true
            if (throwWriteOnce) {
                throwWriteOnce = false
                throw IllegalStateException("write fixture")
            }
        }
        fun isHdr() = hdr
        fun isDolbyVision() = dolby
        fun isHdrVivid() = vivid
        fun isDrm() = drm || getPlayIndex()?.mDrmType != DrmType.No
        fun findMaxAvailableQuality(target: Int): Pair<Int, Int>? {
            helperCalls++
            check(!throwHelper)
            pairOverride?.let { return it }
            var best: Pair<Int, Int>? = null
            mVodIndex.mVodList.forEachIndexed { index, play ->
                if (play.mQuality in 1..target && (play.available || play.mQuality in dashQns) &&
                    play.mQuality > (best?.second ?: 0)
                ) best = index to play.mQuality
            }
            return best
        }
        companion object {
            const val SOURCE_FROM_FLASH = 17
            const val SOURCE_FROM_NORMAL = 31
            const val QUALITY_HDR = 300
            const val QUALITY_DOLBY = 301
            const val QUALITY_HDRVivid = 302
        }
    }

    private fun access() = requireNotNull(StoryQualityResourceAccess.resolve(Resource::class.java, VodIndex::class.java, PlayIndex::class.java))
    private fun resource(vararg qualities: Int) = Resource(VodIndex(qualities.map { PlayIndex(it) }.toCollection(arrayListOf())))

    fun verifyAllCases() {
        val access = access()

        // Proposal is read-only and apply verifies index & quality
        run {
            val res = resource(16, 64, 80, 120)
            val candidate = requireNotNull(access.selection(res, 112, login = true, vip = false))
            assertEquals(2, candidate.index)
            assertEquals(80, candidate.qn)
            assertEquals(0, res.resolved)
            assertEquals(0, res.writes)
            assertTrue(access.apply(res, candidate))
            assertEquals(2, res.resolved)
            assertEquals(80, access.currentQuality(res))
            assertFalse(access.apply(res, candidate))
            assertEquals(1, res.writes)
        }

        // Source identity uses host constants
        run {
            val res = resource(16, 80)
            assertTrue(access.isPreloaded(res))
            res.from = Resource.SOURCE_FROM_NORMAL
            assertFalse(access.isPreloaded(res))
            assertNotNull(access.selection(res, 80, true, false))
            res.from = 1
            assertFalse(access.isPreloaded(res))
            assertNull(access.selection(res, 80, true, false))
            assertEquals(0, res.writes)
        }

        // Login and VIP rights
        run {
            val res = resource(16, 64, 80, 120)
            res.mVodIndex.mVodList[2].mNeedLogin = true
            res.mVodIndex.mVodList[3].mNeedVip = true
            assertEquals(64, access.selection(res, 120, false, false)?.qn)
            assertEquals(80, access.selection(res, 120, true, false)?.qn)
            assertEquals(120, access.selection(res, 120, true, true)?.qn)
            assertNull(access.selection(res, 120, false, false, ownVideo = true))
        }

        // Try watch / device limit / invalid / unknown
        run {
            val res = resource(16, 64, 80, 120)
            res.mVodIndex.mVodList[3].canWatchReason = CanWatchReason.CAN_WATCH_QN_REASON_VIP_QN_TRY_WATCH
            res.mVodIndex.mVodList[2].limitWatchReason = LimitWatchReason.LIMIT_WATCH_QN_REASON_CTCC_HOME_NETWORK_DEVICE_LIMIT
            assertEquals(64, access.selection(res, 120, true, true)?.qn)
            res.mVodIndex.mVodList[0].canWatchReason = CanWatchReason.UNKNOWN
            assertNull(access.selection(res, 120, true, true))
            assertEquals(0, res.writes)
        }
        run {
            val res = resource(16, 80)
            val higher = res.mVodIndex.mVodList[1]
            higher.canWatchReason = CanWatchReason.CAN_WATCH_QN_REASON_DEFAULT
            higher.limitWatchReason = LimitWatchReason.LIMIT_WATCH_QN_REASON_DEFAULT
            assertEquals(80, access.selection(res, 80, true, true)?.qn)
            higher.qualityInvalid = true
            assertNull(access.selection(res, 80, true, true))
            higher.qualityInvalid = false
            higher.limitWatchReason = LimitWatchReason.UNRECOGNIZED
            assertNull(access.selection(res, 80, true, true))
        }

        // Special & DRM
        for (setSpecial in listOf<(Resource) -> Unit>({ it.hdr = true }, { it.dolby = true }, { it.vivid = true }, { it.drm = true })) {
            val res = resource(16, 80)
            setSpecial(res)
            assertEquals(true, access.isSpecial(res))
            assertNull(access.selection(res, 80, true, true))
            assertEquals(0, res.writes)
        }
        run {
            val res = resource(16, 80, Resource.QUALITY_HDR)
            res.mVodIndex.mVodList[1].mDrmType = DrmType.WideVine
            assertNull(access.selection(res, Resource.QUALITY_HDR, true, true))
            res.mVodIndex.mVodList[1].mDrmType = null
            assertNull(access.selection(res, 80, true, true))
            assertNull(access.isSpecial(Any()))
        }

        // DASH, fallback, forged pair
        run {
            val res = resource(16, 80)
            res.mVodIndex.mVodList[1].available = false
            res.dashQns = setOf(80)
            assertEquals(80, access.selection(res, 80, true, false)?.qn)
            res.mVodIndex.mVodList[1].mNeedVip = true
            assertNull(access.selection(res, 80, true, false))
        }
        run {
            val res = resource(16, 80, 120)
            res.mVodIndex.mVodList[2].available = false
            assertEquals(80, access.selection(res, 120, true, false)?.qn)
            res.mVodIndex.mVodList.add(PlayIndex(96).also { it.mNeedVip = true })
            assertNull(access.selection(res, 120, true, false))
        }
        run {
            val res = resource(16, 80, 120)
            res.mVodIndex.mVodList[2].mNeedVip = true
            for (pair in listOf(1 to 64, 2 to 120, -1 to 80, 9 to 80)) {
                res.pairOverride = pair
                assertNull(access.selection(res, 80, true, false))
            }
            assertEquals(0, res.writes)
        }

        // List overflow & malformed list item
        run {
            val res = Resource(VodIndex(ArrayList(List(129) { PlayIndex(16 + it) })))
            assertNull(access.selection(res, 200, true, true))
            assertEquals(0, res.helperCalls)
            res.mVodIndex.mVodList.removeAt(128)
            assertNotNull(access.selection(res, 200, true, true))
        }
        @Suppress("UNCHECKED_CAST")
        run {
            val res = resource(16, 80)
            (res.mVodIndex.mVodList as MutableList<Any?>).add(Any())
            assertNull(access.selection(res, 80, true, false))
            assertEquals(0, res.helperCalls)
        }

        // Apply guards & restore
        run {
            val res = resource(16, 80)
            val candidate = requireNotNull(access.selection(res, 80, true, false))
            assertFalse(access.apply(resource(16, 80), candidate))
            res.mVodIndex.mVodList = ArrayList(res.mVodIndex.mVodList)
            assertFalse(access.apply(res, candidate))
            assertEquals(0, res.writes)
        }
        run {
            val res = resource(16, 64, 80)
            val candidate = requireNotNull(access.selection(res, 80, true, false))
            res.mVodIndex.mVodList[2].mNeedVip = true
            assertFalse(access.apply(res, candidate))
            assertEquals(0, res.writes)
            res.mVodIndex.mVodList[2].mNeedVip = false
            val next = requireNotNull(access.selection(res, 80, true, false))
            res.resolved = 1
            assertFalse(access.apply(res, next))
            assertEquals(1, res.resolved)
            assertEquals(0, res.writes)
        }
        run {
            val res = resource(16, 80)
            val candidate = requireNotNull(access.selection(res, 80, true, false))
            res.throwWriteOnce = true
            assertFalse(access.apply(res, candidate))
            assertEquals(0, res.resolved)
            assertEquals(2, res.writes)
        }
        for (setFailure in listOf<(Resource) -> Unit>({ it.ignoreWrite = true }, { it.wrongWriteOnce = true }, { it.failReadBackOnce = true })) {
            val res = resource(16, 80)
            val candidate = requireNotNull(access.selection(res, 80, true, false))
            setFailure(res)
            assertFalse(access.apply(res, candidate))
            assertEquals(0, res.resolved)
        }
        run {
            val res = resource(16, 80)
            assertNull(access.selection(res, 0, true, true))
            assertNull(access.selection(res, 16, true, true))
            res.throwHelper = true
            assertNull(access.selection(res, 80, true, true))
            assertEquals(0, res.writes)
            assertNull(access.currentQuality(Any()))
            assertNull(StoryQualityResourceAccess.resolve(Any::class.java, VodIndex::class.java, PlayIndex::class.java))
        }

        // Accepted / eligibility / legacy
        run {
            val res = resource(16, 80)
            assertTrue(access.currentEligible(res, true, false))
            assertFalse(access.accepted(res, 80, true, false))
            val candidate = requireNotNull(access.selection(res, 80, true, false))
            assertTrue(access.apply(res, candidate))
            assertTrue(access.accepted(res, 80, true, false))
            assertNull(access.selection(res, 80, true, false))
            res.throwHelper = true
            assertFalse(access.accepted(res, 80, true, false))
            assertTrue(access.currentEligible(res, true, false))
        }
        run {
            val res = resource(16)
            assertTrue(access.isPreloaded(res))
            assertEquals(16, access.currentQuality(res))
            assertTrue(access.currentEligible(res, true, false))
            assertTrue(access.accepted(res, 120, true, false))
            assertFalse(access.accepted(res, 0, true, false))
        }
        run {
            val res = resource(16, 80)
            res.mVodIndex.mVodList[0].limitWatchReason = LimitWatchReason.UNRECOGNIZED
            assertFalse(access.currentEligible(res, true, true))
            assertFalse(access.accepted(res, 80, true, true))
            res.mVodIndex.mVodList[0].limitWatchReason = null
            res.from = -1
            assertFalse(access.currentEligible(res, true, true))
            res.from = Resource.SOURCE_FROM_NORMAL
            repeat(127) { res.mVodIndex.mVodList.add(PlayIndex(32)) }
            assertFalse(access.currentEligible(res, true, true))
            assertFalse(access.accepted(res, 80, true, true))
        }
        run {
            val legacyAccess = requireNotNull(StoryQualityResourceAccess.resolve(LegacyResource::class.java, LegacyVod::class.java, LegacyIndex::class.java))
            val res = LegacyResource(LegacyVod(arrayListOf(LegacyIndex(16), LegacyIndex(80))))
            assertEquals(80, legacyAccess.selection(res, 80, true, false)?.qn)
            res.mVodIndex.mVodList[1].mPlayError = LegacyError.WithMultiDeviceLoginErr
            assertNull(legacyAccess.selection(res, 80, true, false))
            res.mVodIndex.mVodList[1].mPlayError = null
            res.mVodIndex.mVodList[0].mPlayError = LegacyError.WithMultiDeviceLoginErr
            assertNull(legacyAccess.selection(res, 80, true, false))
        }
    }

    enum class LegacyError { WithMultiDeviceLoginErr }
    class LegacyIndex(@JvmField var mQuality: Int) {
        @JvmField var mNeedLogin = false
        @JvmField var mNeedVip = false
        @JvmField var mDrmType = DrmType.No
        @JvmField var mPlayError: LegacyError? = null
    }
    class LegacyVod(@JvmField var mVodList: ArrayList<LegacyIndex>)
    class LegacyResource(@JvmField var mVodIndex: LegacyVod) {
        var resolved = 0
        fun sourceFrom() = SOURCE_FROM_FLASH
        fun getPlayIndex(): LegacyIndex = mVodIndex.mVodList[resolved]
        fun getResolvedIndex() = resolved
        fun setResolvedIndex(index: Int) { resolved = index }
        fun isHdr() = false
        fun isDolbyVision() = false
        fun isDrm() = false
        fun findMaxAvailableQuality(target: Int): Pair<Int, Int>? = mVodIndex.mVodList.withIndex()
            .filter { it.value.mQuality <= target }.maxByOrNull { it.value.mQuality }?.let { it.index to it.value.mQuality }
        companion object {
            const val SOURCE_FROM_FLASH = 17
            const val SOURCE_FROM_NORMAL = 31
            const val QUALITY_HDR = 300
            const val QUALITY_DOLBY = 301
        }
    }
}
