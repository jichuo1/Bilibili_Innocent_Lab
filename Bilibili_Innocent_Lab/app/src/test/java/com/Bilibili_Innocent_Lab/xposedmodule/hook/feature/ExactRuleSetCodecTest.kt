package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FishPeerEcho.Sample
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FishRootEcho.Root
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FishRootEcho.Video
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FishScorePolicy.Config
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FishScorePolicy.Sensitivity
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactRuleSetCodecTest {

    fun `parses the same separators the rule editors already accept`() {
        assertEquals(setOf("影视飓风", "科技"), ExactRuleSetCodec.parse("影视飓风, 科技"))
        assertEquals(setOf("a", "b"), ExactRuleSetCodec.parse("A；b"))
        assertEquals(emptySet<String>(), ExactRuleSetCodec.parse(null))
        assertEquals(emptySet<String>(), ExactRuleSetCodec.parse("  \n "))
    }

    fun `matching is whole string so substrings never leak through`() {
        val blocked = ExactRuleSetCodec.parse("科技,影视")
        assertTrue(ExactRuleSetCodec.matches(blocked, "科技"))
        // contains 语义会让"科技"命中"科技美学"；整串相等不会。
        assertFalse(ExactRuleSetCodec.matches(blocked, "科技美学"))
        assertFalse(ExactRuleSetCodec.matches(blocked, "影视飓风"))
    }

    fun `matching ignores case and surrounding whitespace`() {
        val blocked = ExactRuleSetCodec.parse("TechLead")
        assertTrue(ExactRuleSetCodec.matches(blocked, "techlead"))
        assertTrue(ExactRuleSetCodec.matches(blocked, "  TECHLEAD  "))
    }

    fun `any of the supplied values may match so name and mid share one list`() {
        val blocked = ExactRuleSetCodec.parse("3546963345672636")
        // 作者链会同时给出 UP 名与 mid（mid 按十进制字符串出口）。
        assertTrue(ExactRuleSetCodec.matches(blocked, "心理日记馆", "3546963345672636"))
        assertFalse(ExactRuleSetCodec.matches(blocked, "心理日记馆", "354696334567263"))
    }

    fun `unreadable values are always allowed through`() {
        val blocked = ExactRuleSetCodec.parse("科技")
        assertFalse(ExactRuleSetCodec.matches(blocked, null))
        assertFalse(ExactRuleSetCodec.matches(blocked, "  "))
        assertFalse(ExactRuleSetCodec.matches(blocked))
        assertFalse(ExactRuleSetCodec.matches(emptySet(), "科技"))
    }

    fun `encode normalizes deduplicates and round trips`() {
        assertEquals("科技,影视", ExactRuleSetCodec.encode(listOf(" 科技 ", "影视", "科技")))
        assertEquals("a", ExactRuleSetCodec.encode(listOf("A", "a")))
        assertEquals(
            setOf("科技", "影视"),
            ExactRuleSetCodec.parse(ExactRuleSetCodec.encode(setOf("科技", "影视")))
        )
    }

    fun `panel writes append without duplicating an existing entry`() {
        assertEquals("科技,影视", ExactRuleSetCodec.add("科技", "影视"))
        assertEquals("科技", ExactRuleSetCodec.add("科技", "科技"))
        assertEquals("科技", ExactRuleSetCodec.add("科技", " 科技 "))
        assertEquals("科技", ExactRuleSetCodec.add(null, "科技"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (ExactRuleSetCodecTest) -> Unit) {
            val s = ExactRuleSetCodecTest()
            block(s)
        }
        runIsolated { s -> s.`parses the same separators the rule editors already accept`() }
        runIsolated { s -> s.`matching is whole string so substrings never leak through`() }
        runIsolated { s -> s.`matching ignores case and surrounding whitespace`() }
        runIsolated { s -> s.`any of the supplied values may match so name and mid share one list`() }
        runIsolated { s -> s.`unreadable values are always allowed through`() }
        runIsolated { s -> s.`encode normalizes deduplicates and round trips`() }
        runIsolated { s -> s.`panel writes append without duplicating an existing entry`() }
    
        ExternalBrowserPolicyCases().verifyAllCases()
        FeedbackContentSignatureCases().composeFlagsAreFoundForEachVerifiedSignature()
        FilteredListMemoCases().verifyAllCases()
        FishAccountMetadataCases().verifyAllCases()
        FishDynamicStyleCases().verifyAllCases()
        FishPeerEchoCases().verifyAllCases()
        FishRootEchoCases().verifyAllCases()
    }
}

private class ExternalBrowserPolicyCases {

    private val webActivity = "com.bilibili.lib.biliweb.MWebActivity"

    fun `only the in-app web activity is redirected`() {
        assertTrue(
            ExternalBrowserPolicy.shouldOpenExternally(webActivity, "https", "example.com")
        )
        assertFalse(
            ExternalBrowserPolicy.shouldOpenExternally(
                "com.bilibili.video.videodetail.VideoDetailsActivity",
                "https",
                "example.com"
            )
        )
        assertFalse(ExternalBrowserPolicy.shouldOpenExternally(null, "https", "example.com"))
    }

    fun `bilibili sites and payment gateways stay in the app`() {
        listOf(
            "www.bilibili.com",
            "passport.bilibili.com",
            "b23.tv",
            "m.bilibili.tv",
            "i0.hdslb.com",
            "mclient.alipay.com",
            "wx.tenpay.com",
            "cashier.95516.com"
        ).forEach { host ->
            assertFalse(host, ExternalBrowserPolicy.shouldOpenExternally(webActivity, "https", host))
        }
    }

    fun `look-alike domains are not treated as bilibili`() {
        assertTrue(
            ExternalBrowserPolicy.shouldOpenExternally(webActivity, "https", "notbilibili.com")
        )
        assertTrue(
            ExternalBrowserPolicy.shouldOpenExternally(webActivity, "https", "bilibili.com.evil.cn")
        )
    }

    fun `non web schemes and empty hosts are never redirected`() {
        assertFalse(ExternalBrowserPolicy.shouldOpenExternally(webActivity, "bilibili", "video"))
        assertFalse(ExternalBrowserPolicy.shouldOpenExternally(webActivity, "file", "example.com"))
        assertFalse(ExternalBrowserPolicy.shouldOpenExternally(webActivity, null, "example.com"))
        assertFalse(ExternalBrowserPolicy.shouldOpenExternally(webActivity, "https", null))
        assertFalse(ExternalBrowserPolicy.shouldOpenExternally(webActivity, "https", ""))
    }

    fun `host matching ignores case port and trailing dot`() {
        assertFalse(
            ExternalBrowserPolicy.shouldOpenExternally(webActivity, "HTTPS", "WWW.BILIBILI.COM")
        )
        assertFalse(
            ExternalBrowserPolicy.shouldOpenExternally(webActivity, "https", "www.bilibili.com:443")
        )
        assertFalse(
            ExternalBrowserPolicy.shouldOpenExternally(webActivity, "https", "www.bilibili.com.")
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ExternalBrowserPolicyCases) -> Unit) {
            val s = ExternalBrowserPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`only the in-app web activity is redirected`() }
        runIsolated { s -> s.`bilibili sites and payment gateways stay in the app`() }
        runIsolated { s -> s.`look-alike domains are not treated as bilibili`() }
        runIsolated { s -> s.`non web schemes and empty hosts are never redirected`() }
        runIsolated { s -> s.`host matching ignores case port and trailing dot`() }
    }
}

