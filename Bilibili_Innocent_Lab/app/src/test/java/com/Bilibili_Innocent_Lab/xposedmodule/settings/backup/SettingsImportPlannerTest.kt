package com.Bilibili_Innocent_Lab.xposedmodule.settings.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsImportPlannerTest {
    fun `v76 backups preserve a saved strong mode choice and v77 can restore it`() {
        val spec = SettingsCatalog.byId.getValue(SettingsCatalog.ID_COMMENT_FISH_STRONG_MODE)
        val current = snapshot(spec to StoredSetting(true, SettingValue.Bool(true)))
        val planner = SettingsImportPlanner(listOf(spec), SettingsCatalog.CATALOG_VERSION)
        val older = planner.plan(document(76, emptyList()), current)
        assertEquals(ImportStatus.NEW_IN_CURRENT, older.entries.single().status)
        assertTrue(older.writes.isEmpty())

        val restored = planner.plan(document(77, listOf(record(spec, true, SettingValue.Bool(false)))), current)
        assertEquals(ImportStatus.EXACT, restored.entries.single().status)
        assertEquals(SettingValue.Bool(false), restored.writes.single().value)
    }

    fun `older backups retain disabled mine dimming and current backups restore both choices`() {
        val spec = SettingsCatalog.byId.getValue("host.mine.garb_dim.enabled")
        val current = snapshot(spec to StoredSetting(true, SettingValue.Bool(false)))
        val planner = SettingsImportPlanner(listOf(spec), 65)
        for (version in 1..64) {
            val older = planner.plan(document(version, emptyList()), current)
            assertEquals(ImportStatus.NEW_IN_CURRENT, older.entries.single().status)
            assertTrue(older.writes.isEmpty())
        }
        for (enabled in listOf(false, true)) {
            val before = snapshot(spec to StoredSetting(true, SettingValue.Bool(!enabled)))
            val restored = planner.plan(document(65, listOf(record(spec, true, SettingValue.Bool(enabled)))), before)
            assertEquals(ImportStatus.EXACT, restored.entries.single().status)
            assertEquals(SettingValue.Bool(enabled), restored.writes.single().value)
        }
    }

    fun `older backups preserve a confirmed interpolation thermal choice`() {
        val spec = SettingsCatalog.byId.getValue("player.frame_interpolation.disable_thermal_control")
        val current = snapshot(spec to StoredSetting(true, SettingValue.Bool(true)))
        val planner = SettingsImportPlanner(listOf(spec), 64)
        for (version in 1..63) {
            val older = planner.plan(document(version, emptyList()), current)
            assertEquals(ImportStatus.NEW_IN_CURRENT, older.entries.single().status)
            assertTrue(older.writes.isEmpty())
        }
        val restored = planner.plan(document(64, listOf(record(spec, true, SettingValue.Bool(false)))), current)
        assertEquals(ImportStatus.EXACT, restored.entries.single().status)
        assertEquals(SettingValue.Bool(false), restored.writes.single().value)
    }

    fun `older backups preserve reply button visibility intent and current backups restore it`() {
        val spec = SettingsCatalog.byId.getValue("comments.reply_button.removed")
        val current = snapshot(spec to StoredSetting(true, SettingValue.Bool(true)))
        val planner = SettingsImportPlanner(listOf(spec), 53)
        for (version in 1..52) {
            val older = planner.plan(document(version, emptyList()), current)
            assertEquals(ImportStatus.NEW_IN_CURRENT, older.entries.single().status)
            assertTrue(older.writes.isEmpty())
        }
        val restored = planner.plan(document(53, listOf(record(spec, true, SettingValue.Bool(false)))), current)
        assertEquals(ImportStatus.EXACT, restored.entries.single().status)
        assertEquals(SettingValue.Bool(false), restored.writes.single().value)
    }

    fun `older backups retain an existing experimental buffer strategy`() {
        val specs = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 51 }
        val current = snapshot(*specs.map { spec ->
            spec to StoredSetting(true, when (spec.id) {
                "player.buffer.mode" -> SettingValue.IntValue(2)
                "player.buffer.seconds" -> SettingValue.IntValue(90)
                "player.buffer.memory_mb" -> SettingValue.IntValue(128)
                "player.buffer.disk_mb" -> SettingValue.IntValue(2048)
                else -> SettingValue.Bool(false)
            })
        }.toTypedArray())
        for (version in 1..50) {
            val plan = SettingsImportPlanner(specs, 51).plan(document(version, emptyList()), current)
            assertEquals(5, plan.entries.size)
            assertTrue(plan.entries.all { it.status == ImportStatus.NEW_IN_CURRENT })
            assertTrue(plan.writes.isEmpty())
        }
    }

    fun `next v46 classic comment intent survives merged catalog v48`() {
        val spec = SettingsCatalog.byId.getValue("comments.classic_style.enabled")
        val current = snapshot(spec to StoredSetting(false, SettingValue.Bool(false)))
        val source = document(46, listOf(record(spec, true, SettingValue.Bool(true))))
        val plan = SettingsImportPlanner(listOf(spec), 48).plan(source, current)
        assertEquals(ImportStatus.EXACT, plan.entries.single().status)
        assertEquals(SettingValue.Bool(true), plan.writes.single().value)
        val missing = SettingsImportPlanner(listOf(spec), 48).plan(document(47, emptyList()), current)
        assertEquals(ImportStatus.NEW_IN_CURRENT, missing.entries.single().status)
        assertTrue(missing.writes.isEmpty())
    }

    fun `older backups preserve all four newly added source configurations`() {
        val specs = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 46 }
        val current = snapshot(*specs.map { spec ->
            spec to StoredSetting(true, SettingValue.Text(when {
                spec.id.endsWith(".provider") -> "openai"
                spec.id.endsWith(".endpoint") -> "https://api.example.com/v1"
                else -> "existing-model"
            }))
        }.toTypedArray())
        for (version in 1..45) {
            val plan = SettingsImportPlanner(specs, 47).plan(document(version, emptyList()), current)
            assertEquals(12, plan.entries.size)
            assertTrue(plan.entries.all { it.status == ImportStatus.NEW_IN_CURRENT })
            assertTrue(plan.writes.isEmpty())
        }
    }

    fun `Advanced v45 and v46 source records survive catalog version reconciliation`() {
        val specs = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 46 }
        val current = snapshot(*specs.map { it to StoredSetting(false, it.defaultValue) }.toTypedArray())
        val records = specs.map { spec -> record(spec, true, SettingValue.Text(when {
            spec.id.endsWith(".provider") -> "openai"
            spec.id.endsWith(".endpoint") -> "https://api.example.com/v1"
            else -> "restored-model"
        })) }
        for (version in 45..46) {
            val plan = SettingsImportPlanner(specs, 47).plan(document(version, records), current)
            assertEquals(12, plan.writes.size)
            assertTrue(plan.entries.all { it.status == ImportStatus.EXACT })
            assertEquals(records.map { it.value }, plan.writes.map { it.value })
        }
    }

    fun `Advanced v46 Agent permission remains manual after reconciliation`() {
        val spec = SettingsCatalog.byId.getValue("agent.enabled")
        val source = document(46, listOf(record(spec, true, SettingValue.Bool(true))))
        val current = snapshot(spec to StoredSetting(false, SettingValue.Bool(false)))
        val plan = SettingsImportPlanner(listOf(spec), 47).plan(source, current)
        assertEquals(ImportStatus.MANUAL_REQUIRED, plan.entries.single().status)
        assertFalse(plan.entries.single().willWrite)
        assertTrue(plan.writes.isEmpty())
    }

    fun `old backups preserve search home preference`() {
        val spec = SettingsCatalog.byId.getValue("search.home_recommend.hidden")
        val current = snapshot(spec to StoredSetting(true,SettingValue.Bool(true)))
        for (version in 1..15) {
            val plan = SettingsImportPlanner(listOf(spec),16).plan(document(version,emptyList()),current)
            assertEquals(ImportStatus.NEW_IN_CURRENT,plan.entries.single().status)
            assertTrue(plan.writes.isEmpty())
        }
    }
    fun `older backups preserve frequent visits choice`() {
        val spec = SettingsCatalog.byId.getValue("dynamic.frequent_visits.hidden")
        val current = snapshot(spec to StoredSetting(true, SettingValue.Bool(true)))
        for (version in 1..14) {
            val plan = SettingsImportPlanner(listOf(spec), 15).plan(document(version, emptyList()), current)
            assertEquals(ImportStatus.NEW_IN_CURRENT, plan.entries.single().status)
            assertTrue(plan.writes.isEmpty())
        }
    }

    fun `old backups preserve both new homepage filter choices`() {
        val specs = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 14 }
        assertEquals(2, specs.size)
        val current = snapshot(*specs.map { it to StoredSetting(true, SettingValue.Bool(true)) }.toTypedArray())
        for (version in 1..13) {
            val plan = SettingsImportPlanner(specs, 14).plan(document(version, emptyList()), current)
            assertTrue(plan.entries.all { it.status == ImportStatus.NEW_IN_CURRENT })
            assertTrue(plan.writes.isEmpty())
        }
    }

    private val enabledSpec = boolSpec("feature.enabled", default = false)
    private val manualSpec = boolSpec(
        "feature.manual",
        default = false,
        restorePolicy = RestorePolicy.MANUAL
    )

    fun `all older backups preserve the six new player settings`() {
        val specs = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 13 }
        val current = snapshot(*specs.map { spec ->
            spec to StoredSetting(true, if (spec.type == SettingValueType.BOOLEAN) SettingValue.Bool(true)
                else SettingValue.IntValue(275))
        }.toTypedArray())
        for (version in 1..12) {
            val plan = SettingsImportPlanner(specs, 13).plan(document(version, emptyList()), current)
            assertEquals(6, plan.entries.size)
            assertTrue(plan.entries.all { it.status == ImportStatus.NEW_IN_CURRENT })
            assertTrue(plan.writes.isEmpty())
        }
    }

    fun `every older catalog preserves an already enabled PGC popup setting`() {
        val spec = requireNotNull(SettingsCatalog.byId["pgc.auto_activity_popup.hidden"])
        val current = snapshot(spec to StoredSetting(true, SettingValue.Bool(true)))
        for (version in 1..9) {
            val plan = SettingsImportPlanner(listOf(spec), 10)
                .plan(document(version, emptyList()), current)
            assertEquals(ImportStatus.NEW_IN_CURRENT, plan.entries.single().status)
            assertEquals(SettingValue.Bool(true), plan.entries.single().current?.value)
            assertTrue(plan.writes.isEmpty())
            assertTrue(plan.migrationWarnings.isEmpty())
        }
    }

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

    fun `explicit intent is written even when value equals current implicit default`() {
        val current = snapshot(enabledSpec to StoredSetting(false, SettingValue.Bool(false)))
        val source = document(1, listOf(record(enabledSpec, true, SettingValue.Bool(false))))
        val plan = SettingsImportPlanner(listOf(enabledSpec), 1).plan(source, current)

        assertEquals(ImportStatus.EXACT, plan.entries.single().status)
        assertTrue(plan.entries.single().willWrite)
    }

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

    fun `reversed imported play count range is visible and never written`() {
        val minSpec = requireNotNull(
            SettingsCatalog.byId[SettingsCatalog.ID_RECOMMEND_VIDEO_MIN_PLAY_COUNT]
        )
        val maxSpec = requireNotNull(
            SettingsCatalog.byId[SettingsCatalog.ID_RECOMMEND_VIDEO_MAX_PLAY_COUNT]
        )
        val catalog = listOf(minSpec, maxSpec)
        val current = snapshot(
            minSpec to StoredSetting(explicit = true, SettingValue.IntValue(1_000)),
            maxSpec to StoredSetting(explicit = true, SettingValue.IntValue(10_000))
        )
        val source = document(
            catalogVersion = 27,
            records = listOf(
                record(minSpec, explicit = true, SettingValue.IntValue(20_000)),
                record(maxSpec, explicit = true, SettingValue.IntValue(5_000))
            )
        )

        val plan = SettingsImportPlanner(catalog, 27).plan(source, current)

        assertEquals(2, plan.entries.size)
        assertTrue(plan.entries.all { it.status == ImportStatus.INVALID_VALUE })
        assertTrue(plan.entries.none { it.willWrite })
        assertFalse(plan.canApply)
    }

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

    fun `missing record in a declared partial scope preserves the current value`() {
        val current = snapshot(enabledSpec to StoredSetting(true, SettingValue.Bool(true)))
        val source = document(1, emptyList()).copy(
            scope = BackupScope(SettingsCatalog.SCOPE_ID, complete = false, recordCount = 0)
        )
        val plan = SettingsImportPlanner(listOf(enabledSpec), 1).plan(source, current)

        assertEquals(ImportStatus.SOURCE_DEFAULT_SKIPPED, plan.entries.single().status)
        assertFalse(plan.canApply)
    }

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

    fun `every catalog version from v1 through current minus one migrates to current without missing warnings`() {
        val v1Spec = SettingsCatalog.byId.getValue("ads.pause.hidden")
        val fullCurrent = snapshot(
            *SettingsCatalog.specs.map { spec ->
                val value = if (spec.id == v1Spec.id) SettingValue.Bool(false) else spec.defaultValue
                spec to StoredSetting(explicit = true, value = value)
            }.toTypedArray()
        )
        val planner = SettingsImportPlanner()
        val v1Record = record(v1Spec, explicit = true, SettingValue.Bool(true))
        for (version in 1 until SettingsCatalog.CATALOG_VERSION) {
            val migrationRun = SettingsCatalogMigrations.current.migrate(
                sourceVersion = version,
                targetVersion = SettingsCatalog.CATALOG_VERSION,
                sourceRecords = listOf(v1Record)
            ) as MigrationRun.Success
            assertEquals("Unexpected warnings for migration v$version -> v${SettingsCatalog.CATALOG_VERSION}", emptyList<String>(), migrationRun.warnings)

            val plan = planner.plan(document(version, listOf(v1Record)), fullCurrent)
            assertTrue("Unexpected blockers for v$version", plan.blockers.isEmpty())
            assertEquals("Unexpected migration warnings for v$version", emptyList<String>(), plan.migrationWarnings)
            assertEquals(ImportStatus.EXACT, plan.entries.first { it.id == v1Spec.id }.status)
            assertEquals(SettingValue.Bool(true), plan.writes.single().value)
            val expectedNewCount = SettingsCatalog.specs.count { it.introducedCatalogVersion > version }
            assertEquals(expectedNewCount, plan.count(ImportStatus.NEW_IN_CURRENT))
        }
    }

    fun `duplicate ids and out of order records preserve planning semantics`() {
        val intSpec = SettingSpec(
            id = "feature.count",
            storageKey = "feature_count",
            labelRes = 0,
            type = SettingValueType.INTEGER,
            defaultValue = SettingValue.IntValue(1),
            integerRange = 1..10
        )
        val textSpec = SettingSpec(
            id = "feature.label",
            storageKey = "feature_label",
            labelRes = 0,
            type = SettingValueType.STRING,
            defaultValue = SettingValue.Text("default")
        )
        val catalog = listOf(enabledSpec, manualSpec, intSpec, textSpec)
        val current = snapshot(
            enabledSpec to StoredSetting(explicit = true, SettingValue.Bool(false)),
            manualSpec to StoredSetting(explicit = true, SettingValue.Bool(false)),
            intSpec to StoredSetting(explicit = true, SettingValue.IntValue(1)),
            textSpec to StoredSetting(explicit = true, SettingValue.Text("default"))
        )
        val planner = SettingsImportPlanner(catalog = catalog, currentCatalogVersion = 10)
        val rEnabled = record(enabledSpec, true, SettingValue.Bool(true))
        val rManual = record(manualSpec, true, SettingValue.Bool(true))
        val rInt = record(intSpec, true, SettingValue.IntValue(7))
        val rText = record(textSpec, true, SettingValue.Text("custom"))
        val unknown1 = BackupSetting("unknown.first", 1, true, SettingValue.Bool(true))
        val unknown2 = BackupSetting("unknown.second", 1, true, SettingValue.Text("x"))

        val canonical = planner.plan(
            document(10, listOf(rEnabled, rManual, rInt, rText, unknown1, unknown2)),
            current
        )
        val reordered = planner.plan(
            document(10, listOf(unknown2, rText, rInt, unknown1, rManual, rEnabled)),
            current
        )
        assertEquals(catalog.map(SettingSpec::id), reordered.entries.take(catalog.size).map(ImportPlanEntry::id))
        assertEquals(canonical.entries.take(catalog.size), reordered.entries.take(catalog.size))
        assertEquals(listOf("unknown.second", "unknown.first"), reordered.entries.drop(catalog.size).map(ImportPlanEntry::id))
        assertEquals(listOf(ImportStatus.REMOVED, ImportStatus.REMOVED), reordered.entries.drop(catalog.size).map(ImportPlanEntry::status))
        assertEquals(canonical.writes, reordered.writes)
        assertEquals(canonical.effects, reordered.effects)
        assertEquals(canonical.canApply, reordered.canApply)
        assertEquals(canonical.currentFingerprint, reordered.currentFingerprint)

        // Duplicate known IDs (both differing and identical) must yield CONFLICT; duplicate unknown IDs remain REMOVED.
        val dupIdenticalKnown = record(intSpec, true, SettingValue.IntValue(7))
        val dupUnknown = BackupSetting("unknown.dup", 1, true, SettingValue.Bool(false))
        val duplicatePlan = planner.plan(
            document(
                10,
                listOf(
                    rEnabled,
                    rEnabled.copy(value = SettingValue.Bool(false)),
                    rInt,
                    dupIdenticalKnown,
                    dupUnknown,
                    dupUnknown,
                    rText
                )
            ),
            current
        )
        val dupBoolEntry = duplicatePlan.entries.first { it.id == enabledSpec.id }
        assertEquals(ImportStatus.CONFLICT, dupBoolEntry.status)
        assertEquals(ImportReason.MULTIPLE_SOURCE_RECORDS, dupBoolEntry.reason)
        assertFalse(dupBoolEntry.willWrite)

        val dupIntEntry = duplicatePlan.entries.first { it.id == intSpec.id }
        assertEquals(ImportStatus.CONFLICT, dupIntEntry.status)
        assertEquals(ImportReason.MULTIPLE_SOURCE_RECORDS, dupIntEntry.reason)
        assertFalse(dupIntEntry.willWrite)

        assertEquals(ImportStatus.MISSING_FROM_SOURCE, duplicatePlan.entries.first { it.id == manualSpec.id }.status)
        assertEquals(ImportStatus.EXACT, duplicatePlan.entries.first { it.id == textSpec.id }.status)
        assertEquals(
            listOf("unknown.dup", "unknown.dup"),
            duplicatePlan.entries.drop(catalog.size).map(ImportPlanEntry::id)
        )
        assertEquals(listOf(textSpec.id), duplicatePlan.writes.map { it.spec.id })

        // Duplicate legacy v54 records migrate to v86 and still conflict cleanly without missing migration warnings.
        val materialSpec = SettingsCatalog.byId.getValue("host.cards.material")
        val legacyPlan = SettingsImportPlanner(
            catalog = listOf(materialSpec),
            currentCatalogVersion = SettingsCatalog.CATALOG_VERSION
        ).plan(
            document(
                54,
                listOf(
                    BackupSetting("host.cards.acrylic.enabled", 1, true, SettingValue.Bool(true)),
                    BackupSetting("host.cards.acrylic.enabled", 1, true, SettingValue.Bool(false))
                )
            ),
            snapshot(materialSpec to StoredSetting(true, SettingValue.Text("mica")))
        )
        assertEquals(emptyList<String>(), legacyPlan.migrationWarnings)
        assertEquals(ImportStatus.CONFLICT, legacyPlan.entries.single().status)
        assertEquals(ImportReason.MULTIPLE_SOURCE_RECORDS, legacyPlan.entries.single().reason)
        assertTrue(legacyPlan.writes.isEmpty())
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

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (SettingsImportPlannerTest) -> Unit) {
            val s = SettingsImportPlannerTest()
            block(s)
        }
        runIsolated { s -> s.`v76 backups preserve a saved strong mode choice and v77 can restore it`() }
        runIsolated { s -> s.`older backups retain disabled mine dimming and current backups restore both choices`() }
        runIsolated { s -> s.`older backups preserve a confirmed interpolation thermal choice`() }
        runIsolated { s -> s.`older backups preserve reply button visibility intent and current backups restore it`() }
        runIsolated { s -> s.`older backups retain an existing experimental buffer strategy`() }
        runIsolated { s -> s.`next v46 classic comment intent survives merged catalog v48`() }
        runIsolated { s -> s.`older backups preserve all four newly added source configurations`() }
        runIsolated { s -> s.`Advanced v45 and v46 source records survive catalog version reconciliation`() }
        runIsolated { s -> s.`Advanced v46 Agent permission remains manual after reconciliation`() }
        runIsolated { s -> s.`old backups preserve search home preference`() }
        runIsolated { s -> s.`older backups preserve frequent visits choice`() }
        runIsolated { s -> s.`old backups preserve both new homepage filter choices`() }
        runIsolated { s -> s.`all older backups preserve the six new player settings`() }
        runIsolated { s -> s.`every older catalog preserves an already enabled PGC popup setting`() }
        runIsolated { s -> s.`explicit source value is restored and implicit source default never overwrites`() }
        runIsolated { s -> s.`explicit intent is written even when value equals current implicit default`() }
        runIsolated { s -> s.`old implicit default does not overwrite a changed current default`() }
        runIsolated { s -> s.`equal explicit values are unchanged and manual values are never written`() }
        runIsolated { s -> s.`new current settings stay at current value and future unknown settings are reported`() }
        runIsolated { s -> s.`catalog v1 import reports both duration boundaries as new current settings`() }
        runIsolated { s -> s.`catalog v2 import reports mine selectors as a new current setting`() }
        runIsolated { s -> s.`reversed imported duration range is visible and never written`() }
        runIsolated { s -> s.`reversed imported play count range is visible and never written`() }
        runIsolated { s -> s.`single imported duration boundary cannot conflict with the kept current boundary`() }
        runIsolated { s -> s.`records from a future catalog and future value semantics are not guessed`() }
        runIsolated { s -> s.`value version is classified before applying the current value schema`() }
        runIsolated { s -> s.`invalid values and missing complete records are reported without writes`() }
        runIsolated { s -> s.`missing record in a declared partial scope preserves the current value`() }
        runIsolated { s -> s.`explicit catalog migration can rename a setting`() }
        runIsolated { s -> s.`missing migration still restores a self describing safe subset`() }
        runIsolated { s -> s.`current fingerprint distinguishes malformed legacy utf16 code units`() }
        runIsolated { s -> s.`every catalog version from v1 through current minus one migrates to current without missing warnings`() }
        runIsolated { s -> s.`duplicate ids and out of order records preserve planning semantics`() }
    }
}
