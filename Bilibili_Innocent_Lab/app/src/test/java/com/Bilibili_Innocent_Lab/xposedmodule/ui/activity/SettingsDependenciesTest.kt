package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.ModuleUiSettings
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValue
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import org.junit.Assert.*
import org.junit.Test

class SettingsDependenciesTest {
    @Test fun `every preference dependency uses real catalog identities and boolean parents`() {
        SettingsDependencies.requirements.forEach { (id, requirement) ->
            if (id != SettingsDependencies.FRAME_MODEL) assertNotNull(id, SettingsCatalog.byId[id])
            assertTrue(requirement.key, SettingsCatalog.byStorageKey[requirement.key]?.defaultValue is SettingValue.Bool)
            val parentId = SettingsCatalog.byStorageKey.getValue(requirement.key).id
            assertFalse("A child must not disable its own parent", id == parentId)
        }
    }

    @Test fun `frame options lock while off and restore without discarding saved values`() {
        val off = ModuleUiSettings.of { mapOf(
            FeaturePreferences.PLAYER_FRAME_INTERPOLATION to false,
            FeaturePreferences.PLAYER_FRAME_INTERPOLATION_STATUS_TOAST to true,
            FeaturePreferences.PLAYER_FRAME_INTERPOLATION_MAX_FPS to 144
        ) }
        val ids = listOf("player.frame_interpolation.max_fps", "player.frame_interpolation.status_toast",
            SettingsDependencies.FRAME_MODEL)
        repeat(3) {
            ids.forEach { assertFalse(it, SettingsDependencies.available(it, off)) }
            val on = off.with(FeaturePreferences.PLAYER_FRAME_INTERPOLATION, true)
            ids.forEach { assertTrue(it, SettingsDependencies.available(it, on)) }
            assertTrue(on.bool(FeaturePreferences.PLAYER_FRAME_INTERPOLATION_STATUS_TOAST))
            assertEquals(144, on.int(FeaturePreferences.PLAYER_FRAME_INTERPOLATION_MAX_FPS))
        }
        assertTrue(off.bool(FeaturePreferences.PLAYER_FRAME_INTERPOLATION_STATUS_TOAST))
        assertEquals(144, off.int(FeaturePreferences.PLAYER_FRAME_INTERPOLATION_MAX_FPS))
        assertTrue(SettingsDependencies.available("player.frame_interpolation.enabled", off))
    }

    @Test fun `each filter editor follows its own switch instead of neighbouring filters`() {
        val pairs = listOf(
            FeaturePreferences.COMMENT_KEYWORD_FILTER_ENABLED to "comments.keyword_filter.keywords",
            FeaturePreferences.COMMENT_MIN_LEVEL_FILTER_ENABLED to "comments.minimum_level_filter.level",
            FeaturePreferences.COMMENT_USER_FILTER_ENABLED to "comments.user_filter.rules",
            FeaturePreferences.DANMAKU_WEIGHT_FILTER_ENABLED to "player.danmaku.weight_filter.minimum",
            FeaturePreferences.DYNAMIC_KEYWORD_FILTER_ENABLED to "dynamic.keyword_filter.keywords",
            FeaturePreferences.DYNAMIC_AUTHOR_FILTER_ENABLED to "dynamic.author_filter.rules",
            FeaturePreferences.SEARCH_KEYWORD_FILTER_ENABLED to "search.keyword_filter.keywords",
            FeaturePreferences.SEARCH_AUTHOR_FILTER_ENABLED to "search.author_filter.rules",
            FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_ENABLED to "home.recommend.title_filter.keywords"
        )
        pairs.forEach { (key, id) ->
            val enabled = ModuleUiSettings.EMPTY.with(key, true)
            assertFalse(id, SettingsDependencies.available(id, ModuleUiSettings.EMPTY))
            assertTrue(id, SettingsDependencies.available(id, enabled))
            pairs.filter { it.first != key }.forEach { (_, other) ->
                assertFalse("$id must not unlock $other", SettingsDependencies.available(other, enabled))
            }
        }
    }

