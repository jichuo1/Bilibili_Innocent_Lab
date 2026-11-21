package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 性能报告 P1-07：准备阶段不再逐次 allocateDirect，且能力测量必须检查每一步的成功状态。
 * 复用与状态判定的行为由 FrameScratchArenaTest / FrameCapabilityProbeTest 覆盖；这里锁定接线。
 */
class FramePreparationScratchContractTest {
    private val holder = SourceContract.read("hook/feature/interpolation/FrameInterpolationEngineHolder.kt")

    fun holderNoLongerAllocatesPreparationBuffersPerCall() {
        assertFalse(holder.contains("ByteBuffer.allocateDirect("))
        assertFalse(holder.contains("fun medianMs("))
        assertTrue(holder.contains("withScratch(WARM_SIZES + capabilitySizes)"))
        assertTrue(holder.contains("FrameCapabilityProbe(arena)"))
        assertTrue(holder.contains("withScratch(listOf(size), inputs = 2)"))
    }

    fun warmupCompletionUsesItsEngineAndHandlesFailedInference() {
        val queued = holder.after("fun prewarm(width:").before("private fun warm(")
        assertTrue(queued.contains("engine.claimWarmup(size)"))
        assertTrue(queued.contains("backend === engine && ready()"))
        assertTrue(queued.after("finally {").contains("engine.finishWarmup(size, status)"))
        assertTrue(queued.contains("engine.finishWarmup(size, FrameInterpolationBackend.Status.BUSY)"))
        val warm = holder.after("private fun warm(").before("private fun robustMs(")
        assertTrue(warm.contains("if (status != FrameInterpolationBackend.Status.OK) retireLostDevice(engine)"))
    }

    fun scratchSessionAlwaysReleasesItsReferences() {
        val session = holder.after("private fun withScratch(").before("@Volatile private var modelName")
        assertTrue(session.contains("FrameScratchArena.withMemory(bytes, inputs)"))
        val arena = SourceContract.read("hook/feature/interpolation/FrameScratchArena.kt")
        val scope = arena.after("fun withMemory(")
        assertTrue(scope.contains("FramePreparationMemory.withStorage("))
        assertTrue(scope.after("finally {").contains("arena.invalidate()"))
        val memory = SourceContract.read("hook/feature/interpolation/FramePreparationMemory.kt")
        assertTrue(memory.contains("ManagedMemoryDomain.system("))
        assertTrue(memory.contains(".use { allocation ->"))
        assertTrue(memory.contains("allocation.borrowWrite().use { lease ->"))
        assertTrue(memory.contains("lease.withDirectBuffer { storage ->"))
    }

    fun modelBytesAreVerifiedInTheSameLeaseThatSuppliesTheSynchronousLoad() {
        val backend = SourceContract.read("hook/feature/interpolation/FrameInterpolationBackend.kt")
        val loan = backend.after("FramePreparationMemory.withStorage(").before("opened = openVerifiedModel(")
        assertTrue(loan.contains("readVerified(paramFile, FrameModelSpec.PARAM, param)"))
        assertTrue(loan.contains("readVerified(weightsFile, FrameModelSpec.WEIGHTS, weights)"))
        assertTrue(backend.after("private fun readVerified(").before("private fun planeValid(")
            .contains("FrameModelSpec.matches(part, input)"))
        assertTrue(backend.contains("MediaNative.frameLoad(handle, param, weights)"))
        assertFalse(backend.contains("ByteBuffer.allocateDirect"))
    }

    fun failedStepsNeverBecomeUsableTiers() {
        val measure = holder.after("private fun measureCapability(").before("/** 为宿主实际尺寸预热")
        assertTrue(measure.contains("measured != null && measured.also { chosenMs = it } <="))
        assertTrue(measure.contains("engine.probeInterpolation("))
        val backend = SourceContract.read("hook/feature/interpolation/FrameInterpolationBackend.kt")
        val evidence = backend.after("fun probeInterpolation(").before("fun unreliablePermille(")
        assertTrue(evidence.contains("if (status != Status.OK) return status"))
        assertTrue(evidence.contains("frameLastFrameKind(handle) == FRAME_INFERRED"))
        assertTrue(evidence.contains("frameLastEffectiveFlowScale(handle) == flowScale"))
        val robust = holder.after("private fun robustMs(").before("@Synchronized private fun fail(")
        assertTrue(robust.contains("is FrameCapabilityProbe.Result.Failed"))
        assertTrue(robust.contains("engine.capability.failed"))
        assertTrue(robust.contains("null"))
    }

    fun probeChecksPreRunAndEveryTimedSampleAndKeepsSecondFastest() {
        val probe = SourceContract.read("hook/feature/interpolation/FrameCapabilityProbe.kt")
        assertTrue(probe.contains("val warm = step(0)"))
        assertTrue(probe.contains("if (warm != FrameInterpolationBackend.Status.OK) return Result.Failed(warm, 0)"))
        assertTrue(probe.contains("if (status != FrameInterpolationBackend.Status.OK) return Result.Failed(status, index + 1)"))
        assertTrue(probe.contains("Result.Measured(times[1], times)"))
        assertTrue(probe.before("val started = nanoTime()").contains("arena.input(it, bytes)"))
    }

    fun onlyConfirmedDeviceLossRetiresTheCurrentEngineAndSchedulesReload() {
        val backend = SourceContract.read("hook/feature/interpolation/FrameInterpolationBackend.kt")
        assertTrue(backend.contains("MediaNative.frameDeviceLossStatus(handle) == MGC_DEVICE_LOST"))
        val retire = holder.after("private fun retireLostDevice(").before("@Synchronized private fun fail(")
        assertTrue(retire.before("backend = null").contains("if (backend !== engine || !engine.deviceLost()) return false"))
        assertTrue(retire.contains("engine.close()"))
        assertTrue(retire.after("finally {").contains("fail(\"device-lost\", FramePreparationRetry.Work.LOAD)"))
    }

    fun scratchNeverBorrowsLedgerFramesNorHoldsDirectBuffersForever() {
        val arena = SourceContract.read("hook/feature/interpolation/FrameScratchArena.kt")
        val code = arena.after("internal class FrameScratchArena(")
        assertFalse(code.contains("FrameBufferLedger."))
        assertFalse(code.contains("ByteBuffer.allocateDirect"))
        assertTrue(code.contains("slot.asReadOnlyBuffer()"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FramePreparationScratchContractTest) -> Unit) {
            val s = FramePreparationScratchContractTest()
            block(s)
        }
        runIsolated { s -> s.holderNoLongerAllocatesPreparationBuffersPerCall() }
        runIsolated { s -> s.warmupCompletionUsesItsEngineAndHandlesFailedInference() }
        runIsolated { s -> s.scratchSessionAlwaysReleasesItsReferences() }
        runIsolated { s -> s.modelBytesAreVerifiedInTheSameLeaseThatSuppliesTheSynchronousLoad() }
        runIsolated { s -> s.failedStepsNeverBecomeUsableTiers() }
        runIsolated { s -> s.probeChecksPreRunAndEveryTimedSampleAndKeepsSecondFastest() }
        runIsolated { s -> s.onlyConfirmedDeviceLossRetiresTheCurrentEngineAndSchedulesReload() }
        runIsolated { s -> s.scratchNeverBorrowsLedgerFramesNorHoldsDirectBuffersForever() }
    }
}
