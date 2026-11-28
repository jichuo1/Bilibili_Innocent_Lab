package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.bilibili.app.comm.list.common.api.model.PlayerArgs
import com.bilibili.pegasus.data.base.BasePegasusData
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizationPrecisionTest {

    fun `keywords see through full width and zero width evasion`() {
        val rules = RuleSetCodec.parse("vx, 加微信")
        assertTrue(RuleSetCodec.matches(rules, "私我 ＶＸ 领取"))
        assertTrue(RuleSetCodec.matches(rules, "加​微‍信送资料"))
        assertTrue(RuleSetCodec.matches(rules, "加﻿微信"))
        // 用户用全角写规则也一样。
        assertTrue(RuleSetCodec.matches(RuleSetCodec.parse("ｑｑ群"), "进 qq群 看"))
        // 普通空格与标点不删：不会把正常句子粘出假命中。
        assertFalse(RuleSetCodec.matches(rules, "加 微 信"))
        assertFalse(RuleSetCodec.matches(RuleSetCodec.parse("ab"), "a b"))
    }

    fun `plain text keeps the fast path and common chinese punctuation is untouched`() {
        val plain = "今天的视频“真好看”…——下次见！"
        assertFalse(TextNormalizer.needsNormalization(plain))
        assertEquals(plain.lowercase(), TextNormalizer.forMatching(plain))
        val ascii = "Hello World"
        assertSame(ascii, TextNormalizer.forSemantic(ascii))
    }

    fun `exact author and tag matching normalizes both sides`() {
        assertTrue(ExactRuleSetCodec.matches(ExactRuleSetCodec.parse("影视飓风"), " 影视飓风​ "))
        assertTrue(ExactRuleSetCodec.matches(ExactRuleSetCodec.parse("ABC"), "ＡＢＣ"))
        assertFalse(ExactRuleSetCodec.matches(ExactRuleSetCodec.parse("科技"), "科技美学"))
        assertTrue(AuthorRuleSet.parse("ｔｅｓｔ").matches("TEST", null))
    }

    fun `semantic text collapses whitespace but keeps case`() {
        assertEquals("哈哈 哈", TextNormalizer.forSemantic("  哈哈\n\t 哈  "))
        assertEquals("AAA", TextNormalizer.forSemantic("ＡＡＡ"))
        assertEquals("Wow", TextNormalizer.forSemantic("Wow"))
    }

    fun `cover play counts are parsed exactly for every two decimal value`() {
        for ((suffix, multiplier) in listOf("万" to 10_000L, "亿" to 100_000_000L)) {
            val perHundredth = multiplier / 100L
            for (hundredths in 1 until 100_000) {
                val expected = hundredths.toLong() * perHundredth
                val text = BigDecimal.valueOf(hundredths.toLong(), 2).stripTrailingZeros().toPlainString()
                val actual = VideoPlayCountReader.parseCoverText(text + suffix)
                if (actual != expected) {
                    assertEquals(text + suffix, expected, actual)
                }
            }
        }
        assertEquals(11_300L, VideoPlayCountReader.parseCoverText("1.13万播放"))
        assertEquals(1_234L, VideoPlayCountReader.parseCoverText("1,234"))
        assertEquals(null, VideoPlayCountReader.parseCoverText("--"))
        assertEquals(null, VideoPlayCountReader.parseCoverText("0万"))
    }

    fun `whitespace and width variants share one semantic judgement`() {
        var questions = 0
        val judge = SemanticJudge("k", SemanticPresets.DANMAKU, background = { it.run(); true }, transport = { body, _, _ ->
            val count = JSONObject(String(body)).getJSONObject("questions").length()
            questions += count
            val answers = JSONObject()
            repeat(count) { i ->
                answers.put("item_$i", JSONObject().put("type", "choice").put("choice", "keep")
                    .put("probabilities", JSONObject().put("block", 0.1).put("keep", 0.9)))
            }
            200 to JSONObject().put("answers", answers).toString()
        })
        judge.evaluate(listOf("ＡＡＡ 好耶"), SemanticMode.WAIT)
        judge.evaluate(listOf("AAA  好耶", " AAA​ 好耶 "), SemanticMode.WAIT)
        assertEquals(SemanticPresets.DANMAKU.size, questions) // 归一化后同一文本只判一次；一题 = 一条 × 一条规则
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (TextNormalizationPrecisionTest) -> Unit) {
            val s = TextNormalizationPrecisionTest()
            block(s)
        }
        runIsolated { s -> s.`keywords see through full width and zero width evasion`() }
        runIsolated { s -> s.`plain text keeps the fast path and common chinese punctuation is untouched`() }
        runIsolated { s -> s.`exact author and tag matching normalizes both sides`() }
        runIsolated { s -> s.`semantic text collapses whitespace but keeps case`() }
        runIsolated { s -> s.`cover play counts are parsed exactly for every two decimal value`() }
        runIsolated { s -> s.`whitespace and width variants share one semantic judgement`() }
    
        TidBlocklistCodecCases().verifyAllCases()
        TitleFreeCopyPolicyCases().verifyAllCases()
        VideoDurationRangeCases().verifyAllCases()
        VideoPlayCountRangeCases().verifyAllCases()
        VideoPublishTimeFilterCases().verifyAllCases()
        VideoRelateReasonFilterCases().verifyAllCases()
    }
}

private class TidBlocklistCodecCases {

    fun `parses the separators the rule editors already accept`() {
        assertEquals(
            setOf(163L, 29413L, 93595166L),
            TidBlocklistCodec.parse("163, 29413\n93595166")
        )
        assertEquals(setOf(163L, 3L), TidBlocklistCodec.parse("163；3"))
        assertEquals(emptySet<Long>(), TidBlocklistCodec.parse(null))
        assertEquals(emptySet<Long>(), TidBlocklistCodec.parse("   "))
    }

    fun `illegal entries are dropped instead of breaking the whole chain`() {
        // 备份文件可以被手改，一个错字不许让整条过滤链失效。
        assertEquals(
            setOf(163L, 7L),
            TidBlocklistCodec.parse("163, , 7, 9999999999999999999999")
        )
    }

    fun `non positive ids are dropped because zero means no tag`() {
        // 宿主用 tid = 0 表示"没有标签"（番剧卡就是）；把 0 收进名单会删掉所有无标签卡。
        assertEquals(setOf(5L), TidBlocklistCodec.parse("0, -163, 5"))
        assertFalse(TidBlocklistCodec.matches(setOf(5L), 0L))
        assertFalse(TidBlocklistCodec.matches(setOf(5L), -1L))
        // "-163" 是个非法 id，不能变成一个永远命中不了的"标签名"留在名单里。
        assertEquals(emptySet<String>(), TidBlocklistCodec.parseNames("0, -163"))
    }

