package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptDecision
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptDiagnostic
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HostIdentity
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.StartupCacheSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptAssistGate.Attempt
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptCoverage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlan
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PointFamily
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SyncLocatorRun
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 性能报告 P1-01：缓存的覆盖范围与尝试状态。
 *
 * 覆盖：缓存缺项、旧缓存、手动 reset、宿主升级、进程差异、功能关闭再开启、短暂失败，
 * 以及「局部结果不能被标成全量完成」「未请求不等于缺失」。
 */
class AdaptPlanCacheTest {
    private val system: ClassLoader = ClassLoader.getSystemClassLoader()

    private fun plan(vararg families: PointFamily) = AdaptPlan(1L, families.toSet(), emptySet())

    /** 带全量覆盖范围的缓存：有点的家族 FOUND，其余确认缺失。 */
    private fun fullyCovered(base: AdaptResult = VersionAdapterTest().result()): AdaptResult = base.copy(
        coverage = AdaptCoverage(PointFamily.entries.associateWith {
            if (base.hasPoint(it)) Attempt.FOUND else Attempt.CONCLUSIVE_MISSING
        })
    )

    private fun snapshot(cached: AdaptResult?, resetTs: Long = 0L) = StartupCacheSnapshot(
        HostIdentity(cached?.biliVersionCode ?: 9090300, cached?.hostFingerprint ?: "fp"), resetTs, cached
    )

    // ---------- 持久化形状与向后兼容 ----------

    @Test fun `coverage survives the cache round trip`() {
        val stored = fullyCovered()
        val restored = requireNotNull(AdaptResult.fromJson(JSONObject(stored.toJson().toString())))
        assertEquals(stored.coverage, restored.coverage)
        assertEquals(stored, restored)
    }

    @Test fun `old cache without coverage reads fine but is never complete`() {
        val legacy = VersionAdapterTest().result()
        val json = JSONObject(legacy.toJson().toString())
        assertFalse(json.has("cov"))
        val restored = requireNotNull(AdaptResult.fromJson(json))
        assertNull("覆盖范围未知（null），不是空也不是全部", restored.coverage)
        val snapshot = snapshot(restored)
        // 作为历史（点、诊断）仍可用……
        assertNotNull(snapshot.usableCache(highCandidateExists = false))
        // ……但按任何非空计划都不算完整，且缺口就是计划要求的全部家族。
        val needed = plan(PointFamily.FULL_NUMBERS, PointFamily.BANNER)
        assertNull(snapshot.usableCache(false, needed))
        assertEquals(needed.required, snapshot.missingCoverage(false, needed))
        // 空计划没有任何要求，旧缓存对它是完整的。
        assertNotNull(snapshot.usableCache(false, plan()))
        assertEquals(AdaptDecision.SUPPLEMENT_SILENTLY, VersionAdapter.adaptDecision(restored, false, needed.required))
    }

    @Test fun `malformed or future coverage never crashes and never counts as complete`() {
        val base = JSONObject(VersionAdapterTest().result().toJson().toString())
        for (bad in listOf<Any>(JSONArray().put("full_numbers"), "FOUND", 7, JSONObject.NULL)) {
            val restored = AdaptResult.fromJson(JSONObject(base.toString()).put("cov", bad))
            assertNotNull(restored)
            assertNull(restored!!.coverage)
        }
        val future = AdaptResult.fromJson(JSONObject(base.toString()).put("cov",
            JSONObject().put("family_from_the_future", "FOUND").put("full_numbers", "FOUND")))
        assertEquals(setOf(PointFamily.FULL_NUMBERS), future!!.coverage!!.attempted)
    }

    @Test fun `a cache claiming FOUND for a family without a point is rejected`() {
        val stored = fullyCovered()
        assertFalse(stored.hasPoint(PointFamily.BANNER))
        val forged = JSONObject(stored.toJson().toString())
        forged.getJSONObject("cov").put("banner", "FOUND")
        assertNull(AdaptResult.fromJson(forged))
        // 声称确认缺失而点还在，不影响有效性（点可以来自 DEX 兜底），只是覆盖语义由 coverage 决定。
        val missingButPresent = JSONObject(stored.toJson().toString())
        missingButPresent.getJSONObject("cov").put("full_numbers", "CONCLUSIVE_MISSING")
        assertNotNull(AdaptResult.fromJson(missingButPresent))
    }

