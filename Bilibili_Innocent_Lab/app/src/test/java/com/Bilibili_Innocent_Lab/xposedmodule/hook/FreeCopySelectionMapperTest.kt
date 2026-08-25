package com.Bilibili_Innocent_Lab.xposedmodule.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreeCopySelectionMapperTest {

    @Test
    fun selectedPlaceholderCopiesCustomEmojiToken() {
        val text = "你好\uFFFC世界"
        val replacements = listOf(FreeCopySelectionMapper.Replacement(2, 3, "[dog]"))

        assertEquals("[dog]", FreeCopySelectionMapper.mapSelection(text, 2, 3, replacements))
        assertEquals("好[dog]世", FreeCopySelectionMapper.mapSelection(text, 1, 4, replacements))
    }

    @Test
    fun actualBilibiliZeroWidthSpanCopiesCustomEmojiToken() {
        val text = "你好\u200B世界"
        val replacements = listOf(FreeCopySelectionMapper.Replacement(2, 3, "[dog]"))

        assertEquals("[dog]", FreeCopySelectionMapper.mapSelection(text, 2, 3, replacements))
        assertEquals("好[dog]世", FreeCopySelectionMapper.mapSelection(text, 1, 4, replacements))
    }

    @Test
    fun partialSelectionInsideReplacementExpandsToWholeToken() {
        val text = "甲[dog]乙"
        val replacements = listOf(FreeCopySelectionMapper.Replacement(1, 6, "[dog]"))

        assertEquals("[dog]", FreeCopySelectionMapper.mapSelection(text, 3, 4, replacements))
    }

    @Test
    fun repeatedEmojiKeepTheirOwnOrderAndMeaning() {
        val text = "\uFFFC和\uFFFC"
        val replacements = listOf(
            FreeCopySelectionMapper.Replacement(0, 1, "[dog]"),
            FreeCopySelectionMapper.Replacement(2, 3, "[笑哭]")
        )

        assertEquals("[dog]和[笑哭]", FreeCopySelectionMapper.mapSelection(text, 0, 3, replacements))
    }

    @Test
    fun ordinaryTextAndUnicodeEmojiRemainUnchanged() {
        val text = "原生😂文本"

        assertEquals(text, FreeCopySelectionMapper.mapSelection(text, 0, text.length, emptyList()))
    }

    @Test
    fun emptySelectionDoesNotWriteClipboard() {
        assertNull(FreeCopySelectionMapper.mapSelection("文本", 1, 1, emptyList()))
    }

    @Test
    fun pureCustomEmojiAlignsDespiteTrailingBilibiliSentinel() {
        val aligned = FreeCopySelectionMapper.alignCustomEmojiTokens(
            rawText = "[dog]",
            displayText = "\u200B\u200B",
            displayReplacementRanges = listOf(0..0),
            expectedTokens = listOf("[dog]")
        )

        assertEquals(
            listOf(FreeCopySelectionMapper.AlignedReplacement(0, 1, 0, 5, "[dog]")),
            aligned
        )
    }

    @Test
    fun nativeEmojiDoesNotStealCustomEmojiMapping() {
        val raw = "原生😂和[dog]"
        val display = "原生😂和\u200B\u200B"
        val spanStart = display.indexOf('\u200B')

        val aligned = FreeCopySelectionMapper.alignCustomEmojiTokens(
            rawText = raw,
            displayText = display,
            displayReplacementRanges = listOf(spanStart..spanStart),
            expectedTokens = listOf("[dog]")
        )

        assertEquals("[dog]", aligned?.single()?.copyText)
        assertEquals(raw.indexOf("[dog]"), aligned?.single()?.rawStart)
    }

    @Test
    fun literalBracketTextDoesNotConsumeReplacementToken() {
        val raw = "[说明]和[dog]"
        val display = "[说明]和\u200B\u200B"
        val spanStart = display.indexOf('\u200B')

        val aligned = FreeCopySelectionMapper.alignCustomEmojiTokens(
            rawText = raw,
            displayText = display,
            displayReplacementRanges = listOf(spanStart..spanStart),
            expectedTokens = listOf("[dog]")
        )

        assertEquals("[dog]", aligned?.single()?.copyText)
        assertEquals(raw.indexOf("[dog]"), aligned?.single()?.rawStart)
    }

    @Test
    fun foldedVisiblePrefixStillMapsToFullRawToken() {
        val raw = "前[dog]后续还有很长的正文"
        val display = "前\u200B后续…\u200B"

        val aligned = FreeCopySelectionMapper.alignCustomEmojiTokens(
            rawText = raw,
            displayText = display,
            displayReplacementRanges = listOf(1..1),
            expectedTokens = listOf("[dog]")
        )

        assertEquals("[dog]", aligned?.single()?.copyText)
        assertEquals(raw.indexOf("[dog]"), aligned?.single()?.rawStart)
    }

    @Test
    fun repeatedCustomEmojiKeepOneToOneRawRanges() {
        val raw = "[dog]和[dog]"
        val display = "\u200B和\u200B\u200B"

        val aligned = FreeCopySelectionMapper.alignCustomEmojiTokens(
            rawText = raw,
            displayText = display,
            displayReplacementRanges = listOf(0..0, 2..2),
            expectedTokens = listOf("[dog]", "[dog]")
        )

        assertEquals(2, aligned?.size)
        assertEquals(0, aligned?.get(0)?.rawStart)
        assertEquals(raw.lastIndexOf("[dog]"), aligned?.get(1)?.rawStart)
    }

    @Test
    fun unknownReplacementDoesNotDiscardEarlierConfirmedEmoji() {
        val raw = "[dog]和[未知]"
        val display = "\u200B和\u200B\u200B"

        val aligned = FreeCopySelectionMapper.alignCustomEmojiTokens(
            rawText = raw,
            displayText = display,
            displayReplacementRanges = listOf(0..0, 2..2),
            expectedTokens = listOf("[dog]")
        )

        assertEquals(1, aligned?.size)
        assertEquals("[dog]", aligned?.single()?.copyText)
    }

    @Test
    fun literalSameTokenDoesNotConsumeModelEmoji() {
        val raw = "[dog]文字[dog]"
        val display = "[dog]文字\u200B\u200B"
        val spanStart = display.indexOf('\u200B')

        val aligned = FreeCopySelectionMapper.alignCustomEmojiTokens(
            rawText = raw,
            displayText = display,
            displayReplacementRanges = listOf(spanStart..spanStart),
            expectedTokens = listOf("[dog]")
        )

        assertEquals(raw.lastIndexOf("[dog]"), aligned?.single()?.rawStart)
    }
}