    fun `names and ids share one list because ids are not discoverable`() {
        // App 里没有任何地方告诉用户标签 id 是多少，只认 id 等于没人填得出名单。
        assertEquals(setOf(8318L), TidBlocklistCodec.parse("鬼畜, 8318"))
        assertEquals(setOf("鬼畜"), TidBlocklistCodec.parseNames("鬼畜, 8318"))
        // 归一与 ExactRuleSetCodec 一致：整串小写。
        assertEquals(setOf("vtuber"), TidBlocklistCodec.parseNames("VTuber"))
    }

    fun `a name may contain spaces so entries never split on whitespace`() {
        // 按空白切会把"东方 Project"劈成两个永远命中不了的名字。
        assertEquals(setOf("东方 project"), TidBlocklistCodec.parseNames("东方 Project"))
        // 但纯数字片段内部仍按空白切——历史上"163 29413"这种写法是能用的。
        assertEquals(setOf(163L, 29413L), TidBlocklistCodec.parse("163 29413"))
    }

    fun `normalize keeps names so the editor never eats what the user typed`() {
        // encode(parse(...)) 只留数字：用户填"鬼畜"按确定就没了，还看不出为什么。
        assertEquals("鬼畜,8318", TidBlocklistCodec.normalize("鬼畜, 8318"))
        assertEquals("鬼畜,8318", TidBlocklistCodec.normalize("鬼畜,8318,鬼畜"))
        assertEquals("", TidBlocklistCodec.normalize(null))
        // 存下去的和读出来的必须是同一套切分，否则保存那一步会静默丢东西。
        val normalized = TidBlocklistCodec.normalize("东方 Project, 163, 鬼畜")
        assertEquals(setOf(163L), TidBlocklistCodec.parse(normalized))
        assertEquals(setOf("东方 project", "鬼畜"), TidBlocklistCodec.parseNames(normalized))
    }

    fun `matching is exact so neighbouring numbers are never hit`() {
        val blocked = TidBlocklistCodec.parse("163,29413")
        assertTrue(TidBlocklistCodec.matches(blocked, 163L))
        // 子串语义会让 163 命中 1631；数字上必须是精确相等。
        assertFalse(TidBlocklistCodec.matches(blocked, 1631L))
        assertFalse(TidBlocklistCodec.matches(blocked, 16L))
        assertFalse(TidBlocklistCodec.matches(blocked, 941L))
    }

    fun `an unreadable tid is always allowed through`() {
        // 番剧卡、广告卡、部分直播卡没有分区；"读不到"不等于"未知分区"。
        assertFalse(TidBlocklistCodec.matches(TidBlocklistCodec.parse("163"), null))
        assertFalse(TidBlocklistCodec.matches(emptySet(), 163L))
    }

    fun `encode round trips and deduplicates while keeping order`() {
        assertEquals("163,29413", TidBlocklistCodec.encode(listOf(163L, 29413L, 163L)))
        assertEquals("", TidBlocklistCodec.encode(listOf(0L, -1L)))
        assertEquals(
            setOf(163L, 29413L),
            TidBlocklistCodec.parse(TidBlocklistCodec.encode(setOf(163L, 29413L)))
        )
    }

    fun `panel writes append without disturbing the existing list`() {
        assertEquals("163,29413", TidBlocklistCodec.add("163", 29413L))
        // 重复点同一个标签不应该把名单撑大。
        assertEquals("163", TidBlocklistCodec.add("163", 163L))
        assertEquals("163", TidBlocklistCodec.add(null, 163L))
        // 追加 id 不能顺手抹掉用户已经填好的标签名。
        assertEquals("手游,163", TidBlocklistCodec.add("手游, 163", 163L))
        assertEquals("手游,163", TidBlocklistCodec.add("手游", 163L))
    }

    fun `an unreadable tag name is always allowed through`() {
        // 没有标签的卡片（番剧、广告位）不能因为"读不到"就被删。
        val names = TidBlocklistCodec.parseNames("鬼畜")
        assertFalse(ExactRuleSetCodec.matches(names, null))
        assertFalse(ExactRuleSetCodec.matches(names, "  "))
        assertTrue(ExactRuleSetCodec.matches(names, "鬼畜"))
        // 整串相等：不会因为"科技"就误删"科技美学"。
        assertFalse(ExactRuleSetCodec.matches(TidBlocklistCodec.parseNames("科技"), "科技美学"))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (TidBlocklistCodecCases) -> Unit) {
            val s = TidBlocklistCodecCases()
            block(s)
        }
        runIsolated { s -> s.`parses the separators the rule editors already accept`() }
        runIsolated { s -> s.`illegal entries are dropped instead of breaking the whole chain`() }
        runIsolated { s -> s.`non positive ids are dropped because zero means no tag`() }
        runIsolated { s -> s.`names and ids share one list because ids are not discoverable`() }
        runIsolated { s -> s.`a name may contain spaces so entries never split on whitespace`() }
        runIsolated { s -> s.`normalize keeps names so the editor never eats what the user typed`() }
        runIsolated { s -> s.`matching is exact so neighbouring numbers are never hit`() }
        runIsolated { s -> s.`an unreadable tid is always allowed through`() }
        runIsolated { s -> s.`encode round trips and deduplicates while keeping order`() }
        runIsolated { s -> s.`panel writes append without disturbing the existing list`() }
        runIsolated { s -> s.`an unreadable tag name is always allowed through`() }
    }
}

private class TitleFreeCopyPolicyCases {
    private val widget = "tv.danmaku.bili.videopage.common.widget.view.ExpandableTextView"
    private val legacyWidget = "com.mall.videodetail.vd.videopage.common.widget.view.ExpandableTextView"

    fun `title needs the id, the detail widget class and the arrow sibling all at once`() {
        val chain = listOf(widget, "tv.danmaku.bili.videopage.common.widget.view.a", "androidx.appcompat.widget.AppCompatTextView")
        assertTrue(TitleFreeCopyPolicy.isTitleWidget(true, chain, true))
        // id/title 是宿主里极常见的资源名：缺任何一项都不能当成标题。
        assertFalse(TitleFreeCopyPolicy.isTitleWidget(false, chain, true))
        assertFalse(TitleFreeCopyPolicy.isTitleWidget(true, chain, false))
        assertFalse(TitleFreeCopyPolicy.isTitleWidget(true, listOf("android.widget.TextView"), true))
        assertFalse(TitleFreeCopyPolicy.isTitleWidget(true, emptyList(), true))
    }

