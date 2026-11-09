package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.ActivationDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivationCardVisualSpecTest {
    @Test fun `unsupported embedded mode remains actionable in the card and summary`() {
        assertEquals(DiagnosticStatusTone.ACTION_REQUIRED,
            ActivationCardVisualSpec.tone(ActivationDisplayState.EMBEDDED_UNSUPPORTED))
        assertEquals(ActivationSummaryState.ACTION_REQUIRED,
            ActivationCardVisualSpec.diagnosticsSummaryState(ActivationDisplayState.EMBEDDED_UNSUPPORTED,
                publishFailed = false, skinFallback = false, noRootNeedsAttention = false))
    }
    @Test fun `NPatch bypass does not warn but preserves real failures and attention`() {
        fun summary(bypass: Boolean, attention: Boolean = false) = ActivationCardVisualSpec.diagnosticsSummaryState(
            ActivationDisplayState.ACTIVE_NPATCH, publishFailed = true, skinFallback = false,
            noRootNeedsAttention = attention, standardPublisherBypassed = bypass)
        assertEquals(ActivationSummaryState.READY, summary(true))
        assertEquals(ActivationSummaryState.ATTENTION, summary(false))
        assertEquals(ActivationSummaryState.ATTENTION, summary(true, attention = true))
    }

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
            DiagnosticStatusTone.OK,
            ActivationCardVisualSpec.tone(ActivationDisplayState.ACTIVE_LSPATCH)
        )
        assertEquals(
            DiagnosticStatusTone.INFO,
            ActivationCardVisualSpec.tone(ActivationDisplayState.LSPATCH_WAITING_FOR_HOST)
        )
        assertEquals(
            DiagnosticStatusTone.ACTION_REQUIRED,
            ActivationCardVisualSpec.tone(ActivationDisplayState.LSPATCH_HOST_FAILED)
        )
        assertEquals(
            DiagnosticStatusTone.INFO,
            ActivationCardVisualSpec.tone(ActivationDisplayState.CHECKING)
        )
        assertEquals(
            DiagnosticStatusTone.ACTION_REQUIRED,
            ActivationCardVisualSpec.tone(ActivationDisplayState.UNAVAILABLE)
        )
        assertEquals(
            DiagnosticStatusTone.ACTION_REQUIRED,
            ActivationCardVisualSpec.tone(ActivationDisplayState.SCOPE_MISSING)
        )
        assertEquals(
            DiagnosticStatusTone.INFO,
            ActivationCardVisualSpec.tone(ActivationDisplayState.CONFIRMING)
        )
        assertEquals(
            DiagnosticStatusTone.ATTENTION,
            ActivationCardVisualSpec.tone(ActivationDisplayState.UNCONFIRMED)
        )
        assertEquals(
            DiagnosticStatusTone.ACTION_REQUIRED,
            ActivationCardVisualSpec.tone(ActivationDisplayState.NOT_RUNNING)
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

    @Test
    fun `summary priority preserves actionable unavailable states`() {
        assertEquals(
            ActivationSummaryState.ACTION_REQUIRED,
            ActivationCardVisualSpec.diagnosticsSummaryState(
                displayState = ActivationDisplayState.UNAVAILABLE,
                publishFailed = false,
                skinFallback = true,
                noRootNeedsAttention = true
            )
        )
        assertEquals(
            ActivationSummaryState.ACTION_REQUIRED,
            ActivationCardVisualSpec.diagnosticsSummaryState(
                displayState = ActivationDisplayState.LSPATCH_HOST_FAILED,
                publishFailed = true,
                skinFallback = true,
                noRootNeedsAttention = true
            )
        )
        assertEquals(
            ActivationSummaryState.ACTION_REQUIRED,
            ActivationCardVisualSpec.diagnosticsSummaryState(
                displayState = ActivationDisplayState.SCOPE_MISSING,
                publishFailed = false,
                skinFallback = false,
                noRootNeedsAttention = true
            )
        )
        assertEquals(
            ActivationSummaryState.ACTION_REQUIRED,
            ActivationCardVisualSpec.diagnosticsSummaryState(
                displayState = ActivationDisplayState.NOT_RUNNING,
                publishFailed = true,
                skinFallback = false,
                noRootNeedsAttention = false
            )
        )
        assertEquals(
            ActivationSummaryState.ATTENTION,
            ActivationCardVisualSpec.diagnosticsSummaryState(
                displayState = ActivationDisplayState.UNCONFIRMED,
                publishFailed = false,
                skinFallback = false,
                noRootNeedsAttention = false
            )
        )
        assertEquals(
            ActivationSummaryState.INFO,
            ActivationCardVisualSpec.diagnosticsSummaryState(
                displayState = ActivationDisplayState.CONFIRMING,
                publishFailed = false,
                skinFallback = false,
                noRootNeedsAttention = false
            )
        )
    }

    @Test
    fun `summary priority orders publish failure waiting and ordinary attention`() {
        assertEquals(
            ActivationSummaryState.ATTENTION,
            ActivationCardVisualSpec.diagnosticsSummaryState(
                displayState = ActivationDisplayState.LSPATCH_WAITING_FOR_HOST,
                publishFailed = true,
                skinFallback = false,
                noRootNeedsAttention = false
            )
        )
        assertEquals(
            ActivationSummaryState.INFO,
            ActivationCardVisualSpec.diagnosticsSummaryState(
                displayState = ActivationDisplayState.LSPATCH_WAITING_FOR_HOST,
                publishFailed = false,
                skinFallback = true,
                noRootNeedsAttention = true
            )
        )
        assertEquals(
            ActivationSummaryState.ATTENTION,
            ActivationCardVisualSpec.diagnosticsSummaryState(
                displayState = ActivationDisplayState.ACTIVE_LSPOSED,
                publishFailed = false,
                skinFallback = true,
                noRootNeedsAttention = false
            )
        )
        assertEquals(
            ActivationSummaryState.READY,
            ActivationCardVisualSpec.diagnosticsSummaryState(
                displayState = ActivationDisplayState.ACTIVE_LSPOSED,
                publishFailed = false,
                skinFallback = false,
                noRootNeedsAttention = false
            )
        )
    }
}
