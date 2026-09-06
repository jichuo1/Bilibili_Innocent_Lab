package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSetCodecTest {

    @Test
    fun `matching preserves whole string Unicode lowercase and nullable value semantics`() {
        val cases = listOf<String?>(
            null, "", "直播Game", "İstanbul", "i\u0307stanbul", "ΟΣ", "ος", "οσ", "ı", "I", "ß", "ẞ", "😀A"
        )
        val rules = listOf("", "game", "İ", "i\u0307", "σ", "ς", "ı", "ß", "😀")
        for (rule in rules) {
            val tokens = RuleSetCodec.parse(rule)
            for (first in cases) {
                for (second in cases) {
                    val values = arrayOf(first, second)
                    val expected = tokens.isNotEmpty() && values.asSequence()
                        .filterNotNull().map(String::lowercase)
                        .any { value -> tokens.any(value::contains) }
                    assertEquals("rule=$rule values=${values.contentToString()}",
                        expected, RuleSetCodec.matches(tokens, *values))
                }
            }
        }
        // 调用者可直接传集合，空 token 的旧行为也必须保留。
        assertTrue(RuleSetCodec.matches(setOf(""), ""))
        assertFalse(RuleSetCodec.matches(setOf(""), null))
    }

    @Test
    fun `parses supported separators and matches case insensitive substrings`() {
        val rules = RuleSetCodec.parse("直播, 番剧；Game\n 课堂 ")

        assertEquals(setOf("直播", "番剧", "game", "课堂"), rules)
        assertTrue(RuleSetCodec.matches(rules, "HomeGameFragment"))
        assertTrue(RuleSetCodec.matches(rules, "直播"))
        assertFalse(RuleSetCodec.matches(rules, "推荐"))
    }
}