    fun `other expandable text views in the host are not the video title widget`() {
        for (name in listOf(
            "com.bilibili.ship.theseus.cheese.widget.ExpandableTextView",
            "com.bilibili.app.comm.comment2.widget.CommentExpandableTextView",
            "com.bilibili.app.comment3.ui.widget.ExpandableTextView",
            "com.bilibili.column.ui.widget.ExpandableTextView",
            "com.bilibili.ogv.misc.roledetail.ExpandableTextView"
        )) {
            assertFalse(name, TitleFreeCopyPolicy.isTitleWidget(true, listOf(name), true))
        }
    }

    fun `a host subclass of the widget still counts and both known package names are accepted`() {
        assertTrue(TitleFreeCopyPolicy.isTitleWidget(true, listOf("x.y.Sub", widget), true))
        assertTrue(TitleFreeCopyPolicy.isTitleWidget(true, listOf(legacyWidget), true))
        assertEquals(listOf(widget, legacyWidget), TitleFreeCopyPolicy.WIDGET_CLASS_NAMES)
    }

    fun `resource names match what the host layout declares`() {
        // theseus_module_ugc_headline_layout 里的两个 id（8.84.0～9.19.0 逐版核对）。
        assertEquals("title", TitleFreeCopyPolicy.VIEW_ID_NAME)
        assertEquals("arrow", TitleFreeCopyPolicy.ARROW_ID_NAME)
    }

    fun `the original text field is the smallest named CharSequence field whatever the names are`() {
        // 三种在宿主里实际出现过的混淆名；dex 按名字排序存放字段，原文恒为名字最小的那个。
        assertEquals(0, TitleFreeCopyPolicy.originFieldIndex(listOf("l", "m", "n")))
        assertEquals(0, TitleFreeCopyPolicy.originFieldIndex(listOf("r", "s", "t")))
        assertEquals(0, TitleFreeCopyPolicy.originFieldIndex(listOf("m", "n", "o")))
        // 不依赖反射返回字段的先后顺序。
        assertEquals(1, TitleFreeCopyPolicy.originFieldIndex(listOf("n", "l", "m")))
        assertEquals(2, TitleFreeCopyPolicy.originFieldIndex(listOf("o", "n", "m")))
        assertEquals(0, TitleFreeCopyPolicy.originFieldIndex(listOf("x")))
        assertNull(TitleFreeCopyPolicy.originFieldIndex(emptyList()))
    }

    fun `choose prefers the original text when the visible text is its collapsed or expanded form`() {
        val full = "这是一个很长很长的视频标题用来测试收起时被截断的情况"
        // 收起态：原文前缀 + 省略 + 展开提示
        assertEquals(full, TitleFreeCopyPolicy.choose(full, "这是一个很长很长的视频…展开"))
        // 展开态：原文 + 收起提示
        assertEquals(full, TitleFreeCopyPolicy.choose(full, full + "收起"))
        // 短标题：可见文本就是原文
        assertEquals("短标题", TitleFreeCopyPolicy.choose("短标题", "短标题"))
    }

    fun `choose falls back to the visible text when the guessed field holds something else`() {
        // 猜错字段（里面是别的文本）时，复制眼前看到的，也不复制错内容。
        assertEquals("真正的标题", TitleFreeCopyPolicy.choose("完全不相干的另一段文本", "真正的标题"))
    }

    fun `choose handles a missing side`() {
        assertEquals("可见", TitleFreeCopyPolicy.choose("", "可见"))
        assertEquals("原文", TitleFreeCopyPolicy.choose("原文", ""))
        assertEquals("", TitleFreeCopyPolicy.choose("", ""))
    }

    fun `normalize keeps a plain title untouched`() {
        assertEquals("【4K】这是一个标题", TitleFreeCopyPolicy.normalize("【4K】这是一个标题"))
        // 行内的连续空格是标题的一部分，不能被压缩。
        assertEquals("A  B", TitleFreeCopyPolicy.normalize("A  B"))
    }

    fun `normalize drops the badge placeholder blanks and zero width characters`() {
        // 宿主在标题前放 4 个空格给角标图片；ReplacementSpan 剔除后可能剩下空白或零宽字符。
        assertEquals("标题", TitleFreeCopyPolicy.normalize("    标题"))
        assertEquals("标题", TitleFreeCopyPolicy.normalize("标题  \n"))
        assertEquals("标题", TitleFreeCopyPolicy.normalize("\u200B\uFEFF标\u2060题\u200B"))
    }

    fun `normalize folds stray line breaks into one line and unifies CRLF`() {
        assertEquals("上 下", TitleFreeCopyPolicy.normalize("上\r\n下"))
        assertEquals("上 下", TitleFreeCopyPolicy.normalize("上\r下"))
        assertEquals("上 下", TitleFreeCopyPolicy.normalize("上\n\n \n下"))
    }

    fun `normalize returns an empty string when nothing copyable is left`() {
        assertEquals("", TitleFreeCopyPolicy.normalize(""))
        assertEquals("", TitleFreeCopyPolicy.normalize("   \n \r\n"))
        assertEquals("", TitleFreeCopyPolicy.normalize("\u200B\u200B"))
    }

    fun `normalize caps the length`() {
        val long = "字".repeat(TitleFreeCopyPolicy.MAX_TITLE_LENGTH + 50)
        assertEquals(TitleFreeCopyPolicy.MAX_TITLE_LENGTH, TitleFreeCopyPolicy.normalize(long).length)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (TitleFreeCopyPolicyCases) -> Unit) {
            val s = TitleFreeCopyPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`title needs the id, the detail widget class and the arrow sibling all at once`() }
        runIsolated { s -> s.`other expandable text views in the host are not the video title widget`() }
        runIsolated { s -> s.`a host subclass of the widget still counts and both known package names are accepted`() }
        runIsolated { s -> s.`resource names match what the host layout declares`() }
        runIsolated { s -> s.`the original text field is the smallest named CharSequence field whatever the names are`() }
        runIsolated { s -> s.`choose prefers the original text when the visible text is its collapsed or expanded form`() }
        runIsolated { s -> s.`choose falls back to the visible text when the guessed field holds something else`() }
        runIsolated { s -> s.`choose handles a missing side`() }
        runIsolated { s -> s.`normalize keeps a plain title untouched`() }
        runIsolated { s -> s.`normalize drops the badge placeholder blanks and zero width characters`() }
        runIsolated { s -> s.`normalize folds stray line breaks into one line and unifies CRLF`() }
        runIsolated { s -> s.`normalize returns an empty string when nothing copyable is left`() }
        runIsolated { s -> s.`normalize caps the length`() }
    }
}

