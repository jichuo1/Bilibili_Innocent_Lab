package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticCapabilityCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailUnitedPresentationPurifyFeatureInstallerTest {

    private val statuses = mutableListOf<Pair<String, String>>()
    private val errors = mutableListOf<String>()
    private val capabilities = mutableListOf<Pair<String, FeatureInstallResult>>()

    private fun environment(
        process: String = "tv.danmaku.bili",
        loader: ClassLoader? = null
    ) = HookEnvironment(
        processName = process,
        classLoader = loader ?: javaClass.classLoader,
        hookPoints = HookPointRegistry(javaClass.classLoader),
        registrar = TestHookRegistrar,
        logInfo = { _, _ -> },
        logError = { key, _ -> errors += key },
        reportStatus = { channel, status -> statuses += channel to status },
        capabilityEvidence = { id, result -> capabilities += id to result }
    )

    @Test fun `skips when neither existing detail setting is enabled`() {
        val result = DetailUnitedPresentationPurifyFeatureInstaller(false, false).install(environment())
        assertEquals(FeatureInstallResult.Skipped("disabled"), result)
        assertEquals(
            listOf("detail_united_presentation_purify_status" to "disabled"),
            statuses
        )
        assertTrue(capabilities.isEmpty())
    }

    @Test fun `skips in a non main process`() {
        val result = DetailUnitedPresentationPurifyFeatureInstaller(true, true)
            .install(environment(process = "tv.danmaku.bili:web"))
        assertEquals(FeatureInstallResult.Skipped("non-main-process"), result)
        assertTrue(statuses.isEmpty())
    }

    @Test fun `degrades when the current host does not expose the United rendering classes`() {
        val empty = object : ClassLoader(null) {}
        val result = DetailUnitedPresentationPurifyFeatureInstaller(true, true)
            .install(environment(loader = empty))
        assertEquals(FeatureInstallResult.Skipped("not-applicable-host"), result)
        assertTrue("detail_united_presentation_missing" in errors)
        assertTrue(statuses.single().second.startsWith("partial:0/2:"))
    }

    @Test fun `enabled leaves are reported separately and reuse the existing setting keys`() {
        val hotOnly = DetailUnitedPresentationPurifyFeatureInstaller(true, false)
        val topicOnly = DetailUnitedPresentationPurifyFeatureInstaller(false, true)
        assertEquals(
            listOf(DetailUnitedPresentationPurifyPolicy.HOT_BADGE_CAPABILITY_ID),
            hotOnly.capabilityIds
        )
        assertEquals(
            listOf(DetailUnitedPresentationPurifyPolicy.SPECIAL_TOPIC_CAPABILITY_ID),
            topicOnly.capabilityIds
        )

        val hot = DiagnosticCapabilityCatalog.byId
            .getValue(DetailUnitedPresentationPurifyPolicy.HOT_BADGE_CAPABILITY_ID)
        val topic = DiagnosticCapabilityCatalog.byId
            .getValue(DetailUnitedPresentationPurifyPolicy.SPECIAL_TOPIC_CAPABILITY_ID)
        assertEquals(DetailUnitedPresentationPurifyPolicy.ID, hot.parentId)
        assertEquals(DetailUnitedPresentationPurifyPolicy.ID, topic.parentId)
        assertEquals(
            setOf(SettingsCatalog.byStorageKey.getValue(FeaturePreferences.REMOVE_DETAIL_HOT_BANNER).id),
            hot.settingIds
        )
        assertEquals(
            setOf(SettingsCatalog.byStorageKey.getValue(FeaturePreferences.REMOVE_DETAIL_TOPIC_TAGS).id),
            topic.settingIds
        )
    }
}
