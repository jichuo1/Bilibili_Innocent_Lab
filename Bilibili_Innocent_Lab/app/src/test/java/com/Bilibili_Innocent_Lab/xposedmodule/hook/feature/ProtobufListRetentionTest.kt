package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtobufListRetentionTest {

    @Test
    fun `retainOrNull signals no rewrite by returning null`() {
        val source = listOf("a", "b")
        assertNull(ProtobufListRetention.retainOrNull(source) { true })
    }

    @Test
    fun `retainOrNull keeps order and drops nulls`() {
        val source = listOf("keep", null, "drop", "keep-2")
        val retained = ProtobufListRetention.retainOrNull(source) { it != "drop" }
        assertEquals(listOf("keep", "keep-2"), retained)
        assertEquals(listOf("keep", null, "drop", "keep-2"), source)
    }

    @Test
    fun `filterOrSame returns the same instance when nothing matches`() {
        val source = listOf("a", "b")
        assertSame(source, ProtobufListRetention.filterOrSame(source) { false })
    }

    @Test
    fun `filterOrSame returns an immutable copy that preserves nulls`() {
        val source = listOf("keep", null, "drop")
        val filtered = ProtobufListRetention.filterOrSame(source) { it == "drop" }

        assertNotSame(source, filtered)
        assertEquals(listOf("keep", null), filtered)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (filtered as MutableList<Any?>).add("x")
        }
    }
}
