package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.junit.Assert.assertEquals
import org.junit.Test

class NPatchScopeProbeTest {
    @Test
    fun `embedded scope ignores a stale manager denial without claiming activation`() {
        for (scope in NPatchScopeProbe.ScopeState.entries) {
            assertEquals(NPatchScopeProbe.ScopeState.UNKNOWN, NPatchScopeProbe.effectiveScope(NPatchMode.EMBEDDED, scope))
            assertEquals(scope, NPatchScopeProbe.effectiveScope(NPatchMode.MANAGER, scope))
            assertEquals(scope, NPatchScopeProbe.effectiveScope(NPatchMode.UNKNOWN, scope))
        }
    }

    @Test
    fun `scope classification only trusts explicit module rows`() {
        val module = "com.Bilibili_Innocent_Lab.xposedmodule"

        assertEquals(
            NPatchScopeProbe.ScopeState.IN_SCOPE,
            NPatchScopeProbe.classify(listOf("com.example.other", module), module)
        )
        assertEquals(
            NPatchScopeProbe.ScopeState.OUT_OF_SCOPE,
            NPatchScopeProbe.classify(emptyList(), module)
        )
        assertEquals(
            NPatchScopeProbe.ScopeState.OUT_OF_SCOPE,
            NPatchScopeProbe.classify(listOf("com.example.other"), module)
        )
    }
}
