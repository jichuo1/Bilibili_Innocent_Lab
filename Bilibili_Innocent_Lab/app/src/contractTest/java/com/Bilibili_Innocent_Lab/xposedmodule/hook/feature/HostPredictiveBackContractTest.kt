package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPredictiveBackContractTest {
    @Test
    fun `host predictive back installer is wired into hook entry and settings ui remains visible on api 36 plus`() {
        val hookEntry = SourceContract.read("hook/HookEntry.kt")
        val targetInstall = hookEntry.after("systemEnvInstaller,").before("classicInstaller,")
        assertTrue(targetInstall.contains("HostPredictiveBackFeatureInstaller("))
        assertTrue(targetInstall.contains("FeaturePreferences.HOST_PREDICTIVE_BACK_ENABLED"))

        val sections = SourceContract.read("ui/activity/SettingsCardSections.kt")
        val hostBackSection = sections.after("SettingsCardSections:SystemEnvDetectionTip")
            .before("if (!PredictiveBack.isSystemEnforced)")
        assertTrue(hostBackSection.contains("SettingsCardSections:HostPredictiveBack"))
        assertTrue(hostBackSection.contains("FeaturePreferences.HOST_PREDICTIVE_BACK_ENABLED"))
        assertTrue(hostBackSection.contains("applyModeledToggle(hostPredictiveBackKey, checked, button)"))

        val presenter = SourceContract.read("ui/activity/SettingCommandsPresenter.kt")
        val executeToggle = presenter.after("internal fun MainActivity.executeModeledToggle(")
            .before("internal val modeledToggleKeys")
        assertTrue(executeToggle.contains("FeaturePreferences.HOST_PREDICTIVE_BACK_ENABLED"))
        val modeledKeys = presenter.after("internal val modeledToggleKeys =")
            .before("internal fun MainActivity.synchronizeSettingField")
        assertTrue(modeledKeys.contains("FeaturePreferences.HOST_PREDICTIVE_BACK_ENABLED"))
    }
}
