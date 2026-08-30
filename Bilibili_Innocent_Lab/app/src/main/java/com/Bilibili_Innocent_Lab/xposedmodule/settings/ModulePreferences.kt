package com.Bilibili_Innocent_Lab.xposedmodule.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * 模块 UI 的私有权威设置。
 *
 * 文件名沿用历史 YukiHookAPI 默认值，迁移到 Modern Xposed API 时不会丢失现有设置。
 */
internal fun Context.modulePreferences(): SharedPreferences {
    val appContext = applicationContext ?: this
    return appContext.getSharedPreferences(
        "${appContext.packageName}_preferences",
        Context.MODE_PRIVATE
    )
}

/** 迁移期保持原有调用形状，来源已是标准 Android 私有偏好。 */
internal fun Context.prefs(): SharedPreferences = modulePreferences()
