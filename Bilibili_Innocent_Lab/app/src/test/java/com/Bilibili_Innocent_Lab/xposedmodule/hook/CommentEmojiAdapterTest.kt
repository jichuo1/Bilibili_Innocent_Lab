package com.Bilibili_Innocent_Lab.xposedmodule.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentEmojiAdapterTest {

    private class FakeRichText(
        @Suppress("unused") val raw: String,
        @Suppress("unused") val contents: List<Any>
    )

    private class FakeCommentItem(private val richText: FakeRichText) {
        @Suppress("unused")
        fun richPayload(): FakeRichText = richText
    }

    private class FakeEmote(
        @Suppress("unused") private val imageUrl: String,
        @Suppress("unused") private val animUrl: String,
        @Suppress("unused") private val text: String
    ) {
        override fun toString(): String = "Emote(text=$text)"
    }

    private open class FakeSpanBase(@Suppress("unused") private val resourceUrl: String)
    private class FakeSpan(resourceUrl: String) : FakeSpanBase(resourceUrl)
    private class FakeOtherSpan(@Suppress("unused") private val resourceUrl: String)

    @Test
    fun featureLocatedRichTextMapsStaticAndAnimatedUrlsIndependently() {
        val longToken = "[联动_${"很长".repeat(18)}]"
        val staticUrl = "https://i0.hdslb.com/static.webp"
        val animatedUrl = "https://i0.hdslb.com/animated.webp"
        val raw = "前${longToken}中[dog]后"
        val item = FakeCommentItem(
            FakeRichText(
                raw,
                listOf(
                    FakeEmote(staticUrl, "", longToken),
                    FakeEmote("https://i0.hdslb.com/dog.webp", animatedUrl, "[dog]")
                )
            )
        )
        val first = FakeSpan(staticUrl)
        val unrelated = FakeOtherSpan("https://i0.hdslb.com/card.webp")
        val second = FakeSpan(animatedUrl)

        val result = CommentEmojiAdapter.resolve(item, raw, listOf(first, unrelated, second))

        assertEquals(2, result.emotes.size)
        assertEquals(2, result.urlMatchedCount)
        assertTrue(result.spanMatches[0].span === first)
        assertEquals(longToken, result.spanMatches[0].emote.token)
        assertTrue(result.spanMatches[1].span === second)
        assertEquals("[dog]", result.spanMatches[1].emote.token)
        assertEquals(raw.indexOf(longToken), result.emotes[0].rawStart)
        assertEquals(raw.indexOf("[dog]"), result.emotes[1].rawStart)
    }

    @Test
    fun nonEmoteReplacementDoesNotDiscardConfirmedEmojiMatch() {
        val raw = "正文[dog]卡片"
        val url = "https://i0.hdslb.com/dog.webp"
        val item = FakeCommentItem(FakeRichText(raw, listOf(FakeEmote(url, "", "[dog]"))))
        val unrelated = FakeOtherSpan("https://i0.hdslb.com/card.webp")
        val emoji = FakeSpan(url)

        val result = CommentEmojiAdapter.resolve(item, raw, listOf(unrelated, emoji))

        assertEquals(2, result.inspectedSpanCount)
        assertEquals(1, result.urlMatchedCount)
        assertTrue(result.spanMatches.single().span === emoji)
        assertEquals("[dog]", result.spanMatches.single().emote.token)
    }
}
