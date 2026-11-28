package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class FollowFeedUpdateIndexTest {
    private fun row(identity: Long, reference: Long? = null, position: Int = 0) = FollowFeedRow(identity, true, true, position,
        reference?.let { FollowFeedReference(it, true, true, false) })

    fun keysKeepBothLongsAndAreOnlyBuiltForRowsWithReferences() {
        val index = FollowFeedUpdateIndex()
        val rows = listOf(row(10), row(11, reference = 5), null, row(12, reference = 5))
        index.rebuild(rows.size) { rows[it] }
        assertNull(index.keyAt(0)); assertNull(index.keyAt(2))
        assertEquals(FollowFeedReferenceKey(11, 5), index.keyAt(1))
        assertEquals("same referenced content under different owners stays distinct", FollowFeedReferenceKey(12, 5), index.keyAt(3))
        assertEquals(2L, index.keyAllocations)
        assertEquals(4L, index.lookups)
        // 两个 Long 不能被压成一个：只在 owner 不同的情形下身份也不同。
        assertFalse(index.hasReference(FollowFeedReferenceKey(5, 11)))
        assertTrue(index.hasReference(FollowFeedReferenceKey(12, 5)))
        assertFalse(index.hasReference(FollowFeedReferenceKey(13, 5)))
    }

    fun emptyListTakesTheShortPathAndLeavesNothingBehind() {
        val index = FollowFeedUpdateIndex()
        val rows = listOf(row(1, reference = 2))
        index.rebuild(1) { rows[it] }
        assertTrue(index.hasReference(FollowFeedReferenceKey(1, 2)))
        index.rebuild(0) { error("no lookups for an empty list") }
        assertEquals(0, index.size)
        assertFalse("rows of the previous screen are not retained", index.hasReference(FollowFeedReferenceKey(1, 2)))
    }

    fun shorterScreensClearTheTailAndLongerScreensGrowTheSnapshot() {
        val index = FollowFeedUpdateIndex()
        val long = List(40) { row(it.toLong(), reference = it.toLong() + 100) }
        index.rebuild(long.size) { long[it] }
        assertEquals(40, index.size)
        assertTrue(index.hasReference(FollowFeedReferenceKey(39, 139)))
        val short = long.take(3)
        index.rebuild(short.size) { short[it] }
        assertFalse(index.hasReference(FollowFeedReferenceKey(39, 139)))
        assertNull(index.rowAt(3).also { }) // 尾部已清空
    }

    fun hasReferenceMatchesTheScanTheControllerUsedToDoOnRandomScreens() {
        val random = Random(20261029L)
        val index = FollowFeedUpdateIndex()
        repeat(250) {
            val count = random.nextInt(30)
            val rows = List(count) {
                if (random.nextInt(4) == 0) null
                else row(random.nextInt(6).toLong(), reference = if (random.nextBoolean()) random.nextInt(4).toLong() else null)
            }
            index.rebuild(count) { rows[it] }
            repeat(10) {
                val key = FollowFeedReferenceKey(random.nextInt(7).toLong(), random.nextInt(5).toLong())
                val legacy = (0 until count).none { rows[it]?.referenceKey() == key }.not()
                val actual = index.hasReference(key)
                if (legacy != actual) assertEquals("key $key in $rows", legacy, actual)
            }
        }
    }

    /** 优化前 update() 的查找 / 分配工作量，逐遍照搬：清理离屏引用、面板收集、引用收集、装饰决策。 */
    private class Legacy(val rows: List<FollowFeedRow?>, val references: List<FollowFeedReferenceKey>) {
        var lookups = 0L
        var keyAllocations = 0L
        private fun rowAt(index: Int): FollowFeedRow? { lookups++; return rows[index] }
        private fun key(row: FollowFeedRow): FollowFeedReferenceKey? = row.referenceKey().also { if (it != null) keyAllocations++ }
        fun run(): Int {
            var stale = 0
            for (reference in references) { // pass 1：每个存量引用扫描所有子 View，直到命中
                if ((0 until rows.size).none { index -> rowAt(index)?.let(::key) == reference }) stale++
            }
            for (i in rows.indices) rowAt(i) // pass 2：面板收集
            for (i in rows.indices) { val row = rowAt(i) ?: continue; row.reference ?: continue; key(row) } // pass 3：引用收集
            for (i in rows.indices) { val row = rowAt(i) ?: continue; key(row) } // pass 4：装饰决策
            return stale
        }
    }

    fun operationCountDropsFromRTimesNToNForTheSameScreen() {
        val random = Random(7L)
        val n = 14
        val rows = List(n) { row(it.toLong(), reference = if (it % 3 == 0) it.toLong() + 100 else null, position = it) }
        // 8 个存量引用：其中 5 个仍在屏上，3 个已离屏。
        val onScreen = rows.mapNotNull { it?.referenceKey() }
        val references = onScreen + List(3) { FollowFeedReferenceKey(900L + it, random.nextInt(9).toLong()) }
        val legacy = Legacy(rows, references)
        val legacyStale = legacy.run()
        val index = FollowFeedUpdateIndex()
        index.rebuild(n) { rows[it] }
        val stale = references.count { !index.hasReference(it) }
        assertEquals("same stale decision", legacyStale, stale)
        println("FollowFeed update: rows=$n references=${references.size} legacy lookups=${legacy.lookups} keys=${legacy.keyAllocations}; " +
            "snapshot lookups=${index.lookups} keys=${index.keyAllocations}")
        assertEquals(n.toLong(), index.lookups)
        assertTrue(index.lookups < legacy.lookups)
        assertTrue(index.keyAllocations < legacy.keyAllocations)
    }

    fun `follow feed tab labels and header titles support Simplified Traditional and English locales`() {
        assertTrue(isSupportedTabLabels(listOf("全部", "视频")))
        assertTrue(isSupportedTabLabels(listOf(" 全部 ", " 影片 ")))
        assertTrue(isSupportedTabLabels(listOf("全部", "視頻")))
        assertTrue(isSupportedTabLabels(listOf("All", "Video")))
        assertTrue(isSupportedTabLabels(listOf(" all ", " VIDEOS ")))
        assertFalse(isSupportedTabLabels(listOf("全部", "视频", "同城")))
        assertFalse(isSupportedTabLabels(listOf("全部")))
        assertFalse(isSupportedTabLabels(listOf("全部", "关注")))

        assertTrue(isSupportedHeaderTitle("关注"))
        assertTrue(isSupportedHeaderTitle(" 關注 "))
        assertTrue(isSupportedHeaderTitle("追蹤"))
        assertTrue(isSupportedHeaderTitle("Following"))
        assertTrue(isSupportedHeaderTitle(" FOLLOW "))
        assertFalse(isSupportedHeaderTitle("动态"))
        assertFalse(isSupportedHeaderTitle(null))
        assertFalse(isSupportedHeaderTitle(""))
    }

    class CurrentHostItemRenamed(
        private val uid: Long,
        @JvmField val mods: List<Any>,
        @JvmField val refParent: CurrentHostItemRenamed?,
        private val hasQuote: Boolean
    ) {
        fun renamedId(): Long = uid
        fun b(): List<Any> = mods
        fun h(): Boolean = hasQuote
    }
    class CurrentHostModuleOk(
        @JvmField var top: Any,
        @JvmField var b: CurrentHostItemRenamed
    ) {
        fun M(): CurrentHostItemRenamed = top as CurrentHostItemRenamed
        fun O(): Boolean = false
    }
    class CurrentHostHolder(@JvmField val module: CurrentHostModuleOk)
    class CurrentHostModuleBadOwner(
        private val top: CurrentHostItemRenamed,
        @JvmField val c: CurrentHostItemRenamed
    ) {
        fun M(): CurrentHostItemRenamed = top
        fun O(): Boolean = false
    }

    fun `FollowFeedHostAccess current falls back for renamed identity localModules parent while enforcing a or b localOwner`() {
        val access = requireNotNull(FollowFeedHostAccess.current(CurrentHostModuleOk::class.java, CurrentHostItemRenamed::class.java))
        lateinit var child: CurrentHostItemRenamed
        lateinit var root: CurrentHostItemRenamed
        val mod = CurrentHostModuleOk(
            CurrentHostItemRenamed(99L, emptyList(), null, false),
            CurrentHostItemRenamed(10L, emptyList(), null, true)
        )
        val modules = listOf<Any>(mod)
        root = CurrentHostItemRenamed(99L, modules, null, false)
        child = CurrentHostItemRenamed(10L, modules, root, true)
        mod.top = root
        mod.b = child
        val row = requireNotNull(access.row(CurrentHostHolder(mod), 7))
        assertEquals(99L, row.identity)
        assertEquals(7, row.position)
        assertTrue(row.first)
        assertTrue(row.last)
        assertEquals(10L, row.reference?.identity)
        assertEquals(true, access.isQuotedModule(mod))

        assertNull(FollowFeedHostAccess.current(CurrentHostModuleBadOwner::class.java, CurrentHostItemRenamed::class.java))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FollowFeedUpdateIndexTest) -> Unit) {
            val s = FollowFeedUpdateIndexTest()
            block(s)
        }
        runIsolated { s -> s.keysKeepBothLongsAndAreOnlyBuiltForRowsWithReferences() }
        runIsolated { s -> s.emptyListTakesTheShortPathAndLeavesNothingBehind() }
        runIsolated { s -> s.shorterScreensClearTheTailAndLongerScreensGrowTheSnapshot() }
        runIsolated { s -> s.hasReferenceMatchesTheScanTheControllerUsedToDoOnRandomScreens() }
        runIsolated { s -> s.operationCountDropsFromRTimesNToNForTheSameScreen() }
        runIsolated { s -> s.`follow feed tab labels and header titles support Simplified Traditional and English locales`() }
        runIsolated { s -> s.`FollowFeedHostAccess current falls back for renamed identity localModules parent while enforcing a or b localOwner`() }
    }
}
