package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.SettingsUiSource
import org.junit.Assert.*
import org.junit.Test

class FrameThermalControlContractTest {
    fun `the confirmed preference reaches both player processes with protection on by default`() {
        val entry = SourceContract.read("hook/HookEntry.kt").after("FrameInterpolationFeatureInstaller(")
            .before("featureInstallCoordinator.installAll(")
        assertTrue(entry.contains("disableThermalControl = prefs.getBoolean(FeaturePreferences.PLAYER_FRAME_INTERPOLATION_DISABLE_THERMAL_CONTROL, false)"))
        val installer = SourceContract.read("hook/feature/interpolation/FrameInterpolationFeatureInstaller.kt")
        assertTrue(installer.contains("private val disableThermalControl: Boolean = false"))
        assertTrue(installer.contains("override fun thermalControlEnabled(): Boolean = !disableThermalControl"))
        val bridge = SourceContract.read("hook/feature/interpolation/FrameCaptureBridge.kt")
        assertTrue(bridge.contains("fun thermalControlEnabled(): Boolean = true"))
        val temperature = bridge.after("// 温控：").before("session.tick(now)")
        assertTrue(temperature.contains("FrameThermalControl.pauseHeadroom(host.thermalControlEnabled()) { power?.thermalHeadroom(now) }"))
    }

    fun `a switch request rolls back before confirming and only confirmation enables bypass`() {
        val ui = SourceContract.read("ui/activity/PlayerFrameInterpolationPresenter.kt")
            .after("val thermalKey = FeaturePreferences.PLAYER_FRAME_INTERPOLATION_DISABLE_THERMAL_CONTROL")
            .before("val statusKey = FeaturePreferences.PLAYER_FRAME_INTERPOLATION_STATUS_TOAST")
        val listener = ui.after("setOnCheckedChangeListener { button, checked ->")
        assertTrue(listener.contains("applyModeledToggle(thermalKey, checked, button)"))
        val route = SettingsUiSource.function("requestSettingCommand")
        assertTrue(route.contains("descriptor.storageKey == FeaturePreferences.PLAYER_FRAME_INTERPOLATION_DISABLE_THERMAL_CONTROL"))
        assertTrue(route.contains("requestThermalControlSetting(id, requested, anchor)"))
        val command = SettingsUiSource.function("requestThermalControlSetting")
        val rollback = command.indexOf("(anchor as? MaterialSwitch)?.renderModelState(store.state(id))")
        val confirmation = command.indexOf("showFrameInterpolationThermalConfirmDialog")
        assertTrue("The animation anchor must show confirmed state before the dialog is created", rollback >= 0 && confirmation > rollback)
        assertTrue(command.contains("if (requested) showFrameInterpolationThermalConfirmDialog(anchor) { save(true) }"))
        assertTrue(command.contains("else save(false)"))
        val save = SettingsUiSource.functions(command, "save").single()
        assertTrue(save.contains("settingsState.persistBoolean("))
        assertTrue(save.contains("key = key"))
        assertTrue(save.contains("previous = previous"))
        assertTrue(save.contains("requested = value"))
        assertTrue(save.contains("verifyReadback = true"))
        assertTrue(save.contains(").confirmed"))
        val stateHolder = SourceContract.read("ui/activity/SettingsStateHolder.kt")
            .after("fun persistBoolean(").before("fun persistRegisteredBoolean(")
        assertTrue(stateHolder.contains("SettingWritePolicy.attemptBoolean("))
        val policy = SourceContract.read("ui/activity/SettingWritePolicy.kt")
        val attempt = policy.after("fun attempt(").before("fun <T> attemptValue(")
        assertTrue(attempt.contains("Outcome(requested, null)"))
        assertTrue(attempt.contains("Outcome(previous, failure)"))
        val attemptBool = policy.after("fun attemptBoolean(").before("fun attemptString(")
        assertTrue(attemptBool.contains("preferences.edit().putBoolean(key, requested)"))
        assertTrue(attemptBool.contains("check(preferences.getBoolean(key, !requested) == requested)"))
        assertFalse("No write may escape the confirmation's save command", command.replace(save, "").contains("putBoolean("))
        assertFalse("No persistBoolean may escape the confirmation's save command", command.replace(save, "").contains("persistBoolean("))
        val dialog = SourceContract.read("ui/activity/PlayerPickerDialogs.kt")
            .after("internal fun MainActivity.showFrameInterpolationThermalConfirmDialog(")
            .before("/** apply()")
        val cancel = dialog.after("R.string.dialog_cancel").before("R.string.player_frame_thermal_confirm_accept")
        assertFalse(cancel.contains("onConfirm()"))
        val confirm = dialog.after("R.string.player_frame_thermal_confirm_accept").before("container.addView(buttons)")
        assertTrue(confirm.contains("dismissWithAnimation(dialog, container) { onConfirm() }"))
        assertFalse(dialog.contains("putBoolean("))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FrameThermalControlContractTest) -> Unit) {
            val s = FrameThermalControlContractTest()
            block(s)
        }
        runIsolated { s -> s.`the confirmed preference reaches both player processes with protection on by default`() }
        runIsolated { s -> s.`a switch request rolls back before confirming and only confirmation enables bypass`() }
    }
}
