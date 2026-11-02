package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import androidx.core.content.edit
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.ModuleSettingsStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import io.github.jichuo1.meguviz.android.extensions.versionCodeCompat
import java.io.File
import kotlin.math.max

/** 模块 App 私有的免 Root 用户意图、同步状态与宿主回执。 */
internal object NoRootSupportStore {
    internal const val PREF_FILE = "innocent_lab_no_root_support"
    private const val SNAPSHOT_FILE = "no_root_hook_config.json"

    private const val KEY_DESIRED_ENABLED = "desired_enabled"
    private const val KEY_ADAPTER_RESET_REVISION = "adapter_reset_revision"
    private const val KEY_SYNC_STATE = "sync_state"
    private const val KEY_SYNC_DETAIL = "sync_detail"
    private const val KEY_SYNC_REVISION = "sync_revision"
    private const val KEY_REMOTE_SYNCED_REVISION = "remote_synced_revision"
    private const val KEY_HEARTBEAT_REVISION = "heartbeat_revision"
    private const val KEY_HEARTBEAT_MODULE_VERSION = "heartbeat_module_version"
    private const val KEY_HEARTBEAT_TARGET_VERSION = "heartbeat_target_version"
    private const val KEY_HEARTBEAT_TARGET_UPDATE_TIME = "heartbeat_target_update_time"
    private const val KEY_HEARTBEAT_TARGET_PACKAGE = "heartbeat_target_package"
    private const val KEY_HEARTBEAT_RECEIVED_AT = "heartbeat_received_at"
    private const val KEY_RUNTIME_REPLY_RECEIVED_AT = "runtime_reply_received_at"
    private const val KEY_DISABLE_WAS_ACTIVE = "disable_was_active"

    enum class SyncState {
        DISABLED,
        CHECKING,
        MANAGER_MISSING,
        MODULE_NOT_REGISTERED,
        SYNCING,
        RESTART_REQUIRED,
        DISABLE_RESTART_REQUIRED,
        ACTIVE,
        CONNECTION_TIMEOUT,
        ERROR
    }

    data class Status(
        val desiredEnabled: Boolean,
        val syncState: SyncState,
        val detail: String?,
        val syncRevision: Long,
        val heartbeatRevision: Long,
        val heartbeatModuleVersion: Long,
        val heartbeatTargetVersion: Long,
        val heartbeatTargetUpdateTime: Long,
        val heartbeatTargetPackage: String?,
        val heartbeatReceivedAt: Long,
        val disableWasActive: Boolean
    )

    private val ioLock = Any()

    data class ExportState(
        val valid: Boolean,
        val enabled: Boolean,
        val revision: Long,
        val payload: String?
    )

