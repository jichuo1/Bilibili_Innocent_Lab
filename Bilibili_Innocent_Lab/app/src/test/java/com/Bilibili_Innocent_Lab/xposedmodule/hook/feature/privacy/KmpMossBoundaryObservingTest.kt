package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import kmpfixture.modern.Handler
import kmpfixture.modern.KMossException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KmpMossBoundaryObservingTest {
    private class Recorder : Handler {
        val bytes = mutableListOf<ByteArray?>()
        var completed = 0
        val errors = mutableListOf<KMossException>()
        override fun onNext(bytes: ByteArray?) { this.bytes += bytes }
        override fun onCompleted() { completed++ }
        override fun onError(error: KMossException) { errors += error }
    }

    private fun wrap(original: Handler, seen: MutableList<ByteArray>) =
        KmpMossBoundary.observing(Handler::class.java, original) { seen += it } as Handler

    @Test fun observerSeesTheBytesAndTheHostHandlerStillGetsThemUntouched() {
        val original = Recorder()
        val seen = mutableListOf<ByteArray>()
        val payload = byteArrayOf(1, 2, 3)
        val wrapped = wrap(original, seen)
        wrapped.onNext(payload)
        wrapped.onCompleted()
        assertEquals(1, seen.size)
        assertArrayEquals(payload, seen[0])
        assertSame(payload, original.bytes.single())
        assertEquals(1, original.completed)
    }

    @Test fun anObserverFailureNeverReachesTheHost() {
        val original = Recorder()
        val wrapped = KmpMossBoundary.observing(Handler::class.java, original) { error("observer bug") } as Handler
        wrapped.onNext(byteArrayOf(9))
        assertEquals(1, original.bytes.size)
    }

    @Test fun nullRepliesAndErrorsAreForwardedAsIs() {
        val original = Recorder()
        val seen = mutableListOf<ByteArray>()
        val wrapped = wrap(original, seen)
        wrapped.onNext(null)
        val failure = KMossException("boom", RuntimeException())
        wrapped.onError(failure)
        assertTrue(seen.isEmpty())
        assertEquals(listOf<ByteArray?>(null), original.bytes)
        assertSame(failure, original.errors.single())
    }

    @Test fun exceptionsFromTheHostHandlerAreRethrownUnwrapped() {
        val throwing = object : Handler {
            override fun onNext(bytes: ByteArray?) { throw IllegalStateException("host") }
            override fun onCompleted() {}
            override fun onError(error: KMossException) {}
        }
        val wrapped = wrap(throwing, mutableListOf())
        assertThrows(IllegalStateException::class.java) { wrapped.onNext(byteArrayOf(1)) }
    }
}
