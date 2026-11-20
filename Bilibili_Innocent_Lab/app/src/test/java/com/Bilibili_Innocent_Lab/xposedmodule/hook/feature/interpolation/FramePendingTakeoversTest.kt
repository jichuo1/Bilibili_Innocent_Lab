package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import org.junit.Assert.*
import org.junit.Test

class FramePendingTakeoversTest {
    @Test fun rifeAhbWaitsForItsModelAndOnlyRunsOncePerDecoderAttempt() {
        val attempt = FramePlaybackRoutes.Attempt()
        assertFalse(attempt.claimRifeAhb(false))
        assertTrue(attempt.claimRifeAhb(true))
        assertFalse(attempt.claimRifeAhb(true))
        assertTrue(FramePlaybackRoutes.Attempt().claimRifeAhb(true))
    }

    @Test fun delayedMediaKeepsAdmissionOpenWithCappedProbesOnlyForMissingEvidence() {
        val attempt = FramePlaybackRoutes.Attempt()
        val delays = List(32) {
            assertTrue(attempt.waitForMedia(FrameMediaPolicy.Reason.UNKNOWN_AUDIO_PATH))
            attempt.mediaRetryDelayMs
        }
        assertEquals(150L, delays.first())
        assertEquals(3_000L, delays.last())
        assertTrue(delays.zipWithNext().all { (previous, next) -> next in previous..3_000L })
        assertTrue(attempt.waitForMedia(FrameMediaPolicy.Reason.UNKNOWN_COLOR))
        assertTrue(attempt.waitForMedia(FrameMediaPolicy.Reason.UNKNOWN_BIT_DEPTH))
        assertFalse(attempt.waitForMedia(FrameMediaPolicy.Reason.HDR_PQ))
        assertFalse(attempt.waitForMedia(FrameMediaPolicy.Reason.PROTECTED_CONTENT))
        assertFalse(attempt.waitForMedia(FrameMediaPolicy.Reason.READY))
    }

    @Test fun pendingRifeModelDoesNotHoldOtherQcomCandidates() {
        val pending = FramePendingTakeovers<Any, String>()
        val a = Any()
        val b = Any()
        pending.await(a, "rife", { false }) {}
        pending.await(b, "qcom", { true }) {}
        assertEquals(listOf(b to "qcom"), pending.takeReady { it == "qcom" })
        assertFalse(pending.hasReady { it == "qcom" })
        assertEquals(listOf(a to "rife"), pending.takeReady { true })
    }
    @Test fun readyBetweenTheInitialCheckAndRegistrationStillWakesTheDecoder() {
        val pending = FramePendingTakeovers<Any, String>()
        val codec = Any()
        val initialReady = false
        var ready = initialReady
        assertFalse(initialReady)
        // The worker announces Ready before the configure callback stores its pending decoder.
        ready = true
        assertTrue(pending.take().isEmpty())
        var wakes = 0
        pending.await(codec, "video", { ready }) { wakes++ }
        assertEquals(1, wakes)
        assertEquals(listOf(codec to "video"), pending.take())
    }

    @Test fun readyAfterRegistrationAndNewArrivalsDuringADrainAreBothRetained() {
        val pending = FramePendingTakeovers<Any, Int>()
        val first = Any(); val second = Any()
        var ready = false; var wakes = 0
        pending.await(first, 1, { ready }) { wakes++ }
        assertEquals(0, wakes)
        ready = true
        assertEquals(listOf(first to 1), pending.take())
        pending.await(second, 2, { ready }) { wakes++ }
        assertTrue(pending.hasPending())
        assertEquals(1, wakes)
        assertEquals(listOf(second to 2), pending.take())
        assertFalse(pending.hasPending())
    }

    @Test fun replacementRevokesAlreadyQueuedRestorationAndAudioCleanupCallbacks() {
        val generation = FrameDecoderGeneration<Any>()
        val codec = Any()
        val old = generation.begin(codec)
        var surface = "new"
        var delay = 150
        val queuedRestore = { if (old.isActive()) { surface = "old"; delay = 0 } }
        val queuedAudioCleanup = { if (old.isActive()) delay = 0 }
        val next = generation.replace(codec, old) {}!!
        queuedRestore()
        queuedAudioCleanup()
        assertEquals("new", surface)
        assertEquals(150, delay)
        assertFalse(old.isActive())
        assertTrue(next.isActive())
        generation.end(codec)
        assertFalse(next.isActive())
        assertNull(generation.replace(codec, next) { fail("released decoder cannot acquire a replacement") })
    }
}
