package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SkinId
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinPreferenceState
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinRecoveryGuard
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinRecoveryReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinRecoveryGuardTest {

    private val attemptA = "attempt-a"
    private val attemptB = "attempt-b"
    private val processAttempt = "attempt-process"

    @Test
    fun `liquid selection stays pending until matching health confirmation`() {
        val pending = SkinRecoveryGuard.beginSelection(
            current = SkinPreferenceState.MATERIAL_DEFAULT,
            target = SkinId.LIQUID,
            currentLiquidRendererVersion = 7,
            newActivationAttemptId = attemptA
        )

        assertEquals(SkinPreferenceState.pendingLiquid(7, attemptA), pending.state)
        assertEquals(SkinId.MATERIAL_YOU, pending.state.lastKnownGoodSkin)
        assertTrue(pending.shouldPersist)
        assertEquals(SkinRecoveryReason.LIQUID_VALIDATION_PENDING, pending.reason)

        val confirmed = SkinRecoveryGuard.confirmLiquidHealthy(
            current = pending.state,
            confirmedRendererVersion = 7,
            activationAttemptId = attemptA
        )

        assertEquals(SkinPreferenceState.confirmedLiquid(7, attemptA), confirmed.state)
        assertTrue(confirmed.shouldPersist)
        assertEquals(SkinRecoveryReason.LIQUID_HEALTH_CONFIRMED, confirmed.reason)
    }

    @Test
    fun `unfinished pending rolls back on next process start`() {
        val recovered = SkinRecoveryGuard.onProcessStart(
            persisted = SkinPreferenceState.pendingLiquid(11, attemptA),
            currentLiquidRendererVersion = 11,
            newActivationAttemptId = processAttempt
        )

        assertEquals(SkinPreferenceState.MATERIAL_DEFAULT, recovered.state)
        assertEquals(SkinId.MATERIAL_YOU, recovered.selectedSkin)
        assertTrue(recovered.shouldPersist)
        assertEquals(SkinRecoveryReason.INCOMPLETE_PENDING_ROLLED_BACK, recovered.reason)
    }

    @Test
    fun `confirmed liquid remains ready for the same renderer version`() {
        val ready = SkinRecoveryGuard.onProcessStart(
            persisted = SkinPreferenceState.confirmedLiquid(3, attemptA),
            currentLiquidRendererVersion = 3,
            newActivationAttemptId = processAttempt
        )

        assertEquals(SkinPreferenceState.confirmedLiquid(3, attemptA), ready.state)
        assertFalse(ready.shouldPersist)
        assertEquals(SkinRecoveryReason.READY, ready.reason)
    }

    @Test
    fun `renderer version change enters revalidation with material rollback point`() {
        val revalidation = SkinRecoveryGuard.onProcessStart(
            persisted = SkinPreferenceState.confirmedLiquid(3, attemptA),
            currentLiquidRendererVersion = 4,
            newActivationAttemptId = processAttempt
        )

        assertEquals(
            SkinPreferenceState.pendingLiquid(4, processAttempt),
            revalidation.state
        )
        assertEquals(SkinId.MATERIAL_YOU, revalidation.state.lastKnownGoodSkin)
        assertTrue(revalidation.shouldPersist)
        assertEquals(
            SkinRecoveryReason.RENDERER_REVALIDATION_REQUIRED,
            revalidation.reason
        )
    }

    @Test
    fun `failed renderer revalidation returns to material`() {
        val pending = SkinRecoveryGuard.onProcessStart(
            persisted = SkinPreferenceState.confirmedLiquid(3, attemptA),
            currentLiquidRendererVersion = 4,
            newActivationAttemptId = processAttempt
        ).state

        val failed = SkinRecoveryGuard.onLiquidValidationFailed(
            current = pending,
            failedRendererVersion = 4,
            activationAttemptId = processAttempt
        )

        assertEquals(SkinPreferenceState.MATERIAL_DEFAULT, failed.state)
        assertTrue(failed.shouldPersist)
        assertEquals(SkinRecoveryReason.LIQUID_VALIDATION_FAILED, failed.reason)
    }

    @Test
    fun `successful renderer revalidation promotes only the new version`() {
        val pending = SkinRecoveryGuard.onProcessStart(
            persisted = SkinPreferenceState.confirmedLiquid(3, attemptA),
            currentLiquidRendererVersion = 4,
            newActivationAttemptId = processAttempt
        ).state

        val confirmed = SkinRecoveryGuard.confirmLiquidHealthy(
            pending,
            4,
            processAttempt
        )

        assertEquals(
            SkinPreferenceState.confirmedLiquid(4, processAttempt),
            confirmed.state
        )
        assertTrue(confirmed.shouldPersist)
    }

    @Test
    fun `switching to material is immediate and clears pending state`() {
        listOf(
            SkinPreferenceState.pendingLiquid(5, attemptA),
            SkinPreferenceState.confirmedLiquid(5, attemptA)
        ).forEach { current ->
            val material = SkinRecoveryGuard.beginSelection(
                current = current,
                target = SkinId.MATERIAL_YOU,
                currentLiquidRendererVersion = 5,
                newActivationAttemptId = attemptB
            )

            assertEquals(SkinPreferenceState.MATERIAL_DEFAULT, material.state)
            assertTrue(material.shouldPersist)
            assertEquals(SkinRecoveryReason.MATERIAL_SELECTED, material.reason)
        }
    }

    @Test
    fun `late liquid health confirmation cannot override material selection`() {
        val material = SkinRecoveryGuard.beginSelection(
            current = SkinPreferenceState.pendingLiquid(8, attemptA),
            target = SkinId.MATERIAL_YOU,
            currentLiquidRendererVersion = 8,
            newActivationAttemptId = attemptB
        ).state

        val late = SkinRecoveryGuard.confirmLiquidHealthy(material, 8, attemptA)

        assertEquals(SkinPreferenceState.MATERIAL_DEFAULT, late.state)
        assertFalse(late.shouldPersist)
        assertEquals(
            SkinRecoveryReason.STALE_HEALTH_CONFIRMATION_IGNORED,
            late.reason
        )
    }

    @Test
    fun `health confirmation from an older renderer cannot promote newer pending`() {
        val pending = SkinPreferenceState.pendingLiquid(9, attemptB)

        val stale = SkinRecoveryGuard.confirmLiquidHealthy(pending, 8, attemptA)

        assertEquals(pending, stale.state)
        assertFalse(stale.shouldPersist)
        assertEquals(
            SkinRecoveryReason.STALE_HEALTH_CONFIRMATION_IGNORED,
            stale.reason
        )
    }

    @Test
    fun `selecting already confirmed liquid with same renderer is a no-op`() {
        val current = SkinPreferenceState.confirmedLiquid(12, attemptA)

        val decision = SkinRecoveryGuard.beginSelection(
            current = current,
            target = SkinId.LIQUID,
            currentLiquidRendererVersion = 12,
            newActivationAttemptId = attemptB
        )

        assertEquals(current, decision.state)
        assertFalse(decision.shouldPersist)
        assertEquals(SkinRecoveryReason.READY, decision.reason)
    }

    @Test
    fun `old success from same renderer version cannot confirm a retry`() {
        val retry = SkinPreferenceState.pendingLiquid(7, attemptB)

        val stale = SkinRecoveryGuard.confirmLiquidHealthy(retry, 7, attemptA)

        assertEquals(retry, stale.state)
        assertFalse(stale.shouldPersist)
        assertEquals(
            SkinRecoveryReason.STALE_HEALTH_CONFIRMATION_IGNORED,
            stale.reason
        )
    }

    @Test
    fun `old failure cannot roll back a newly confirmed attempt`() {
        val confirmed = SkinPreferenceState.confirmedLiquid(7, attemptB)

        val stale = SkinRecoveryGuard.onLiquidValidationFailed(
            current = confirmed,
            failedRendererVersion = 7,
            activationAttemptId = attemptA
        )

        assertEquals(confirmed, stale.state)
        assertFalse(stale.shouldPersist)
        assertEquals(
            SkinRecoveryReason.STALE_VALIDATION_FAILURE_IGNORED,
            stale.reason
        )
    }

    @Test
    fun `failure from the active confirmed attempt rolls back to material`() {
        val confirmed = SkinPreferenceState.confirmedLiquid(7, attemptA)

        val failed = SkinRecoveryGuard.onLiquidValidationFailed(
            current = confirmed,
            failedRendererVersion = 7,
            activationAttemptId = attemptA
        )

        assertEquals(SkinPreferenceState.MATERIAL_DEFAULT, failed.state)
        assertTrue(failed.shouldPersist)
        assertEquals(SkinRecoveryReason.LIQUID_VALIDATION_FAILED, failed.reason)
    }

    @Test
    fun `negative runtime renderer versions are rejected as programmer errors`() {
        assertThrows(IllegalArgumentException::class.java) {
            SkinRecoveryGuard.onProcessStart(
                SkinPreferenceState.MATERIAL_DEFAULT,
                -1,
                processAttempt
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SkinRecoveryGuard.beginSelection(
                SkinPreferenceState.MATERIAL_DEFAULT,
                SkinId.LIQUID,
                -1,
                attemptA
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SkinRecoveryGuard.confirmLiquidHealthy(
                SkinPreferenceState.pendingLiquid(0, attemptA),
                -1,
                attemptA
            )
        }
    }
}
