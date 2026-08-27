package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSetCodecTest {

    @Test
    fun `parses supported separators and matches case insensitive substrings`() {
        val rules = RuleSetCodec.parse("直播, 番剧；Game\n 课堂 ")

        assertEquals(setOf("直播", "番剧", "game", "课堂"), rules)
        assertTrue(RuleSetCodec.matches(rules, "HomeGameFragment"))
        assertTrue(RuleSetCodec.matches(rules, "直播"))
        assertFalse(RuleSetCodec.matches(rules, "推荐"))
    }
}
