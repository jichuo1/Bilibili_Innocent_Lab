package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KmpMossSurfaceTest {
    private val loader = javaClass.classLoader!!

    private fun names(pkg: String, exception: String = "KMossException") = KmpMossSurface.Names(
        engine = "kmpfixture.$pkg.GrpcEngine",
        interceptor = "kmpfixture.$pkg.MossInterceptor",
        exception = "kmpfixture.$pkg.$exception")

    private fun resolved(pkg: String) =
        (KmpMossSurface.resolve(loader, names(pkg)) as KmpMossSurface.Result.Resolved).surface

    @Test fun modernShapeUsesThreeArgResponseAndFieldAccessors() {
        val surface = resolved("modern")
        assertFalse(surface.responseHasRequest)
        assertEquals(3, surface.responseCtor.parameterCount)
        assertEquals("ALL", (surface.protocolAll as Enum<*>).name)
        assertEquals(2, surface.headerMapGetters.size)
        assertEquals("interceptors", surface.engineList.name)
        assertEquals("a", surface.engineEntry.name)
    }

    @Test fun legacyShapeUsesFourArgResponseAndAccessorMethods() {
        val surface = resolved("legacy")
        assertTrue(surface.responseHasRequest)
        assertEquals(4, surface.responseCtor.parameterCount)
        // 8.84-8.96 expose the payload and descriptor through accessors c() and e(), not public fields.
        val request = kmpfixture.legacy.RequestImpl(kmpfixture.legacy.Service("h"),
            kmpfixture.legacy.Descriptor("p", "S", "M", String::class, object : kotlinx.serialization.SerializationStrategy<String> {}),
            byteArrayOf(1, 2))
        assertEquals(listOf<Byte>(1, 2), (surface.requestPayload.get(request) as ByteArray).toList())
        assertTrue(surface.requestDescriptor.get(request).toString().startsWith("KMethodDescriptor(packageName=p"))
        assertEquals("d", surface.requestHeaders.name)
    }

    @Test fun entryPositionsOfDescriptorAndReplyHandlerAreFoundByType() {
        val modern = resolved("modern")
        assertEquals(1, modern.entryDescriptorIndex)
        assertEquals(3, modern.entryHandlerIndex)
        // The legacy fixture types its handler as Any, so observation is reported unavailable (not an error).
        val legacy = resolved("legacy")
        assertEquals(1, legacy.entryDescriptorIndex)
        assertEquals(-1, legacy.entryHandlerIndex)
    }

    @Test fun hostWithoutAnEngineIsAbsentNotUnresolved() {
        assertEquals(KmpMossSurface.Result.Absent, KmpMossSurface.resolve(loader, names("none")))
    }

    @Test fun missingExceptionTypeFailsClosed() {
        val result = KmpMossSurface.resolve(loader, names("modern", exception = "Nope"))
        assertTrue(result is KmpMossSurface.Result.Unresolved)
        assertEquals("exception", (result as KmpMossSurface.Result.Unresolved).reason)
    }
}
