package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionPickPolicyTest {

    @After fun tearDown() {
        SectionPickSession.resetForTest()
        SectionPickClickSlot.resetForTest()
    }

    @Test fun `only the dislike group carries a tag`() {
        // FEEDBACK 组是"恐怖血腥/色情低俗"那一批，语义完全不同。
        assertEquals(163L, SectionPickPolicy.resolveSection("DISLIKE", 3L, null, 163L))
        assertNull(SectionPickPolicy.resolveSection("FEEDBACK", 3L, null, 163L))
        assertNull(SectionPickPolicy.resolveSection("NO_SELECTED", 3L, null, 163L))
        assertNull(SectionPickPolicy.resolveSection(null, 3L, null, 163L))
    }

    @Test fun `an extend that equals the card tag is self proving`() {
        // 主判据：不依赖任何 id 约定。面板内容是服务端按当前卡下发的，id 不可预知。
        // 9.10.0 实测 extend 恒为 null，这条判据拿不到数据，但宿主一旦填上就该优先。
        assertEquals(163L, SectionPickPolicy.resolveSection("DISLIKE", 9999L, "163", 163L))
        assertEquals(163L, SectionPickPolicy.resolveSection("DISLIKE", null, " 163 ", 163L))
        // 对不上就不算——绝不从 extend 里"捞"数字。
        assertNull(SectionPickPolicy.resolveSection("DISLIKE", null, "164", 163L))
        assertNull(SectionPickPolicy.resolveSection("DISLIKE", null, "{\"tid\":163}", 163L))
        assertNull(SectionPickPolicy.parseExtend(null))
        assertNull(SectionPickPolicy.parseExtend(""))
        assertNull(SectionPickPolicy.parseExtend("0"))
    }

    @Test fun `the recorded value is always the card tag never the section`() {
        // args.tid 是标签（宿主按 tag_id 上报）、args.rid 才是分区，两者不是同一个 id
        // 空间。判定与落库只认 tid；rid 混进来会把分区 id 写进标签名单，进而误删
        // ——分区 id 都是两三位数，撞上一个小标签 id 是迟早的事。
        assertEquals(163L, SectionPickPolicy.resolveSection("DISLIKE", null, "163", 163L))
        assertNull("extend 等于 rid 不算命中", SectionPickPolicy.resolveSection("DISLIKE", null, "4", 163L))
        assertTrue(SectionPickPolicy.matchesExtend("163", 163L))
        assertFalse(SectionPickPolicy.matchesExtend("4", 163L))
    }

    @Test fun `the ordinary card chain decides without any usable id`() {
        // 被点项的 id 由服务端下发，不可预知；判据必须在 id 全缺时照样成立。
        assertEquals(163L, SectionPickPolicy.resolveSection("DISLIKE", null, "163", 163L))
        assertNull(SectionPickPolicy.resolveSection("DISLIKE", null, "164", 163L))
    }

    @Test fun `both the legacy card ids still count as a fallback`() {
        // 旧面板语义：直播卡 id=2，普通 av 卡 id=3。
        assertEquals(163L, SectionPickPolicy.resolveSection("DISLIKE", 2L, null, 163L))
        assertEquals(163L, SectionPickPolicy.resolveSection("DISLIKE", 3L, null, 163L))
    }

    @Test fun `the other panel entries are never mistaken for a tag pick`() {
        // 实测面板项 id：第一层 0=稍后再看 / 1=当前视频；第二层 2,4,8,10,12,13,14。
        listOf(0L, 1L, 4L, 8L, 10L, 12L, 13L, 14L).forEach { id ->
            assertNull(
                "id=$id 不该被当成标签选择",
                SectionPickPolicy.resolveSection("DISLIKE", id, null, 163L)
            )
        }
        assertNull(SectionPickPolicy.resolveSection("DISLIKE", null, null, 163L))
    }

    @Test fun `unmatched dislikes are diagnosable so the criterion can be fixed with evidence`() {
        // 判据来自抓包推断；没命中时要留下可核对的实测值，而不是静默放过。
        assertTrue(SectionPickPolicy.shouldDiagnose("DISLIKE", 163L, null))
        assertTrue(SectionPickPolicy.shouldDiagnose("DISLIKE", null, 4L))
        assertFalse(SectionPickPolicy.shouldDiagnose("FEEDBACK", 163L, 4L))
        assertFalse(SectionPickPolicy.shouldDiagnose("DISLIKE", null, null))
        assertFalse(SectionPickPolicy.shouldDiagnose("DISLIKE", 0L, 0L))
    }

    @Test fun `an unreadable tag is never guessed`() {
        // 宁可不记，也不猜一个 id 写进用户名单。
        assertNull(SectionPickPolicy.resolveSection("DISLIKE", 3L, "163", null))
        assertNull(SectionPickPolicy.resolveSection("DISLIKE", 3L, null, 0L))
        assertNull(SectionPickPolicy.resolveSection("DISLIKE", 3L, null, -1L))
    }

    @Test fun `the click slot carries the pick across the two hops`() {
        // extend 只活在 FeedbackItem 上，分区只活在占位卡回指的原卡上，中间那段挂不上。
        SectionPickClickSlot.remember(7L, "163", 1_000L)
        val pick = SectionPickClickSlot.consume(1_005L)
        assertEquals(7L, pick?.itemId)
        assertEquals("163", pick?.extend)
    }

    @Test fun `the click slot is single use so one click is never counted twice`() {
        SectionPickClickSlot.remember(7L, "163", 1_000L)
        assertEquals("163", SectionPickClickSlot.consume(1_001L)?.extend)
        assertNull("取走就该失效，否则同一次点击会被两个落点各算一遍",
            SectionPickClickSlot.consume(1_002L))
    }

    @Test fun `a stale pick is never claimed by a later card`() {
        // 旧版 CardClickProcessor 那条链会读 extend 却不产生占位卡，残值必须自己老死。
        SectionPickClickSlot.remember(7L, "163", 1_000L)
        assertNull(SectionPickClickSlot.consume(1_000L + SectionPickClickSlot.FRESH_WINDOW_MS + 1))
        SectionPickClickSlot.remember(7L, "163", 1_000L)
        assertNull("时钟倒退时也不能认领", SectionPickClickSlot.consume(999L))
    }

    @Test fun `session picks apply immediately and stay deduplicated`() {
        assertTrue(SectionPickSession.add(163L))
        assertFalse("同一分区点第二次不该再增长", SectionPickSession.add(163L))
        assertTrue(SectionPickSession.contains(163L))
        assertFalse(SectionPickSession.contains(29413L))
        assertEquals(setOf(163L), SectionPickSession.current)
    }

    @Test fun `session rejects unusable ids and stays bounded`() {
        assertFalse(SectionPickSession.add(0L))
        assertFalse(SectionPickSession.add(-5L))
        assertFalse(SectionPickSession.contains(null))
        repeat(80) { SectionPickSession.add((it + 1).toLong()) }
        assertTrue("会话集合必须有界，防止异常路径撑成泄漏", SectionPickSession.current.size <= 64)
    }

    @Test fun `the section id is still diagnosable even though it never decides`() {
        // rid 不参与判定，但没命中时要记进日志——日后真要做"按分区过滤"得有实测数据。
        assertTrue(SectionPickPolicy.shouldDiagnose("DISLIKE", null, 4L))
        assertFalse(SectionPickPolicy.shouldDiagnose("DISLIKE", null, null))
    }

    @Test fun `the snapshot key is stable so repeated picks collapse to one entry`() {
        assertEquals("tid:163", SectionPickPolicy.snapshotKey(163L))
        assertEquals(SectionPickPolicy.snapshotKey(163L), SectionPickPolicy.snapshotKey(163L))
    }

    @Test fun `the picks surface is a registered observation surface`() {
        // 没登记进 ALLOWED_SURFACES 的话 encode 会把它悄悄改写成 mine，快照就串面了。
        assertTrue(
            MineComponentSnapshotCodec.SURFACE_SECTION_PICKS in
                MineComponentSnapshotCodec.ALLOWED_SURFACES
        )
    }
}