private class FeedbackContentSignatureCases {
    fun composeFlagsAreFoundForEachVerifiedSignature() {
        val tail = listOf("kotlin.jvm.functions.Function1", "androidx.compose.runtime.Composer", "int", "int")
        val first = listOf("java.util.List")
        val head = first + "kntr.app.pegasus.feedbackdialog.model.FeedbackHead"
        assertEquals(3, FeedbackContentSignature.changedIndex(first + tail))
        assertEquals(4, FeedbackContentSignature.changedIndex(first + "boolean" + tail))
        assertEquals(5, FeedbackContentSignature.changedIndex(head + "boolean" + tail))
        assertNull(FeedbackContentSignature.changedIndex(head + tail))
        assertNull(FeedbackContentSignature.changedIndex(listOf("java.util.Map") + tail))
        assertNull(FeedbackContentSignature.changedIndex(head + tail + "int"))
    }
}

private class FilteredListMemoCases {
    private var now = 1_000L
    private val memo = FilteredListMemo { now }

    fun sameSourceAndRulesReturnTheRememberedInstance() {
        val source = mutableListOf<Any?>("a", "b")
        val result = listOf("a")
        val rules = arrayOf<Any?>(setOf(1L), null)
        memo.remember(source, rules, result)
        assertSame(result, memo.reuse(source, arrayOf(rules[0], rules[1]))?.result)
    }