private class VideoDurationRangeCases {

    fun `empty range is disabled and unknown duration is always preserved`() {
        val range = VideoDurationRange(minSeconds = 0, maxSeconds = 0)

        assertFalse(range.isEnabled)
        assertFalse(range.shouldRemove(null))
        assertFalse(range.shouldRemove(0))
        assertFalse(range.shouldRemove(-1))
        assertFalse(range.shouldRemove(1))
    }

    fun `minimum and maximum are exclusive removal boundaries`() {
        val range = VideoDurationRange(minSeconds = 30, maxSeconds = 600)

        assertTrue(range.isEnabled)
        assertTrue(range.shouldRemove(29))
        assertFalse(range.shouldRemove(30))
        assertFalse(range.shouldRemove(600))
        assertTrue(range.shouldRemove(601))
    }

    fun `either boundary can be omitted`() {
        val minimumOnly = VideoDurationRange(minSeconds = 30, maxSeconds = 0)
        val maximumOnly = VideoDurationRange(minSeconds = 0, maxSeconds = 600)

        assertTrue(minimumOnly.shouldRemove(29))
        assertFalse(minimumOnly.shouldRemove(Long.MAX_VALUE))
        assertFalse(maximumOnly.shouldRemove(1))
        assertTrue(maximumOnly.shouldRemove(601))
    }

    fun `negative or reversed configuration fails open`() {
        listOf(
            VideoDurationRange(minSeconds = -1, maxSeconds = 600),
            VideoDurationRange(minSeconds = 30, maxSeconds = -1),
            VideoDurationRange(minSeconds = 601, maxSeconds = 600)
        ).forEach { range ->
            assertFalse(range.isValid)
            assertFalse(range.isEnabled)
            assertFalse(range.shouldRemove(1))
            assertFalse(range.shouldRemove(10_000))
        }
    }

    fun `home field reader follows only the pre-resolved getter and field`() {
        val getter = HomeItem::class.java.getMethod("getPlayerArgs")
        val field = PlayerArgs::class.java.getField("duration")

        assertEquals(
            90L,
            VideoDurationReader.fromField(HomeItem(PlayerArgs(90)), getter, field)
        )
        assertNull(VideoDurationReader.fromField(HomeItem(PlayerArgs(0)), getter, field))
    }

    fun `home container reader prefers getter and falls back to annotated field`() {
        val containerGetter = HomeItem::class.java.getMethod("getPlayerArgs")
        val durationGetter = PlayerArgs::class.java.getMethod("getDuration")
        val durationField = PlayerArgs::class.java.getField("duration")

        assertEquals(
            120L,
            VideoDurationReader.fromContainer(
                HomeItem(PlayerArgs(duration = 90, getterDuration = 120)),
                containerGetter,
                durationGetter,
                durationField
            )
        )
        assertEquals(
            90L,
            VideoDurationReader.fromContainer(
                HomeItem(PlayerArgs(duration = 90, getterDuration = 0)),
                containerGetter,
                durationGetter,
                durationField
            )
        )
    }

    fun `detail reader supports direct and explicit nested duration methods`() {
        val direct = OldRelate::class.java.getMethod("getDuration")
        val itemGetter = NewRelate::class.java.getMethod("getAv")
        val durationGetter = AvCard::class.java.getMethod("getDuration")
        val paths = VideoDurationReader.buildMethodPaths(
            directDurationGetters = listOf(direct),
            nestedChains = listOf(itemGetter to durationGetter)
        )

        assertEquals(120L, VideoDurationReader.fromMethods(OldRelate(120), paths))
        assertEquals(240L, VideoDurationReader.fromMethods(NewRelate(AvCard(240)), paths))
        assertNull(VideoDurationReader.fromMethods(NewRelate(null), paths))
    }

    private class HomeItem(private val playerArgs: PlayerArgs) {
        fun getPlayerArgs(): PlayerArgs = playerArgs
    }

    private class PlayerArgs(
        @JvmField val duration: Int,
        private val getterDuration: Int = duration
    ) {
        fun getDuration(): Int = getterDuration
    }

    private class OldRelate(private val duration: Long) {
        fun getDuration(): Long = duration
    }

    private class NewRelate(private val av: AvCard?) {
        fun getAv(): AvCard? = av
    }

    private class AvCard(private val duration: Long) {
        fun getDuration(): Long = duration
    }

    fun verifyAllCases() {
        fun runIsolated(block: (VideoDurationRangeCases) -> Unit) {
            val s = VideoDurationRangeCases()
            block(s)
        }
        runIsolated { s -> s.`empty range is disabled and unknown duration is always preserved`() }
        runIsolated { s -> s.`minimum and maximum are exclusive removal boundaries`() }
        runIsolated { s -> s.`either boundary can be omitted`() }
        runIsolated { s -> s.`negative or reversed configuration fails open`() }
        runIsolated { s -> s.`home field reader follows only the pre-resolved getter and field`() }
        runIsolated { s -> s.`home container reader prefers getter and falls back to annotated field`() }
        runIsolated { s -> s.`detail reader supports direct and explicit nested duration methods`() }
    }
}

private class VideoPlayCountRangeCases {

    fun `empty range is disabled and unknown play count is always preserved`() {
        val range = VideoPlayCountRange(minimum = 0, maximum = 0)

        assertFalse(range.isEnabled)
        assertFalse(range.shouldRemove(null))
        assertFalse(range.shouldRemove(0))
        assertFalse(range.shouldRemove(-1))
        assertFalse(range.shouldRemove(1))
    }

    fun `minimum and maximum are exclusive removal boundaries`() {
        val range = VideoPlayCountRange(minimum = 10_000, maximum = 1_000_000)

        assertTrue(range.isEnabled)
        assertTrue(range.shouldRemove(9_999))
        assertFalse(range.shouldRemove(10_000))
        assertFalse(range.shouldRemove(1_000_000))
        assertTrue(range.shouldRemove(1_000_001))
    }

