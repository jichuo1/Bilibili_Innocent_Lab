package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.widget.CompoundButton
import android.view.View
import android.util.Log
import androidx.core.content.edit
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.ModuleUiSettings
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch

internal fun MainActivity.applyModeledToggle(key: String, requested: Boolean, button: CompoundButton) {
    val store = settingStore ?: return
    val descriptor = store.descriptors.singleOrNull { it.storageKey == key } ?: return
    try {
        requestSettingCommand(descriptor.id, requested, button)
    } finally {
        (button as? MaterialSwitch)?.renderModelState(store.state(descriptor.id))
        refreshSettingAvailability()
    }
}

internal fun MainActivity.requestFavoriteSetting(id: String, requested: Boolean, anchor: View) {
    val descriptor = settingStore?.descriptor(id) ?: return
    if (!descriptor.directToggle) return
    requestSettingCommand(id, requested, anchor)
}

/** The anchor belongs to this user command; neither the store nor the descriptor retains it. */
internal fun MainActivity.requestSettingCommand(id: String, requested: Boolean, anchor: View) {
    val store = settingStore ?: return
    val descriptor = store.descriptor(id) ?: return
    if (!store.state(id).enabled) return
    if (descriptor.storageKey == FeaturePreferences.PLAYER_FRAME_INTERPOLATION_DISABLE_THERMAL_CONTROL) {
        requestThermalControlSetting(id, requested, anchor)
        return
    }
    store.request(id, requested) {
        when (descriptor.storageKey) {
            HookEntry.PREF_FREE_COPY_LIGHT_MODE -> {
                requestManualLightSetting(id, requested, anchor)
                store.state(id).checked
            }
            else -> {
                check(descriptor.storageKey in modeledToggleKeys) { "No business command for ${descriptor.id}" }
                synchronizeSettingField(descriptor.storageKey, store.state(id).checked)
                executeModeledToggle(descriptor.storageKey, requested)
            }
        }
    }
    refreshSettingAvailability()
}

private fun MainActivity.requestThermalControlSetting(id: String, requested: Boolean, anchor: View) {
    val store = settingStore ?: return
    (anchor as? MaterialSwitch)?.renderModelState(store.state(id))
    val key = FeaturePreferences.PLAYER_FRAME_INTERPOLATION_DISABLE_THERMAL_CONTROL
    fun save(value: Boolean) {
        if (isFinishing || isDestroyed) return
        store.request(id, value) {
            val previous = store.state(id).checked
            val saved = runCatching {
                val preferences = prefs()
                preferences.edit { putBoolean(key, value) }
                check(preferences.getBoolean(key, !value) == value)
            }.onFailure { Log.e("BilibiliInnocentLab", "write frame thermal control prefs failed", it) }.isSuccess
            if (saved) value else previous
        }
    }
    if (requested) showFrameInterpolationThermalConfirmDialog(anchor) { save(true) }
    else save(false)
}

private fun MainActivity.requestManualLightSetting(id: String, requested: Boolean, anchor: View) {
    val store = settingStore ?: return
    if (programmaticSwitch || autoLightConfirmInProgress) return
    if (freeCopyAutoLight && !autoLightConfirmInProgress) {
        autoLightConfirmInProgress = true
        showAutoLightConfirmDialog(
            anchor = anchor,
            onConfirm = confirm@{
                autoLightConfirmInProgress = false
                if (isFinishing || isDestroyed) return@confirm
                freeCopyAutoLight = false
                runCatching { prefs().edit { putBoolean(HookEntry.PREF_FREE_COPY_AUTO_LIGHT, false) } }
                    .onFailure { Log.e("BilibiliInnocentLab", "write free copy auto light prefs failed", it) }
                store.descriptors.singleOrNull { it.storageKey == HookEntry.PREF_FREE_COPY_AUTO_LIGHT }
                    ?.let { store.checked(it.id, false) }
                onFreeCopyLightModeChanged(requested)
                store.checked(id, freeCopyLightMode)
                animateLightModeTip()
            },
            onCancel = {
                autoLightConfirmInProgress = false
                store.checked(id, freeCopyLightMode)
            },
        )
        return
    }
    onFreeCopyLightModeChanged(requested)
    store.checked(id, freeCopyLightMode)
}

internal fun MainActivity.refreshSettingAvailability() {
    val store = settingStore ?: return
    val snapshot = ModuleUiSettings.read(runCatching { prefs() }.getOrNull())
    fun modelBoolean(key: String): Boolean = store.descriptors.singleOrNull { it.storageKey == key && it.favoriteEligible }
        ?.let { store.state(it.id).checked } ?: snapshot.bool(key)
    store.descriptors.filter { it.favoriteEligible }.forEach { descriptor ->
        val old = store.state(descriptor.id)
        val available = when (descriptor.storageKey) {
            FeaturePreferences.HOST_BOTTOM_BAR_COMPACT, FeaturePreferences.HOST_BOTTOM_BAR_ICON_ONLY ->
                modelBoolean(FeaturePreferences.HOST_BOTTOM_BAR_LIQUID_GLASS)
            HookEntry.PREF_FREE_COPY_AUTO_LIGHT, HookEntry.PREF_FREE_COPY_LIGHT_MODE ->
                modelBoolean(HookEntry.PREF_FREE_COPY_ENABLED) || modelBoolean(HookEntry.PREF_FREE_COPY_DESC_ENABLED) ||
                    modelBoolean(FeaturePreferences.FREE_COPY_TITLE_ENABLED)
            else -> if (descriptor.id in SettingsDependencies.requirements)
                SettingsDependencies.available(descriptor.id, snapshot) else old.enabled
        }
        store.confirm(descriptor.id, old.copy(enabled = available))
    }
}
