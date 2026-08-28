package com.Bilibili_Innocent_Lab.xposedmodule.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import androidx.core.net.toUri
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.FreeCopyConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore
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
 * Provider 主要是只读通道，目前暴露 Hook 授权、漫游开关、自由复制配置、模块注入 UI
 * 的语言标签与免 Root 完整快照；只额外接受一个受限的免 Root 宿主心跳 call。
 * 每条路径均复用同一 Binder caller UID 白名单。条款未授权时，除授权查询本身外均
 * 只返回安全关闭态，不会暴露可被宿主继续使用的派生配置。
 */
class RoamingCompatProvider : ContentProvider() {

    /** Provider 的 authority（与模块包名区分，避免与资源命名冲突） */
    companion object {
        const val AUTHORITY = "com.Bilibili_Innocent_Lab.xposedmodule.roaming"

        const val PATH_ENABLED = "roaming_compat_enabled"
        const val PATH_FREE_COPY_CONFIG = "free_copy_config"
        const val PATH_UI_LOCALE = InjectedUiLocale.PROVIDER_PATH
        const val PATH_HOOK_AUTHORIZATION = "hook_authorization"
        const val PATH_NO_ROOT_CONFIG = "no_root_config"
        const val COLUMN_HOOK_AUTHORIZED = "authorized"
        const val COLUMN_NO_ROOT_VALID = "valid"
        const val COLUMN_NO_ROOT_ENABLED = "enabled"
        const val COLUMN_NO_ROOT_REVISION = "revision"
        const val COLUMN_NO_ROOT_PAYLOAD = "payload"

        const val METHOD_REPORT_NO_ROOT_HEARTBEAT = "report_no_root_heartbeat"
        const val EXTRA_NO_ROOT_REVISION = "revision"
        const val EXTRA_NO_ROOT_MODULE_VERSION = "module_version"
        const val EXTRA_NO_ROOT_TARGET_VERSION = "target_version"
        const val EXTRA_NO_ROOT_TARGET_UPDATE_TIME = "target_update_time"
        const val EXTRA_NO_ROOT_TARGET_PACKAGE = "target_package"
        const val EXTRA_NO_ROOT_PROCESS = "process"
        const val RESULT_NO_ROOT_ACCEPTED = "accepted"

        val CONTENT_URI: Uri = "content://$AUTHORITY/$PATH_ENABLED".toUri()
        val FREE_COPY_CONFIG_URI: Uri = "content://$AUTHORITY/$PATH_FREE_COPY_CONFIG".toUri()
        val UI_LOCALE_URI: Uri = "content://$AUTHORITY/$PATH_UI_LOCALE".toUri()
        val HOOK_AUTHORIZATION_URI: Uri =
            "content://$AUTHORITY/$PATH_HOOK_AUTHORIZATION".toUri()
        val NO_ROOT_CONFIG_URI: Uri = "content://$AUTHORITY/$PATH_NO_ROOT_CONFIG".toUri()
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
            return if (projection?.contains(COLUMN_NO_ROOT_PAYLOAD) == true) {
                hookBootstrapCursor(authorized)
            } else {
                MatrixCursor(arrayOf(COLUMN_HOOK_AUTHORIZED)).apply {
                    addRow(arrayOf(if (authorized) 1 else 0))
                }
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
        if (uri.pathSegments == listOf(PATH_NO_ROOT_CONFIG)) {
            return noRootConfigCursor(authorized)
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
                    uri.pathSegments == listOf(PATH_HOOK_AUTHORIZATION) ||
                    uri.pathSegments == listOf(PATH_NO_ROOT_CONFIG)
                )
        ) "text/plain" else null

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_REPORT_NO_ROOT_HEARTBEAT) return super.call(method, arg, extras)
        enforceTrustedCaller()
        val accepted = if (isHookAuthorized()) {
            recordNoRootHeartbeat(extras)
        } else {
            false
        }
        return Bundle().apply { putBoolean(RESULT_NO_ROOT_ACCEPTED, accepted) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    /**
     * 免 Root 目标进程必须获得一份完整、可验证的配置快照：
     * - 未授权或用户关闭时返回明确关闭态，促使宿主清掉旧缓存；
     * - 用户已开启但快照缺失/损坏时返回 enabled + invalid，禁止宿主用默认值安装 Hook；
     * - 只有完整快照才序列化为 payload。
     */
    private fun noRootConfigCursor(authorized: Boolean): Cursor {
        val exported = context?.let { NoRootSupportStore.exportState(it, authorized) }
            ?: NoRootSupportStore.ExportState(false, false, 0L, null)
        return MatrixCursor(
            arrayOf(
                COLUMN_NO_ROOT_VALID,
                COLUMN_NO_ROOT_ENABLED,
                COLUMN_NO_ROOT_REVISION,
                COLUMN_NO_ROOT_PAYLOAD
            )
        ).apply {
            addRow(
                arrayOf<Any?>(
                    if (exported.valid) 1 else 0,
                    if (exported.enabled) 1 else 0,
                    exported.revision,
                    exported.payload
                )
            )
        }
    }

    /** 旧调用仍可只读取 authorized；新启动握手一次取得授权和免 Root envelope。 */
    private fun hookBootstrapCursor(authorized: Boolean): Cursor {
        val exported = context?.let { NoRootSupportStore.exportState(it, authorized) }
            ?: NoRootSupportStore.ExportState(false, false, 0L, null)
        return MatrixCursor(
            arrayOf(
                COLUMN_HOOK_AUTHORIZED,
                COLUMN_NO_ROOT_VALID,
                COLUMN_NO_ROOT_ENABLED,
                COLUMN_NO_ROOT_REVISION,
                COLUMN_NO_ROOT_PAYLOAD
            )
        ).apply {
            addRow(
                arrayOf<Any?>(
                    if (authorized) 1 else 0,
                    if (exported.valid) 1 else 0,
                    if (exported.enabled) 1 else 0,
                    exported.revision,
                    exported.payload
                )
            )
        }
    }

    /** 只接受当前 B 站宿主进程、当前快照 revision 的冷启动回执。 */
    private fun recordNoRootHeartbeat(extras: Bundle?): Boolean {
        val providerContext = context ?: return false
        if (!NoRootSupportStore.isDesiredEnabled(providerContext) || extras == null) return false
        val targetPackage = extras.getString(EXTRA_NO_ROOT_TARGET_PACKAGE).orEmpty()
        val processName = extras.getString(EXTRA_NO_ROOT_PROCESS).orEmpty()
        if (targetPackage != HookEntry.TARGET_PACKAGE) return false
        if (processName != targetPackage && !processName.startsWith("$targetPackage:")) return false
        return NoRootSupportStore.recordHeartbeat(
            context = providerContext,
            revision = extras.getLong(EXTRA_NO_ROOT_REVISION, 0L),
            moduleVersionCode = extras.getLong(EXTRA_NO_ROOT_MODULE_VERSION, 0L),
            targetVersionCode = extras.getLong(EXTRA_NO_ROOT_TARGET_VERSION, 0L),
            targetUpdateTime = extras.getLong(EXTRA_NO_ROOT_TARGET_UPDATE_TIME, 0L),
            targetPackage = targetPackage
        )
    }

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
