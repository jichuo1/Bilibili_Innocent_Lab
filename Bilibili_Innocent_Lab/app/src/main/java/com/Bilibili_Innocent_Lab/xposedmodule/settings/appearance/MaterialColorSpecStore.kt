package com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance

import android.annotation.SuppressLint
import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences

/** 模块界面允许选择的 Material 动态颜色规范；未知值始终回退到兼容默认值。 */
internal enum class MaterialColorSpec(val storageValue: String) {
    SPEC_2021("2021"),
    SPEC_2025("2025");

    companion object {
        val DEFAULT = SPEC_2021

        fun fromStorageValue(value: String?): MaterialColorSpec =
            entries.firstOrNull { it.storageValue == value } ?: DEFAULT
    }
}

/**
 * Material 配色规范属于可备份的模块界面设置，和其他用户意图共用模块权威偏好。
 * 同步写入并读回后才能重建 Activity，避免界面显示与实际规范不一致。
 */
internal object MaterialColorSpecStore {
    const val PREF_KEY = "material_color_spec"

    fun read(context: Context): MaterialColorSpec = runCatching {
        MaterialColorSpec.fromStorageValue(
            context.modulePreferences().getString(
                PREF_KEY,
                MaterialColorSpec.DEFAULT.storageValue
            )
        )
    }.getOrDefault(MaterialColorSpec.DEFAULT)

    @SuppressLint("UseKtx") // 必须检查 commit() 返回值并立即读回。
    fun write(context: Context, spec: MaterialColorSpec): Boolean = runCatching {
        val preferences = context.modulePreferences()
        preferences.edit().putString(PREF_KEY, spec.storageValue).commit() &&
            preferences.getString(PREF_KEY, null) == spec.storageValue
    }.getOrDefault(false)
}
