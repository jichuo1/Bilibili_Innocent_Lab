package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TidBlocklistCodecTest {

    @Test fun `parses the separators the rule editors already accept`() {
        assertEquals(
            setOf(163L, 29413L, 93595166L),
            TidBlocklistCodec.parse("163, 29413\n93595166")
        )
        assertEquals(setOf(163L, 3L), TidBlocklistCodec.parse("163；3"))
        assertEquals(emptySet<Long>(), TidBlocklistCodec.parse(null))
        assertEquals(emptySet<Long>(), TidBlocklistCodec.parse("   "))
    }

    @Test fun `illegal entries are dropped instead of breaking the whole chain`() {
        // 备份文件可以被手改，一个错字不许让整条过滤链失效。
        assertEquals(
            setOf(163L, 7L),
            TidBlocklistCodec.parse("163, , 7, 9999999999999999999999")
        )
    }

    @Test fun `non positive ids are dropped because zero means no tag`() {
        // 宿主用 tid = 0 表示"没有标签"（番剧卡就是）；把 0 收进名单会删掉所有无标签卡。
        assertEquals(setOf(5L), TidBlocklistCodec.parse("0, -163, 5"))
        assertFalse(TidBlocklistCodec.matches(setOf(5L), 0L))
        assertFalse(TidBlocklistCodec.matches(setOf(5L), -1L))
        // "-163" 是个非法 id，不能变成一个永远命中不了的"标签名"留在名单里。
        assertEquals(emptySet<String>(), TidBlocklistCodec.parseNames("0, -163"))
    }

    @Test fun `names and ids share one list because ids are not discoverable`() {
        // App 里没有任何地方告诉用户标签 id 是多少，只认 id 等于没人填得出名单。
        assertEquals(setOf(8318L), TidBlocklistCodec.parse("鬼畜, 8318"))
        assertEquals(setOf("鬼畜"), TidBlocklistCodec.parseNames("鬼畜, 8318"))
        // 归一与 ExactRuleSetCodec 一致：整串小写。
        assertEquals(setOf("vtuber"), TidBlocklistCodec.parseNames("VTuber"))
    }

    @Test fun `a name may contain spaces so entries never split on whitespace`() {
        // 按空白切会把"东方 Project"劈成两个永远命中不了的名字。
        assertEquals(setOf("东方 project"), TidBlocklistCodec.parseNames("东方 Project"))
        // 但纯数字片段内部仍按空白切——历史上"163 29413"这种写法是能用的。
        assertEquals(setOf(163L, 29413L), TidBlocklistCodec.parse("163 29413"))
    }

    @Test fun `normalize keeps names so the editor never eats what the user typed`() {
        // encode(parse(...)) 只留数字：用户填"鬼畜"按确定就没了，还看不出为什么。
        assertEquals("鬼畜,8318", TidBlocklistCodec.normalize("鬼畜, 8318"))
        assertEquals("鬼畜,8318", TidBlocklistCodec.normalize("鬼畜,8318,鬼畜"))
        assertEquals("", TidBlocklistCodec.normalize(null))
        // 存下去的和读出来的必须是同一套切分，否则保存那一步会静默丢东西。
        val normalized = TidBlocklistCodec.normalize("东方 Project, 163, 鬼畜")
        assertEquals(setOf(163L), TidBlocklistCodec.parse(normalized))
        assertEquals(setOf("东方 project", "鬼畜"), TidBlocklistCodec.parseNames(normalized))
    }

    @Test fun `matching is exact so neighbouring numbers are never hit`() {
        val blocked = TidBlocklistCodec.parse("163,29413")
        assertTrue(TidBlocklistCodec.matches(blocked, 163L))
        // 子串语义会让 163 命中 1631；数字上必须是精确相等。
        assertFalse(TidBlocklistCodec.matches(blocked, 1631L))
        assertFalse(TidBlocklistCodec.matches(blocked, 16L))
        assertFalse(TidBlocklistCodec.matches(blocked, 941L))
    }

    @Test fun `an unreadable tid is always allowed through`() {
        // 番剧卡、广告卡、部分直播卡没有分区；"读不到"不等于"未知分区"。
        assertFalse(TidBlocklistCodec.matches(TidBlocklistCodec.parse("163"), null))
        assertFalse(TidBlocklistCodec.matches(emptySet(), 163L))
    }

    @Test fun `encode round trips and deduplicates while keeping order`() {
        assertEquals("163,29413", TidBlocklistCodec.encode(listOf(163L, 29413L, 163L)))
        assertEquals("", TidBlocklistCodec.encode(listOf(0L, -1L)))
        assertEquals(
            setOf(163L, 29413L),
            TidBlocklistCodec.parse(TidBlocklistCodec.encode(setOf(163L, 29413L)))
        )
    }

    @Test fun `panel writes append without disturbing the existing list`() {
        assertEquals("163,29413", TidBlocklistCodec.add("163", 29413L))
        // 重复点同一个标签不应该把名单撑大。
        assertEquals("163", TidBlocklistCodec.add("163", 163L))
        assertEquals("163", TidBlocklistCodec.add(null, 163L))
        // 追加 id 不能顺手抹掉用户已经填好的标签名。
        assertEquals("手游,163", TidBlocklistCodec.add("手游, 163", 163L))
        assertEquals("手游,163", TidBlocklistCodec.add("手游", 163L))
    }

    @Test fun `an unreadable tag name is always allowed through`() {
        // 没有标签的卡片（番剧、广告位）不能因为"读不到"就被删。
        val names = TidBlocklistCodec.parseNames("鬼畜")
        assertFalse(ExactRuleSetCodec.matches(names, null))
        assertFalse(ExactRuleSetCodec.matches(names, "  "))
        assertTrue(ExactRuleSetCodec.matches(names, "鬼畜"))
        // 整串相等：不会因为"科技"就误删"科技美学"。
        assertFalse(ExactRuleSetCodec.matches(TidBlocklistCodec.parseNames("科技"), "科技美学"))
    }
}
