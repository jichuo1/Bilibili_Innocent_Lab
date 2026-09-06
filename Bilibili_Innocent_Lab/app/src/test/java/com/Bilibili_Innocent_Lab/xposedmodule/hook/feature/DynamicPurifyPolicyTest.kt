package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicPurifyPolicyTest {

    private fun plan(
        keywords: Set<String> = emptySet(),
        authorRules: AuthorRuleSet = AuthorRuleSet.EMPTY,
        removePromotion: Boolean = false,
        removeLockedChargeOnly: Boolean = false
    ) = DynamicPurifyPolicy.Plan(keywords, authorRules, removePromotion, removeLockedChargeOnly)

    /** 用固定的几段正文模拟宿主的分段回调；返回值语义与安装器里的实现一致。 */
    private fun fragments(vararg values: String): ((String) -> Boolean) -> Boolean = { matches ->
        values.any(matches)
    }

    @Test
    fun `keyword matches any single text fragment`() {
        val judgement = plan(keywords = RuleSetCodec.parse("恰饭, Sponsor"))

        assertTrue(
            DynamicPurifyPolicy.shouldRemove(
                DynamicPurifyPolicy.Signals(textFragments = fragments("今天恰饭了")),
                judgement
            )
        )
        assertTrue(
            DynamicPurifyPolicy.shouldRemove(
                DynamicPurifyPolicy.Signals(textFragments = fragments("普通正文", "SPONSOR ad")),
                judgement
            )
        )
        assertFalse(
            DynamicPurifyPolicy.shouldRemove(
                DynamicPurifyPolicy.Signals(textFragments = fragments("普通正文")),
                judgement
            )
        )
    }

    @Test
    fun `keywords never match across fragment boundaries`() {
        val judgement = plan(keywords = RuleSetCodec.parse("恰饭"))

        // 拼接会得到"…恰" + "饭…" = 命中；逐段匹配不会。
        assertFalse(
            DynamicPurifyPolicy.shouldRemove(
                DynamicPurifyPolicy.Signals(textFragments = fragments("今天恰", "饭好吃")),
                judgement
            )
        )
    }

    @Test
    fun `text is not read at all when no keyword is configured`() {
        var reads = 0
        val signals = DynamicPurifyPolicy.Signals(
            textFragments = { _ ->
                reads += 1
                true
            }
        )

        assertFalse(DynamicPurifyPolicy.shouldRemove(signals, plan()))
        assertEquals(0, reads)
    }

    @Test
    fun `author promotion and charge-only judgements are independent`() {
        val byAuthor = plan(authorRules = AuthorRuleSet.parse("777"))
        assertTrue(
            DynamicPurifyPolicy.shouldRemove(
                DynamicPurifyPolicy.Signals(authorMid = 777L),
                byAuthor
            )
        )
        assertFalse(
            DynamicPurifyPolicy.shouldRemove(DynamicPurifyPolicy.Signals(), byAuthor)
        )

        assertTrue(
            DynamicPurifyPolicy.shouldRemove(
                DynamicPurifyPolicy.Signals(promotion = true),
                plan(removePromotion = true)
            )
        )
        assertFalse(
            DynamicPurifyPolicy.shouldRemove(
                DynamicPurifyPolicy.Signals(promotion = true),
                plan()
            )
        )

        assertTrue(
            DynamicPurifyPolicy.shouldRemove(
                DynamicPurifyPolicy.Signals(lockedChargeOnly = true),
                plan(removeLockedChargeOnly = true)
            )
        )
        assertFalse(
            DynamicPurifyPolicy.shouldRemove(
                DynamicPurifyPolicy.Signals(lockedChargeOnly = false),
                plan(removeLockedChargeOnly = true)
            )
        )
    }

    @Test
    fun `plan reports what is actually active`() {
        assertFalse(plan().hasAnyItemJudgement)
        assertFalse(plan().needsText)
        assertTrue(plan(keywords = setOf("a")).needsText)
        assertTrue(plan(authorRules = AuthorRuleSet.parse("1")).needsAuthor)
        assertTrue(plan(removePromotion = true).hasAnyItemJudgement)
        assertTrue(plan(removeLockedChargeOnly = true).hasAnyItemJudgement)
        // 只按发布者过滤时不该白读一遍正文。
        assertFalse(plan(authorRules = AuthorRuleSet.parse("1")).needsText)
    }

    @Test
    fun `promotion additional type fallbacks stay aligned with the constant names`() {
        assertEquals(
            DynamicPurifyPolicy.PROMOTION_ADDITIONAL_TYPE_FIELDS.size,
            DynamicPurifyPolicy.FALLBACK_PROMOTION_ADDITIONAL_TYPES.size
        )
    }
}
