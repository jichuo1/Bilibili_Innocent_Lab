package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentFilterFeatureInstallerTest {

    private fun plan(
        keywords: Set<String> = emptySet(),
        minimumLevel: Int? = null,
        removeAtOnly: Boolean = false,
        userRules: AuthorRuleSet =
            AuthorRuleSet.EMPTY
    ) = CommentFilterFeatureInstaller.JudgementPlan(
        keywords = keywords,
        minimumLevel = minimumLevel,
        removeAtOnly = removeAtOnly,
        userRules = userRules
    )

    @Test
    fun `filters exact keyword containment case insensitively`() {
        val rules = RuleSetCodec.parse("剧透，Spoiler")

        assertTrue(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals("这条含有剧透", 6),
                rules,
                null
            )
        )
        assertTrue(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals("SPOILER warning", 6),
                rules,
                null
            )
        )
        assertFalse(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals("正常评论", 6),
                rules,
                null
            )
        )
    }

    @Test
    fun `removes only known levels below threshold and fails open when unreadable`() {
        assertTrue(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals(level = 2),
                emptySet(),
                3
            )
        )
        assertFalse(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals(level = 3),
                emptySet(),
                3
            )
        )
        assertFalse(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals(level = null),
                emptySet(),
                3
            )
        )
    }

    @Test
    fun `returns original list when unchanged and immutable copy when filtered`() {
        val source = listOf("keep", "drop", "keep-2")
        val unchanged = CommentFilterFeatureInstaller.filterComments(source) { false }
        val filtered = CommentFilterFeatureInstaller.filterComments(source) { it == "drop" }

        assertSame(source, unchanged)
        assertEquals(listOf("keep", "keep-2"), filtered)
        assertEquals(source, listOf("keep", "drop", "keep-2"))
    }

    @Test
    fun `at-only detection needs both the mention list and an empty residue`() {
        assertTrue(
            CommentFilterFeatureInstaller.isAtOnlyComment("@张三 @李四 ", setOf("张三", "李四"))
        )
        assertTrue(CommentFilterFeatureInstaller.isAtOnlyComment("@张三：", setOf("张三")))
        assertFalse(
            CommentFilterFeatureInstaller.isAtOnlyComment("@张三 说得好", setOf("张三"))
        )
        // 读不到 @ 名单时一律保留，不按"正文很短"猜。
        assertFalse(CommentFilterFeatureInstaller.isAtOnlyComment("@张三", null))
        assertFalse(CommentFilterFeatureInstaller.isAtOnlyComment("@张三", emptySet()))
        assertFalse(CommentFilterFeatureInstaller.isAtOnlyComment(null, setOf("张三")))
    }

    @Test
    fun `at-only detection removes the longest mention first`() {
        // 先删短名字会给"@张三丰"留下一个孤立的"丰"，从而漏删。
        assertTrue(
            CommentFilterFeatureInstaller.isAtOnlyComment(
                "@张三丰 @张三",
                setOf("张三", "张三丰")
            )
        )
    }

    @Test
    fun `author rules split numeric uids from names and match exactly`() {
        val rules = AuthorRuleSet.parse("12345, 某位UP主, 0")

        assertTrue(rules.matches(null, 12345L))
        assertFalse(rules.matches(null, 1234L))
        assertTrue(rules.matches("某位UP主", null))
        assertTrue(rules.matches("  某位up主  ", null))
        // 全等匹配：包含关系不能命中，否则一条规则会误删一大片。
        assertFalse(rules.matches("某位UP主的粉丝", null))
        // 0 不是合法 UID，只能按名字看待，因此不会匹配 mid=0。
        assertFalse(rules.matches(null, 0L))
        assertTrue(AuthorRuleSet.parse("").isEmpty())
    }

    @Test
    fun `each judgement removes independently and unknown signals fail open`() {
        val atOnly = plan(removeAtOnly = true)
        assertTrue(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals(message = "@张三", atNames = setOf("张三")),
                atOnly
            )
        )
        assertFalse(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals(message = "@张三", atNames = null),
                atOnly
            )
        )

        val byAuthor = plan(userRules = AuthorRuleSet.parse("777"))
        assertTrue(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals(authorMid = 777L),
                byAuthor
            )
        )
        assertFalse(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals(authorMid = null, authorName = null),
                byAuthor
            )
        )
    }

    @Test
    fun `judgement plan reports what is actually active`() {
        assertFalse(plan().hasAnyJudgement)
        assertTrue(plan(keywords = setOf("a")).hasAnyJudgement)
        assertTrue(plan(minimumLevel = 3).hasAnyJudgement)
        assertTrue(plan(removeAtOnly = true).hasAnyJudgement)
        assertTrue(
            plan(userRules = AuthorRuleSet.parse("1")).hasAnyJudgement
        )

        // 只有关键词或 @ 判据才需要读正文；按等级/发布者过滤不该白读一次 message。
        assertFalse(plan(minimumLevel = 3).needsMessage)
        assertTrue(plan(keywords = setOf("a")).needsMessage)
        assertTrue(plan(removeAtOnly = true).needsMessage)
    }
}
