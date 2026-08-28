package com.Bilibili_Innocent_Lab.xposedmodule.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.FreeCopyConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale

/**
 * 漫游版本支持扩展的跨进程开关读取入口。
 *
 * 为什么需要它：LSPosed 的 XSharedPreferences 在部分构建（如本机使用的
 * DirectAccessService 实现）中，目标进程（B 站）读取模块 App 自身
 * shared_prefs 会被 SELinux 拦截，导致 YukiHookAPI 的 prefs() 永远返回默认值，
 * 开关形同虚设。ContentProvider 走 Binder + PackageManager，不受文件系统
 * SELinux 限制：B 站进程查询本 Provider 时，系统会自动拉起模块 App 进程
 * （模块 App 用自己的 uid 读自己的 prefs，天然可读），跨进程可靠。
 *
 * Provider 只读不写，目前暴露漫游开关、自由复制配置和模块注入 UI 的语言标签；
 * 每条路径均复用同一 Binder caller UID 白名单。
 */
class RoamingCompatProvider : ContentProvider() {

    /** Provider 的 authority（与模块包名区分，避免与资源命名冲突） */
    companion object {
        const val AUTHORITY = "com.Bilibili_Innocent_Lab.xposedmodule.roaming"

        const val PATH_ENABLED = "roaming_compat_enabled"
        const val PATH_FREE_COPY_CONFIG = "free_copy_config"
        const val PATH_UI_LOCALE = InjectedUiLocale.PROVIDER_PATH

        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_ENABLED")
        val FREE_COPY_CONFIG_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_FREE_COPY_CONFIG")
        val UI_LOCALE_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_UI_LOCALE")
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        if (uri.authority != AUTHORITY) return null
        enforceTrustedCaller()
        if (uri.pathSegments == listOf(PATH_UI_LOCALE)) {
            val selectionTag = context?.let(InjectedUiLocale::moduleSelectionForProvider)
                ?: InjectedUiLocale.TAG_SYSTEM
            return MatrixCursor(arrayOf(InjectedUiLocale.PROVIDER_COLUMN)).apply {
                addRow(arrayOf(selectionTag))
            }
        }
        if (uri.pathSegments == listOf(PATH_FREE_COPY_CONFIG)) {
            val snapshot = context?.let(FreeCopyConfigStore::read)
            return MatrixCursor(
                arrayOf("valid", "comment_enabled", "description_enabled", "revision")
            ).apply {
                addRow(
                    arrayOf(
                        if (snapshot != null) 1 else 0,
                        if (snapshot?.commentEnabled == true) 1 else 0,
                        if (snapshot?.descriptionEnabled == true) 1 else 0,
                        snapshot?.revision ?: 0L
                    )
                )
            }
        }
        if (uri.pathSegments != listOf(PATH_ENABLED)) return null
        // 读取模块 App 自身 prefs（与 MainActivity 开关写入的是同一份文件：
        // YukiHookAPI 默认 prefs 名 = 模块包名 + "_preferences"）。
        val prefsName = "${context?.packageName}_preferences"
        val p = context?.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val enabled = p?.getBoolean(HookEntry.PREF_ROAMING_COMPAT_ENABLED, false) ?: false
        val cursor = MatrixCursor(arrayOf("value"))
        cursor.addRow(arrayOf(if (enabled) "1" else "0"))
        return cursor
    }

    override fun getType(uri: Uri): String? =
        if (uri.authority == AUTHORITY &&
            (
                uri.pathSegments == listOf(PATH_ENABLED) ||
                    uri.pathSegments == listOf(PATH_FREE_COPY_CONFIG) ||
                    uri.pathSegments == listOf(PATH_UI_LOCALE)
                )
        ) "text/plain" else null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    /**
     * Provider 必须导出给 B 站进程作为 prefs 降级通道，但不应向任意第三方应用暴露
     * 功能开关或允许其反复冷启动模块进程。Shell 保留用于 ADB 诊断。
     */
    private fun enforceTrustedCaller() {
        val callerUid = Binder.getCallingUid()
        if (
            callerUid == Process.myUid() ||
            callerUid == Process.ROOT_UID ||
            callerUid == Process.SYSTEM_UID ||
            callerUid == Process.SHELL_UID
        ) return
        val callerPackages = context?.packageManager?.getPackagesForUid(callerUid).orEmpty()
        if (callerPackages.any { it == HookEntry.TARGET_PACKAGE }) return
        throw SecurityException("Caller uid $callerUid is not allowed to query $AUTHORITY")
    }
}
