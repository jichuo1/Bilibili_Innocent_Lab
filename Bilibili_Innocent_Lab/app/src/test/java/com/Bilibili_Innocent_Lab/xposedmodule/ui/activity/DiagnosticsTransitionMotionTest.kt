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
        assertTrue(DiagnosticsEntryVisualSpec.scrimAlpha(darkTheme = false) > 0x38)
        assertTrue(DiagnosticsEntryVisualSpec.scrimAlpha(darkTheme = true) > 0x38)
    }

    @Test
    fun sourceWindowCoordinatesAvoidCrossActivitySystemBarOffset() {
        val origin = transitionOrigin(
            localEntry = entryBounds,
            localTitle = entryTitle,
            sourceWindowTopOnScreen = 72f
        )

        val mapped = requireNotNull(
            DiagnosticsTransitionCoordinateMapper.map(
                origin = origin,
                destinationWindowWidth = 1080,
                destinationWindowHeight = 2160,
                destinationWindowLeftOnScreen = 0f,
                destinationWindowTopOnScreen = 24f,
                tolerancePx = 4f
            )
        )

        assertTrue(mapped.usedSourceWindowCoordinates)
        assertEquals(entryBounds, mapped.entryBounds)
        assertEquals(entryTitle, mapped.titleBounds)
    }

    @Test
    fun invalidSourceWindowCoordinatesUseValidatedScreenFallback() {
        val origin = transitionOrigin(
            localEntry = SettingsBackupMotionRect(-400f, -300f, -200f, -100f),
            localTitle = SettingsBackupMotionRect(-380f, -280f, -240f, -220f),
            sourceWindowTopOnScreen = 24f
        )

        val mapped = requireNotNull(
            DiagnosticsTransitionCoordinateMapper.map(
                origin = origin,
                destinationWindowWidth = 1080,
                destinationWindowHeight = 2160,
                destinationWindowLeftOnScreen = 0f,
                destinationWindowTopOnScreen = 24f,
                tolerancePx = 4f
            )
        )

        assertTrue(!mapped.usedSourceWindowCoordinates)
        assertEquals(entryBounds, mapped.entryBounds)
        assertEquals(entryTitle, mapped.titleBounds)
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

    private fun transitionOrigin(
        localEntry: SettingsBackupMotionRect,
        localTitle: SettingsBackupMotionRect,
        sourceWindowTopOnScreen: Float
    ): DiagnosticsTransitionOrigin = DiagnosticsTransitionOrigin(
        entryBoundsOnScreen = entryBounds.offsetBy(dy = sourceWindowTopOnScreen),
        titleBoundsOnScreen = entryTitle.offsetBy(dy = sourceWindowTopOnScreen),
        entryBoundsInSourceWindow = localEntry,
        titleBoundsInSourceWindow = localTitle,
        sourceWindowBoundsOnScreen = SettingsBackupMotionRect(
            0f,
            sourceWindowTopOnScreen,
            1080f,
            sourceWindowTopOnScreen + 2160f
        ),
        titleTextSizePx = 45f,
        titleLineCount = 1,
        titleLayoutDirection = android.view.View.LAYOUT_DIRECTION_LTR,
        sourceWindowWidth = 1080,
        sourceWindowHeight = 2160,
        displayId = 0,
        displayRotation = 0
    )

    private fun SettingsBackupMotionRect.offsetBy(
        dx: Float = 0f,
        dy: Float = 0f
    ) = SettingsBackupMotionRect(left + dx, top + dy, right + dx, bottom + dy)
}