    @Test fun `semantic rules custom types and routes follow only the corresponding surface`() {
        val surfaces = listOf(
            FeaturePreferences.COMMENT_SEMANTIC_FILTER_ENABLED to "comments",
            FeaturePreferences.DANMAKU_SEMANTIC_FILTER_ENABLED to "player.danmaku",
            FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_ENABLED to "dynamic",
            FeaturePreferences.VIDEO_SEMANTIC_FILTER_ENABLED to "video"
        )
        surfaces.forEach { (key, prefix) ->
            val settings = ModuleUiSettings.EMPTY.with(key, true)
            surfaces.forEach { (_, other) ->
                listOf("rules", "custom_rules", "source").forEach { suffix ->
                    assertEquals("$other.$suffix", prefix == other,
                        SettingsDependencies.available("$other.semantic_filter.$suffix", settings))
                }
            }
        }
        assertTrue(SettingsDependencies.available(SettingsCatalog.ID_SEMANTIC_JEV_ENDPOINT, ModuleUiSettings.EMPTY))
    }

    @Test fun `sponsor automatic and retained vip space require their own main feature`() {
        listOf(
            FeaturePreferences.SPONSORBLOCK_ENABLED to "player.sponsorblock.automatic",
            FeaturePreferences.HIDE_MINE_VIP to "mine.vip.space_kept"
        ).forEach { (key, id) ->
            assertFalse(id, SettingsDependencies.available(id, ModuleUiSettings.EMPTY))
            assertTrue(id, SettingsDependencies.available(id, ModuleUiSettings.EMPTY.with(key, true)))
        }
    }

    @Test fun `disabling long press locks its speed while default speed remains independent`() {
        val disabled = ModuleUiSettings.EMPTY.with(FeaturePreferences.PLAYER_DISABLE_LONG_PRESS, true)
        assertTrue(SettingsDependencies.available(SettingsCatalog.ID_PLAYER_LONG_PRESS_SPEED, ModuleUiSettings.EMPTY))
        assertFalse(SettingsDependencies.available(SettingsCatalog.ID_PLAYER_LONG_PRESS_SPEED, disabled))
        assertTrue(SettingsDependencies.available(SettingsCatalog.ID_PLAYER_DEFAULT_SPEED, disabled))
        assertTrue(SettingsDependencies.available(SettingsCatalog.ID_PLAYER_SPEED_MENU, disabled))
    }

    @Test fun `logging uses catalog default and does not clear retained detail level`() {
        assertTrue(SettingsDependencies.available("diagnostics.logging.level", ModuleUiSettings.EMPTY))
        val settings = ModuleUiSettings.EMPTY.with(HookEntry.PREF_LOG_ENABLED, false)
            .with(HookEntry.PREF_LOG_LEVEL, HookEntry.LOG_LEVEL_COMPLETE)
        assertFalse(SettingsDependencies.available("diagnostics.logging.level", settings))
        assertEquals(HookEntry.LOG_LEVEL_COMPLETE, settings.string(HookEntry.PREF_LOG_LEVEL))
    }

    @Test fun `unrelated capabilities remain configurable and malformed values use catalog defaults`() {
        listOf("player.subtitle.ai.enabled", "player.subtitle.style.enabled", "share.mini_program.direct_link",
            "host.home_cards.horizontal_dp", "host.home_cards.vertical_dp", "splash.auto_night.enabled").forEach {
            assertTrue(it, SettingsDependencies.available(it, ModuleUiSettings.EMPTY))
        }
        val wrong = ModuleUiSettings.of { mapOf(FeaturePreferences.PLAYER_FRAME_INTERPOLATION to "true") }
        assertFalse(SettingsDependencies.available("player.frame_interpolation.max_fps", wrong))
    }
}
