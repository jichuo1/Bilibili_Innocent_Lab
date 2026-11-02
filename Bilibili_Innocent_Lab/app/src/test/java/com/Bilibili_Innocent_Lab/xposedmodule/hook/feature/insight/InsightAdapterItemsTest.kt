package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import org.junit.Assert.*
import org.junit.Test

class InsightAdapterItemsTest {
    class Legacy {
        private var data: List<Any> = listOf("old")
        private val unrelated = listOf("cache")
        fun getItems(): List<Any> = data
        fun replace(items: List<Any>) { data = items }
    }
    class Current {
        @JvmField var d: List<Any> = listOf("current")
        fun updateData(items: List<Any>) { d = items }
    }
    class Ambiguous {
        @JvmField var first: List<Any> = emptyList()
        @JvmField var second: List<Any> = emptyList()
        fun updateData(items: List<Any>) { first = items }
    }
    class StaticOnly {
        companion object { @JvmField var data: List<Any> = emptyList() }
        fun updateData(items: List<Any>) = Unit
    }
    class MultipleWriters {
        @JvmField var data: List<Any> = emptyList()
        fun first(items: List<Any>) { data = items }
        fun second(items: List<Any>) { data = items }
    }
    @Test fun `legacy getter wins over unrelated list fields`() {
        val value = Legacy(); val plan = checkNotNull(InsightAdapterItems.resolve(value.javaClass))
        assertEquals("method", plan.readKind); assertEquals(listOf("old"), plan.read(value))
        plan.write.invoke(value, listOf("header", "old"))
        assertEquals(listOf("header", "old"), plan.read(value))
    }
    @Test fun `current native adapter reads its unique list field and writes through updateData`() {
        val value = Current(); val plan = checkNotNull(InsightAdapterItems.resolve(value.javaClass))
        assertEquals("field", plan.readKind); assertEquals(listOf("current"), plan.read(value))
        plan.write.invoke(value, listOf("header", "current"))
        assertEquals(listOf("header", "current"), value.d)
    }
    @Test fun `ambiguous list fields static fields and overloaded update plans are rejected`() {
        assertNull(InsightAdapterItems.resolve(Ambiguous::class.java))
        assertNull(InsightAdapterItems.resolve(StaticOnly::class.java))
        assertNull(InsightAdapterItems.resolve(MultipleWriters::class.java))
    }
}
