package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.annotation.SuppressLint
import android.content.Context
import android.util.AtomicFile
import androidx.core.content.edit
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValue
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.YukiModuleSettingsStore
import com.highcapable.betterandroid.system.extension.component.versionCodeCompat
import com.highcapable.yukihookapi.hook.xposed.prefs.YukiHookPrefsBridge
import java.io.File
import kotlin.math.max

/** 模块 App 私有的免 Root 用户意图、同步状态与宿主回执。 */
internal object NoRootSupportStore {
    private const val PREF_FILE = "innocent_lab_no_root_support"
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

    fun isDesiredEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DESIRED_ENABLED, false)

    @SuppressLint("UseKtx") // 必须取得 commit 结果，KTX edit(Unit) 无法表达写入失败。
    fun setDesiredEnabled(context: Context, enabled: Boolean): Boolean = synchronized(ioLock) {
        prefs(context).edit()
            .putBoolean(KEY_DESIRED_ENABLED, enabled)
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
        val desired = prefs.getBoolean(KEY_DESIRED_ENABLED, false)
        val storedState = runCatching {
            SyncState.valueOf(prefs.getString(KEY_SYNC_STATE, null).orEmpty())
        }.getOrNull() ?: if (desired) SyncState.CHECKING else SyncState.DISABLED
        return Status(
            desiredEnabled = desired,
            syncState = if (desired || storedState == SyncState.DISABLE_RESTART_REQUIRED) {
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
            targetPackage.isBlank()
        ) return@synchronized false
        val snapshot = readSnapshotLocked(context) ?: return@synchronized false
        if (snapshot.enabled || snapshot.revision != revision ||
            snapshot.moduleVersionCode != moduleVersionCode
        ) return@synchronized false
        val committed = prefs(context).edit()
            .putString(KEY_SYNC_STATE, SyncState.DISABLED.name)
            .putLong(KEY_SYNC_REVISION, revision)
            .remove(KEY_SYNC_DETAIL)
            .remove(KEY_DISABLE_WAS_ACTIVE)
            .commit()
        committed
    }

    /** 从当前 70 项白名单构造完整快照；内容未变化时保留 revision。 */
    fun upsertEnabledSnapshot(
        context: Context,
        bridge: YukiHookPrefsBridge
    ): NoRootConfigSnapshot? = synchronized(ioLock) {
        if (!isDesiredEnabled(context)) return@synchronized null
        val settingsStore = YukiModuleSettingsStore(bridge)
        val values = linkedMapOf<String, Any>()
        SettingsCatalog.specs.forEach { spec ->
            val storedValue = settingsStore.read(spec).value
            // 与备份协议采用同一兼容语义：离散旧值回落默认、连续范围收敛；
            // 无法可靠解释的损坏值也只在快照中回落，不反向改写用户原 prefs。
            val value = spec.normalizeForBackup(storedValue) ?: spec.defaultValue
            values[spec.storageKey] = when (value) {
                is SettingValue.Bool -> value.value
                is SettingValue.IntValue -> value.value
                is SettingValue.Text -> value.value
            }
        }
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
        val resolved = if (previous != null && draft.hasSameContent(previous)) {
            previous
        } else {
            draft.copy(revision = nextRevision(previous?.revision ?: 0L))
        }
        if (!isDesiredEnabled(context)) return@synchronized null
        if (!writeSnapshotLocked(context, resolved)) return@synchronized null
        if (previous?.revision != resolved.revision) {
            clearHeartbeat(context)
            prefs(context).edit { remove(KEY_REMOTE_SYNCED_REVISION) }
        }
        resolved
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
        val remoteSynced = snapshot?.revision != null &&
            prefs(context).getLong(KEY_REMOTE_SYNCED_REVISION, 0L) == snapshot.revision
        val payload = if (snapshot?.enabled == true && remoteSynced) {
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
        }
    }

    private fun readSnapshotLocked(context: Context): NoRootConfigSnapshot? {
        val atomicFile = AtomicFile(File(context.filesDir, SNAPSHOT_FILE))
        if (!atomicFile.baseFile.isFile) return null
        val payload = runCatching {
            atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull() ?: return null
        return NoRootConfigSnapshotCodec.decode(
            payload,
            expectedModulePackage = BuildConfig.APPLICATION_ID,
            expectedModuleVersionCode = BuildConfig.VERSION_CODE.toLong()
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
