package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyTopologyTextTest {

    @Test
    fun collapsesWhitespaceAndRemovesHostLayoutSentinels() {
        assertEquals(
            "第一行 第二行[dog]",
            ReplyTopologyText.summarize("  第一行\n\t第二行\u200B[dog]  ", 120)
        )
    }

    @Test
    fun neverSplitsUtf16SurrogatePair() {
        assertEquals("A😀…", ReplyTopologyText.summarize("A😀BC", 2))
    }

    @Test
    fun keepsZwjEmojiSequenceTogether() {
        assertEquals("A👩‍💻…", ReplyTopologyText.summarize("A👩‍💻BC", 2))
    }

    @Test
    fun keepsCombiningMarkWithItsBaseCharacter() {
        assertEquals("Ae\u0301…", ReplyTopologyText.summarize("Ae\u0301BC", 2))
    }

    @Test
    fun keepsBilibiliBracketEmojiTokenWhole() {
        assertEquals("A[dog]…", ReplyTopologyText.summarize("A[dog]BC", 3))
    }

    @Test
    fun rawSnapshotBoundsMessageAndAuthorImmediately() {
        val snapshot = ReplyTopologyNodeSnapshot.fromRaw(
            rpid = 1L,
            rootRpid = 2L,
            parentRpid = 2L,
            authorName = "作者名字",
            repliedAuthorName = "回复对象",
            message = "一二三四五",
            previewCodePoints = 3
        )

        assertEquals("一二三…", snapshot.messagePreview)
        assertEquals("作者名字", snapshot.authorName)
        assertEquals("回复对象", snapshot.repliedAuthorName)
    }
}
