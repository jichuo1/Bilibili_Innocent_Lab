package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import org.junit.Assert.*
import org.junit.Test

class FrameTakeoverPolicyTest {
    @Test fun surfaceHolderAndSurfacePathsRetainOnlyTheCurrentMainPlayerSurface() {
        val ownership = FrameSurfaceOwnership<Any, Any>()
        val player = Any()
        val holderSurface = Any()
        val replacement = Any()
        assertFalse(ownership.bind(Any(), holderSurface, false))
        assertTrue(ownership.bind(player, holderSurface, true))
        assertTrue(ownership.contains(holderSurface))
        assertTrue(ownership.bind(player, null, false))
        assertFalse(ownership.contains(holderSurface))
        assertTrue(ownership.bind(player, replacement, false))
        assertTrue(ownership.contains(replacement))
        ownership.release(player)
        assertFalse(ownership.contains(replacement))
        assertFalse(ownership.bind(player, replacement, false))
    }

    private class EqualObject {
        override fun equals(other: Any?) = other is EqualObject
        override fun hashCode() = 1
    }

    @Test fun hostEqualityNeverGrantsAnUnrelatedPlayerOrSurfaceOwnership() {
        val ownership = FrameSurfaceOwnership<EqualObject, EqualObject>()
        val surface = EqualObject()
        assertTrue(ownership.bind(EqualObject(), surface, true))
        assertFalse(ownership.contains(EqualObject()))
        assertFalse(ownership.bind(EqualObject(), surface, false))
    }

    @Test fun onePlayerReleasingASharedSurfaceDoesNotRevokeAnotherOwner() {
        val ownership = FrameSurfaceOwnership<Any, Any>(2)
        val first = Any(); val second = Any(); val shared = Any()
        assertTrue(ownership.bind(first, shared, true))
        assertTrue(ownership.bind(second, shared, true))
        assertFalse(ownership.bind(Any(), Any(), true))
        ownership.release(first)
        assertTrue(ownership.contains(shared))
        ownership.release(second)
        assertFalse(ownership.contains(shared))
    }

    @Test fun playbackRatesFollowThePlayerAndDoNotLeakAcrossConcurrentServiceSurfaces() {
        val ownership = FrameSurfaceOwnership<Any, Any>(2)
        val first = Any(); val second = Any()
        val firstSurface = Any(); val secondSurface = Any(); val alias = Any()
        ownership.updateSpeed(first, 1.5f, allowNew = true)
        assertEquals(1.0, ownership.rateReader(alias, true)(), 0.0)
        ownership.bind(first, firstSurface, true)
        val originalReader = ownership.rateReader(alias, true)
        assertEquals(1.5, ownership.rateReader(firstSurface, false)(), 0.0)
        assertEquals(1.0, ownership.rateReader(alias, false)(), 0.0)
        assertEquals(1.5, ownership.rateReader(alias, true)(), 0.0)

        ownership.bind(second, secondSurface, true)
        ownership.updateSpeed(second, 2f, allowNew = false)
        ownership.updateSpeed(second, Float.NaN, allowNew = false)
        assertEquals(1.0, ownership.rateReader(alias, true)(), 0.0)
        assertEquals(2.0, ownership.rateReader(secondSurface, true)(), 0.0)
        ownership.bind(second, firstSurface, false)
        assertEquals(1.0, originalReader(), 0.0)
        assertEquals(1.0, ownership.rateReader(firstSurface, true)(), 0.0)
        ownership.bind(second, null, false)
        assertEquals(1.5, ownership.rateReader(alias, true)(), 0.0)
        val lastReader = ownership.rateReader(alias, true)
        ownership.release(first)
        assertEquals(1.0, lastReader(), 0.0)
        assertEquals(1.0, ownership.rateReader(alias, true)(), 0.0)
    }

    @Test fun aSpeedNotificationWithoutASurfaceCannotDisplaceAnActivePlayer() {
        val ownership = FrameSurfaceOwnership<Any, Any>(1)
        val pending = Any(); val active = Any(); val surface = Any()
        ownership.updateSpeed(pending, 2f, true)
        assertTrue(ownership.bind(active, surface, true))
        ownership.updateSpeed(active, 1.5f, false)
        ownership.updateSpeed(pending, 2f, true)
        assertFalse(ownership.bind(pending, Any(), true))
        assertEquals(1.5, ownership.rateReader(surface, false)(), 0.0)
    }

