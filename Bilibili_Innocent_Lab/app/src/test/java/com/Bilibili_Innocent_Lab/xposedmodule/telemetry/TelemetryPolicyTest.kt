package com.Bilibili_Innocent_Lab.xposedmodule.telemetry

import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryPolicyTest {
    @Test
    fun `manual quota is rolling persistent and fails closed on corruption or clock rollback`() {
        val now = 200_000_000L
        val window = TelemetryPolicy.SUCCESS_INTERVAL_MS
        assertEquals(emptyList<Long>(), TelemetryPolicy.activeManualAttempts("", now))
        assertEquals(listOf(now - 1), TelemetryPolicy.activeManualAttempts("${now - window},${now - 1}", now))
        assertEquals(null, TelemetryPolicy.activeManualAttempts("bad", now))
        assertEquals(null, TelemetryPolicy.activeManualAttempts("1,2,3,4", now))
        assertEquals(TelemetryAttemptDecision.MANUAL_LIMIT_REACHED,
            TelemetryPolicy.manualDecision(true, false, now, 0, "$now,$now,${now + 1}"))
        assertEquals(TelemetryAttemptDecision.MANUAL_LIMIT_REACHED,
            TelemetryPolicy.manualDecision(true, false, now, 0, "bad"))
        assertEquals(TelemetryAttemptDecision.ALLOWED,
            TelemetryPolicy.manualDecision(true, false, now, 0, "${now - window},$now,$now"))
    }

    @Test
    fun `manual and automatic gates are independent but consent retirement and server backoff remain mandatory`() {
        val now = 200_000_000L
        assertEquals(TelemetryAttemptDecision.NOT_DUE,
            TelemetryPolicy.attemptDecision(true, false, now, now + 1000, now, force = false))
        assertEquals(TelemetryAttemptDecision.ALLOWED,
            TelemetryPolicy.manualDecision(true, false, now, 0, ""))
        assertEquals(TelemetryAttemptDecision.DISABLED,
            TelemetryPolicy.manualDecision(false, false, now, 0, ""))
        assertEquals(TelemetryAttemptDecision.RETIRED,
            TelemetryPolicy.manualDecision(true, true, now, 0, ""))
        assertEquals(TelemetryAttemptDecision.NOT_DUE,
            TelemetryPolicy.manualDecision(true, false, now, now + 1, ""))
        assertEquals(TelemetryAttemptDecision.ALLOWED,
            TelemetryPolicy.attemptDecision(true, false, now, now, now - TelemetryPolicy.SUCCESS_INTERVAL_MS, force = false))
    }

    @Test
    fun `telemetry consent identity and runtime state stay outside settings backup`() {
        assertTrue(
            SettingsCatalog.specs.none { spec ->
                "telemetry" in spec.id || "telemetry" in spec.storageKey
            }
        )
        assertTrue(RemoteHookConfigContract.persistedKeys.none { "telemetry" in it })
    }

    @Test
    fun `terms choice is visually on by default but never authorizes without a current record`() {
        assertTrue(TelemetryPolicy.termsChoice(hasCurrentChoice = false, currentChoice = false))
        assertFalse(
            TelemetryPolicy.consentAuthorizesUpload(
                termsAuthorized = true,
                hasEnabledChoice = false,
                enabled = true,
                storedTermsVersion = 2,
                currentTermsVersion = 2
            )
        )
    }

    @Test
    fun `upload requires terms authorization current consent version and enabled choice`() {
        assertTrue(
            TelemetryPolicy.consentAuthorizesUpload(
                termsAuthorized = true,
                hasEnabledChoice = true,
                enabled = true,
                storedTermsVersion = 2,
                currentTermsVersion = 2
            )
        )
        listOf(
            arrayOf(false, true, true, true),
            arrayOf(true, false, true, true),
            arrayOf(true, true, false, true),
            arrayOf(true, true, true, false)
        ).forEach { conditions ->
            assertFalse(
                TelemetryPolicy.consentAuthorizesUpload(
                    termsAuthorized = conditions[0],
                    hasEnabledChoice = conditions[1],
                    enabled = conditions[2],
                    storedTermsVersion = if (conditions[3]) 2 else 1,
                    currentTermsVersion = 2
                )
            )
        }
    }

    @Test
    fun `force bypasses daily interval but not disable retirement or retry window`() {
        val now = 1_000_000L
        assertEquals(
            TelemetryAttemptDecision.ALLOWED,
            TelemetryPolicy.attemptDecision(true, false, now, 0L, now - 1L, force = true)
        )
        assertEquals(
            TelemetryAttemptDecision.DISABLED,
            TelemetryPolicy.attemptDecision(false, false, now, 0L, 0L, force = true)
        )
        assertEquals(
            TelemetryAttemptDecision.RETIRED,
            TelemetryPolicy.attemptDecision(true, true, now, 0L, 0L, force = true)
        )
        assertEquals(
            TelemetryAttemptDecision.NOT_DUE,
            TelemetryPolicy.attemptDecision(true, false, now, now + 1L, 0L, force = true)
        )
    }

    @Test
    fun `identity rotation is bounded and clock rollback fails closed`() {
        val now = 2_000_000_000L
        assertFalse(TelemetryPolicy.shouldRotateIdentity(now - 1_000L, now))
        assertTrue(TelemetryPolicy.shouldRotateIdentity(0L, now))
        assertTrue(TelemetryPolicy.shouldRotateIdentity(now + 1L, now))
        assertTrue(
            TelemetryPolicy.shouldRotateIdentity(
                now - TelemetryPolicy.IDENTITY_ROTATION_MS,
                now
            )
        )
    }

    @Test
    fun `HTTP status classification keeps retirement and retry semantics distinct`() {
        assertEquals(TelemetryHttpOutcome.SUCCESS, TelemetryPolicy.classifyHttpStatus(204))
        assertEquals(TelemetryHttpOutcome.RETIRED, TelemetryPolicy.classifyHttpStatus(410))
        assertEquals(TelemetryHttpOutcome.RATE_LIMITED, TelemetryPolicy.classifyHttpStatus(429))
        assertEquals(TelemetryHttpOutcome.REJECTED, TelemetryPolicy.classifyHttpStatus(400))
        assertEquals(
            TelemetryHttpOutcome.RETRYABLE_FAILURE,
            TelemetryPolicy.classifyHttpStatus(503)
        )
    }
}
