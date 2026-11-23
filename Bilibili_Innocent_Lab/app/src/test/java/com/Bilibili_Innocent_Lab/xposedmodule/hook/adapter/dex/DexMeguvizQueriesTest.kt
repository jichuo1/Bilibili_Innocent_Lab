package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentComposeMenuAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SystemEnvironmentAdapter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `every query produces bounded rules with a non-empty where condition`() {
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
        assertEquals("catalog size is pinned so additions update this guard deliberately", 40, DexAssistQuery.entries.size)
    }

    fun `multi-rule queries keep their fan-out rules`() {
        assertTrue(DexMeguvizQueries.rules(DexAssistQuery.PLAYER_SPEED_MENU).size > 1)
        assertTrue(DexMeguvizQueries.rules(DexAssistQuery.STORY_QUALITY_DEFAULT).size > 1)
        assertTrue(DexMeguvizQueries.rules(DexAssistQuery.COMMENT_REPLY_MAPPER).size > 1)
        assertEquals(3, DexMeguvizQueries.rules(DexAssistQuery.WECHAT_SHARE_WRAPPER_DISCOVERY).size)
        assertEquals(2, DexMeguvizQueries.rules(DexAssistQuery.FOLLOW_FEED_DECORATION_CONSTRUCTORS).size)
        assertEquals(5, DexMeguvizQueries.rules(DexAssistQuery.COMMENT_COMPOSE_MORE_MENU).size)
        val sponsorRun = DexMeguvizQueries.rules(DexAssistQuery.SPONSOR_RUN_PLAYABLE)
        val sponsorWrapper = DexMeguvizQueries.rules(DexAssistQuery.SPONSOR_PLAYER_WRAPPER)
        assertEquals(2, sponsorRun.size)
        assertEquals(2, sponsorWrapper.size)
        assertTrue(JSONObject(sponsorRun[0].toString(Charsets.UTF_8)).getJSONObject("where").has("owner"))
        assertFalse(JSONObject(sponsorRun[1].toString(Charsets.UTF_8)).getJSONObject("where").has("owner"))
        assertTrue(sponsorRun[1].toString(Charsets.UTF_8).contains(SponsorPlayerLocator.COROUTINE_ERROR))
        assertTrue(JSONObject(sponsorWrapper[0].toString(Charsets.UTF_8)).getJSONObject("where").has("owner"))
        assertFalse(JSONObject(sponsorWrapper[1].toString(Charsets.UTF_8)).getJSONObject("where").has("owner"))
        assertTrue(sponsorWrapper[1].toString(Charsets.UTF_8).contains("seekTo"))
    }

    fun `compose menu and grpc wire queries avoid obfuscated method names`() {
        val wireRules = DexMeguvizQueries.rules(DexAssistQuery.KMP_GRPC_WIRE_ENGINE)
        assertEquals(1, wireRules.size)
        val wireText = wireRules.single().toString(Charsets.UTF_8)
        val wireWhere = JSONObject(wireText).getJSONObject("where")
        assertFalse(wireWhere.has("name"))
        assertFalse(wireWhere.has("owner"))
        KmpGrpcWireAdapter.ENTRY_MARKS.forEach { token ->
            assertTrue("Missing KMP wire token $token", wireText.contains(token))
        }

        val composeText = DexMeguvizQueries.rules(DexAssistQuery.COMMENT_COMPOSE_MORE_MENU)
            .joinToString("\n") { it.toString(Charsets.UTF_8) }
        for (mark in listOf(
            CommentComposeMenuAdapter.BUBBLE_TRACE_MARK,
            CommentComposeMenuAdapter.GROUPED_TRACE_MARK,
            CommentComposeMenuAdapter.GROUPED_ITEM_TRACE_MARK,
            CommentComposeMenuAdapter.LEGACY_TRACE_MARK
        )) {
            assertTrue("Missing compose menu trace $mark", composeText.contains(mark))
        }
    }

    fun `system environment queries match static descriptors and semantic constants without method names`() {
        val rootRules = DexMeguvizQueries.rules(DexAssistQuery.SYSTEM_ENV_ROOT_RESULT)
        val hookRules = DexMeguvizQueries.rules(DexAssistQuery.SYSTEM_ENV_HOOK_RESULT)
        assertEquals(1, rootRules.size)
        assertEquals(1, hookRules.size)

        val rootText = rootRules.single().toString(Charsets.UTF_8)
        val hookText = hookRules.single().toString(Charsets.UTF_8)
        val rootWhere = JSONObject(rootText).getJSONObject("where")
        val hookWhere = JSONObject(hookText).getJSONObject("where")

        assertFalse("SYSTEM_ENV_ROOT_RESULT must not pin obfuscated method name", rootWhere.has("name"))
        assertFalse("SYSTEM_ENV_HOOK_RESULT must not pin obfuscated method name", hookWhere.has("name"))
        assertFalse("SYSTEM_ENV_ROOT_RESULT must not match shared /system/bin/su helper", rootText.contains("/system/bin/su"))
        assertFalse("SYSTEM_ENV_HOOK_RESULT must not match shared /system/bin/su helper", hookText.contains("/system/bin/su"))
        assertTrue(rootText.contains("Ljava/util/Set;"))
        SystemEnvironmentAdapter.ENV_STRINGS.forEach { token ->
            assertTrue("Missing ENV_STRINGS token $token", rootText.contains(token))
        }
        SystemEnvironmentAdapter.HOOK_STRINGS.forEach { token ->
            assertTrue("Missing HOOK_STRINGS token $token", hookText.contains(token))
        }
    }

    fun `owner member and declaration rules address exact descriptors`() {
        val member = JSONObject(DexMeguvizQueries.ownerMembers("Lcom/example/Owner;").toString(Charsets.UTF_8))
        assertEquals("Lcom/example/Owner;", member.getJSONObject("where").getString("owner"))
        val declaration = JSONObject(
            DexMeguvizQueries.declaration("Lcom/example/Owner;", "run", "(I)V").toString(Charsets.UTF_8)
        ).getJSONObject("where")
        assertEquals("Lcom/example/Owner;", declaration.getString("owner"))
        assertEquals("run", declaration.getString("name"))
        assertEquals("(I)V", declaration.getString("prototype"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (DexMeguvizQueriesTest) -> Unit) {
            val s = DexMeguvizQueriesTest()
            block(s)
        }
        runIsolated { s -> s.`every query produces bounded rules with a non-empty where condition`() }
        runIsolated { s -> s.`multi-rule queries keep their fan-out rules`() }
        runIsolated { s -> s.`compose menu and grpc wire queries avoid obfuscated method names`() }
        runIsolated { s -> s.`system environment queries match static descriptors and semantic constants without method names`() }
        runIsolated { s -> s.`owner member and declaration rules address exact descriptors`() }
    }
}
