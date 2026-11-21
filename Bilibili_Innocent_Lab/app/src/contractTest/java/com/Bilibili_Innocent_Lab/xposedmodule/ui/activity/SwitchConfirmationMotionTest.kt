package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.*
import org.junit.Test

class SwitchConfirmationMotionTest {
    fun lightModeConfirmationUsesTheSwitchTitleAndKeepsTheWarning() {
        val body = SettingsUiSource.function("showAutoLightConfirmDialog")
        assertTrue(body.contains("anchor: View? = null"))
        val title = body.indexOf("getString(R.string.free_copy_light_mode)")
        val warning = body.indexOf("getString(R.string.free_copy_light_mode_confirm_title)")
        assertTrue(title >= 0 && warning > title)
        assertTrue(body.contains("presentModalDialog(dialog, container, anchor, onBackDismiss = onCancel)"))
        assertTrue(body.contains("dismissWithAnimation(dialog, container) { onCancel() }"))
        assertTrue(body.contains("dismissWithAnimation(dialog, container) { onConfirm() }"))
    }

    fun theSwitchPassesItsOwnViewAsTheAnimationAnchor() {
        val main = SettingsUiSource.code(SettingsUiSource.settingsPage())
        assertTrue(Regex("applyModeledToggle\\(HookEntry\\.PREF_FREE_COPY_LIGHT_MODE,\\s*checked,\\s*button\\)")
            .containsMatchIn(main))
        assertTrue(SettingsUiSource.function("applyModeledToggle")
            .contains("requestSettingCommand(descriptor.id, requested, button)"))
        assertTrue(SettingsUiSource.function("requestSettingCommand")
            .contains("requestManualLightSetting(id, requested, anchor)"))
        assertTrue(Regex("showAutoLightConfirmDialog\\(\\s*anchor = anchor,")
            .containsMatchIn(SettingsUiSource.function("requestManualLightSetting")))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (SwitchConfirmationMotionTest) -> Unit) {
            val s = SwitchConfirmationMotionTest()
            block(s)
        }
        runIsolated { s -> s.lightModeConfirmationUsesTheSwitchTitleAndKeepsTheWarning() }
        runIsolated { s -> s.theSwitchPassesItsOwnViewAsTheAnimationAnchor() }
    }
}
