package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameEngineGenerationTest {
    fun sessionCannotBorrowAReplacementEngineAfterDeviceLoss() {
        val holder = FrameInterpolationEngineHolder(File("module.apk"), File("cache"), { null }, { _, _ -> })
        val unprepared = holder.synthesizer()
        val state = holder.javaClass.getDeclaredField("state").apply { isAccessible = true }
        val current = holder.javaClass.getDeclaredField("backend").apply { isAccessible = true }
        current.set(holder, engine(1L))
        state.set(holder, FrameInterpolationEngineHolder.State.READY)
        val old = holder.synthesizer()
        state.set(holder, FrameInterpolationEngineHolder.State.FAILED)
        current.set(holder, null)
        assertEquals(FrameInterpolationBackend.Status.GPU_UNAVAILABLE, probe(old))

        current.set(holder, engine(2L))
        state.set(holder, FrameInterpolationEngineHolder.State.READY)
        assertEquals(FrameInterpolationBackend.Status.NOT_LOADED, probe(unprepared))
        assertEquals(FrameInterpolationBackend.Status.GPU_UNAVAILABLE, probe(old))
    }

    fun warmupFailureCanRetryWithoutClearingTheReplacementEnginesClaim() {
        val first = engine(1L)
        val replacement = engine(2L)
        val size = 640 to 360
        assertTrue(first.claimWarmup(size))
        assertFalse(first.claimWarmup(size))
        assertTrue(replacement.claimWarmup(size))
        first.finishWarmup(size, FrameInterpolationBackend.Status.INFERENCE_FAILED)
        assertTrue(first.claimWarmup(size))
        assertFalse(replacement.claimWarmup(size))
        replacement.finishWarmup(size, FrameInterpolationBackend.Status.OK)
        assertFalse(replacement.claimWarmup(size))
    }

    private fun engine(handle: Long) = FrameInterpolationBackend::class.java
        .getDeclaredConstructor(Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
        .apply { isAccessible = true }.newInstance(handle, 64L)

    private fun probe(synthesizer: FrameInterpolationSession.Synthesizer): FrameInterpolationBackend.Status {
        val input = ByteBuffer.allocateDirect(3)
        return synthesizer.process(input, input, input, FrameBufferLedger.Format(1, 1, 3), 0.5f, 1L, 2L)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FrameEngineGenerationTest) -> Unit) {
            val s = FrameEngineGenerationTest()
            block(s)
        }
        runIsolated { s -> s.sessionCannotBorrowAReplacementEngineAfterDeviceLoss() }
        runIsolated { s -> s.warmupFailureCanRetryWithoutClearingTheReplacementEnginesClaim() }
    }
}