    @Test fun `schema and rule versions are unchanged because the key is purely additive`() {
        assertEquals(62, VersionAdapterContract.SCHEMA_VERSION)
        assertEquals(81, VersionAdapterContract.RULE_VERSION)
        assertEquals(62, JSONObject(fullyCovered().toJson().toString()).getInt("sv"))
    }

    // ---------- usableCache / ensureAdapted 按计划判断完整性 ----------

    @Test fun `complete coverage is usable and a larger plan asks for a differential supplement`() {
        val stored = fullyCovered()
        val covered = PointFamily.entries.filter(stored::hasPoint).toSet()
        val snapshot = snapshot(stored)
        assertEquals(stored, snapshot.usableCache(false, AdaptPlan(1L, covered, emptySet())))
        assertTrue(snapshot.missingCoverage(false, AdaptPlan.full())!!.isEmpty())
        // 局部缓存：只尝试过 FULL_NUMBERS。
        val partial = stored.copy(coverage = AdaptCoverage(mapOf(PointFamily.FULL_NUMBERS to Attempt.FOUND)))
        val partialSnapshot = snapshot(partial)
        assertSame(partial, partialSnapshot.usableCache(false, plan(PointFamily.FULL_NUMBERS)))
        assertNull("局部结果不能被当成全量完成", partialSnapshot.usableCache(false, plan(PointFamily.FULL_NUMBERS, PointFamily.SPLASH_ADS)))
        assertEquals(setOf(PointFamily.SPLASH_ADS), partialSnapshot.missingCoverage(false, plan(PointFamily.FULL_NUMBERS, PointFamily.SPLASH_ADS)))
    }

    @Test fun `host upgrade manual reset and rule change still invalidate regardless of coverage`() {
        val stored = fullyCovered()
        val identity = HostIdentity(stored.biliVersionCode, stored.hostFingerprint)
        val everything = AdaptPlan.full()
        val ok = StartupCacheSnapshot(identity, stored.ts, stored)
        assertNotNull(ok.usableCache(false, plan()))
        // 宿主升级：versionCode 变化。
        assertNull(ok.copy(identity = identity.copy(versionCode = identity.versionCode + 1)).usableCache(false, plan()))
        // 规则版本变化 / 宿主 APK 变化：指纹不同。
        assertNull(ok.copy(identity = identity.copy(fingerprint = "rules=82")).usableCache(false, plan()))
        // 手动 reset：reset 时间戳晚于缓存。
        assertNull(ok.copy(resetTimestamp = stored.ts + 1).usableCache(false, plan()))
        // 这些情况下缓存整体不可用 → null（整轮重新适配），而不是「缺口」→ 静默补扫。
        for (invalid in listOf(
            ok.copy(identity = identity.copy(versionCode = identity.versionCode + 1)),
            ok.copy(identity = identity.copy(fingerprint = "rules=82")),
            ok.copy(resetTimestamp = stored.ts + 1),
            ok.copy(cached = null)
        )) {
            assertNull(invalid.missingCoverage(false, everything))
            assertEquals(AdaptDecision.ADAPT, VersionAdapter.adaptDecision(invalid.usableCache(false), false, emptySet()))
        }
        // 评论 high 点缺失的旧规则仍然整轮失效。
        val lowOnly = stored.copy(commentHigh = null, coverage = null)
        assertNull(StartupCacheSnapshot(identity, stored.ts, lowOnly).usableCache(true, plan()))
    }

    @Test fun `decision table`() {
        val usable = fullyCovered()
        val missing = setOf(PointFamily.SPLASH_ADS)
        assertEquals(AdaptDecision.REUSE, VersionAdapter.adaptDecision(usable, false, emptySet()))
        assertEquals(AdaptDecision.SUPPLEMENT_SILENTLY, VersionAdapter.adaptDecision(usable, false, missing))
        // 有功能需要 DEX 兜底补扫时是完整适配（带提示），不是静默补扫。
        assertEquals(AdaptDecision.ADAPT, VersionAdapter.adaptDecision(usable, true, missing))
        assertEquals(AdaptDecision.ADAPT, VersionAdapter.adaptDecision(usable, true, emptySet()))
        assertEquals(AdaptDecision.ADAPT, VersionAdapter.adaptDecision(null, false, emptySet()))
        assertEquals(AdaptDecision.ADAPT, VersionAdapter.adaptDecision(null, false, missing))
    }

