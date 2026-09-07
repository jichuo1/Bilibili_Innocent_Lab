package com.Bilibili_Innocent_Lab.xposedmodule.telemetry

import org.junit.Assert.*
import org.junit.Test

class TelemetryVersionPolicyTest {
    @Test fun `either numeric version changing is a new pair including downgrade`() {
        val now = 200_000_000L
        val current = TelemetryVersionPolicy.key(15, 91100)!!
        assertEquals("15:91100", current)
        assertNull(TelemetryVersionPolicy.key(0, 91100))
        assertNull(TelemetryVersionPolicy.key(15, Long.MAX_VALUE))
        assertFalse(TelemetryVersionPolicy.allowed(current, current, "", 0, now))
        listOf(null, "14:91100", "16:91100", "15:91000").forEach {
            assertTrue(TelemetryVersionPolicy.allowed(current, it, "", 0, now))
        }
    }

    @Test fun `three attempts persist across restart clock rollback and exact 24h boundary`() {
        val now = 200_000_000L
        val attempts = "$now,${now + 1},${now + 2}"
        assertFalse(TelemetryVersionPolicy.allowed("15:91100", null, attempts, 0, now - 1))
        assertFalse(TelemetryVersionPolicy.allowed("16:91100", null, attempts, 0, now + 86_399_999))
        assertTrue(TelemetryVersionPolicy.allowed("16:91100", null, attempts, 0, now + 86_400_000))
        listOf("bad", "1,2,3,4", "0").forEach {
            assertFalse(TelemetryVersionPolicy.allowed("15:91100", null, it, 0, now))
        }
        assertFalse(TelemetryVersionPolicy.allowed("15:91100", null, "", now + 1, now))
    }

    @Test fun `version quota exhaustion does not alter manual or automatic policy`() {
        val now = 200_000_000L
        assertFalse(TelemetryVersionPolicy.allowed("15:91100", null, "$now,$now,$now", 0, now))
        assertEquals(TelemetryAttemptDecision.ALLOWED,
            TelemetryPolicy.manualDecision(true, false, now, 0, ""))
        assertEquals(TelemetryAttemptDecision.ALLOWED,
            TelemetryPolicy.attemptDecision(true, false, now, 0, 0, false))
    }
}
