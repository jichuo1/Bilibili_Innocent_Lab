package com.Bilibili_Innocent_Lab.xposedmodule.settings.backup

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.FreeCopyConfigStore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import kotlin.math.max

/** 通过模块私有权威 SharedPreferences 访问设置；不会读取任意未知键，也不会 clear。 */
internal class ModuleSettingsStore(
    private val bridge: SharedPreferences
) : SettingsReader {

    override fun read(spec: SettingSpec): StoredSetting {
        val value = when (val default = spec.defaultValue) {
            is SettingValue.Bool -> SettingValue.Bool(
                bridge.getBoolean(spec.storageKey, default.value)
            )
            is SettingValue.IntValue -> SettingValue.IntValue(
                bridge.getInt(spec.storageKey, default.value)
            )
            is SettingValue.Text -> SettingValue.Text(
                bridge.getString(spec.storageKey, default.value) ?: default.value
            )
        }
        return StoredSetting(
            explicit = bridge.contains(spec.storageKey),
            value = value
        )
    }

    fun snapshot(catalog: List<SettingSpec> = SettingsCatalog.specs): SettingsSnapshot =
        SettingsBackupFactory.snapshot(this, catalog)

    fun commit(writes: List<SettingWrite>, freeCopyRevision: Long? = null): Boolean {
        val editor = bridge.edit()
        writes.forEach { write ->
            when (val value = write.value) {
                is SettingValue.Bool -> editor.putBoolean(write.spec.storageKey, value.value)
                is SettingValue.IntValue -> editor.putInt(write.spec.storageKey, value.value)
                is SettingValue.Text -> editor.putString(write.spec.storageKey, value.value)
            }
        }
        if (freeCopyRevision != null) {
            editor.putLong(HookEntry.PREF_FREE_COPY_CONFIG_REVISION, freeCopyRevision)
        }
        return editor.commit()
    }

    fun getLong(key: String, defaultValue: Long): Long = bridge.getLong(key, defaultValue)
}

internal sealed interface SettingsApplyResult {
    data class Success(
        val changedCount: Int,
        val effects: Set<ImportEffect>
    ) : SettingsApplyResult

    data object NothingToApply : SettingsApplyResult
    data object StalePlan : SettingsApplyResult
    data object CommitFailed : SettingsApplyResult
    data object ReadBackFailed : SettingsApplyResult
    data object DerivedStatePending : SettingsApplyResult
}

/**
 * 把纯 ImportPlan 落到模块私有 prefs，并闭环自由复制的 AtomicFile 镜像。
 *
 * journal 采用幂等 roll-forward：prefs 单文件提交成功但进程在镜像写入前退出时，
 * 下次模块进程启动会用同一 revision 补写镜像；不会尝试脆弱的跨文件即时回滚。
 */
