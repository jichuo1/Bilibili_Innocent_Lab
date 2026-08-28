package com.Bilibili_Innocent_Lab.xposedmodule.settings.backup

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal enum class ImportStatus {
    EXACT,
    MIGRATED,
    UNCHANGED,
    SOURCE_DEFAULT_SKIPPED,
    NEW_IN_CURRENT,
    MISSING_FROM_SOURCE,
    REMOVED,
    UNKNOWN_FROM_NEWER,
    MANUAL_REQUIRED,
    INVALID_VALUE,
    CONFLICT
}

internal enum class ImportReason {
    SAFE_EXACT_MATCH,
    SAFE_AFTER_MIGRATION,
    ALREADY_EXPLICIT_AND_EQUAL,
    SOURCE_WAS_IMPLICIT_DEFAULT,
    INTRODUCED_AFTER_BACKUP,
    PARTIAL_BACKUP_OMISSION,
    COMPLETE_BACKUP_MISSING_RECORD,
    REMOVED_FROM_CURRENT,
    CREATED_BY_NEWER_VERSION,
    VALUE_VERSION_NEWER,
    VALUE_MIGRATION_MISSING,
    RESTORE_POLICY_MANUAL,
    TYPE_OR_RANGE_INVALID,
    MULTIPLE_SOURCE_RECORDS
}

internal data class SettingWrite(
    val spec: SettingSpec,
    val value: SettingValue
)

internal data class ImportPlanEntry(
    val id: String,
    val spec: SettingSpec?,
    val status: ImportStatus,
    val source: BackupSetting?,
    val current: StoredSetting?,
    val proposed: SettingValue?,
    val willWrite: Boolean,
    val reason: ImportReason,
    val migrationNotes: List<String> = emptyList()
)

internal enum class PlanBlocker {
    CATALOG_MIGRATION_FAILED
}

internal data class ImportPlan(
    val source: SettingsBackupDocument,
    val entries: List<ImportPlanEntry>,
    val currentFingerprint: String,
    val blockers: Set<PlanBlocker> = emptySet(),
    val migrationWarnings: List<String> = emptyList()
) {
    val writes: List<SettingWrite>
        get() = entries.filter(ImportPlanEntry::willWrite).map { entry ->
            SettingWrite(requireNotNull(entry.spec), requireNotNull(entry.proposed))
        }

    val effects: Set<ImportEffect>
        get() = writes.flatMapTo(linkedSetOf()) { it.spec.effects }

    val canApply: Boolean
        get() = blockers.isEmpty() && writes.isNotEmpty()

    fun count(status: ImportStatus): Int = entries.count { it.status == status }
}

internal data class MigrationStepResult(
    val records: List<BackupSetting>,
    val notesByResultId: Map<String, List<String>> = emptyMap(),
    val warnings: List<String> = emptyList()
)

/** 一次 catalog 升级可以观察完整记录集合，以显式支持 rename、split 和 merge。 */
internal interface CatalogMigration {
    val fromVersion: Int
    val toVersion: Int
    fun migrate(records: List<BackupSetting>): MigrationStepResult
}

internal data class SettingTombstone(
    val id: String,
    val removedInCatalogVersion: Int
)

internal sealed interface MigrationRun {
    data class Success(
        val records: List<BackupSetting>,
        val notesByResultId: Map<String, List<String>>,
        val warnings: List<String>
    ) : MigrationRun

    data class Unsupported(val blocker: PlanBlocker) : MigrationRun
}

