package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModalTitleHandoffTest {
    @Test fun sourceHandoffKeepsPositionBaselineAndSizeExactlyAtTheSource() {
        for (progress in listOf(-1f, 0f, .03f, .06f, .10f, .12f)) {
            val motion = ModalTitleMotionSpec.motionProgress(progress)
            assertEquals(0f, motion, 0f)
            assertEquals(37f, ModalTitleMotionSpec.interpolate(37f, 173f, motion), 0f)
            assertEquals(541f, ModalTitleMotionSpec.interpolate(541f, 297f, motion), 0f)
            assertEquals(16f, ModalTitleMotionSpec.interpolate(16f, 19f, motion), 0f)
        }
    }

    @Test fun targetHandoffOnlyStartsAfterPositionBaselineAndSizeHaveSettled() {
        for (progress in listOf(.85f, .90f, .925f, .99f, 1f, 2f)) {
            val motion = ModalTitleMotionSpec.motionProgress(progress)
            assertEquals(1f, motion, 0f)
            assertEquals(173f, ModalTitleMotionSpec.interpolate(37f, 173f, motion), 0f)
            assertEquals(297f, ModalTitleMotionSpec.interpolate(541f, 297f, motion), 0f)
            assertEquals(19f, ModalTitleMotionSpec.interpolate(16f, 19f, motion), 0f)
        }
    }

    @Test fun movementBetweenHandoffsIsMonotonicBoundedAndSmoothAtBothStops() {
        var previous = 0f
        for (step in 0..1000) {
            val motion = ModalTitleMotionSpec.motionProgress(step / 1000f)
            assertTrue(motion in 0f..1f)
            assertTrue(motion >= previous)
            previous = motion
        }
        assertEquals(.5f, ModalTitleMotionSpec.motionProgress((.12f + .85f) / 2f), .000001f)
        val epsilon = .0001f
        for (boundary in listOf(.12f, .85f)) {
            val before = ModalTitleMotionSpec.motionProgress(boundary - epsilon)
            val at = ModalTitleMotionSpec.motionProgress(boundary)
            val after = ModalTitleMotionSpec.motionProgress(boundary + epsilon)
            assertTrue(abs(at - before) / epsilon < .01f)
            assertTrue(abs(after - at) / epsilon < .01f)
        }
    }

    @Test fun nativeTitlesAndOverlayHaveComplementaryOwnershipWithoutAVisibilityGap() {
        for (step in -10..1010) {
            val progress = step / 1000f
            val source = ModalTitleMotionSpec.sourceWeight(progress)
            val target = ModalTitleMotionSpec.targetWeight(progress)
            val overlay = ModalTitleMotionSpec.overlayWeight(progress)
            assertTrue(source in 0f..1f && target in 0f..1f && overlay in 0f..1f)
            assertEquals("No frame may lose or double its logical title opacity", 1f,
                source + target + overlay, .000001f)
            assertEquals("Native titles at different locations must not appear together", 0f,
                source * target, 0f)
        }
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), weights(0f), 0f)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), weights(1f), 0f)
        assertArrayEquals(floatArrayOf(.5f, 0f, .5f), weights(.06f), .000001f)
        assertArrayEquals(floatArrayOf(0f, .5f, .5f), weights(.925f), .000001f)
    }

    @Test fun travelingTitleHasOneDrawingOwnerAndNativeTitlesStayHidden() {
        for (step in 120..850) {
            assertArrayEquals(floatArrayOf(0f, 0f, 1f), weights(step / 1000f), 0f)
        }
    }

    @Test fun handoffWeightsAreContinuousAndDoNotJumpAtTheTravelBoundaries() {
        for (boundary in listOf(0f, .12f, .85f, 1f)) {
            val before = weights(boundary - .00001f)
            val after = weights(boundary + .00001f)
            for (index in before.indices) assertTrue(abs(before[index] - after[index]) < .0001f)
        }
    }

    @Test fun interruptedEntryCloseAndGestureCancellationReuseTheSameTitleFrame() {
        for (start in listOf(.03f, .12f, .35f, .70f, .85f, .925f, .99f)) {
            for (target in listOf(0f, 1f)) {
                for (velocity in listOf(-4f, 0f, 4f)) {
                    val continuation = NavigationMotionContinuation(start, target, velocity, 240L)
                    assertArrayEquals(frame(start), frame(continuation.value(0f)), 0f)
                    assertArrayEquals(frame(target), frame(continuation.value(1f)), .000001f)
                }
            }
            // Visiting either endpoint must not select a different entry/exit title profile.
            val before = frame(start)
            frame(0f)
            frame(1f)
            assertArrayEquals(before, frame(start), 0f)
        }
    }

    @Test fun nativeLayoutOffsetsIncludePaddingLinePositionAndOwnScrollingExactlyOnce() {
        assertEquals(104f, ModalTitleMotionSpec.layoutOffset(100f, 12f, -3f, 5f), 0f)
        assertEquals(92.25f, ModalTitleMotionSpec.layoutOffset(80f, 8.5f, 7f, 3.25f), 0f)
        assertEquals(-43f, ModalTitleMotionSpec.layoutOffset(-60f, 12f, 9f, 4f), 0f)
        assertEquals(27f, ModalTitleMotionSpec.layoutOffset(0f, 5f, 26f, 4f), 0f)
    }

    @Test fun targetHandoffWaitsUntilBothContentProfilesAreOpaqueAndUntranslated() {
        val geometry = IconAnchoredMotionGeometry(
            collapsedBounds = SettingsBackupMotionRect(24f, 840f, 384f, 900f),
            expandedBounds = SettingsBackupMotionRect(32f, 160f, 368f, 780f),
            collapsedRadiusPx = 30f,
            expandedRadiusPx = 28f,
            contentTravelCapPx = 20f
        )
        val frame = IconAnchoredMotionFrameBuffer()
        for (timing in IconAnchoredContentTiming.entries) {
            for (progress in listOf(.85f, .90f, .925f, 1f)) {
                assertEquals(1f, IconAnchoredMotionSpec.contentFraction(progress, timing), 0f)
                IconAnchoredMotionSpec.fillFrame(frame, progress, geometry, timing)
                assertEquals(1f, frame.contentAlpha, 0f)
                assertEquals(0f, frame.contentTranslationXPx, 0f)
                assertEquals(0f, frame.contentTranslationYPx, 0f)
            }
        }
    }

    private fun weights(progress: Float): FloatArray = floatArrayOf(
        ModalTitleMotionSpec.sourceWeight(progress),
        ModalTitleMotionSpec.targetWeight(progress),
        ModalTitleMotionSpec.overlayWeight(progress)
    )

    private fun frame(progress: Float): FloatArray = floatArrayOf(
        ModalTitleMotionSpec.motionProgress(progress),
        ModalTitleMotionSpec.sourceWeight(progress),
        ModalTitleMotionSpec.targetWeight(progress),
        ModalTitleMotionSpec.overlayWeight(progress)
    )
}
