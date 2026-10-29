package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlanCatalog.Flag
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlanCatalog.Gate
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlanCatalog.NonZero
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlanCatalog.Signal
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlanCatalog.Text
import com.Bilibili_Innocent_Lab.xposedmodule.hook.config.HookConfigSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.config.SnapshotHookConfigSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences as P
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * 性能报告 P1-01 同步定位计划：「功能 → 定位家族」映射与计划生成。
 *
 * 下面的 [groups] 是人读的独立期望表（来自对 HookEntry 安装段的逐个核对），不从 [AdaptPlanCatalog] 推导；
 * 计划必须与它一致。读取者里有任何一个无条件读取（入口、扫描面、运行期可翻转的常驻能力）的家族，
 * 永远在计划里。
 */
class AdaptPlanCatalogTest {
    /** 一组开关 → 它们独占控制的家族。组内任一开关有效都会让这些家族进入计划。 */
    private class Group(val name: String, val signals: List<Signal>, val families: Set<PointFamily>)

    private fun flags(vararg keys: String) = keys.map { Flag(it) }

    private val rangeSignals: List<Signal> = listOf(
        NonZero(P.RECOMMEND_VIDEO_MIN_DURATION_SECONDS), NonZero(P.RECOMMEND_VIDEO_MAX_DURATION_SECONDS),
        NonZero(P.RECOMMEND_VIDEO_MIN_PLAY_COUNT), NonZero(P.RECOMMEND_VIDEO_MAX_PLAY_COUNT),
        Text(P.RECOMMEND_VIDEO_PUBLISH_TIME_RULE)
    )

