package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.*
import org.junit.Test

class SwitchConfirmationMotionTest {
    @Test fun lightModeConfirmationUsesTheSwitchTitleAndKeepsTheWarning() {
        val body = SettingsUiSource.function("showAutoLightConfirmDialog")
        assertTrue(body.contains("anchor: View? = null"))
        val title = body.indexOf("getString(R.string.free_copy_light_mode)")
        val warning = body.indexOf("getString(R.string.free_copy_light_mode_confirm_title)")
        assertTrue(title >= 0 && warning > title)
        assertTrue(body.contains("presentModalDialog(dialog, container, anchor, onBackDismiss = onCancel)"))
        assertTrue(body.contains("dismissWithAnimation(dialog, container) { onCancel() }"))
        assertTrue(body.contains("dismissWithAnimation(dialog, container) { onConfirm() }"))
    }

    @Test fun theSwitchPassesItsOwnViewAsTheAnimationAnchor() {
        val main = SettingsUiSource.code(SettingsUiSource.mainActivity())
        assertTrue(Regex("showAutoLightConfirmDialog\\(\\s*anchor = button,").containsMatchIn(main))
    }
}