    /** 保留 NPatch 用户偏好，但它不能接管当前 LSPatch 的发布、准入与重启。 */
    fun isDesiredEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DESIRED_ENABLED, false) && !LspatchRuntimeProfiles.usesDirectConfig(context)

    @SuppressLint("UseKtx") // 必须取得 commit 结果，KTX edit(Unit) 无法表达写入失败。
    fun setDesiredEnabled(context: Context, enabled: Boolean): Boolean = synchronized(ioLock) {
        prefs(context).edit()
            .putBoolean(KEY_DESIRED_ENABLED, enabled)
            .remove(KEY_DISABLE_WAS_ACTIVE)
            .commit()
    }

    /**
     * 设置后端迁移只恢复用户是否启用的意图；旧 CE 文件中的同步代次、心跳、错误状态和
     * 适配器重置标记都属于另一存储世代，必须一次提交中清除，避免“复活”历史运行态。
     */
    @SuppressLint("UseKtx")
    fun restoreDesiredForMigration(context: Context, enabled: Boolean): Boolean =
        synchronized(ioLock) {
            prefs(context).edit()
                .putBoolean(KEY_DESIRED_ENABLED, enabled)
                .remove(KEY_ADAPTER_RESET_REVISION)
                .remove(KEY_SYNC_STATE)
                .remove(KEY_SYNC_DETAIL)
                .remove(KEY_SYNC_REVISION)
                .remove(KEY_REMOTE_SYNCED_REVISION)
                .remove(KEY_HEARTBEAT_REVISION)
                .remove(KEY_HEARTBEAT_MODULE_VERSION)
                .remove(KEY_HEARTBEAT_TARGET_VERSION)
                .remove(KEY_HEARTBEAT_TARGET_UPDATE_TIME)
                .remove(KEY_HEARTBEAT_TARGET_PACKAGE)
                .remove(KEY_HEARTBEAT_RECEIVED_AT)
                .remove(KEY_RUNTIME_REPLY_RECEIVED_AT)
                .remove(KEY_DISABLE_WAS_ACTIVE)
                .commit()
        }

    /**
     * 关闭必须先持久化更高 revision 的 tombstone，再提交 UI 意图和清理旧 heartbeat。
     * 任一步失败都会保守返回 false；目标进程绝不会凭旧 enabled 快照继续安装 Hook。
     */
    @SuppressLint("UseKtx")
    fun disable(context: Context): Boolean = synchronized(ioLock) {
        val wasDesiredEnabled = isDesiredEnabled(context)
        val previous = readSnapshotLocked(context)
        @Suppress("DEPRECATION")
        val targetInfo = runCatching {
            context.packageManager.getPackageInfo(NoRootSupportState.TARGET_PACKAGE, 0)
        }.getOrNull()
        val currentPrefs = prefs(context)
        val wasVerifiedActive = previous?.enabled == true &&
            currentPrefs.getLong(KEY_HEARTBEAT_REVISION, 0L) == previous.revision &&
            currentPrefs.getLong(KEY_HEARTBEAT_MODULE_VERSION, 0L) ==
            previous.moduleVersionCode &&
            currentPrefs.getString(KEY_HEARTBEAT_TARGET_PACKAGE, null) ==
            NoRootSupportState.TARGET_PACKAGE &&
            currentPrefs.getLong(KEY_HEARTBEAT_RECEIVED_AT, 0L) > 0L &&
            targetInfo != null &&
            currentPrefs.getLong(KEY_HEARTBEAT_TARGET_VERSION, 0L) ==
            targetInfo.versionCodeCompat &&
            currentPrefs.getLong(KEY_HEARTBEAT_TARGET_UPDATE_TIME, 0L) ==
            targetInfo.lastUpdateTime
        val tombstone = NoRootConfigSnapshot(
            schemaVersion = NoRootConfigSnapshotCodec.CURRENT_SCHEMA_VERSION,
            catalogVersion = SettingsCatalog.CATALOG_VERSION,
            modulePackage = BuildConfig.APPLICATION_ID,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
            revision = nextRevision(previous?.revision ?: 0L),
            adapterResetRevision = adapterResetRevision(context),
            enabled = false,
            values = emptyMap()
        )
        if (!writeSnapshotLocked(context, tombstone)) return@synchronized false
        prefs(context).edit()
            .putBoolean(KEY_DESIRED_ENABLED, false)
            .putString(
                KEY_SYNC_STATE,
                if (wasDesiredEnabled) SyncState.DISABLE_RESTART_REQUIRED.name
                else SyncState.DISABLED.name
            )
            .putLong(KEY_SYNC_REVISION, tombstone.revision)
            .putBoolean(KEY_DISABLE_WAS_ACTIVE, wasVerifiedActive)
            .remove(KEY_REMOTE_SYNCED_REVISION)
            .remove(KEY_SYNC_DETAIL)
            .remove(KEY_HEARTBEAT_REVISION)
            .remove(KEY_HEARTBEAT_MODULE_VERSION)
            .remove(KEY_HEARTBEAT_TARGET_VERSION)
            .remove(KEY_HEARTBEAT_TARGET_UPDATE_TIME)
            .remove(KEY_HEARTBEAT_TARGET_PACKAGE)
            .remove(KEY_HEARTBEAT_RECEIVED_AT)
            .remove(KEY_RUNTIME_REPLY_RECEIVED_AT)
            .commit()
    }

    fun adapterResetRevision(context: Context): Long =
        prefs(context).getLong(KEY_ADAPTER_RESET_REVISION, 0L).coerceAtLeast(0L)

    fun markAdapterReset(context: Context): Long {
        val current = adapterResetRevision(context)
        val next = nextRevision(current)
        prefs(context).edit { putLong(KEY_ADAPTER_RESET_REVISION, next) }
        return next
    }

    fun readStatus(context: Context): Status {
        val prefs = prefs(context)
        val lspatch = LspatchRuntimeProfiles.usesDirectConfig(context)
        val desired = prefs.getBoolean(KEY_DESIRED_ENABLED, false) && !lspatch
        val storedState = runCatching {
            SyncState.valueOf(prefs.getString(KEY_SYNC_STATE, null).orEmpty())
        }.getOrNull() ?: if (desired) SyncState.CHECKING else SyncState.DISABLED
        return Status(
            desiredEnabled = desired,
            syncState = if (desired || !lspatch && storedState == SyncState.DISABLE_RESTART_REQUIRED) {
                storedState
            } else {
                SyncState.DISABLED
            },
            detail = prefs.getString(KEY_SYNC_DETAIL, null),
            syncRevision = prefs.getLong(KEY_SYNC_REVISION, 0L),
            heartbeatRevision = prefs.getLong(KEY_HEARTBEAT_REVISION, 0L),
            heartbeatModuleVersion = prefs.getLong(KEY_HEARTBEAT_MODULE_VERSION, 0L),
            heartbeatTargetVersion = prefs.getLong(KEY_HEARTBEAT_TARGET_VERSION, 0L),
            heartbeatTargetUpdateTime = prefs.getLong(
                KEY_HEARTBEAT_TARGET_UPDATE_TIME,
                0L
            ),
            heartbeatTargetPackage = prefs.getString(KEY_HEARTBEAT_TARGET_PACKAGE, null),
            heartbeatReceivedAt = prefs.getLong(KEY_HEARTBEAT_RECEIVED_AT, 0L),
            disableWasActive = prefs.getBoolean(KEY_DISABLE_WAS_ACTIVE, false)
        )
    }

    /** 只记录已通过身份、nonce 和 Hook 安装校验的存活回执，不提升安装/同步状态。 */
    fun recordRuntimeReply(context: Context, receivedAt: Long = System.currentTimeMillis()) =
        synchronized(ioLock) {
            if (isDesiredEnabled(context) && receivedAt > 0L) {
                prefs(context).edit { putLong(KEY_RUNTIME_REPLY_RECEIVED_AT, receivedAt) }
            }
        }

    /** 冷启动心跳与存活探测都算回执；旧版本尚无探测时间时回落到心跳时间。 */
    fun lastReceiptReceivedAt(context: Context): Long {
        val prefs = prefs(context)
        return maxOf(
            prefs.getLong(KEY_HEARTBEAT_RECEIVED_AT, 0L),
            prefs.getLong(KEY_RUNTIME_REPLY_RECEIVED_AT, 0L)
        )
    }

    @SuppressLint("UseKtx")
    fun updateSyncState(
        context: Context,
        state: SyncState,
        revision: Long = 0L,
        detail: String? = null,
        stillCurrent: () -> Boolean = { true }
    ): Boolean = synchronized(ioLock) {
        val expectedRevision = revision.coerceAtLeast(0L)
        val currentSnapshot = if (expectedRevision > 0L) readSnapshotLocked(context) else null
        if (!acceptsEnabledStateWrite(
                desiredEnabled = isDesiredEnabled(context),
                currentSnapshot = currentSnapshot,
                expectedRevision = expectedRevision,
                stillCurrent = runCatching(stillCurrent).getOrDefault(false)
            )
        ) return@synchronized false
        val editor = prefs(context).edit()
            .putString(KEY_SYNC_STATE, state.name)
            .putLong(KEY_SYNC_REVISION, expectedRevision)
        if (detail.isNullOrBlank()) {
            editor.remove(KEY_SYNC_DETAIL)
        } else {
            editor.putString(KEY_SYNC_DETAIL, detail)
        }
        editor.commit()
    }

    @SuppressLint("UseKtx")
    fun markRemoteSynced(
        context: Context,
        revision: Long,
        stillCurrent: () -> Boolean = { true }
    ): Boolean = synchronized(ioLock) {
        if (revision <= 0L) return@synchronized false
        val snapshot = readSnapshotLocked(context)
        if (!acceptsEnabledStateWrite(
                desiredEnabled = isDesiredEnabled(context),
                currentSnapshot = snapshot,
                expectedRevision = revision,
                stillCurrent = runCatching(stillCurrent).getOrDefault(false)
            )
        ) return@synchronized false
        prefs(context).edit()
            .putLong(KEY_REMOTE_SYNCED_REVISION, revision)
            .commit()
    }

    fun isRemoteSynced(context: Context, revision: Long): Boolean = synchronized(ioLock) {
        if (revision <= 0L || !isDesiredEnabled(context)) return@synchronized false
        val snapshot = readSnapshotLocked(context) ?: return@synchronized false
        snapshot.enabled && snapshot.revision == revision &&
            prefs(context).getLong(KEY_REMOTE_SYNCED_REVISION, 0L) == revision
    }

    @SuppressLint("UseKtx")
    fun recordHeartbeat(
        context: Context,
        revision: Long,
        moduleVersionCode: Long,
        targetVersionCode: Long,
        targetUpdateTime: Long,
        targetPackage: String,
        receivedAt: Long = System.currentTimeMillis()
    ): Boolean = synchronized(ioLock) {
        if (!isDesiredEnabled(context) || revision <= 0L || moduleVersionCode <= 0L ||
            targetVersionCode <= 0L || targetUpdateTime <= 0L ||
            targetPackage.isBlank() || receivedAt <= 0L
        ) return@synchronized false
        val snapshot = readSnapshotLocked(context) ?: return@synchronized false
        if (!snapshot.enabled || snapshot.revision != revision ||
            snapshot.moduleVersionCode != moduleVersionCode ||
            snapshot.moduleVersionCode != BuildConfig.VERSION_CODE.toLong()
        ) return@synchronized false
        val committed = prefs(context).edit()
            .putLong(KEY_HEARTBEAT_REVISION, revision)
            .putLong(KEY_HEARTBEAT_MODULE_VERSION, moduleVersionCode)
            .putLong(KEY_HEARTBEAT_TARGET_VERSION, targetVersionCode)
            .putLong(KEY_HEARTBEAT_TARGET_UPDATE_TIME, targetUpdateTime)
            .putString(KEY_HEARTBEAT_TARGET_PACKAGE, targetPackage)
            .putLong(KEY_HEARTBEAT_RECEIVED_AT, receivedAt)
            .putString(KEY_SYNC_STATE, SyncState.ACTIVE.name)
            .putLong(KEY_SYNC_REVISION, revision)
            .remove(KEY_SYNC_DETAIL)
            .remove(KEY_DISABLE_WAS_ACTIVE)
            .commit()
        committed
    }

    /** 关闭后的补丁宿主完成一次冷启动握手，证明旧进程中的 Hook 已经退出。 */
    @SuppressLint("UseKtx")
    fun recordDisabledAck(
        context: Context,
        revision: Long,
        moduleVersionCode: Long,
        targetVersionCode: Long,
        targetUpdateTime: Long,
        targetPackage: String
    ): Boolean = synchronized(ioLock) {
        if (isDesiredEnabled(context) || revision <= 0L ||
            moduleVersionCode != BuildConfig.VERSION_CODE.toLong() ||
            targetVersionCode <= 0L || targetUpdateTime <= 0L ||
            targetPackage != NoRootSupportState.TARGET_PACKAGE
        ) return@synchronized false
        val snapshot = readSnapshotLocked(context) ?: return@synchronized false
        if (!acceptsDisabledAck(snapshot, revision, moduleVersionCode)) return@synchronized false
        val target = runCatching { context.packageManager.getPackageInfo(targetPackage, 0) }.getOrNull()
            ?: return@synchronized false
        if (target.versionCodeCompat != targetVersionCode || target.lastUpdateTime != targetUpdateTime)
            return@synchronized false
        val committed = prefs(context).edit()
            .putString(KEY_SYNC_STATE, SyncState.DISABLED.name)
            .putLong(KEY_SYNC_REVISION, revision)
            .remove(KEY_SYNC_DETAIL)
            .remove(KEY_DISABLE_WAS_ACTIVE)
            .commit()
        committed
    }

    internal fun acceptsDisabledAck(snapshot: NoRootConfigSnapshot, revision: Long, moduleVersion: Long): Boolean =
        !snapshot.enabled && revision > 0L && snapshot.revision == revision &&
            snapshot.moduleVersionCode == moduleVersion

    /** 从当前 70 项白名单构造完整快照；内容未变化时保留 revision。 */
    fun upsertEnabledSnapshot(
        context: Context,
        bridge: SharedPreferences
    ): NoRootConfigSnapshot? = synchronized(ioLock) {
        if (!isDesiredEnabled(context)) return@synchronized null
        val values = snapshotValues(bridge)
        val previous = readSnapshotLocked(context)
        val draft = NoRootConfigSnapshot(
            schemaVersion = NoRootConfigSnapshotCodec.CURRENT_SCHEMA_VERSION,
            catalogVersion = SettingsCatalog.CATALOG_VERSION,
            modulePackage = BuildConfig.APPLICATION_ID,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
            revision = previous?.revision ?: 1L,
            adapterResetRevision = adapterResetRevision(context),
            enabled = true,
            values = values
        )
        if (!isDesiredEnabled(context)) return@synchronized null
        if (previous != null && draft.hasSameContent(previous)) {
            return@synchronized previous
        }
        val resolved = draft.copy(revision = nextRevision(previous?.revision ?: 0L))
        if (!writeSnapshotLocked(context, resolved)) return@synchronized null
        clearHeartbeat(context)
        prefs(context).edit { remove(KEY_REMOTE_SYNCED_REVISION) }
        resolved
    }

    /** 与标准发布共用类型回落及旧键迁移，单次读取避免混合批量修改前后的配置。 */
    internal fun snapshotValues(bridge: SharedPreferences): Map<String, Any> {
        val normalized = RemoteHookConfigContract.resolveSourceValues(bridge.all)
        // 运行时凭据继续只在发布时合并；免 Root 文件仍只包含可备份设置。
        return SettingsCatalog.specs.associateTo(linkedMapOf()) { spec ->
            spec.storageKey to normalized.getValue(spec.storageKey)
        }
    }

    fun readSnapshot(context: Context): NoRootConfigSnapshot? = synchronized(ioLock) {
        readSnapshotLocked(context)
    }

    /** Provider 与安全回调共用同一权威导出语义。 */
    fun exportState(context: Context, authorized: Boolean): ExportState = synchronized(ioLock) {
        val desiredEnabled = authorized && isDesiredEnabled(context)
        if (!desiredEnabled) {
            val tombstone = readSnapshotLocked(context)?.takeUnless { it.enabled }
            return@synchronized ExportState(
                valid = true,
                enabled = false,
                revision = tombstone?.revision ?: 0L,
                payload = null
            )
        }
        val snapshot = readSnapshotLocked(context)
        // 这是已验真的本地权威导出；Manager 镜像不再是可读取当前快照的前置条件。
        val payload = if (snapshot?.enabled == true) {
            runCatching { NoRootConfigSnapshotCodec.encode(snapshot) }.getOrNull()
        } else {
            null
        }
        ExportState(
            valid = payload != null,
            enabled = true,
            revision = snapshot?.revision ?: 0L,
            payload = payload
        )
    }

    private fun clearHeartbeat(context: Context) {
        prefs(context).edit {
            remove(KEY_HEARTBEAT_REVISION)
            remove(KEY_HEARTBEAT_MODULE_VERSION)
            remove(KEY_HEARTBEAT_TARGET_VERSION)
            remove(KEY_HEARTBEAT_TARGET_UPDATE_TIME)
            remove(KEY_HEARTBEAT_TARGET_PACKAGE)
            remove(KEY_HEARTBEAT_RECEIVED_AT)
            remove(KEY_RUNTIME_REPLY_RECEIVED_AT)
        }
    }

    /** 只在原始关闭意图仍有效时修复停用文档；旧版本文件仅提供 revision 下界，不恢复配置。 */
    @SuppressLint("UseKtx") // 修复完成须同时确认快照和状态持久化，不能丢弃 commit 结果。
    fun ensureDisabledSnapshot(context: Context): NoRootConfigSnapshot? = synchronized(ioLock) {
        val stored = prefs(context)
        if (stored.getBoolean(KEY_DESIRED_ENABLED, false)) return@synchronized null
        val current = readSnapshotLocked(context)?.takeUnless { it.enabled }
        val syncRevision = stored.getLong(KEY_SYNC_REVISION, 0L).coerceAtLeast(0L)
        val heartbeatRevision = stored.getLong(KEY_HEARTBEAT_REVISION, 0L).coerceAtLeast(0L)
        if (current != null && current.revision == syncRevision && heartbeatRevision == 0L &&
            stored.getString(KEY_SYNC_STATE, null) in setOf(SyncState.DISABLED.name, SyncState.DISABLE_RESTART_REQUIRED.name)) {
            return@synchronized current
        }
        val previous = readSnapshotLocked(context, currentVersionOnly = false)
        val lowerBound = maxOf(previous?.revision ?: 0L, syncRevision, heartbeatRevision)
        val tombstone = current?.takeIf { it.revision >= lowerBound } ?: run {
            if (lowerBound == Long.MAX_VALUE) return@synchronized null
            NoRootConfigSnapshot(NoRootConfigSnapshotCodec.CURRENT_SCHEMA_VERSION, SettingsCatalog.CATALOG_VERSION,
                BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE.toLong(), nextRevision(lowerBound),
                adapterResetRevision(context), false, emptyMap())
        }
        if (tombstone !== current && !writeSnapshotLocked(context, tombstone)) return@synchronized null
        // 新文档必须取得自己的停用确认；保留 disableWasActive，不能把旧 Hook 描述为已退出。
        val saved = stored.edit()
            .putString(KEY_SYNC_STATE, SyncState.DISABLE_RESTART_REQUIRED.name)
            .putLong(KEY_SYNC_REVISION, tombstone.revision)
            .remove(KEY_SYNC_DETAIL)
            .remove(KEY_REMOTE_SYNCED_REVISION)
            .remove(KEY_HEARTBEAT_REVISION)
            .remove(KEY_HEARTBEAT_MODULE_VERSION)
            .remove(KEY_HEARTBEAT_TARGET_VERSION)
            .remove(KEY_HEARTBEAT_TARGET_UPDATE_TIME)
            .remove(KEY_HEARTBEAT_TARGET_PACKAGE)
            .remove(KEY_HEARTBEAT_RECEIVED_AT)
            .remove(KEY_RUNTIME_REPLY_RECEIVED_AT)
            .commit()
        tombstone.takeIf { saved }
    }

    private fun readSnapshotLocked(context: Context, currentVersionOnly: Boolean = true): NoRootConfigSnapshot? {
        val atomicFile = AtomicFile(File(context.filesDir, SNAPSHOT_FILE))
        if (!atomicFile.baseFile.isFile) return null
        val payload = runCatching {
            atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull() ?: return null
        return NoRootConfigSnapshotCodec.decode(
            payload,
            expectedModulePackage = BuildConfig.APPLICATION_ID,
            expectedModuleVersionCode = if (currentVersionOnly) BuildConfig.VERSION_CODE.toLong() else null
        )
    }

    private fun writeSnapshotLocked(context: Context, snapshot: NoRootConfigSnapshot): Boolean {
        val payload = runCatching { NoRootConfigSnapshotCodec.encode(snapshot) }.getOrNull()
            ?: return false
        val atomicFile = AtomicFile(File(context.filesDir, SNAPSHOT_FILE))
        val output = runCatching { atomicFile.startWrite() }.getOrNull() ?: return false
        return try {
            val writer = output.bufferedWriter(Charsets.UTF_8)
            writer.write(payload)
            writer.flush()
            atomicFile.finishWrite(output)
            true
        } catch (_: Throwable) {
            runCatching { atomicFile.failWrite(output) }
            false
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    private fun nextRevision(previous: Long): Long =
        max(System.currentTimeMillis().coerceAtLeast(1L), previous + 1L)

    /** 所有 enabled 状态写入都必须在 [ioLock] 内通过当前意图与快照代次校验。 */
    internal fun acceptsEnabledStateWrite(
        desiredEnabled: Boolean,
        currentSnapshot: NoRootConfigSnapshot?,
        expectedRevision: Long,
        stillCurrent: Boolean
    ): Boolean = stillCurrent && desiredEnabled &&
        (
            expectedRevision <= 0L ||
                (currentSnapshot?.enabled == true && currentSnapshot.revision == expectedRevision)
            )
}