    private val groups: List<Group> = listOf(
        Group("暂停页广告", listOf(Flag(HookEntry.PREF_ENABLED, true)), setOf(PointFamily.PAUSE)),
        Group("首页轮播", listOf(Flag(HookEntry.PREF_BANNER_ENABLED, true)),
            setOf(PointFamily.BANNER, PointFamily.HOME_RECOMMEND_FEED)),
        Group("我的 VIP", flags(P.HIDE_MINE_VIP), setOf(PointFamily.MINE_VIP)),
        Group("拦截更新", flags(P.BLOCK_APP_UPDATE), setOf(PointFamily.BLOCK_UPDATE)),
        Group("动态页签", flags(P.HIDE_DYNAMIC_CITY_TAB, P.HIDE_DYNAMIC_SCHOOL_TAB, P.PREFER_DYNAMIC_VIDEO_TAB),
            setOf(PointFamily.DYNAMIC_TABS)),
        Group("完整数字", flags(P.SHOW_FULL_NUMBERS), setOf(PointFamily.FULL_NUMBERS)),
        Group("播放器竖屏控件", flags(P.HIDE_PLAYER_PORTRAIT_CONTROL), setOf(PointFamily.PLAYER_PORTRAIT)),
        Group("播放器互动层", flags(P.HIDE_PLAYER_INTERACTIVE_OVERLAYS), setOf(PointFamily.PLAYER_INTERACTIVE_OVERLAYS)),
        Group("影视活动半屏", flags(P.HIDE_PGC_AUTO_ACTIVITY_POPUP), setOf(PointFamily.PGC_AUTO_ACTIVITY_POPUP)),
        Group("透明状态栏", flags(P.TRANSPARENT_PLAYER_STATUS_BAR), setOf(PointFamily.PLAYER_STATUS_BAR)),
        Group("首页竖屏详情", flags(P.HOME_VERTICAL_OPEN_DETAIL), setOf(PointFamily.HOME_RECOMMEND_FEED)),
        Group("首页推荐净化",
            flags(
                P.REMOVE_HOME_RECOMMEND_ADS, P.REMOVE_HOME_RECOMMEND_CM_V2, P.REMOVE_HOME_RECOMMEND_PICTURES,
                P.REMOVE_HOME_RECOMMEND_GAME_PROMOTIONS, P.HOME_RECOMMEND_TITLE_FILTER_ENABLED,
                P.REMOVE_HOME_RECOMMEND_LIVE, P.REMOVE_HOME_RECOMMEND_PGC, P.REMOVE_HOME_RECOMMEND_COMIC,
                P.REMOVE_HOME_RECOMMEND_SPECIAL_CARDS, P.REMOVE_HOME_RECOMMEND_COURSES,
                P.REMOVE_HOME_RECOMMEND_VERTICAL, P.REMOVE_HOME_RECOMMEND_LARGE, P.BLOCK_AI_DECLARED_VIDEOS,
                P.BLOCK_AI_DECLARED_VIDEOS_STRONG_MODE
            ) + listOf(Text(P.HOME_RECOMMEND_BLOCKED_TIDS), Text(P.HOME_RECOMMEND_BLOCKED_AUTHORS)),
            setOf(PointFamily.HOME_RECOMMEND_FEED)),
        Group("版块挑选（共享首页推荐与相关推荐）", flags(P.HOME_RECOMMEND_SECTION_PICK_ENABLED),
            setOf(PointFamily.HOME_RECOMMEND_FEED, PointFamily.VIDEO_RELATE)),
        Group("视频语义过滤（共享首页推荐与相关推荐）", flags(P.VIDEO_SEMANTIC_FILTER_ENABLED),
            setOf(PointFamily.HOME_RECOMMEND_FEED, PointFamily.VIDEO_RELATE)),
        Group("推荐区间/发布时间规则（共享首页推荐与相关推荐）", rangeSignals,
            setOf(PointFamily.HOME_RECOMMEND_FEED, PointFamily.VIDEO_RELATE)),
        Group("相关推荐过滤",
            flags(
                P.REMOVE_RELATE_COMMERCIAL, P.REMOVE_RELATE_GAME, P.REMOVE_RELATE_LIVE, P.REMOVE_RELATE_COURSE,
                P.REMOVE_RELATE_SPECIAL, P.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED,
                P.VIDEO_RELATE_STRONG_MODE_ENABLED, P.VIDEO_RELATE_REASON_FILTER_ENABLED
            ) + listOf(Text(P.VIDEO_RELATE_BLOCKED_AUTHORS), Text(P.VIDEO_RELATE_BLOCKED_TAGS)),
            setOf(PointFamily.VIDEO_RELATE)),
        Group("Story 净化",
            flags(
                P.REMOVE_STORY_ADS, P.REMOVE_STORY_LIVE, P.REMOVE_STORY_GAMES, P.REMOVE_STORY_BANGUMI,
                P.REMOVE_STORY_COURSES, P.REMOVE_STORY_SHORT_DRAMA, P.REMOVE_STORY_SHOPPING,
                P.REMOVE_STORY_MOVIES, P.REMOVE_STORY_DOCUMENTARIES, P.REMOVE_STORY_TV,
                P.REMOVE_STORY_VARIETY, P.REMOVE_STORY_MUSIC
            ), setOf(PointFamily.STORY_FEED)),
        Group("默认画质", listOf(NonZero(P.PLAYER_DEFAULT_QUALITY_QN)), setOf(PointFamily.PLAYER_QUALITY)),
        Group("青少年模式提示", flags(P.BLOCK_TEENAGERS_MODE_PROMPT), setOf(PointFamily.TEENAGERS_MODE)),
        Group("评论区净化",
            flags(
                P.REMOVE_COMMENT_SEARCH_LINKS, P.REMOVE_COMMENT_EMPTY_GUIDE, P.REMOVE_COMMENT_VOTE_WIDGETS,
                P.REMOVE_COMMENT_FOLLOW_BUTTONS, P.REMOVE_COMMENT_QOE, P.REMOVE_COMMENT_OPERATIONS,
                P.BLOCK_COMMENT_QUICK_REPLY
            ), setOf(PointFamily.COMMENT_PURIFY)),
        Group("评论过滤",
            flags(
                P.COMMENT_KEYWORD_FILTER_ENABLED, P.COMMENT_MIN_LEVEL_FILTER_ENABLED, P.REMOVE_AT_ONLY_COMMENTS,
                P.COMMENT_USER_FILTER_ENABLED, P.COMMENT_SEMANTIC_FILTER_ENABLED
            ), setOf(PointFamily.COMMENT_FILTER)),
        Group("回复脉络", flags(P.REPLY_TOPOLOGY_ENABLED), setOf(PointFamily.COMMENT_TOPOLOGY)),
        Group("评论区折叠", flags(P.HIDE_COMMENT_SECTION), setOf(PointFamily.COMMENT_SECTION)),
        Group("开屏广告", flags(P.PURIFY_SPLASH_ADS), setOf(PointFamily.SPLASH_ADS))
    )

