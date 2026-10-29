package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 性能报告 P1-01b：三类历史兜底（更新检查、默认画质、评论脉络）必须经 AdaptAssistGate 入队。
 * 门控判定本身由 AdaptAssistGateTest 覆盖，这里锁定接线：不得再有「直接定位没找到就无条件查询」的路径。
 */
class AdaptAssistGateWiringContractTest {
    private val adapter = SourceContract.read("hook/VersionAdapter.kt")
    private val adapt = adapter.after("private fun adapt(context: Context").before("private data class ProtocolFingerprint")

    @Test fun plannedQueriesComeFromTheGateNotFromDirectLocationAlone() {
        val planned = adapt.after("planned = buildSet {").before("classicMissing.forEach")
        assertTrue(planned.contains("if (blockUpdatePlanned) add(DexAssistQuery.BLOCK_UPDATE)"))
        assertTrue(planned.contains("if (playerQualityPlanned) add(DexAssistQuery.PLAYER_DEFAULT_QUALITY)"))
        assertTrue(planned.contains("if (topologyPlanned) add(DexAssistQuery.COMMENT_REPLY_MAPPER)"))
        assertFalse(planned.contains("if (directBlockUpdate == null)"))
        assertFalse(planned.contains("if (playerQualityNeedsAssist)"))
        assertFalse(planned.contains("if (directTopologyOutcome.points == null)"))
    }

    @Test fun everyGateUsesFeatureFlagCachedPointAndHistory() {
        for ((flag, planned) in listOf("blockUpdateEnabled" to "blockUpdatePlanned", "playerQualityEnabled" to "playerQualityPlanned",
            "commentTopologyEnabled" to "topologyPlanned")) {
            val line = adapt.after("val $planned = AdaptAssistGate.needsQuery($flag,").before("val ")
            assertTrue(planned, line.contains("assistHistory."))
        }
    }

    @Test fun dexLocatorsRunOnlyForPlannedFamilies() {
        assertTrue(adapt.contains("blockUpdatePlanned -> locateBlockUpdateByDex(loader, dexAssist)"))
        assertTrue(adapt.contains("playerQualityPlanned -> locatePlayerQualityByDex(loader, dexAssist)"))
        assertTrue(adapt.contains("topologyPlanned -> locateCommentTopologyMapperByDex(loader, dexAssist)"))
        for (call in listOf("locateBlockUpdateByDex(", "locatePlayerQualityByDex(", "locateCommentTopologyMapperByDex(")) {
            assertEquals(call, 1, Regex(Regex.escape(call)).findAll(adapt).count())
        }
    }

    @Test fun skippedFamiliesKeepCachedPointsAndNeverBecomeMissing() {
        assertTrue(adapt.contains("point = assistHistory.blockUpdate"))
        assertTrue(adapt.contains("val carriedQuality = if (playerQualityNeedsAssist && !playerQualityPlanned) assistHistory.assistedQuality else null"))
        assertTrue(adapt.contains("?: if (topologyPlanned) null else assistHistory.commentTopology"))
        assertEquals(3, Regex("""AdaptAssistGate\.skippedDiagnostic\(""").findAll(adapt).count())
    }

    @Test fun cacheFastPathRequestsASupplementalScanWhenAFeatureIsEnabledLater() {
        val cached = adapter.after("val cached = usable?.takeUnless { value ->").before("if (cached != null) {")
        assertTrue(cached.contains("assistRefreshNeeded(classLoader, value, blockUpdateEnabled, playerQualityEnabled, commentTopologyEnabled)"))
        // 功能关闭时 internal 判定在求值直接定位前就短路。
        val gate = SourceContract.read("hook/adapter/AdaptAssistGate.kt")
        assertTrue(gate.contains("enabled && !cachedPresent && attempt != Attempt.CONCLUSIVE_MISSING && !directFound()"))
        assertTrue(gate.contains("enabled && !cachedPresent && attempt == Attempt.NOT_REQUESTED && !directFound()"))
        val refresh = adapter.after("internal fun assistRefreshNeeded(").before("private data class BlockUpdateDexAssist")
        assertEquals(3, Regex("""AdaptAssistGate\.needsSupplementalScan\(""").findAll(refresh).count())
        assertFalse(refresh.contains("AdaptAssistGate.needsQuery("))
    }

    @Test fun startupPassesTheFeatureSwitchesFromTheSameConfigurationSnapshot() {
        val hook = SourceContract.read("hook/HookEntry.kt").after("VersionAdapter.ensureAdapted(").before("}.onFailure")
        assertTrue(hook.contains("blockUpdateEnabled = prefs.getBoolean(FeaturePreferences.BLOCK_APP_UPDATE, false)"))
        assertTrue(hook.contains("commentTopologyEnabled = prefs.getBoolean(FeaturePreferences.REPLY_TOPOLOGY_ENABLED, false)"))
        // 同一份 prefs 也驱动对应 installer，需求与安装不会各读一份。
        val source = SourceContract.read("hook/HookEntry.kt")
        assertTrue(source.contains("enabled = prefs.getBoolean(FeaturePreferences.BLOCK_APP_UPDATE, false)"))
        assertTrue(source.contains("val commentTopologyEnabled = prefs.getBoolean(FeaturePreferences.REPLY_TOPOLOGY_ENABLED, false)"))
    }
}
