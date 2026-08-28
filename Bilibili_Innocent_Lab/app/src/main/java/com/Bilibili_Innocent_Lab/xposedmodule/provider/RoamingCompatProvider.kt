package com.Bilibili_Innocent_Lab.xposedmodule.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import androidx.core.net.toUri
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.FreeCopyConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore

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
 * Provider 只读不写，目前暴露 Hook 授权、漫游开关、自由复制配置和模块注入 UI
 * 的语言标签；每条路径均复用同一 Binder caller UID 白名单。条款未授权时，除授权
 * 查询本身外均只返回安全关闭态，不会暴露可被宿主继续使用的派生配置。
 */
class RoamingCompatProvider : ContentProvider() {

    /** Provider 的 authority（与模块包名区分，避免与资源命名冲突） */
    companion object {
        const val AUTHORITY = "com.Bilibili_Innocent_Lab.xposedmodule.roaming"

        const val PATH_ENABLED = "roaming_compat_enabled"
        const val PATH_FREE_COPY_CONFIG = "free_copy_config"
        const val PATH_UI_LOCALE = InjectedUiLocale.PROVIDER_PATH
        const val PATH_HOOK_AUTHORIZATION = "hook_authorization"
        const val COLUMN_HOOK_AUTHORIZED = "authorized"

        val CONTENT_URI: Uri = "content://$AUTHORITY/$PATH_ENABLED".toUri()
        val FREE_COPY_CONFIG_URI: Uri = "content://$AUTHORITY/$PATH_FREE_COPY_CONFIG".toUri()
        val UI_LOCALE_URI: Uri = "content://$AUTHORITY/$PATH_UI_LOCALE".toUri()
        val HOOK_AUTHORIZATION_URI: Uri =
            "content://$AUTHORITY/$PATH_HOOK_AUTHORIZATION".toUri()
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
        val authorized = isHookAuthorized()
        if (uri.pathSegments == listOf(PATH_HOOK_AUTHORIZATION)) {
            return MatrixCursor(arrayOf(COLUMN_HOOK_AUTHORIZED)).apply {
                addRow(arrayOf(if (authorized) 1 else 0))
            }
        }
        if (uri.pathSegments == listOf(PATH_UI_LOCALE)) {
            val selectionTag = if (authorized) {
                context?.let(InjectedUiLocale::moduleSelectionForProvider)
                    ?: InjectedUiLocale.TAG_SYSTEM
            } else {
                InjectedUiLocale.TAG_SYSTEM
            }
            return MatrixCursor(arrayOf(InjectedUiLocale.PROVIDER_COLUMN)).apply {
                addRow(arrayOf(selectionTag))
            }
        }
        if (uri.pathSegments == listOf(PATH_FREE_COPY_CONFIG)) {
            val snapshot = if (authorized) context?.let(FreeCopyConfigStore::read) else null
            // 未授权时返回「valid + revision>0 + 两项 false」的确定关闭快照，
            // 防止旧宿主进程把它视为 UNKNOWN 后回落到自己的陈旧开启缓存。
            return MatrixCursor(
                arrayOf("valid", "comment_enabled", "description_enabled", "revision")
            ).apply {
                addRow(
                    arrayOf(
                        if (snapshot != null || !authorized) 1 else 0,
                        if (snapshot?.commentEnabled == true) 1 else 0,
                        if (snapshot?.descriptionEnabled == true) 1 else 0,
                        snapshot?.revision ?: if (authorized) 0L else 1L
                    )
                )
            }
        }
        if (uri.pathSegments != listOf(PATH_ENABLED)) return null
        // 读取模块 App 自身 prefs（与 MainActivity 开关写入的是同一份文件：
        // YukiHookAPI 默认 prefs 名 = 模块包名 + "_preferences"）。
        val prefsName = "${context?.packageName}_preferences"
        val p = context?.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val enabled = authorized &&
            (p?.getBoolean(HookEntry.PREF_ROAMING_COMPAT_ENABLED, false) ?: false)
        val cursor = MatrixCursor(arrayOf("value"))
        cursor.addRow(arrayOf(if (enabled) "1" else "0"))
        return cursor
    }

    override fun getType(uri: Uri): String? =
        if (uri.authority == AUTHORITY &&
            (
                uri.pathSegments == listOf(PATH_ENABLED) ||
                    uri.pathSegments == listOf(PATH_FREE_COPY_CONFIG) ||
                    uri.pathSegments == listOf(PATH_UI_LOCALE) ||
                    uri.pathSegments == listOf(PATH_HOOK_AUTHORIZATION)
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

    /** 条款状态只在模块 uid 内读取；任何异常均 fail-closed。 */
    private fun isHookAuthorized(): Boolean = context?.let { providerContext ->
        runCatching {
            UserTermsConsentStore.readOrInitialize(providerContext).isAuthorized
        }.getOrDefault(false)
    } ?: false
}
