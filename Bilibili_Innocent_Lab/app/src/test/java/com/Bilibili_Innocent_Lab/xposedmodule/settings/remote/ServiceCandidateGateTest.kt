package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote
import org.junit.Assert.*
import org.junit.Test
class ServiceCandidateGateTest {
    @Test fun deathOfOldActiveServiceDoesNotCancelValidationOfItsReplacement() {
        val gate=ServiceCandidateGate<Any>(); val a=Any(); val b=Any()
        gate.arrive(a); val pausedB=gate.arrive(b)
        gate.remove(a)
        assertTrue(gate.matches(b,pausedB))
    }
    @Test fun deadPendingCandidateCannotBePromotedAndLateOldDeathIsHarmless() {
        val gate=ServiceCandidateGate<Any>(); val a=Any(); val b=Any()
        val ta=gate.arrive(a); val tb=gate.arrive(b)
        assertFalse(gate.matches(a,ta)); gate.remove(b)
        assertFalse(gate.matches(b,tb))
        val fresh=gate.arrive(b); gate.remove(a)
        assertTrue(gate.matches(b,fresh))
    }
}
