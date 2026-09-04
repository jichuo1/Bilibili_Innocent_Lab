package com.Bilibili_Innocent_Lab.xposedmodule.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticFeatureRegistryTest {
    @Test
    fun `registry is unique bounded and covers specialized feature paths`() {
        assertEquals(30, DiagnosticFeatureRegistry.descriptors.size)
        assertEquals(
            DiagnosticFeatureRegistry.descriptors.size,
            DiagnosticFeatureRegistry.ids.size
        )
        assertTrue("free_copy" in DiagnosticFeatureRegistry.ids)
        assertTrue("roaming_compat" in DiagnosticFeatureRegistry.ids)
        assertTrue("player_interactive_overlay" in DiagnosticFeatureRegistry.ids)
        assertTrue(
            DiagnosticFeatureRegistry.descriptors
                .filter { it.runtimeEvidenceExpected }
                .all { it.id in DiagnosticFeatureRegistry.ids }
        )
    }
}
