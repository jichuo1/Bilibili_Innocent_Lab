package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTransitionMotionTest {
    private val entryBounds = SettingsBackupMotionRect(72f, 360f, 1008f, 540f)
    private val windowBounds = SettingsBackupMotionRect(0f, 0f, 1080f, 2160f)
    private val entryTitle = SettingsBackupMotionRect(108f, 390f, 620f, 435f)
    private val toolbarTitle = SettingsBackupMotionRect(174f, 18f, 900f, 66f)

    @Test
    fun diagnosticsEntryExpandsToWindowWithoutChangingTheSourceEndpoint() {
        val collapsed = frame(0f)
        val expanded = frame(1f)

        assertEquals(entryBounds, collapsed.bounds)
        assertEquals(entryTitle.left, collapsed.titleX, 0f)
        assertEquals(entryTitle.top, collapsed.titleY, 0f)
        assertEquals(0f, collapsed.contentAlpha, 0f)
        assertEquals(windowBounds, expanded.bounds)
        assertEquals(toolbarTitle.left, expanded.titleX, 0f)
        assertEquals(toolbarTitle.top, expanded.titleY, 0f)
        assertEquals(1f, expanded.contentAlpha, 0f)
    }

    @Test
    fun predictiveReturnKeepsContentVisibleLongerThanTimedClose() {
        val timed = frame(0.8f, SettingsBackupContentTiming.TIMED)
        val predictive = frame(0.8f, SettingsBackupContentTiming.PREDICTIVE)

        assertEquals(0f, timed.contentAlpha, 0f)
        assertEquals(1f, predictive.contentAlpha, 0f)
        assertTrue(predictive.contentTranslationYPx < timed.contentTranslationYPx)
    }

    @Test
    fun diagnosticSurfaceHandsOffOnlyAfterReachingTheSourceEndpoint() {
        assertEquals(
            0f,
            SettingsBackupMotionSpec.transitionSurfaceAlpha(
                expansion = 0f,
                handoffExpansion = DiagnosticsEntryVisualSpec.SURFACE_HANDOFF_EXPANSION
            ),
            0f
        )
        assertEquals(
            1f,
            SettingsBackupMotionSpec.transitionSurfaceAlpha(
                expansion = DiagnosticsEntryVisualSpec.SURFACE_HANDOFF_EXPANSION,
                handoffExpansion = DiagnosticsEntryVisualSpec.SURFACE_HANDOFF_EXPANSION
            ),
            0f
        )
        assertEquals(
            0.5f,
            SettingsBackupMotionSpec.transitionSurfaceAlpha(
                expansion = DiagnosticsEntryVisualSpec.SURFACE_HANDOFF_EXPANSION / 2f,
                handoffExpansion = DiagnosticsEntryVisualSpec.SURFACE_HANDOFF_EXPANSION
            ),
            0.0001f
        )
        assertEquals(1f, SettingsBackupMotionSpec.collapsedChromeFraction(0f), 0f)
        assertEquals(0f, SettingsBackupMotionSpec.collapsedChromeFraction(0.28f), 0f)
        assertEquals(12f, DiagnosticsEntryVisualSpec.CORNER_RADIUS_DP, 0f)
        assertEquals(2f, DiagnosticsEntryVisualSpec.STROKE_WIDTH_DP, 0f)
    }

    private fun frame(
        expansion: Float,
        timing: SettingsBackupContentTiming = SettingsBackupContentTiming.TIMED
    ): SettingsBackupMotionFrame = SettingsBackupMotionSpec.frame(
        expansion = expansion,
        collapsedBounds = entryBounds,
        expandedBounds = windowBounds,
        collapsedTitleBounds = entryTitle,
        expandedTitleBounds = toolbarTitle,
        collapsedTitleTextSizePx = 45f,
        expandedTitleTextSizePx = 51f,
        collapsedCornerRadiusPx = 45f,
        contentTravelPx = 36f,
        contentTiming = timing
    )
}
