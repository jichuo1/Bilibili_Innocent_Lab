package com.Bilibili_Innocent_Lab.xposedmodule.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentTextIdentityTest {

    @Test fun decoratedReplyNameIsRemovedOnlyFromVerifiedCompleteBody() {
        val raw = "5000多楼6225评论(;¬_¬)手有点酸，如果不是特殊方法进来的话是更多完整正文[大哭]"
        val prefix = "名字里:也有冒号: "
        val rendered = prefix + raw.replace("[大哭]", "\u200B") + "\u200B"
        val emoji = rendered.indexOf('\u200B')
        val start = CommentTextIdentity.replyBodyStart(raw, rendered, listOf(emoji..emoji),
            listOf(0 until prefix.length - 2))
        assertEquals(prefix.length, start)
        assertEquals(CommentTextIdentity.matchKey(raw), CommentTextIdentity.matchKey(
            rendered.substring(start!!), listOf(emoji - start..emoji - start)))
        assertNull(CommentTextIdentity.replyBodyStart(raw, rendered, listOf(emoji..emoji), emptyList()))
        assertNull(CommentTextIdentity.replyBodyStart("另一条完整正文", rendered, listOf(emoji..emoji),
            listOf(0 until prefix.length - 2)))
    }

    @Test fun previewProjectionRejectsTruncationAndUnmarkedBodyPrefixes() {
        val raw = "不能截断的完整回复正文"
        assertNull(CommentTextIdentity.replyBodyStart(raw, "昵称: 不能截断...", emptyList(), listOf(0..1)))
        assertNull(CommentTextIdentity.replyBodyStart(raw, "额外正文" + raw, emptyList(), listOf(0..1)))
        assertNull(CommentTextIdentity.replyBodyStart("哈哈", "昵称: 哈哈", emptyList(), listOf(0..1)))
        assertEquals(0, CommentTextIdentity.replyBodyStart(raw, raw, emptyList(), emptyList()))
    }

    @Test fun pureEmojiReplyNeedsTheSameCountAndDecoratedNameBoundary() {
        val rendered = "昵称: \u200B\u200B\u200B"
        val ranges = listOf(4..4, 5..5, 6..6)
        assertEquals(4, CommentTextIdentity.replyBodyStart("[大哭][大哭][大哭]", rendered, ranges, listOf(0..1)))
        assertNull(CommentTextIdentity.replyBodyStart("[大哭][大哭][大哭][大哭]", rendered, ranges, listOf(0..1)))
        assertNull(CommentTextIdentity.replyBodyStart("[大哭][大哭]", rendered, ranges, listOf(0..1)))
    }

    @Test
    fun literalSearchSpanAndThreeEmojiKeepTheSameCommentIdentity() {
        val prefix = "人类最古のav号（挂了的不算）也变成bv了"
        val raw = prefix + "[大哭][大哭][大哭]青春结束了"
        val visible = prefix + "\u200B\u200B\u200B青春结束了"
        val keyword = prefix.indexOf("bv")
        val ranges = listOf(keyword until keyword + 2) + (0..2).map {
            prefix.length + it..prefix.length + it
        }
        assertFalse(CommentTextIdentity.isEmojiReplacement(visible, keyword, keyword + 2))
        assertEquals(CommentTextIdentity.matchKey(raw), CommentTextIdentity.matchKey(visible, ranges))
        assertTrue(CommentTextIdentity.isEmojiReplacement(visible, prefix.length, prefix.length + 1))
        assertFalse(CommentTextIdentity.matchKey(raw) == CommentTextIdentity.matchKey(
            visible.replace("人类", "另一条"), ranges))
    }

    @Test
    fun replacementClassificationRequiresOneWholeEmojiRepresentation() {
        assertTrue(CommentTextIdentity.isEmojiReplacement("[大哭]", 0, 4))
        assertTrue(CommentTextIdentity.isEmojiReplacement("😀", 0, 2))
        assertFalse(CommentTextIdentity.isEmojiReplacement("文字😀", 0, 4))
        assertFalse(CommentTextIdentity.isEmojiReplacement("bv", -1, 2))
        assertFalse(CommentTextIdentity.isEmojiReplacement("bv", 1, 1))
    }

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