    fun anEqualButDifferentSourceListIsNotAHit() {
        memo.remember(mutableListOf<Any?>("a"), emptyArray(), emptyList<Any>())
        assertNull(memo.reuse(mutableListOf<Any?>("a"), emptyArray()))
    }

    fun aSourceThatChangedLengthIsRecomputedAndForgotten() {
        val source = mutableListOf<Any?>("a")
        memo.remember(source, emptyArray(), emptyList<Any>())
        source += "b"
        assertNull(memo.reuse(source, emptyArray()))
        source.removeAt(1)
        // 旧记录已在上一次未命中时丢弃，长度改回来也不会复活过期的结果。
        assertNull(memo.reuse(source, emptyArray()))
    }

    fun aReplacedRuleSnapshotInvalidatesEvenWhenItsContentIsEqual() {
        val source = listOf("a")
        memo.remember(source, arrayOf(setOf(1L)), emptyList<Any>())
        assertNull(memo.reuse(source, arrayOf(setOf(1L))))
    }

    fun entriesExpireAfterTheTimeBoundAndAClockGoingBackwardsIsNotTrusted() {
        val source = listOf("a")
        memo.remember(source, emptyArray(), emptyList<Any>())
        now += FilteredListMemo.MAX_AGE_MS
        assertNull(memo.reuse(source, emptyArray()))
        memo.remember(source, emptyArray(), emptyList<Any>())
        now -= 1
        assertNull(memo.reuse(source, emptyArray()))
    }

    fun capacityIsBoundedAndTheOldestEntryIsEvictedFirst() {
        val sources = (0..FilteredListMemo.CAPACITY).map { listOf(it) }
        sources.forEach { memo.remember(it, emptyArray(), it) }
        assertEquals(FilteredListMemo.CAPACITY, memo.size)
        assertNull(memo.reuse(sources.first(), emptyArray()))
        assertSame(sources.last(), memo.reuse(sources.last(), emptyArray())?.result)
    }

    fun rememberingTheSameSourceAgainReplacesItsEntry() {
        val source = listOf("a")
        memo.remember(source, emptyArray(), listOf(1))
        val newer = listOf(2)
        memo.remember(source, emptyArray(), newer)
        assertEquals(1, memo.size)
        assertSame(newer, memo.reuse(source, emptyArray())?.result)
    }

    fun anUnsettledResultIsReturnedAsIsUntilItIsSettledUnderTheSameSource() {
        val source = listOf("a", "b")
        val filtered = listOf("a")
        memo.remember(source, emptyArray(), filtered, settled = false)
        val pending = requireNotNull(memo.reuse(source, emptyArray()))
        assertSame(filtered, pending.result)
        assertFalse(pending.settled)
        val arranged = listOf("a")
        memo.remember(source, emptyArray(), arranged)
        val settled = requireNotNull(memo.reuse(source, emptyArray()))
        assertSame(arranged, settled.result)
        assertTrue(settled.settled)
        assertEquals(1, memo.size)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FilteredListMemoCases) -> Unit) {
            val s = FilteredListMemoCases()
            block(s)
        }
        runIsolated { s -> s.sameSourceAndRulesReturnTheRememberedInstance() }
        runIsolated { s -> s.anEqualButDifferentSourceListIsNotAHit() }
        runIsolated { s -> s.aSourceThatChangedLengthIsRecomputedAndForgotten() }
        runIsolated { s -> s.aReplacedRuleSnapshotInvalidatesEvenWhenItsContentIsEqual() }
        runIsolated { s -> s.entriesExpireAfterTheTimeBoundAndAClockGoingBackwardsIsNotTrusted() }
        runIsolated { s -> s.capacityIsBoundedAndTheOldestEntryIsEvictedFirst() }
        runIsolated { s -> s.rememberingTheSameSourceAgainReplacesItsEntry() }
        runIsolated { s -> s.anUnsettledResultIsReturnedAsIsUntilItIsSettledUnderTheSameSource() }
    }
}

