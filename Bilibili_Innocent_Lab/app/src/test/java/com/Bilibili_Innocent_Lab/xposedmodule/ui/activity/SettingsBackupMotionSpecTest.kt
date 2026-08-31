package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupMotionSpecTest {

    private val collapsedBounds = SettingsBackupMotionRect(15f, 420f, 1065f, 540f)
    private val expandedBounds = SettingsBackupMotionRect(0f, 0f, 1080f, 2160f)
    private val collapsedTitle = SettingsBackupMotionRect(65f, 445f, 500f, 490f)
    private val expandedTitle = SettingsBackupMotionRect(60f, 10f, 1020f, 58f)

    @Test
    fun collapsedFrameMatchesSourceCardAndLeavesUnderlyingCardVisible() {
        val frame = frame(0f)

        assertEquals(collapsedBounds, frame.bounds)
        assertEquals(45f, frame.cornerRadiusPx, 0f)
        assertEquals(0f, frame.surfaceAlpha, 0f)
        assertEquals(0f, frame.contentAlpha, 0f)
        assertEquals(36f, frame.contentTranslationYPx, 0f)
        assertEquals(collapsedTitle.left, frame.titleX, 0f)
        assertEquals(collapsedTitle.top, frame.titleY, 0f)
        assertEquals(45f, frame.titleTextSizePx, 0f)
    }

    @Test
    fun expandedFrameMatchesWindowAndToolbar() {
        val frame = frame(1f)

        assertEquals(expandedBounds, frame.bounds)
        assertEquals(0f, frame.cornerRadiusPx, 0f)
        assertEquals(1f, frame.surfaceAlpha, 0f)
        assertEquals(1f, frame.contentAlpha, 0f)
        assertEquals(0f, frame.contentTranslationYPx, 0f)
        assertEquals(expandedTitle.left, frame.titleX, 0f)
        assertEquals(expandedTitle.top, frame.titleY, 0f)
        assertEquals(51f, frame.titleTextSizePx, 0f)
    }

    @Test
    fun contentWaitsUntilContainerAndTitleAreNearTheirDestinations() {
        val early = frame(0.8f)
        val middle = frame(0.92f)
        val late = frame(0.99f)

        assertEquals(0f, early.contentAlpha, 0f)
        assertTrue(middle.contentAlpha in 0f..1f)
        assertTrue(middle.contentAlpha > early.contentAlpha)
        assertEquals(1f, late.contentAlpha, 0f)
        assertTrue(middle.contentTranslationYPx < early.contentTranslationYPx)
    }

    @Test
    fun predictiveContentUsesWiderRangeThanTimedAnimation() {
        val predictiveStart = frame(0.22f, SettingsBackupContentTiming.PREDICTIVE)
        val predictiveMiddle = frame(0.47f, SettingsBackupContentTiming.PREDICTIVE)
        val predictiveEnd = frame(0.72f, SettingsBackupContentTiming.PREDICTIVE)

        assertEquals(0f, predictiveStart.contentAlpha, 0f)
        assertTrue(predictiveMiddle.contentAlpha in 0f..1f)
        assertTrue(predictiveMiddle.contentAlpha > predictiveStart.contentAlpha)
        assertEquals(1f, predictiveEnd.contentAlpha, 0f)
        assertEquals(0f, frame(0.8f).contentAlpha, 0f)
        assertEquals(
            1f,
            frame(0.8f, SettingsBackupContentTiming.PREDICTIVE).contentAlpha,
            0f
        )
    }

    @Test
    fun progressOutsideRangeIsClampedToEndpoints() {
        assertEquals(frame(0f), frame(-2f))
        assertEquals(frame(1f), frame(3f))
    }

    @Test
    fun closeCurveSettlesAtEndpointAndKeepsBoundedContinuationTime() {
        assertEquals(1f, SettingsBackupMotionSpec.CLOSE_EASING_Y2, 0f)
        assertEquals(1f, SettingsBackupMotionSpec.COMMIT_EASING_Y2, 0f)
        assertTrue(SettingsBackupMotionSpec.CLOSE_EASING_X2 > SettingsBackupMotionSpec.COMMIT_EASING_X1)
        assertEquals(300L, SettingsBackupMotionSpec.closeDurationMs(300L, 1f, 80L))
        assertEquals(150L, SettingsBackupMotionSpec.closeDurationMs(300L, 0.5f, 80L))
        assertEquals(80L, SettingsBackupMotionSpec.closeDurationMs(300L, 0.1f, 80L))
        assertEquals(300L, SettingsBackupMotionSpec.closeDurationMs(300L, 3f, 80L))
    }

    @Test
    fun movingTitleRequiresSingleLineLeftToRightEndpoints() {
        assertTrue(SettingsBackupMotionSpec.canMoveTitle(1, 1, true, true))
        assertEquals(false, SettingsBackupMotionSpec.canMoveTitle(2, 1, true, true))
        assertEquals(false, SettingsBackupMotionSpec.canMoveTitle(1, 2, true, true))
        assertEquals(false, SettingsBackupMotionSpec.canMoveTitle(1, 1, false, true))
        assertEquals(false, SettingsBackupMotionSpec.canMoveTitle(1, 1, true, false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidCollapsedBoundsAreRejected() {
        SettingsBackupMotionSpec.frame(
            expansion = 0.5f,
            collapsedBounds = SettingsBackupMotionRect(10f, 10f, 10f, 20f),
            expandedBounds = expandedBounds,
            collapsedTitleBounds = collapsedTitle,
            expandedTitleBounds = expandedTitle,
            collapsedTitleTextSizePx = 45f,
            expandedTitleTextSizePx = 51f,
            collapsedCornerRadiusPx = 45f,
            contentTravelPx = 36f
        )
    }

    private fun frame(
        expansion: Float,
        contentTiming: SettingsBackupContentTiming = SettingsBackupContentTiming.TIMED
    ): SettingsBackupMotionFrame =
        SettingsBackupMotionSpec.frame(
            expansion = expansion,
            collapsedBounds = collapsedBounds,
            expandedBounds = expandedBounds,
            collapsedTitleBounds = collapsedTitle,
            expandedTitleBounds = expandedTitle,
            collapsedTitleTextSizePx = 45f,
            expandedTitleTextSizePx = 51f,
            collapsedCornerRadiusPx = 45f,
            contentTravelPx = 36f,
            contentTiming = contentTiming
        )
}
