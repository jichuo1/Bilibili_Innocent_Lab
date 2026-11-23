package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptDecision
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptDiagnostic
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HostIdentity
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PlayerQualityPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.StartupCacheSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptAssistGate
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptAssistGate.Attempt
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptAssistGate.Family
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptCoverage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlan
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PointFamily
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SyncLocatorRun
import com.bilibili.ship.theseus.united.page.intro.module.relate.AmbiguousRelateService
import com.bilibili.ship.theseus.united.page.intro.module.relate.DetailRelateService
import com.bilibili.ship.theseus.united.page.intro.module.relate.ModernRelateService
import com.bilibili.ship.theseus.united.page.intro.module.relate.UnverifiedRelateService
import com.bilibili.ship.theseus.united.page.intro.module.relate.r2
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptAssistGateTest {
    /** 没有任何宿主类：直接定位全部落空（测试源集里的夹具类在系统 ClassLoader 里，不能用它）。 */
    private val loader: ClassLoader = object : ClassLoader(null) {}
    /** 夹具类可见：直接定位全部满足。 */
    private val fixtureLoader: ClassLoader = ClassLoader.getSystemClassLoader()
    private fun cached() = VersionAdapterTest().result().copy(blockUpdate = null, playerQuality = null,
        commentTopology = null, diagnostics = emptyList())

    private fun diag(family: Family, state: AdaptState, detail: String = "") =
        AdaptDiagnostic(family.diagnosticId, state, detail)

    fun diagnosticIdsStayCompatibleWithExistingCaches() {
        assertEquals("dex.assist", Family.BLOCK_UPDATE.diagnosticId)
        assertEquals("dex.assist.player_quality", Family.PLAYER_QUALITY.diagnosticId)
        assertEquals("dex.assist.comment_topology", Family.COMMENT_TOPOLOGY.diagnosticId)
    }

    fun cachedOutcomesClassifyIntoRequestedFoundConclusiveAndRetryable() {
        for (family in Family.values()) {
            fun attempt(state: AdaptState, detail: String) =
                AdaptAssistGate.attempt(listOf(diag(family, state, family.prefix + detail)), family)
            assertEquals(Attempt.NOT_REQUESTED, AdaptAssistGate.attempt(null, family))
            assertEquals(Attempt.NOT_REQUESTED, AdaptAssistGate.attempt(emptyList(), family))
            for (detail in listOf("not-required", "feature-disabled", "quick-locate"))
                assertEquals("$family $detail", Attempt.NOT_REQUESTED, attempt(AdaptState.NOT_APPLICABLE, detail))
            assertEquals(Attempt.FOUND, attempt(AdaptState.FOUND, "x.y#z"))
            for (detail in listOf("ambiguous:2", "ambiguous:0", "no_match", "too_many_matches", "return-type-missing",
                "reply-info-missing", "comment-item-missing"))
                assertEquals("$family $detail", Attempt.CONCLUSIVE_MISSING, attempt(AdaptState.MISSING, detail))
            for (detail in listOf("native_unavailable", "query_failed", "too_many_archives", "previous_attempt_unfinished",
                "no_code_path", "no-dex-source"))
                assertEquals("$family $detail", Attempt.RETRYABLE, attempt(AdaptState.MISSING, detail))
        }
    }

    fun onlyTheMatchingFamilyDiagnosticCounts() {
        val others = listOf(diag(Family.PLAYER_QUALITY, AdaptState.MISSING, "player-quality:no_match"),
            AdaptDiagnostic("dex.assist.follow_feed_style", AdaptState.MISSING, "no_match"))
        assertEquals(Attempt.NOT_REQUESTED, AdaptAssistGate.attempt(others, Family.BLOCK_UPDATE))
        assertEquals(Attempt.CONCLUSIVE_MISSING, AdaptAssistGate.attempt(others, Family.PLAYER_QUALITY))
        // 同 id 多条时以最后一条为准。
        val history = listOf(diag(Family.BLOCK_UPDATE, AdaptState.MISSING, "block-update:no_match"),
            diag(Family.BLOCK_UPDATE, AdaptState.FOUND, "block-update:a#b"))
        assertEquals(Attempt.FOUND, AdaptAssistGate.attempt(history, Family.BLOCK_UPDATE))
    }

    fun queryIsNeededOnlyWhenEnabledUnsatisfiedUncachedAndNotConclusivelyMissing() {
        var directEvaluations = 0
        fun needs(enabled: Boolean, cached: Boolean, attempt: Attempt, direct: Boolean): Boolean {
            directEvaluations = 0
            return AdaptAssistGate.needsQuery(enabled, cached, attempt) { directEvaluations++; direct }
        }
        for (attempt in Attempt.values()) for (cached in listOf(false, true)) for (direct in listOf(false, true)) {
            assertFalse("disabled $attempt $cached $direct", needs(false, cached, attempt, direct))
            assertEquals("disabled never reflects", 0, directEvaluations)
            val expected = !cached && !direct && attempt != Attempt.CONCLUSIVE_MISSING
            assertEquals("enabled $attempt cached=$cached direct=$direct", expected, needs(true, cached, attempt, direct))
        }
        // 缓存已有点或历史已确认缺失时不做直接定位反射。
        assertFalse(needs(true, true, Attempt.NOT_REQUESTED, false)); assertEquals(0, directEvaluations)
        assertFalse(needs(true, false, Attempt.CONCLUSIVE_MISSING, false)); assertEquals(0, directEvaluations)
        assertTrue(needs(true, false, Attempt.NOT_REQUESTED, false)); assertEquals(1, directEvaluations)
        assertTrue(needs(true, false, Attempt.RETRYABLE, false))
    }

    fun supplementalScanIsNarrowerThanPlanningAndFiresOnlyForNeverRequestedFamilies() {
        var directEvaluations = 0
        fun scan(enabled: Boolean, cached: Boolean, attempt: Attempt, direct: Boolean): Boolean {
            directEvaluations = 0
            return AdaptAssistGate.needsSupplementalScan(enabled, cached, attempt) { directEvaluations++; direct }
        }
        for (attempt in Attempt.values()) for (cached in listOf(false, true)) for (direct in listOf(false, true)) {
            assertFalse(scan(false, cached, attempt, direct)); assertEquals(0, directEvaluations)
            val expected = !cached && !direct && attempt == Attempt.NOT_REQUESTED
            assertEquals("$attempt cached=$cached direct=$direct", expected, scan(true, cached, attempt, direct))
            // 补扫是规划的子集：规划不需要的，补扫一定不需要。
            if (scan(true, cached, attempt, direct)) assertTrue(AdaptAssistGate.needsQuery(true, cached, attempt) { direct })
        }
        // 只有 RETRYABLE 区分两者：规划会重试，快路径不会。
        assertTrue(AdaptAssistGate.needsQuery(true, false, Attempt.RETRYABLE) { false })
        assertFalse(scan(true, false, Attempt.RETRYABLE, false))
    }

    fun skippedFamiliesAreNotRequestedUnlessAConclusionAlreadyExists() {
        val disabled = AdaptAssistGate.skippedDiagnostic(Family.BLOCK_UPDATE, false, null, Attempt.NOT_REQUESTED)
        assertEquals(AdaptDiagnostic("dex.assist", AdaptState.NOT_APPLICABLE, "block-update:feature-disabled"), disabled)
        val satisfied = AdaptAssistGate.skippedDiagnostic(Family.PLAYER_QUALITY, true, null, Attempt.NOT_REQUESTED)
        assertEquals(AdaptState.NOT_APPLICABLE, satisfied.state)
        assertEquals("player-quality:not-required", satisfied.detail)
        val found = diag(Family.COMMENT_TOPOLOGY, AdaptState.FOUND, "comment-topology:a.B,mappers=2")
        assertSame(found, AdaptAssistGate.skippedDiagnostic(Family.COMMENT_TOPOLOGY, false, found, Attempt.FOUND))
        val missing = diag(Family.BLOCK_UPDATE, AdaptState.MISSING, "block-update:ambiguous:3")
        assertSame(missing, AdaptAssistGate.skippedDiagnostic(Family.BLOCK_UPDATE, true, missing, Attempt.CONCLUSIVE_MISSING))
        // 关闭期间不把「未请求」写成缺失。
        assertTrue(AdaptAssistGate.skippedDiagnostic(Family.BLOCK_UPDATE, false, null, Attempt.NOT_REQUESTED).state != AdaptState.MISSING)
    }

    fun cacheFastPathHasNoSupplementalScanWhenTheFeaturesAreOff() {
        val retryable = listOf(diag(Family.BLOCK_UPDATE, AdaptState.MISSING, "block-update:native_unavailable"))
        assertFalse(VersionAdapter.assistRefreshNeeded(loader, cached(), false, false, false))
        assertFalse(VersionAdapter.assistRefreshNeeded(loader, cached().copy(diagnostics = retryable), false, false, false))
    }

    fun enablingAFeatureWithoutACachedPointTriggersExactlyThatSupplementalScan() {
        assertTrue(VersionAdapter.assistRefreshNeeded(loader, cached(), true, false, false))
        assertTrue(VersionAdapter.assistRefreshNeeded(loader, cached(), false, true, false))
        assertTrue(VersionAdapter.assistRefreshNeeded(loader, cached(), false, false, true))
    }

    fun directLocationThatAlreadySatisfiesTheFeatureNeverRequestsAScan() {
        assertFalse(VersionAdapter.assistRefreshNeeded(fixtureLoader, cached(), true, true, true))
    }

    fun cachedAssistedPointsAndConfirmedMissingResultsSatisfyTheCacheOnNextStart() {
        val point = HookPoint("vd6.c", "c", listOf("android.content.Context"))
        // 补扫后写入的点：下次启动不再补扫。
        assertFalse(VersionAdapter.assistRefreshNeeded(loader, cached().copy(blockUpdate = point,
            diagnostics = listOf(diag(Family.BLOCK_UPDATE, AdaptState.FOUND, "block-update:vd6.c#c"))), true, false, false))
        // 确认缺失（不唯一/无匹配）：保持负缓存，不在每次启动重扫。
        for (detail in listOf("block-update:ambiguous:2", "block-update:no_match", "block-update:too_many_matches"))
            assertFalse(detail, VersionAdapter.assistRefreshNeeded(loader,
                cached().copy(diagnostics = listOf(diag(Family.BLOCK_UPDATE, AdaptState.MISSING, detail))), true, false, false))
        // 短暂失败：快路径不因它每次冷启动重新适配（持久的环境问题会让每次启动都弹出适配提示）；
        // 但只要本来就要适配，下一轮仍会重试它（见 queryIsNeeded… 的 RETRYABLE 行）。
        for (detail in listOf("block-update:native_unavailable", "block-update:query_failed", "block-update:previous_attempt_unfinished",
            "block-update:no-dex-source"))
            assertFalse(detail, VersionAdapter.assistRefreshNeeded(loader,
                cached().copy(diagnostics = listOf(diag(Family.BLOCK_UPDATE, AdaptState.MISSING, detail))), true, false, false))
        // 一个家族的结论不影响另一个家族。
        val blockOnly = cached().copy(blockUpdate = point,
            diagnostics = listOf(diag(Family.BLOCK_UPDATE, AdaptState.FOUND, "block-update:vd6.c#c")))
        assertTrue(VersionAdapter.assistRefreshNeeded(loader, blockOnly, true, true, false))
    }

    fun defaultQualityOnlyCountsTheAssistedImplementationNotTheLegacyWrapper() {
        val assisted = PlayerQualityPoints(HookPoint("hd1.h", "a", emptyList()))
        val legacy = PlayerQualityPoints(HookPoint("com.bilibili.playerbizcommon.utils.PlayerSettingHelper", "getDefaultQuality", emptyList()))
        assertFalse(VersionAdapter.assistRefreshNeeded(loader, cached().copy(playerQuality = assisted), false, true, false))
        assertTrue("旧包装层不算满足", VersionAdapter.assistRefreshNeeded(loader, cached().copy(playerQuality = legacy), false, true, false))
        assertTrue("只有 Story 点不算满足", VersionAdapter.assistRefreshNeeded(loader,
            cached().copy(playerQuality = PlayerQualityPoints(null)), false, true, false))
        val history = VersionAdapter.AssistHistory.of(cached().copy(playerQuality = assisted.copy(story = null)))
        assertTrue(history.hasAssistedQuality)
        assertNull(history.assistedQuality?.story)
        assertFalse(VersionAdapter.AssistHistory.of(cached().copy(playerQuality = legacy)).hasAssistedQuality)
    }

    fun missingCacheMeansEveryFamilyIsUnrequestedAndNothingIsCarried() {
        val empty = VersionAdapter.AssistHistory.of(null)
        assertSame(VersionAdapter.AssistHistory.EMPTY, empty)
        for (family in Family.values()) assertEquals(Attempt.NOT_REQUESTED, empty.attempt(family))
        assertNull(empty.blockUpdate); assertNull(empty.commentTopology); assertFalse(empty.hasAssistedQuality)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AdaptAssistGateTest) -> Unit) {
            val s = AdaptAssistGateTest()
            block(s)
        }
        runIsolated { s -> s.diagnosticIdsStayCompatibleWithExistingCaches() }
        runIsolated { s -> s.cachedOutcomesClassifyIntoRequestedFoundConclusiveAndRetryable() }
        runIsolated { s -> s.onlyTheMatchingFamilyDiagnosticCounts() }
        runIsolated { s -> s.queryIsNeededOnlyWhenEnabledUnsatisfiedUncachedAndNotConclusivelyMissing() }
        runIsolated { s -> s.supplementalScanIsNarrowerThanPlanningAndFiresOnlyForNeverRequestedFamilies() }
        runIsolated { s -> s.skippedFamiliesAreNotRequestedUnlessAConclusionAlreadyExists() }
        runIsolated { s -> s.cacheFastPathHasNoSupplementalScanWhenTheFeaturesAreOff() }
        runIsolated { s -> s.enablingAFeatureWithoutACachedPointTriggersExactlyThatSupplementalScan() }
        runIsolated { s -> s.directLocationThatAlreadySatisfiesTheFeatureNeverRequestsAScan() }
        runIsolated { s -> s.cachedAssistedPointsAndConfirmedMissingResultsSatisfyTheCacheOnNextStart() }
        runIsolated { s -> s.defaultQualityOnlyCountsTheAssistedImplementationNotTheLegacyWrapper() }
        runIsolated { s -> s.missingCacheMeansEveryFamilyIsUnrequestedAndNothingIsCarried() }
    
        AdaptPlanCacheCases().verifyAllCases()
        CommentEmojiAdapterCases().verifyAllCases()
        CommentTextIdentityCases().verifyAllCases()
        CommentTopologyPointsCases().verifyAllCases()
        DetailRelateServiceLocatorCases().verifyAllCases()
    }
}

