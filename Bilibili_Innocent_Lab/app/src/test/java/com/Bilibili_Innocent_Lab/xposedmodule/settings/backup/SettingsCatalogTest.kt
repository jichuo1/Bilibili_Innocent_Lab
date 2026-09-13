package com.Bilibili_Innocent_Lab.xposedmodule.settings.backup

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogTest {
    @Test fun `catalog v23 adds the default off player end page filter`() {
        val expected=requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v23.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected,SettingsCatalog.specs.map { it.id }.sorted())
        val added=SettingsCatalog.specs.filter { it.introducedCatalogVersion==23 }.single()
        assertEquals("player.end_page_recommend.hidden",added.id)
        assertEquals(SettingValue.Bool(false),added.defaultValue)
        assertEquals(RestorePolicy.AUTOMATIC,added.restorePolicy)
        assertTrue(ImportEffect.RESTART_BILIBILI in added.effects)
    }

    @Test fun `catalog v21 adds one default off player popup promotion setting`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v21.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 21 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.single { it.introducedCatalogVersion == 21 }
        assertEquals("player.popup_promotion.hidden", added.id)
        assertEquals(SettingValue.Bool(false), added.defaultValue)
        assertEquals(RestorePolicy.AUTOMATIC, added.restorePolicy)
        assertTrue(ImportEffect.RESTART_BILIBILI in added.effects)
    }

    @Test fun `catalog v20 adds the recommendation uploader blocklist`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v20.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 20 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 20 }
        assertEquals(listOf("home.recommend.blocked_authors"), added.map { it.id })
        // 名单是 Text：授权链只搬 Bool/Int/Text，集合型偏好过不了那一层。
        val list = added.single()
        assertEquals(SettingValueType.STRING, list.type)
        assertEquals(SettingValue.Text(""), list.defaultValue)
    }

    @Test fun `catalog v19 adds the recommendation section blocklist`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v19.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        // 只比到 v19：v20 之后新增的条目不属于这份 golden。
        assertEquals(
            expected,
            SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 19 }.map { it.id }.sorted()
        )
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 19 }
        assertEquals(
            listOf(
                "home.recommend.blocked_tids",
                "home.recommend.section_pick.enabled",
                "video.related.blocked_authors",
                "video.related.blocked_tags"
            ),
            added.map { it.id }.sorted()
        )
        // 三条名单是 Text：授权链只搬 Bool/Int/Text，集合型偏好过不了那一层。
        val lists = added.filter { it.type == SettingValueType.STRING }
        assertEquals(3, lists.size)
        lists.forEach { assertEquals(SettingValue.Text(""), it.defaultValue) }
        // 面板劫持改变宿主界面行为，必须默认关。
        val pick = added.single { it.id == "home.recommend.section_pick.enabled" }
        assertEquals(SettingValue.Bool(false), pick.defaultValue)
        added.forEach { assertEquals(RestorePolicy.AUTOMATIC, it.restorePolicy) }
        added.forEach { assertTrue(ImportEffect.RESTART_BILIBILI in it.effects) }
    }

    @Test fun `catalog v18 adds seven default off detail component settings`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v18.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        // 上一版 golden 只能比"截至 v18 的集合"，否则每加一个新设置都会顶红。
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 18 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 18 }
        assertEquals(
            listOf(
                "purify.detail.honor.removed",
                "purify.detail.hot_banner.hidden",
                "purify.detail.live_order.removed",
                "purify.detail.staff_follow.hidden",
                "purify.detail.topic_tags.removed",
                "purify.detail.ugc_season.removed",
                "purify.detail.up_vip_label.removed"
            ),
            added.map { it.id }.sorted()
        )
        // 新增净化项一律默认关，不动用户既有页面。
        added.forEach { assertEquals(SettingValue.Bool(false), it.defaultValue) }
        added.forEach { assertEquals(RestorePolicy.AUTOMATIC, it.restorePolicy) }
        // 这七项都进宿主，导入后必须提示重启哔哩哔哩。
        added.forEach { assertTrue(ImportEffect.RESTART_BILIBILI in it.effects) }
    }

    @Test fun `catalog v17 adds one default off module ui panel blur setting`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v17.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 17 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.single { it.introducedCatalogVersion == 17 }
        assertEquals("module_ui.appearance.panel_window_blur", added.id)
        assertEquals(SettingValue.Bool(false), added.defaultValue)
        assertEquals(RestorePolicy.AUTOMATIC, added.restorePolicy)
        // 只影响模块界面自己的弹窗动画，不进宿主：不许带 RESTART_BILIBILI。
        assertFalse(ImportEffect.RESTART_BILIBILI in added.effects)
        assertTrue(ImportEffect.RECREATE_MODULE_UI in added.effects)
    }

    @Test fun `catalog v16 adds one default off search home setting`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v16.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 16 }.map { it.id }.sorted())
        val spec = SettingsCatalog.specs.single { it.introducedCatalogVersion == 16 }
        assertEquals("search.home_recommend.hidden",spec.id)
        assertEquals(SettingValue.Bool(false),spec.defaultValue)
        assertEquals(RestorePolicy.AUTOMATIC,spec.restorePolicy)
        assertTrue(ImportEffect.RESTART_BILIBILI in spec.effects)
    }
    @Test fun `catalog v15 adds one default off frequent visits setting`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v15.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 15 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.single { it.introducedCatalogVersion == 15 }
        assertEquals("dynamic.frequent_visits.hidden", added.id)
        assertEquals(SettingValue.Bool(false), added.defaultValue)
        assertEquals(RestorePolicy.AUTOMATIC, added.restorePolicy)
        assertTrue(ImportEffect.RESTART_BILIBILI in added.effects)
    }

    @Test
    fun `catalog is a unique allowlist with 139 settings`() {
        assertEquals(139, SettingsCatalog.specs.size)
        assertEquals(139, SettingsCatalog.specs.map { it.id }.distinct().size)
        assertEquals(139, SettingsCatalog.specs.map { it.storageKey }.distinct().size)
        assertEquals(137, SettingsCatalog.specs.count { it.restorePolicy == RestorePolicy.AUTOMATIC })
        assertEquals(2, SettingsCatalog.specs.count { it.restorePolicy == RestorePolicy.MANUAL })
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
        assertEquals(expected, SettingsCatalog.specs
            .filter { it.introducedCatalogVersion <= 9 }.map { it.id }.sorted())
    }

    @Test
    fun `catalog v10 adds only the opt in PGC activity setting`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v10.txt")
        ).bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(
            expected,
            SettingsCatalog.specs
                .filter { it.introducedCatalogVersion <= 10 }
                .map { it.id }
                .sorted()
        )
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 10 }.single()
        assertEquals("pgc.auto_activity_popup.hidden", added.id)
        assertEquals(SettingValue.Bool(false), added.defaultValue)
        assertEquals(RestorePolicy.AUTOMATIC, added.restorePolicy)
        assertTrue(ImportEffect.RESTART_BILIBILI in added.effects)
    }

    /** v11 = 哔哩漫游移植批次：弹幕 / 评论判据扩展 / 分享 / 站外链接 / 系统 / 开屏 / 直播间 / AV 号。 */
    @Test
    fun `catalog v11 adds the ported feature settings and stays fully restorable`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v11.txt")
        ).bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(
            expected,
            SettingsCatalog.specs
                .filter { it.introducedCatalogVersion <= 11 }
                .map { it.id }
                .sorted()
        )

        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 11 }
        assertEquals(14, added.size)
        assertEquals(
            listOf(
                "comments.at_only.removed",
                "comments.user_filter.enabled",
                "comments.user_filter.rules",
                "links.external_browser.enabled",
                "live.double_tap.pause",
                "live.room_switch.blocked",
                "numbers.bv_as_av.enabled",
                "player.danmaku.vip_colorful.removed",
                "player.danmaku.weight_filter.enabled",
                "player.danmaku.weight_filter.minimum",
                "share.content.purified",
                "share.mini_program.direct_link",
                "splash.auto_night.enabled",
                "system.media_notification.enabled"
            ),
            added.map { it.id }.sorted()
        )
        // 全部默认关闭：移植功能不改变升级用户的既有行为。
        assertTrue(
            added.filter { it.type == SettingValueType.BOOLEAN }
                .all { it.defaultValue == SettingValue.Bool(false) }
        )
        assertTrue(added.all { it.restorePolicy == RestorePolicy.AUTOMATIC })
        assertTrue(added.all { ImportEffect.RESTART_BILIBILI in it.effects })

        val weight = requireNotNull(SettingsCatalog.byId[SettingsCatalog.ID_DANMAKU_WEIGHT_MINIMUM])
        assertEquals(SettingValue.IntValue(3), weight.defaultValue)
        assertTrue(weight.accepts(SettingValue.IntValue(1)))
        assertTrue(weight.accepts(SettingValue.IntValue(10)))
        assertFalse(weight.accepts(SettingValue.IntValue(0)))
        assertFalse(weight.accepts(SettingValue.IntValue(11)))
        assertEquals(SettingValue.IntValue(1), weight.normalizeForBackup(SettingValue.IntValue(-3)))
        assertEquals(SettingValue.IntValue(10), weight.normalizeForBackup(SettingValue.IntValue(99)))
    }

    /** v12 = 哔哩漫游移植批次二：动态页内容过滤 / 搜索结果过滤。 */
    @Test
    fun `catalog v12 adds the dynamic and search filters and stays fully restorable`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v12.txt")
        ).bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 12 }.map { it.id }.sorted())

        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 12 }
        assertEquals(13, added.size)
        assertEquals(
            listOf(
                "dynamic.author_filter.enabled",
                "dynamic.author_filter.rules",
                "dynamic.charge_only.removed",
                "dynamic.keyword_filter.enabled",
                "dynamic.keyword_filter.keywords",
                "dynamic.promotions.removed",
                "dynamic.topic_list.hidden",
                "dynamic.up_list.live.removed",
                "search.author_filter.enabled",
                "search.author_filter.rules",
                "search.commercial.removed",
                "search.keyword_filter.enabled",
                "search.keyword_filter.keywords"
            ),
            added.map { it.id }.sorted()
        )
        // 全部默认关闭 / 空规则：移植功能不改变升级用户的既有行为。
        assertTrue(
            added.filter { it.type == SettingValueType.BOOLEAN }
                .all { it.defaultValue == SettingValue.Bool(false) }
        )
        assertTrue(
            added.filter { it.type == SettingValueType.STRING }
                .all { it.defaultValue == SettingValue.Text("") }
        )
        assertTrue(added.all { it.restorePolicy == RestorePolicy.AUTOMATIC })
        assertTrue(added.all { ImportEffect.RESTART_BILIBILI in it.effects })
    }

    @Test
    fun `catalog v13 preserves old backups and publishes six default off player settings`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v13.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 13 }.map { it.id }.sorted())
        assertEquals(23, SettingsCatalog.CATALOG_VERSION)
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 13 }
        assertEquals(6, added.size)
        assertTrue(added.all { it.restorePolicy == RestorePolicy.AUTOMATIC && ImportEffect.RESTART_BILIBILI in it.effects })
        assertTrue(added.filter { it.type == SettingValueType.BOOLEAN }.all { it.defaultValue == SettingValue.Bool(false) })
        added.filter { it.type == SettingValueType.INTEGER }.forEach {
            assertEquals(SettingValue.IntValue(0), it.defaultValue)
            listOf(0, 25, 125, 275, 400).forEach { value -> assertTrue(it.accepts(SettingValue.IntValue(value))) }
            listOf(-1, 1, 24, 401, Int.MAX_VALUE).forEach { value -> assertFalse(it.accepts(SettingValue.IntValue(value))) }
        }
    }

    @Test
    fun `catalog types and manual roaming boundary are explicit`() {
        assertEquals(109, SettingsCatalog.specs.count { it.type == SettingValueType.BOOLEAN })
        assertEquals(7, SettingsCatalog.specs.count { it.type == SettingValueType.INTEGER })
        assertEquals(23, SettingsCatalog.specs.count { it.type == SettingValueType.STRING })

        val roaming = requireNotNull(SettingsCatalog.byId["compat.roaming.enabled"])
        assertEquals(RestorePolicy.MANUAL, roaming.restorePolicy)
        assertTrue(roaming.effects.isEmpty())
    }

    @Test
    fun `catalog v14 adds only two independent default off homepage filters`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v14.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 14 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 14 }
        assertEquals(setOf("home.recommend.pgc.removed", "home.recommend.special_cards.removed"), added.map { it.id }.toSet())
        assertTrue(added.all { it.defaultValue == SettingValue.Bool(false) &&
            it.restorePolicy == RestorePolicy.AUTOMATIC && ImportEffect.RESTART_BILIBILI in it.effects })
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
