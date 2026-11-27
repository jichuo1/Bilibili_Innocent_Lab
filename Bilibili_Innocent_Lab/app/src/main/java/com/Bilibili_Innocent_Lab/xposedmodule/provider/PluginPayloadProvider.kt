package com.Bilibili_Innocent_Lab.xposedmodule.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.net.toUri
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.container.PluginIndexCodec
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.container.PluginIndexDocument
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.container.PluginRepository
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostCallerIdentityVerifier
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.bilab.core.settings.PluginSettingKeySpace
import com.bilab.plugin.api.model.PluginManifest
import org.json.JSONObject
import java.io.FileNotFoundException

/**
 * 跨进程只读 `.bilp` 插件索引与包载荷交付通道。
 *
 * 仅向模块自身与目标宿主 (`tv.danmaku.bili`) 暴露经过 [UserTermsConsentStore] 条款授权、
 * 已验签/已启用且非隔离状态的 `.bilp` 插件索引及只读文件描述符；所有写入操作一律拒绝。
 */
class PluginPayloadProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.plugin.payload"
        const val PATH_INDEX = "plugin_index"
        const val PATH_PACKAGE = "plugin_package"
        const val PATH_SETTINGS = "plugin_settings"
        const val PARAM_PLUGIN_ID = "id"

        const val COLUMN_INDEX_JSON = "index_json"
        const val COLUMN_INDEX_DIGEST = "index_digest"
        const val COLUMN_REVISION = "revision"
        const val COLUMN_SETTINGS_JSON = "settings_json"
        const val SETTINGS_PREFS_NAME = "plugin_settings"

        val INDEX_URI: Uri = "content://$AUTHORITY/$PATH_INDEX".toUri()
        val PACKAGE_URI: Uri = "content://$AUTHORITY/$PATH_PACKAGE".toUri()
        val SETTINGS_URI: Uri = "content://$AUTHORITY/$PATH_SETTINGS".toUri()
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
        val pathSegments = uri.pathSegments
        val isIndexQuery = pathSegments == listOf(PATH_INDEX)
        val isSettingsQuery = pathSegments == listOf(PATH_SETTINGS)
        if (!isIndexQuery && !isSettingsQuery) return null

        val providerContext = context ?: return null
        val authorized = isHookAuthorized()

        if (isSettingsQuery) {
            val rawPluginId = uri.getQueryParameter(PARAM_PLUGIN_ID)
            val validRequestedId = rawPluginId
                ?.takeIf { PluginManifest.ID_REGEX.matches(it) && !it.contains("..") }
            val json = if (authorized && (rawPluginId == null || validRequestedId != null)) {
                buildSettingsSnapshotJson(providerContext, validRequestedId)
            } else {
                "{}"
            }
            return MatrixCursor(arrayOf(COLUMN_SETTINGS_JSON)).apply {
                addRow(arrayOf<Any?>(json))
            }
        }

        val doc = if (authorized) {
            runCatching { PluginRepository.fromContext(providerContext).exportIndexDocument() }
                .getOrElse { PluginIndexDocument() }
        } else {
            PluginIndexDocument()
        }
        val json = PluginIndexCodec.encodeJson(doc)
        val digest = if (authorized) PluginIndexCodec.expectedRemoteDigest(doc) else ""
        return MatrixCursor(
            arrayOf(COLUMN_INDEX_JSON, COLUMN_INDEX_DIGEST, COLUMN_REVISION)
        ).apply {
            addRow(arrayOf<Any?>(json, digest, doc.revision))
        }
    }

    override fun getType(uri: Uri): String? = when {
        uri.authority != AUTHORITY -> null
        uri.pathSegments == listOf(PATH_INDEX) -> "application/json"
        uri.pathSegments == listOf(PATH_SETTINGS) -> "application/json"
        uri.pathSegments == listOf(PATH_PACKAGE) -> "application/octet-stream"
        else -> null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (uri.authority != AUTHORITY || uri.pathSegments != listOf(PATH_PACKAGE)) return null
        enforceTrustedCaller()
        if (mode != "r") throw SecurityException("Plugin packages are read-only")
        if (!isHookAuthorized()) throw FileNotFoundException("Plugin delivery is not authorized")
        val pluginId = uri.getQueryParameter(PARAM_PLUGIN_ID)
            ?.takeIf { PluginManifest.ID_REGEX.matches(it) && !it.contains("..") }
            ?: return null
        val providerContext = context ?: return null
        val repo = PluginRepository.fromContext(providerContext)
        val packageFile = repo.verifiedPackageFile(pluginId) ?: return null
        val canonicalPackagesDir = repo.packagesDirectory().canonicalFile.toPath()
        val canonicalFile = packageFile.canonicalFile.toPath()
        if (canonicalFile.parent != canonicalPackagesDir) {
            throw SecurityException("Plugin package path escaped repository directory")
        }
        return ParcelFileDescriptor.open(packageFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("PluginPayloadProvider is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("PluginPayloadProvider is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("PluginPayloadProvider is read-only")

    private fun enforceTrustedCaller() {
        val callerUid = Binder.getCallingUid()
        if (
            callerUid == Process.myUid() ||
            callerUid == Process.ROOT_UID ||
            callerUid == Process.SYSTEM_UID ||
            callerUid == Process.SHELL_UID
        ) return
        val providerContext = context
            ?: throw SecurityException("Caller uid $callerUid is not allowed to query $AUTHORITY")
        val verification = HostCallerIdentityVerifier.verifyProviderCaller(
            context = providerContext,
            callerUid = callerUid,
            expectedPackage = HookEntry.TARGET_PACKAGE,
            allowSelf = true,
            allowPrivilegedShell = true,
            allowPackageListFallbackWhenPrimaryMissing = true,
            auditTag = "PluginPayloadProvider"
        )
        if (verification.allowed) return
        throw SecurityException(
            "Caller uid $callerUid is not allowed to query $AUTHORITY (${verification.reason})"
        )
    }

    private fun buildSettingsSnapshotJson(
        providerContext: android.content.Context,
        pluginIdFilter: String?
    ): String = runCatching {
        val prefs = providerContext.getSharedPreferences(
            SETTINGS_PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        )
        val json = JSONObject()
        for ((key, value) in prefs.all) {
            if (!PluginSettingKeySpace.isPluginKey(key)) continue
            if (pluginIdFilter != null && !PluginSettingKeySpace.belongsToPlugin(key, pluginIdFilter)) {
                continue
            }
            when (value) {
                is Boolean, is Int, is String -> json.put(key, value)
            }
        }
        json.toString()
    }.getOrDefault("{}")

    private fun isHookAuthorized(): Boolean = context?.let { providerContext ->
        runCatching {
            UserTermsConsentStore.readOrInitialize(providerContext).isAuthorized
        }.getOrDefault(false)
    } ?: false
}
