package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import org.junit.Assert.*
import org.junit.Test

class NativePickSnapshotTest {
    @Test fun twoTagPicksSurviveTheActualWireCodecAndAccumulator() {
        val receipts = mutableListOf<MineComponentSnapshot>()
        val environment = HookEnvironment("tv.danmaku.bili", javaClass.classLoader,
            HookPointRegistry(javaClass.classLoader), TestHookRegistrar, { _, _ -> }, { _, _ -> }, { _, _ -> },
            writeScanSnapshot = { surface, content ->
                val json = MineComponentSnapshotCodec.encode(content.processName, content.capabilities, content.entries, surface)
                receipts += checkNotNull(MineComponentSnapshotCodec.decodeOrNull(json, allowLegacy = false))
                true
            })
        val publisher = ScanSnapshotPublisher(environment, MineComponentSnapshotCodec.SURFACE_SECTION_PICKS,
            setOf("home_recommend_tid_block"))
        publisher.accumulate(MineComponentScanEntry("tid:11", "section", "first", "11", null, true))
        publisher.accumulate(MineComponentScanEntry("tid:22", "section", "second", "22", null, true))
        assertEquals(setOf("11", "22"), receipts.last().entries.map { it.id }.toSet())
        assertEquals("section_picks", receipts.last().surface)
    }

    @Test fun authorKeysRoundTripAndForgedTagKeysAreRejected() {
        val entry = checkNotNull(MineComponentScanEntry.create("author", "UP name", "UP name", null, true))
        assertEquals(entry, MineComponentScanEntry.fromJsonOrNull(entry.toJson()))
        assertNull(MineComponentScanEntry.create("section", "bad", "-1", null, true))
        assertNull(MineComponentScanEntry.fromJsonOrNull(
            MineComponentScanEntry("tid:99", "section", "bad", "11", null, true).toJson()))
    }
}
