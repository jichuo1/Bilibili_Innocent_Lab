package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import org.junit.Assert.*
import org.junit.Test

class InsightTextRevealTest {
    @Test fun `new glyphs fade individually while settled prefix stays visible`() {
        val reveal = InsightTextReveal()
        val first = reveal.update("你好", 100)
        assertEquals(2, first.size); assertEquals(0f, first.first().alpha(100), .001f)
        assertTrue(first.first().alpha(190) > first.last().alpha(190))
        assertFalse(reveal.active(500))
        val next = reveal.update("你好世界", 500)
        assertEquals(listOf(2, 3), next.map { it.start })
    }
    @Test fun `surrogate pairs and combining marks share glyph ranges`() {
        val ranges = InsightTextReveal().update("🌙e\u0301中", 0)
        assertEquals(listOf(0 to 2, 2 to 4, 4 to 5), ranges.map { it.start to it.end })
    }
    @Test fun `a combining mark arriving in another chunk replaces the prior glyph span`() {
        val reveal = InsightTextReveal(); reveal.update("e", 0)
        assertEquals(listOf(0 to 2), reveal.update("e\u0301", 10).map { it.start to it.end })
    }
    @Test fun `large bursts have a bounded tail and settle without long typing delays`() {
        val reveal = InsightTextReveal()
        val spans = reveal.update("字".repeat(12_000), 1000)
        assertEquals(96, spans.size)
        assertTrue(reveal.active(1000)); assertFalse(reveal.active(1400))
        assertTrue(spans.all { it.start >= 11_904 })
    }
    @Test fun `repair replaces the old suffix and reset clears all animation state`() {
        val reveal = InsightTextReveal(); reveal.update("旧内容", 100)
        val changed = reveal.update("新", 110)
        assertTrue(changed.all { it.end <= 1 })
        reveal.clear(); assertFalse(reveal.active(110))
    }
}
