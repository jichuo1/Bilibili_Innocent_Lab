package com.Bilibili_Innocent_Lab.xposedmodule.diagnostics

import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticFeatureRegistryTest {
    fun `guest browsing and incognito are independent general groups expecting runtime evidence`() {
        listOf("guest_browsing", "incognito_privacy").forEach { id ->
            val descriptor = requireNotNull(DiagnosticFeatureRegistry.descriptorOrNull(id))
            assertEquals(DiagnosticFeatureCategory.GENERAL, descriptor.category)
            assertTrue(descriptor.runtimeEvidenceExpected)
            assertEquals(1, DiagnosticFeatureRegistry.descriptors.count { it.id == id })
        }
    }

    fun `registry is unique bounded and covers specialized feature paths`() {
        assertEquals(512, HostRuntimeDiagnosticsCodec.MAX_FEATURE_COUNT)
        assertEquals(128 * 1024, HostRuntimeDiagnosticsCodec.MAX_PAYLOAD_CHARS)
        assertEquals(3, HostRuntimeDiagnosticsCodec.CURRENT_SCHEMA_VERSION)
        assertEquals(DiagnosticCapabilityCatalog.leafIds.size + DiagnosticCapabilityCatalog.splitParents.size,
            DiagnosticFeatureRegistry.descriptors.size)
        assertTrue(DiagnosticFeatureRegistry.descriptors.size <=
            HostRuntimeDiagnosticsCodec.MAX_FEATURE_COUNT)
        assertEquals(
            DiagnosticFeatureRegistry.descriptors.size,
            DiagnosticFeatureRegistry.ids.size
        )
        assertTrue("free_copy" in DiagnosticFeatureRegistry.ids)
        assertTrue("roaming_compat" in DiagnosticFeatureRegistry.ids)
        assertTrue("player_interactive_overlay" in DiagnosticFeatureRegistry.ids)
        assertTrue("home_frame_lock" in DiagnosticFeatureRegistry.ids)
        assertTrue("home_recommend_early_load" in DiagnosticFeatureRegistry.ids)
        assertTrue("cloud_key_freeze" in DiagnosticFeatureRegistry.ids)
        // 哔哩漫游移植批次：九个新安装器都必须能在诊断中心单独看到运行证据。
        listOf(
            "danmaku_purify",
            "live_room_widgets",
            "share_purify",
            "external_browser",
            "system_media_notification",
            "splash_auto_night",
            "bv_to_av",
            "dynamic_purify",
            "search_purify",
            "player_capabilities",
            "player_speed"
        ).forEach { id ->
            assertTrue(
                "missing ported diagnostic feature $id",
                DiagnosticFeatureRegistry.descriptorOrNull(id)?.runtimeEvidenceExpected == true
            )
        }
        assertTrue(DiagnosticFeatureRegistry.descriptorOrNull("pgc_auto_activity_popup")?.runtimeEvidenceExpected == true)
        assertTrue(
            DiagnosticFeatureRegistry.descriptors
                .filter { it.runtimeEvidenceExpected }
                .all { it.id in DiagnosticFeatureRegistry.ids }
        )
    }

    fun `titleResId covers every registered feature id and prefers capability label`() {
        DiagnosticFeatureRegistry.descriptors.forEach { descriptor ->
            assertNotNull("missing labelRes on descriptor ${descriptor.id}", descriptor.labelRes)
            assertTrue("zero labelRes on descriptor ${descriptor.id}", descriptor.labelRes != 0)
            val resolved = DiagnosticFeatureRegistry.titleResId(descriptor.id)
            assertNotNull("missing titleResId for ${descriptor.id}", resolved)
            assertTrue("zero titleResId for ${descriptor.id}", resolved != 0)
        }
        DiagnosticCapabilityCatalog.definitions.forEach { definition ->
            assertEquals(
                "catalog label must win for ${definition.id}",
                definition.labelRes,
                DiagnosticFeatureRegistry.titleResId(definition.id)
            )
        }
        assertEquals(R.string.no_login_enabled, DiagnosticFeatureRegistry.titleResId("guest_browsing"))
        assertEquals(R.string.incognito_enabled, DiagnosticFeatureRegistry.titleResId("incognito_privacy"))
        assertEquals(R.string.diagnostics_host_feature_home_feed, DiagnosticFeatureRegistry.titleResId("home_recommend_purify"))
        assertEquals(R.string.detail_module_purify_settings, DiagnosticFeatureRegistry.titleResId("detail_module_purify"))
        assertEquals(R.string.block_ai_declared_videos, DiagnosticFeatureRegistry.titleResId("ai_declared_video_block"))
        assertEquals(R.string.purify_splash_ads, DiagnosticFeatureRegistry.titleResId("splash_ad_purify"))
        assertEquals(R.string.sponsorblock_enabled, DiagnosticFeatureRegistry.titleResId("sponsorblock"))
        assertNull(DiagnosticFeatureRegistry.titleResId("unknown_feature_id"))
        assertNull(DiagnosticFeatureRegistry.titleResId(""))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (DiagnosticFeatureRegistryTest) -> Unit) {
            val s = DiagnosticFeatureRegistryTest()
            block(s)
        }
        runIsolated { s -> s.`guest browsing and incognito are independent general groups expecting runtime evidence`() }
        runIsolated { s -> s.`registry is unique bounded and covers specialized feature paths`() }
        runIsolated { s -> s.`titleResId covers every registered feature id and prefers capability label`() }
    }
}