internal class SettingsMigrationRegistry(
    migrations: List<CatalogMigration> = emptyList(),
    tombstones: List<SettingTombstone> = emptyList()
) {
    private val migrationsByVersion = migrations.associateBy(CatalogMigration::fromVersion)
    private val tombstonesById = tombstones.associateBy(SettingTombstone::id)

    init {
        check(migrationsByVersion.size == migrations.size) { "Duplicate catalog migration source version" }
        check(migrations.all { it.toVersion == it.fromVersion + 1 }) {
            "Catalog migrations must advance exactly one version"
        }
        check(tombstonesById.size == tombstones.size) { "Duplicate setting tombstone" }
    }

    fun migrate(
        sourceVersion: Int,
        targetVersion: Int,
        sourceRecords: List<BackupSetting>
    ): MigrationRun {
        if (sourceVersion >= targetVersion) {
            return MigrationRun.Success(sourceRecords, emptyMap(), emptyList())
        }
        var version = sourceVersion
        var records = sourceRecords
        val notes = linkedMapOf<String, MutableList<String>>()
        val warnings = mutableListOf<String>()
        while (version < targetVersion) {
            val migration = migrationsByVersion[version]
            if (migration == null) {
                // 文件逐项自描述；缺少迁移时仍可对同 id/同 valueVersion 的记录做安全子集恢复。
                warnings += "missing-catalog-migration:$version"
                break
            }
            val result = try {
                migration.migrate(records)
            } catch (_: RuntimeException) {
                return MigrationRun.Unsupported(PlanBlocker.CATALOG_MIGRATION_FAILED)
            }
            records = result.records
            result.notesByResultId.forEach { (id, values) ->
                notes.getOrPut(id, ::mutableListOf).addAll(values)
            }
            warnings += result.warnings
            version = migration.toVersion
        }
        return MigrationRun.Success(records, notes.mapValues { it.value.toList() }, warnings)
    }

    fun isRemoved(id: String, currentCatalogVersion: Int): Boolean =
        tombstonesById[id]?.removedInCatalogVersion?.let { it <= currentCatalogVersion } == true
}