private class FishAccountMetadataCases {
    fun `only explicit matching public profile counts earn a bonus`() {
        val accounts = FishAccountMetadata()
        accounts.observeSpaceResponse(1, space("""{"mid":1,"attention":0,"fans":0}"""), 0)
        accounts.observeSpaceResponse(2, space("""{"mid":9,"attention":0,"fans":0,"likes":{"like_num":0}}"""), 0)
        accounts.observeSpaceResponse(3, space("""{"mid":3,"attention":0,"fans":0,"likes":{}}"""), 0)
        accounts.observeSpaceResponse(4, space("""{"mid":4,"attention":0,"fans":0,"likes":{"like_num":0}}"""), 0)
        accounts.observeSpaceResponse(5, space("""{"mid":"5","attention":0,"fans":5,"user_like":{"like_num":20}}"""), 0)
        assertEquals(mapOf(4L to 8, 5L to 6), accounts.snapshot)

        accounts.observeSpaceResponse(4, space("""{"mid":4,"attention":1}"""), 0)
        accounts.observeSpaceResponse(
            5, space("""{"mid":5}""", ""","setting":{"disable_following":true}"""), 0
        )
        assertTrue(accounts.snapshot.isEmpty())
    }

    fun `score snapshots stay bounded and expire independently of future requests`() {
        var now = 0L
        val accounts = FishAccountMetadata(nowNanos = { now })
        for (uid in 1L..65L) {
            accounts.observeSpaceResponse(uid, space("""{"mid":$uid,"attention":0,"fans":0,"likes":{"like_num":0}}"""), 0)
        }
        val snapshot = accounts.snapshot
        assertEquals(64, snapshot.size)
        assertNull(snapshot[1L])
        accounts.observeSpaceResponse(65, space("""{"mid":65,"attention":0}"""), 0)
        assertSame(snapshot, accounts.snapshot)
        now += TimeUnit.MINUTES.toNanos(31)
        assertTrue(accounts.snapshot.isEmpty())
        assertEquals(8, snapshot[65L])
    }

    fun `stream forwards each read and only reports a complete sequential body`() {
        val bytes = "abc中文".encodeToByteArray()
        var completed: ByteArray? = null
        val stream = FishAccountMetadata.ObservedStream(ByteArrayInputStream(bytes)) { completed = it }
        assertEquals(bytes[0].toInt(), stream.read())
        assertNull(completed)
        assertArrayEquals(bytes.copyOfRange(1, bytes.size), stream.readBytes())
        assertArrayEquals(bytes, completed)

        completed = null
        val reordered = FishAccountMetadata.ObservedStream(ByteArrayInputStream(bytes)) { completed = it }
        reordered.mark(bytes.size)
        reordered.read()
        reordered.reset()
        assertArrayEquals(bytes, reordered.readBytes())
        assertNull(completed)

        val oversized = ByteArray(256 * 1024 + 1)
        val large = FishAccountMetadata.ObservedStream(ByteArrayInputStream(oversized)) { completed = it }
        assertArrayEquals(oversized, large.readBytes())
        assertNull(completed)
    }

    private fun space(card: String, extra: String = "") = """{"code":0,"data":{"card":$card$extra}}"""

    fun verifyAllCases() {
        fun runIsolated(block: (FishAccountMetadataCases) -> Unit) {
            val s = FishAccountMetadataCases()
            block(s)
        }
        runIsolated { s -> s.`only explicit matching public profile counts earn a bonus`() }
        runIsolated { s -> s.`score snapshots stay bounded and expire independently of future requests`() }
        runIsolated { s -> s.`stream forwards each read and only reports a complete sequential body`() }
    }
}

private class FishDynamicStyleCases {
    fun `a stylized delivery with duration and narration has a stable template`() {
        val morning = FishDynamicStyle.analyze("【哦花哇哇哟！早晨视频递上~】5分47秒，视频里先对比三个不同的模型。")
        val repeated = FishDynamicStyle.analyze("【哦花哇哇哟!早晨视频递上】4分12秒，片中开始演示音效的变化。")
        assertTrue(morning.composedIntro)
        assertTrue(repeated.composedIntro)
        assertNotNull(morning.introFingerprint)
        assertEquals(morning.introFingerprint, repeated.introFingerprint)
        assertFalse(morning.narratedTimeline)
    }

