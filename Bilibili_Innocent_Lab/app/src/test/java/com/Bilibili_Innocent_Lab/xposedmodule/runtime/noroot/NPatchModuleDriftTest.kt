package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.junit.Assert.*
import org.junit.Test

class NPatchModuleDriftTest {
    private val row = NPatchProcessSnapshot("tv.danmaku.bili", 1, NPatchMode.MANAGER, NPatchProcessState.INSTALLED,
        "module_direct", 49, 123, 20)

    @Test fun oldAndMixedVersionsAreObservationsButNeverCurrentProcessEvidence() {
        val old = NPatchModuleObservation.fromRows(listOf(row), 21)
        assertEquals(listOf(20L), old.versions)
        assertTrue(old.processes.isEmpty())
        val mixed = NPatchModuleObservation.fromRows(listOf(row, row.copy(processName = "tv.danmaku.bili:web", moduleVersionCode = 21)), 21)
        assertEquals(listOf(20L, 21L), mixed.versions)
        assertTrue(mixed.processes.isEmpty())
        val current = row.copy(moduleVersionCode = 21)
        assertEquals(listOf(current), NPatchModuleObservation.fromRows(listOf(current), 21).processes)
    }

    @Test fun historicalVersionIsDistinguishedFromCurrentObservationAndUnknown() {
        assertEquals(NPatchModuleDrift.UNKNOWN, NPatchModuleDrift.assess(21, emptyList(), 21, NPatchEmbeddedBuildState.UNKNOWN))
        assertEquals(NPatchModuleDrift.PREVIOUS_DIFFERENT, NPatchModuleDrift.assess(21, emptyList(), 20, NPatchEmbeddedBuildState.UNKNOWN))
        assertEquals(NPatchModuleDrift.CURRENT_VERSION, NPatchModuleDrift.assess(21, listOf(21), 20, NPatchEmbeddedBuildState.NOT_APPLICABLE))
        assertEquals(NPatchModuleDrift.RUNTIME_DIFFERENT, NPatchModuleDrift.assess(21, listOf(20), 21, NPatchEmbeddedBuildState.UNKNOWN))
    }

    @Test fun equalVersionCodesCannotOverrideDifferentEmbeddedBytes() {
        assertEquals(NPatchModuleDrift.EMBEDDED_DIFFERENT, NPatchModuleDrift.assess(21, listOf(21), 21, NPatchEmbeddedBuildState.DIFFERENT))
        assertEquals(NPatchModuleDrift.EMBEDDED_DIFFERENT, NPatchModuleDrift.assess(21, emptyList(), 0, NPatchEmbeddedBuildState.MODULE_MISSING))
    }
}
