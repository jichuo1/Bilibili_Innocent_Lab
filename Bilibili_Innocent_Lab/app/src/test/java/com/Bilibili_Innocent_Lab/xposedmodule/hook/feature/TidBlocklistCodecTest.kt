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
            TidBlocklistCodec.parse("163, 手游, , 7, 9999999999999999999999")
        )
    }

    @Test fun `non positive ids are dropped because zero means no section`() {
        // 宿主用 tid = 0 表示"没有分区"（番剧卡就是）；把 0 收进名单会删掉所有无分区卡。
        assertEquals(setOf(5L), TidBlocklistCodec.parse("0, -163, 5"))
        assertFalse(TidBlocklistCodec.matches(setOf(5L), 0L))
        assertFalse(TidBlocklistCodec.matches(setOf(5L), -1L))
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
        // 重复点同一个分区不应该把名单撑大。
        assertEquals("163", TidBlocklistCodec.add("163", 163L))
        assertEquals("163", TidBlocklistCodec.add(null, 163L))
        assertEquals("163", TidBlocklistCodec.add("手游, 163", 163L))
    }
}
