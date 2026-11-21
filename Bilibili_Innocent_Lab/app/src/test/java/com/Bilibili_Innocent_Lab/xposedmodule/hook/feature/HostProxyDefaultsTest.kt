package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy

class HostProxyDefaultsTest {
    private interface Listener {
        fun onClick(view: Any?)
        fun isEnabled(): Boolean
        fun priority(): Int
        fun id(): Long
        fun label(): String?
    }

    fun `primitive return types get zero values and references get null`() {
        assertEquals(false, hostProxyDefaultValue(java.lang.Boolean.TYPE))
        assertEquals(0.toByte(), hostProxyDefaultValue(java.lang.Byte.TYPE))
        assertEquals('\u0000', hostProxyDefaultValue(java.lang.Character.TYPE))
        assertEquals(0.toShort(), hostProxyDefaultValue(java.lang.Short.TYPE))
        assertEquals(0, hostProxyDefaultValue(java.lang.Integer.TYPE))
        assertEquals(0L, hostProxyDefaultValue(java.lang.Long.TYPE))
        assertEquals(0f, hostProxyDefaultValue(java.lang.Float.TYPE))
        assertEquals(0.0, hostProxyDefaultValue(java.lang.Double.TYPE))
        assertNull(hostProxyDefaultValue(Void.TYPE))
        assertNull(hostProxyDefaultValue(String::class.java))
    }

    /** 代理对未处理的方法返回 null 时，基本类型返回值会在调用方抛 NPE；用零值就不会。 */
    fun `proxy answering unknown methods with defaults never throws at the call site`() {
        val listener = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Listener::class.java)) { _, method, _ ->
            hostProxyDefaultValue(method.returnType)
        } as Listener
        listener.onClick(null)
        assertEquals(false, listener.isEnabled())
        assertEquals(0, listener.priority())
        assertEquals(0L, listener.id())
        assertNull(listener.label())
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostProxyDefaultsTest) -> Unit) {
            val s = HostProxyDefaultsTest()
            block(s)
        }
        runIsolated { s -> s.`primitive return types get zero values and references get null`() }
        runIsolated { s -> s.`proxy answering unknown methods with defaults never throws at the call site`() }
    }
}
