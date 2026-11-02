package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import org.junit.Assert.*
import org.junit.Test

class InsightCommentTextTest {
    @Test fun `legacy paragraph separators disappear while ordinary braces and quotations survive`() {
        val raw = "资源\n来源为已读评论。},{\n\n学习方法\n讨论先听后看。} , {"
        val clean = InsightCommentText.cleanLegacy(raw)
        assertEquals("资源\n来源为已读评论。\n\n学习方法\n讨论先听后看。", clean)
        assertEquals(clean, InsightCommentText.cleanLegacy(clean))
        assertEquals(clean, InsightCommentText.cleanLegacy(raw.replace("\n", "\r\n")).replace("\r\n", "\n"))
        assertEquals("主题\n讨论。", InsightCommentText.cleanLegacy("主题\n讨论。},{ },{"))
        val prose = "代码\n观众讨论 {a:1},{b:2} 的含义，以及引号‘{’。"
        assertEquals(prose, InsightCommentText.cleanLegacy(prose))
        assertFalse(InsightCommentText.hasTrailingSeparator(prose))
    }
}
