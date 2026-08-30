package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoRootUpgradeRecoveryCoordinatorTest {

    @Test
    fun `recovers only authorized enabled state without a ready snapshot`() {
        assertTrue(
            NoRootUpgradeRecoveryCoordinator.shouldRecoverSnapshot(
                authorized = true,
                desiredEnabled = true,
                hasReadySnapshot = false
            )
        )
    }

    @Test
    fun `does not recover when terms are unauthorized`() {
        assertFalse(
            NoRootUpgradeRecoveryCoordinator.shouldRecoverSnapshot(
                authorized = false,
                desiredEnabled = true,
                hasReadySnapshot = false
            )
        )
    }

    @Test
    fun `does not recover when no-root support is disabled`() {
        assertFalse(
            NoRootUpgradeRecoveryCoordinator.shouldRecoverSnapshot(
                authorized = true,
                desiredEnabled = false,
                hasReadySnapshot = false
            )
        )
    }

    @Test
    fun `does not rewrite a strictly valid snapshot already synced to remote store`() {
        assertFalse(
            NoRootUpgradeRecoveryCoordinator.shouldRecoverSnapshot(
                authorized = true,
                desiredEnabled = true,
                hasReadySnapshot = true
            )
        )
    }

    @Test
    fun `enabled export is not ready until its revision is remotely synced`() {
        assertFalse(
            NoRootUpgradeRecoveryCoordinator.isRecoveryExportReady(
                exportValid = true,
                exportEnabled = true,
                revision = 42L,
                remoteSynced = false
            )
        )
        assertTrue(
            NoRootUpgradeRecoveryCoordinator.isRecoveryExportReady(
                exportValid = true,
                exportEnabled = true,
                revision = 42L,
                remoteSynced = true
            )
        )
    }

    @Test
    fun `first failed attempt waits for the only retry`() {
        assertEquals(
            NoRootUpgradeRecoveryCoordinator.RecoveryPhase.RETRY_WAIT,
            NoRootUpgradeRecoveryCoordinator.transitionAfterAttempt(
                stillNeedsRecovery = true,
                attemptsStarted = 1
            )
        )
    }

    @Test
    fun `second failed attempt exhausts the per-process retry budget`() {
        assertEquals(
            NoRootUpgradeRecoveryCoordinator.RecoveryPhase.EXHAUSTED,
            NoRootUpgradeRecoveryCoordinator.transitionAfterAttempt(
                stillNeedsRecovery = true,
                attemptsStarted = NoRootUpgradeRecoveryCoordinator.MAX_ATTEMPTS_PER_PROCESS
            )
        )
    }

    @Test
    fun `ready export completes without retry`() {
        assertEquals(
            NoRootUpgradeRecoveryCoordinator.RecoveryPhase.READY,
            NoRootUpgradeRecoveryCoordinator.transitionAfterAttempt(
                stillNeedsRecovery = false,
                attemptsStarted = 1
            )
        )
    }

    @Test
    fun `retry starts only after cooldown and before budget is exhausted`() {
        val retryAt = 20_000L
        assertFalse(
            NoRootUpgradeRecoveryCoordinator.shouldStartRetry(
                phase = NoRootUpgradeRecoveryCoordinator.RecoveryPhase.RETRY_WAIT,
                attemptsStarted = 1,
                nowElapsedMs = retryAt - 1L,
                retryNotBeforeElapsedMs = retryAt
            )
        )
        assertTrue(
            NoRootUpgradeRecoveryCoordinator.shouldStartRetry(
                phase = NoRootUpgradeRecoveryCoordinator.RecoveryPhase.RETRY_WAIT,
                attemptsStarted = 1,
                nowElapsedMs = retryAt,
                retryNotBeforeElapsedMs = retryAt
            )
        )
        assertFalse(
            NoRootUpgradeRecoveryCoordinator.shouldStartRetry(
                phase = NoRootUpgradeRecoveryCoordinator.RecoveryPhase.RETRY_WAIT,
                attemptsStarted = NoRootUpgradeRecoveryCoordinator.MAX_ATTEMPTS_PER_PROCESS,
                nowElapsedMs = retryAt,
                retryNotBeforeElapsedMs = retryAt
            )
        )
    }

    @Test
    fun `first host query may start recovery only after its current export is built`() {
        assertTrue(
            NoRootUpgradeRecoveryCoordinator.shouldStartAttempt(
                phase = NoRootUpgradeRecoveryCoordinator.RecoveryPhase.NOT_STARTED,
                attemptsStarted = 0,
                nowElapsedMs = 1L,
                retryNotBeforeElapsedMs = 0L
            )
        )
        assertFalse(
            NoRootUpgradeRecoveryCoordinator.shouldStartAttempt(
                phase = NoRootUpgradeRecoveryCoordinator.RecoveryPhase.RUNNING,
                attemptsStarted = 1,
                nowElapsedMs = 1L,
                retryNotBeforeElapsedMs = 0L
            )
        )
    }
}
