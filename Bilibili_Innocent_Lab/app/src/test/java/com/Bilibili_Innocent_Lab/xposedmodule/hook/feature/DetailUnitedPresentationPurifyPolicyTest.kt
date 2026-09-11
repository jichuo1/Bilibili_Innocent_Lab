package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailUnitedPresentationPurifyPolicyTest {

    @Test fun `special tag mapper candidates stay bounded to audited host classes`() {
        assertEquals(
            listOf(
                "com.bilibili.ship.theseus.united.page.intro.module.tags.f",
                "com.bilibili.ship.theseus.united.page.intro.module.tags.j"
            ),
            DetailUnitedPresentationPurifyPolicy.specialTagMapperClasses
        )
    }

    @Test fun `hot badge source requires the exact search from parameter`() {
        assertTrue(
            DetailUnitedPresentationPurifyPolicy.isHotSearchLabelUri(
                "bilibili://search?keyword=OpenAI%20news&from=apphotword_search_huangtiao"
            )
        )
        assertTrue(
            DetailUnitedPresentationPurifyPolicy.isHotSearchLabelUri(
                "bilibili://search?from=apphotword_search_huangtiao&keyword=contains spaces"
            )
        )
        assertFalse(
            DetailUnitedPresentationPurifyPolicy.isHotSearchLabelUri(
                "bilibili://search?keyword=apphotword_search_huangtiao"
            )
        )
        assertFalse(
            DetailUnitedPresentationPurifyPolicy.isHotSearchLabelUri(
                "bilibili://search?from=apphotword_search_huangtiao_extra"
            )
        )
        assertFalse(
            DetailUnitedPresentationPurifyPolicy.isHotSearchLabelUri(
                "https://m.bilibili.com/search?from=apphotword_search_huangtiao"
            )
        )
    }

    /**
     * 标题角标按**类别**判：只要 label 跳的是搜索页，无论运营文案是什么都覆盖。
     *
     * 2026-09-11 实测「热搜」与「活动」是同一套协议结构，只有 `icon_name` 文案和
     * `from` 来源标记不同（`apphotword_search_huangtiao` vs `app_comment_topic_search`）。
     * 一个词一个开关是不可维护的，所以判据收在"跳搜索"这个结构特征上。
     */
    @Test fun `title badge matching is by category so every from variant is covered`() {
        val policy = DetailUnitedPresentationPurifyPolicy
        // 热搜
        assertTrue(policy.isSearchJumpLabelUri(
            "bilibili://search?keyword=OpenAI&from=apphotword_search_huangtiao"))
        // 活动——旧的精确判据漏掉的正是它
        assertTrue(policy.isSearchJumpLabelUri(
            "bilibili://search?keyword=%E6%B4%BB%E5%8A%A8&from=app_comment_topic_search"))
        assertFalse(policy.isHotSearchLabelUri(
            "bilibili://search?keyword=%E6%B4%BB%E5%8A%A8&from=app_comment_topic_search"))
        // 未来任何新来源标记都自动覆盖
        assertTrue(policy.isSearchJumpLabelUri("bilibili://search?from=whatever_comes_next"))
        assertTrue(policy.isSearchJumpLabelUri("bilibili://search"))
    }

    /** 判的是 host，不是子串：正常标识与其它宿主链接都必须放行。 */
    @Test fun `title badge matching never fires on non search destinations`() {
        val policy = DetailUnitedPresentationPurifyPolicy
        // 「互动视频」这类正常徽标跳的不是搜索页
        assertFalse(policy.isSearchJumpLabelUri("bilibili://video/117249805387384"))
        assertFalse(policy.isSearchJumpLabelUri("bilibili://pegasus/channel/v2"))
        // keyword 里出现 search 字样不算
        assertFalse(policy.isSearchJumpLabelUri("bilibili://video/1?keyword=search"))
        // 换 scheme / 换 host 都不算
        assertFalse(policy.isSearchJumpLabelUri("https://m.bilibili.com/search?from=x"))
        assertFalse(policy.isSearchJumpLabelUri("bilibili://searchresult?from=x"))
        assertFalse(policy.isSearchJumpLabelUri(null))
        assertFalse(policy.isSearchJumpLabelUri(""))
    }

    /** 精确判据仍然是类别判据的子集——保留它是为了钉住最初的抓包证据。 */
    @Test fun `the exact hot search predicate is a subset of the category predicate`() {
        val policy = DetailUnitedPresentationPurifyPolicy
        val hot = "bilibili://search?from=apphotword_search_huangtiao"
        assertTrue(policy.isHotSearchLabelUri(hot))
        assertTrue(policy.isSearchJumpLabelUri(hot))
        assertFalse(policy.isHotSearchLabelUri("bilibili://video/1"))
    }

    @Test fun `special topic matcher requires a Bilibili topic detail route`() {
        assertTrue(
            DetailUnitedPresentationPurifyPolicy.isSpecialTopicUri(
                "https://m.bilibili.com/topic-detail?topic_id=1255303&topic_name=AI+IN+ALL"
            )
        )
        assertTrue(
            DetailUnitedPresentationPurifyPolicy.isSpecialTopicUri(
                "https://www.bilibili.com/topic-detail?topic_id=1"
            )
        )
        assertFalse(
            DetailUnitedPresentationPurifyPolicy.isSpecialTopicUri(
                "https://evilbilibili.com/topic-detail?topic_id=1"
            )
        )
        assertFalse(
            DetailUnitedPresentationPurifyPolicy.isSpecialTopicUri(
                "https://m.bilibili.com/search?keyword=topic-detail"
            )
        )
        assertFalse(DetailUnitedPresentationPurifyPolicy.isSpecialTopicUri(null))
    }

    @Test fun `headline cleaner only removes a label with the exact hot search source`() {
        val cleaner = requireNotNull(HeadlineBadgeModelCleaner.resolve(FakeHeadline::class.java))
        val hot = FakeHeadline(
            "normal video title",
            FakeHeadlineLabel(uri = "bilibili://search?from=apphotword_search_huangtiao")
        )
        val cleaned = cleaner.removeHotSearchBadge(hot) as? FakeHeadline
        assertNotNull(cleaned)
        assertEquals("normal video title", cleaned!!.content)
        assertNull(cleaned.label)

        val interactive = FakeHeadline(
            "normal video title",
            FakeHeadlineLabel(uri = "bilibili://video/interactive")
        )
        assertNull(cleaner.removeHotSearchBadge(interactive))
    }

    @Test fun `special topic cleaner retains other cells and their original order`() {
        val cleaner = requireNotNull(SpecialTopicTagsModelCleaner.resolve(FakeTagsData::class.java))
        val normalBefore = FakeCell("normal", "bilibili://search?keyword=topic-detail")
        val topic = FakeCell("AI IN ALL", "https://m.bilibili.com/topic-detail?topic_id=1255303")
        val normalAfter = FakeCell("BGM", "bilibili://music/detail/1")
        val input = FakeTagsData(listOf(normalBefore, topic, normalAfter), refresh = true)

        val result = cleaner.removeSpecialTopicCells(input) as FakeTagsData
        assertEquals(listOf(normalBefore, normalAfter), result.cells)
        assertTrue(result.refresh)
    }

    @Test fun `all topic cells disable refresh so TagsService creates no empty component`() {
        val cleaner = requireNotNull(SpecialTopicTagsModelCleaner.resolve(FakeTagsData::class.java))
        val input = FakeTagsData(
            listOf(
                FakeCell("topic one", "https://m.bilibili.com/topic-detail?topic_id=1"),
                FakeCell("topic two", "https://m.bilibili.com/topic-detail?topic_id=2")
            ),
            refresh = true
        )

        val result = cleaner.removeSpecialTopicCells(input) as FakeTagsData
        assertTrue(result.cells.isEmpty())
        assertFalse(result.refresh)
    }

    @Test fun `special topic cleaner returns the original instance when nothing matches`() {
        val cleaner = requireNotNull(SpecialTopicTagsModelCleaner.resolve(FakeTagsData::class.java))
        val input = FakeTagsData(
            listOf(FakeCell("normal", "bilibili://search?keyword=topic-detail")),
            refresh = true
        )
        assertSame(input, cleaner.removeSpecialTopicCells(input))
    }

    @Test fun `model cleaners fail closed when future models gain an unaccounted field`() {
        assertNull(HeadlineBadgeModelCleaner.resolve(ExpandedFakeHeadline::class.java))
        assertNull(HeadlineBadgeModelCleaner.resolve(HeadlineWithExpandedFakeLabel::class.java))
        assertNull(SpecialTopicTagsModelCleaner.resolve(ExpandedFakeTagsData::class.java))
    }

    private data class FakeHeadlineLabel(
        val type: Int = 0,
        val uri: String,
        val icon: String = "",
        val iconNight: String = "",
        val lottie: String = "",
        val lottieNight: String = "",
        val width: Long = 0L,
        val height: Long = 0L,
        val reportMap: Map<String, String> = emptyMap()
    )
    private data class FakeHeadline(val content: String, val label: FakeHeadlineLabel?)
    private data class ExpandedFakeHeadline(
        val content: String,
        val label: FakeHeadlineLabel?,
        val unaccounted: String
    )
    private data class ExpandedFakeHeadlineLabel(
        val type: Int = 0,
        val uri: String,
        val icon: String = "",
        val iconNight: String = "",
        val lottie: String = "",
        val lottieNight: String = "",
        val width: Long = 0L,
        val height: Long = 0L,
        val reportMap: Map<String, String> = emptyMap(),
        val unaccounted: String = ""
    )
    private data class HeadlineWithExpandedFakeLabel(
        val content: String,
        val label: ExpandedFakeHeadlineLabel?
    )
    private data class FakeCell(val text: String, val jumpUrl: String)
    private data class FakeTagsData(val cells: List<FakeCell>, val refresh: Boolean)
    private data class ExpandedFakeTagsData(
        val cells: List<FakeCell>,
        val refresh: Boolean,
        val unaccounted: String
    )
}
