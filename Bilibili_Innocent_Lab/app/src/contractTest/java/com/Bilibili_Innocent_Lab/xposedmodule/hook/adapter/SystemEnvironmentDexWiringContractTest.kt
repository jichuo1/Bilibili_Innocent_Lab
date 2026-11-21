package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemEnvironmentDexWiringContractTest {
    @Test
    fun `system environment boundaries only use planned background dex queries and verified exact hooks`() {
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate(").before("private fun adapt(")
        assertFalse(quick.contains("SystemEnvironmentAdapter"))
        assertFalse(quick.contains("DexAssistSession("))
        assertFalse(quick.contains("DexRefHookBridge"))

        val adapt = adapter.after("private fun adapt(").before("private data class ProtocolFingerprint")
        assertTrue(adapt.contains("SystemEnvironmentAdapter.planned("))
        assertTrue(adapt.contains("systemEnvPlanned.forEach { add(it.query) }"))
        assertTrue(adapt.contains("SystemEnvironmentAdapter.locate("))
        assertTrue(adapt.contains("SystemEnvironmentAdapter.conclusive("))
        assertTrue(adapt.contains("\"feature-disabled:cached-attempt-preserved\""))

        val merge = adapter.after("internal fun mergeRuntimeWithCached(")
            .before("internal fun AdaptResult.applyStartupExclusions(")
        assertTrue(merge.contains("SystemEnvironmentAdapter.merge("))

        val queries = SourceContract.read("hook/adapter/dex/DexMeguvizQueries.kt")
        val rootQuery = queries.after("DexAssistQuery.SYSTEM_ENV_ROOT_RESULT ->")
            .before("DexAssistQuery.SYSTEM_ENV_HOOK_RESULT ->")
        assertTrue(rootQuery.contains("SystemEnvironmentAdapter.ENV_PARAM_SET"))
        assertTrue(rootQuery.contains("SystemEnvironmentAdapter.ENV_STRINGS"))
        assertFalse(rootQuery.contains("/system/bin/su"))
        assertFalse(rootQuery.contains("name = \"b\""))

        val hookQuery = queries.after("DexAssistQuery.SYSTEM_ENV_HOOK_RESULT ->")
            .before("DexAssistQuery.BLOCK_UPDATE ->")
        assertTrue(hookQuery.contains("SystemEnvironmentAdapter.HOOK_STRINGS"))
        assertFalse(hookQuery.contains("/system/bin/su"))
        assertFalse(hookQuery.contains("name = \"c\""))

        val installer = SourceContract.read("hook/feature/SystemEnvironmentFeatureInstaller.kt")
        assertTrue(installer.contains("SystemEnvironmentAdapter.resolve("))
        assertTrue(installer.contains("environment.registrar.exact("))
        assertTrue(installer.contains("environment.forCapabilityRuntime("))
        assertFalse(installer.contains("DexRefHookBridge"))
        assertFalse(installer.contains("DexAssistSession"))

        val hookEntry = SourceContract.read("hook/HookEntry.kt")
        val ensureAdapted = hookEntry.after("VersionAdapter.ensureAdapted(").before("}.onFailure")
        assertTrue(
            ensureAdapted.contains("systemEnvironmentEnabled = prefs.getBoolean(FeaturePreferences.BLOCK_SYSTEM_ENV_DETECTION, false)")
        )
        val adaptFinished = hookEntry.after("override fun onAdaptFinished(ok: Boolean)")
            .before("if (sponsorInstaller.requiresAdaptationRetry")
        assertTrue(adaptFinished.contains("if (systemEnvInstaller.requiresAdaptationRetry)"))
        assertTrue(adaptFinished.contains("systemEnvPointsRef.set(adapted?.systemEnvironment)"))

        val presenter = SourceContract.read("ui/activity/SettingCommandsPresenter.kt")
        val executeToggle = presenter.after("internal fun MainActivity.executeModeledToggle(")
            .before("internal val modeledToggleKeys")
        assertTrue(executeToggle.contains("FeaturePreferences.BLOCK_SYSTEM_ENV_DETECTION"))
        val modeledKeys = presenter.after("internal val modeledToggleKeys =")
            .before("internal fun MainActivity.synchronizeSettingField")
        assertTrue(modeledKeys.contains("FeaturePreferences.BLOCK_SYSTEM_ENV_DETECTION"))
        assertTrue(modeledKeys.contains("HookEntry.PREF_PREDICTIVE_BACK_ENABLED"))
    }
}