    /** 无论开关如何都不裁剪的家族及原因：入口、扫描面（设置页勾选列表）、运行期可翻转的常驻能力。 */
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

    /** 所有相关键都显式关闭（默认开的开关也显式关）。 */
    private fun allOff(): MutableMap<String, Any> =
        allSignals().associateTo(linkedMapOf()) { it.keys.single() to offValue(it) }

    private fun plan(values: Map<String, Any>, revision: Long = 7L) =
        AdaptPlanner.build(SnapshotHookConfigSource(values, revision))

    @Test fun `every family is catalogued and the always set is exactly the reviewed one`() {
        for (family in PointFamily.entries) {
            val spec = requireNotNull(AdaptPlanCatalog.spec(family)) { "$family 没有登记读取者" }
            assertTrue("$family 读取者为空", spec.readers.isNotEmpty())
            assertTrue("$family 缺依据", spec.evidence.isNotBlank())
        }
        assertEquals(alwaysWithReason.keys, AdaptPlanCatalog.alwaysFamilies)
        // 每个常开家族至少有一个无条件读取者，并写明为什么（防止「常开」只是漏登记）。
        for (family in alwaysWithReason.keys) {
            val always = AdaptPlanCatalog.spec(family)!!.readers.filter { it.gate == Gate.Always }
            assertTrue("$family 没有无条件读取者", always.isNotEmpty())
            assertTrue("$family 无说明", always.all { it.note.isNotBlank() })
        }
    }

    @Test fun `scan surfaces and runtime flags are marked as always needed with the right timing`() {
        fun latest(family: PointFamily) = AdaptPlanCatalog.spec(family)!!.readers.filter { it.gate == Gate.Always }
            .map { it.latest }.toSet()
        assertEquals(setOf(AdaptPlanCatalog.Latest.RUNTIME_FLAG), latest(PointFamily.COMMENT_LOW))
        assertEquals(setOf(AdaptPlanCatalog.Latest.RUNTIME_FLAG), latest(PointFamily.COMMENT_HIGH))
        for (family in listOf(PointFamily.HOME_TABS, PointFamily.HOME_COMPONENTS, PointFamily.MINE_COMPONENTS,
            PointFamily.MINE_ACCOUNT_MINE, PointFamily.BOTTOM_BAR, PointFamily.HOME_TOP_BAR))
            assertEquals(family.name, setOf(AdaptPlanCatalog.Latest.RESIDENT_SCAN), latest(family))
        // 其余读取者都在安装链构造阶段取走定位结果。
        for (spec in AdaptPlanCatalog.specs) for (reader in spec.readers.filter { it.gate is Gate.AnyOf })
            assertEquals("${spec.family}/${reader.installer}", AdaptPlanCatalog.Latest.INSTALL_CHAIN, reader.latest)
    }

