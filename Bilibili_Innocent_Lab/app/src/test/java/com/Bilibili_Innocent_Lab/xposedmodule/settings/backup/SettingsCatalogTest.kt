package com.Bilibili_Innocent_Lab.xposedmodule.settings.backup

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogTest {

    @Test
    fun `catalog is a unique allowlist with 85 settings`() {
        assertEquals(85, SettingsCatalog.specs.size)
        assertEquals(85, SettingsCatalog.specs.map { it.id }.distinct().size)
        assertEquals(85, SettingsCatalog.specs.map { it.storageKey }.distinct().size)
        assertEquals(84, SettingsCatalog.specs.count { it.restorePolicy == RestorePolicy.AUTOMATIC })
        assertEquals(1, SettingsCatalog.specs.count { it.restorePolicy == RestorePolicy.MANUAL })
        assertTrue(SettingsCatalog.specs.all { it.accepts(it.defaultValue) })
        assertTrue(SettingsCatalog.specs.all { it.id.matches(Regex("[a-z0-9][a-z0-9._-]{0,127}")) })
    }

    @Test
    fun `catalog v1 logical ids remain locked by a golden fixture`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v1.txt")
        ).bufferedReader().useLines { lines ->
            lines.map(String::trim).filter(String::isNotEmpty).toList()
        }
        assertEquals(
            expected,
            SettingsCatalog.specs
                .filter { it.introducedCatalogVersion <= 1 }
                .map { it.id }
                .sorted()
        )
    }

    @Test
    fun `catalog v2 logical ids remain locked by a golden fixture`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v2.txt")
        ).bufferedReader().useLines { lines ->
            lines.map(String::trim).filter(String::isNotEmpty).toList()
        }
        assertEquals(
            expected,
            SettingsCatalog.specs
                .filter { it.introducedCatalogVersion <= 2 }
                .map { it.id }
                .sorted()
        )
    }

    @Test
    fun `catalog v3 logical ids remain locked by a golden fixture`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v3.txt")
        ).bufferedReader().useLines { lines ->
            lines.map(String::trim).filter(String::isNotEmpty).toList()
        }
        assertEquals(
            expected,
            SettingsCatalog.specs
                .filter { it.introducedCatalogVersion <= 3 }
                .map { it.id }
                .sorted()
        )
    }

    @Test
    fun `catalog v4 logical ids remain locked by a golden fixture`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v4.txt")
        ).bufferedReader().useLines { lines ->
            lines.map(String::trim).filter(String::isNotEmpty).toList()
        }
        assertEquals(
            expected,
            SettingsCatalog.specs
                .filter { it.introducedCatalogVersion <= 4 }
                .map { it.id }
                .sorted()
        )
    }

    @Test
    fun `catalog v5 logical ids remain locked by a golden fixture`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v5.txt")
        ).bufferedReader().useLines { lines ->
            lines.map(String::trim).filter(String::isNotEmpty).toList()
        }
        assertEquals(
            expected,
            SettingsCatalog.specs
                .filter { it.introducedCatalogVersion <= 5 }
                .map { it.id }
                .sorted()
        )
    }

    @Test
    fun `catalog v6 logical ids remain locked by a golden fixture`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v6.txt")
        ).bufferedReader().useLines { lines ->
            lines.map(String::trim).filter(String::isNotEmpty).toList()
        }
        assertEquals(
            expected,
            SettingsCatalog.specs
                .filter { it.introducedCatalogVersion <= 6 }
                .map { it.id }
                .sorted()
        )
    }

    @Test
    fun `catalog v9 logical ids remain locked by a golden fixture`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v9.txt")
        ).bufferedReader().useLines { lines ->
            lines.map(String::trim).filter(String::isNotEmpty).toList()
        }
        assertEquals(expected, SettingsCatalog.specs.map { it.id }.sorted())
    }

    @Test
    fun `catalog types and manual roaming boundary are explicit`() {
        assertEquals(67, SettingsCatalog.specs.count { it.type == SettingValueType.BOOLEAN })
        assertEquals(4, SettingsCatalog.specs.count { it.type == SettingValueType.INTEGER })
        assertEquals(14, SettingsCatalog.specs.count { it.type == SettingValueType.STRING })

        val roaming = requireNotNull(SettingsCatalog.byId["compat.roaming.enabled"])
        assertEquals(RestorePolicy.MANUAL, roaming.restorePolicy)
        assertTrue(roaming.effects.isEmpty())
    }

    @Test
    fun `derived metadata and runtime sentinels are excluded`() {
        val keys = SettingsCatalog.specs.mapTo(hashSetOf()) { it.storageKey }
        assertFalse(HookEntry.PREF_FREE_COPY_CONFIG_REVISION in keys)
        assertFalse(HookEntry.PREF_PREFS_ALIVE_TS in keys)
        assertFalse("adapt_reset_ts" in keys)
        assertFalse("update_channel" in keys)
        assertFalse("selection_tag" in keys)
    }

    @Test
    fun `restricted values reject unsupported data`() {
        val quality = requireNotNull(SettingsCatalog.byId["player.default_quality.qn"])
        assertTrue(quality.accepts(SettingValue.IntValue(127)))
        assertFalse(quality.accepts(SettingValue.IntValue(999)))

        val level = requireNotNull(SettingsCatalog.byId["comments.minimum_level_filter.level"])
        assertTrue(level.accepts(SettingValue.IntValue(1)))
        assertTrue(level.accepts(SettingValue.IntValue(6)))
        assertFalse(level.accepts(SettingValue.IntValue(0)))

        listOf(
            SettingsCatalog.ID_RECOMMEND_VIDEO_MIN_DURATION,
            SettingsCatalog.ID_RECOMMEND_VIDEO_MAX_DURATION
        ).forEach { id ->
            val duration = requireNotNull(SettingsCatalog.byId[id])
            assertEquals(2, duration.introducedCatalogVersion)
            assertEquals(SettingValue.IntValue(0), duration.defaultValue)
            assertTrue(duration.accepts(SettingValue.IntValue(0)))
            assertTrue(duration.accepts(SettingValue.IntValue(Int.MAX_VALUE)))
            assertFalse(duration.accepts(SettingValue.IntValue(-1)))
        }

        val logLevel = requireNotNull(SettingsCatalog.byId["diagnostics.logging.level"])
        assertTrue(logLevel.accepts(SettingValue.Text(HookEntry.LOG_LEVEL_MINIMAL)))
        assertTrue(logLevel.accepts(SettingValue.Text(HookEntry.LOG_LEVEL_COMPLETE)))
        assertFalse(logLevel.accepts(SettingValue.Text("verbose")))

        val materialColorSpec = requireNotNull(
            SettingsCatalog.byId[SettingsCatalog.ID_MATERIAL_COLOR_SPEC]
        )
        assertEquals(4, materialColorSpec.introducedCatalogVersion)
        assertEquals(SettingValue.Text("2021"), materialColorSpec.defaultValue)
        assertEquals(setOf("2021", "2025"), materialColorSpec.allowedStrings)
        assertTrue(materialColorSpec.accepts(SettingValue.Text("2021")))
        assertTrue(materialColorSpec.accepts(SettingValue.Text("2025")))
        assertFalse(materialColorSpec.accepts(SettingValue.Text("2026")))

        val reasonKeywords = requireNotNull(
            SettingsCatalog.byId["video.related.reason_filter.keywords"]
        )
        assertEquals(5, reasonKeywords.introducedCatalogVersion)
        assertEquals(4_096, reasonKeywords.maxStringLength)
        assertTrue(reasonKeywords.accepts(SettingValue.Text("a".repeat(4_096))))
        assertFalse(reasonKeywords.accepts(SettingValue.Text("a".repeat(4_097))))

        val strongMode = requireNotNull(
            SettingsCatalog.byId["video.related.strong_mode.enabled"]
        )
        assertEquals(6, strongMode.introducedCatalogVersion)
        assertEquals(SettingValue.Bool(false), strongMode.defaultValue)
    }

    @Test
    fun `legacy constrained values export with the same normalization as the ui`() {
        val quality = requireNotNull(SettingsCatalog.byId["player.default_quality.qn"])
        assertEquals(SettingValue.IntValue(0), quality.normalizeForBackup(SettingValue.IntValue(999)))

        val level = requireNotNull(SettingsCatalog.byId["comments.minimum_level_filter.level"])
        assertEquals(SettingValue.IntValue(1), level.normalizeForBackup(SettingValue.IntValue(-5)))
        assertEquals(SettingValue.IntValue(6), level.normalizeForBackup(SettingValue.IntValue(99)))

        val logLevel = requireNotNull(SettingsCatalog.byId["diagnostics.logging.level"])
        assertEquals(
            SettingValue.Text(HookEntry.LOG_LEVEL_COMPLETE),
            logLevel.normalizeForBackup(SettingValue.Text("legacy-verbose"))
        )
    }
}
