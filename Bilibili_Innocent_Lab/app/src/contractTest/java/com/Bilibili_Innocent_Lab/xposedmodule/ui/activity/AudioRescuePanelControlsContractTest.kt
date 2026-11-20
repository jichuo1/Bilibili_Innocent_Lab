package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音频拯救面板只用引擎标准控件：选项用 MaterialSwitch，声道单选用滑动选择面，
 * 操作用 createTermsActionButton，说明与状态走弹窗正文的共用控件。
 * 不再出现原生 CheckBox / RadioGroup / RadioButton，也不再把可点文字当按钮用。
 */
class AudioRescuePanelControlsContractTest {
    private fun dialog() = SettingsUiSource.code(SettingsUiSource.function("showAudioRescueDialog"))
    private fun assets() = SettingsUiSource.code(SettingsUiSource.function("addAudioAssetControls"))

    @Test fun `options and channel mode use the module switch and the sliding selection`() {
        val panel = dialog()
        assertTrue(panel.contains("MaterialSwitch(this, null)"))
        assertTrue(panel.contains("createSlidingChoice(labels = channelLabels, selectedIndex = channelMode)"))
        assertTrue(panel.contains("createModalTip("))
        listOf("CheckBox", "RadioButton", "RadioGroup", "selfRippleBackground(", "setOnClickListener").forEach {
            assertFalse("raw control $it must not come back", panel.contains(it))
        }
    }

    @Test fun `confirming commits the whole draft once and a failed write keeps the panel open`() {
        val panel = dialog()
        assertTrue(panel.contains("createTermsActionButton(getString(R.string.dialog_cancel), filled = false)"))
        val confirm = panel.substring(panel.indexOf("createTermsActionButton(getString(R.string.dialog_confirm), filled = true)"))
        assertTrue(confirm.contains(".putBoolean(audioKeys[0], guard.isChecked).putInt(audioKeys[1], channelMode)"))
        assertTrue(confirm.contains(".putBoolean(audioKeys[2], noise.isChecked).putBoolean(audioKeys[3], voice.isChecked).commit()"))
        assertTrue("one atomic commit", Regex("""\.commit\(\)""").findAll(panel).count() == 1)
        val failure = confirm.indexOf("return@createTermsActionButton")
        val dismiss = confirm.indexOf("dismissWithAnimation(dialog, container) {}")
        assertTrue("a failed write must return before the panel is dismissed", failure in 0 until dismiss)
    }

    @Test fun `asset actions are standard buttons and a status card instead of clickable text rows`() {
        val controls = assets()
        assertTrue(controls.contains("createTermsActionButton(getString(resource), filled, onClick)"))
        assertTrue(controls.contains("createModalStatusCard()"))
        assertTrue(controls.contains("createModalTip("))
        listOf("selfRippleBackground(", "isClickable", "setOnClickListener").forEach {
            assertFalse("clickable text row $it must not come back", controls.contains(it))
        }
        assertTrue("busy state disables through the shared helper", controls.contains("enable(download, !value.busy)"))
    }

    @Test fun `shared modal body controls use the skin card surface and the grey tip style`() {
        val tip = SettingsUiSource.code(SettingsUiSource.function("createModalTip"))
        assertTrue(tip.contains("R.color.colorTextGray"))
        val card = SettingsUiSource.code(SettingsUiSource.function("createModalStatusCard"))
        assertTrue(card.contains("skinCardBackground(monetColors.surfaceVariant, 14f)"))
    }
}
