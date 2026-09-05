package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.*
import org.junit.Test

class PgcAutoActivityPopupPolicyTest {
    private class Popup
    private fun filter(values: Any?, size: Int = 9, index: Int = 7) =
        PgcAutoActivityPopupPolicy.filter(values, size, index, Popup::class.java)

    @Test
    fun `copies once and preserves the original array and all unrelated identities`() {
        val original = Array<Any?>(9) { Any() }.apply { this[7] = Popup() }
        val filtered = filter(original) as PgcAutoActivityPopupPolicy.Decision.Filtered
        assertNotSame(original, filtered.values)
        assertNull(filtered.values[7])
        assertTrue(original[7] is Popup)
        original.indices.filter { it != 7 }.forEach { assertSame(original[it], filtered.values[it]) }
        assertSame(PgcAutoActivityPopupPolicy.Decision.Absent, filter(filtered.values))
    }

    @Test
    fun `empty payload and missing half remain unchanged without a filtered result`() {
        assertSame(PgcAutoActivityPopupPolicy.Decision.Absent, filter(arrayOfNulls<Any>(9)))
        listOf(null, "not-array", emptyArray<Any>(), arrayOfNulls<Any>(8)).forEach {
            assertSame(PgcAutoActivityPopupPolicy.Decision.InvalidShape, filter(it))
        }
        assertSame(PgcAutoActivityPopupPolicy.Decision.InvalidShape, filter(arrayOfNulls<Any>(9), index = 9))
    }

    @Test
    fun `wrong type is not removed and a shifted slot is supported`() {
        val wrong = arrayOf<Any?>(Any(), "not-a-popup")
        assertSame(PgcAutoActivityPopupPolicy.Decision.UnexpectedType, filter(wrong, 2, 1))
        assertEquals("not-a-popup", wrong[1])
        val shifted = arrayOf<Any?>(Popup(), Any())
        val filtered = filter(shifted, 2, 0) as PgcAutoActivityPopupPolicy.Decision.Filtered
        assertNull(filtered.values[0])
        assertSame(shifted[1], filtered.values[1])
    }
}
