package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptAssistGate.Attempt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 覆盖范围与尝试状态：持久化形状、向后兼容、合并，以及定位执行器对「未请求/找到/缺失/可重试」的记录。 */
class AdaptCoverageTest {
    private fun plan(vararg families: PointFamily) = AdaptPlan(1L, families.toSet(), emptySet())

    @Test fun `only attempted families are persisted and the round trip is lossless`() {
        val coverage = AdaptCoverage(mapOf(
            PointFamily.FULL_NUMBERS to Attempt.FOUND,
            PointFamily.BANNER to Attempt.CONCLUSIVE_MISSING,
            PointFamily.COMMENT_FILTER to Attempt.RETRYABLE,
            PointFamily.STORY_FEED to Attempt.NOT_REQUESTED
        ))
        val json = coverage.toJson()
        assertEquals(setOf("full_numbers", "banner", "comment_filter"), json.keys().asSequence().toSet())
        val restored = AdaptCoverage.fromJson(JSONObject(json.toString()))
        assertEquals(coverage, restored)
        assertEquals(Attempt.NOT_REQUESTED, restored.attempt(PointFamily.STORY_FEED))
        assertEquals(setOf(PointFamily.FULL_NUMBERS, PointFamily.BANNER, PointFamily.COMMENT_FILTER), restored.attempted)
    }

    @Test fun `unknown families and malformed states never read as covered`() {
        val restored = AdaptCoverage.fromJson(JSONObject()
            .put("future_family", "FOUND")
            .put("full_numbers", "DONE")
            .put("banner", 3)
            .put("story_feed", "FOUND"))
        assertEquals(Attempt.FOUND, restored.attempt(PointFamily.STORY_FEED))
        assertEquals("非法状态按未请求", Attempt.NOT_REQUESTED, restored.attempt(PointFamily.FULL_NUMBERS))
        assertEquals(Attempt.NOT_REQUESTED, restored.attempt(PointFamily.BANNER))
        assertEquals(setOf(PointFamily.STORY_FEED), restored.attempted)
    }

    @Test fun `every family key is unique and stable`() {
        assertEquals(PointFamily.entries.size, PointFamily.entries.map { it.key }.toSet().size)
        for (family in PointFamily.entries) assertSame(family, PointFamily.fromKey(family.key))
        assertNull(PointFamily.fromKey("nope"))
    }

    @Test fun `merging prefers what the live process actually attempted`() {
        val live = AdaptCoverage(mapOf(PointFamily.FULL_NUMBERS to Attempt.CONCLUSIVE_MISSING))
        val cached = AdaptCoverage(mapOf(
            PointFamily.FULL_NUMBERS to Attempt.FOUND, PointFamily.BANNER to Attempt.FOUND
        ))
        val merged = requireNotNull(AdaptCoverage.merge(live, cached))
        assertEquals(Attempt.CONCLUSIVE_MISSING, merged.attempt(PointFamily.FULL_NUMBERS))
        assertEquals(Attempt.FOUND, merged.attempt(PointFamily.BANNER))
        assertSame(cached, AdaptCoverage.merge(null, cached))
        assertSame(live, AdaptCoverage.merge(live, null))
        assertNull(AdaptCoverage.merge(null, null))
    }

    @Test fun `supplement is needed only for families that were never attempted`() {
        val required = plan(PointFamily.FULL_NUMBERS, PointFamily.BANNER, PointFamily.STORY_FEED, PointFamily.MINE_VIP)
        // 旧缓存（覆盖范围未知）不能当成覆盖完整：计划要求的全部待补。
        assertEquals(required.required, AdaptPlanner.missing(null, required))
        assertEquals(required.required, AdaptPlanner.missing(AdaptCoverage.EMPTY, required))
        val coverage = AdaptCoverage(mapOf(
            PointFamily.FULL_NUMBERS to Attempt.FOUND,
            PointFamily.BANNER to Attempt.CONCLUSIVE_MISSING,
            PointFamily.STORY_FEED to Attempt.RETRYABLE
        ))
        // 确认缺失与可重试都不在快路径上触发补适配（只会在本来就要适配的那一轮里重试）。
        assertEquals(setOf(PointFamily.MINE_VIP), AdaptPlanner.missing(coverage, required))
        // 计划里没有的家族不要求覆盖。
        assertEquals(emptySet<PointFamily>(), AdaptPlanner.missing(coverage, plan(PointFamily.FULL_NUMBERS)))
        assertEquals(emptySet<PointFamily>(), AdaptPlanner.missing(null, plan()))
    }

    @Test fun `enabling a feature later requests exactly that family`() {
        val before = AdaptCoverage(mapOf(PointFamily.FULL_NUMBERS to Attempt.FOUND))
        assertTrue(AdaptPlanner.missing(before, plan(PointFamily.FULL_NUMBERS)).isEmpty())
        assertEquals(setOf(PointFamily.SPLASH_ADS), AdaptPlanner.missing(before, plan(PointFamily.FULL_NUMBERS, PointFamily.SPLASH_ADS)))
    }

