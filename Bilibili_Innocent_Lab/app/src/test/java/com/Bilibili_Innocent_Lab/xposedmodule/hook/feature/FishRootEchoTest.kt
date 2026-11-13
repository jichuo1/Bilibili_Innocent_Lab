package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FishRootEcho.Root
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FishRootEcho.Video
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FishScorePolicy.Config
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FishScorePolicy.Sensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FishRootEchoTest {

    private val rootText = "一米宽的画布能画出四季变化，镜头里的细节太绝了"

    private fun points(root: String?, reply: String) =
        FishRootEcho.points(Root.of(root), TextNormalizer.forMatching(reply))

    private fun plan(sensitivity: Sensitivity = Sensitivity.STANDARD, protect: Boolean = true, strongMode: Boolean = false) =
        requireNotNull(Config(true, sensitivity.id, protectHighLevel = protect, strongMode = strongMode).plan())

    @Test fun `peer echo shares the existing cap and weak signals need independent evidence`() {
        val message = "一米宽的画布能画出四季变化真的太有意思了"
        val video = { FishRootEcho.Video(rootText, null) }
        val peer = { FishScorePolicy.Evidence(peerPoints = 12) }
        val both = { FishScorePolicy.Evidence(peerPoints = 12, accountPoints = 12) }

        assertEquals(48, FishScorePolicy.score(plan(), "深海大鱼君", 2, message, extra = peer))
        assertEquals(60, FishScorePolicy.score(plan(), "深海大鱼君", 2, message, video = video, extra = peer))
        assertEquals(60, FishScorePolicy.score(plan(), "深海大鱼君", 2, message, extra = both))
        assertEquals(120, FishScorePolicy.score(plan(strongMode = true), "深海大鱼君", 2, message,
            video = video, extra = peer))
        assertFalse(FishScorePolicy.shouldRemove(plan(Sensitivity.STRICT), "路人甲", null, 2, message, video = video,
            extra = { error("low level alone must not consult account or peer evidence") }))
        assertFalse(FishScorePolicy.shouldRemove(plan(), "小蓝的大肥鱼", null, 5, message,
            extra = { error("high level protection must win before the new evidence") }))
    }

    @Test
    fun `a verbatim retelling of the root scores and an unrelated reply does not`() {
        // 13 字原样复述 + 一半以上由主评论片段拼成。
        assertEquals(24, points(rootText, "一米宽的画布能画出四季变化真的太有意思了"))
        assertEquals(0, points(rootText, "我昨天也去看了展览，人太多了根本挤不进去"))
        // 只复述了一小段：5～6 字 +6，不触发"一半以上"。
        assertEquals(6, points(rootText, "我也想知道镜头里的细节是怎么拍出来的呢"))
    }

    @Test
    fun `generic windows and single character runs never count`() {
        assertTrue(FishRootEcho.GENERIC_TEXTS.all { it.length == 4 && it == it.lowercase() })
        assertEquals(0, points("这个视频真的太好看了，我看了三遍", "真的太好看了，这个视频我也很喜欢"))
        assertEquals(0, points("哈哈哈哈哈哈哈哈哈哈", "哈哈哈哈哈哈哈哈哈哈哈哈"))
        // 纯 ASCII 窗口不算：英文单词太容易撞上。
        assertEquals(0, points("this is a really great video", "this is a really great video indeed"))
    }

    @Test
    fun `short texts mentions reply prefixes and emote placeholders are not compared`() {
        assertEquals(0, points(rootText, "一米宽的画布"))
        assertEquals(0, points("太绝了太绝了", "太绝了太绝了太绝了太绝了"))
        // 前缀、@ 与表情占位不是说话人写的内容：去掉后只剩 6 个字，不够比。
        assertEquals(0, points(rootText, "回复 @一米宽的画布能画出四季变化 :[doge]一米宽的画布"))
        assertEquals(24, points(rootText, "回复 @张三 :一米宽的画布能画出四季变化真的太有意思了"))
        assertEquals(0, points(null, "一米宽的画布能画出四季变化真的太有意思了"))
        assertEquals(0, points("   ", "一米宽的画布能画出四季变化真的太有意思了"))
    }

    @Test
    fun `windows do not span punctuation`() {
        // 两半各只有 3 个字相邻，中间被标点隔开：不能拼成一个窗口。
        assertEquals(0, points("画布能画，四季变化，镜头细节都太绝了吧", "画布能，画四季，变化镜，头细节，都太绝了"))
    }

    @Test
    fun `a retelling only tips a borderline account over the line and never removes alone`() {
        val echo = "一米宽的画布能画出四季变化真的太有意思了"
        val root = Root.of(rootText, "画家本人")
        // 大鱼 +22，Lv2 +14 = 36：标准档差 24 分，复述 +24 刚好到线。
        assertEquals(36, FishScorePolicy.score(plan(), "深海大鱼君", 2, echo))
        assertEquals(60, FishScorePolicy.score(plan(), "深海大鱼君", 2, echo, root))
        assertTrue(FishScorePolicy.shouldRemove(plan(), "深海大鱼君", null, 2, echo, root))
        assertFalse(FishScorePolicy.shouldRemove(plan(), "深海大鱼君", null, 2, echo))
        // 没有昵称特征的新号：14 + 24 也到不了 45，复述根本不会去算；标准与严格都不删。
        assertEquals(14, FishScorePolicy.score(plan(), "路人甲", 2, echo, root))
        assertFalse(FishScorePolicy.shouldRemove(plan(Sensitivity.STRICT), "路人甲", null, 2, echo, root))
        // 老号、放行名单照旧先于计分：Lv5 的"大肥鱼"靠昵称 55 + 复述 24 本会过线。
        assertFalse(FishScorePolicy.shouldRemove(plan(), "小蓝的大肥鱼", null, 5, echo, root))
        assertTrue(FishScorePolicy.shouldRemove(plan(protect = false), "小蓝的大肥鱼", null, 5, echo, root))
        val allow = requireNotNull(Config(true, rawWhitelist = "深海大鱼君").plan())
        assertFalse(FishScorePolicy.shouldRemove(allow, "深海大鱼君", null, 2, echo, root))
    }

    @Test
    fun `the thread owner answering in their own thread is never compared`() {
        val echo = "一米宽的画布能画出四季变化真的太有意思了"
        assertTrue(FishScorePolicy.shouldRemove(plan(), "深海大鱼君", null, 2, echo, Root.of(rootText, "别人")))
        assertFalse(FishScorePolicy.shouldRemove(plan(), "深海大鱼君", null, 2, echo, Root.of(rootText, "深海大鱼君")))
        // 全角、大小写写法不同也算同一个名字（同一套归一化）。
        assertFalse(FishScorePolicy.shouldRemove(plan(), "ＤＥＥＰ大鱼", null, 2, echo, Root.of(rootText, "deep大鱼")))
    }

    @Test
    fun `video title or description can tip a borderline top level comment or a reply over the line`() {
        val echo = "一米宽的画布能画出四季变化真的太有意思了"
        val title = Video(rootText, "我昨天也去看了展览，人太多了根本挤不进去")
        val description = Video(null, rootText)
        assertEquals(60, FishScorePolicy.score(plan(), "深海大鱼君", 2, echo, video = { title }))
        assertEquals(60, FishScorePolicy.score(plan(), "深海大鱼君", 2, echo, video = { description }))
        val ownThread = Root.of(rootText, "深海大鱼君")
        assertTrue(FishScorePolicy.shouldRemove(plan(), "深海大鱼君", null, 2, echo, ownThread, video = { description }))
        assertFalse(FishScorePolicy.shouldRemove(plan(), "深海大鱼君", null, 2, echo, video = { Video(null, null) }))
        assertFalse(FishScorePolicy.shouldRemove(plan(), "深海大鱼君", null, 2, echo, video = { error("unreadable metadata") }))
    }

    @Test
    fun `only the strongest echo counts across the root title and description`() {
        val echo = "一米宽的画布能画出四季变化真的太有意思了"
        val root = Root.of("一米宽的画布，在阴天")
        val video = Video("一米宽的画布能，在阴天", "四季变化真的，颜料不一样")
        assertEquals(48, FishScorePolicy.score(plan(), "深海大鱼君", 2, echo, root, video = { video }))
        // 短标题、常见单词和仅 3 字的技术名词不足以加分。
        assertEquals(36, FishScorePolicy.score(plan(), "深海大鱼君", 2, echo, video = { Video("一米宽的画布能", null) }))
        assertEquals(0, Video("24帧怎么拍更好看", null).points(TextNormalizer.forMatching("一直在研究24帧的电影效果")))
    }

    @Test
    fun `video metadata is only read for unprotected accounts within reach of the threshold`() {
        val echo = "一米宽的画布能画出四季变化真的太有意思了"
        var reads = 0
        val video = Video(rootText, rootText)
        val readVideo = { reads++; video }
        val allow = requireNotNull(Config(true, rawWhitelist = "深海大鱼君").plan())
        assertFalse(FishScorePolicy.shouldRemove(plan(Sensitivity.STRICT), "路人甲", null, 2, echo, video = readVideo))
        assertFalse(FishScorePolicy.shouldRemove(plan(), "小蓝的大肥鱼", null, 5, echo, video = readVideo))
        assertFalse(FishScorePolicy.shouldRemove(allow, "深海大鱼君", null, 2, echo, video = readVideo))
        assertTrue(FishScorePolicy.shouldRemove(plan(), "小蓝的大肥鱼", null, 2, echo, video = readVideo))
        assertTrue(FishScorePolicy.shouldRemove(plan(), "深海大鱼君", null, 2, echo, Root.of(rootText), readVideo))
        assertEquals(0, reads)
        assertTrue(FishScorePolicy.shouldRemove(plan(), "深海大鱼君", null, 2, echo, video = readVideo))
        assertEquals(1, reads)
    }

    @Test
    fun `the root is read only when the score lands within reach of the threshold`() {
        var reads = 0
        val root = Root({ reads++; rootText }, { reads++; "别人" })
        val echo = "一米宽的画布能画出四季变化真的太有意思了"
        // 离线分远低于阈值：复述最多 +24 也到不了，主评论一次都不读。
        assertFalse(FishScorePolicy.shouldRemove(plan(), "路人甲", null, 4, echo, root))
        // 已经够删：不再需要复述。
        assertTrue(FishScorePolicy.shouldRemove(plan(), "小蓝的大肥鱼", null, 2, echo, root))
        assertEquals(0, reads)
        // 落进区间才读，而且只读一次。
        FishScorePolicy.score(plan(), "深海大鱼君", 2, echo, root)
        FishScorePolicy.score(plan(), "深海大鱼君", 2, echo, root)
        assertEquals(2, reads)
    }

    @Test
    fun `an unreadable root is skipped and a throwing reader fails open`() {
        val echo = "一米宽的画布能画出四季变化真的太有意思了"
        assertFalse(FishScorePolicy.shouldRemove(plan(), "深海大鱼君", null, 2, echo, Root.of(null)))
        assertFalse(FishScorePolicy.shouldRemove(plan(), "深海大鱼君", null, 2, echo, Root({ error("boom") }, { error("boom") })))
        assertFalse(FishScorePolicy.shouldRemove(plan(), "深海大鱼君", null, 2, null, Root.of(rootText)))
    }

    @Test(timeout = 10_000)
    fun `long roots and replies stay fast`() {
        val long = "一米宽的画布能画出四季变化，".repeat(80)
        val root = Root.of(long)
        val reply = TextNormalizer.forMatching("一米宽的画布能画出四季变化".repeat(80))
        repeat(50) { FishRootEcho.points(root, reply) }
        assertEquals(24, FishRootEcho.points(root, reply))
    }
}
