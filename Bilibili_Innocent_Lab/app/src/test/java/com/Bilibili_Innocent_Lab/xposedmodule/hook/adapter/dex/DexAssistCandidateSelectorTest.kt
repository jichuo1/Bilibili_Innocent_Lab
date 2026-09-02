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
}
