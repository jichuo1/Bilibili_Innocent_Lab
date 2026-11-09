package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.junit.Assert.*
import org.junit.Test

class NPatchHealthTest {
    @Test fun managerPresenceScopeAndReachabilityRemainSeparate() {
        assertEquals(NPatchManagerReachability.NOT_APPLICABLE,
            NPatchScopeProbe.classifyReachability(NPatchMode.EMBEDDED, NPatchScopeProbe.ScopeState.IN_SCOPE))
        assertEquals(NPatchManagerReachability.REACHABLE,
            NPatchScopeProbe.classifyReachability(NPatchMode.MANAGER, NPatchScopeProbe.ScopeState.OUT_OF_SCOPE))
        assertEquals(NPatchManagerReachability.UNKNOWN,
            NPatchScopeProbe.classifyReachability(NPatchMode.MANAGER, NPatchScopeProbe.ScopeState.UNKNOWN))
        assertEquals(NPatchManagerReachability.UNKNOWN,
            NPatchScopeProbe.classifyReachability(NPatchMode.UNKNOWN, NPatchScopeProbe.ScopeState.IN_SCOPE))
    }
    private fun sample(index: Int, installed: Long? = null) = NPatchStartupEvidence(
        "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}", index.toLong(), 1, 10,
        installed, 10 + (installed ?: 0), "granted", "provider", listOf(NPatchRouteEvidence("provider", "granted")))

    @Test fun missingAndCancelledTransportResultsNeverBecomeFailuresOrZeroPercent() {
        val stats = NPatchHealth.summarize(listOf(sample(1, 20)))
        assertEquals(100, stats.routes.single { it.route == "provider" }.grantedPercent)
        assertNull(stats.routes.single { it.route == "binder" }.grantedPercent)
        assertEquals(0, stats.routes.single { it.route == "broadcast" }.completed)
        assertEquals(1, stats.grantedBoots)
    }

    @Test fun measurementsExcludeUnfinishedInstallationAndRetryCountsStaySeparate() {
        val samples = listOf(sample(1, 20), sample(2, 40), sample(3).copy(attempt = 2, result = "timeout",
            selectedRoute = "unknown", routes = listOf(NPatchRouteEvidence("provider", "timeout"))))
        val stats = NPatchHealth.summarize(samples)
        assertEquals(3, stats.observedBoots)
        assertEquals(2, stats.grantedBoots)
        assertEquals(1, stats.retryCount)
        assertEquals(2, stats.measuredBoots)
        assertEquals(30L, stats.p50Ms)
        assertEquals(50L, stats.maxMs)
        assertEquals(66, stats.routes.single { it.route == "provider" }.grantedPercent)
    }

    @Test fun noSamplesRemainUnknownAndRefreshesDoNotChangeDenominators() {
        val empty = NPatchHealth.summarize(emptyList())
        assertNull(empty.p50Ms)
        assertNull(empty.maxMs)
        assertTrue(empty.routes.all { it.grantedPercent == null })
        val sample = sample(1, 0)
        val once = NPatchHealth.summarize(listOf(sample))
        assertEquals(once, NPatchHealth.summarize(NPatchStartupCodec.merge(listOf(sample), listOf(sample))))
    }
}
