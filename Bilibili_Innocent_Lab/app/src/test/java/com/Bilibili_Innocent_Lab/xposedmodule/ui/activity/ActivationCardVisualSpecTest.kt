package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.ActivationDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivationCardVisualSpecTest {

    @Test
    fun `activation states map to stable semantic tones`() {
        assertEquals(
            DiagnosticStatusTone.OK,
            ActivationCardVisualSpec.tone(ActivationDisplayState.ACTIVE_LSPOSED)
        )
        assertEquals(
            DiagnosticStatusTone.OK,
            ActivationCardVisualSpec.tone(ActivationDisplayState.ACTIVE_NPATCH)
        )
        assertEquals(
            DiagnosticStatusTone.INFO,
            ActivationCardVisualSpec.tone(ActivationDisplayState.CHECKING)
        )
        assertEquals(
            DiagnosticStatusTone.ACTION_REQUIRED,
            ActivationCardVisualSpec.tone(ActivationDisplayState.UNAVAILABLE)
        )
    }

    @Test
    fun `accent layers become narrower and stronger toward the edge`() {
        assertTrue(
            ActivationCardVisualSpec.OUTER_GLOW_WIDTH_DP >
                ActivationCardVisualSpec.INNER_GLOW_WIDTH_DP
        )
        assertTrue(
            ActivationCardVisualSpec.INNER_GLOW_WIDTH_DP >
                ActivationCardVisualSpec.BORDER_WIDTH_DP
        )
        assertTrue(
            ActivationCardVisualSpec.OUTER_GLOW_ALPHA <
                ActivationCardVisualSpec.INNER_GLOW_ALPHA
        )
        assertTrue(
            ActivationCardVisualSpec.INNER_GLOW_ALPHA <
                ActivationCardVisualSpec.BORDER_ALPHA
        )
    }
}
