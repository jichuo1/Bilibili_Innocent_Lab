package Bilibili_Innocent_Lab.pro.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommentTextIdentityTest {

    @Test
    fun customEmojiAndReplacementSpanProduceSameIdentity() {
        val slot = CommentTextIdentity.EMOJI_SLOT
        assertEquals("前${slot}后", CommentTextIdentity.matchKey("前[dog]后"))
        assertEquals("前${slot}后", CommentTextIdentity.matchKey("前\uFFFC后", listOf(1..1)))
    }

    @Test
    fun unicodeEmojiSequencesOccupyOneSlotEach() {
        val slot = CommentTextIdentity.EMOJI_SLOT
        assertEquals("甲${slot}乙", CommentTextIdentity.matchKey("甲👨‍👩‍👧‍👦乙"))
        assertEquals("甲${slot}乙", CommentTextIdentity.matchKey("甲🇨🇳乙"))
        assertEquals("甲${slot}乙", CommentTextIdentity.matchKey("甲1️⃣乙"))
    }

    @Test
    fun plainDigitsAndTextAreNotMisclassifiedAsEmoji() {
        assertEquals("版本9.8.0", CommentTextIdentity.matchKey(" 版本 9.8.0… "))
    }

    @Test
    fun emojiCountAndPositionRemainPartOfIdentity() {
        val slot = CommentTextIdentity.EMOJI_SLOT
        assertEquals("${slot}相同${slot}", CommentTextIdentity.matchKey("[dog]相同[笑哭]"))
        assertEquals("相同${slot}", CommentTextIdentity.matchKey("相同[dog]"))
    }

    @Test
    fun unspannedZeroWidthSentinelDoesNotChangeIdentity() {
        val slot = CommentTextIdentity.EMOJI_SLOT
        assertEquals(
            slot.toString(),
            CommentTextIdentity.matchKey("\u200B\u200B", listOf(0..0))
        )
    }

    @Test
    fun longBilibiliCustomEmojiTokenStillOccupiesOneSlot() {
        val token = "[联动_${"很长".repeat(18)}]"

        assertEquals(
            "前${CommentTextIdentity.EMOJI_SLOT}后",
            CommentTextIdentity.matchKey("前${token}后")
        )
    }

    @Test
    fun styledFoldControlTailIsProjectedBeforeExpansionLabel() {
        val text = "前\u200B后续内容... 展开"
        val markerStart = text.indexOf("...")
        val actionStart = text.indexOf("展开")

        assertEquals(
            markerStart,
            CommentTextIdentity.foldControlStart(
                text,
                listOf(markerStart until actionStart, actionStart until text.length)
            )
        )
    }

    @Test
    fun localizedFoldActionAndSuffixDoNotNeedHardCodedText() {
        val text = "visible body... Show more ›"
        val markerStart = text.indexOf("...")
        val actionStart = text.indexOf("Show")
        val actionEnd = text.indexOf('›') - 1

        assertEquals(
            markerStart,
            CommentTextIdentity.foldControlStart(
                text,
                listOf(markerStart until actionStart, actionStart..actionEnd)
            )
        )
    }

    @Test
    fun userWrittenExpansionWordWithoutStyledMarkerIsNotRemoved() {
        val text = "请展开说明，这不是折叠控制"
        val actionStart = text.indexOf("展开")

        assertNull(
            CommentTextIdentity.foldControlStart(
                text,
                listOf(actionStart until actionStart + 2)
            )
        )
    }

    @Test
    fun literalEllipsisAndExpansionTextWithoutAdjacentDecorationsIsNotRemoved() {
        val text = "用户原文... 展开"
        val markerStart = text.indexOf("...")

        assertNull(
            CommentTextIdentity.foldControlStart(text, listOf(markerStart until markerStart + 3))
        )
        assertNull(CommentTextIdentity.foldControlStart(text, emptyList()))
    }

    @Test
    fun decoratedEllipsisInMiddleOfLongCommentIsNotFoldControl() {
        val text = "正文... 链接" + "后续内容".repeat(40)
        val markerStart = text.indexOf("...")
        val actionStart = text.indexOf("链接")

        assertNull(
            CommentTextIdentity.foldControlStart(
                text,
                listOf(markerStart until actionStart, actionStart until actionStart + 2)
            )
        )
    }
}