    fun `either boundary can be omitted`() {
        val minimumOnly = VideoPlayCountRange(minimum = 10_000, maximum = 0)
        val maximumOnly = VideoPlayCountRange(minimum = 0, maximum = 1_000_000)

        assertTrue(minimumOnly.shouldRemove(9_999))
        assertFalse(minimumOnly.shouldRemove(Long.MAX_VALUE))
        assertFalse(maximumOnly.shouldRemove(1))
        assertTrue(maximumOnly.shouldRemove(1_000_001))
    }

    fun `negative or reversed configuration fails open`() {
        listOf(
            VideoPlayCountRange(minimum = -1, maximum = 1_000),
            VideoPlayCountRange(minimum = 10, maximum = -1),
            VideoPlayCountRange(minimum = 2, maximum = 1)
        ).forEach { range ->
            assertFalse(range.isValid)
            assertFalse(range.isEnabled)
            assertFalse(range.shouldRemove(1))
            assertFalse(range.shouldRemove(10_000))
        }
    }

    fun `cover text parser accepts wan yi and play suffixes`() {
        assertEquals(1_230L, VideoPlayCountReader.parseCoverText("1,230"))
        assertEquals(12_000L, VideoPlayCountReader.parseCoverText("1.2万播放"))
        assertEquals(12_300L, VideoPlayCountReader.parseCoverText("1.23万"))
        assertEquals(100_000_000L, VideoPlayCountReader.parseCoverText("1亿观看"))
        assertEquals(10_000L, VideoPlayCountReader.parseCoverText("1萬次"))
        assertNull(VideoPlayCountReader.parseCoverText(""))
        assertNull(VideoPlayCountReader.parseCoverText("刚刚"))
        assertNull(VideoPlayCountReader.parseCoverText("播放"))
        assertNull(VideoPlayCountReader.parseCoverText("1.2万弹幕"))
        assertNull(VideoPlayCountReader.parseCoverText("0"))
        assertNull(VideoPlayCountReader.parseCoverText("-1"))
    }

    fun `detail reader walks the four-step stat chain and skips broken receivers`() {
        val itemGetter = RelateCard::class.java.getMethod("getAv")
        val statGetter = AvCard::class.java.getMethod("getStat")
        val vtGetter = Stat::class.java.getMethod("getVt")
        val valueGetter = StatInfo::class.java.getMethod("getValue")
        val paths = VideoPlayCountReader.buildMethodPaths(
            listOf(listOf(itemGetter, statGetter, vtGetter, valueGetter))
        )

        assertEquals(
            45_000L,
            VideoPlayCountReader.fromMethods(RelateCard(AvCard(Stat(StatInfo(45_000L)))), paths)
        )
        assertNull(VideoPlayCountReader.fromMethods(RelateCard(null), paths))
        assertNull(
            VideoPlayCountReader.fromMethods(RelateCard(AvCard(Stat(StatInfo(0L)))), paths)
        )
    }

    fun `home cover reader requires a vod aid and ignores live viewer counts`() {
        val getter = HomeItem::class.java.getMethod("getPlayerArgs")

        assertEquals(
            12_000L,
            VideoPlayCountReader.fromHomeCover(
                HomeItem(PlayerArgs(aid = 42L), cover = "1.2万观看"),
                getter
            )
        )
        assertNull(
            VideoPlayCountReader.fromHomeCover(
                HomeItem(PlayerArgs(aid = 0L), cover = "1.2万观看"),
                getter
            )
        )
        assertNull(
            VideoPlayCountReader.fromHomeCover(
                HomeItem(PlayerArgs(aid = 42L, isLive = 1), cover = "1.2万观看"),
                getter
            )
        )
        assertNull(
            VideoPlayCountReader.fromHomeCover(
                HomeItem(PlayerArgs(aid = 42L, roomId = 9L), cover = "1.2万观看"),
                getter
            )
        )
        val booleanLiveGetter = BooleanLiveItem::class.java.getMethod("getPlayerArgs")
        assertNull(
            VideoPlayCountReader.fromHomeCover(
                BooleanLiveItem(BooleanLiveArgs(aid = 42L, isLive = true), cover = "1.2万观看"),
                booleanLiveGetter
            )
        )
        assertNull(
            VideoPlayCountReader.fromHomeCover(
                HomeItem(PlayerArgs(aid = 42L), cover = "刚刚"),
                getter
            )
        )
    }

    private class HomeItem(
        private val playerArgs: PlayerArgs,
        private val cover: String
    ) {
        fun getPlayerArgs(): PlayerArgs = playerArgs
        fun getCoverLeftText1(): String = cover
    }

    private class PlayerArgs(
        @JvmField val aid: Long,
        @JvmField val isLive: Int = 0,
        @JvmField val roomId: Long = 0L
    )

    private class BooleanLiveItem(
        private val playerArgs: BooleanLiveArgs,
        private val cover: String
    ) {
        fun getPlayerArgs(): BooleanLiveArgs = playerArgs
        fun getCoverLeftText1(): String = cover
    }

    private class BooleanLiveArgs(
        @JvmField val aid: Long,
        @JvmField val isLive: Boolean
    )

    private class RelateCard(private val av: AvCard?) {
        fun getAv(): AvCard? = av
    }

    private class AvCard(private val stat: Stat) {
        fun getStat(): Stat = stat
    }

    private class Stat(private val vt: StatInfo) {
        fun getVt(): StatInfo = vt
    }

    private class StatInfo(private val value: Long) {
        fun getValue(): Long = value
    }

    fun verifyAllCases() {
        fun runIsolated(block: (VideoPlayCountRangeCases) -> Unit) {
            val s = VideoPlayCountRangeCases()
            block(s)
        }
        runIsolated { s -> s.`empty range is disabled and unknown play count is always preserved`() }
        runIsolated { s -> s.`minimum and maximum are exclusive removal boundaries`() }
        runIsolated { s -> s.`either boundary can be omitted`() }
        runIsolated { s -> s.`negative or reversed configuration fails open`() }
        runIsolated { s -> s.`cover text parser accepts wan yi and play suffixes`() }
        runIsolated { s -> s.`detail reader walks the four-step stat chain and skips broken receivers`() }
        runIsolated { s -> s.`home cover reader requires a vod aid and ignores live viewer counts`() }
    }
}

private class VideoPublishTimeFilterCases {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-10-18 20:00 北京时间。 */
    private val now: Instant = at(2026, 10, 18, 20, 0)

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Instant =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant()

    private fun exactAt(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): PublishTimeEstimate {
        val instant = at(year, month, day, hour, minute)
        return PublishTimeEstimate(instant, instant.plusSeconds(1))
    }

    private val stale = VideoPublishTextParser.MAX_TEXT_STALENESS_SECONDS

