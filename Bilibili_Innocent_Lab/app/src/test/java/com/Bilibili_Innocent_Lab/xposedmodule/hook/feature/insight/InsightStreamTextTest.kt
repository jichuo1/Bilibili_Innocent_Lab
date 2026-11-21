package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InsightStreamTextTest {
    private val context = InsightContext(InsightVideo("BV1xx411c7mD", 1), 300, "title", "desc",
        listOf(InsightCue(0.0, 20.0, "开始"), InsightCue(45.0, 60.0, "原理"), InsightCue(90.0, 110.0, "结论")))
    fun `answer appears before JSON closes and never shows partial tags or escapes`() {
        val stream = InsightStreamText(InsightMode.ASK, context)
        assertEquals("原理", stream.append("{\"answer\":\"原理 [[t:"))
        assertEquals("原理 0:45", stream.append("45]]"))
        assertEquals("原理 0:45 在这里", stream.append(" 在这里\"}"))
    }
    fun `every character boundary handles unicode escapes quoted text and surrogate pairs`() {
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
    fun `timed summary uses cue boundaries and never exposes structural punctuation`() {
        val stream = InsightStreamText(InsightMode.SUMMARY, context)
        assertEquals("概述\n\n• 0:00 开始：原", stream.append("""{"overview":"概述","sections":[{"start_cue":0,"title":"开始","summary":"原"""))
        assertEquals("概述\n\n• 0:00 开始：原理", stream.append("""理"}]}"""))
    }
    fun `topic field order may vary and complete serialization remnants are hidden`() {
        val stream = InsightStreamText(InsightMode.COMMENTS, context)
        assertEquals("方向\n观点", stream.append("""{"topics":[{"summary":"观点},{","title":"方向"}]}"""))
    }
    fun `untimed summary and invalid time references never acquire a timestamp`() {
        val untimed = InsightStreamText(InsightMode.SUMMARY, context.copy(cues = emptyList()))
        assertEquals("概述\n\n• 具体结论", untimed.append("""{"overview":"概述","points":["具体结论"]}"""))
        val ask = InsightStreamText(InsightMode.ASK, context)
        assertEquals("超范围  未覆盖", ask.append("""{"answer":"超范围 [[t:999]] 未覆盖 [[t:30]]"}"""))
    }
    fun `stream preview has the same bounded output budget as the final contract`() {
        val stream = InsightStreamText(InsightMode.ASK, context)
        assertTrue(runCatching { stream.append("x".repeat(InsightProtocol.MAX_OUTPUT + 1)) }.isFailure)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (InsightStreamTextTest) -> Unit) {
            val s = InsightStreamTextTest()
            block(s)
        }
        runIsolated { s -> s.`answer appears before JSON closes and never shows partial tags or escapes`() }
        runIsolated { s -> s.`every character boundary handles unicode escapes quoted text and surrogate pairs`() }
        runIsolated { s -> s.`timed summary uses cue boundaries and never exposes structural punctuation`() }
        runIsolated { s -> s.`topic field order may vary and complete serialization remnants are hidden`() }
        runIsolated { s -> s.`untimed summary and invalid time references never acquire a timestamp`() }
        runIsolated { s -> s.`stream preview has the same bounded output budget as the final contract`() }
    
        InsightSummaryTextCases().verifyAllCases()
        InsightTextRevealCases().verifyAllCases()
        InsightThumbnailIndexCases().verifyAllCases()
    }
}

private class InsightSummaryTextCases {
    fun `legacy adjacent tail citations become one leading representative time without a model call`() {
        val raw = "主题概述\n\n• 提出关卡设计的核心是微型叙事。 [[t:168]] [[t:498]]\n\n• 第二个论点 [[t:600]]"
        val formatted = InsightSummaryText.leadingTimes(raw)
        assertEquals("主题概述\n\n• [[t:168]] 提出关卡设计的核心是微型叙事。\n\n• [[t:600]] 第二个论点", formatted)
        assertEquals(listOf(168, 600), InsightTimestamp.links(formatted, 1200, true).map { it.second })
        assertEquals(formatted, InsightSummaryText.leadingTimes(formatted))
    }
    fun `timestamps stay with their own points and untimed summaries remain readable`() {
        assertEquals("概述\n\n• [[t:12]] 第一条\n\n• [[t:90]] 第二条",
            InsightSummaryText.leadingTimes("概述\n\n• 第一条 [[t:12]]\n\n• [[t:90]] 第二条"))
        val plain = "元信息\n\n• 简介中的事实"
        assertEquals(plain, InsightSummaryText.leadingTimes(plain))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (InsightSummaryTextCases) -> Unit) {
            val s = InsightSummaryTextCases()
            block(s)
        }
        runIsolated { s -> s.`legacy adjacent tail citations become one leading representative time without a model call`() }
        runIsolated { s -> s.`timestamps stay with their own points and untimed summaries remain readable`() }
    }
}

