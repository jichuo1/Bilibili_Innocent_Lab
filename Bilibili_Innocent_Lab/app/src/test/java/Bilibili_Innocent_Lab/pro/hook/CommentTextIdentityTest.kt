package Bilibili_Innocent_Lab.pro.hook

import org.junit.Assert.assertEquals
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
}