    private fun date(year: Int, month: Int, day: Int) =
        PublishTimePoint.Absolute(LocalDate.of(year, month, day))

    private fun VideoPublishTimeRule.removes(estimate: PublishTimeEstimate?) =
        shouldRemove(estimate, now, zone)

    fun `before a date keeps strictly earlier videos only`() {
        val rule = VideoPublishTimeRule.of(PublishTimeMode.BEFORE, date(2021, 6, 4))

        assertFalse(rule.removes(exactAt(2021, 6, 3, 23, 59)))
        assertTrue(rule.removes(exactAt(2021, 6, 4, 0, 0)))
        assertTrue(rule.removes(exactAt(2024, 1, 1)))
    }

    fun `after a date keeps the node day and everything later`() {
        val rule = VideoPublishTimeRule.of(PublishTimeMode.AFTER, date(2021, 6, 4))

        assertTrue(rule.removes(exactAt(2021, 6, 3, 23, 59)))
        assertFalse(rule.removes(exactAt(2021, 6, 4, 0, 0)))
        assertFalse(rule.removes(exactAt(2026, 10, 18)))
    }

    fun `excluding a range removes both inclusive end days and nothing outside`() {
        val rule = VideoPublishTimeRule.of(
            PublishTimeMode.OUTSIDE,
            date(2021, 7, 8),
            date(2021, 9, 1)
        )

        assertFalse(rule.removes(exactAt(2021, 7, 7, 23, 59)))
        assertTrue(rule.removes(exactAt(2021, 7, 8, 0, 0)))
        assertTrue(rule.removes(exactAt(2021, 9, 1, 23, 59)))
        assertFalse(rule.removes(exactAt(2021, 9, 2, 0, 0)))
    }

    fun `keeping only a range removes both sides`() {
        val rule = VideoPublishTimeRule.of(
            PublishTimeMode.BETWEEN,
            date(2021, 7, 8),
            date(2021, 9, 1)
        )

        assertTrue(rule.removes(exactAt(2021, 7, 7, 23, 59)))
        assertFalse(rule.removes(exactAt(2021, 7, 8)))
        assertFalse(rule.removes(exactAt(2021, 9, 1, 23, 59)))
        assertTrue(rule.removes(exactAt(2021, 9, 2)))
    }

    fun `older than three months uses calendar months relative to now`() {
        val rule = VideoPublishTimeRule.of(
            PublishTimeMode.BEFORE,
            PublishTimePoint.Relative(3, PublishTimeUnit.MONTH)
        )

        // 3 个月前 = 2026-07-18 20:00。
        assertFalse(rule.removes(exactAt(2026, 7, 18, 19, 59)))
        assertTrue(rule.removes(exactAt(2026, 7, 18, 20, 0)))
        assertTrue(rule.removes(exactAt(2026, 10, 1)))
    }

    fun `within the last two days keeps only the trailing 48 hours`() {
        val rule = VideoPublishTimeRule.of(
            PublishTimeMode.AFTER,
            PublishTimePoint.Relative(2, PublishTimeUnit.DAY)
        )

        assertTrue(rule.removes(exactAt(2026, 10, 16, 19, 59)))
        assertFalse(rule.removes(exactAt(2026, 10, 16, 20, 0)))
        assertFalse(rule.removes(exactAt(2026, 10, 18, 19, 0)))
    }

    fun `relative points move with the evaluation time`() {
        val rule = VideoPublishTimeRule.of(
            PublishTimeMode.AFTER,
            PublishTimePoint.Relative(2, PublishTimeUnit.DAY)
        )
        val video = exactAt(2026, 10, 17, 12, 0)

        assertFalse(rule.shouldRemove(video, now, zone))
        assertTrue(rule.shouldRemove(video, at(2026, 10, 20, 12, 1), zone))
    }

    fun `unknown publish time is always kept`() {
        PublishTimeMode.entries.forEach { mode ->
            val rule = if (mode.pointCount == 2) {
                VideoPublishTimeRule.of(mode, date(2021, 1, 1), date(2021, 2, 1))
            } else {
                VideoPublishTimeRule.of(mode, date(2021, 1, 1))
            }
            assertFalse(mode.name, rule.removes(null))
        }
    }

    fun `imprecise estimates are removed only when entirely outside the kept side`() {
        val rule = VideoPublishTimeRule.of(PublishTimeMode.BEFORE, date(2021, 6, 4))
        val straddling = PublishTimeEstimate(at(2021, 6, 3, 12), at(2021, 6, 4, 12))
        val entirelyAfter = PublishTimeEstimate(at(2021, 6, 4), at(2021, 6, 5))

        assertFalse(rule.removes(straddling))
        assertTrue(rule.removes(entirelyAfter))

        val exclude = VideoPublishTimeRule.of(PublishTimeMode.OUTSIDE, date(2021, 7, 8), date(2021, 9, 1))
        val partlyInside = PublishTimeEstimate(at(2021, 9, 1, 12), at(2021, 9, 2, 12))
        assertFalse(exclude.removes(partlyInside))
    }

    fun `encoding round trips every mode and point kind`() {
        listOf(
            VideoPublishTimeRule.of(PublishTimeMode.BEFORE, date(2021, 6, 4)),
            VideoPublishTimeRule.of(PublishTimeMode.AFTER, PublishTimePoint.Relative(2, PublishTimeUnit.DAY)),
            VideoPublishTimeRule.of(PublishTimeMode.BETWEEN, date(2021, 7, 8), date(2021, 9, 1)),
            VideoPublishTimeRule.of(
                PublishTimeMode.OUTSIDE,
                PublishTimePoint.Relative(7, PublishTimeUnit.DAY),
                PublishTimePoint.Relative(3, PublishTimeUnit.DAY)
            ),
            VideoPublishTimeRule.of(
                PublishTimeMode.BETWEEN,
                date(2026, 1, 1),
                PublishTimePoint.Relative(12, PublishTimeUnit.HOUR)
            )
        ).forEach { rule ->
            val parsed = VideoPublishTimeRule.parse(rule.encode())
            assertTrue(parsed.isEnabled)
            assertEquals(rule.encode(), parsed.encode())
            assertEquals(rule.mode, parsed.mode)
            assertEquals(rule.first, parsed.first)
            assertEquals(rule.second, parsed.second)
        }
        assertEquals("v1|outside|d:2021-07-08|d:2021-09-01",
            VideoPublishTimeRule.of(PublishTimeMode.OUTSIDE, date(2021, 7, 8), date(2021, 9, 1)).encode())
    }

