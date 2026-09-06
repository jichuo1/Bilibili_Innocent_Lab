package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorRuleSetTest {

    @Test
    fun `splits numeric uids from names`() {
        val rules = AuthorRuleSet.parse("12345\n某位UP主；6789, 0")

        assertEquals(setOf(12345L, 6789L), rules.mids)
        assertEquals(setOf("某位up主", "0"), rules.names)
        assertTrue(rules.isNotEmpty())
    }

    @Test
    fun `names match exactly and ignore case and padding`() {
        val rules = AuthorRuleSet.parse("SomeUp")

        assertTrue(rules.matches("someup", null))
        assertTrue(rules.matches("  SomeUp  ", null))
        // 全等匹配：一条规则不能把带前缀/后缀的其它作者一起删掉。
        assertFalse(rules.matches("SomeUploader", null))
        assertFalse(rules.matches("aSomeUp", null))
    }

    @Test
    fun `unreadable signals never match`() {
        val rules = AuthorRuleSet.parse("42, name")

        assertFalse(rules.matches(null, null))
        assertFalse(AuthorRuleSet.EMPTY.matches("name", 42L))
        assertTrue(AuthorRuleSet.EMPTY.isEmpty())
    }

    @Test
    fun `zero and negative uids fall back to name matching`() {
        val rules = AuthorRuleSet.parse("0, -5")

        assertFalse(rules.matches(null, 0L))
        assertFalse(rules.matches(null, -5L))
        assertTrue(rules.matches("0", null))
        assertTrue(rules.matches("-5", null))
    }

    @Test
    fun `rule count is bounded`() {
        val raw = (1..AuthorRuleSet.MAX_RULES + 50).joinToString(",")
        val rules = AuthorRuleSet.parse(raw)

        assertEquals(AuthorRuleSet.MAX_RULES, rules.mids.size + rules.names.size)
    }
}