    @Test fun nestedAdapterCallsAndExceptionalExitPreserveAndThenClearTheirScope() {
        val scope = FrameCallScope()
        scope.enter()
        try {
            scope.enter()
            try { assertTrue(scope.active); throw IllegalStateException() }
            catch (_: IllegalStateException) { }
            finally { scope.leave() }
            assertTrue(scope.active)
        } finally { scope.leave() }
        assertFalse(scope.active)
    }

    @Test fun stoppedAndReconfiguredDecodersRejectLateBridgesWithoutCancellingTheirReplacement() {
        val generation = FrameDecoderGeneration<Any>()
        val codec = Any()
        val old = generation.begin(codec)
        generation.end(codec)
        assertFalse(generation.commit(codec, old) { fail("released decoder") })
        val next = generation.begin(codec)
        assertFalse(generation.end(codec, old) { fail("old failure must not remove a new bridge") })
        assertTrue(generation.commit(codec, next) {})
        val newer = generation.begin(codec)
        assertFalse(generation.commit(codec, next) { fail("reconfigured decoder") })
        assertTrue(generation.matches(codec, newer))
    }

    @Test fun onlyTheTwoKnownConfigureAbisAreAcceptedAndEncryptedArgumentsKeepTheirOwnIndex() {
        val prefix = listOf("android.media.MediaFormat", "android.view.Surface")
        assertEquals(2, FrameCodecConfigureShape.cryptoIndex(prefix + listOf("android.media.MediaCrypto", "int")))
        assertEquals(3, FrameCodecConfigureShape.cryptoIndex(prefix + listOf("int", "android.media.MediaDescrambler")))
        assertNull(FrameCodecConfigureShape.cryptoIndex(prefix + listOf("int", "java.lang.Object")))
        assertNull(FrameCodecConfigureShape.cryptoIndex(prefix + listOf("android.media.MediaCrypto", "int", "int")))
    }

    @Test fun nestedPrivateConfigureDelegationPreservesOuterRegistrationWhileTopLevelReconfigureDetaches() {
        val inConfigure = FrameCallScope()
        val generation = FrameDecoderGeneration<Any>()
        val configured = HashMap<Any, FrameDecoderGeneration.Ticket>()
        val codec = Any()
        val publicFourArg = listOf("android.media.MediaFormat", "android.view.Surface", "android.media.MediaCrypto", "int")
        val privateFiveArg = listOf("android.media.MediaFormat", "android.view.Surface", "android.media.MediaCrypto", "android.os.IHwBinder", "int")

        fun simulateConfigure(params: List<String>, body: () -> Unit = {}) {
            if (inConfigure.active) return
            inConfigure.enter()
            try {
                if (configured.containsKey(codec)) generation.end(codec) { configured.remove(codec) }
                val crypto = FrameCodecConfigureShape.cryptoIndex(params)
                if (crypto != null) {
                    val ticket = generation.begin(codec) { configured.remove(codec) }
                    generation.commit(codec, ticket) { configured[codec] = ticket }
                }
                body()
            } finally {
                inConfigure.leave()
            }
        }

        // Public 4-arg configure calls private 5-arg configure internally: registration must survive for start().
        simulateConfigure(publicFourArg) { simulateConfigure(privateFiveArg) }
        val registered = configured[codec]
        assertNotNull(registered)
        assertTrue(registered!!.isActive())
        assertFalse(inConfigure.active)

        // A genuine top-level reconfigure via an unsupported overload detaches the previously owned decoder.
        simulateConfigure(privateFiveArg)
        assertNull(configured[codec])
        assertFalse(registered.isActive())
        assertFalse(inConfigure.active)
    }

    @Test fun optionalHooksCannotMaskAMissingConfigureOrSurfaceRole() {
        val required = setOf("display", "op", "surface", "surface.holder", "surface.release", "speed",
            "configure", "start", "output.surface", "flush", "stop", "reset", "release", "audio.ctor")
        val service = setOf("client.setSurface", "client.release", "client.speed")
        assertTrue(FrameHookCoverage.complete(required, false))
        assertFalse(FrameHookCoverage.complete(required - "configure" + setOf("display.resume", "display.pause"), false))
        assertFalse(FrameHookCoverage.complete(required - "surface" + setOf("display.resume", "display.pause"), false))
        assertTrue(FrameHookCoverage.complete(required + service, true))
        for (role in service) assertFalse(FrameHookCoverage.complete(required + service - role, true))
    }
}