    fun `blank text is disabled and corrupt text fails open`() {
        assertSame(VideoPublishTimeRule.DISABLED, VideoPublishTimeRule.parse(""))
        assertFalse(VideoPublishTimeRule.parse("  ").isConfigured)
        listOf(
            "v2|before|d:2021-06-04",
            "v1|sometime|d:2021-06-04",
            "v1|before",
            "v1|before|d:2021-02-30",
            "v1|before|d:1999-01-01",
            "v1|after|r:0d",
            "v1|after|r:10000d",
            "v1|after|r:3x",
            "v1|between|d:2021-06-04",
            "v1|before|d:2021-06-04|d:2021-06-05",
            "v1|between|d:2021-09-01|d:2021-07-08",
            "v1|outside|r:3d|r:7d"
        ).forEach { raw ->
            val rule = VideoPublishTimeRule.parse(raw)
            assertTrue(raw, rule.isConfigured)
            assertFalse(raw, rule.isValid)
            assertFalse(raw, rule.isEnabled)
            assertFalse(raw, rule.removes(exactAt(2021, 6, 4)))
        }
    }

    fun `a mixed range that is inverted at evaluation time fails open`() {
        // 起点 2026-10-01，终点"12 小时前"：10-01 之后两周内有效，但若终点早于起点就整体放行。
        val rule = VideoPublishTimeRule.parse("v1|between|d:2026-10-01|r:12h")
        assertTrue(rule.isEnabled)
        assertNotNull(rule.resolveBounds(now, zone))
        val earlyNow = at(2026, 10, 1, 6)
        assertNull(rule.resolveBounds(earlyNow, zone))
        assertFalse(rule.shouldRemove(exactAt(2020, 1, 1), earlyNow, zone))
    }

    fun `day range with identical start and end covers that whole day`() {
        val rule = VideoPublishTimeRule.of(PublishTimeMode.BETWEEN, date(2021, 6, 4), date(2021, 6, 4))

        assertFalse(rule.removes(exactAt(2021, 6, 4, 0, 0)))
        assertFalse(rule.removes(exactAt(2021, 6, 4, 23, 59)))
        assertTrue(rule.removes(exactAt(2021, 6, 5, 0, 0)))
    }

    fun `parser reads absolute dates as whole server days`() {
        val full = VideoPublishTextParser.parse("某UP主 · 2021-06-04", now)!!
        assertEquals(at(2021, 6, 4), full.earliest)
        assertEquals(at(2021, 6, 5), full.latestExclusive)

        assertEquals(full, VideoPublishTextParser.parse("2021年6月4日", now))
        assertEquals(full, VideoPublishTextParser.parse("2021/06/04 发布", now))
    }

    fun `short dates belong to this year unless they would be in the future`() {
        val thisYear = VideoPublishTextParser.parse("UP · 06-04", now)!!
        assertEquals(at(2026, 6, 4), thisYear.earliest)

        val lastYear = VideoPublishTextParser.parse("12-25", now)!!
        assertEquals(at(2025, 12, 25), lastYear.earliest)
        assertEquals(at(2025, 12, 26), lastYear.latestExclusive)
    }

    fun `calendar words map to whole days widened for stale text`() {
        val yesterday = VideoPublishTextParser.parse("昨天", now)!!
        assertEquals(at(2026, 10, 17).minusSeconds(stale), yesterday.earliest)
        assertEquals(at(2026, 10, 18), yesterday.latestExclusive)
        assertEquals(at(2026, 10, 17), VideoPublishTextParser.parse("前天 12:30", now)!!.latestExclusive)
        assertEquals(at(2026, 10, 17), VideoPublishTextParser.parse("前天12:30", now)!!.latestExclusive)
        assertEquals(at(2026, 10, 19), VideoPublishTextParser.parse("今天", now)!!.latestExclusive)
    }

    fun `relative text widens by one unit on each side`() {
        val estimate = VideoPublishTextParser.parse("UP名 · 3天前", now)!!
        assertEquals(at(2026, 10, 14, 20, 0).minusSeconds(stale), estimate.earliest)
        assertEquals(at(2026, 10, 16, 20, 0).plusSeconds(1), estimate.latestExclusive)

        val hours = VideoPublishTextParser.parse("5小时前", now)!!
        assertTrue(hours.earliest <= at(2026, 10, 18, 14, 0))
        assertTrue(hours.latestExclusive > at(2026, 10, 18, 16, 0))

        assertNotNull(VideoPublishTextParser.parse("刚刚", now))
        assertNotNull(VideoPublishTextParser.parse("2个月前", now))
        assertNotNull(VideoPublishTextParser.parse("1年前", now))
    }

    fun `parser ignores non time segments and conflicting segments`() {
        assertNull(VideoPublishTextParser.parse(null, now))
        assertNull(VideoPublishTextParser.parse("", now))
        assertNull(VideoPublishTextParser.parse("影视飓风", now))
        assertNull(VideoPublishTextParser.parse("1.2万播放", now))
        assertNull(VideoPublishTextParser.parse("12:34", now))
        // UP 名里带日期样式但不是整段：不采用。
        assertNull(VideoPublishTextParser.parse("小明06-04号", now))
        assertNull(VideoPublishTextParser.parse("13-40", now))
        assertNull(VideoPublishTextParser.parse("0天前", now))
        assertNull(VideoPublishTextParser.parse("2021-06-04 · 2022-01-01", now))
    }

    fun `multiple sources merge by intersection and contradictions become unknown`() {
        val merged = VideoPublishTextParser.parseAll(listOf("3天前", "10-15"), now)!!
        assertEquals(at(2026, 10, 15), merged.earliest)
        assertEquals(at(2026, 10, 16), merged.latestExclusive)

        assertNull(VideoPublishTextParser.parseAll(listOf("2021-06-04", "昨天"), now))
        assertNull(VideoPublishTextParser.parseAll(listOf("UP名", null), now))
    }

    fun `parsed relative text never flips a decision near the boundary`() {
        // "3天前"可能是 2–4 天前；"最近两天"规则不应删掉它，"最近五天"必然保留，"最近一天"才删。
        val estimate = VideoPublishTextParser.parse("3天前", now)
        fun lastDays(days: Int) = VideoPublishTimeRule.of(
            PublishTimeMode.AFTER,
            PublishTimePoint.Relative(days, PublishTimeUnit.DAY)
        )

        assertFalse(lastDays(5).removes(estimate))
        assertFalse(lastDays(2).removes(estimate))
        assertTrue(lastDays(1).removes(estimate))
    }

