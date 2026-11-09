package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.junit.Assert.*
import org.junit.Test

class GuestFollowGroupBindingsTest {
    private class EqualCall {
        override fun equals(other: Any?) = other is EqualCall
        override fun hashCode() = 1
    }

    @Test fun callsRemainBoundToTheirOriginalSessionAndAuthor() {
        var next = 0
        val bindings = GuestFollowGroupBindings<Any>(newMarker = { "marker-${next++}" })
        val first = Any()
        val second = Any()
        val firstMarker = bindings.register(first, 11)
        val call = Any()
        bindings.tag(call, firstMarker)
        val secondMarker = bindings.register(second, 12)
        val secondCall = Any()
        bindings.tag(secondCall, secondMarker)
        assertSame(first, bindings.tagged(call)!!.binding!!.token)
        assertEquals(11L, bindings.tagged(call)!!.binding!!.mid)
        assertSame(second, bindings.tagged(secondCall)!!.binding!!.token)
        assertEquals(12L, bindings.tagged(secondCall)!!.binding!!.mid)
        assertNull(bindings.tagged(Any()))
    }

    @Test fun missingRestoredAndEvictedMarkersStayTaggedAndBlocked() {
        var next = 0
        val bindings = GuestFollowGroupBindings<Any>(capacity = 1, newMarker = { "marker-${next++}" })
        val first = bindings.register(Any(), 11)
        val pending = Any()
        bindings.tag(pending, first)
        bindings.register(Any(), 12)
        assertNull(bindings.lookup(first))
        assertNotNull(bindings.tagged(pending))
        assertNull(bindings.tagged(pending)!!.binding)
        val restored = Any()
        bindings.tag(restored, "marker-from-a-previous-process")
        assertNotNull(bindings.tagged(restored))
        assertNull(bindings.tagged(restored)!!.binding)
    }

    @Test fun hostEqualityCannotMixCallsAndRebindingFailsClosed() {
        var next = 0
        val bindings = GuestFollowGroupBindings<Any>(newMarker = { "marker-${next++}" })
        val first = bindings.register(Any(), 11)
        val second = bindings.register(Any(), 12)
        val firstCall = EqualCall()
        val secondCall = EqualCall()
        bindings.tag(firstCall, first)
        bindings.tag(secondCall, second)
        assertEquals(11L, bindings.tagged(firstCall)!!.binding!!.mid)
        assertEquals(12L, bindings.tagged(secondCall)!!.binding!!.mid)
        bindings.tag(firstCall, second)
        assertNull(bindings.tagged(firstCall)!!.binding)
        bindings.tag(firstCall, first)
        assertNull(bindings.tagged(firstCall)!!.binding)
        assertEquals(12L, bindings.tagged(secondCall)!!.binding!!.mid)
    }

    @Test fun clonedCallsInheritTheirBindingAndRemainBlockedAfterEviction() {
        val bindings = GuestFollowGroupBindings<Any>(capacity = 1, newMarker = { "test" })
        val token = Any()
        val marker = bindings.register(token, 11)
        val original = Any()
        val clone = Any()
        bindings.tag(original, marker)
        bindings.forward(original, clone)
        assertSame(token, bindings.tagged(clone)!!.binding!!.token)
        bindings.register(Any(), 12)
        val laterClone = Any()
        bindings.forward(clone, laterClone)
        assertNotNull(bindings.tagged(laterClone))
        assertNull(bindings.tagged(laterClone)!!.binding)
        val unrelated = Any()
        bindings.forward(Any(), unrelated)
        assertNull(bindings.tagged(unrelated))
    }
    @Test fun invalidOrReusedMarkersCannotReplaceALiveBinding() {
        val token = Any()
        val bindings = GuestFollowGroupBindings<Any>(capacity = 1, newMarker = { "same" })
        assertThrows(IllegalArgumentException::class.java) { bindings.register(token, 0) }
        val marker = bindings.register(token, 11)
        assertSame(token, bindings.lookup(marker)!!.token)
        val pending = Any()
        bindings.tag(pending, marker)
        val later = bindings.register(Any(), 12)
        assertNotEquals(marker, later)
        assertNull(bindings.tagged(pending)!!.binding)
        assertThrows(IllegalArgumentException::class.java) { GuestFollowGroupBindings<Any>(capacity = 0) }
        assertThrows(IllegalArgumentException::class.java) { GuestFollowGroupBindings<Any>(newMarker = { "" }) }
    }
}
