package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.junit.Assert.*
import org.junit.Test

class GuestFollowGroupServiceProxyTest {
    class Call(val mid: Long)
    interface Service {
        fun group(mid: Long): Call
        fun unsupported(): String
        fun fail(): Call
    }
    class Delegate : Service {
        var calls = 0
        override fun group(mid: Long): Call { calls++; return Call(mid) }
        override fun unsupported(): String { calls++; return "unsafe" }
        override fun fail(): Call { throw IllegalArgumentException("host failure") }
    }

    @Test fun returnedCallsAreTaggedBeforeTheCallerCanUseThem() {
        val token = Any()
        val bindings = GuestFollowGroupBindings<Any>(newMarker = { "test" })
        val marker = bindings.register(token, 42)
        val delegate = Delegate()
        val service = GuestFollowGroupServiceProxy(bindings).wrap(Service::class.java, delegate, Call::class.java, marker) as Service
        val call = service.group(42)
        assertEquals(1, delegate.calls)
        assertEquals(42L, call.mid)
        assertSame(token, bindings.tagged(call)!!.binding!!.token)
        assertEquals(42L, bindings.tagged(call)!!.binding!!.mid)
        assertNull(bindings.tagged(Call(42)))
        assertEquals(service, service)
        assertNotEquals(service, delegate)
    }

    @Test fun unexpectedShapesFailBeforeCallingTheHostAndOriginalErrorsArePreserved() {
        val bindings = GuestFollowGroupBindings<Any>(newMarker = { "test" })
        val marker = bindings.register(Any(), 42)
        val delegate = Delegate()
        val service = GuestFollowGroupServiceProxy(bindings).wrap(Service::class.java, delegate, Call::class.java, marker) as Service
        assertThrows(IllegalStateException::class.java) { service.unsupported() }
        assertEquals(0, delegate.calls)
        assertEquals("host failure", assertThrows(IllegalArgumentException::class.java) { service.fail() }.message)
        assertThrows(IllegalArgumentException::class.java) {
            GuestFollowGroupServiceProxy(bindings).wrap(String::class.java, delegate, Call::class.java, marker)
        }
    }

    @Test fun anEvictedPageStillProducesBlockedCalls() {
        val bindings = GuestFollowGroupBindings<Any>(capacity = 1, newMarker = { "test" })
        val marker = bindings.register(Any(), 42)
        val service = GuestFollowGroupServiceProxy(bindings).wrap(Service::class.java, Delegate(), Call::class.java, marker) as Service
        bindings.register(Any(), 43)
        val call = service.group(42)
        assertNotNull(bindings.tagged(call))
        assertNull(bindings.tagged(call)!!.binding)
    }
}