    @Test fun `executor skips unplanned families without evaluating them`() {
        val calls = mutableListOf<PointFamily>()
        val run = SyncLocatorRun(plan(PointFamily.BANNER), observer = { family, _, _ -> calls += family })
        var evaluated = false
        val skipped = run.locate(PointFamily.STORY_FEED) { evaluated = true; "x" }
        assertNull(skipped)
        assertFalse(evaluated)
        assertEquals(Attempt.NOT_REQUESTED, run.attemptOf(PointFamily.STORY_FEED))
        assertTrue(calls.isEmpty())
        assertEquals(Attempt.NOT_REQUESTED, run.coverage().attempt(PointFamily.STORY_FEED))
    }

    @Test fun `executor records found missing and retryable`() {
        val outcomes = mutableMapOf<PointFamily, Attempt>()
        val run = SyncLocatorRun(plan(PointFamily.BANNER, PointFamily.STORY_FEED, PointFamily.MINE_VIP, PointFamily.FULL_NUMBERS),
            observer = { family, elapsed, outcome ->
                assertTrue(elapsed >= 0)
                outcomes[family] = outcome
            })
        assertEquals("b", run.locate(PointFamily.BANNER) { "b" })
        assertNull(run.locate<String>(PointFamily.STORY_FEED) { null })
        // 找到但不满足「有效」判定（例如暂停页四个子点全空）按确认缺失记。
        assertEquals("", run.locate<String>(PointFamily.MINE_VIP, found = { it.isNotEmpty() }) { "" })
        assertNull(run.locate<String>(PointFamily.FULL_NUMBERS) { throw IllegalStateException("temporary") })
        assertEquals(Attempt.FOUND, run.attemptOf(PointFamily.BANNER))
        assertEquals(Attempt.CONCLUSIVE_MISSING, run.attemptOf(PointFamily.STORY_FEED))
        assertEquals(Attempt.CONCLUSIVE_MISSING, run.attemptOf(PointFamily.MINE_VIP))
        assertEquals("短暂失败可重试，不是确认缺失", Attempt.RETRYABLE, run.attemptOf(PointFamily.FULL_NUMBERS))
        assertEquals(run.coverage().attempted.associateWith(run::attemptOf), outcomes)
        // 链接类错误同样可重试。
        val linkage = SyncLocatorRun(plan(PointFamily.BANNER))
        assertNull(linkage.locate<String>(PointFamily.BANNER) { throw NoClassDefFoundError("x") })
        assertEquals(Attempt.RETRYABLE, linkage.attemptOf(PointFamily.BANNER))
    }

    @Test fun `timings observer summarises the slowest families for device comparisons`() {
        val timings = LocateTimings()
        val run = SyncLocatorRun(plan(PointFamily.BANNER, PointFamily.STORY_FEED, PointFamily.MINE_VIP), observer = timings)
        run.locate(PointFamily.BANNER) { "a" }
        run.locate<String>(PointFamily.STORY_FEED) { null }
        assertEquals(2, timings.familyCount)
        assertTrue(timings.totalNanos >= 0)
        timings.located(PointFamily.MINE_VIP, 9_000_000L, Attempt.FOUND)
        assertEquals(3, timings.familyCount)
        assertTrue(timings.summary(top = 1).contains("slowest=[mine_vip=9000us]"))
        assertTrue(timings.summary().startsWith("families=3/${PointFamily.entries.size}"))
    }

    @Test fun `virtual machine errors are never swallowed`() {
        val run = SyncLocatorRun(plan(PointFamily.BANNER))
        try {
            run.locate<String>(PointFamily.BANNER) { throw StackOverflowError() }
            fail("StackOverflowError 必须继续抛出")
        } catch (expected: StackOverflowError) {
            // 未记录为任何尝试。
            assertEquals(Attempt.NOT_REQUESTED, run.attemptOf(PointFamily.BANNER))
        }
    }

    @Test fun `unplanned families are carried only when an earlier attempt covers them`() {
        val previous = AdaptCoverage(mapOf(PointFamily.BANNER to Attempt.FOUND))
        val run = SyncLocatorRun(plan(PointFamily.STORY_FEED))
        var evaluated = false
        assertEquals("old", run.locateOrCarry(PointFamily.BANNER, "old", previous) { evaluated = true; "new" })
        assertFalse(evaluated)
        // 没有覆盖证明的旧点不带入（旧缓存 / 此前未请求）。
        assertNull(run.locateOrCarry(PointFamily.MINE_VIP, "stale", previous) { "new" })
        assertNull(run.locateOrCarry(PointFamily.BANNER, "legacy", null) { "new" })
        // 计划内永远重新定位，不沿用旧值。
        assertEquals("fresh", run.locateOrCarry(PointFamily.STORY_FEED, "old", previous) { "fresh" })
        assertEquals(Attempt.FOUND, run.attemptOf(PointFamily.STORY_FEED))
    }
}
