package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuestPlaybackSealLocatorTest {
    private val loader = javaClass.classLoader!!

    private fun locate(pkg: String) = GuestPlaybackSealLocator.locate(
        loader, "playbackfixture.$pkg.", "playbackfixture.Playable", "playbackfixture.Accounts")

    fun legacyShapePicksNamedStateSealNotTheOtherLongMethod() {
        val seal = requireNotNull(locate("legacy"))
        assertEquals("t", seal.method.name)
        assertEquals("playbackfixture.legacy.TheseusHistoryService\$b", seal.legacyState?.name)
        assertEquals("playbackfixture.Playable", seal.playable.type.name)
    }

    fun modernShapeDerivesRenamedOwnerFromLambdaCapture() {
        val seal = requireNotNull(locate("modern"))
        assertEquals("playbackfixture.modern.b", seal.method.declaringClass.name)
        assertEquals("a", seal.method.name)
        assertEquals(listOf(Long::class.javaPrimitiveType), seal.method.parameterTypes.toList())
        assertNull(seal.legacyState)
    }

    fun ambiguousSealFailsClosed() {
        assertNull(locate("ambiguous"))
    }

    fun missingPackageFailsClosed() {
        assertNull(locate("absent"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (GuestPlaybackSealLocatorTest) -> Unit) {
            val s = GuestPlaybackSealLocatorTest()
            block(s)
        }
        runIsolated { s -> s.legacyShapePicksNamedStateSealNotTheOtherLongMethod() }
        runIsolated { s -> s.modernShapeDerivesRenamedOwnerFromLambdaCapture() }
        runIsolated { s -> s.ambiguousSealFailsClosed() }
        runIsolated { s -> s.missingPackageFailsClosed() }
    }
}
