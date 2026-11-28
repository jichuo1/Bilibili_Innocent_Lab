package com.Bilibili_Innocent_Lab.xposedmodule.settings.backup

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerBufferConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight.InsightConfig
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogTest {
    @Test
    fun `all historical catalog snapshots match introduced versions`() {
        val versions = SettingsCatalogHistoryFixture.versions()
        var verifiedSnapshots = 0
        for (version in versions) {
            if (version >= SettingsCatalog.CATALOG_VERSION) continue
            val expectedIds = SettingsCatalogHistoryFixture.idsAtVersion(version)
            assertEquals(
                "Golden snapshot mismatch for catalog v$version",
                expectedIds,
                SettingsCatalog.specs.filter { it.introducedCatalogVersion <= version }.map { it.id }.sorted()
            )
            verifiedSnapshots++
        }
        assertTrue("Expected at least 69 historical snapshots, verified $verifiedSnapshots", verifiedSnapshots >= 69)
        SettingsCatalog.specs.forEach { spec ->
            assertEquals(spec, SettingsCatalog.byStorageKey[spec.storageKey])
        }
    }

    @Test
    fun `current catalog satisfies global schema security and normalization invariants`() {
        assertEquals(86, SettingsCatalog.CATALOG_VERSION)
        assertEquals(281, SettingsCatalog.specs.size)
        assertEquals(281, SettingsCatalog.specs.map { it.id }.distinct().size)
        assertEquals(281, SettingsCatalog.specs.map { it.storageKey }.distinct().size)
        assertEquals(275, SettingsCatalog.specs.count { it.restorePolicy == RestorePolicy.AUTOMATIC })
        assertEquals(6, SettingsCatalog.specs.count { it.restorePolicy == RestorePolicy.MANUAL })
        assertEquals(174, SettingsCatalog.specs.count { it.type == SettingValueType.BOOLEAN })
        assertEquals(25, SettingsCatalog.specs.count { it.type == SettingValueType.INTEGER })
        assertEquals(82, SettingsCatalog.specs.count { it.type == SettingValueType.STRING })
        assertTrue(SettingsCatalog.specs.all { it.accepts(it.defaultValue) })
        assertTrue(SettingsCatalog.specs.all { it.id.matches(Regex("[a-z0-9][a-z0-9._-]{0,127}")) })

        val roaming = requireNotNull(SettingsCatalog.byId["compat.roaming.enabled"])
        assertEquals(RestorePolicy.MANUAL, roaming.restorePolicy)
        assertTrue(roaming.effects.isEmpty())

        val keys = SettingsCatalog.specs.mapTo(hashSetOf()) { it.storageKey }
        assertFalse(HookEntry.PREF_FREE_COPY_CONFIG_REVISION in keys)
        assertFalse(HookEntry.PREF_PREFS_ALIVE_TS in keys)
        assertFalse("adapt_reset_ts" in keys)
        assertFalse("update_channel" in keys)
        assertFalse("selection_tag" in keys)
        assertFalse(RemoteHookConfigContract.KEY_AI_INSIGHT_API_KEY in keys)
        assertTrue(SettingsCatalog.specs.none { "api_key" in it.id || "api_key" in it.storageKey })

        val quality = requireNotNull(SettingsCatalog.byId["player.default_quality.qn"])
        assertTrue(quality.accepts(SettingValue.IntValue(127)))
        assertFalse(quality.accepts(SettingValue.IntValue(999)))
        assertEquals(SettingValue.IntValue(0), quality.normalizeForBackup(SettingValue.IntValue(999)))

        val level = requireNotNull(SettingsCatalog.byId["comments.minimum_level_filter.level"])
        assertTrue(level.accepts(SettingValue.IntValue(1)))
        assertTrue(level.accepts(SettingValue.IntValue(6)))
        assertFalse(level.accepts(SettingValue.IntValue(0)))
        assertEquals(SettingValue.IntValue(1), level.normalizeForBackup(SettingValue.IntValue(-5)))
        assertEquals(SettingValue.IntValue(6), level.normalizeForBackup(SettingValue.IntValue(99)))

        listOf(
            SettingsCatalog.ID_RECOMMEND_VIDEO_MIN_DURATION,
            SettingsCatalog.ID_RECOMMEND_VIDEO_MAX_DURATION,
            SettingsCatalog.ID_RECOMMEND_VIDEO_MIN_PLAY_COUNT,
            SettingsCatalog.ID_RECOMMEND_VIDEO_MAX_PLAY_COUNT
        ).forEach { id ->
            val metric = requireNotNull(SettingsCatalog.byId[id])
            assertEquals(SettingValue.IntValue(0), metric.defaultValue)
            assertTrue(metric.accepts(SettingValue.IntValue(0)))
            assertTrue(metric.accepts(SettingValue.IntValue(Int.MAX_VALUE)))
            assertFalse(metric.accepts(SettingValue.IntValue(-1)))
        }

        val logLevel = requireNotNull(SettingsCatalog.byId["diagnostics.logging.level"])
        assertTrue(logLevel.accepts(SettingValue.Text(HookEntry.LOG_LEVEL_MINIMAL)))
        assertTrue(logLevel.accepts(SettingValue.Text(HookEntry.LOG_LEVEL_COMPLETE)))
        assertFalse(logLevel.accepts(SettingValue.Text("verbose")))
        assertEquals(SettingValue.Text(HookEntry.LOG_LEVEL_COMPLETE), logLevel.normalizeForBackup(SettingValue.Text("legacy-verbose")))

        val materialColorSpec = requireNotNull(SettingsCatalog.byId[SettingsCatalog.ID_MATERIAL_COLOR_SPEC])
        assertEquals(setOf("2021", "2025"), materialColorSpec.allowedStrings)
        assertFalse(materialColorSpec.accepts(SettingValue.Text("2026")))

        val reasonKeywords = requireNotNull(SettingsCatalog.byId["video.related.reason_filter.keywords"])
        assertEquals(4_096, reasonKeywords.maxStringLength)
        assertTrue(reasonKeywords.accepts(SettingValue.Text("a".repeat(4_096))))
        assertFalse(reasonKeywords.accepts(SettingValue.Text("a".repeat(4_097))))

        val fishSensitivity = requireNotNull(SettingsCatalog.byId[SettingsCatalog.ID_COMMENT_FISH_SENSITIVITY])
        assertEquals(setOf("loose", "standard", "strict"), fishSensitivity.allowedStrings)
        val aiProtocol = requireNotNull(SettingsCatalog.byId["ai_insight.protocol"])
        assertEquals(InsightConfig.IDS, aiProtocol.allowedStrings)
        assertEquals(setOf("jev", "openai", "cloudflare"), SettingsCatalog.byId["compat.semantic_source.3.provider"]?.allowedStrings)

        val bufferMode = requireNotNull(SettingsCatalog.byId["player.buffer.mode"])
        PlayerBufferConfig.supportedModes.forEach { assertTrue(bufferMode.accepts(SettingValue.IntValue(it))) }
        assertFalse(bufferMode.accepts(SettingValue.IntValue(-1)))

        // Preserve incremental contract checks from v84-v86 inside the unified catalog invariant test.
        val added84 = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 84 }.associateBy { it.id }
        assertEquals(setOf("player.frame_interpolation.force_mode", "player.frame_interpolation.mode"), added84.keys)
        val force = added84.getValue("player.frame_interpolation.force_mode")
        assertEquals(FeaturePreferences.PLAYER_FRAME_INTERPOLATION_FORCE_MODE, force.storageKey)
        assertEquals(SettingValue.Bool(false), force.defaultValue)
        assertTrue(ImportEffect.RESTART_BILIBILI in force.effects)
        val mode = added84.getValue("player.frame_interpolation.mode")
        assertEquals(FeaturePreferences.PLAYER_FRAME_INTERPOLATION_MODE, mode.storageKey)
        assertEquals(SettingValue.Text(""), mode.defaultValue)
        assertTrue(ImportEffect.RESTART_BILIBILI in mode.effects)
        assertTrue(mode.accepts(SettingValue.Text("qcom")) && mode.accepts(SettingValue.Text("rife")))
        assertFalse(mode.accepts(SettingValue.Text("invalid")))
        assertEquals(SettingValue.Text(""), mode.normalizeForBackup(SettingValue.Text("invalid")))

        val added85 = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 85 }.associateBy { it.id }
        assertEquals(setOf("system.env_detection.blocked"), added85.keys)
        assertTrue(ImportEffect.RESTART_BILIBILI in added85.getValue("system.env_detection.blocked").effects)

        val added86 = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 86 }.associateBy { it.id }
        assertEquals(setOf(SettingsCatalog.ID_HOST_PREDICTIVE_BACK), added86.keys)
        assertTrue(ImportEffect.RESTART_BILIBILI in added86.getValue(SettingsCatalog.ID_HOST_PREDICTIVE_BACK).effects)
    }
}