    @Test fun `different processes with different plans agree through coverage`() {
        // 主进程按计划 A 适配并写缓存；另一个进程的计划 B 只看覆盖范围，不看谁写的。
        val written = fullyCovered().copy(coverage = AdaptCoverage(mapOf(
            PointFamily.FULL_NUMBERS to Attempt.FOUND, PointFamily.BANNER to Attempt.CONCLUSIVE_MISSING
        )))
        val reader = snapshot(written)
        assertNotNull(reader.usableCache(false, plan(PointFamily.BANNER)))
        assertNull(reader.usableCache(false, plan(PointFamily.BANNER, PointFamily.MINE_VIP)))
        // quickLocate 的结果带实时覆盖范围，但永远不会被当作缓存写入：它的时间戳和版本号为 0，缓存读取会拒绝。
        val live = requireNotNull(VersionAdapter.quickLocate(system, AdaptPlan.full()))
        assertNotNull(live.coverage)
        assertEquals(0, live.biliVersionCode)
        assertNull(snapshot(live.copy(biliVersionCode = 0)).copy(identity = HostIdentity(9090300, live.hostFingerprint)).usableCache(false))
    }

    // ---------- 一轮适配之后的覆盖范围 ----------

    private fun resultWith(vararg present: PointFamily): AdaptResult {
        val base = VersionAdapterTest().result()
        // 只保留「present」里的家族，其余置空，方便精确构造 hasPoint。
        return base.copy(
            commentLow = base.commentLow.takeIf { PointFamily.COMMENT_LOW in present },
            commentHigh = base.commentHigh.takeIf { PointFamily.COMMENT_HIGH in present },
            pause = if (PointFamily.PAUSE in present) base.pause else VersionAdapter.PausePoints(emptyList(), null, null, null),
            homeTopBar = base.homeTopBar.takeIf { PointFamily.HOME_TOP_BAR in present },
            mineVip = base.mineVip.takeIf { PointFamily.MINE_VIP in present },
            blockUpdate = base.blockUpdate.takeIf { PointFamily.BLOCK_UPDATE in present },
            dynamicTabs = base.dynamicTabs.takeIf { PointFamily.DYNAMIC_TABS in present },
            fullNumbers = base.fullNumbers.takeIf { PointFamily.FULL_NUMBERS in present },
            playerPortrait = base.playerPortrait.takeIf { PointFamily.PLAYER_PORTRAIT in present },
            playerStatusBar = base.playerStatusBar.takeIf { PointFamily.PLAYER_STATUS_BAR in present },
            homeRecommendFeed = base.homeRecommendFeed.takeIf { PointFamily.HOME_RECOMMEND_FEED in present },
            videoRelate = null, homeTabs = null, homeComponents = null, mineComponents = null, mineAccountMine = null,
            storyFeed = null, bottomBar = null, playerQuality = null, teenagersMode = null, commentPurify = null,
            commentFilter = null, commentTopology = null, commentSection = null, splashAds = null, playerInteractiveOverlays = null,
            pgcAutoActivityPopup = null, mineEntry = null, banner = null, coverage = null
        )
    }

    @Test fun `planned families record found or conclusive missing and unplanned ones stay not requested`() {
        val result = resultWith(PointFamily.FULL_NUMBERS, PointFamily.MINE_VIP)
        val plan = plan(PointFamily.FULL_NUMBERS, PointFamily.BANNER)
        val run = SyncLocatorRun(plan)
        run.locate(PointFamily.FULL_NUMBERS) { result.fullNumbers }
        run.locate(PointFamily.BANNER) { result.banner }
        val coverage = VersionAdapter.coverageOf(result, run, plan, null)
        assertEquals(Attempt.FOUND, coverage.attempt(PointFamily.FULL_NUMBERS))
        assertEquals(Attempt.CONCLUSIVE_MISSING, coverage.attempt(PointFamily.BANNER))
        // MINE_VIP 带着点，但没有任何覆盖证明，不能说「找到」：局部结果不是全量完成。
        assertEquals(Attempt.NOT_REQUESTED, coverage.attempt(PointFamily.MINE_VIP))
        assertEquals(Attempt.NOT_REQUESTED, coverage.attempt(PointFamily.SPLASH_ADS))
    }