    private class HomeCard(private val text: String, private val args: PlayerArgs) : BasePegasusData() {
        override fun getDesc(): String = text
        override fun getPlayerArgs(): PlayerArgs = args
    }

    fun `home reader parses the desc of regular videos only`() {
        val gate = BasePegasusData::class.java.getMethod("getPlayerArgs")
        val video = HomeCard("某UP主 · 06-04", PlayerArgs().apply { aid = 42L })
        val live = HomeCard("某UP主 · 06-04", PlayerArgs().apply { aid = 42L; isLive = 1 })
        val noAid = HomeCard("某UP主 · 06-04", PlayerArgs())

        assertEquals(at(2026, 6, 4), VideoPublishTimeReader.fromHomeDesc(video, gate, now)!!.earliest)
        assertNull(VideoPublishTimeReader.fromHomeDesc(live, gate, now))
        assertNull(VideoPublishTimeReader.fromHomeDesc(noAid, gate, now))
        assertNull(VideoPublishTimeReader.fromHomeDesc(HomeCard("某UP主", PlayerArgs().apply { aid = 1L }), gate, now))
    }

    fun `stale relative text never removes a card under an older than rule`() {
        // 缓存回放三天后的"3天前"实际可能是 6 天前；"只保留 5 天前之前"的规则不能删它。
        val olderThanFiveDays = VideoPublishTimeRule.of(
            PublishTimeMode.BEFORE,
            PublishTimePoint.Relative(5, PublishTimeUnit.DAY)
        )
        assertFalse(olderThanFiveDays.removes(VideoPublishTextParser.parse("3天前", now)))
        // 绝对日期不受过时影响，照常判定。
        assertTrue(olderThanFiveDays.removes(VideoPublishTextParser.parse("10-17", now)))
    }

    fun `absolute points outside the supported years are rejected`() {
        assertNull(PublishTimePoint.decode("d:2101-01-01"))
        assertTrue(runCatching { date(2101, 1, 1) }.isFailure)
        assertTrue(runCatching { date(2008, 12, 31) }.isFailure)
    }

    fun `parse probe reports exactly once after the sample fills`() {
        val probe = PublishTimeParseProbe(sampleSize = 3)
        assertNull(probe.record(false))
        assertNull(probe.record(true))
        assertEquals(PublishTimeParseProbe.Sample(parsed = 1, seen = 3), probe.record(false))
        assertNull(probe.record(true))
    }

    open class BaseCoverCard {
        open fun getCoverLeftText1(): String = "1"
    }

    class OverridingCoverCard : BaseCoverCard() {
        override fun getCoverLeftText1(): String = "1.2万"
    }

    fun `cover reader resolves getters that a card subclass overrides`() {
        // 基类与子类两条同签名 getter 不能让读取放弃；invoke 分派到子类覆写。
        assertEquals(12_000L, VideoPlayCountReader.coverPlayCount(OverridingCoverCard()))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (VideoPublishTimeFilterCases) -> Unit) {
            val s = VideoPublishTimeFilterCases()
            block(s)
        }
        runIsolated { s -> s.`before a date keeps strictly earlier videos only`() }
        runIsolated { s -> s.`after a date keeps the node day and everything later`() }
        runIsolated { s -> s.`excluding a range removes both inclusive end days and nothing outside`() }
        runIsolated { s -> s.`keeping only a range removes both sides`() }
        runIsolated { s -> s.`older than three months uses calendar months relative to now`() }
        runIsolated { s -> s.`within the last two days keeps only the trailing 48 hours`() }
        runIsolated { s -> s.`relative points move with the evaluation time`() }
        runIsolated { s -> s.`unknown publish time is always kept`() }
        runIsolated { s -> s.`imprecise estimates are removed only when entirely outside the kept side`() }
        runIsolated { s -> s.`encoding round trips every mode and point kind`() }
        runIsolated { s -> s.`blank text is disabled and corrupt text fails open`() }
        runIsolated { s -> s.`a mixed range that is inverted at evaluation time fails open`() }
        runIsolated { s -> s.`day range with identical start and end covers that whole day`() }
        runIsolated { s -> s.`parser reads absolute dates as whole server days`() }
        runIsolated { s -> s.`short dates belong to this year unless they would be in the future`() }
        runIsolated { s -> s.`calendar words map to whole days widened for stale text`() }
        runIsolated { s -> s.`relative text widens by one unit on each side`() }
        runIsolated { s -> s.`parser ignores non time segments and conflicting segments`() }
        runIsolated { s -> s.`multiple sources merge by intersection and contradictions become unknown`() }
        runIsolated { s -> s.`parsed relative text never flips a decision near the boundary`() }
        runIsolated { s -> s.`home reader parses the desc of regular videos only`() }
        runIsolated { s -> s.`stale relative text never removes a card under an older than rule`() }
        runIsolated { s -> s.`absolute points outside the supported years are rejected`() }
        runIsolated { s -> s.`parse probe reports exactly once after the sample fills`() }
        runIsolated { s -> s.`cover reader resolves getters that a card subclass overrides`() }
    }
}

private class VideoRelateReasonFilterCases {
    fun `observed named million-like badge is recognized without matching prose or coins`() {
        assertTrue(VideoRelateEngagementReasonParser.isLikeCountReason("百万点赞"))
        assertTrue(VideoRelateEngagementReasonParser.isLikeCountReason("百 万 点 赞"))
        for (reason in listOf("百万投币", "获得百万点赞", "百万点赞的视频", "百万点赞+")) {
            assertFalse(reason, VideoRelateEngagementReasonParser.isLikeCountReason(reason))
        }
    }


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

    fun verifyAllCases() {
        fun runIsolated(block: (VideoRelateReasonFilterCases) -> Unit) {
            val s = VideoRelateReasonFilterCases()
            block(s)
        }
        runIsolated { s -> s.`observed named million-like badge is recognized without matching prose or coins`() }
        runIsolated { s -> s.`reader supports validated direct and nested chains`() }
        runIsolated { s -> s.`reader rejects incompatible paths and fails open on host errors`() }
        runIsolated { s -> s.`reader preserves missing empty and oversized reason states`() }
        runIsolated { s -> s.`high confidence promotion labels do not treat ordinary reasons as ads`() }
        runIsolated { s -> s.`custom keywords are bounded and matched only against supplied reasons`() }
        runIsolated { s -> s.`strong mode matches promotion wording and generalized like counts`() }
        runIsolated { s -> s.`commercial evidence requires an explicit true protocol getter`() }
    }
}
