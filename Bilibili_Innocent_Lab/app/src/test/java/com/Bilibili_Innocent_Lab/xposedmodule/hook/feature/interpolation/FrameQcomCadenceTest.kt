package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FrameQcomCadenceTest {
    @Test fun repeatedForwardGapsRejectEvenWhenEveryPairNeedsANewAnchor() {
        val cadence = FrameQcomCadence(33_333_333L)
        repeat(7) { assertFalse(cadence.observe(166_666_667L)); assertFalse(cadence.unsuitable) }
        assertFalse(cadence.observe(166_666_667L))
        assertTrue(cadence.unsuitable)
    }

    @Test fun validPairsAndExplicitFlushStartANewCadenceObservation() {
        val cadence = FrameQcomCadence(33_333_333L)
        repeat(7) { cadence.observe(500_000_000L) }
        assertTrue(cadence.observe(33_366_667L))
        assertFalse(cadence.observe(-1L))
        assertFalse(cadence.unsuitable)
        repeat(6) { cadence.observe(200_000_000L) }
        cadence.reset()
        assertFalse(cadence.observe(200_000_000L))
        assertFalse(cadence.unsuitable)
    }

    @Test fun repeatedHostOutputCallsKeepTheProducerUntilTheLastPlatformReturn() {
        val lease = FrameQcomOutputBorrow<Any>()
        val display = Any()
        val input = Any()
        lease.connect(display, input)
        assertNull(lease.borrow(Any()))
        assertSame(input, lease.borrow(display))
        assertSame(input, lease.borrow(display))
        assertFalse(lease.revoke())
        assertNull(lease.borrow(display))
        assertFalse(lease.finish())
        assertTrue(lease.finish())
        assertTrue(lease.revoke())
    }
}
