package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.junit.Assert.*
import org.junit.Test

class GuestFollowGroupCallGuardTest {
    @Test fun taggedCallsKeepTheirOriginalSessionAndNeverFallThroughAfterItChanges() {
        val bindings = GuestFollowGroupBindings<Any>(newMarker = { "test" })
        val original = Any()
        var current = original
        var guest = true
        val guard = GuestFollowGroupCallGuard(bindings) { guest && it === current }
        val marker = bindings.register(original, 42)
        val call = Any()
        bindings.tag(call, marker)
        val allowed = guard.decide(call) as GuestFollowGroupCallGuard.Decision.Local
        assertSame(original, allowed.binding.token)
        assertEquals(42L, allowed.binding.mid)
        current = Any()
        assertEquals(GuestFollowGroupCallGuard.Decision.Blocked, guard.decide(call))
        guest = false
        assertEquals(GuestFollowGroupCallGuard.Decision.Blocked, guard.decide(call))
        assertEquals(GuestFollowGroupCallGuard.Decision.Unrelated, guard.decide(Any()))
    }

    @Test fun missingBindingsAndValidationErrorsBlockInsteadOfRunningTheHostCall() {
        val bindings = GuestFollowGroupBindings<Any>(newMarker = { "test" })
        val missing = Any()
        bindings.tag(missing, "restored-from-another-process")
        val guard = GuestFollowGroupCallGuard(bindings) { error("validation unavailable") }
        assertEquals(GuestFollowGroupCallGuard.Decision.Blocked, guard.decide(missing))
        val marker = bindings.register(Any(), 42)
        val call = Any()
        bindings.tag(call, marker)
        assertEquals(GuestFollowGroupCallGuard.Decision.Blocked, guard.decide(call))
    }
}
