package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptDiagnostic
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.PlayerQualityPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptAssistGate
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptAssistGate.Attempt
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptAssistGate.Family
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 性能报告 P1-01b：更新检查、默认画质、评论脉络三类 DEX 兜底按功能需求与历史结论入队，
 * 跳过的结果是「未请求」而不是「缺失」，用户开启功能后补扫一次。
 */
class AdaptAssistGateTest {
    /** 没有任何宿主类：直接定位全部落空（测试源集里的夹具类在系统 ClassLoader 里，不能用它）。 */
    private val loader: ClassLoader = object : ClassLoader(null) {}
    /** 夹具类可见：直接定位全部满足。 */
    private val fixtureLoader: ClassLoader = ClassLoader.getSystemClassLoader()
    private fun cached() = VersionAdapterTest().result().copy(blockUpdate = null, playerQuality = null,
        commentTopology = null, diagnostics = emptyList())

    private fun diag(family: Family, state: AdaptState, detail: String = "") =
        AdaptDiagnostic(family.diagnosticId, state, detail)

    @Test fun diagnosticIdsStayCompatibleWithExistingCaches() {
        assertEquals("dex.assist", Family.BLOCK_UPDATE.diagnosticId)
        assertEquals("dex.assist.player_quality", Family.PLAYER_QUALITY.diagnosticId)
        assertEquals("dex.assist.comment_topology", Family.COMMENT_TOPOLOGY.diagnosticId)
    }

    @Test fun cachedOutcomesClassifyIntoRequestedFoundConclusiveAndRetryable() {
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

    @Test fun onlyTheMatchingFamilyDiagnosticCounts() {
        val others = listOf(diag(Family.PLAYER_QUALITY, AdaptState.MISSING, "player-quality:no_match"),
            AdaptDiagnostic("dex.assist.follow_feed_style", AdaptState.MISSING, "no_match"))
        assertEquals(Attempt.NOT_REQUESTED, AdaptAssistGate.attempt(others, Family.BLOCK_UPDATE))
        assertEquals(Attempt.CONCLUSIVE_MISSING, AdaptAssistGate.attempt(others, Family.PLAYER_QUALITY))
        // 同 id 多条时以最后一条为准。
        val history = listOf(diag(Family.BLOCK_UPDATE, AdaptState.MISSING, "block-update:no_match"),
            diag(Family.BLOCK_UPDATE, AdaptState.FOUND, "block-update:a#b"))
        assertEquals(Attempt.FOUND, AdaptAssistGate.attempt(history, Family.BLOCK_UPDATE))
    }

    @Test fun queryIsNeededOnlyWhenEnabledUnsatisfiedUncachedAndNotConclusivelyMissing() {
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

    @Test fun supplementalScanIsNarrowerThanPlanningAndFiresOnlyForNeverRequestedFamilies() {
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

    @Test fun skippedFamiliesAreNotRequestedUnlessAConclusionAlreadyExists() {
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

    @Test fun cacheFastPathHasNoSupplementalScanWhenTheFeaturesAreOff() {
        val retryable = listOf(diag(Family.BLOCK_UPDATE, AdaptState.MISSING, "block-update:native_unavailable"))
        assertFalse(VersionAdapter.assistRefreshNeeded(loader, cached(), false, false, false))
        assertFalse(VersionAdapter.assistRefreshNeeded(loader, cached().copy(diagnostics = retryable), false, false, false))
    }

    @Test fun enablingAFeatureWithoutACachedPointTriggersExactlyThatSupplementalScan() {
        assertTrue(VersionAdapter.assistRefreshNeeded(loader, cached(), true, false, false))
        assertTrue(VersionAdapter.assistRefreshNeeded(loader, cached(), false, true, false))
        assertTrue(VersionAdapter.assistRefreshNeeded(loader, cached(), false, false, true))
    }

    @Test fun directLocationThatAlreadySatisfiesTheFeatureNeverRequestsAScan() {
        assertFalse(VersionAdapter.assistRefreshNeeded(fixtureLoader, cached(), true, true, true))
    }

    @Test fun cachedAssistedPointsAndConfirmedMissingResultsSatisfyTheCacheOnNextStart() {
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

    @Test fun defaultQualityOnlyCountsTheAssistedImplementationNotTheLegacyWrapper() {
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

    @Test fun missingCacheMeansEveryFamilyIsUnrequestedAndNothingIsCarried() {
        val empty = VersionAdapter.AssistHistory.of(null)
        assertSame(VersionAdapter.AssistHistory.EMPTY, empty)
        for (family in Family.values()) assertEquals(Attempt.NOT_REQUESTED, empty.attempt(family))
        assertNull(empty.blockUpdate); assertNull(empty.commentTopology); assertFalse(empty.hasAssistedQuality)
    }
}