internal class SettingsImportPlanner(
    private val catalog: List<SettingSpec> = SettingsCatalog.specs,
    private val currentCatalogVersion: Int = SettingsCatalog.CATALOG_VERSION,
    private val migrations: SettingsMigrationRegistry = SettingsMigrationRegistry()
) {

    fun plan(source: SettingsBackupDocument, current: SettingsSnapshot): ImportPlan {
        require(source.productId == SettingsCatalog.PRODUCT_ID) { "Wrong product" }
        require(source.scope.id == SettingsCatalog.SCOPE_ID) { "Wrong backup scope" }
        require(catalog.map(SettingSpec::id).toSet() == current.values.keys) {
            "Current settings snapshot does not match catalog"
        }

        val migrationRun = migrations.migrate(
            sourceVersion = source.catalogVersion,
            targetVersion = currentCatalogVersion,
            sourceRecords = source.settings
        )
        if (migrationRun is MigrationRun.Unsupported) {
            return ImportPlan(
                source = source,
                entries = emptyList(),
                currentFingerprint = SettingsFingerprint.create(current, catalog),
                blockers = setOf(migrationRun.blocker)
            )
        }
        migrationRun as MigrationRun.Success

        val entries = mutableListOf<ImportPlanEntry>()
        val consumed = hashSetOf<BackupSetting>()
        catalog.forEach { spec ->
            val currentValue = requireNotNull(current[spec.id])
            val candidates = migrationRun.records.filter { it.id == spec.id }
            if (candidates.size > 1) {
                consumed += candidates
                entries += ImportPlanEntry(
                    id = spec.id,
                    spec = spec,
                    status = ImportStatus.CONFLICT,
                    source = null,
                    current = currentValue,
                    proposed = null,
                    willWrite = false,
                    reason = ImportReason.MULTIPLE_SOURCE_RECORDS
                )
                return@forEach
            }
            val record = candidates.singleOrNull()
            if (record == null) {
                val status: ImportStatus
                val reason: ImportReason
                when {
                    source.catalogVersion < spec.introducedCatalogVersion -> {
                        status = ImportStatus.NEW_IN_CURRENT
                        reason = ImportReason.INTRODUCED_AFTER_BACKUP
                    }
                    !source.scope.complete -> {
                        status = ImportStatus.SOURCE_DEFAULT_SKIPPED
                        reason = ImportReason.PARTIAL_BACKUP_OMISSION
                    }
                    else -> {
                        status = ImportStatus.MISSING_FROM_SOURCE
                        reason = ImportReason.COMPLETE_BACKUP_MISSING_RECORD
                    }
                }
                entries += ImportPlanEntry(
                    id = spec.id,
                    spec = spec,
                    status = status,
                    source = null,
                    current = currentValue,
                    proposed = null,
                    willWrite = false,
                    reason = reason
                )
                return@forEach
            }
            consumed += record
            entries += planKnownRecord(
                spec = spec,
                record = record,
                current = currentValue,
                migrationNotes = migrationRun.notesByResultId[spec.id].orEmpty()
            )
        }

        migrationRun.records.filterNot(consumed::contains).forEach { record ->
            val fromNewer = source.catalogVersion > currentCatalogVersion &&
                !migrations.isRemoved(record.id, currentCatalogVersion)
            entries += ImportPlanEntry(
                id = record.id,
                spec = null,
                status = if (fromNewer) ImportStatus.UNKNOWN_FROM_NEWER else ImportStatus.REMOVED,
                source = record,
                current = null,
                proposed = null,
                willWrite = false,
                reason = if (fromNewer) {
                    ImportReason.CREATED_BY_NEWER_VERSION
                } else {
                    ImportReason.REMOVED_FROM_CURRENT
                }
            )
        }

        return ImportPlan(
            source = source,
            entries = entries,
            currentFingerprint = SettingsFingerprint.create(current, catalog),
            migrationWarnings = migrationRun.warnings
        )
    }

    private fun planKnownRecord(
        spec: SettingSpec,
        record: BackupSetting,
        current: StoredSetting,
        migrationNotes: List<String>
    ): ImportPlanEntry {
        if (record.valueVersion > spec.valueVersion) {
            return entry(spec, record, current, ImportStatus.UNKNOWN_FROM_NEWER, ImportReason.VALUE_VERSION_NEWER)
        }
        if (record.valueVersion < spec.valueVersion) {
            return entry(spec, record, current, ImportStatus.MANUAL_REQUIRED, ImportReason.VALUE_MIGRATION_MISSING)
        }
        if (!spec.type.accepts(record.value) || !spec.accepts(record.value)) {
            return entry(spec, record, current, ImportStatus.INVALID_VALUE, ImportReason.TYPE_OR_RANGE_INVALID)
        }
        if (!record.explicit) {
            return entry(
                spec,
                record,
                current,
                ImportStatus.SOURCE_DEFAULT_SKIPPED,
                ImportReason.SOURCE_WAS_IMPLICIT_DEFAULT
            )
        }
        if (spec.restorePolicy == RestorePolicy.MANUAL) {
            return entry(spec, record, current, ImportStatus.MANUAL_REQUIRED, ImportReason.RESTORE_POLICY_MANUAL)
        }
        if (current.explicit && current.value == record.value) {
            return entry(spec, record, current, ImportStatus.UNCHANGED, ImportReason.ALREADY_EXPLICIT_AND_EQUAL)
        }
        val wasMigrated = migrationNotes.isNotEmpty()
        return ImportPlanEntry(
            id = spec.id,
            spec = spec,
            status = if (wasMigrated) ImportStatus.MIGRATED else ImportStatus.EXACT,
            source = record,
            current = current,
            proposed = record.value,
            willWrite = true,
            reason = if (wasMigrated) ImportReason.SAFE_AFTER_MIGRATION else ImportReason.SAFE_EXACT_MATCH,
            migrationNotes = migrationNotes
        )
    }

    private fun entry(
        spec: SettingSpec,
        source: BackupSetting,
        current: StoredSetting,
        status: ImportStatus,
        reason: ImportReason
    ) = ImportPlanEntry(
        id = spec.id,
        spec = spec,
        status = status,
        source = source,
        current = current,
        proposed = null,
        willWrite = false,
        reason = reason
    )
}

internal object SettingsFingerprint {
    fun create(snapshot: SettingsSnapshot, catalog: List<SettingSpec> = SettingsCatalog.specs): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            catalog.sortedBy(SettingSpec::id).forEach { spec ->
                val stored = requireNotNull(snapshot[spec.id])
                output.writeString(spec.id)
                output.writeBoolean(stored.explicit)
                when (val value = stored.value) {
                    is SettingValue.Bool -> {
                        output.writeByte(1)
                        output.writeBoolean(value.value)
                    }
                    is SettingValue.IntValue -> {
                        output.writeByte(2)
                        output.writeInt(value.value)
                    }
                    is SettingValue.Text -> {
                        output.writeByte(3)
                        output.writeString(value.value)
                    }
                }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
        val alphabet = "0123456789abcdef"
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(alphabet[value ushr 4])
                append(alphabet[value and 0x0f])
            }
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        // Fingerprint is process-internal: write exact UTF-16 code units so malformed legacy
        // strings cannot collapse through UTF-8 replacement-character encoding.
        writeInt(value.length)
        value.forEach { character -> writeChar(character.code) }
    }
}