    fun `narration needs multiple increasing specific timestamps`() {
        val summary = "我刚刷到这段视频。0:23 镜头展示了初版效果。1分14秒，画面切到性能对比。2:08 最后介绍了实现细节。"
        val evidence = FishDynamicStyle.analyze(summary)
        assertTrue(evidence.narratedTimeline)
        assertTrue(evidence.browsingIntro)
        assertFalse(FishDynamicStyle.analyze("1:32 这段我看了好几遍，画面真的漂亮。").narratedTimeline)
        assertFalse(FishDynamicStyle.analyze("0:10、1:32、2:45。").narratedTimeline)
        assertFalse(FishDynamicStyle.analyze("2:08 展示初版结果。1:14 画面切到性能对比。0:23 开始介绍实现细节。").narratedTimeline)
    }

    fun `casual browsing headings and a duration do not establish a style`() {
        val casual = FishDynamicStyle.analyze("我刷到这个视频，好看！才5分47秒。")
        assertFalse(casual.browsingIntro)
        assertFalse(casual.composedIntro)
        assertNull(casual.introFingerprint)
        assertFalse(FishDynamicStyle.analyze("【更新说明】5分47秒，片中开始演示音效的变化。").composedIntro)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FishDynamicStyleCases) -> Unit) {
            val s = FishDynamicStyleCases()
            block(s)
        }
        runIsolated { s -> s.`a stylized delivery with duration and narration has a stable template`() }
        runIsolated { s -> s.`narration needs multiple increasing specific timestamps`() }
        runIsolated { s -> s.`casual browsing headings and a duration do not establish a style`() }
    }
}

private class FishPeerEchoCases {
    private val first = Sample(1L, 101L, 11L, "我见过三次不说帧率就给24帧的，确实是这套。")
    private val second = Sample(2L, 102L, 12L, "三道题都没提帧率，三次全给24帧，规格这栏空着它就真不替你想。")
    private val third = Sample(3L, 103L, 13L, "没指定帧速率却默认生成二十四帧。")
    private val fourth = Sample(4L, 104L, 14L, "没有设置 fps，仍然直接输出 24 fps。")

    fun `three independent threads and authors repeat the same structured fact across pages`() {
        val echo = FishPeerEcho()
        echo.observe(42L, listOf(first, second))
        assertTrue(echo.snapshot.isEmpty())
        echo.observe(43L, listOf(third))
        assertTrue(echo.snapshot.isEmpty())

        echo.observe(42L, listOf(third))
        val initial = echo.snapshot
        assertEquals(mapOf(1L to 8, 2L to 8, 3L to 8), initial[42L])
        echo.observe(42L, listOf(first, second, third))
        assertSame(initial, echo.snapshot)
        echo.observe(42L, listOf(fourth))
        assertEquals(mapOf(1L to 12, 2L to 12, 3L to 12, 4L to 12), echo.snapshot[42L])
        assertEquals(mapOf(1L to 8, 2L to 8, 3L to 8), initial[42L])
    }

    fun `keywords ordinary measurements quotations and copied memes never supply the relation`() {
        val echo = FishPeerEcho()
        echo.observe(42L, listOf(
            first, second,
            third.copy(message = "24帧是动画常见的帧率，电影也经常是 24 fps。"),
            fourth.copy(message = "笑死，没提帧率这波真是 24 帧。"),
            Sample(5L, 105L, 15L, "> 没有提帧率就给24帧\n我觉得应该再确认。"),
            Sample(6L, 106L, 16L, "没提帧率，三次全给60帧。")
        ))
        assertTrue(echo.snapshot.isEmpty())

        echo.observe(43L, listOf(first, second.copy(message = first.message), third.copy(message = first.message)))
        assertTrue(echo.snapshot.isEmpty())
    }

