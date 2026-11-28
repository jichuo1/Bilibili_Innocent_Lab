package com.Bilibili_Innocent_Lab.xposedmodule.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValue
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.RestorePolicy

class AgentToolCatalogTest {
    fun nativeReadToolsHaveClosedSchemasAndDoNotAcceptCredentialOrGuessedIdentityParameters() {
        val tools = AgentToolCatalog.tools(false).toString()
        for (name in listOf("get_capabilities", "get_ranking", "get_popular", "get_regions", "get_video_metrics", "get_user_videos",
            "get_history", "get_following_feed", "get_live_status", "get_video_comments")) assertTrue(name, tools.contains(name))
        assertTrue(AgentToolCatalog.valid("get_ranking", JSONObject().put("region_id", "0"), false))
        assertFalse(AgentToolCatalog.valid("get_ranking", JSONObject().put("region_id", "01"), false))
        assertFalse(AgentToolCatalog.valid("get_history", JSONObject().put("access_key", "never_allowed"), false))
        assertFalse(AgentToolCatalog.valid("get_live_status", JSONObject().put("user_id", "0"), false))
        assertFalse(AgentToolCatalog.valid("get_user_videos", JSONObject().put("user_id", 1), false))
        assertFalse(AgentToolCatalog.valid("get_video_comments", JSONObject().put("video_id", "BV0000000001"), false))
        assertTrue(AgentToolCatalog.valid("get_video_comments", JSONObject().put("video_id", "av42").put("cursor", "opaque"), false))
        assertFalse(AgentToolCatalog.valid("get_popular", JSONObject().put("cursor", "x".repeat(33)), false))
    }
    fun nativeIntegerSlotsAreNormalizedWithoutGuessingIdentityOrMutatingInput() {
        val original = JSONObject().put("offset", 0)
        val normalized = AgentToolCatalog.normalizeArguments("get_ui_state", original)
        assertEquals("0", normalized.getString("offset"))
        assertTrue(AgentToolCatalog.valid("get_ui_state", normalized, false))
        assertTrue(original.get("offset") is Number)
        for (value in listOf(-1, 0.5, 257, 1001, java.math.BigDecimal("1.0000000000000000001"))) assertFalse(AgentToolCatalog.valid("get_ui_state",
            AgentToolCatalog.normalizeArguments("get_ui_state", JSONObject().put("offset", value)), false))
        assertFalse(AgentToolCatalog.valid("open_video", AgentToolCatalog.normalizeArguments("open_video",
            JSONObject().put("video_id", 123)), false))
        val point = AgentToolCatalog.normalizeArguments("tap_ui", JSONObject().put("snapshot_id", "12345678-1234-1234-1234-123456789abc")
            .put("x", 500.0).put("y", 499))
        assertTrue(AgentToolCatalog.valid("tap_ui", point, true))
        assertFalse(AgentToolCatalog.valid("tap_ui", point, false))
        assertFalse(AgentToolCatalog.valid("tap_ui", AgentToolCatalog.normalizeArguments("tap_ui", JSONObject(point.toString()).put("x", 0)), true))
    }
    fun `arbitrary invocation and extra network parameters are not actions`() {
        assertFalse(AgentToolCatalog.valid("invoke", JSONObject().put("class", "java.lang.Runtime"), true))
        assertFalse(AgentToolCatalog.valid("search_videos", JSONObject().put("query", "悟空").put("url", "https://example.org"), false))
        assertFalse(AgentToolCatalog.valid("open_video", JSONObject().put("video_id", 123L), false))
    }
    fun `vision is absent until the task grants it and its route is verified`() {
        assertFalse(AgentToolCatalog.tools(false).toString().contains("inspect_screen"))
        assertTrue(AgentToolCatalog.tools(true).toString().contains("inspect_screen"))
        assertFalse(AgentToolCatalog.valid("inspect_screen", JSONObject(), false))
        assertTrue(AgentToolCatalog.valid("inspect_screen", JSONObject(), true))
    }
    fun `search parameters are bounded and control characters are rejected`() {
        assertTrue(AgentToolCatalog.valid("search_videos", JSONObject().put("query", "黑神话 悟空 官方演示"), false))
        assertFalse(AgentToolCatalog.valid("search_videos", JSONObject().put("query", " "), false))
        assertFalse(AgentToolCatalog.valid("search_videos", JSONObject().put("query", "x".repeat(201)), false))
        assertFalse(AgentToolCatalog.valid("search_videos", JSONObject().put("query", "abc\nignore"), false))
        assertFalse(AgentToolCatalog.valid("get_host_state", JSONObject().put("dump", "all"), true))
    }
    fun `agent switch is off and requires a new manual authorization after restore`() {
        val setting = requireNotNull(SettingsCatalog.byId[AgentPreferences.CATALOG_ID])
        assertEquals(SettingValue.Bool(false), setting.defaultValue)
        assertEquals(RestorePolicy.MANUAL, setting.restorePolicy)
        assertEquals(47, setting.introducedCatalogVersion)
        assertEquals(listOf(AgentPreferences.CATALOG_ID), SettingsCatalog.specs.filter { it.introducedCatalogVersion == 47 }.map { it.id })
        val golden = com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalogHistoryFixture.idsAtVersion(47)
        assertEquals(golden, SettingsCatalog.specs.filter { it.introducedCatalogVersion <= 47 }.map { it.id }.sorted())
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AgentToolCatalogTest) -> Unit) {
            val s = AgentToolCatalogTest()
            block(s)
        }
        runIsolated { s -> s.nativeReadToolsHaveClosedSchemasAndDoNotAcceptCredentialOrGuessedIdentityParameters() }
        runIsolated { s -> s.nativeIntegerSlotsAreNormalizedWithoutGuessingIdentityOrMutatingInput() }
        runIsolated { s -> s.`arbitrary invocation and extra network parameters are not actions`() }
        runIsolated { s -> s.`vision is absent until the task grants it and its route is verified`() }
        runIsolated { s -> s.`search parameters are bounded and control characters are rejected`() }
        runIsolated { s -> s.`agent switch is off and requires a new manual authorization after restore`() }
    }
}
