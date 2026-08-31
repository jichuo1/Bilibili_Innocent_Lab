package com.Bilibili_Innocent_Lab.xposedmodule.settings.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsImportPlannerTest {

    private val enabledSpec = boolSpec("feature.enabled", default = false)
    private val manualSpec = boolSpec(
        "feature.manual",
        default = false,
        restorePolicy = RestorePolicy.MANUAL
    )

    @Test
    fun `explicit source value is restored and implicit source default never overwrites`() {
        val catalog = listOf(enabledSpec, manualSpec)
        val current = snapshot(
            enabledSpec to StoredSetting(explicit = true, SettingValue.Bool(false)),
            manualSpec to StoredSetting(explicit = true, SettingValue.Bool(true))
        )
        val source = document(
            catalogVersion = 1,
            records = listOf(
                record(enabledSpec, explicit = true, SettingValue.Bool(true)),
                record(manualSpec, explicit = false, SettingValue.Bool(false))
            )
        )

        val plan = SettingsImportPlanner(catalog, 1).plan(source, current)
        assertEquals(ImportStatus.EXACT, plan.entries.first { it.id == enabledSpec.id }.status)
        assertEquals(
            ImportStatus.SOURCE_DEFAULT_SKIPPED,
            plan.entries.first { it.id == manualSpec.id }.status
        )
        assertEquals(1, plan.writes.size)
    }

    @Test
    fun `explicit intent is written even when value equals current implicit default`() {
        val current = snapshot(enabledSpec to StoredSetting(false, SettingValue.Bool(false)))
        val source = document(1, listOf(record(enabledSpec, true, SettingValue.Bool(false))))
        val plan = SettingsImportPlanner(listOf(enabledSpec), 1).plan(source, current)

        assertEquals(ImportStatus.EXACT, plan.entries.single().status)
        assertTrue(plan.entries.single().willWrite)
    }

    @Test
    fun `old implicit default does not overwrite a changed current default`() {
        val currentSpec = boolSpec("feature.changed_default", default = true)
        val source = document(1, listOf(
            BackupSetting(currentSpec.id, 1, explicit = false, SettingValue.Bool(false))
        ))
        val current = snapshot(currentSpec to StoredSetting(false, SettingValue.Bool(true)))
        val plan = SettingsImportPlanner(listOf(currentSpec), 1).plan(source, current)

        assertEquals(ImportStatus.SOURCE_DEFAULT_SKIPPED, plan.entries.single().status)
        assertFalse(plan.canApply)
    }

    @Test
    fun `equal explicit values are unchanged and manual values are never written`() {
        val catalog = listOf(enabledSpec, manualSpec)
        val current = snapshot(
            enabledSpec to StoredSetting(true, SettingValue.Bool(true)),
            manualSpec to StoredSetting(true, SettingValue.Bool(false))
        )
        val source = document(1, listOf(
            record(enabledSpec, true, SettingValue.Bool(true)),
            record(manualSpec, true, SettingValue.Bool(true))
        ))
        val plan = SettingsImportPlanner(catalog, 1).plan(source, current)

        assertEquals(ImportStatus.UNCHANGED, plan.entries.first { it.id == enabledSpec.id }.status)
        assertEquals(ImportStatus.MANUAL_REQUIRED, plan.entries.first { it.id == manualSpec.id }.status)
        assertFalse(plan.canApply)
    }

    @Test
    fun `new current settings stay at current value and future unknown settings are reported`() {
        val newSpec = boolSpec("feature.new", introducedCatalogVersion = 2)
        val current = snapshot(newSpec to StoredSetting(false, SettingValue.Bool(false)))
        val source = document(
            catalogVersion = 1,
            records = listOf(
                BackupSetting("future.unknown", 1, true, SettingValue.Bool(true))
            )
        )
        val plan = SettingsImportPlanner(listOf(newSpec), 2).plan(source, current)

        assertEquals(ImportStatus.NEW_IN_CURRENT, plan.entries.first { it.id == newSpec.id }.status)
        assertEquals(ImportStatus.REMOVED, plan.entries.first { it.id == "future.unknown" }.status)
    }

    @Test
    fun `catalog v1 import reports both duration boundaries as new current settings`() {
        val durationSpecs = listOf(
            requireNotNull(
                SettingsCatalog.byId[SettingsCatalog.ID_RECOMMEND_VIDEO_MIN_DURATION]
            ),
            requireNotNull(
                SettingsCatalog.byId[SettingsCatalog.ID_RECOMMEND_VIDEO_MAX_DURATION]
            )
        )
        val current = SettingsSnapshot(
            durationSpecs.associate { spec ->
                spec.id to StoredSetting(explicit = true, SettingValue.IntValue(120))
            }
        )
        val source = document(catalogVersion = 1, records = emptyList())

        val plan = SettingsImportPlanner(
            durationSpecs,
            2
        ).plan(source, current)

        assertEquals(2, plan.entries.size)
        assertTrue(plan.entries.all { it.status == ImportStatus.NEW_IN_CURRENT })
        assertTrue(plan.entries.none { it.willWrite })
        assertTrue(plan.migrationWarnings.isEmpty())
    }

    @Test
    fun `catalog v2 import reports mine selectors as a new current setting`() {
        val selectorSpec = requireNotNull(
            SettingsCatalog.byId["mine.components.hidden_selectors"]
        )
        val current = SettingsSnapshot(
            mapOf(
                selectorSpec.id to StoredSetting(
                    explicit = true,
                    value = SettingValue.Text("[\"item:id:1\"]")
                )
            )
        )
        val source = document(catalogVersion = 2, records = emptyList())

        val plan = SettingsImportPlanner(listOf(selectorSpec), 3).plan(source, current)

        assertEquals(1, plan.entries.size)
        assertEquals(ImportStatus.NEW_IN_CURRENT, plan.entries.single().status)
        assertFalse(plan.entries.single().willWrite)
        assertTrue(plan.migrationWarnings.isEmpty())
    }

    @Test
    fun `reversed imported duration range is visible and never written`() {
        val minSpec = requireNotNull(
            SettingsCatalog.byId[SettingsCatalog.ID_RECOMMEND_VIDEO_MIN_DURATION]
        )
        val maxSpec = requireNotNull(
            SettingsCatalog.byId[SettingsCatalog.ID_RECOMMEND_VIDEO_MAX_DURATION]
        )
        val catalog = listOf(minSpec, maxSpec)
        val current = snapshot(
            minSpec to StoredSetting(explicit = true, SettingValue.IntValue(60)),
            maxSpec to StoredSetting(explicit = true, SettingValue.IntValue(600))
        )
        val source = document(
            catalogVersion = 2,
            records = listOf(
                record(minSpec, explicit = true, SettingValue.IntValue(600)),
                record(maxSpec, explicit = true, SettingValue.IntValue(30))
            )
        )

        val plan = SettingsImportPlanner(catalog, 2).plan(source, current)

        assertEquals(2, plan.entries.size)
        assertTrue(plan.entries.all { it.status == ImportStatus.INVALID_VALUE })
        assertTrue(plan.entries.none { it.willWrite })
        assertFalse(plan.canApply)
    }

    @Test
    fun `single imported duration boundary cannot conflict with the kept current boundary`() {
        val minSpec = requireNotNull(
            SettingsCatalog.byId[SettingsCatalog.ID_RECOMMEND_VIDEO_MIN_DURATION]
        )
        val maxSpec = requireNotNull(
            SettingsCatalog.byId[SettingsCatalog.ID_RECOMMEND_VIDEO_MAX_DURATION]
        )
        val catalog = listOf(minSpec, maxSpec)
        val current = snapshot(
            minSpec to StoredSetting(explicit = true, SettingValue.IntValue(60)),
            maxSpec to StoredSetting(explicit = true, SettingValue.IntValue(300))
        )
        val source = document(
            catalogVersion = 2,
            records = listOf(record(minSpec, true, SettingValue.IntValue(600)))
        ).copy(scope = BackupScope(SettingsCatalog.SCOPE_ID, complete = false, recordCount = 1))

        val plan = SettingsImportPlanner(catalog, 2).plan(source, current)

        assertEquals(
            ImportStatus.INVALID_VALUE,
            plan.entries.first { it.id == minSpec.id }.status
        )
        assertEquals(
            ImportStatus.SOURCE_DEFAULT_SKIPPED,
            plan.entries.first { it.id == maxSpec.id }.status
        )
        assertFalse(plan.canApply)
    }

    @Test
    fun `records from a future catalog and future value semantics are not guessed`() {
        val source = document(
            catalogVersion = 3,
            records = listOf(
                record(enabledSpec, true, SettingValue.Bool(true)).copy(valueVersion = 2),
                BackupSetting("future.unknown", 1, true, SettingValue.Bool(true))
            )
        )
        val current = snapshot(enabledSpec to StoredSetting(false, SettingValue.Bool(false)))
        val plan = SettingsImportPlanner(listOf(enabledSpec), 1).plan(source, current)

        assertEquals(ImportStatus.UNKNOWN_FROM_NEWER, plan.entries.first { it.id == enabledSpec.id }.status)
        assertEquals(ImportStatus.UNKNOWN_FROM_NEWER, plan.entries.first { it.id == "future.unknown" }.status)
    }

    @Test
    fun `value version is classified before applying the current value schema`() {
        val currentV2 = enabledSpec.copy(valueVersion = 2)
        val current = snapshot(currentV2 to StoredSetting(false, SettingValue.Bool(false)))

        val newerType = document(3, listOf(
            BackupSetting(currentV2.id, 3, true, SettingValue.Text("new-type"))
        ))
        assertEquals(
            ImportStatus.UNKNOWN_FROM_NEWER,
            SettingsImportPlanner(listOf(currentV2), 2).plan(newerType, current).entries.single().status
        )

        val olderType = document(1, listOf(
            BackupSetting(currentV2.id, 1, true, SettingValue.Text("old-type"))
        ))
        assertEquals(
            ImportStatus.MANUAL_REQUIRED,
            SettingsImportPlanner(listOf(currentV2), 2).plan(olderType, current).entries.single().status
        )
    }

    @Test
    fun `invalid values and missing complete records are reported without writes`() {
        val levelSpec = SettingSpec(
            id = "comments.level",
            storageKey = "comments_level",
            labelRes = 0,
            type = SettingValueType.INTEGER,
            defaultValue = SettingValue.IntValue(3),
            integerRange = 1..6
        )
        val catalog = listOf(levelSpec, enabledSpec)
        val current = snapshot(
            levelSpec to StoredSetting(false, SettingValue.IntValue(3)),
            enabledSpec to StoredSetting(false, SettingValue.Bool(false))
        )
        val source = document(1, listOf(
            record(levelSpec, true, SettingValue.IntValue(9))
        ))
        val plan = SettingsImportPlanner(catalog, 1).plan(source, current)

        assertEquals(ImportStatus.INVALID_VALUE, plan.entries.first { it.id == levelSpec.id }.status)
        assertEquals(ImportStatus.MISSING_FROM_SOURCE, plan.entries.first { it.id == enabledSpec.id }.status)
        assertFalse(plan.canApply)
    }

    @Test
    fun `missing record in a declared partial scope preserves the current value`() {
        val current = snapshot(enabledSpec to StoredSetting(true, SettingValue.Bool(true)))
        val source = document(1, emptyList()).copy(
            scope = BackupScope(SettingsCatalog.SCOPE_ID, complete = false, recordCount = 0)
        )
        val plan = SettingsImportPlanner(listOf(enabledSpec), 1).plan(source, current)

        assertEquals(ImportStatus.SOURCE_DEFAULT_SKIPPED, plan.entries.single().status)
        assertFalse(plan.canApply)
    }

    @Test
    fun `explicit catalog migration can rename a setting`() {
        val renamed = boolSpec("feature.renamed", introducedCatalogVersion = 2)
        val migration = object : CatalogMigration {
            override val fromVersion: Int = 1
            override val toVersion: Int = 2
            override fun migrate(records: List<BackupSetting>) = MigrationStepResult(
                records = records.map {
                    if (it.id == "feature.old") it.copy(id = renamed.id) else it
                },
                notesByResultId = mapOf(renamed.id to listOf("renamed-from:feature.old"))
            )
        }
        val source = document(1, listOf(
            BackupSetting("feature.old", 1, true, SettingValue.Bool(true))
        ))
        val current = snapshot(renamed to StoredSetting(false, SettingValue.Bool(false)))
        val plan = SettingsImportPlanner(
            catalog = listOf(renamed),
            currentCatalogVersion = 2,
            migrations = SettingsMigrationRegistry(listOf(migration))
        ).plan(source, current)

        assertEquals(ImportStatus.MIGRATED, plan.entries.single().status)
        assertTrue(plan.canApply)
    }

    @Test
    fun `missing migration still restores a self describing safe subset`() {
        val current = snapshot(enabledSpec to StoredSetting(false, SettingValue.Bool(false)))
        val source = document(1, listOf(record(enabledSpec, true, SettingValue.Bool(true))))
        val plan = SettingsImportPlanner(
            catalog = listOf(enabledSpec),
            currentCatalogVersion = 3,
            migrations = SettingsMigrationRegistry()
        ).plan(source, current)

        assertEquals(ImportStatus.EXACT, plan.entries.single().status)
        assertTrue(plan.canApply)
        assertTrue(plan.migrationWarnings.isNotEmpty())
    }

    @Test
    fun `current fingerprint distinguishes malformed legacy utf16 code units`() {
        val textSpec = SettingSpec(
            id = "feature.text",
            storageKey = "feature_text",
            labelRes = 0,
            type = SettingValueType.STRING,
            defaultValue = SettingValue.Text("")
        )
        val first = snapshot(textSpec to StoredSetting(true, SettingValue.Text("\uD800")))
        val second = snapshot(textSpec to StoredSetting(true, SettingValue.Text("\uD801")))

        assertFalse(
            SettingsFingerprint.create(first, listOf(textSpec)) ==
                SettingsFingerprint.create(second, listOf(textSpec))
        )
    }

    private fun boolSpec(
        id: String,
        default: Boolean = false,
        introducedCatalogVersion: Int = 1,
        restorePolicy: RestorePolicy = RestorePolicy.AUTOMATIC
    ) = SettingSpec(
        id = id,
        storageKey = id.replace('.', '_'),
        labelRes = 0,
        type = SettingValueType.BOOLEAN,
        defaultValue = SettingValue.Bool(default),
        introducedCatalogVersion = introducedCatalogVersion,
        restorePolicy = restorePolicy
    )

    private fun record(
        spec: SettingSpec,
        explicit: Boolean,
        value: SettingValue
    ) = BackupSetting(spec.id, spec.valueVersion, explicit, value)

    private fun snapshot(vararg values: Pair<SettingSpec, StoredSetting>) = SettingsSnapshot(
        values.associate { (spec, stored) -> spec.id to stored }
    )

    private fun document(
        catalogVersion: Int,
        records: List<BackupSetting>
    ) = SettingsBackupDocument(
        productId = SettingsCatalog.PRODUCT_ID,
        formatVersion = SettingsBackupCodec.CURRENT_FORMAT_VERSION,
        catalogVersion = catalogVersion,
        createdAtEpochMs = 1L,
        source = BackupSource("1.0.0", 1, "test.application"),
        scope = BackupScope(SettingsCatalog.SCOPE_ID, complete = true, recordCount = records.size),
        settings = records
    )
}