internal class SettingsImportApplier(
    private val context: Context,
    private val store: ModuleSettingsStore,
    private val now: () -> Long = System::currentTimeMillis
) {

    fun apply(plan: ImportPlan): SettingsApplyResult {
        if (!recoverPending(context, store)) return SettingsApplyResult.DerivedStatePending
        if (!plan.canApply) return SettingsApplyResult.NothingToApply

        val before = runCatching { store.snapshot() }.getOrNull()
            ?: return SettingsApplyResult.ReadBackFailed
        if (SettingsFingerprint.create(before) != plan.currentFingerprint) {
            return SettingsApplyResult.StalePlan
        }

        val rebuildFreeCopy = ImportEffect.REBUILD_FREE_COPY_MIRROR in plan.effects
        val pending = if (rebuildFreeCopy) createPendingFreeCopy(before, plan.writes) else null
        if (pending != null && !FreeCopyImportJournal.write(context, pending)) {
            return SettingsApplyResult.DerivedStatePending
        }

        if (!runCatching { store.commit(plan.writes, pending?.revision) }.getOrDefault(false)) {
            if (pending != null) FreeCopyImportJournal.delete(context)
            return SettingsApplyResult.CommitFailed
        }

        val after = runCatching { store.snapshot() }.getOrNull()
            ?: return SettingsApplyResult.ReadBackFailed
        val readBackMatches = plan.writes.all { write ->
            after[write.spec.id]?.let { stored -> stored.explicit && stored.value == write.value } == true
        }
        val revisionMatches = pending == null || runCatching {
            store.getLong(HookEntry.PREF_FREE_COPY_CONFIG_REVISION, 0L)
        }.getOrNull() == pending.revision
        if (!readBackMatches || !revisionMatches) return SettingsApplyResult.ReadBackFailed

        if (pending != null) {
            if (!writeAndVerifyFreeCopyMirror(context, pending)) {
                return SettingsApplyResult.DerivedStatePending
            }
            FreeCopyImportJournal.delete(context)
        }
        return SettingsApplyResult.Success(plan.writes.size, plan.effects)
    }

    private fun createPendingFreeCopy(
        before: SettingsSnapshot,
        writes: List<SettingWrite>
    ): PendingFreeCopyMirror {
        val values = before.values.toMutableMap()
        writes.forEach { write -> values[write.spec.id] = StoredSetting(true, write.value) }
        val comment = (requireNotNull(values[SettingsCatalog.ID_FREE_COPY_COMMENT]).value as SettingValue.Bool).value
        val description = (requireNotNull(values[SettingsCatalog.ID_FREE_COPY_DESCRIPTION]).value as SettingValue.Bool).value
        val mirrorRevision = FreeCopyConfigStore.read(context)?.revision ?: 0L
        val prefsRevision = store.getLong(HookEntry.PREF_FREE_COPY_CONFIG_REVISION, 0L)
        val revision = max(
            max(now().coerceAtLeast(1L), mirrorRevision.nextRevision()),
            prefsRevision.nextRevision()
        )
        return PendingFreeCopyMirror(comment, description, revision)
    }

    private fun Long.nextRevision(): Long = when {
        this < 1L -> 1L
        this == Long.MAX_VALUE -> Long.MAX_VALUE
        else -> this + 1L
    }

    companion object {
        fun hasPendingRecovery(context: Context): Boolean = FreeCopyImportJournal.exists(context)

        fun recoverPending(context: Context, store: ModuleSettingsStore): Boolean {
            val pending = when (val read = FreeCopyImportJournal.read(context)) {
                FreeCopyJournalRead.Missing -> return true
                FreeCopyJournalRead.Unreadable -> return false
                is FreeCopyJournalRead.Ready -> read.pending
            }
            val prefsRevision = runCatching {
                store.getLong(HookEntry.PREF_FREE_COPY_CONFIG_REVISION, 0L)
            }.getOrElse {
                // 暂时性 bridge/类型读取失败不能销毁唯一的前滚恢复意图。
                return false
            }
            if (prefsRevision != pending.revision) {
                FreeCopyImportJournal.delete(context)
                return true
            }
            val commentSpec = requireNotNull(SettingsCatalog.byId[SettingsCatalog.ID_FREE_COPY_COMMENT])
            val descriptionSpec = requireNotNull(SettingsCatalog.byId[SettingsCatalog.ID_FREE_COPY_DESCRIPTION])
            val comment = runCatching {
                (store.read(commentSpec).value as SettingValue.Bool).value
            }.getOrNull() ?: return false
            val description = runCatching {
                (store.read(descriptionSpec).value as SettingValue.Bool).value
            }.getOrNull() ?: return false
            if (comment != pending.commentEnabled || description != pending.descriptionEnabled) {
                FreeCopyImportJournal.delete(context)
                return true
            }
            if (!writeAndVerifyFreeCopyMirror(context, pending)) return false
            FreeCopyImportJournal.delete(context)
            return true
        }

        private fun writeAndVerifyFreeCopyMirror(
            context: Context,
            pending: PendingFreeCopyMirror
        ): Boolean {
            if (!FreeCopyConfigStore.write(
                    context = context,
                    commentEnabled = pending.commentEnabled,
                    descriptionEnabled = pending.descriptionEnabled,
                    revision = pending.revision
                )
            ) return false
            return FreeCopyConfigStore.read(context) == FreeCopyConfigStore.Snapshot(
                commentEnabled = pending.commentEnabled,
                descriptionEnabled = pending.descriptionEnabled,
                revision = pending.revision
            )
        }
    }
}

private data class PendingFreeCopyMirror(
    val commentEnabled: Boolean,
    val descriptionEnabled: Boolean,
    val revision: Long
)

private sealed interface FreeCopyJournalRead {
    data object Missing : FreeCopyJournalRead
    data object Unreadable : FreeCopyJournalRead
    data class Ready(val pending: PendingFreeCopyMirror) : FreeCopyJournalRead
}

private object FreeCopyImportJournal {
    private const val FILE_NAME = "settings_import_free_copy_journal.bin"
    private const val MAGIC = 0x42494C4A // BILJ
    private const val VERSION = 1

    fun write(context: Context, pending: PendingFreeCopyMirror): Boolean {
        val file = AtomicFile(File(context.filesDir, FILE_NAME))
        val output = runCatching { file.startWrite() }.getOrNull() ?: return false
        return try {
            val data = DataOutputStream(output.buffered())
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeLong(pending.revision)
            data.writeBoolean(pending.commentEnabled)
            data.writeBoolean(pending.descriptionEnabled)
            data.flush()
            file.finishWrite(output)
            true
        } catch (_: Exception) {
            runCatching { file.failWrite(output) }
            false
        }
    }

    fun exists(context: Context): Boolean = atomicFile(context).baseFile.isFile

    fun read(context: Context): FreeCopyJournalRead {
        val file = AtomicFile(File(context.filesDir, FILE_NAME))
        if (!file.baseFile.isFile) return FreeCopyJournalRead.Missing
        val input = try {
            file.openRead()
        } catch (_: IOException) {
            return FreeCopyJournalRead.Unreadable
        } catch (_: SecurityException) {
            return FreeCopyJournalRead.Unreadable
        }
        val pending = try {
            DataInputStream(input.buffered()).use { data ->
                if (data.readInt() != MAGIC || data.readInt() != VERSION) return@use null
                val revision = data.readLong()
                val comment = data.readBoolean()
                val description = data.readBoolean()
                if (revision <= 0L || data.read() != -1) null
                else PendingFreeCopyMirror(comment, description, revision)
            }
        } catch (_: EOFException) {
            null
        } catch (_: IOException) {
            return FreeCopyJournalRead.Unreadable
        } catch (_: SecurityException) {
            return FreeCopyJournalRead.Unreadable
        }
        return if (pending == null) {
            delete(context)
            FreeCopyJournalRead.Missing
        } else {
            FreeCopyJournalRead.Ready(pending)
        }
    }

    fun delete(context: Context) {
        atomicFile(context).delete()
    }

    private fun atomicFile(context: Context) = AtomicFile(File(context.filesDir, FILE_NAME))
}
