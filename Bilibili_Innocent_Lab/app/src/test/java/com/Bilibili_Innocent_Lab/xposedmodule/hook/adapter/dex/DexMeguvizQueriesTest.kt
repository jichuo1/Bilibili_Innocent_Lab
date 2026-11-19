package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录完整性护栏：每个 [DexAssistQuery] 枚举都必须产出非空、可解析且带实际 where 条件的规则。
 *
 * 历史上 configure 是语句 when，新增枚举漏写分支只会静默得到空规则（where 为空对象），
 * 运行时与"无命中"无法区分。现在 configure 改表达式 when 由编译器穷尽，本测试再锁运行时形态：
 * 多规则型（PLAYER_SPEED_MENU/STORY_QUALITY_DEFAULT/COMMENT_REPLY_MAPPER）不得退化成单条空规则。
 */
class DexMeguvizQueriesTest {
    @Test fun `every query produces bounded rules with a non-empty where condition`() {
        for (query in DexAssistQuery.entries) {
            val rules = DexMeguvizQueries.rules(query)
            assertTrue("$query must produce at least one rule", rules.isNotEmpty())
            assertTrue("$query must stay within the batch rule budget", rules.size <= 32)
            for ((index, bytes) in rules.withIndex()) {
                val rule = JSONObject(bytes.toString(Charsets.UTF_8))
                assertEquals("$query#$index schema", 1, rule.getInt("schema_version"))
                assertEquals("$query#$index relation", "method_defs", rule.getString("relation"))
                assertEquals("$query#$index max_results", DexMeguvizQueries.MAX_MATCHES + 1, rule.getInt("max_results"))
                val where = rule.getJSONObject("where")
                assertTrue("$query#$index where must not be empty", where.length() > 0)
            }
        }
        assertEquals("catalog size is pinned so additions update this guard deliberately", 36, DexAssistQuery.entries.size)
    }

    @Test fun `multi-rule queries keep their fan-out rules`() {
        assertTrue(DexMeguvizQueries.rules(DexAssistQuery.PLAYER_SPEED_MENU).size > 1)
        assertTrue(DexMeguvizQueries.rules(DexAssistQuery.STORY_QUALITY_DEFAULT).size > 1)
        assertTrue(DexMeguvizQueries.rules(DexAssistQuery.COMMENT_REPLY_MAPPER).size > 1)
        assertEquals(3, DexMeguvizQueries.rules(DexAssistQuery.WECHAT_SHARE_WRAPPER_DISCOVERY).size)
        assertEquals(2, DexMeguvizQueries.rules(DexAssistQuery.FOLLOW_FEED_DECORATION_CONSTRUCTORS).size)
    }

    @Test fun `owner member and declaration rules address exact descriptors`() {
        val member = JSONObject(DexMeguvizQueries.ownerMembers("Lcom/example/Owner;").toString(Charsets.UTF_8))
        assertEquals("Lcom/example/Owner;", member.getJSONObject("where").getString("owner"))
        val declaration = JSONObject(
            DexMeguvizQueries.declaration("Lcom/example/Owner;", "run", "(I)V").toString(Charsets.UTF_8)
        ).getJSONObject("where")
        assertEquals("Lcom/example/Owner;", declaration.getString("owner"))
        assertEquals("run", declaration.getString("name"))
        assertEquals("(I)V", declaration.getString("prototype"))
    }
}
