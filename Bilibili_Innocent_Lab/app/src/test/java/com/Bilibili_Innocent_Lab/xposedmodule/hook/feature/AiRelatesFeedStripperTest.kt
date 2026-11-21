package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import aistub.v1.RelatesFeedReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

class AiRelatesFeedStripperTest {
    private val stripper = checkNotNull(
        AiRelatesFeedStripper.resolve(javaClass.classLoader!!, "aistub.v1.RelatesFeedReply")
    )
    private val knownAi = setOf("ai-1", "ai-2")
    private val isKnownAi: (Any) -> Boolean = { it in knownAi }

    fun `known ai cards are removed in order and other fields are kept`() {
        val original = RelatesFeedReply(listOf("a", "ai-1", "b", "ai-2", "c"), "next-page")
        val (updated, removed) = checkNotNull(stripper.strip(original, isKnownAi))
        updated as RelatesFeedReply
        assertNotSame(original, updated)
        assertEquals(listOf("a", "b", "c"), updated.relatesList)
        assertEquals("next-page", updated.offset)
        assertEquals(2, removed)
        // 源响应不被改动。
        assertEquals(listOf("a", "ai-1", "b", "ai-2", "c"), original.relatesList)
    }

    fun `page without known ai cards is left untouched`() {
        assertNull(stripper.strip(RelatesFeedReply(listOf("a", "b"), ""), isKnownAi))
    }

    /** 空页可能被宿主当成"没有更多了"，所以一页全中时原样交付。 */
    fun `page made only of known ai cards is delivered as is`() {
        assertNull(stripper.strip(RelatesFeedReply(listOf("ai-1", "ai-2"), "next"), isKnownAi))
    }

    fun `default instance and foreign objects are never rewritten`() {
        assertNull(stripper.strip(RelatesFeedReply.getDefaultInstance(), isKnownAi))
        assertNull(stripper.strip("not a reply", isKnownAi))
    }

    fun `missing reply class disables the stripper`() {
        assertNull(AiRelatesFeedStripper.resolve(javaClass.classLoader!!, "aistub.v1.Missing"))
        assertNotNull(AiRelatesFeedStripper.resolve(javaClass.classLoader!!, "aistub.v1.RelatesFeedReply"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AiRelatesFeedStripperTest) -> Unit) {
            val s = AiRelatesFeedStripperTest()
            block(s)
        }
        runIsolated { s -> s.`known ai cards are removed in order and other fields are kept`() }
        runIsolated { s -> s.`page without known ai cards is left untouched`() }
        runIsolated { s -> s.`page made only of known ai cards is delivered as is`() }
        runIsolated { s -> s.`default instance and foreign objects are never rewritten`() }
        runIsolated { s -> s.`missing reply class disables the stripper`() }
    }
}
