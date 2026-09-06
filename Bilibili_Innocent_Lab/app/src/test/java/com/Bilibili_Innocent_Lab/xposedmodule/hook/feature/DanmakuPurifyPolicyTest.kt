package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuPurifyPolicyTest {

    @Test
    fun `retain returns null when nothing is dropped`() {
        val source = listOf("a", "b", "c")
        assertNull(DanmakuPurifyPolicy.retain(source) { true })
    }

    @Test
    fun `retain keeps order and drops only rejected items`() {
        val source = listOf("keep", "drop", "keep-2", "drop-2")
        val retained = DanmakuPurifyPolicy.retain(source) { it.toString().startsWith("keep") }
        assertEquals(listOf("keep", "keep-2"), retained)
        assertEquals(listOf("keep", "drop", "keep-2", "drop-2"), source)
    }

    @Test
    fun `retain drops null entries without crashing`() {
        val source = listOf("keep", null, "keep-2")
        val retained = DanmakuPurifyPolicy.retain(source) { true }
        assertEquals(listOf("keep", "keep-2"), retained)
    }

    @Test
    fun `weight signal counts as unusable when the whole segment reads zero or fails`() {
        val source = listOf(0, 0, 0)
        assertFalse(DanmakuPurifyPolicy.hasUsableWeight(source) { it as Int })
        assertFalse(DanmakuPurifyPolicy.hasUsableWeight(source) { null })
        assertTrue(DanmakuPurifyPolicy.hasUsableWeight(listOf(0, 4)) { it as Int })
    }

    @Test
    fun `weight threshold is clamped to the supported range`() {
        assertEquals(DanmakuPurifyPolicy.MIN_WEIGHT, DanmakuPurifyPolicy.normalizeWeight(-7))
        assertEquals(DanmakuPurifyPolicy.MAX_WEIGHT, DanmakuPurifyPolicy.normalizeWeight(99))
        assertEquals(5, DanmakuPurifyPolicy.normalizeWeight(5))
        assertTrue(
            DanmakuPurifyPolicy.DEFAULT_MINIMUM_WEIGHT in
                DanmakuPurifyPolicy.MIN_WEIGHT..DanmakuPurifyPolicy.MAX_WEIGHT
        )
    }
}
