package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup

/** 只覆盖宿主主题读取，不写主题偏好；换回其他预设后冷启动即恢复宿主原选择。 */
internal object HostBackgroundTheme {
    fun install(environment: HookEnvironment, preset: HostBackgroundPreset): Int {
        if (!preset.isCelestial) return 0
        if (environment.processName != "tv.danmaku.bili" && !environment.processName.startsWith("tv.danmaku.bili:")) return 0
        val loader = environment.classLoader ?: return 0
        return runCatching {
            val theme = KavaMemberLookup.classOrNull(loader, "com.bilibili.lib.ui.util.MultipleThemeUtils")
                ?: error("Missing host theme utility")
            val current = KavaMemberLookup.methodOrNull(theme, "getCurrentThemeId", Context::class.java)
                ?: error("Missing host theme selection")
            val follow = KavaMemberLookup.methodOrNull(theme, "isNightFollowSystem", Context::class.java)
                ?: error("Missing host system-theme policy")
            val night = KavaMemberLookup.fieldOrNull(theme, "NIGHT_THEME_ID")?.getInt(null)
                ?: error("Missing host night theme id")
            environment.registrar.exact("host_background.theme", current.declaringClass, current.name, *current.parameterTypes) {
                replaceResultOnSuccess(night)
            }
            environment.registrar.exact("host_background.theme_follow_system", follow.declaringClass, follow.name, *follow.parameterTypes) {
                replaceResultOnSuccess(false)
            }
            environment.logInfo("host_background_night_theme", "[BIL] 夜空背景自动使用宿主深色主题，原主题偏好保留")
            2
        }.getOrElse {
            environment.logError("host_background_night_theme_error", "[BIL] 夜空背景深色主题安装失败: $it")
            0
        }
    }
}
