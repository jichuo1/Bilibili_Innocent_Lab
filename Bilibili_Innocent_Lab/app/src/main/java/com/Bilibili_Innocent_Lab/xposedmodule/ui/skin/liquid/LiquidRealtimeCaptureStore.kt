package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import android.annotation.SuppressLint
import android.content.Context

/** 全屏实时取样是本机界面效果，不进入 Hook 配置、设置备份或 NPatch 发布快照。 */
internal object LiquidRealtimeCaptureStore {
    private const val PREF_FILE = "ui_liquid_effect_preferences"
    private const val KEY_ENABLED = "fullscreen_realtime_capture_enabled"

    fun isEnabled(context: Context): Boolean = runCatching {
        preferences(context).getBoolean(KEY_ENABLED, false)
    }.getOrDefault(false)

    @SuppressLint("UseKtx") // 必须检查同步落盘与读回结果，切换后 Activity 会立即重建。
    fun setEnabled(context: Context, enabled: Boolean): Boolean = runCatching {
        val preferences = preferences(context)
        preferences.edit().putBoolean(KEY_ENABLED, enabled).commit() &&
            preferences.getBoolean(KEY_ENABLED, !enabled) == enabled
    }.getOrDefault(false)

    private fun preferences(context: Context) =
        (context.applicationContext ?: context).getSharedPreferences(
            PREF_FILE,
            Context.MODE_PRIVATE
        )
}
