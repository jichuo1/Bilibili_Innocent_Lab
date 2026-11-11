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
            AuthorRuleSet.EMPTY,
        fish: FishScorePolicy.Plan? = null
    ) = CommentFilterFeatureInstaller.JudgementPlan(
        keywords = keywords,
        minimumLevel = minimumLevel,
        removeAtOnly = removeAtOnly,
        userRules = userRules,
        fish = fish
    )

    private fun fishPlan(whitelist: String = "") =
        requireNotNull(FishScorePolicy.Config(enabled = true, rawWhitelist = whitelist).plan())

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

    @Test
    fun `fish judgement is the fifth criterion and fails open on unreadable signals`() {
        val fish = plan(fish = fishPlan())
        assertTrue(fish.hasAnyJudgement)
        // 打分要读正文，哪怕没开关键词判据。
        assertTrue(fish.needsMessage)
        assertTrue(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals(message = "本鲸来汇报", level = 2, authorName = "小蓝的大肥鱼"), fish
            )
        )
        assertFalse(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals(message = "这个视频讲得很清楚", level = 2, authorName = "路人"), fish
            )
        )
        assertFalse(CommentFilterFeatureInstaller.shouldRemove(CommentFilterFeatureInstaller.Signals(), fish))
        assertTrue(fish.describe().contains("fish>=60"))
    }

    @Test
    fun `fish whitelist protects only against fish scoring and never against the other criteria`() {
        val signals = CommentFilterFeatureInstaller.Signals(
            message = "本鲸来汇报（摇了摇尾巴）", level = 2, authorName = "小蓝的大肥鱼", authorMid = 42L
        )
        assertTrue(CommentFilterFeatureInstaller.shouldRemove(signals, plan(fish = fishPlan())))
        assertFalse(CommentFilterFeatureInstaller.shouldRemove(signals, plan(fish = fishPlan(whitelist = "42"))))
        // 发布者过滤是用户明确写下的名单，不受 AI 判据的放行名单影响。
        assertTrue(
            CommentFilterFeatureInstaller.shouldRemove(
                signals, plan(fish = fishPlan(whitelist = "42"), userRules = AuthorRuleSet.parse("42"))
            )
        )
        assertTrue(
            CommentFilterFeatureInstaller.shouldRemove(
                signals, plan(fish = fishPlan(whitelist = "42"), keywords = setOf("汇报"))
            )
        )
    }

    @Test
    fun `reply verdict memo computes once per list instance and size`() {
        val memo = ReplyVerdictMemo()
        var calls = 0
        val list = mutableListOf<Any>("a", "b")
        fun verdicts(source: List<*>) = memo.getOrCompute(source) {
            calls++
            java.util.IdentityHashMap<Any, Boolean>().apply { source.filterNotNull().forEach { put(it, it == "b") } }
        }
        assertSame(verdicts(list), verdicts(list))
        assertEquals(1, calls)
        // 同一引用但长度变了：宿主换了内容，不能复用。
        list += "c"
        verdicts(list)
        assertEquals(2, calls)
        // 不同实例即使内容相同也各算各的。
        verdicts(listOf<Any>("a", "b", "c"))
        assertEquals(3, calls)
        // 容量有限：最旧的被挤掉后重新计算。
        repeat(6) { index -> verdicts(listOf<Any>("x$index")) }
        verdicts(list)
        assertEquals(10, calls)
    }
}
