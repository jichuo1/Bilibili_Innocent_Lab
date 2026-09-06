package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MossResponseHandlerProxyTest {

    /** 结构与宿主 `MossResponseHandler` 一致的最小替身：一个 onNext + 一个带返回值的方法。 */
    interface FakeHandler {
        fun onNext(reply: Any?)
        fun onError(reason: String)
        fun onNextForAck(reply: Any?): Long
    }

    private class RecordingHandler : FakeHandler {
        val delivered = mutableListOf<Any?>()
        val errors = mutableListOf<String>()
        var ackCalls = 0

        override fun onNext(reply: Any?) {
            delivered += reply
        }

        override fun onError(reason: String) {
            errors += reason
        }

        override fun onNextForAck(reply: Any?): Long {
            ackCalls += 1
            return 42L
        }
    }

    @Test
    fun `observes onNext then forwards the original reply`() {
        val delegate = RecordingHandler()
        val observed = mutableListOf<Any>()
        val proxy = MossResponseHandlerProxy.wrap(
            FakeHandler::class.java,
            delegate
        ) { observed += it } as FakeHandler

        proxy.onNext("reply")

        assertEquals(listOf<Any>("reply"), observed)
        assertEquals(listOf<Any?>("reply"), delegate.delivered)
    }

    @Test
    fun `observer failure never breaks host delivery`() {
        val delegate = RecordingHandler()
        val proxy = MossResponseHandlerProxy.wrap(
            FakeHandler::class.java,
            delegate
        ) { error("observer blew up") } as FakeHandler

        proxy.onNext("reply")

        assertEquals(listOf<Any?>("reply"), delegate.delivered)
    }

    @Test
    fun `other callbacks are forwarded untouched including return values`() {
        val delegate = RecordingHandler()
        val observed = mutableListOf<Any>()
        val proxy = MossResponseHandlerProxy.wrap(
            FakeHandler::class.java,
            delegate
        ) { observed += it } as FakeHandler

        proxy.onError("boom")
        val ack = proxy.onNextForAck("reply")

        assertEquals(listOf("boom"), delegate.errors)
        assertEquals(42L, ack)
        assertEquals(1, delegate.ackCalls)
        assertTrue(observed.isEmpty())
    }

    @Test
    fun `host thrown exceptions keep their original type`() {
        val delegate = object : FakeHandler {
            override fun onNext(reply: Any?) = throw IllegalStateException("host failure")
            override fun onError(reason: String) = Unit
            override fun onNextForAck(reply: Any?): Long = 0L
        }
        val proxy = MossResponseHandlerProxy.wrap(FakeHandler::class.java, delegate) {} as FakeHandler

        val failure = assertThrows(IllegalStateException::class.java) { proxy.onNext("reply") }
        assertEquals("host failure", failure.message)
    }

    @Test
    fun `refuses to wrap a mismatched delegate or a non interface`() {
        assertNull(MossResponseHandlerProxy.wrap(FakeHandler::class.java, "not a handler") {})
        assertNull(MossResponseHandlerProxy.wrap(String::class.java, "text") {})
        assertNotNull(
            MossResponseHandlerProxy.wrap(FakeHandler::class.java, RecordingHandler()) {}
        )
    }
}
