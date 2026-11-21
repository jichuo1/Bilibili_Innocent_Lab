package com.Bilibili_Innocent_Lab.xposedmodule.settings.backup

import org.junit.Assert.*
import org.junit.Test

class HostCardMaterialMigrationTest {
    fun `import planner restores explicit legacy choices and skips defaults and conflicting records`() {
        val spec = SettingsCatalog.byId.getValue("host.cards.material")
        val snapshot = SettingsSnapshot(mapOf(spec.id to StoredSetting(true, SettingValue.Text("mica"))))
        fun plan(records: List<BackupSetting>) = SettingsImportPlanner(catalog = listOf(spec)).plan(
            SettingsBackupDocument(SettingsCatalog.PRODUCT_ID, SettingsBackupCodec.CURRENT_FORMAT_VERSION,
                54, 1L, BackupSource("old", 1, "module"), BackupScope(SettingsCatalog.SCOPE_ID, true, records.size), records),
            snapshot)
        val enabled = BackupSetting("host.cards.acrylic.enabled", 1, true, SettingValue.Bool(true))
        assertEquals(SettingValue.Text("acrylic"), plan(listOf(enabled)).writes.single().value)
        assertEquals(ImportStatus.MIGRATED, plan(listOf(enabled)).entries.single().status)
        val disabled = enabled.copy(value = SettingValue.Bool(false))
        assertEquals(SettingValue.Text("original"), plan(listOf(disabled)).writes.single().value)
        val implicit = plan(listOf(disabled.copy(explicit = false)))
        assertEquals(ImportStatus.SOURCE_DEFAULT_SKIPPED, implicit.entries.single().status)
        assertTrue(implicit.writes.isEmpty())
        val duplicate = plan(listOf(enabled, disabled))
        assertEquals(ImportStatus.CONFLICT, duplicate.entries.single().status)
        assertTrue(duplicate.writes.isEmpty())
        val invalid = plan(listOf(enabled.copy(value = SettingValue.Text("true"))))
        assertEquals(ImportStatus.INVALID_VALUE, invalid.entries.single().status)
        assertTrue(invalid.writes.isEmpty())
    }

    fun `legacy backup choices retain explicitness and become material values`() {
        for (enabled in listOf(false, true)) for (explicit in listOf(false, true)) {
            val record = BackupSetting("host.cards.acrylic.enabled", 1, explicit, SettingValue.Bool(enabled))
            val migrated = SettingsCatalogMigrations.current.migrate(54, 55, listOf(record)) as MigrationRun.Success
            assertEquals(listOf(BackupSetting("host.cards.material", 1, explicit,
                SettingValue.Text(if (enabled) "acrylic" else "original"))), migrated.records)
            assertTrue(migrated.notesByResultId.containsKey("host.cards.material"))
        }
    }

    fun `old backups reach migration while missing settings and unrelated values stay unchanged`() {
        val other = BackupSetting("host.video_cards.enabled", 1, true, SettingValue.Bool(true))
        val old = BackupSetting("host.cards.acrylic.enabled", 1, true, SettingValue.Bool(true))
        val result = SettingsCatalogMigrations.current.migrate(10, 55, listOf(other, old)) as MigrationRun.Success
        assertEquals(other, result.records.first())
        assertEquals(SettingValue.Text("acrylic"), result.records.last().value)
        val absent = SettingsCatalogMigrations.current.migrate(54, 55, listOf(other)) as MigrationRun.Success
        assertEquals(listOf(other), absent.records)
        assertTrue(absent.notesByResultId.isEmpty())
    }

    fun `invalid and duplicate legacy records remain rejectable by planner`() {
        val malformed = BackupSetting("host.cards.acrylic.enabled", 1, true, SettingValue.Text("true"))
        val future = BackupSetting("host.cards.acrylic.enabled", 2, true, SettingValue.Bool(true))
        val result = SettingsCatalogMigrations.current.migrate(54, 55, listOf(malformed, future)) as MigrationRun.Success
        assertEquals(2, result.records.size)
        assertEquals(SettingValue.Text("true"), result.records[0].value)
        assertEquals(2, result.records[1].valueVersion)
        assertEquals(SettingValue.Bool(true), result.records[1].value)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostCardMaterialMigrationTest) -> Unit) {
            val s = HostCardMaterialMigrationTest()
            block(s)
        }
        runIsolated { s -> s.`import planner restores explicit legacy choices and skips defaults and conflicting records`() }
        runIsolated { s -> s.`legacy backup choices retain explicitness and become material values`() }
        runIsolated { s -> s.`old backups reach migration while missing settings and unrelated values stay unchanged`() }
        runIsolated { s -> s.`invalid and duplicate legacy records remain rejectable by planner`() }
    }
}
