package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 性能报告 P1-07：准备阶段不再逐次 allocateDirect，且能力测量必须检查每一步的成功状态。
 * 复用与状态判定的行为由 FrameScratchArenaTest / FrameCapabilityProbeTest 覆盖；这里锁定接线。
 */
class FramePreparationScratchContractTest {
    private val holder = SourceContract.read("hook/feature/interpolation/FrameInterpolationEngineHolder.kt")

    @Test fun holderNoLongerAllocatesPreparationBuffersPerCall() {
        assertFalse(holder.contains("ByteBuffer.allocateDirect("))
        assertFalse(holder.contains("fun medianMs("))
        assertTrue(holder.contains("withScratch(WARM_SIZES + capabilitySizes)"))
        assertTrue(holder.contains("FrameCapabilityProbe(arena, scratchHint(capabilitySizes))"))
    }

    @Test fun scratchSessionAlwaysReleasesItsReferences() {
        val session = holder.after("private inline fun <T> withScratch(").before("private fun scratchHint(")
        assertTrue(session.contains("finally {"))
        assertTrue(session.after("finally {").contains("arena.close()"))
    }

    @Test fun failedStepsNeverBecomeUsableTiers() {
        val measure = holder.after("private fun measureCapability(").before("/** 为宿主实际尺寸预热")
        assertTrue(measure.contains("measured != null && measured.also { chosenMs = it } <="))
        val robust = holder.after("private fun robustMs(").before("@Synchronized private fun fail(")
        assertTrue(robust.contains("is FrameCapabilityProbe.Result.Failed"))
        assertTrue(robust.contains("engine.capability.failed"))
        assertTrue(robust.contains("null"))
    }

    @Test fun probeChecksPreRunAndEveryTimedSampleAndKeepsSecondFastest() {
        val probe = SourceContract.read("hook/feature/interpolation/FrameCapabilityProbe.kt")
        assertTrue(probe.contains("val warm = step(0)"))
        assertTrue(probe.contains("if (warm != FrameInterpolationBackend.Status.OK) return Result.Failed(warm, 0)"))
        assertTrue(probe.contains("if (status != FrameInterpolationBackend.Status.OK) return Result.Failed(status, index + 1)"))
        assertTrue(probe.contains("Result.Measured(times[1], times)"))
        assertEquals("zeroing precedes the timer", true,
            probe.indexOf("arena.zeroed(it, bytes, hint)") in 0 until probe.indexOf("val started = nanoTime()"))
    }

    @Test fun scratchNeverBorrowsLedgerFramesNorHoldsDirectBuffersForever() {
        val arena = SourceContract.read("hook/feature/interpolation/FrameScratchArena.kt")
        val code = arena.substringAfter("internal class FrameScratchArena(")
        assertFalse(code.contains("FrameBufferLedger."))
        assertFalse(code.contains("object ") && code.contains("companion object").not())
        assertTrue(code.contains("override fun close()"))
        assertTrue(code.contains("slots.fill(null)"))
    }
}
