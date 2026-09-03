package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DexAssistCandidateSelectorTest {

    @Test
    fun `interface bridge is discarded in favor of unique leaf`() {
        val candidates = LeafImplementation::class.java.declaredMethods.filter {
            it.parameterTypes.contentEquals(arrayOf(String::class.java))
        }

        val selected = DexAssistCandidateSelector.selectUniqueLeaf(candidates)

        assertEquals("leaf", selected?.name)
    }

    @Test
    fun `multiple owners fail closed`() {
        val candidates = listOf(
            LeafImplementation::class.java.getDeclaredMethod("leaf", String::class.java),
            OtherImplementation::class.java.getDeclaredMethod("leaf", String::class.java)
        )

        assertNull(DexAssistCandidateSelector.selectUniqueLeaf(candidates))
    }

    @Test
    fun `single owner group keeps every verified entry in stable order`() {
        val candidates = MapperFacade::class.java.declaredMethods.filter {
            it.parameterTypes.contentEquals(arrayOf(String::class.java))
        }

        val selected = DexAssistCandidateSelector.selectSingleOwnerGroup(candidates)

        assertEquals(2, selected.size)
        assertEquals(listOf("first", "second"), selected.map { it.name })
    }

    @Test
    fun `mapper candidates spread over multiple owners fail closed`() {
        val candidates = listOf(
            MapperFacade::class.java.getDeclaredMethod("first", String::class.java),
            OtherMapperFacade::class.java.getDeclaredMethod("third", String::class.java)
        )

        assertEquals(emptyList<Any>(), DexAssistCandidateSelector.selectSingleOwnerGroup(candidates))
    }

    @Test
    fun `empty candidate set fails closed`() {
        assertEquals(emptyList<Any>(), DexAssistCandidateSelector.selectSingleOwnerGroup(emptyList()))
    }

    private interface Contract {
        fun bridge(value: String): String
    }

    private class LeafImplementation : Contract {
        override fun bridge(value: String): String = value
        fun leaf(value: String): String = value
    }

    private class OtherImplementation {
        fun leaf(value: String): String = value
    }

    @Suppress("unused")
    private class MapperFacade {
        fun first(value: String): String = value
        fun second(value: String): String = value
    }

    @Suppress("unused")
    private class OtherMapperFacade {
        fun third(value: String): String = value
    }
}