    fun `repeat IDs unknown identity and entangled authors and roots do not create extra votes`() {
        val echo = FishPeerEcho()
        echo.observe(42L, listOf(
            first, second.copy(mid = first.mid), third.copy(mid = first.mid),
            fourth.copy(root = first.root), Sample(5L, 105L, first.root, second.message),
            third.copy(rpid = 1L), Sample(6L, 0L, 17L, third.message)
        ))
        assertTrue(echo.snapshot.isEmpty())
    }

    fun `a comment only scores when the independent witness set can include it`() {
        val echo = FishPeerEcho()
        val conflictsWithTwoWitnesses = fourth.copy(mid = first.mid, root = second.root)
        echo.observe(42L, listOf(first, second, third, conflictsWithTwoWitnesses))
        assertEquals(mapOf(1L to 8, 2L to 8, 3L to 8), echo.snapshot[42L])
    }

    fun `other parameters use the same rules and expired evidence is replaced`() {
        var now = 0L
        val echo = FishPeerEcho { now }
        echo.observe(42L, listOf(
            first.copy(message = "没说功耗就给30w。"),
            second.copy(message = "未指定功率却默认设为30瓦。"),
            third.copy(message = "没设置功率，三次都输出30瓦。")
        ))
        assertEquals(mapOf(1L to 8, 2L to 8, 3L to 8), echo.snapshot[42L])

        now += 15 * 60 * 1_000L
        echo.observe(42L, listOf(first, second))
        assertTrue(echo.snapshot.isEmpty())
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FishPeerEchoCases) -> Unit) {
            val s = FishPeerEchoCases()
            block(s)
        }
        runIsolated { s -> s.`three independent threads and authors repeat the same structured fact across pages`() }
        runIsolated { s -> s.`keywords ordinary measurements quotations and copied memes never supply the relation`() }
        runIsolated { s -> s.`repeat IDs unknown identity and entangled authors and roots do not create extra votes`() }
        runIsolated { s -> s.`a comment only scores when the independent witness set can include it`() }
        runIsolated { s -> s.`other parameters use the same rules and expired evidence is replaced`() }
    }
}

private class FishRootEchoCases {

    private val rootText = "一米宽的画布能画出四季变化，镜头里的细节太绝了"

    private fun points(root: String?, reply: String) =
        FishRootEcho.points(Root.of(root), TextNormalizer.forMatching(reply))

    private fun plan(sensitivity: Sensitivity = Sensitivity.STANDARD, protect: Boolean = true, strongMode: Boolean = false) =
        requireNotNull(Config(true, sensitivity.id, protectHighLevel = protect, strongMode = strongMode).plan())

