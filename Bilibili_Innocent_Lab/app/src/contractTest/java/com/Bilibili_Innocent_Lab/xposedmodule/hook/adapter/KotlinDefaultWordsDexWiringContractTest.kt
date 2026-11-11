package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

class KotlinDefaultWordsDexWiringContractTest {
    @Test fun queryUsesSemanticInvokeAndRunsOnlyInSharedBackgroundPlan() {
        val engine = SourceContract.read("hook/adapter/dex/DexMeguvizQueries.kt")
        val query = engine.after("DexAssistQuery.SEARCH_DEFAULT_WORDS_KOTLIN ->").before("DexAssistQuery.PLAYER_SPEED_MENU, DexAssistQuery.STORY_QUALITY_DEFAULT -> error")
        assertTrue(query.contains("returnType = \"void\""))
        assertTrue(query.contains("paramCount(5)"))
        assertTrue(query.contains("addInvoke"))
        assertTrue(query.contains("KotlinDefaultWordsLocator.DESCRIPTOR_GETTER"))
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate").before("private fun adapt(")
        assertFalse(quick.contains("DexAssistSession("))
        assertFalse(quick.contains("locateKotlinDefaultWordsByDex("))
        val background = adapter.after("private fun adapt(").before("private data class ProtocolFingerprint")
        assertTrue(background.contains("if (defaultWordsNeedsAssist) add(DexAssistQuery.SEARCH_DEFAULT_WORDS_KOTLIN)"))
        assertTrue(background.contains("DexAssistAttemptGuard("))
        assertTrue(background.contains("if (defaultWordsNeedsAssist) locateKotlinDefaultWordsByDex(dexAssist)"))
    }

    @Test fun featureFlagAndCachedFailurePreventUnrequestedAndRepeatedScans() {
        val hook = SourceContract.read("hook/HookEntry.kt").after("VersionAdapter.ensureAdapted(").before("}.onFailure")
        assertTrue(hook.contains("kotlinDefaultWordsEnabled = prefs.getBoolean(FeaturePreferences.HIDE_HOME_SEARCH_DEFAULT_WORD, false)"))
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        // 相同宿主的快照先被保留给其它家族；默认词 refresh 仍只能拒绝已经 usable 的快照。
        val refresh = adapter.after("fun requiresFeatureRefresh(value: AdaptResult): Boolean")
            .before("val refreshRequested = usable?.let(::requiresFeatureRefresh)")
        assertTrue(refresh.contains("kotlinDefaultWordsEnabled && KotlinDefaultWordsLocator.refreshCache"))
        assertTrue(refresh.contains("it.state == AdaptState.MISSING"))
        // Repacked results must use the same feature-refresh gate as an ordinary startup cache hit.
        val worker = adapter.after("HostThreadGuard.run(\"adapter.adapt_worker\")").before("result = repacked ?:")
        assertTrue(worker.contains("!requiresFeatureRefresh(value)"))
        val installer = SourceContract.read("hook/feature/HomeTopBarFeatureInstaller.kt")
        assertFalse(installer.contains("DexRefHookBridge"))
        assertFalse(installer.contains("DexAssistSession("))
        assertTrue(installer.contains("resolvedEntry = points?.kotlinDefaultWords"))
    }
}