    @Test fun `closing a feature and opening it again does not lose earlier coverage`() {
        // 第一轮：A、B 都启用。
        val first = resultWith(PointFamily.FULL_NUMBERS, PointFamily.MINE_VIP)
        val firstPlan = plan(PointFamily.FULL_NUMBERS, PointFamily.MINE_VIP, PointFamily.BANNER)
        val firstRun = SyncLocatorRun(firstPlan)
        firstPlan.required.forEach { family -> firstRun.locate(family) { if (first.hasPoint(family)) "x" else null } }
        val firstCoverage = VersionAdapter.coverageOf(first, firstRun, firstPlan, null)
        assertEquals(Attempt.CONCLUSIVE_MISSING, firstCoverage.attempt(PointFamily.BANNER))
        // 第二轮：只剩 A（B、BANNER 关闭）。关闭的家族带着此前的点与结论一起沿用，不被改写成缺失。
        val secondPlan = plan(PointFamily.FULL_NUMBERS)
        val second = first
        val secondRun = SyncLocatorRun(secondPlan)
        secondRun.locate(PointFamily.FULL_NUMBERS) { second.fullNumbers }
        val secondCoverage = VersionAdapter.coverageOf(second, secondRun, secondPlan, firstCoverage)
        assertEquals(Attempt.FOUND, secondCoverage.attempt(PointFamily.FULL_NUMBERS))
        assertEquals(Attempt.FOUND, secondCoverage.attempt(PointFamily.MINE_VIP))
        assertEquals(Attempt.CONCLUSIVE_MISSING, secondCoverage.attempt(PointFamily.BANNER))
        // 第三轮：B 重新开启——不需要补扫，直接复用。
        val reopened = plan(PointFamily.FULL_NUMBERS, PointFamily.MINE_VIP, PointFamily.BANNER)
        assertTrue(com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlanner.missing(secondCoverage, reopened).isEmpty())
        // 如果关闭期间点被丢掉了（例如缓存里没有），就不能再说「找到」，退回未请求并触发补扫。
        val dropped = resultWith(PointFamily.FULL_NUMBERS)
        val droppedCoverage = VersionAdapter.coverageOf(dropped, secondRun, secondPlan, firstCoverage)
        assertEquals(Attempt.NOT_REQUESTED, droppedCoverage.attempt(PointFamily.MINE_VIP))
        assertEquals(setOf(PointFamily.MINE_VIP), com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlanner.missing(droppedCoverage, plan(PointFamily.FULL_NUMBERS, PointFamily.MINE_VIP)))
    }

    @Test fun `transient failure stays retryable and is retried by the next adaptation without forcing one`() {
        val plan = plan(PointFamily.FULL_NUMBERS, PointFamily.MINE_VIP)
        val result = resultWith()
        val run = SyncLocatorRun(plan)
        run.locate<String>(PointFamily.FULL_NUMBERS) { throw IllegalStateException("transient") }
        run.locate<String>(PointFamily.MINE_VIP) { null }
        val coverage = VersionAdapter.coverageOf(result, run, plan, null)
        assertEquals(Attempt.RETRYABLE, coverage.attempt(PointFamily.FULL_NUMBERS))
        assertEquals(Attempt.CONCLUSIVE_MISSING, coverage.attempt(PointFamily.MINE_VIP))
        // 不会因为持久的环境问题让每次冷启动都重新适配。
        assertTrue(com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlanner.missing(coverage, plan).isEmpty())
        // 之后某轮该家族被裁剪：结论原样沿用，仍可重试。
        val smaller = plan(PointFamily.MINE_VIP)
        val smallerRun = SyncLocatorRun(smaller)
        smallerRun.locate<String>(PointFamily.MINE_VIP) { null }
        val after = VersionAdapter.coverageOf(result, smallerRun, smaller, coverage)
        assertEquals(Attempt.RETRYABLE, after.attempt(PointFamily.FULL_NUMBERS))
    }

    // ---------- 诊断：未请求 ≠ 缺失 ----------

    private fun diagnosticOf(result: AdaptResult, id: String) = result.diagnostics.single { it.id == id }

    @Test fun `diagnostics of pruned families are not requested and never missing`() {
        val live = requireNotNull(VersionAdapter.quickLocate(system, plan(PointFamily.FULL_NUMBERS)))
        val numbers = diagnosticOf(live, "number.full")
        assertEquals(AdaptState.FOUND, numbers.state)
        val splash = diagnosticOf(live, "splash.ads")
        assertEquals(AdaptState.NOT_APPLICABLE, splash.state)
        assertEquals(VersionAdapter.PLAN_NOT_REQUESTED, splash.detail)
        assertTrue("未请求不是缺失", live.diagnostics.none { it.state == AdaptState.MISSING && it.detail == VersionAdapter.PLAN_NOT_REQUESTED })
        // 计划外的家族在夹具上其实存在候选类：如果误探测，这里会是 MISSING 或 FOUND。
        val full = requireNotNull(VersionAdapter.quickLocate(system, AdaptPlan.full()))
        assertEquals(AdaptState.FOUND, diagnosticOf(full, "splash.ads").state)
    }

