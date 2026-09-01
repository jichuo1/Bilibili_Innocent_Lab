package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRelateReasonFilterTest {

    private class Badge(private val value: String) {
        fun getText(): String = value
    }

    private class Card(private val value: String) {
        fun getReason(): Badge = Badge(value)
        fun getDirectReason(): String = value
    }

    private class OtherBadge {
        fun getText(): String = "should-not-run"
    }

    private class ThrowingCard {
        fun getDirectReason(): String = error("host getter failed")
    }

    @Test
    fun `reader supports validated direct and nested chains`() {
        val direct = Card::class.java.getDeclaredMethod("getDirectReason")
        val nested = Card::class.java.getDeclaredMethod("getReason")
        val text = Badge::class.java.getDeclaredMethod("getText")
        listOf(direct, nested, text).forEach { it.isAccessible = true }
        val paths = VideoRelateReasonReader.buildMethodPaths(
            listOf(listOf(direct), listOf(nested, text))
        )

        assertEquals(2, paths.size)
        assertEquals(setOf("商业推广"), VideoRelateReasonReader.read(Card("商业推广"), paths))
    }

    @Test
    fun `reader rejects incompatible paths and fails open on host errors`() {
        val nested = Card::class.java.getDeclaredMethod("getReason")
        val incompatible = OtherBadge::class.java.getDeclaredMethod("getText")
        val throwing = ThrowingCard::class.java.getDeclaredMethod("getDirectReason")
        listOf(nested, incompatible, throwing).forEach { it.isAccessible = true }

        assertTrue(
            VideoRelateReasonReader.buildMethodPaths(listOf(listOf(nested, incompatible))).isEmpty()
        )
        val paths = VideoRelateReasonReader.buildMethodPaths(listOf(listOf(throwing)))
        assertTrue(VideoRelateReasonReader.read(ThrowingCard(), paths).isEmpty())
    }

    @Test
    fun `high confidence promotion labels do not treat ordinary reasons as ads`() {
        listOf(
            "去小程序",
            "预约游戏",
            "网友期待游戏",
            "全平台预约",
            "速来预约",
            "快来预约",
            "快来体验",
            "速来体验",
            "速速下载",
            "快来下载",
            "速来下载",
            "点击下载",
            "立即预约"
        ).forEach { phrase ->
            assertTrue(
                "$phrase should be recognized inside a structured recommendation reason",
                VideoRelateReasonMatcher.matchesHighConfidencePromotion(
                    setOf("新游$phrase，限时开启")
                )
            )
        }
        assertFalse(
            VideoRelateReasonMatcher.matchesHighConfidencePromotion(
                setOf("3万点赞", "大家都在看", "立即体验")
            )
        )
    }

    @Test
    fun `custom keywords are bounded and matched only against supplied reasons`() {
        val keywords = VideoRelateReasonMatcher.parseCustom(
            "商业推广,Mini Program\n${"x".repeat(97)}"
        )

        assertEquals(setOf("商业推广", "mini program"), keywords)
        assertTrue(
            VideoRelateReasonMatcher.matchesCustom(
                setOf("Open MINI PROGRAM now"),
                keywords
            )
        )
        assertFalse(VideoRelateReasonMatcher.matchesCustom(setOf("3万点赞"), keywords))
    }
}
