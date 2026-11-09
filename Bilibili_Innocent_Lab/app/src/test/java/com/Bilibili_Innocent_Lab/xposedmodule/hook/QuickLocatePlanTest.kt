package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.hook.QuickLocateCharacterizationTest.Companion.canonical
import com.Bilibili_Innocent_Lab.xposedmodule.hook.QuickLocateCharacterizationTest.Companion.normalize
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptAssistGate.Attempt
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlan
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.LocateObserver
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PointFamily
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger

/**
 * 性能报告 P1-01：对改动前后的 [VersionAdapter.quickLocate] 做差分。
 *
 * 基线是同一 JVM、同一宿主夹具上的全量计划（其本身与改动前实现的逐项一致由
 * [QuickLocateCharacterizationTest] 对着旧实现生成的基线锁住）。随机计划下：
 * 被选中的家族结果、诊断与全量逐项相同；被裁剪的家族**没有被求值**（观察点没有它、候选类没有被探测），
 * 结果为空、诊断是「未请求」而不是「缺失」。
 */
class QuickLocatePlanTest {
    private val system: ClassLoader = ClassLoader.getSystemClassLoader()

    /** 用独立实例避开 KavaMemberLookup 的按 ClassLoader 缓存，每次都是真实求值。 */
    private fun freshLoader(hiddenPrefixes: Set<String> = emptySet(), counter: AtomicInteger? = null): ClassLoader =
        object : ClassLoader(system) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                counter?.incrementAndGet()
                if (hiddenPrefixes.any(name::startsWith)) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }

    private class Recorder : LocateObserver {
        val calls = mutableListOf<PointFamily>()
        val outcomes = linkedMapOf<PointFamily, Attempt>()
        override fun located(family: PointFamily, elapsedNanos: Long, outcome: Attempt) {
            calls += family
            outcomes[family] = outcome
        }
    }

    private fun plan(vararg families: PointFamily) = AdaptPlan(7L, families.toSet(), emptySet())

    /** 诊断 id → 它所属的家族；与 buildDiagnostics 的登记保持一致，差分测试同时锁住这张表。 */
    private val diagnosticFamily: Map<String, PointFamily> = mapOf(
        "comment.low" to PointFamily.COMMENT_LOW, "comment.high" to PointFamily.COMMENT_HIGH,
        "mine.entry" to PointFamily.MINE_ENTRY,
        "paused.request" to PointFamily.PAUSE, "paused.panel" to PointFamily.PAUSE, "paused.countdown" to PointFamily.PAUSE,
        "home.banner" to PointFamily.BANNER,
        "home.top_bar.game" to PointFamily.HOME_TOP_BAR, "home.top_bar.game_compose" to PointFamily.HOME_TOP_BAR,
        "home.top_bar.search" to PointFamily.HOME_TOP_BAR,
        "mine.vip" to PointFamily.MINE_VIP, "update.block" to PointFamily.BLOCK_UPDATE,
        "dynamic.tabs" to PointFamily.DYNAMIC_TABS, "number.full" to PointFamily.FULL_NUMBERS,
        "player.portrait" to PointFamily.PLAYER_PORTRAIT,
        "player.interactive_overlay" to PointFamily.PLAYER_INTERACTIVE_OVERLAYS,
        "pgc.auto_activity_popup" to PointFamily.PGC_AUTO_ACTIVITY_POPUP,
        "player.status_bar" to PointFamily.PLAYER_STATUS_BAR,
        "home.recommend_feed" to PointFamily.HOME_RECOMMEND_FEED, "video.relate" to PointFamily.VIDEO_RELATE,
        "home.tabs" to PointFamily.HOME_TABS, "home.components" to PointFamily.HOME_COMPONENTS,
        "mine.components" to PointFamily.MINE_COMPONENTS, "story.feed" to PointFamily.STORY_FEED,
        "home.bottom_bar" to PointFamily.BOTTOM_BAR, "player.default_quality" to PointFamily.PLAYER_QUALITY,
        "teenagers.mode" to PointFamily.TEENAGERS_MODE,
        "comment.purify.search" to PointFamily.COMMENT_PURIFY, "comment.purify.empty_page" to PointFamily.COMMENT_PURIFY,
        "comment.purify.vote" to PointFamily.COMMENT_PURIFY, "comment.purify.follow" to PointFamily.COMMENT_PURIFY,
        "comment.purify.qoe" to PointFamily.COMMENT_PURIFY, "comment.purify.operation" to PointFamily.COMMENT_PURIFY,
        "comment.quick_reply" to PointFamily.COMMENT_PURIFY,
        "comment.filter" to PointFamily.COMMENT_FILTER, "comment.topology" to PointFamily.COMMENT_TOPOLOGY,
        "comment.section" to PointFamily.COMMENT_SECTION, "splash.ads" to PointFamily.SPLASH_ADS
    )

    private fun JSONObject.pointJson(family: PointFamily): Any? = opt(family.key)?.let { normalize(it) }?.let { canonical(it) }

    @Test fun `full plan reproduces the original result and calls every locator exactly once`() {
        val loader = freshLoader()
        val recorder = Recorder()
        val full = requireNotNull(VersionAdapter.quickLocate(loader, AdaptPlan.full(), recorder))
        assertEquals(PointFamily.entries.toList(), recorder.calls.sortedBy { it.ordinal })
        assertEquals(PointFamily.entries.size, recorder.calls.size)
        // 默认重载就是全量计划。
        val viaDefault = requireNotNull(VersionAdapter.quickLocate(loader))
        assertEquals(canonical(normalize(JSONObject(full.toJson().toString()))), canonical(normalize(JSONObject(viaDefault.toJson().toString()))))
        // 覆盖范围：找到的记 FOUND，其余确认缺失，没有任何家族是未请求。
        val coverage = requireNotNull(full.coverage)
        for (family in PointFamily.entries) {
            val expected = if (full.hasPoint(family)) Attempt.FOUND else Attempt.CONCLUSIVE_MISSING
            assertEquals(family.name, expected, coverage.attempt(family))
        }
    }

    @Test fun `pruned families are not evaluated and selected families match the full result`() {
        val random = Random(20261029L)
        val full = requireNotNull(VersionAdapter.quickLocate(freshLoader(), AdaptPlan.full()))
        val fullJson = JSONObject(full.toJson().toString())
        val fullDiag = full.diagnostics.associateBy { it.id }
        repeat(150) { round ->
            val families = PointFamily.entries.filter { random.nextInt(100) < 20 + round % 5 * 15 }.toSet()
            val plan = AdaptPlan(round.toLong(), families, emptySet())
            val loader = freshLoader()
            val recorder = Recorder()
            val located = VersionAdapter.quickLocate(loader, plan, recorder)

            assertEquals("round=$round 只调用计划内的定位器", families, recorder.calls.toSet())
            assertEquals("每个计划内的定位器恰好一次", families.size, recorder.calls.size)
            if (located == null) {
                // 计划内的家族在夹具上本来就全落空（或计划为空）。
                assertTrue(families.none { full.hasPoint(it) })
                return@repeat
            }
            val json = JSONObject(located.toJson().toString())
            for (family in PointFamily.entries) {
                val coverage = requireNotNull(located.coverage).attempt(family)
                if (family in families) {
                    assertEquals("$family 结果与全量一致", fullJson.pointJson(family), json.pointJson(family))
                    assertEquals(family.name, if (full.hasPoint(family)) Attempt.FOUND else Attempt.CONCLUSIVE_MISSING, coverage)
                } else {
                    assertFalse("$family 被裁剪却有结果", located.hasPoint(family))
                    assertEquals("$family 被裁剪应是未请求", Attempt.NOT_REQUESTED, coverage)
                }
            }
            // 诊断：id 集合不变；计划内逐项相同；计划外是 NOT_APPLICABLE + plan:not-requested，绝不是 MISSING。
            val diag = located.diagnostics.associateBy { it.id }
            assertEquals(fullDiag.keys, diag.keys)
            for ((id, family) in diagnosticFamily) {
                val actual = requireNotNull(diag[id]) { id }
                if (family in families) {
                    assertEquals(id, fullDiag[id]!!.state, actual.state)
                    assertEquals(id, canonical(normalize(JSONObject().put("detail", fullDiag[id]!!.detail), "x")),
                        canonical(normalize(JSONObject().put("detail", actual.detail), "x")))
                } else {
                    assertEquals(id, AdaptState.NOT_APPLICABLE, actual.state)
                    assertEquals(id, VersionAdapter.PLAN_NOT_REQUESTED, actual.detail)
                }
            }
            // 与家族无关的诊断（占位、协议摘要以外）不受计划影响。
            for (id in fullDiag.keys - diagnosticFamily.keys - "protocol.structure") {
                assertEquals(id, fullDiag[id], diag[id])
            }
        }
    }

    @Test fun `empty plan probes nothing and yields no result`() {
        val probes = AtomicInteger()
        val recorder = Recorder()
        val located = VersionAdapter.quickLocate(freshLoader(counter = probes), plan(), recorder)
        assertNull(located)
        assertTrue(recorder.calls.isEmpty())
        assertEquals("空计划不应加载任何宿主类", 0, probes.get())
    }

    @Test fun `a single family only probes its own candidates`() {
        val onlyFullNumbers = AtomicInteger()
        VersionAdapter.quickLocate(freshLoader(counter = onlyFullNumbers), plan(PointFamily.FULL_NUMBERS))
        val everything = AtomicInteger()
        VersionAdapter.quickLocate(freshLoader(counter = everything), AdaptPlan.full())
        assertTrue("单家族 ${onlyFullNumbers.get()} 次，全量 ${everything.get()} 次", onlyFullNumbers.get() * 10 < everything.get())
        assertTrue(onlyFullNumbers.get() > 0)
    }

    @Test fun `partially hidden hosts agree with the full plan on every selected family`() {
        val random = Random(77L)
        val packages = listOf(
            "com.bilibili.pegasus.", "com.bilibili.app.comment", "com.bapis.", "com.bilibili.ship.",
            "com.bilibili.player", "com.bilibili.lib.homepage", "com.bilibili.teenagersmode", "com.bilibili.bplus",
            "com.bilibili.relation", "com.bilibili.search2", "com.bilibili.adcommon", "tv.danmaku"
        )
        repeat(60) { round ->
            val hidden = packages.filter { random.nextInt(3) == 0 }.toSet()
            val families = PointFamily.entries.filter { random.nextBoolean() }.toSet()
            // 同一个隐藏集合建两份独立 ClassLoader：全量与按计划各自真实求值。
            val full = VersionAdapter.quickLocate(freshLoader(hidden), AdaptPlan.full())
            val partial = VersionAdapter.quickLocate(freshLoader(hidden), AdaptPlan(round.toLong(), families, emptySet()))
            if (partial == null) {
                assertTrue("round=$round hidden=$hidden", full == null || families.none { full.hasPoint(it) })
                return@repeat
            }
            requireNotNull(full)
            for (family in families) {
                assertEquals("round=$round $family hidden=$hidden", full.hasPoint(family), partial.hasPoint(family))
                assertEquals(JSONObject(full.toJson().toString()).pointJson(family), JSONObject(partial.toJson().toString()).pointJson(family))
            }
        }
    }

    @Test fun `fixture host really exercises both found and missing families`() {
        val full = requireNotNull(VersionAdapter.quickLocate(freshLoader(), AdaptPlan.full()))
        val found = PointFamily.entries.count(full::hasPoint)
        assertTrue("夹具应命中足够多的家族: $found", found >= 20)
        assertTrue("夹具应保留若干确认缺失的家族", found < PointFamily.entries.size)
        assertNotNull(full.coverage)
    }
}
