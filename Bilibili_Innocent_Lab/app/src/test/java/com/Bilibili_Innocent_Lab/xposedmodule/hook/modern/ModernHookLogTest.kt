package com.Bilibili_Innocent_Lab.xposedmodule.hook.modern

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ModernHookLogTest {
    fun tearDown() = ModernHookLog.bindSink(null)

    fun `messages go to the bound framework sink`() {
        val seen = mutableListOf<Pair<String, Throwable?>>()
        ModernHookLog.bindSink { message, throwable -> seen += message to throwable }
        val failure = IllegalStateException("x")
        ModernHookLog.info("a")
        ModernHookLog.error("b", failure)
        assertEquals(listOf("a" to null, "b" to failure), seen)
    }

    /** binder 断开时框架通道会抛；JVM 里 android.util.Log 回退也会抛——两者都不能逃逸。 */
    fun `failing framework sink and failing system log never throw to the caller`() {
        ModernHookLog.bindSink { _, _ -> throw IllegalStateException("binder died") }
        ModernHookLog.info("a")
        ModernHookLog.error("b", RuntimeException())
    }

    fun `unbound log falls back without throwing`() {
        ModernHookLog.bindSink(null)
        ModernHookLog.info("a")
        ModernHookLog.error("b")
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (ModernHookLogTest) -> Unit) {
            val s = ModernHookLogTest()
            try {
                block(s)
            } finally {
                s.tearDown()
            }
        }
        runIsolated { s -> s.`messages go to the bound framework sink`() }
        runIsolated { s -> s.`failing framework sink and failing system log never throw to the caller`() }
        runIsolated { s -> s.`unbound log falls back without throwing`() }
    }
}