private class InsightTextRevealCases {
    fun `new glyphs fade individually while settled prefix stays visible`() {
        val reveal = InsightTextReveal()
        val first = reveal.update("你好", 100)
        assertEquals(2, first.size); assertEquals(0f, first.first().alpha(100), .001f)
        assertTrue(first.first().alpha(190) > first.last().alpha(190))
        assertFalse(reveal.active(500))
        val next = reveal.update("你好世界", 500)
        assertEquals(listOf(2, 3), next.map { it.start })
    }
    fun `surrogate pairs and combining marks share glyph ranges`() {
        val ranges = InsightTextReveal().update("🌙e\u0301中", 0)
        assertEquals(listOf(0 to 2, 2 to 4, 4 to 5), ranges.map { it.start to it.end })
    }
    fun `a combining mark arriving in another chunk replaces the prior glyph span`() {
        val reveal = InsightTextReveal(); reveal.update("e", 0)
        assertEquals(listOf(0 to 2), reveal.update("e\u0301", 10).map { it.start to it.end })
    }
    fun `large bursts have a bounded tail and settle without long typing delays`() {
        val reveal = InsightTextReveal()
        val spans = reveal.update("字".repeat(12_000), 1000)
        assertEquals(96, spans.size)
        assertTrue(reveal.active(1000)); assertFalse(reveal.active(1400))
        assertTrue(spans.all { it.start >= 11_904 })
    }
    fun `repair replaces the old suffix and reset clears all animation state`() {
        val reveal = InsightTextReveal(); reveal.update("旧内容", 100)
        val changed = reveal.update("新", 110)
        assertTrue(changed.all { it.end <= 1 })
        reveal.clear(); assertFalse(reveal.active(110))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (InsightTextRevealCases) -> Unit) {
            val s = InsightTextRevealCases()
            block(s)
        }
        runIsolated { s -> s.`new glyphs fade individually while settled prefix stays visible`() }
        runIsolated { s -> s.`surrogate pairs and combining marks share glyph ranges`() }
        runIsolated { s -> s.`a combining mark arriving in another chunk replaces the prior glyph span`() }
        runIsolated { s -> s.`large bursts have a bounded tail and settle without long typing delays`() }
        runIsolated { s -> s.`repair replaces the old suffix and reset clears all animation state`() }
    }
}

private class InsightThumbnailIndexCases {
    private fun data() = JSONObject().put("img_x_len", 2).put("img_y_len", 2)
        .put("image", JSONArray().put("//bimp.hdslb.com/a.jpg").put("//bimp.hdslb.com/b.jpg"))
    fun `binary header is removed and chapter seconds select the correct sprite and cell`() {
        val seconds = listOf(0, 5, 10, 15, 20, 25)
        val bytes = (listOf(0) + seconds).flatMap { listOf((it shr 8).toByte(), it.toByte()) }.toByteArray()
        val index = InsightThumbnailIndex.parse(data(), bytes)
        assertEquals(seconds, index.times)
        assertEquals(InsightThumbnailIndex.Cell(0, 0, 0), index.cell(0))
        assertEquals(InsightThumbnailIndex.Cell(0, 1, 0), index.cell(9))
        assertEquals(InsightThumbnailIndex.Cell(0, 1, 1), index.cell(15))
        assertEquals(InsightThumbnailIndex.Cell(1, 0, 0), index.cell(22))
        assertEquals(InsightThumbnailIndex.Cell(1, 1, 0), index.cell(100))
    }
    fun `inline preview indexes and malformed or unsafe sources are bounded`() {
        val data = data().put("index", JSONArray(listOf(0, 12, 40)))
        assertEquals(InsightThumbnailIndex.Cell(0, 0, 1), InsightThumbnailIndex.parse(data, byteArrayOf()).cell(40))
        for (invalid in listOf(data().put("img_x_len", 0), data().put("image", JSONArray().put("https://evil.test/image")),
            data().put("index", JSONArray(listOf(10, 0))))) {
            assertTrue(runCatching { InsightThumbnailIndex.parse(invalid, byteArrayOf(0, 0, 0, 0)) }.isFailure)
        }
        assertTrue(runCatching { InsightThumbnailIndex.parse(data(), byteArrayOf(0, 0, 1)) }.isFailure)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (InsightThumbnailIndexCases) -> Unit) {
            val s = InsightThumbnailIndexCases()
            block(s)
        }
        runIsolated { s -> s.`binary header is removed and chapter seconds select the correct sprite and cell`() }
        runIsolated { s -> s.`inline preview indexes and malformed or unsafe sources are bounded`() }
    }
}
