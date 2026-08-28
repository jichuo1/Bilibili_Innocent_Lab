package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.app.LocaleManager
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion
import com.highcapable.kavaref.extension.classOf

/**
 * 模块应用语言选择的派生镜像。
 *
 * AppCompat/系统应用语言始终是权威来源；该文件只解决 API 27-32 中 Provider 冷启动早于
 * AppCompatActivity、因而尚不能可靠恢复 AppCompat 内部语言状态的问题。镜像只保存受支持的
 * BCP-47 标签，不保存 Context，也不参与宿主 Hook 热路径。
 */
internal object InjectedUiLocaleStore {

    private const val PREF_FILE = "innocent_lab_injected_ui_locale"
    private const val PREF_SELECTION_TAG = "selection_tag"

    /** 缺失或损坏时回退跟随系统；镜像从不把未知语言扩散到宿主进程。 */
    fun read(context: Context): String {
        val rawTag = runCatching {
            context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .getString(PREF_SELECTION_TAG, null)
        }.getOrNull()
        return InjectedUiLocale.normalizeSelectionTag(rawTag)
    }

    /** 由模块设置页在 AppCompat 语言状态确定后调用。 */
    fun write(context: Context, selectionTag: String?): String {
        val normalized = InjectedUiLocale.normalizeSelectionTag(selectionTag)
        runCatching {
            context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit {
                putString(PREF_SELECTION_TAG, normalized)
            }
        }
        return normalized
    }

    /**
     * 从权威语言状态刷新镜像。Android 13+ 直接读取系统 LocaleManager；旧系统只应在
     * AppCompatActivity 已恢复语言状态后调用。
     */
    fun syncFromAppCompat(context: Context): String {
        val explicitTag = if (AndroidVersion.isAtLeast(AndroidVersion.T)) {
            runCatching {
                context.getSystemService(classOf<LocaleManager>())
                    ?.applicationLocales
                    ?.takeUnless { it.isEmpty }
                    ?.get(0)
                    ?.toLanguageTag()
            }.getOrNull()
        } else {
            runCatching {
                AppCompatDelegate.getApplicationLocales()
                    .toLanguageTags()
                    .substringBefore(',')
                    .takeIf(String::isNotBlank)
            }.getOrNull()
        }
        return write(context, explicitTag ?: InjectedUiLocale.TAG_SYSTEM)
    }

    /** Provider 冷启动读取：API 33+ 可直接取系统状态，API 27-32 使用已派生镜像。 */
    fun readForProvider(context: Context): String =
        if (AndroidVersion.isAtLeast(AndroidVersion.T)) {
            syncFromAppCompat(context)
        } else {
            read(context)
        }
}
