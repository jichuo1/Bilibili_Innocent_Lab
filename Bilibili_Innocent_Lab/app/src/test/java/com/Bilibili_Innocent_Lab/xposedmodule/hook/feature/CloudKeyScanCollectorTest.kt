package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudKeyScanCollectorTest {
    private class Harness(capacity: Int = 1024) {
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val published = mutableListOf<List<MineComponentScanEntry>>()
        val collector = CloudKeyScanCollector(
            capacity = capacity,
            schedule = { delay, task -> scheduled += delay to task },
            publish = { published += it }
        )
        fun runScheduled() {
            val tasks = scheduled.toList(); scheduled.clear()
            tasks.forEach { (_, task) -> task() }
        }
        fun last() = published.last().associate { it.id!! to it }
    }

    @Test fun `new keys are published sorted with their boolean value in one debounced flush`() {
        val h = Harness()
        h.collector.record("ff_b", false)
        h.collector.record("dd.a", true)
        h.collector.record("ff_b", false)
        assertEquals("one flush is scheduled however many keys arrive", 1, h.scheduled.size)
        assertEquals(CloudKeyScanCollector.DEFAULT_FLUSH_DELAY_MS, h.scheduled.single().first)
        assertTrue(h.published.isEmpty())
        h.runScheduled()
        val entries = h.published.single()
        assertEquals(listOf("dd.a", "ff_b"), entries.map { it.id })
        assertEquals(listOf("true", "false"), entries.map { it.value })
        assertTrue(entries.all { it.kind == CloudKeyScan.KIND && it.key == "cloud_key:${it.id}" && it.selectable })
    }

    @Test fun `repeating a known value schedules nothing`() {
        val h = Harness()
        h.collector.record("ff_a", true)
        h.runScheduled()
        repeat(1000) { h.collector.record("ff_a", true) }
        assertTrue(h.scheduled.isEmpty())
        assertEquals(1, h.published.size)
    }

    @Test fun `a key read as both on and off becomes mixed once and never flaps again`() {
        val h = Harness()
        h.collector.record("ff_flap", true)
        h.runScheduled()
        h.collector.record("ff_flap", false)
        assertEquals(1, h.scheduled.size)
        h.runScheduled()
        assertEquals("mixed", h.last().getValue("ff_flap").value)
        assertFalse("a mixed key cannot be newly ticked", h.last().getValue("ff_flap").selectable)
        // 之后无论读到什么都不再触发发布：一个键最多两次落盘。
        repeat(50) { h.collector.record("ff_flap", it % 2 == 0) }
        assertTrue(h.scheduled.isEmpty())
        assertEquals(2, h.published.size)
    }

    @Test fun `keys that cannot be written as a rule never enter the scan`() {
        val h = Harness()
        listOf("grpc.timeout", "Infra.base", "login.x", "has space", "", "a".repeat(97), "中文键").forEach {
            h.collector.record(it, true)
        }
        assertEquals(0, h.collector.size)
        assertTrue(h.scheduled.isEmpty())
        h.collector.record("ff_ok", true)
        assertEquals(1, h.collector.size)
    }

    @Test fun `capacity bounds the scan and later keys are dropped silently`() {
        val h = Harness(capacity = 3)
        (1..10).forEach { h.collector.record("ff_$it", true) }
        assertEquals(3, h.collector.size)
        h.runScheduled()
        assertEquals(3, h.published.single().size)
        // 已登记的键仍能更新状态。
        h.collector.record("ff_1", false)
        h.runScheduled()
        assertEquals("mixed", h.last().getValue("ff_1").value)
    }

    @Test fun `a later change after the flush started schedules the next flush`() {
        val h = Harness()
        h.collector.record("ff_a", true)
        h.runScheduled()
        h.collector.record("ff_b", true)
        assertEquals(1, h.scheduled.size)
        h.runScheduled()
        assertEquals(listOf("ff_a", "ff_b"), h.published.last().map { it.id })
    }

    @Test fun `entries carry only a boolean marker never anything else`() {
        val h = Harness()
        h.collector.record("ff_a", true)
        h.collector.record("ff_b", false)
        h.runScheduled()
        assertTrue(h.published.single().all { it.value in setOf("true", "false", "mixed") })
        assertTrue(h.published.single().all { it.title == null && it.uri == null })
    }

    @Test fun `entryOf refuses an over long key and builds the stable selector key`() {
        assertNull(CloudKeyScan.entryOf("k".repeat(200), CloudKeyScan.VALUE_TRUE))
        val entry = CloudKeyScan.entryOf("ff_x", CloudKeyScan.VALUE_FALSE)!!
        assertEquals("cloud_key:ff_x", entry.key)
    }
}
