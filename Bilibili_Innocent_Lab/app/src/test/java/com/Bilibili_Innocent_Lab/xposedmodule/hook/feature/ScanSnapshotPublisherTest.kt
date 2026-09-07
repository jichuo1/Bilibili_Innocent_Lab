package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import org.junit.Assert.*
import org.junit.Test

class ScanSnapshotPublisherTest {
    private fun environment(sink: (String, ScanSnapshotContent) -> Boolean) = HookEnvironment(
        "tv.danmaku.bili", javaClass.classLoader, HookPointRegistry(javaClass.classLoader),
        TestHookRegistrar, { _, _ -> }, { _, _ -> }, { _, _ -> }, writeScanSnapshot = sink
    )

    @Test fun `sink failure does not suppress identical resubmission`() {
        var calls = 0
        val publisher = ScanSnapshotPublisher(environment { _, _ -> ++calls > 1 }, "home_tabs", setOf("item_filter"))
        val entry = MineComponentScanEntry("k", "item", "title", "1", null, true)
        publisher.publish(listOf(entry)); publisher.publish(listOf(entry))
        assertEquals(2, calls)
    }

    @Test fun `queued content does not share mutable accumulator or caller collections`() {
        val captured = mutableListOf<ScanSnapshotContent>()
        val capabilities = mutableSetOf("item_filter")
        val publisher = ScanSnapshotPublisher(environment { _, value -> captured += value; true }, "home_tabs", capabilities)
        val first = MineComponentScanEntry("one", "item", "first", "1", null, true)
        val second = MineComponentScanEntry("two", "item", "second", "2", null, true)
        publisher.accumulate(first); publisher.accumulate(second); capabilities.clear()
        assertEquals(listOf(first), captured[0].entries)
        assertEquals(setOf("item_filter"), captured[0].capabilities)
        assertEquals(2, captured[1].entries.size)
    }
}
