package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactRuleSetCodecTest {

    @Test fun `parses the same separators the rule editors already accept`() {
        assertEquals(setOf("影视飓风", "科技"), ExactRuleSetCodec.parse("影视飓风, 科技"))
        assertEquals(setOf("a", "b"), ExactRuleSetCodec.parse("A；b"))
        assertEquals(emptySet<String>(), ExactRuleSetCodec.parse(null))
        assertEquals(emptySet<String>(), ExactRuleSetCodec.parse("  \n "))
    }

    @Test fun `matching is whole string so substrings never leak through`() {
        val blocked = ExactRuleSetCodec.parse("科技,影视")
        assertTrue(ExactRuleSetCodec.matches(blocked, "科技"))
        // contains 语义会让"科技"命中"科技美学"；整串相等不会。
        assertFalse(ExactRuleSetCodec.matches(blocked, "科技美学"))
        assertFalse(ExactRuleSetCodec.matches(blocked, "影视飓风"))
    }

    @Test fun `matching ignores case and surrounding whitespace`() {
        val blocked = ExactRuleSetCodec.parse("TechLead")
        assertTrue(ExactRuleSetCodec.matches(blocked, "techlead"))
        assertTrue(ExactRuleSetCodec.matches(blocked, "  TECHLEAD  "))
    }

    @Test fun `any of the supplied values may match so name and mid share one list`() {
        val blocked = ExactRuleSetCodec.parse("3546963345672636")
        // 作者链会同时给出 UP 名与 mid（mid 按十进制字符串出口）。
        assertTrue(ExactRuleSetCodec.matches(blocked, "心理日记馆", "3546963345672636"))
        assertFalse(ExactRuleSetCodec.matches(blocked, "心理日记馆", "354696334567263"))
    }

    @Test fun `unreadable values are always allowed through`() {
        val blocked = ExactRuleSetCodec.parse("科技")
        assertFalse(ExactRuleSetCodec.matches(blocked, null))
        assertFalse(ExactRuleSetCodec.matches(blocked, "  "))
        assertFalse(ExactRuleSetCodec.matches(blocked))
        assertFalse(ExactRuleSetCodec.matches(emptySet(), "科技"))
    }

    @Test fun `encode normalizes deduplicates and round trips`() {
        assertEquals("科技,影视", ExactRuleSetCodec.encode(listOf(" 科技 ", "影视", "科技")))
        assertEquals("a", ExactRuleSetCodec.encode(listOf("A", "a")))
        assertEquals(
            setOf("科技", "影视"),
            ExactRuleSetCodec.parse(ExactRuleSetCodec.encode(setOf("科技", "影视")))
        )
    }

    @Test fun `panel writes append without duplicating an existing entry`() {
        assertEquals("科技,影视", ExactRuleSetCodec.add("科技", "影视"))
        assertEquals("科技", ExactRuleSetCodec.add("科技", "科技"))
        assertEquals("科技", ExactRuleSetCodec.add("科技", " 科技 "))
        assertEquals("科技", ExactRuleSetCodec.add(null, "科技"))
    }
}
