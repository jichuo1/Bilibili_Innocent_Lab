package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import org.junit.Assert.*
import org.junit.Test

class InsightSummaryTextTest {
    @Test fun `legacy adjacent tail citations become one leading representative time without a model call`() {
        val raw = "主题概述\n\n• 提出关卡设计的核心是微型叙事。 [[t:168]] [[t:498]]\n\n• 第二个论点 [[t:600]]"
        val formatted = InsightSummaryText.leadingTimes(raw)
        assertEquals("主题概述\n\n• [[t:168]] 提出关卡设计的核心是微型叙事。\n\n• [[t:600]] 第二个论点", formatted)
        assertEquals(listOf(168, 600), InsightTimestamp.links(formatted, 1200, true).map { it.second })
        assertEquals(formatted, InsightSummaryText.leadingTimes(formatted))
    }
    @Test fun `timestamps stay with their own points and untimed summaries remain readable`() {
        assertEquals("概述\n\n• [[t:12]] 第一条\n\n• [[t:90]] 第二条",
            InsightSummaryText.leadingTimes("概述\n\n• 第一条 [[t:12]]\n\n• [[t:90]] 第二条"))
        val plain = "元信息\n\n• 简介中的事实"
        assertEquals(plain, InsightSummaryText.leadingTimes(plain))
    }
}