    fun `peer echo shares the existing cap and weak signals need independent evidence`() {
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

    fun `a verbatim retelling of the root scores and an unrelated reply does not`() {
        // 13 字原样复述 + 一半以上由主评论片段拼成。
        assertEquals(24, points(rootText, "一米宽的画布能画出四季变化真的太有意思了"))
        assertEquals(0, points(rootText, "我昨天也去看了展览，人太多了根本挤不进去"))
        // 只复述了一小段：5～6 字 +6，不触发"一半以上"。
        assertEquals(6, points(rootText, "我也想知道镜头里的细节是怎么拍出来的呢"))
    }

    fun `generic windows and single character runs never count`() {
        assertTrue(FishRootEcho.GENERIC_TEXTS.all { it.length == 4 && it == it.lowercase() })
        assertEquals(0, points("这个视频真的太好看了，我看了三遍", "真的太好看了，这个视频我也很喜欢"))
        assertEquals(0, points("哈哈哈哈哈哈哈哈哈哈", "哈哈哈哈哈哈哈哈哈哈哈哈"))
        // 纯 ASCII 窗口不算：英文单词太容易撞上。
        assertEquals(0, points("this is a really great video", "this is a really great video indeed"))
    }

    fun `short texts mentions reply prefixes and emote placeholders are not compared`() {
        assertEquals(0, points(rootText, "一米宽的画布"))
        assertEquals(0, points("太绝了太绝了", "太绝了太绝了太绝了太绝了"))
        // 前缀、@ 与表情占位不是说话人写的内容：去掉后只剩 6 个字，不够比。
        assertEquals(0, points(rootText, "回复 @一米宽的画布能画出四季变化 :[doge]一米宽的画布"))
        assertEquals(24, points(rootText, "回复 @张三 :一米宽的画布能画出四季变化真的太有意思了"))
        assertEquals(0, points(null, "一米宽的画布能画出四季变化真的太有意思了"))
        assertEquals(0, points("   ", "一米宽的画布能画出四季变化真的太有意思了"))
    }

    fun `windows do not span punctuation`() {
        // 两半各只有 3 个字相邻，中间被标点隔开：不能拼成一个窗口。
        assertEquals(0, points("画布能画，四季变化，镜头细节都太绝了吧", "画布能，画四季，变化镜，头细节，都太绝了"))
    }

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

    fun `the thread owner answering in their own thread is never compared`() {
        val echo = "一米宽的画布能画出四季变化真的太有意思了"
        assertTrue(FishScorePolicy.shouldRemove(plan(), "深海大鱼君", null, 2, echo, Root.of(rootText, "别人")))
        assertFalse(FishScorePolicy.shouldRemove(plan(), "深海大鱼君", null, 2, echo, Root.of(rootText, "深海大鱼君")))
        // 全角、大小写写法不同也算同一个名字（同一套归一化）。
        assertFalse(FishScorePolicy.shouldRemove(plan(), "ＤＥＥＰ大鱼", null, 2, echo, Root.of(rootText, "deep大鱼")))
    }

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

    fun `only the strongest echo counts across the root title and description`() {
        val echo = "一米宽的画布能画出四季变化真的太有意思了"
        val root = Root.of("一米宽的画布，在阴天")
        val video = Video("一米宽的画布能，在阴天", "四季变化真的，颜料不一样")
        assertEquals(48, FishScorePolicy.score(plan(), "深海大鱼君", 2, echo, root, video = { video }))
        // 短标题、常见单词和仅 3 字的技术名词不足以加分。
        assertEquals(36, FishScorePolicy.score(plan(), "深海大鱼君", 2, echo, video = { Video("一米宽的画布能", null) }))
        assertEquals(0, Video("24帧怎么拍更好看", null).points(TextNormalizer.forMatching("一直在研究24帧的电影效果")))
    }

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

    fun `an unreadable root is skipped and a throwing reader fails open`() {
        val echo = "一米宽的画布能画出四季变化真的太有意思了"
        assertFalse(FishScorePolicy.shouldRemove(plan(), "深海大鱼君", null, 2, echo, Root.of(null)))
        assertFalse(FishScorePolicy.shouldRemove(plan(), "深海大鱼君", null, 2, echo, Root({ error("boom") }, { error("boom") })))
        assertFalse(FishScorePolicy.shouldRemove(plan(), "深海大鱼君", null, 2, null, Root.of(rootText)))
    }

    fun `long roots and replies stay fast`() {
        val long = "一米宽的画布能画出四季变化，".repeat(80)
        val root = Root.of(long)
        val reply = TextNormalizer.forMatching("一米宽的画布能画出四季变化".repeat(80))
        repeat(50) { FishRootEcho.points(root, reply) }
        assertEquals(24, FishRootEcho.points(root, reply))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FishRootEchoCases) -> Unit) {
            val s = FishRootEchoCases()
            block(s)
        }
        runIsolated { s -> s.`peer echo shares the existing cap and weak signals need independent evidence`() }
        runIsolated { s -> s.`a verbatim retelling of the root scores and an unrelated reply does not`() }
        runIsolated { s -> s.`generic windows and single character runs never count`() }
        runIsolated { s -> s.`short texts mentions reply prefixes and emote placeholders are not compared`() }
        runIsolated { s -> s.`windows do not span punctuation`() }
        runIsolated { s -> s.`a retelling only tips a borderline account over the line and never removes alone`() }
        runIsolated { s -> s.`the thread owner answering in their own thread is never compared`() }
        runIsolated { s -> s.`video title or description can tip a borderline top level comment or a reply over the line`() }
        runIsolated { s -> s.`only the strongest echo counts across the root title and description`() }
        runIsolated { s -> s.`video metadata is only read for unprotected accounts within reach of the threshold`() }
        runIsolated { s -> s.`the root is read only when the score lands within reach of the threshold`() }
        runIsolated { s -> s.`an unreadable root is skipped and a throwing reader fails open`() }
        runIsolated { s -> s.`long roots and replies stay fast`() }
    }
}
