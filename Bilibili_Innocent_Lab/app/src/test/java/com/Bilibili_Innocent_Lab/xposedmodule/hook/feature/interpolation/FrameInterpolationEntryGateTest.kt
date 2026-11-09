package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationEntryGate.Condition
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationEntryGate.Evidence
import org.junit.Assert.*
import org.junit.Test

class FrameInterpolationEntryGateTest {
    private val complete = Evidence(true, true, true, true, true, true, true, true, true)

    @Test fun `entry opens only when every condition is evidenced`() {
        assertTrue(FrameInterpolationEntryGate.evaluate(complete).open)
        val missing = listOf(
            complete.copy(nativeReady = false) to Condition.NATIVE_READY,
            complete.copy(modelVerified = false) to Condition.MODEL_VERIFIED,
            complete.copy(mediaPermit = false) to Condition.MEDIA_PERMIT,
            complete.copy(verifiedClock = false) to Condition.VERIFIED_CLOCK,
            complete.copy(verifiedCapture = false) to Condition.VERIFIED_CAPTURE,
            complete.copy(presentationOwned = false) to Condition.PRESENTATION_OWNED,
            complete.copy(ownershipAndRecovery = false) to Condition.OWNERSHIP_AND_RECOVERY,
            complete.copy(inferenceWithinBudget = false) to Condition.INFERENCE_WITHIN_BUDGET,
            complete.copy(deviceValidated = false) to Condition.DEVICE_VALIDATED)
        missing.forEach { (evidence, condition) ->
            val verdict = FrameInterpolationEntryGate.evaluate(evidence)
            assertFalse(verdict.open)
            assertEquals(listOf(condition), verdict.missing)
        }
    }

    @Test fun `default evidence carries only what this repository proves`() {
        val verdict = FrameInterpolationEntryGate.evaluate(Evidence())
        assertFalse(Condition.OWNERSHIP_AND_RECOVERY in verdict.missing)
        assertFalse(Condition.DEVICE_VALIDATED in verdict.missing)
        assertTrue(verdict.missing.containsAll(FrameInterpolationEntryGate.RUNTIME_ENFORCED))
    }

    @Test fun `production entry leaves only runtime enforced conditions to playback`() {
        val verdict = FrameInterpolationEntryGate.production(nativeReady = false)
        assertFalse(verdict.open)
        assertTrue(FrameInterpolationEntryGate.entryOpen(nativeReady = false))
        assertEquals(FrameInterpolationEntryGate.RUNTIME_ENFORCED, verdict.missing.toSet())
        assertEquals(setOf(Condition.NATIVE_READY, Condition.MODEL_VERIFIED, Condition.MEDIA_PERMIT,
            Condition.INFERENCE_WITHIN_BUDGET), FrameInterpolationEntryGate.RUNTIME_ENFORCED)
        val ready = FrameInterpolationEntryGate.production(nativeReady = true)
        assertEquals(FrameInterpolationEntryGate.RUNTIME_ENFORCED - Condition.NATIVE_READY, ready.missing.toSet())
        assertTrue(FrameInterpolationEntryGate.entryOpen(nativeReady = true))
    }
}
