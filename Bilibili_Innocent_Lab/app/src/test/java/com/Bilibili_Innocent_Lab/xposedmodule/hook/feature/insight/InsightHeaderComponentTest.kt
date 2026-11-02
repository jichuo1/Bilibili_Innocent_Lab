package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import android.view.View
import org.junit.Assert.*
import org.junit.Test

interface InsightComponentFixture {
    fun viewReusingKey(): Any
    fun identityEqualityKey(): Any
    fun contentEqualityKey(): Any
}
interface InsightEntryFixture { fun getRoot(): View }

class InsightHeaderComponentTest {
    private fun header() = InsightHeaderComponent(InsightComponentFixture::class.java, InsightEntryFixture::class.java,
        Unit, { error("No Android view needed for the component contract") }, bind = {}).value
    @Test fun `native adapter updates preserve every original item and insert exactly one module header`() {
        val first = Any(); val second = Any(); val older = header(); val current = header()
        val original = listOf(first, older, second)
        val updated = InsightHeaderComponent.prepend(original, current)
        assertEquals(3, updated.size)
        assertSame(current, updated[0]); assertSame(first, updated[1]); assertSame(second, updated[2])
        assertSame(older, original[1])
        assertEquals(updated, InsightHeaderComponent.prepend(updated, current))
    }
    @Test fun `process cached component keys contain stable values rather than views or owners`() {
        val first = header() as InsightComponentFixture; val second = header() as InsightComponentFixture
        assertEquals(InsightHeaderComponent.KEY, first.viewReusingKey())
        assertEquals(first.viewReusingKey(), second.viewReusingKey())
        assertEquals(InsightHeaderComponent.KEY, first.identityEqualityKey())
        assertEquals(InsightHeaderComponent.KEY, first.contentEqualityKey())
        assertNotEquals(first, second)
        assertTrue(first == first)
    }
}
