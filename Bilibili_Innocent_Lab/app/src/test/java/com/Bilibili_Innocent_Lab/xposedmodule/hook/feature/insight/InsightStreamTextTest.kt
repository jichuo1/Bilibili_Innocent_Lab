package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InsightStreamTextTest {
    private val context = InsightContext(InsightVideo("BV1xx411c7mD", 1), 300, "title", "desc",
        listOf(InsightCue(0.0, 20.0, "开始"), InsightCue(45.0, 60.0, "原理"), InsightCue(90.0, 110.0, "结论")))
    @Test fun `answer appears before JSON closes and never shows partial tags or escapes`() {
        val stream = InsightStreamText(InsightMode.ASK, context)
        assertEquals("原理", stream.append("{\"answer\":\"原理 [[t:"))
        assertEquals("原理 0:45", stream.append("45]]"))
        assertEquals("原理 0:45 在这里", stream.append(" 在这里\"}"))
    }
    @Test fun `every character boundary handles unicode escapes quoted text and surrogate pairs`() {
        val raw = """{"answer":"你好\n\"引用\" \uD83C\uDF19 [[t:45]]。"}"""
        val expected = "你好\n\"引用\" 🌙 0:45。"
        for (split in 0..raw.length) {
            val stream = InsightStreamText(InsightMode.ASK, context)
            val first = stream.append(raw.take(split))
            assertFalse(first.lastOrNull()?.isHighSurrogate() == true)
            assertFalse(first.contains("[[")); assertFalse(first.contains("\\u"))
            assertEquals(expected, stream.append(raw.drop(split)))
        }
    }
    @Test fun `timed summary uses cue boundaries and never exposes structural punctuation`() {
        val stream = InsightStreamText(InsightMode.SUMMARY, context)
        assertEquals("概述\n\n• 0:00 开始：原", stream.append("""{"overview":"概述","sections":[{"start_cue":0,"title":"开始","summary":"原"""))
        assertEquals("概述\n\n• 0:00 开始：原理", stream.append("""理"}]}"""))
    }
    @Test fun `topic field order may vary and complete serialization remnants are hidden`() {
        val stream = InsightStreamText(InsightMode.COMMENTS, context)
        assertEquals("方向\n观点", stream.append("""{"topics":[{"summary":"观点},{","title":"方向"}]}"""))
    }
    @Test fun `untimed summary and invalid time references never acquire a timestamp`() {
        val untimed = InsightStreamText(InsightMode.SUMMARY, context.copy(cues = emptyList()))
        assertEquals("概述\n\n• 具体结论", untimed.append("""{"overview":"概述","points":["具体结论"]}"""))
        val ask = InsightStreamText(InsightMode.ASK, context)
        assertEquals("超范围  未覆盖", ask.append("""{"answer":"超范围 [[t:999]] 未覆盖 [[t:30]]"}"""))
    }
    @Test fun `stream preview has the same bounded output budget as the final contract`() {
        val stream = InsightStreamText(InsightMode.ASK, context)
        assertTrue(runCatching { stream.append("x".repeat(InsightProtocol.MAX_OUTPUT + 1)) }.isFailure)
    }
}
