package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyThreadKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReplyTopologyPageCallTest {

    @Test
    fun cancelClearsResponseAndSuppressesLateCompletion() {
        var callbackCount = 0
        val call = ReplyTopologyPageCall { callbackCount++ }
        call.retainClient(Any())
        call.retainResponse(Any())

        call.cancel()
        call.retainResponse(Any())
        call.complete(Result.success(emptyPage()))

        assertNull(call.responseOrNull())
        assertEquals(0, callbackCount)
    }

    @Test
    fun completionInvokesCallbackAtMostOnce() {
        var callbackCount = 0
        var deliveredPage: ReplyTopologyHostPage? = null
        val expected = emptyPage()
        val call = ReplyTopologyPageCall { result ->
            callbackCount++
            deliveredPage = result.getOrNull()
        }

        call.complete(Result.success(expected))
        call.complete(Result.failure(IllegalStateException("late error")))
        call.cancel()

        assertEquals(1, callbackCount)
        assertEquals(expected, deliveredPage)
        assertNull(call.responseOrNull())
    }

    @Test
    fun responseThreadIdentityMustMatchTheRequestedThreadExactly() {
        val expected = ReplyTopologyThreadKey(oid = 100L, type = 1L, rootRpid = 200L)

        requireMatchingReplyTopologyThread(expected, expected.copy())
        assertThrows(IllegalStateException::class.java) {
            requireMatchingReplyTopologyThread(expected, expected.copy(oid = 101L))
        }
        assertThrows(IllegalStateException::class.java) {
            requireMatchingReplyTopologyThread(expected, expected.copy(type = 2L))
        }
        assertThrows(IllegalStateException::class.java) {
            requireMatchingReplyTopologyThread(expected, expected.copy(rootRpid = 201L))
        }
    }

    @Test
    fun proxyFallbackReturnsBoxableZeroForEveryPrimitiveType() {
        assertFalse(defaultReplyTopologyProxyValue(java.lang.Boolean.TYPE) as Boolean)
        assertEquals(0.toByte(), defaultReplyTopologyProxyValue(java.lang.Byte.TYPE))
        assertEquals('\u0000', defaultReplyTopologyProxyValue(java.lang.Character.TYPE))
        assertEquals(0.toShort(), defaultReplyTopologyProxyValue(java.lang.Short.TYPE))
        assertEquals(0, defaultReplyTopologyProxyValue(java.lang.Integer.TYPE))
        assertEquals(0L, defaultReplyTopologyProxyValue(java.lang.Long.TYPE))
        assertEquals(0f, defaultReplyTopologyProxyValue(java.lang.Float.TYPE))
        assertEquals(0.0, defaultReplyTopologyProxyValue(java.lang.Double.TYPE))
        assertNull(defaultReplyTopologyProxyValue(java.lang.Void.TYPE))
        assertNull(defaultReplyTopologyProxyValue(String::class.java))
    }

    private fun emptyPage() = ReplyTopologyHostPage(
        nodes = emptyList(),
        nextOffset = null,
        expectedReplyCount = 0
    )
}
