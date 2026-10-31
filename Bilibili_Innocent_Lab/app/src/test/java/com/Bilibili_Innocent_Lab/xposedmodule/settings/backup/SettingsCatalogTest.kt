package com.Bilibili_Innocent_Lab.xposedmodule.settings.backup

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerBufferConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogTest {
    @Test fun `cloud key freeze joins catalog v67 as an opt in restorable group that needs a host restart`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v67.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 67 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 67 }.associateBy { it.id }
        assertEquals(
            setOf("cloud.key_freeze.enabled", "cloud.key_freeze.rules", "cloud.key_freeze.observe"),
            added.keys
        )
        assertEquals(FeaturePreferences.CLOUD_KEY_FREEZE_ENABLED, added.getValue("cloud.key_freeze.enabled").storageKey)
        assertEquals(FeaturePreferences.CLOUD_KEY_FREEZE_RULES, added.getValue("cloud.key_freeze.rules").storageKey)
        assertEquals(FeaturePreferences.CLOUD_KEY_FREEZE_OBSERVE, added.getValue("cloud.key_freeze.observe").storageKey)
        assertEquals(SettingValue.Bool(false), added.getValue("cloud.key_freeze.enabled").defaultValue)
        assertEquals(SettingValue.Text(""), added.getValue("cloud.key_freeze.rules").defaultValue)
        assertEquals(SettingValue.Bool(false), added.getValue("cloud.key_freeze.observe").defaultValue)
        assertTrue(added.values.all { it.restorePolicy == RestorePolicy.AUTOMATIC })
        assertTrue(added.values.all { ImportEffect.RESTART_BILIBILI in it.effects })
        // 规则文本里允许 ; 与 s: 取值，目录层不应把它们当非法字符串拒掉。
        assertTrue(added.getValue("cloud.key_freeze.rules").accepts(SettingValue.Text("ff_a=true;live.cfg=s:x")))
    }

    @Test fun `legacy home frame lock is opt in restorable and requires host restart in catalog v66`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v66.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 66 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.single { it.introducedCatalogVersion == 66 }
        assertEquals("home.frame.legacy_lock.enabled", added.id)
        assertEquals(FeaturePreferences.HOME_FRAME_LEGACY_LOCK, added.storageKey)
        assertEquals(SettingValue.Bool(false), added.defaultValue)
        assertEquals(RestorePolicy.AUTOMATIC, added.restorePolicy)
        assertTrue(ImportEffect.RESTART_BILIBILI in added.effects)
    }

    @Test fun `mine garb dimming preserves the existing default and joins catalog v65`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v65.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 65 }.map { it.id }.sorted())
        val spec = SettingsCatalog.byId.getValue("host.mine.garb_dim.enabled")
        assertEquals(65, spec.introducedCatalogVersion)
        assertEquals(FeaturePreferences.HOST_MINE_GARB_DIM, spec.storageKey)
        assertEquals(SettingValue.Bool(true), spec.defaultValue)
        assertEquals(RestorePolicy.AUTOMATIC, spec.restorePolicy)
        assertTrue(ImportEffect.RESTART_BILIBILI in spec.effects)
    }

    @Test fun `thermal bypass is default off restorable and requires host restart`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v64.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 64 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.single { it.introducedCatalogVersion == 64 }
        assertEquals("player.frame_interpolation.disable_thermal_control", added.id)
        assertEquals(FeaturePreferences.PLAYER_FRAME_INTERPOLATION_DISABLE_THERMAL_CONTROL, added.storageKey)
        assertEquals(SettingValue.Bool(false), added.defaultValue)
        assertEquals(RestorePolicy.AUTOMATIC, added.restorePolicy)
        assertTrue(ImportEffect.RESTART_BILIBILI in added.effects)
    }

    @Test fun `live home promotion is opt in and participates in backup and host restart`() {
        val added = SettingsCatalog.specs.single { it.introducedCatalogVersion == 63 }
        assertEquals("home.live.promotion.hidden", added.id)
        assertEquals(FeaturePreferences.HIDE_LIVE_HOME_PROMOTION, added.storageKey)
        assertEquals(SettingValue.Bool(false), added.defaultValue)
        assertEquals(RestorePolicy.AUTOMATIC, added.restorePolicy)
        assertTrue(ImportEffect.RESTART_BILIBILI in added.effects)
    }


    @Test
    fun `subtitle controls are independent default off settings and preserve older catalogs`() {
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 62 }
        assertEquals(setOf("player.subtitle.style.enabled", "player.subtitle.ai.enabled"), added.map { it.id }.toSet())
        assertTrue(added.all { it.defaultValue == SettingValue.Bool(false) && it.restorePolicy == RestorePolicy.AUTOMATIC })
        assertTrue(added.all { ImportEffect.RESTART_BILIBILI in it.effects })
    }

    @Test fun `catalog v61 adds a default off frame interpolation status toast switch`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v61.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 61 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.single { it.introducedCatalogVersion == 61 }
        assertEquals("player.frame_interpolation.status_toast", added.id)
        assertEquals(FeaturePreferences.PLAYER_FRAME_INTERPOLATION_STATUS_TOAST, added.storageKey)
        assertEquals(SettingValue.Bool(false), added.defaultValue)
        assertEquals(RestorePolicy.AUTOMATIC, added.restorePolicy)
        assertTrue(ImportEffect.RESTART_BILIBILI in added.effects)
    }

    @Test fun `catalog v59 adds a 60 fps default frame interpolation ceiling`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v59.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 59 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.single { it.introducedCatalogVersion == 59 }
        assertEquals("player.frame_interpolation.max_fps", added.id)
        assertEquals(FeaturePreferences.PLAYER_FRAME_INTERPOLATION_MAX_FPS, added.storageKey)
        assertEquals(SettingValue.IntValue(60), added.defaultValue)
        assertTrue(added.accepts(SettingValue.IntValue(185)))
        assertFalse(added.accepts(SettingValue.IntValue(240)))
        assertFalse(added.accepts(SettingValue.IntValue(100)))
        assertTrue(ImportEffect.RESTART_BILIBILI in added.effects)
    }

    @Test fun `catalog v58 adds a default off frame interpolation switch`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v58.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 58 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.single { it.introducedCatalogVersion == 58 }
        assertEquals("player.frame_interpolation.enabled", added.id)
        assertEquals(FeaturePreferences.PLAYER_FRAME_INTERPOLATION, added.storageKey)
        assertEquals(SettingValue.Bool(false), added.defaultValue)
        assertEquals(RestorePolicy.AUTOMATIC, added.restorePolicy)
        assertTrue(ImportEffect.RESTART_BILIBILI in added.effects)
    }

    @Test fun `catalog v60 adds a default empty player speed menu percents setting`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v60.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 60 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.single { it.introducedCatalogVersion == 60 }
        assertEquals(SettingsCatalog.ID_PLAYER_SPEED_MENU, added.id)
        assertEquals(FeaturePreferences.PLAYER_SPEED_MENU_PERCENTS, added.storageKey)
        assertEquals(SettingValue.Text(""), added.defaultValue)
        assertTrue(added.accepts(SettingValue.Text("300,200,150,125,100,75,50")))
        assertFalse(added.accepts(SettingValue.IntValue(200)))
        assertEquals(RestorePolicy.AUTOMATIC, added.restorePolicy)
        assertTrue(ImportEffect.RESTART_BILIBILI in added.effects)
    }

    @Test fun `catalog v57 adds an empty by default publish time rule`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v57.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 57 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.single { it.introducedCatalogVersion == 57 }
        assertEquals(SettingsCatalog.ID_RECOMMEND_VIDEO_PUBLISH_TIME_RULE, added.id)
        assertEquals(FeaturePreferences.RECOMMEND_VIDEO_PUBLISH_TIME_RULE, added.storageKey)
        assertEquals(SettingValue.Text(""), added.defaultValue)
        assertTrue(added.accepts(SettingValue.Text("v1|outside|d:2021-07-08|d:2021-09-01")))
        assertFalse(added.accepts(SettingValue.IntValue(1)))
        assertTrue(ImportEffect.RESTART_BILIBILI in added.effects)
    }

    @Test fun `catalog v55 replaces the acrylic switch with three materials defaulting to original`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v55.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 55 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.single { it.introducedCatalogVersion == 55 }
        assertEquals("host.cards.material", added.id)
        assertEquals(FeaturePreferences.HOST_CARD_MATERIAL, added.storageKey)
        assertEquals(SettingValue.Text("original"), added.defaultValue)
        listOf("original", "acrylic", "mica").forEach { assertTrue(added.accepts(SettingValue.Text(it))) }
        assertFalse(added.accepts(SettingValue.Text("unknown")))
        assertFalse(added.accepts(SettingValue.Bool(true)))
        assertEquals(RestorePolicy.AUTOMATIC, added.restorePolicy)
        assertTrue(ImportEffect.RESTART_BILIBILI in added.effects)
    }

    @Test fun `catalog v53 adds a default off comment action reply button setting`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v53.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 53 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.single { it.introducedCatalogVersion == 53 }
        assertEquals("comments.reply_button.removed", added.id)
        assertEquals(FeaturePreferences.REMOVE_COMMENT_REPLY_BUTTON, added.storageKey)
        assertEquals(SettingValue.Bool(false), added.defaultValue)
        assertEquals(RestorePolicy.AUTOMATIC, added.restorePolicy)
        assertTrue(ImportEffect.RESTART_BILIBILI in added.effects)
    }

    @Test fun `catalog v52 adds a default off precise home comic filter`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v52.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 52 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.single { it.introducedCatalogVersion == 52 }
        assertEquals("home.recommend.comic.removed", added.id)
        assertEquals(FeaturePreferences.REMOVE_HOME_RECOMMEND_COMIC, added.storageKey)
        assertEquals(SettingValue.Bool(false), added.defaultValue)
        assertEquals(RestorePolicy.AUTOMATIC, added.restorePolicy)
        assertTrue(ImportEffect.RESTART_BILIBILI in added.effects)
    }

    @Test fun `catalog v47 adds manual Agent permission after the merged source catalog`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v47.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 47 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 47 }.single()
        assertEquals("agent.enabled", added.id)
        assertEquals(SettingValue.Bool(false), added.defaultValue)
        assertEquals(RestorePolicy.MANUAL, added.restorePolicy)
    }

    @Test fun `catalog v46 adds four optional sources without backing up their keys`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v46.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 46 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 46 }
        assertEquals(12, added.size)
        added.forEach {
            assertEquals(SettingValueType.STRING, it.type)
            assertEquals(SettingValue.Text(if (it.id.endsWith(".provider")) "jev" else ""), it.defaultValue)
            assertEquals(RestorePolicy.AUTOMATIC, it.restorePolicy)
            assertTrue(ImportEffect.RESTART_BILIBILI in it.effects)
        }
        (1..8).forEach { index ->
            val key = com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract.semanticApiKey(index)
            assertTrue(SettingsCatalog.specs.none { it.storageKey == key })
            val (provider, endpoint, model) = FeaturePreferences.semanticSourceKeys(index)
            listOf(provider, endpoint, model).forEach { storage -> assertTrue(SettingsCatalog.specs.any { it.storageKey == storage }) }
        }
        listOf(SettingsCatalog.ID_DYNAMIC_SEMANTIC_SOURCE, SettingsCatalog.ID_DANMAKU_SEMANTIC_SOURCE,
            SettingsCatalog.ID_COMMENT_SEMANTIC_SOURCE, SettingsCatalog.ID_VIDEO_SEMANTIC_SOURCE).forEach { id ->
            (1..8).forEach { assertTrue(SettingsCatalog.byId.getValue(id).accepts(SettingValue.Text(it.toString()))) }
            assertFalse(SettingsCatalog.byId.getValue(id).accepts(SettingValue.Text("9")))
        }
    }

    @Test fun `catalog v48 adds classic comments without changing previous backup entries`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v47.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 47 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 48 }.single()
        assertEquals("comments.classic_style.enabled", added.id)
        assertEquals(FeaturePreferences.COMMENT_CLASSIC_STYLE, added.storageKey)
        assertEquals(SettingValue.Bool(false), added.defaultValue)
        assertEquals(RestorePolicy.AUTOMATIC, added.restorePolicy)
        assertTrue(ImportEffect.RESTART_BILIBILI in added.effects)
    }
    @Test fun `catalog v44 publishes two default off SponsorBlock settings without changing old backups`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v44.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 44 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 44 }
        assertEquals(listOf("player.sponsorblock.automatic", "player.sponsorblock.enabled"), added.map { it.id }.sorted())
        added.forEach {
            assertEquals(SettingValue.Bool(false), it.defaultValue)
            assertEquals(RestorePolicy.AUTOMATIC, it.restorePolicy)
            assertTrue(ImportEffect.RESTART_BILIBILI in it.effects)
        }
    }

    @Test fun `catalog v45 stores glass choices without losing legacy backend intent`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v45.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 45 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 45 }
        assertEquals(setOf("host.top_bar.glass.material", "host.top_bar.glass.renderer",
            "host.bottom_bar.glass.material", "host.bottom_bar.glass.renderer"), added.map { it.id }.toSet())
        added.forEach {
            assertTrue(ImportEffect.RESTART_BILIBILI in it.effects)
            assertTrue(it.accepts(it.defaultValue))
            assertFalse(it.accepts(SettingValue.Text("unknown")))
            assertEquals(RestorePolicy.AUTOMATIC, it.restorePolicy)
        }
        added.filter { it.id.endsWith("renderer") }.forEach {
            assertEquals(SettingValue.Text("legacy"), it.defaultValue)
            assertTrue(it.accepts(SettingValue.Text("software")))
            assertTrue(it.accepts(SettingValue.Text("auto")))
        }
    }
    @Test fun `catalog v23 adds the default off player end page filter`() {
        val expected=requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v23.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected,SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 23 }.map { it.id }.sorted())
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
    fun `catalog is a unique allowlist with 249 settings`() {
        assertEquals(249, SettingsCatalog.specs.size)
        assertEquals(249, SettingsCatalog.specs.map { it.id }.distinct().size)
        assertEquals(249, SettingsCatalog.specs.map { it.storageKey }.distinct().size)
        assertEquals(244, SettingsCatalog.specs.count { it.restorePolicy == RestorePolicy.AUTOMATIC })
        assertEquals(5, SettingsCatalog.specs.count { it.restorePolicy == RestorePolicy.MANUAL })
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
        assertEquals(67, SettingsCatalog.CATALOG_VERSION)
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 13 }
        assertEquals(6, added.size)
        assertTrue(added.all { it.restorePolicy == RestorePolicy.AUTOMATIC && ImportEffect.RESTART_BILIBILI in it.effects })
        assertTrue(added.filter { it.type == SettingValueType.BOOLEAN }.all { it.defaultValue == SettingValue.Bool(false) })
        added.filter { it.type == SettingValueType.INTEGER }.forEach {
            assertEquals(SettingValue.IntValue(0), it.defaultValue)
            listOf(0, 10, 25, 125, 275, 400, 475, 800).forEach { value -> assertTrue(it.accepts(SettingValue.IntValue(value))) }
            listOf(-1, 1, 9, 801, Int.MAX_VALUE).forEach { value -> assertFalse(it.accepts(SettingValue.IntValue(value))) }
        }
    }

    @Test
    fun `catalog v24 adds codec decoder and component library settings`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v24.txt")
        ).bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 24 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 24 }
        assertEquals(
            setOf(
                "client.component_library.download.blocked",
                "player.codec.preference",
                "player.decode.mode"
            ),
            added.map { it.id }.toSet()
        )
        assertEquals(1, added.count { it.type == SettingValueType.BOOLEAN })
        assertEquals(2, added.count { it.type == SettingValueType.INTEGER })
        assertTrue(added.all { it.restorePolicy == RestorePolicy.AUTOMATIC })
    }

    /**
     * v25 只加了一条：「自动确认新增屏蔽标签」。
     *
     * 它是**纯模块 App 行为**（宿主收下这个键但从不读），登记进目录只为了能被
     * 设置备份带走，所以刻意只带 `RECREATE_MODULE_UI`——导入它去重启哔哩哔哩没有意义。
     * 同一张面板的 `recommendation_feedback_reviewed_events` 是处理状态不是用户意图，
     * 继续留在本地、不进目录，这里顺带钉住。
     */
    @Test
    fun `catalog v25 adds only the feedback auto confirm switch`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v25.txt")
        ).bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 25 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.single { it.introducedCatalogVersion == 25 }
        assertEquals("home.recommend.feedback_auto_confirm", added.id)
        assertEquals("recommendation_feedback_auto_confirm", added.storageKey)
        assertEquals(SettingValue.Bool(false), added.defaultValue)
        assertEquals(RestorePolicy.AUTOMATIC, added.restorePolicy)
        assertEquals(setOf(ImportEffect.RECREATE_MODULE_UI), added.effects)
        assertFalse(SettingsCatalog.specs.any {
            it.storageKey == "recommendation_feedback_reviewed_events"
        })
    }

    /**
     * v26 = 组件库拦截从"一刀切开关"改成勾选面板，两条 Text 与其余四个面同构：
     * selectors 是勾出来的，rules 是手填的（含「全量禁止」写进去的 `*` 哨兵），宿主取并集。
     * 两条都进宿主，导入后必须提示重启哔哩哔哩。
     */
    @Test
    fun `catalog v26 adds the component pool selection pair`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v26.txt")
        ).bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 26 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 26 }
        assertEquals(
            listOf(
                "client.component_library.blocked_pools.rules",
                "client.component_library.blocked_pools.selectors"
            ),
            added.map { it.id }.sorted()
        )
        assertEquals(
            listOf("component_pool_blocked_rules", "component_pool_blocked_selectors"),
            added.map { it.storageKey }.sorted()
        )
        added.forEach {
            assertEquals(SettingValueType.STRING, it.type)
            assertEquals(SettingValue.Text(""), it.defaultValue)
            assertEquals(RestorePolicy.AUTOMATIC, it.restorePolicy)
            assertTrue(ImportEffect.RESTART_BILIBILI in it.effects)
        }
    }

    @Test
    fun `catalog v27 adds the shared play count range pair`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v27.txt")
        ).bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 27 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 27 }
        assertEquals(
            listOf(
                SettingsCatalog.ID_RECOMMEND_VIDEO_MAX_PLAY_COUNT,
                SettingsCatalog.ID_RECOMMEND_VIDEO_MIN_PLAY_COUNT
            ),
            added.map { it.id }.sorted()
        )
        assertEquals(
            listOf("recommend_video_max_play_count", "recommend_video_min_play_count"),
            added.map { it.storageKey }.sorted()
        )
        added.forEach {
            assertEquals(SettingValueType.INTEGER, it.type)
            assertEquals(SettingValue.IntValue(0), it.defaultValue)
            assertEquals(RestorePolicy.AUTOMATIC, it.restorePolicy)
            assertTrue(ImportEffect.RESTART_BILIBILI in it.effects)
        }
    }

    @Test
    fun `catalog v28 adds the ai declared video switch and its strong mode`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v28.txt")
        ).bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 28 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 28 }
        assertEquals(
            listOf(
                SettingsCatalog.ID_AI_DECLARED_VIDEOS_BLOCKED,
                SettingsCatalog.ID_AI_DECLARED_VIDEOS_STRONG_MODE
            ),
            added.map { it.id }.sorted()
        )
        assertEquals(
            listOf("block_ai_declared_videos", "block_ai_declared_videos_strong_mode"),
            added.map { it.storageKey }.sorted()
        )
        added.forEach {
            assertEquals(SettingValueType.BOOLEAN, it.type)
            // 两个都默认关：会改写详情页跳转、会往 UP 名单里加人，必须由用户显式打开。
            assertEquals(SettingValue.Bool(false), it.defaultValue)
            assertEquals(RestorePolicy.AUTOMATIC, it.restorePolicy)
            assertTrue(ImportEffect.RESTART_BILIBILI in it.effects)
            assertTrue(ImportEffect.RECREATE_MODULE_UI in it.effects)
        }
    }

    /** access_key 授权：默认关、恢复必须手动确认（换机导入备份不会悄悄重新授权）。 */
    @Test
    fun `catalog v29 adds the access key authorization as a manual restore switch`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v29.txt")
        ).bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 29 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 29 }
        assertEquals(listOf(SettingsCatalog.ID_BILI_ACCESS_KEY_AUTHORIZED), added.map { it.id })
        val spec = added.single()
        assertEquals("bili_access_key_authorized", spec.storageKey)
        assertEquals(SettingValueType.BOOLEAN, spec.type)
        assertEquals(SettingValue.Bool(false), spec.defaultValue)
        assertEquals(RestorePolicy.MANUAL, spec.restorePolicy)
        assertTrue(ImportEffect.RESTART_BILIBILI in spec.effects)
    }

    /** 强力模式拆分：「获取 access_key」推荐预检独立成项，默认关；授权本身仍是 v29 的手动恢复项。 */
    @Test
    fun `catalog v30 splits the ai declared precheck out of strong mode`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v30.txt")
        ).bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 30 }.map { it.id }.sorted())
        val spec = SettingsCatalog.specs.single { it.introducedCatalogVersion == 30 }
        assertEquals(SettingsCatalog.ID_AI_DECLARED_VIDEOS_PRECHECK, spec.id)
        assertEquals("block_ai_declared_videos_precheck", spec.storageKey)
        assertEquals(SettingValue.Bool(false), spec.defaultValue)
        assertEquals(RestorePolicy.AUTOMATIC, spec.restorePolicy)
        assertTrue(ImportEffect.RESTART_BILIBILI in spec.effects)
    }

    /**
     * 智能过滤动态（JEV）：独立开关 + 三项非敏感配置，全部默认关/空/中灵敏度、自动恢复。
     * API Key 是 hook_config 运行时键，**刻意不在目录里**，所以不会进入设置备份。
     */
    @Test
    fun `catalog v31 adds the semantic filter switch and non secret jev settings`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v31.txt")
        ).bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 31 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 31 }.associateBy { it.id }
        assertEquals(
            setOf(
                SettingsCatalog.ID_DYNAMIC_SEMANTIC_FILTER,
                SettingsCatalog.ID_SEMANTIC_JEV_ENDPOINT,
                SettingsCatalog.ID_SEMANTIC_JEV_SENSITIVITY,
                SettingsCatalog.ID_SEMANTIC_JEV_WAIT_FIRST_SCREEN
            ),
            added.keys
        )
        assertEquals(SettingValue.Bool(false), added.getValue(SettingsCatalog.ID_DYNAMIC_SEMANTIC_FILTER).defaultValue)
        assertEquals(SettingValue.Text(""), added.getValue(SettingsCatalog.ID_SEMANTIC_JEV_ENDPOINT).defaultValue)
        val sensitivity = added.getValue(SettingsCatalog.ID_SEMANTIC_JEV_SENSITIVITY)
        assertEquals(SettingValue.Text("medium"), sensitivity.defaultValue)
        assertEquals(setOf("low", "medium", "high"), sensitivity.allowedStrings)
        assertEquals(SettingValue.Bool(false), added.getValue(SettingsCatalog.ID_SEMANTIC_JEV_WAIT_FIRST_SCREEN).defaultValue)
        assertTrue(added.values.all { it.restorePolicy == RestorePolicy.AUTOMATIC && ImportEffect.RESTART_BILIBILI in it.effects })
        assertTrue(SettingsCatalog.specs.none { "api_key" in it.id || "api_key" in it.storageKey })
    }

    /** 弹幕 / 评论 / 推荐视频三个智能过滤开关 + 四个面的屏蔽类型勾选；勾选默认值来自预设目录。 */
    @Test
    fun `catalog v32 adds semantic filter surfaces and rule selections`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v32.txt")
        ).bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 32 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 32 }.associateBy { it.id }
        assertEquals(7, added.size)
        listOf(
            SettingsCatalog.ID_DANMAKU_SEMANTIC_FILTER,
            SettingsCatalog.ID_COMMENT_SEMANTIC_FILTER,
            SettingsCatalog.ID_VIDEO_SEMANTIC_FILTER
        ).forEach { assertEquals(SettingValue.Bool(false), added.getValue(it).defaultValue) }
        mapOf(
            SettingsCatalog.ID_DYNAMIC_SEMANTIC_RULES to com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSurface.DYNAMIC,
            SettingsCatalog.ID_DANMAKU_SEMANTIC_RULES to com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSurface.DANMAKU,
            SettingsCatalog.ID_COMMENT_SEMANTIC_RULES to com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSurface.COMMENT,
            SettingsCatalog.ID_VIDEO_SEMANTIC_RULES to com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSurface.VIDEO
        ).forEach { (id, surface) ->
            assertEquals(
                SettingValue.Text(com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticPresets.defaultSelection(surface)),
                added.getValue(id).defaultValue
            )
        }
        assertTrue(added.values.all { it.restorePolicy == RestorePolicy.AUTOMATIC && ImportEffect.RESTART_BILIBILI in it.effects })
    }

    /** 判定结果保存时长：1–90 天，默认 7；只影响宿主缓存，恢复时自动写回。 */
    @Test
    fun `catalog v33 adds the bounded semantic cache retention`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v33.txt")
        ).bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 33 }.map { it.id }.sorted())
        val spec = SettingsCatalog.specs.single { it.introducedCatalogVersion == 33 }
        assertEquals(SettingsCatalog.ID_SEMANTIC_JEV_CACHE_DAYS, spec.id)
        assertEquals(SettingValue.IntValue(7), spec.defaultValue)
        assertEquals(1..90, spec.integerRange)
        assertTrue(spec.accepts(SettingValue.IntValue(90)))
        assertFalse(spec.accepts(SettingValue.IntValue(91)))
        assertFalse(spec.accepts(SettingValue.IntValue(0)))
        assertEquals(SettingValue.IntValue(90), spec.normalizeForBackup(SettingValue.IntValue(365)))
    }

    /** 判定后端可选（JEV / OpenAI 兼容）+ 模型名；默认 JEV、模型留空。 */
    @Test
    fun `catalog v34 adds the semantic backend and model`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v34.txt")
        ).bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 34 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 34 }.associateBy { it.id }
        val provider = added.getValue(SettingsCatalog.ID_SEMANTIC_JEV_PROVIDER)
        assertEquals(SettingValue.Text("jev"), provider.defaultValue)
        // v35 起新增 cloudflare（同一个键，取值集合扩大）。
        assertEquals(setOf("jev", "openai", "cloudflare"), provider.allowedStrings)
        assertEquals(SettingValue.Text(""), added.getValue(SettingsCatalog.ID_SEMANTIC_JEV_MODEL).defaultValue)
        assertEquals(2, added.size)
    }

    /** 等待上限：0 = 自动，其余毫秒 ≤ 30000；只影响宿主，恢复时自动写回。 */
    @Test
    fun `catalog v35 adds the semantic wait limit`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v35.txt")
        ).bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 35 }.map { it.id }.sorted())
        val spec = SettingsCatalog.specs.single { it.introducedCatalogVersion == 35 }
        assertEquals(SettingsCatalog.ID_SEMANTIC_JEV_TIMEOUT_MS, spec.id)
        assertEquals(SettingValue.IntValue(0), spec.defaultValue)
        assertEquals(0..30_000, spec.integerRange)
        assertFalse(spec.accepts(SettingValue.IntValue(30_001)))
    }

    /** 多来源（2–4 号，Key 不在目录）、判定口径、各面判定来源与自定义类型；默认全空 / 自动分流。 */
    @Test
    fun `catalog v36 adds multi source routing guidance and custom types`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v36.txt")
        ).bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 36 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 36 }.associateBy { it.id }
        assertEquals(18, added.size)
        assertTrue(added.values.all { it.type == SettingValueType.STRING && it.restorePolicy == RestorePolicy.AUTOMATIC })
        assertTrue(added.keys.none { it.contains("api_key") }) // Key 不进目录与备份
        val route = added.getValue(SettingsCatalog.ID_COMMENT_SEMANTIC_SOURCE)
        assertEquals(SettingValue.Text("auto"), route.defaultValue)
        assertEquals(setOf("auto", "1", "2", "3", "4", "5", "6", "7", "8"), route.allowedStrings)
        assertEquals(SettingValue.Text("jev"), added.getValue("compat.semantic_source.3.provider").defaultValue)
        assertFalse(added.getValue(SettingsCatalog.ID_SEMANTIC_JEV_GUIDANCE).accepts(SettingValue.Text("x".repeat(1_001))))
    }

    @Test
    fun `catalog types and manual roaming boundary are explicit`() {
        assertEquals(152, SettingsCatalog.specs.count { it.type == SettingValueType.BOOLEAN })
        assertEquals(24, SettingsCatalog.specs.count { it.type == SettingValueType.INTEGER })
        assertEquals(73, SettingsCatalog.specs.count { it.type == SettingValueType.STRING })

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

        listOf(
            SettingsCatalog.ID_RECOMMEND_VIDEO_MIN_PLAY_COUNT,
            SettingsCatalog.ID_RECOMMEND_VIDEO_MAX_PLAY_COUNT
        ).forEach { id ->
            val playCount = requireNotNull(SettingsCatalog.byId[id])
            assertEquals(27, playCount.introducedCatalogVersion)
            assertEquals(SettingValue.IntValue(0), playCount.defaultValue)
            assertTrue(playCount.accepts(SettingValue.IntValue(0)))
            assertTrue(playCount.accepts(SettingValue.IntValue(Int.MAX_VALUE)))
            assertFalse(playCount.accepts(SettingValue.IntValue(-1)))
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

    @Test
    fun `catalog v38 adds independent default off brand splash controls`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v38.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 38 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 38 }
        assertEquals(setOf(FeaturePreferences.BRAND_SPLASH_SKIP, FeaturePreferences.BRAND_SPLASH_CUSTOM),
            added.map { it.storageKey }.toSet())
        assertTrue(added.all { it.defaultValue == SettingValue.Bool(false) && ImportEffect.RESTART_BILIBILI in it.effects })
    }

    @Test
    fun `catalog v37 adds six default off story action icon switches`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v37.txt")
        ).bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 37 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 37 }
        assertEquals(6, added.size)
        assertTrue(added.all {
            it.type == SettingValueType.BOOLEAN && it.defaultValue == SettingValue.Bool(false) &&
                it.restorePolicy == RestorePolicy.AUTOMATIC && ImportEffect.RESTART_BILIBILI in it.effects
        })
        assertEquals(
            com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.StoryActionIcon.preferenceKeys.toSet(),
            added.map { it.storageKey }.toSet()
        )
    }

    @Test
    fun `catalog v39 adds two default off host bottom bar visual effect switches`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v39.txt")
        ).bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 39 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 39 }
        assertEquals(2, added.size)
        assertTrue(added.all {
            it.type == SettingValueType.BOOLEAN && it.defaultValue == SettingValue.Bool(false) &&
                it.restorePolicy == RestorePolicy.AUTOMATIC && ImportEffect.RESTART_BILIBILI in it.effects
        })
        assertEquals(
            setOf(
                com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences.HOST_BOTTOM_BAR_LIQUID_GLASS,
                com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences.HOST_BOTTOM_BAR_TOUCH_GLOW
            ),
            added.map { it.storageKey }.toSet()
        )
    }

    @Test
    fun `catalog v40 adds two default off host top bar visual effect switches`() {
        val expected = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v40.txt")
        ).bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 40 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 40 }
        assertEquals(2, added.size)
        assertTrue(added.all {
            it.type == SettingValueType.BOOLEAN && it.defaultValue == SettingValue.Bool(false) &&
                it.restorePolicy == RestorePolicy.AUTOMATIC && ImportEffect.RESTART_BILIBILI in it.effects
        })
        assertEquals(
            setOf(
                com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences.HOST_TOP_BAR_LIQUID_GLASS,
                com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences.HOST_TOP_BAR_TOUCH_GLOW
            ),
            added.map { it.storageKey }.toSet()
        )
    }
    @Test
    fun `catalog v41 adds independently restorable default off bottom bar layout switches`() {
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 41 }
        assertEquals(setOf("host.bottom_bar.compact.enabled", "host.bottom_bar.icon_only.enabled"),
            added.map { it.id }.toSet())
        assertTrue(added.all {
            it.type == SettingValueType.BOOLEAN && it.defaultValue == SettingValue.Bool(false) &&
                it.restorePolicy == RestorePolicy.AUTOMATIC && ImportEffect.RESTART_BILIBILI in it.effects
        })
    }

    @Test
    fun `catalog v42 adds one default off video card style setting`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v42.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 42 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.single { it.introducedCatalogVersion == 42 }
        assertEquals("host.video_cards.enabled", added.id)
        assertEquals(FeaturePreferences.HOST_VIDEO_CARDS, added.storageKey)
        assertEquals(SettingValue.Bool(false), added.defaultValue)
        assertEquals(RestorePolicy.AUTOMATIC, added.restorePolicy)
        assertTrue(ImportEffect.RESTART_BILIBILI in added.effects)
    }

    @Test
    fun `catalog v50 adds independently restorable home card spacing`() {
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 50 }
        assertEquals(setOf(FeaturePreferences.HOST_HOME_CARD_HORIZONTAL_DP, FeaturePreferences.HOST_HOME_CARD_VERTICAL_DP),
            added.map { it.storageKey }.toSet())
        added.forEach {
            assertEquals(SettingValue.IntValue(-1), it.defaultValue)
            assertEquals(-1..40, it.integerRange)
            assertEquals(RestorePolicy.AUTOMATIC, it.restorePolicy)
            assertTrue(ImportEffect.RESTART_BILIBILI in it.effects)
            assertTrue(it.accepts(SettingValue.IntValue(0)))
            assertTrue(it.accepts(SettingValue.IntValue(40)))
            assertFalse(it.accepts(SettingValue.IntValue(41)))
        }
    }

    @Test
    fun `catalog v51 adds bounded experimental buffer settings with host defaults`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v51.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 51 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.filter { it.introducedCatalogVersion == 51 }.associateBy { it.storageKey }
        assertEquals(setOf(FeaturePreferences.PLAYER_BUFFER_MODE, FeaturePreferences.PLAYER_BUFFER_SECONDS,
            FeaturePreferences.PLAYER_BUFFER_MEMORY_MB, FeaturePreferences.PLAYER_BUFFER_DISK_MB,
            FeaturePreferences.PLAYER_BUFFER_WIFI_ONLY), added.keys)
        assertTrue(added.values.all {
            it.restorePolicy == RestorePolicy.AUTOMATIC && ImportEffect.RESTART_BILIBILI in it.effects
        })
        val mode = added.getValue(FeaturePreferences.PLAYER_BUFFER_MODE)
        assertEquals(SettingValue.IntValue(0), mode.defaultValue)
        PlayerBufferConfig.supportedModes.forEach { assertTrue(mode.accepts(SettingValue.IntValue(it))) }
        listOf(-1, 4, Int.MAX_VALUE).forEach { assertFalse(mode.accepts(SettingValue.IntValue(it))) }
        val ranges = listOf(
            Triple(FeaturePreferences.PLAYER_BUFFER_SECONDS, PlayerBufferConfig.DEFAULT_SECONDS,
                PlayerBufferConfig.MIN_SECONDS..PlayerBufferConfig.MAX_SECONDS),
            Triple(FeaturePreferences.PLAYER_BUFFER_MEMORY_MB, PlayerBufferConfig.DEFAULT_MEMORY_MB,
                PlayerBufferConfig.MIN_MEMORY_MB..PlayerBufferConfig.MAX_MEMORY_MB),
            Triple(FeaturePreferences.PLAYER_BUFFER_DISK_MB, PlayerBufferConfig.DEFAULT_DISK_MB,
                PlayerBufferConfig.MIN_DISK_MB..PlayerBufferConfig.MAX_DISK_MB)
        )
        ranges.forEach { (key, default, range) ->
            val spec = added.getValue(key)
            assertEquals(SettingValue.IntValue(default), spec.defaultValue)
            assertEquals(range, spec.integerRange)
            assertTrue(spec.accepts(SettingValue.IntValue(range.first)))
            assertTrue(spec.accepts(SettingValue.IntValue(range.last)))
            assertFalse(spec.accepts(SettingValue.IntValue(range.first - 1)))
            assertFalse(spec.accepts(SettingValue.IntValue(range.last + 1)))
            assertFalse(spec.accepts(SettingValue.Text(default.toString())))
        }
        assertEquals(SettingValue.Bool(true), added.getValue(FeaturePreferences.PLAYER_BUFFER_WIFI_ONLY).defaultValue)
    }

    @Test
    fun `catalog v43 adds restorable video card radius with legacy default`() {
        val expected = requireNotNull(javaClass.classLoader?.getResourceAsStream("settings-backup/catalog-v43.txt"))
            .bufferedReader().useLines { it.filter(String::isNotBlank).toList() }
        assertEquals(expected, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 43 }.map { it.id }.sorted())
        val added = SettingsCatalog.specs.single { it.introducedCatalogVersion == 43 }
        assertEquals("host.video_cards.radius_dp", added.id)
        assertEquals(FeaturePreferences.HOST_VIDEO_CARD_RADIUS_DP, added.storageKey)
        assertEquals(SettingValue.IntValue(-1), added.defaultValue)
        assertEquals(-1..40, added.integerRange)
        assertEquals(RestorePolicy.AUTOMATIC, added.restorePolicy)
        assertTrue(ImportEffect.RESTART_BILIBILI in added.effects)
    }
}