    @Test fun `earlier real attempts are kept in diagnostics while the family is pruned`() {
        val previous = requireNotNull(VersionAdapter.quickLocate(system, AdaptPlan.full()))
        val current = requireNotNull(VersionAdapter.quickLocate(system, plan(PointFamily.FULL_NUMBERS)))
        // 沿用：此前有覆盖证明，诊断原样保留（FOUND 的 splash.ads）。
        val carried = VersionAdapter.buildDiagnostics(system, plan(PointFamily.FULL_NUMBERS), previous, current, "")
        assertEquals(diagnosticOf(previous, "splash.ads"), carried.single { it.id == "splash.ads" })
        // 没有覆盖证明（旧缓存）：不沿用，写未请求。
        val legacy = VersionAdapter.buildDiagnostics(system, plan(PointFamily.FULL_NUMBERS), previous.copy(coverage = null), current, "")
        assertEquals(AdaptDiagnostic("splash.ads", AdaptState.NOT_APPLICABLE, VersionAdapter.PLAN_NOT_REQUESTED), legacy.single { it.id == "splash.ads" })
        // 此前对该家族从未尝试：未请求。
        val neverTried = previous.copy(coverage = AdaptCoverage(mapOf(PointFamily.FULL_NUMBERS to Attempt.FOUND)))
        val untouched = VersionAdapter.buildDiagnostics(system, plan(PointFamily.FULL_NUMBERS), neverTried, current, "")
        assertEquals(VersionAdapter.PLAN_NOT_REQUESTED, untouched.single { it.id == "splash.ads" }.detail)
    }

    @Test fun `merging live and cached keeps the cached conclusion over a not-requested placeholder`() {
        val notRequested = AdaptDiagnostic("splash.ads", AdaptState.NOT_APPLICABLE, VersionAdapter.PLAN_NOT_REQUESTED)
        val liveBase = requireNotNull(VersionAdapter.quickLocate(system, plan(PointFamily.FULL_NUMBERS)))
        for (stored in listOf(
            AdaptDiagnostic("splash.ads", AdaptState.MISSING, "contract=none"),
            AdaptDiagnostic("splash.ads", AdaptState.FOUND, "contract=splash-v1")
        )) {
            val cached = fullyCovered().copy(diagnostics = listOf(stored))
            val live = liveBase.copy(diagnostics = listOf(notRequested))
            assertEquals(stored, VersionAdapter.mergeRuntimeWithCached(live, cached)!!.diagnostics.single())
        }
        // 实时真的尝试了：以实时为准。
        val cached = fullyCovered().copy(diagnostics = listOf(AdaptDiagnostic("splash.ads", AdaptState.MISSING, "old")))
        val attempted = AdaptDiagnostic("splash.ads", AdaptState.NOT_APPLICABLE, "")
        assertEquals(attempted, VersionAdapter.mergeRuntimeWithCached(liveBase.copy(diagnostics = listOf(attempted)), cached)!!.diagnostics.single())
    }

    @Test fun `merged coverage and carried points are consistent`() {
        val cached = fullyCovered()
        val live = requireNotNull(VersionAdapter.quickLocate(system, plan(PointFamily.FULL_NUMBERS)))
        val merged = requireNotNull(VersionAdapter.mergeRuntimeWithCached(live, cached))
        // 计划外的家族沿用缓存里的点和它的覆盖结论；计划内以实时为准。
        assertEquals(cached.coverage!!.attempt(PointFamily.MINE_VIP), merged.coverage!!.attempt(PointFamily.MINE_VIP))
        assertEquals(cached.mineVip, merged.mineVip)
        assertEquals(live.coverage!!.attempt(PointFamily.FULL_NUMBERS), merged.coverage!!.attempt(PointFamily.FULL_NUMBERS))
        // 旧缓存（覆盖范围未知）合并后只声明实时尝试过的家族。
        val legacyMerged = requireNotNull(VersionAdapter.mergeRuntimeWithCached(live, cached.copy(coverage = null)))
        assertEquals(live.coverage, legacyMerged.coverage)
        assertEquals(cached.mineVip, legacyMerged.mineVip)
    }
}
