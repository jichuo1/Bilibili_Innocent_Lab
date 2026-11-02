package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 Android ICU 与 JVM Pattern 对未转义右花括号的行为不同。 */
@RunWith(AndroidJUnit4::class)
class InsightCommentTextInstrumentedTest {
    @Test fun oldCommentSeparatorsAreRemovedOnAndroidIcu() {
        val raw = "资源\r\n第一段。},{\r\n\r\n方法\r\n第二段。},{ },{"
        assertEquals("资源\r\n第一段。\r\n\r\n方法\r\n第二段。", InsightCommentText.cleanLegacy(raw))
        val ordinary = "讨论 {a:1},{b:2} 的含义。"
        assertEquals(ordinary, InsightCommentText.cleanLegacy(ordinary))
    }
    @Test fun newCommentOutputIsRejectedBeforeRenderingOnAndroid() {
        val input = InsightContext(InsightVideo("BV1xx411c7mD", 123), 90, "标题", "简介")
        val raw = """{"topics":[{"title":"观点","summary":"第一段。},{"}]}"""
        try { InsightProtocol.decode(InsightMode.COMMENTS, raw, input); fail("Expected local validation") }
        catch (invalid: InsightValidationException) { assertEquals("topic-prose", invalid.rule) }
    }
}
