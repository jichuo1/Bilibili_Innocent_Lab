package com.Bilibili_Innocent_Lab.xposedmodule.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticFeatureRegistryTest {
    @Test
    fun `registry is unique bounded and covers specialized feature paths`() {
        assertEquals(42, DiagnosticFeatureRegistry.descriptors.size)
        assertEquals(
            DiagnosticFeatureRegistry.descriptors.size,
            DiagnosticFeatureRegistry.ids.size
        )
        assertTrue("free_copy" in DiagnosticFeatureRegistry.ids)
        assertTrue("roaming_compat" in DiagnosticFeatureRegistry.ids)
        assertTrue("player_interactive_overlay" in DiagnosticFeatureRegistry.ids)
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
}