    @Test fun `dependencies are pulled into the plan`() {
        assertEquals(setOf(PointFamily.MINE_ENTRY), AdaptPlanCatalog.spec(PointFamily.MINE_COMPONENTS)!!.dependsOn)
        val onlyComponents = AdaptPlanCatalog.closure(setOf(PointFamily.MINE_COMPONENTS))
        assertEquals(setOf(PointFamily.MINE_COMPONENTS, PointFamily.MINE_ENTRY), onlyComponents)
    }

    @Test fun `all switches off keeps only the always set and prunes the rest`() {
        val plan = plan(allOff())
        assertEquals(alwaysWithReason.keys, plan.required)
        assertEquals(alwaysWithReason.keys, plan.always)
        assertEquals(PointFamily.entries.size - alwaysWithReason.size, plan.pruned.size)
        assertEquals(7L, plan.configRevision)
        assertTrue("最小配置确实裁掉了定位器", plan.pruned.size >= 20)
    }

    @Test fun `default configuration keeps the default on features`() {
        // 快照里一个键都没有：默认开的暂停页广告与首页轮播（轮播同时驱动首页推荐净化）仍然在计划里。
        val plan = plan(emptyMap())
        assertEquals(
            alwaysWithReason.keys + setOf(PointFamily.PAUSE, PointFamily.BANNER, PointFamily.HOME_RECOMMEND_FEED),
            plan.required
        )
        assertEquals(listOf("PausedAdFeatureInstaller"), plan.requestedBy(PointFamily.PAUSE))
        assertTrue("HomeRecommendPurifyFeatureInstaller" in plan.requestedBy(PointFamily.HOME_RECOMMEND_FEED))
    }

    @Test fun `each switch alone requests exactly its reviewed families`() {
        for (group in groups) for (signal in group.signals) {
            val values = allOff()
            values[signal.keys.single()] = onValue(signal)
            val expected = alwaysWithReason.keys + group.families
            assertEquals("${group.name}/${signal.keys.single()}", expected, plan(values).required)
        }
    }

    @Test fun `shared families stay while any one of their readers is enabled`() {
        // 首页推荐被三个安装器共用：关掉净化但保留竖屏详情或版块挑选，家族仍然要定位。
        val onlyVertical = allOff().also { it[P.HOME_VERTICAL_OPEN_DETAIL] = true }
        assertTrue(PointFamily.HOME_RECOMMEND_FEED in plan(onlyVertical).required)
        assertFalse(PointFamily.VIDEO_RELATE in plan(onlyVertical).required)
        // 版块挑选同时读首页推荐和相关推荐；相关推荐过滤关闭也不能裁掉 VIDEO_RELATE。
        val onlyPick = allOff().also { it[P.HOME_RECOMMEND_SECTION_PICK_ENABLED] = true }
        assertTrue(plan(onlyPick).required.containsAll(setOf(PointFamily.HOME_RECOMMEND_FEED, PointFamily.VIDEO_RELATE)))
        // 回复脉络只开：评论 low/high 绑定点本来就常开，不会因此多出评论过滤/净化。
        val onlyTopology = allOff().also { it[P.REPLY_TOPOLOGY_ENABLED] = true }
        val required = plan(onlyTopology).required
        assertTrue(PointFamily.COMMENT_TOPOLOGY in required)
        assertFalse(PointFamily.COMMENT_FILTER in required)
        assertFalse(PointFamily.COMMENT_PURIFY in required)
    }

    @Test fun `random configurations match the reviewed table and never drop an always family`() {
        val random = Random(20261029L)
        val signals = allSignals()
        repeat(3000) { round ->
            val values = allOff()
            val density = random.nextInt(4)
            for (signal in signals) if (random.nextInt(8) < density) values[signal.keys.single()] = onValue(signal)
            val expected = linkedSetOf<PointFamily>().apply {
                addAll(alwaysWithReason.keys)
                for (group in groups) if (group.signals.any { it.active(SnapshotHookConfigSource(values)) }) addAll(group.families)
            }
            val plan = plan(values)
            assertEquals("round=$round values=${values.filterValues { it != false && it != "" && it != 0 }.keys}", expected, plan.required)
            assertTrue(plan.required.containsAll(plan.always))
        }
    }