private class AdaptPlanCacheCases {
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

    fun `coverage survives the cache round trip`() {
        val stored = fullyCovered()
        val restored = requireNotNull(AdaptResult.fromJson(JSONObject(stored.toJson().toString())))
        assertEquals(stored.coverage, restored.coverage)
        assertEquals(stored, restored)
    }

    fun `old cache without coverage reads fine but is never complete`() {
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

    fun `malformed or future coverage never crashes and never counts as complete`() {
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

    fun `a cache claiming FOUND for a family without a point is rejected`() {
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

    fun `schema remains compatible while merged rules invalidate old caches`() {
        assertEquals(62, VersionAdapterContract.SCHEMA_VERSION)
        assertEquals(93, VersionAdapterContract.RULE_VERSION)
        assertEquals(62, JSONObject(fullyCovered().toJson().toString()).getInt("sv"))
    }

    // ---------- usableCache / ensureAdapted 按计划判断完整性 ----------

    fun `complete coverage is usable and a larger plan asks for a differential supplement`() {
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

    fun `host upgrade manual reset and rule change still invalidate regardless of coverage`() {
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

    fun `decision table`() {
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

    fun `different processes with different plans agree through coverage`() {
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

    fun `planned families record found or conclusive missing and unplanned ones stay not requested`() {
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

    fun `closing a feature and opening it again does not lose earlier coverage`() {
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

    fun `transient failure stays retryable and is retried by the next adaptation without forcing one`() {
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

    fun `diagnostics of pruned families are not requested and never missing`() {
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

    fun `earlier real attempts are kept in diagnostics while the family is pruned`() {
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

    fun `merging live and cached keeps the cached conclusion over a not-requested placeholder`() {
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

    fun `merged coverage and carried points are consistent`() {
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

    fun verifyAllCases() {
        fun runIsolated(block: (AdaptPlanCacheCases) -> Unit) {
            val s = AdaptPlanCacheCases()
            block(s)
        }
        runIsolated { s -> s.`coverage survives the cache round trip`() }
        runIsolated { s -> s.`old cache without coverage reads fine but is never complete`() }
        runIsolated { s -> s.`malformed or future coverage never crashes and never counts as complete`() }
        runIsolated { s -> s.`a cache claiming FOUND for a family without a point is rejected`() }
        runIsolated { s -> s.`schema remains compatible while merged rules invalidate old caches`() }
        runIsolated { s -> s.`complete coverage is usable and a larger plan asks for a differential supplement`() }
        runIsolated { s -> s.`host upgrade manual reset and rule change still invalidate regardless of coverage`() }
        runIsolated { s -> s.`decision table`() }
        runIsolated { s -> s.`different processes with different plans agree through coverage`() }
        runIsolated { s -> s.`planned families record found or conclusive missing and unplanned ones stay not requested`() }
        runIsolated { s -> s.`closing a feature and opening it again does not lose earlier coverage`() }
        runIsolated { s -> s.`transient failure stays retryable and is retried by the next adaptation without forcing one`() }
        runIsolated { s -> s.`diagnostics of pruned families are not requested and never missing`() }
        runIsolated { s -> s.`earlier real attempts are kept in diagnostics while the family is pruned`() }
        runIsolated { s -> s.`merging live and cached keeps the cached conclusion over a not-requested placeholder`() }
        runIsolated { s -> s.`merged coverage and carried points are consistent`() }
    }
}

private class CommentEmojiAdapterCases {
    fun legacyEmojiDictionaryMatchesCurrentSpanRegardlessOfMapOrder() {
        val content = com.bilibili.app.comm.comment2.model.BiliComment.Content("[dog][cat][dog]")
        content.emote["[cat]"] = FakeSpan("https://i0.hdslb.com/cat.webp")
        content.emote["[dog]"] = FakeSpan("https://i0.hdslb.com/dog.webp")
        val result = CommentEmojiAdapter.resolve(content, content.mMsg, listOf(
            FakeSpan("https://i0.hdslb.com/dog.webp"), FakeSpan("https://i0.hdslb.com/cat.webp"),
            FakeSpan("https://i0.hdslb.com/dog.webp")))
        assertEquals(listOf("[dog]", "[cat]", "[dog]"), result.emotes.map { it.token })
        assertEquals(3, result.urlMatchedCount)
    }
    fun pushRichTextSourcePreservesEmojiTokensWithoutACommentItemWrapper() {
        val raw = "[dog]"
        val url = "https://i0.hdslb.com/dog.webp"
        val rich = FakeRichText(raw, listOf(FakeEmote(url, "", raw)))
        val span = FakeSpan(url)
        val result = CommentEmojiAdapter.resolve(rich, raw, listOf(span))
        assertEquals(raw, result.emotes.single().token)
        assertTrue(result.spanMatches.single().span === span)
    }

    private class FakeRichText(
        @Suppress("unused") val raw: String,
        @Suppress("unused") val contents: List<Any>
    )

    private class FakeCommentItem(private val richText: FakeRichText) {
        @Suppress("unused")
        fun richPayload(): FakeRichText = richText
    }

    private class FakeEmote(
        @Suppress("unused") private val imageUrl: String,
        @Suppress("unused") private val animUrl: String,
        @Suppress("unused") private val text: String
    ) {
        override fun toString(): String = "Emote(text=$text)"
    }

    private open class FakeSpanBase(@Suppress("unused") private val resourceUrl: String)
    private class FakeSpan(resourceUrl: String) : FakeSpanBase(resourceUrl)
    private class FakeOtherSpan(@Suppress("unused") private val resourceUrl: String)

    fun featureLocatedRichTextMapsStaticAndAnimatedUrlsIndependently() {
        val longToken = "[联动_${"很长".repeat(18)}]"
        val staticUrl = "https://i0.hdslb.com/static.webp"
        val animatedUrl = "https://i0.hdslb.com/animated.webp"
        val raw = "前${longToken}中[dog]后"
        val item = FakeCommentItem(
            FakeRichText(
                raw,
                listOf(
                    FakeEmote(staticUrl, "", longToken),
                    FakeEmote("https://i0.hdslb.com/dog.webp", animatedUrl, "[dog]")
                )
            )
        )
        val first = FakeSpan(staticUrl)
        val unrelated = FakeOtherSpan("https://i0.hdslb.com/card.webp")
        val second = FakeSpan(animatedUrl)

        val result = CommentEmojiAdapter.resolve(item, raw, listOf(first, unrelated, second))

        assertEquals(2, result.emotes.size)
        assertEquals(2, result.urlMatchedCount)
        assertTrue(result.spanMatches[0].span === first)
        assertEquals(longToken, result.spanMatches[0].emote.token)
        assertTrue(result.spanMatches[1].span === second)
        assertEquals("[dog]", result.spanMatches[1].emote.token)
        assertEquals(raw.indexOf(longToken), result.emotes[0].rawStart)
        assertEquals(raw.indexOf("[dog]"), result.emotes[1].rawStart)
    }

    fun nonEmoteReplacementDoesNotDiscardConfirmedEmojiMatch() {
        val raw = "正文[dog]卡片"
        val url = "https://i0.hdslb.com/dog.webp"
        val item = FakeCommentItem(FakeRichText(raw, listOf(FakeEmote(url, "", "[dog]"))))
        val unrelated = FakeOtherSpan("https://i0.hdslb.com/card.webp")
        val emoji = FakeSpan(url)

        val result = CommentEmojiAdapter.resolve(item, raw, listOf(unrelated, emoji))

        assertEquals(2, result.inspectedSpanCount)
        assertEquals(1, result.urlMatchedCount)
        assertTrue(result.spanMatches.single().span === emoji)
        assertEquals("[dog]", result.spanMatches.single().emote.token)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (CommentEmojiAdapterCases) -> Unit) {
            val s = CommentEmojiAdapterCases()
            block(s)
        }
        runIsolated { s -> s.legacyEmojiDictionaryMatchesCurrentSpanRegardlessOfMapOrder() }
        runIsolated { s -> s.pushRichTextSourcePreservesEmojiTokensWithoutACommentItemWrapper() }
        runIsolated { s -> s.featureLocatedRichTextMapsStaticAndAnimatedUrlsIndependently() }
        runIsolated { s -> s.nonEmoteReplacementDoesNotDiscardConfirmedEmojiMatch() }
    }
}

private class CommentTextIdentityCases {

    fun decoratedReplyNameIsRemovedOnlyFromVerifiedCompleteBody() {
        val raw = "5000多楼6225评论(;¬_¬)手有点酸，如果不是特殊方法进来的话是更多完整正文[大哭]"
        val prefix = "名字里:也有冒号: "
        val rendered = prefix + raw.replace("[大哭]", "\u200B") + "\u200B"
        val emoji = rendered.indexOf('\u200B')
        val start = CommentTextIdentity.replyBodyStart(raw, rendered, listOf(emoji..emoji),
            listOf(0 until prefix.length - 2))
        assertEquals(prefix.length, start)
        assertEquals(CommentTextIdentity.matchKey(raw), CommentTextIdentity.matchKey(
            rendered.substring(start!!), listOf(emoji - start..emoji - start)))
        assertNull(CommentTextIdentity.replyBodyStart(raw, rendered, listOf(emoji..emoji), emptyList()))
        assertNull(CommentTextIdentity.replyBodyStart("另一条完整正文", rendered, listOf(emoji..emoji),
            listOf(0 until prefix.length - 2)))
    }

    fun previewProjectionRejectsTruncationAndUnmarkedBodyPrefixes() {
        val raw = "不能截断的完整回复正文"
        assertNull(CommentTextIdentity.replyBodyStart(raw, "昵称: 不能截断...", emptyList(), listOf(0..1)))
        assertNull(CommentTextIdentity.replyBodyStart(raw, "额外正文" + raw, emptyList(), listOf(0..1)))
        assertNull(CommentTextIdentity.replyBodyStart("哈哈", "昵称: 哈哈", emptyList(), listOf(0..1)))
        assertEquals(0, CommentTextIdentity.replyBodyStart(raw, raw, emptyList(), emptyList()))
    }

    fun pureEmojiReplyNeedsTheSameCountAndDecoratedNameBoundary() {
        val rendered = "昵称: \u200B\u200B\u200B"
        val ranges = listOf(4..4, 5..5, 6..6)
        assertEquals(4, CommentTextIdentity.replyBodyStart("[大哭][大哭][大哭]", rendered, ranges, listOf(0..1)))
        assertNull(CommentTextIdentity.replyBodyStart("[大哭][大哭][大哭][大哭]", rendered, ranges, listOf(0..1)))
        assertNull(CommentTextIdentity.replyBodyStart("[大哭][大哭]", rendered, ranges, listOf(0..1)))
    }

    fun literalSearchSpanAndThreeEmojiKeepTheSameCommentIdentity() {
        val prefix = "人类最古のav号（挂了的不算）也变成bv了"
        val raw = prefix + "[大哭][大哭][大哭]青春结束了"
        val visible = prefix + "\u200B\u200B\u200B青春结束了"
        val keyword = prefix.indexOf("bv")
        val ranges = listOf(keyword until keyword + 2) + (0..2).map {
            prefix.length + it..prefix.length + it
        }
        assertFalse(CommentTextIdentity.isEmojiReplacement(visible, keyword, keyword + 2))
        assertEquals(CommentTextIdentity.matchKey(raw), CommentTextIdentity.matchKey(visible, ranges))
        assertTrue(CommentTextIdentity.isEmojiReplacement(visible, prefix.length, prefix.length + 1))
        assertFalse(CommentTextIdentity.matchKey(raw) == CommentTextIdentity.matchKey(
            visible.replace("人类", "另一条"), ranges))
    }

    fun replacementClassificationRequiresOneWholeEmojiRepresentation() {
        assertTrue(CommentTextIdentity.isEmojiReplacement("[大哭]", 0, 4))
        assertTrue(CommentTextIdentity.isEmojiReplacement("😀", 0, 2))
        assertFalse(CommentTextIdentity.isEmojiReplacement("文字😀", 0, 4))
        assertFalse(CommentTextIdentity.isEmojiReplacement("bv", -1, 2))
        assertFalse(CommentTextIdentity.isEmojiReplacement("bv", 1, 1))
    }

    fun customEmojiAndReplacementSpanProduceSameIdentity() {
        val slot = CommentTextIdentity.EMOJI_SLOT
        assertEquals("前${slot}后", CommentTextIdentity.matchKey("前[dog]后"))
        assertEquals("前${slot}后", CommentTextIdentity.matchKey("前\uFFFC后", listOf(1..1)))
    }

    fun unicodeEmojiSequencesOccupyOneSlotEach() {
        val slot = CommentTextIdentity.EMOJI_SLOT
        assertEquals("甲${slot}乙", CommentTextIdentity.matchKey("甲👨‍👩‍👧‍👦乙"))
        assertEquals("甲${slot}乙", CommentTextIdentity.matchKey("甲🇨🇳乙"))
        assertEquals("甲${slot}乙", CommentTextIdentity.matchKey("甲1️⃣乙"))
    }

    fun plainDigitsAndTextAreNotMisclassifiedAsEmoji() {
        assertEquals("版本9.8.0", CommentTextIdentity.matchKey(" 版本 9.8.0… "))
    }

    fun emojiCountAndPositionRemainPartOfIdentity() {
        val slot = CommentTextIdentity.EMOJI_SLOT
        assertEquals("${slot}相同${slot}", CommentTextIdentity.matchKey("[dog]相同[笑哭]"))
        assertEquals("相同${slot}", CommentTextIdentity.matchKey("相同[dog]"))
    }

    fun unspannedZeroWidthSentinelDoesNotChangeIdentity() {
        val slot = CommentTextIdentity.EMOJI_SLOT
        assertEquals(
            slot.toString(),
            CommentTextIdentity.matchKey("\u200B\u200B", listOf(0..0))
        )
    }

    fun longBilibiliCustomEmojiTokenStillOccupiesOneSlot() {
        val token = "[联动_${"很长".repeat(18)}]"

        assertEquals(
            "前${CommentTextIdentity.EMOJI_SLOT}后",
            CommentTextIdentity.matchKey("前${token}后")
        )
    }

    fun styledFoldControlTailIsProjectedBeforeExpansionLabel() {
        val text = "前\u200B后续内容... 展开"
        val markerStart = text.indexOf("...")
        val actionStart = text.indexOf("展开")

        assertEquals(
            markerStart,
            CommentTextIdentity.foldControlStart(
                text,
                listOf(markerStart until actionStart, actionStart until text.length)
            )
        )
    }

    fun localizedFoldActionAndSuffixDoNotNeedHardCodedText() {
        val text = "visible body... Show more ›"
        val markerStart = text.indexOf("...")
        val actionStart = text.indexOf("Show")
        val actionEnd = text.indexOf('›') - 1

        assertEquals(
            markerStart,
            CommentTextIdentity.foldControlStart(
                text,
                listOf(markerStart until actionStart, actionStart..actionEnd)
            )
        )
    }

    fun userWrittenExpansionWordWithoutStyledMarkerIsNotRemoved() {
        val text = "请展开说明，这不是折叠控制"
        val actionStart = text.indexOf("展开")

        assertNull(
            CommentTextIdentity.foldControlStart(
                text,
                listOf(actionStart until actionStart + 2)
            )
        )
    }

    fun literalEllipsisAndExpansionTextWithoutAdjacentDecorationsIsNotRemoved() {
        val text = "用户原文... 展开"
        val markerStart = text.indexOf("...")

        assertNull(
            CommentTextIdentity.foldControlStart(text, listOf(markerStart until markerStart + 3))
        )
        assertNull(CommentTextIdentity.foldControlStart(text, emptyList()))
    }

    fun decoratedEllipsisInMiddleOfLongCommentIsNotFoldControl() {
        val text = "正文... 链接" + "后续内容".repeat(40)
        val markerStart = text.indexOf("...")
        val actionStart = text.indexOf("链接")

        assertNull(
            CommentTextIdentity.foldControlStart(
                text,
                listOf(markerStart until actionStart, actionStart until actionStart + 2)
            )
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (CommentTextIdentityCases) -> Unit) {
            val s = CommentTextIdentityCases()
            block(s)
        }
        runIsolated { s -> s.decoratedReplyNameIsRemovedOnlyFromVerifiedCompleteBody() }
        runIsolated { s -> s.previewProjectionRejectsTruncationAndUnmarkedBodyPrefixes() }
        runIsolated { s -> s.pureEmojiReplyNeedsTheSameCountAndDecoratedNameBoundary() }
        runIsolated { s -> s.literalSearchSpanAndThreeEmojiKeepTheSameCommentIdentity() }
        runIsolated { s -> s.replacementClassificationRequiresOneWholeEmojiRepresentation() }
        runIsolated { s -> s.customEmojiAndReplacementSpanProduceSameIdentity() }
        runIsolated { s -> s.unicodeEmojiSequencesOccupyOneSlotEach() }
        runIsolated { s -> s.plainDigitsAndTextAreNotMisclassifiedAsEmoji() }
        runIsolated { s -> s.emojiCountAndPositionRemainPartOfIdentity() }
        runIsolated { s -> s.unspannedZeroWidthSentinelDoesNotChangeIdentity() }
        runIsolated { s -> s.longBilibiliCustomEmojiTokenStillOccupiesOneSlot() }
        runIsolated { s -> s.styledFoldControlTailIsProjectedBeforeExpansionLabel() }
        runIsolated { s -> s.localizedFoldActionAndSuffixDoNotNeedHardCodedText() }
        runIsolated { s -> s.userWrittenExpansionWordWithoutStyledMarkerIsNotRemoved() }
        runIsolated { s -> s.literalEllipsisAndExpansionTextWithoutAdjacentDecorationsIsNotRemoved() }
        runIsolated { s -> s.decoratedEllipsisInMiddleOfLongCommentIsNotFoldControl() }
    }
}

private class CommentTopologyPointsCases {

    fun jsonRoundTripPreservesMapperMossAndMethodPoints() {
        val methods = VersionAdapter.CommentTopologyPoints.REQUIRED_METHOD_KEYS
            .mapIndexed { index, key ->
                key to VersionAdapter.HookPoint(
                    className = "topology.Owner$index",
                    methodName = "method$index",
                    paramClassNames = if (index % 2 == 0) listOf("long", "java.lang.String") else null,
                    viewField = if (index == 0) "verifiedField" else null
                )
            }
            .toMap(linkedMapOf())
        val original = VersionAdapter.CommentTopologyPoints(
            mapperMethods = listOf(
                VersionAdapter.HookPoint(
                    className = "topology.Mapper",
                    methodName = "map",
                    paramClassNames = listOf("topology.ReplyInfo"),
                    viewField = "commentItem"
                ),
                VersionAdapter.HookPoint(
                    className = "topology.LegacyMapper",
                    methodName = "mapLegacy",
                    paramClassNames = listOf("topology.ReplyInfo", "boolean")
                )
            ),
            replyMossClassName = "topology.ReplyMoss",
            methods = methods
        )

        val restored = VersionAdapter.CommentTopologyPoints.fromJson(original.toJson())

        assertEquals(original, restored)
        assertEquals(methods.keys, restored.methods.keys)
        assertTrue(restored.hasRequiredMethods())
    }

    fun requiredMethodValidationRejectsAnyMissingKeyAndAllowsExtraKeys() {
        val requiredMethods = VersionAdapter.CommentTopologyPoints.REQUIRED_METHOD_KEYS
            .associateWithTo(linkedMapOf()) { key ->
                VersionAdapter.HookPoint("topology.$key", "read")
            }
        val points = VersionAdapter.CommentTopologyPoints(
            mapperMethods = listOf(VersionAdapter.HookPoint("topology.Mapper", "map")),
            replyMossClassName = "topology.ReplyMoss",
            methods = requiredMethods
        )

        assertTrue(points.hasRequiredMethods())

        assertFalse(points.copy(mapperMethods = emptyList()).hasRequiredMethods())
        assertFalse(
            points.copy(
                mapperMethods = List(
                    VersionAdapter.CommentTopologyPoints.MAX_MAPPER_METHODS + 1
                ) { index -> VersionAdapter.HookPoint("topology.Mapper$index", "map") }
            ).hasRequiredMethods()
        )

        VersionAdapter.CommentTopologyPoints.REQUIRED_METHOD_KEYS.forEach { missingKey ->
            assertFalse(
                "missing required method key must invalidate points: $missingKey",
                points.copy(methods = requiredMethods - missingKey).hasRequiredMethods()
            )
        }

        assertTrue(
            points.copy(
                methods = requiredMethods +
                    ("future.optional" to VersionAdapter.HookPoint("topology.Optional", "read"))
            ).hasRequiredMethods()
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (CommentTopologyPointsCases) -> Unit) {
            val s = CommentTopologyPointsCases()
            block(s)
        }
        runIsolated { s -> s.jsonRoundTripPreservesMapperMossAndMethodPoints() }
        runIsolated { s -> s.requiredMethodValidationRejectsAnyMissingKeyAndAllowsExtraKeys() }
    }
}

private class DetailRelateServiceLocatorCases {
    fun verifiedDispatcherWinsOverIdenticallyTypedSpecializedFactories() {
        val factory = requireNotNull(VersionAdapter.selectDetailRelateComponentFactory(ModernRelateService::class.java))
        assertEquals("C0", factory.name)
        assertEquals(listOf(r2::class.java), factory.parameterTypes.toList())
        assertNull(factory.invoke(ModernRelateService(), r2()))
    }

    fun unknownDtoAndUnresolvedSpecializedTiesAreRejected() {
        assertNull(VersionAdapter.selectDetailRelateComponentFactory(UnverifiedRelateService::class.java))
        assertNull(VersionAdapter.selectDetailRelateComponentFactory(AmbiguousRelateService::class.java))
    }

    fun legacyDispatcherRemainsAvailable() {
        assertEquals("d", VersionAdapter.selectDetailRelateComponentFactory(DetailRelateService::class.java)?.name)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (DetailRelateServiceLocatorCases) -> Unit) {
            val s = DetailRelateServiceLocatorCases()
            block(s)
        }
        runIsolated { s -> s.verifiedDispatcherWinsOverIdenticallyTypedSpecializedFactories() }
        runIsolated { s -> s.unknownDtoAndUnresolvedSpecializedTiesAreRejected() }
        runIsolated { s -> s.legacyDispatcherRemainsAvailable() }
    }
}
