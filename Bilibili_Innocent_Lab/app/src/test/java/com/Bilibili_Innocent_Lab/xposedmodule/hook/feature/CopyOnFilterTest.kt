package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CopyOnFilterTest {

    @Test
    fun `returns original list when no item matches`() {
        val source = listOf("one", "two")

        assertSame(source, CopyOnFilter.list(source) { false })
    }

    @Test
    fun `copies only after first match and preserves null items`() {
        val source = listOf("keep", null, "drop", "keep-2")

        val filtered = CopyOnFilter.list(source) { it == "drop" }

        assertEquals(listOf("keep", null, "keep-2"), filtered)
        assertEquals(listOf("keep", null, "drop", "keep-2"), source)
    }
}
