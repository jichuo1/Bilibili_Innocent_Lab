package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchMode
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRuntimeProfile
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchScopeProbe
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootDisplayState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.ScopeRequestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoRootActionPolicyTest {
    private val manager = NPatchRuntimeProfile(markerPresent = true, mode = NPatchMode.MANAGER, metadataValid = true)
    private val embedded = NPatchRuntimeProfile(markerPresent = true, mode = NPatchMode.EMBEDDED, metadataValid = true)
    private val unpatched = NPatchRuntimeProfile()

    @Test fun `missing scope on a manager patched host offers to join it`() {
        assertEquals(NoRootActionKind.JOIN_SCOPE, NoRootActionPolicy.kind(NoRootDisplayState.SCOPE_MISSING, manager))
    }

    @Test fun `join scope is never offered where it cannot help`() {
        // Embedded scope is fixed inside the patched APK; an unpatched host has nothing to be scoped for.
        assertEquals(NoRootActionKind.VERIFY, NoRootActionPolicy.kind(NoRootDisplayState.SCOPE_MISSING, embedded))
        assertEquals(NoRootActionKind.OPEN_MANAGER, NoRootActionPolicy.kind(NoRootDisplayState.SCOPE_MISSING, unpatched))
        assertEquals(
            NoRootActionKind.OPEN_MANAGER,
            NoRootActionPolicy.kind(NoRootDisplayState.SCOPE_MISSING, manager.copy(mode = NPatchMode.UNKNOWN))
        )
    }

    @Test fun `the pre-existing actions are unchanged`() {
        assertEquals(NoRootActionKind.VERIFY, NoRootActionPolicy.kind(NoRootDisplayState.UNCONFIRMED, manager))
        assertEquals(NoRootActionKind.OPEN_MANAGER, NoRootActionPolicy.kind(NoRootDisplayState.NOT_RUNNING, manager))
        assertEquals(NoRootActionKind.VERIFY, NoRootActionPolicy.kind(NoRootDisplayState.NOT_RUNNING, embedded))
        for (state in NoRootDisplayState.entries) {
            if (state in setOf(NoRootDisplayState.UNCONFIRMED, NoRootDisplayState.NOT_RUNNING, NoRootDisplayState.SCOPE_MISSING)) continue
            assertEquals(state.name, NoRootActionKind.NONE, NoRootActionPolicy.kind(state, manager))
        }
    }

    @Test fun `every outcome has a distinct user-facing message where the user must act differently`() {
        val messages = listOf(
            ScopeRequestResult.Added, ScopeRequestResult.AlreadyPresent, ScopeRequestResult.ManagerMissing,
            ScopeRequestResult.ModuleNotRegistered, ScopeRequestResult.ConnectionTimeout,
            ScopeRequestResult.Failure("scope_not_applied")
        ).map(NoRootActionPolicy::message)
        assertEquals(messages.size, messages.toSet().size)
        assertTrue(messages.all { it != 0 })
        assertEquals(
            NoRootActionPolicy.message(ScopeRequestResult.Failure("a")),
            NoRootActionPolicy.message(ScopeRequestResult.Failure("b"))
        )
    }

    @Test fun `a forced scope probe skips only the minimum interval`() {
        val interval = NPatchScopeProbe.MIN_INTERVAL_MS
        assertTrue(NPatchScopeProbe.shouldProbe(inFlight = false, lastProbeAtMs = 0L, nowMs = 100L, force = false))
        assertFalse(NPatchScopeProbe.shouldProbe(inFlight = false, lastProbeAtMs = 1_000L, nowMs = 1_000L + interval - 1, force = false))
        assertTrue(NPatchScopeProbe.shouldProbe(inFlight = false, lastProbeAtMs = 1_000L, nowMs = 1_000L + interval, force = false))
        assertTrue(NPatchScopeProbe.shouldProbe(inFlight = false, lastProbeAtMs = 1_000L, nowMs = 1_001L, force = true))
        assertFalse("never stack a probe on one in flight", NPatchScopeProbe.shouldProbe(inFlight = true, lastProbeAtMs = 0L, nowMs = 1L, force = true))
    }
}