    @Test fun `turning more switches on never shrinks the plan`() {
        val random = Random(42L)
        val signals = allSignals()
        repeat(500) {
            val values = allOff()
            var previous = plan(values).required
            for (signal in signals.shuffled(random)) {
                values[signal.keys.single()] = onValue(signal)
                val next = plan(values).required
                assertTrue(next.containsAll(previous))
                previous = next
            }
            assertEquals(PointFamily.entries.toSet(), previous)
        }
    }

    @Test fun `wrongly typed values never turn a feature on`() {
        val values = allOff()
        values[P.SHOW_FULL_NUMBERS] = "true"
        values[P.PLAYER_DEFAULT_QUALITY_QN] = 80L
        values[P.HOME_RECOMMEND_BLOCKED_TIDS] = 5
        // 与安装器读取同一个 HookConfigSource：类型不符就是默认值，计划不能比安装器更“宽松地”理解它。
        assertEquals(alwaysWithReason.keys, plan(values).required)
    }

    @Test fun `unmapped families fail open`() {
        val plan = AdaptPlanner.build(SnapshotHookConfigSource(allOff()), specOf = { null })
        assertEquals(PointFamily.entries.toSet(), plan.required)
        assertEquals(PointFamily.entries.toSet(), plan.always)
        assertEquals(listOf("unmapped(fail-open)"), plan.requestedBy(PointFamily.PLAYER_QUALITY))
    }

    @Test fun `plan is derived from the given snapshot only`() {
        val source = object : HookConfigSource {
            var reads = 0
            override val revision: Long = 99L
            override fun getBoolean(key: String, defaultValue: Boolean): Boolean { reads++; return false }
            override fun getInt(key: String, defaultValue: Int): Int { reads++; return 0 }
            override fun getLong(key: String, defaultValue: Long): Long = error("计划不读 Long")
            override fun getString(key: String, defaultValue: String): String { reads++; return "" }
        }
        val plan = AdaptPlanner.build(source)
        assertEquals(99L, plan.configRevision)
        assertTrue(source.reads > 0)
        assertEquals(alwaysWithReason.keys, plan.required)
    }

    @Test fun `full plan and explicit additions only grow`() {
        val full = AdaptPlan.full(5L)
        assertEquals(PointFamily.entries.toSet(), full.required)
        assertEquals(alwaysWithReason.keys, full.always)
        assertSame(full, full.including(setOf(PointFamily.BLOCK_UPDATE)))
        val minimal = plan(allOff())
        val grown = minimal.including(setOf(PointFamily.BLOCK_UPDATE, PointFamily.PLAYER_QUALITY))
        assertEquals(minimal.required + setOf(PointFamily.BLOCK_UPDATE, PointFamily.PLAYER_QUALITY), grown.required)
        assertEquals(minimal.configRevision, grown.configRevision)
        assertEquals(listOf("explicit-flag"), grown.requestedBy(PointFamily.BLOCK_UPDATE))
    }

    @Test fun `assist gate families use the same switches as the plan`() {
        // 三类带 DEX 兜底的家族：计划的开关必须覆盖 ensureAdapted 的显式开关，否则会出现「要补扫却没定位」。
        val updateOn = allOff().also { it[P.BLOCK_APP_UPDATE] = true }
        val topologyOn = allOff().also { it[P.REPLY_TOPOLOGY_ENABLED] = true }
        val qualityOn = allOff().also { it[P.PLAYER_DEFAULT_QUALITY_QN] = 80 }
        assertTrue(PointFamily.BLOCK_UPDATE in plan(updateOn).required)
        assertTrue(PointFamily.COMMENT_TOPOLOGY in plan(topologyOn).required)
        assertTrue(PointFamily.PLAYER_QUALITY in plan(qualityOn).required)
    }
}
