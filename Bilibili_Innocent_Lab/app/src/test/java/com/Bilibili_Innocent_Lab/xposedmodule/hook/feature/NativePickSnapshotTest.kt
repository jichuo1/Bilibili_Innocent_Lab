package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import org.junit.Assert.*
import org.junit.Test

class NativePickSnapshotTest {
    fun twoTagPicksSurviveTheActualWireCodecAndAccumulator() {
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

    /**
     * 宿主进程重启后的第一次点选**不能**把之前还没确认的点选整份抹掉。
     *
     * 2026-09-15 真机实测：`tid:79793` 先被记下，宿主进程重启后点了 `tid:13160`，
     * 累积器是空的、整份覆盖，`payload_section_picks` 里只剩后者。用户在模块里
     * 确认之前记录就没了，且全程没有任何错误。
     */
    fun aPickMadeAfterAHostRestartUnionsWithTheOnePersistedBefore() {
        val before = listOf(MineComponentScanEntry("tid:79793", "section", "日本文化", "79793", null, true,
            selectionToken = "first-session"))
        // 重启后的新进程只累积到自己这一条。
        val afterRestart = listOf(MineComponentScanEntry("tid:13160", "section", "学习", "13160", null, true,
            selectionToken = "second-session"))
        val merged = MineComponentSnapshotCodec.accumulate(
            MineComponentSnapshotCodec.SURFACE_SECTION_PICKS, before, afterRestart)
        assertEquals(listOf("13160", "79793"), merged.map { it.id })
    }

    fun theNewerEntryWinsOnTheSameKeyAndListSurfacesStillReplaceWholesale() {
        val previous = listOf(MineComponentScanEntry("tid:11", "section", "old", "11", null, true,
            selectionToken = "old-token"))
        val current = listOf(MineComponentScanEntry("tid:11", "section", "new", "11", null, true,
            selectionToken = "new-token"))
        assertEquals(listOf("new-token"), MineComponentSnapshotCodec.accumulate(
            MineComponentSnapshotCodec.SURFACE_SECTION_PICKS, previous, current).map { it.selectionToken })
        // 列表面每次提交的就是当前页面的完整候选，绝不能并进上一页的残留。
        assertEquals(current, MineComponentSnapshotCodec.accumulate(
            MineComponentSnapshotCodec.SURFACE_HOME_TABS, previous, current))
        assertTrue(MineComponentSnapshotCodec.ACCUMULATING_SURFACES.all {
            it in MineComponentSnapshotCodec.ALLOWED_SURFACES
        })
    }

    /** 满额时保本次点选，用旧记录补齐；否则用户刚点的那条会被历史挤掉。 */
    fun theCurrentPicksAreKeptWhenTheMergedSetWouldOverflow() {
        val previous = (1..MineComponentSnapshotCodec.MAX_ENTRY_COUNT).map {
            MineComponentScanEntry("tid:$it", "section", "old $it", it.toString(), null, true)
        }
        val current = listOf(MineComponentScanEntry("tid:900000", "section", "新点的", "900000", null, true))
        val merged = MineComponentSnapshotCodec.accumulate(
            MineComponentSnapshotCodec.SURFACE_SECTION_PICKS, previous, current)
        assertEquals(MineComponentSnapshotCodec.MAX_ENTRY_COUNT, merged.size)
        assertTrue(merged.any { it.id == "900000" })
    }

    fun authorKeysRoundTripAndForgedTagKeysAreRejected() {
        val entry = checkNotNull(MineComponentScanEntry.create("author", "UP name", "UP name", null, true))
        assertEquals(entry, MineComponentScanEntry.fromJsonOrNull(entry.toJson()))
        assertNull(MineComponentScanEntry.create("section", "bad", "-1", null, true))
        assertNull(MineComponentScanEntry.fromJsonOrNull(
            MineComponentScanEntry("tid:99", "section", "bad", "11", null, true).toJson()))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (NativePickSnapshotTest) -> Unit) {
            val s = NativePickSnapshotTest()
            block(s)
        }
        runIsolated { s -> s.twoTagPicksSurviveTheActualWireCodecAndAccumulator() }
        runIsolated { s -> s.aPickMadeAfterAHostRestartUnionsWithTheOnePersistedBefore() }
        runIsolated { s -> s.theNewerEntryWinsOnTheSameKeyAndListSurfacesStillReplaceWholesale() }
        runIsolated { s -> s.theCurrentPicksAreKeptWhenTheMergedSetWouldOverflow() }
        runIsolated { s -> s.authorKeysRoundTripAndForgedTagKeysAreRejected() }
    }
}
