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

    private class Menu(private val feedback: Boolean) {
        fun hasFeedback(): Boolean = feedback
    }

    private class CommercialCard(
        private val direct: Boolean,
        private val feedback: Boolean
    ) {
        fun hasCmStock(): Boolean = direct
        fun getThreePoint(): Menu = Menu(feedback)
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
        val observation = VideoRelateReasonReader.observe(ThrowingCard(), paths)
        assertFalse(observation.hasUsableReason)
        assertTrue(
            observation.issueFlags and VideoRelateReasonReader.ISSUE_INVOCATION_FAILED != 0
        )
    }

    @Test
    fun `reader preserves missing empty and oversized reason states`() {
        val direct = Card::class.java.getDeclaredMethod("getDirectReason").apply {
            isAccessible = true
        }
        val paths = VideoRelateReasonReader.buildMethodPaths(listOf(listOf(direct)))

        val missing = VideoRelateReasonReader.observe(Card("unused"), emptyList())
        val empty = VideoRelateReasonReader.observe(Card("  "), paths)
        val oversized = VideoRelateReasonReader.observe(Card("x".repeat(257)), paths)

        assertTrue(missing.issueFlags and VideoRelateReasonReader.ISSUE_NO_PATH != 0)
        assertTrue(empty.issueFlags and VideoRelateReasonReader.ISSUE_EMPTY_VALUE != 0)
        assertTrue(oversized.issueFlags and VideoRelateReasonReader.ISSUE_TOO_LONG != 0)
        assertFalse(missing.hasUsableReason)
        assertFalse(empty.hasUsableReason)
        assertFalse(oversized.hasUsableReason)
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

    @Test
    fun `strong mode matches promotion wording and generalized like counts`() {
        listOf(
            "大家都在看",
            "大家都在看这款游戏",
            "立即体验",
            "立即体验新游",
            "商业推广"
        ).forEach { reason ->
            assertTrue(VideoRelateReasonMatcher.matchesStrongModePromotion(setOf(reason)))
        }
        assertFalse(VideoRelateReasonMatcher.matchesStrongModePromotion(setOf("编辑精选")))

        listOf(
            "3万点赞",
            "3.2万点赞",
            "12万+点赞",
            "1234点赞",
            "1,234点赞",
            "１２．５万＋ 点赞",
            "2亿点赞"
        ).forEach { reason ->
            assertTrue(
                "$reason should be recognized as a like-count reason",
                VideoRelateReasonMatcher.matchesLikeCount(setOf(reason))
            )
        }
        listOf(
            "获得3万点赞",
            "3万点赞的视频",
            "3.2点赞",
            "1,23点赞",
            "万点赞",
            "-3点赞",
            "3万播放",
            "点赞3万"
        ).forEach { reason ->
            assertFalse(
                "$reason should not be recognized as a like-count reason",
                VideoRelateReasonMatcher.matchesLikeCount(setOf(reason))
            )
        }
    }

    @Test
    fun `commercial evidence requires an explicit true protocol getter`() {
        val direct = CommercialCard::class.java.getDeclaredMethod("hasCmStock")
        val menu = CommercialCard::class.java.getDeclaredMethod("getThreePoint")
        val feedback = Menu::class.java.getDeclaredMethod("hasFeedback")
        listOf(direct, menu, feedback).forEach { it.isAccessible = true }
        val paths = VideoRelateBooleanEvidenceReader.buildMethodPaths(
            listOf(listOf(direct), listOf(menu, feedback))
        )

        assertEquals(2, paths.size)
        assertFalse(
            VideoRelateBooleanEvidenceReader.hasPositiveEvidence(
                CommercialCard(direct = false, feedback = false),
                paths
            )
        )
        assertTrue(
            VideoRelateBooleanEvidenceReader.hasPositiveEvidence(
                CommercialCard(direct = true, feedback = false),
                paths
            )
        )
        assertTrue(
            VideoRelateBooleanEvidenceReader.hasPositiveEvidence(
                CommercialCard(direct = false, feedback = true),
                paths
            )
        )
    }
}
