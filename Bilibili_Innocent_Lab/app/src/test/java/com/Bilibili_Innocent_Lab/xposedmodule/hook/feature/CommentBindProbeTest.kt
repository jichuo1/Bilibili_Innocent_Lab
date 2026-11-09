package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentBindProbe.Companion.NO_TOKEN
import java.lang.management.ManagementFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 假时钟只验证记录逻辑（计数、窗口、分位、限频）。这里的纳秒数是测试里手工推进的，
 * 不是任何设备上的耗时，不能引用为性能结论。
 */
class CommentBindProbeTest {
    private class FakeClock(var now: Long = 1_000_000L) : CommentBindProbe.Clock {
        var reads = 0
        override fun nanoTime(): Long {
            reads++
            return now
        }
    }

    private val clock = FakeClock()
    private val probe = CommentBindProbe(clock)

    private fun ms(value: Long) = value * 1_000_000L

    /** 一个 item：[nodes] 个节点、[bindable] 个候选，耗时 [durationMs]。 */
    private fun item(nodes: Int, bindable: Int, durationMs: Long, valid: Boolean = true, enqueuedAt: Long = 0L) {
        val token = probe.beginItem()
        repeat(nodes) { probe.countNode() }
        probe.noteBindable(bindable)
        clock.now += ms(durationMs)
        probe.endItem(token, enqueuedAt, valid)
    }

    @Test fun isOffByDefaultAndEveryEntryPointIsInert() {
        assertFalse(probe.enabled)
        assertEquals(0L, probe.stamp())
        probe.onEnqueue(replaced = 2, overflowed = 3, queueLength = 50)
        probe.onDeferred(CommentBindProbe.DEFER_SCROLL)
        val drain = probe.beginDrain()
        val itemToken = probe.beginItem()
        probe.noteStale()
        probe.noteDetached()
        probe.endItem(itemToken, 0L, valid = true)
        probe.endDrain(drain, queueBefore = 10, taken = 6, queueAfter = 4)
        assertEquals(NO_TOKEN, drain)
        assertEquals(NO_TOKEN, itemToken)
        assertEquals("a disabled probe never reads the clock", 0, clock.reads)
        assertNull(probe.takeReportIfDue(queueEmpty = true))
        val s = probe.snapshot()
        assertFalse(s.enabled)
        assertEquals(0L, s.drains + s.items + s.enqueued + s.staleGenerationItems + s.detachedItems + s.overflowDropped)
        assertTrue(s.recentDrains.isEmpty() && s.recentItems.isEmpty())
    }

    @Test fun recordsOneDrainWithItsItems() {
        probe.enabled = true
        val queued = probe.stamp() // 入队时刻 = 1_000_000
        probe.onEnqueue(replaced = 0, overflowed = 0, queueLength = 8)
        clock.now += ms(3)
        val drain = probe.beginDrain()
        item(nodes = 120, bindable = 30, durationMs = 2, enqueuedAt = queued)
        item(nodes = 400, bindable = 90, durationMs = 7, enqueuedAt = queued)
        item(nodes = 15, bindable = 0, durationMs = 1, valid = false, enqueuedAt = queued)
        probe.endDrain(drain, queueBefore = 8, taken = 6, queueAfter = 2)

        val s = probe.snapshot()
        assertEquals(1L, s.drains)
        assertEquals(3L, s.items)
        assertEquals(2L, s.boundItems)
        assertEquals(1L, s.invalidItems)
        assertEquals(535L, s.totalNodes)
        assertEquals(400, s.maxNodes)
        assertEquals(ms(7), s.maxItemNs)
        assertEquals(ms(10), s.maxDrainNs)
        assertEquals(8, s.maxQueueLength)
        val d = s.recentDrains.single()
        assertEquals(CommentBindProbe.DrainSample(
            durationNs = ms(10), maxItemNs = ms(7), queueBefore = 8, taken = 6,
            bound = 2, invalid = 1, detached = 0, stale = 0, nodes = 535, queueAfter = 2
        ), d)
        assertEquals(listOf(120, 400, 15), s.recentItems.map { it.nodes })
        assertEquals(listOf(30, 90, 0), s.recentItems.map { it.bindable })
        assertEquals(listOf(true, true, false), s.recentItems.map { it.valid })
        assertEquals(listOf(ms(2), ms(7), ms(1)), s.recentItems.map { it.durationNs })
        // 等待 = 入队到 item 完成：3ms 排队 + 之前各 item 的耗时。
        assertEquals(listOf(ms(5), ms(12), ms(13)), s.recentItems.map { it.waitNs })
    }

    @Test fun staleAndDetachedItemsAreCountedPerDrainWithoutItemSamples() {
        probe.enabled = true
        val drain = probe.beginDrain()
        probe.noteStale()
        probe.noteStale()
        probe.noteDetached()
        item(nodes = 10, bindable = 4, durationMs = 1)
        probe.endDrain(drain, queueBefore = 4, taken = 4, queueAfter = 0)
        val s = probe.snapshot()
        assertEquals(2L, s.staleGenerationItems)
        assertEquals(1L, s.detachedItems)
        assertEquals("only the item that walked a tree is an item sample", 1, s.recentItems.size)
        val d = s.recentDrains.single()
        assertEquals(2, d.stale); assertEquals(1, d.detached); assertEquals(1, d.bound)
        // 下一次 drain 的逐批累加从零开始，旧批的丢弃数不会漏进来。
        val next = probe.beginDrain()
        probe.endDrain(next, queueBefore = 0, taken = 0, queueAfter = 0)
        val last = probe.snapshot().recentDrains.last()
        assertEquals(0, last.stale); assertEquals(0, last.detached); assertEquals(0, last.bound)
        assertEquals("cumulative counters keep the totals", 2L, probe.snapshot().staleGenerationItems)
    }

    @Test fun enqueueAccountingCoversReplacementsOverflowAndPeakQueueLength() {
        probe.enabled = true
        probe.onEnqueue(replaced = 0, overflowed = 0, queueLength = 40)
        probe.onEnqueue(replaced = 1, overflowed = 0, queueLength = 40)
        probe.onEnqueue(replaced = 0, overflowed = 2, queueLength = 96)
        probe.onEnqueue(replaced = 0, overflowed = 0, queueLength = 10)
        val s = probe.snapshot()
        assertEquals(4L, s.enqueued)
        assertEquals(1L, s.replacedDuplicates)
        assertEquals(2L, s.overflowDropped)
        assertEquals(96, s.maxQueueLength)
    }

    @Test fun deferralsAreCountedByReason() {
        probe.enabled = true
        repeat(3) { probe.onDeferred(CommentBindProbe.DEFER_SCROLL) }
        probe.onDeferred(CommentBindProbe.DEFER_TOUCH)
        repeat(2) { probe.onDeferred(CommentBindProbe.DEFER_QUIET) }
        probe.onDeferred(99) // 未知原因不计入任何一类
        val s = probe.snapshot()
        assertEquals(3L, s.deferredByScroll); assertEquals(1L, s.deferredByTouch); assertEquals(2L, s.deferredByQuiet)
    }

    @Test fun waitIsUnknownWhenTheItemWasQueuedBeforeTheProbeWasEnabled() {
        probe.enabled = true
        val drain = probe.beginDrain()
        item(nodes = 5, bindable = 1, durationMs = 1, enqueuedAt = 0L)
        probe.endDrain(drain, 1, 1, 0)
        val s = probe.snapshot()
        assertEquals(-1L, s.recentItems.single().waitNs)
        assertEquals("unknown waits are excluded from the wait series", 0, s.waitNs.count)
    }

    @Test fun nodeCountsOutsideAnItemNeverLeakIntoTheNextItem() {
        probe.enabled = true
        // 触摸夺回等非 drain 路径也会走整树收集并调用 countNode。
        repeat(500) { probe.countNode() }
        probe.noteBindable(77)
        val drain = probe.beginDrain()
        item(nodes = 3, bindable = 1, durationMs = 1)
        probe.endDrain(drain, 1, 1, 0)
        val sample = probe.snapshot().recentItems.single()
        assertEquals(3, sample.nodes); assertEquals(1, sample.bindable)
    }

    @Test fun ringsStayBoundedAndKeepTheNewestSamplesOldestFirst() {
        probe.enabled = true
        val drains = CommentBindProbe.DRAIN_CAPACITY * 3 + 5
        for (i in 1..drains) {
            val drain = probe.beginDrain()
            item(nodes = i, bindable = 1, durationMs = 1)
            probe.endDrain(drain, queueBefore = i, taken = 1, queueAfter = 0)
        }
        val s = probe.snapshot()
        assertEquals(drains.toLong(), s.drains)
        assertEquals(drains.toLong(), s.items)
        assertEquals(CommentBindProbe.DRAIN_CAPACITY, s.recentDrains.size)
        // 窗口 = 最近 64 次，最旧在前、最新在后。
        assertEquals((drains - CommentBindProbe.DRAIN_CAPACITY + 1..drains).toList(), s.recentDrains.map { it.queueBefore })
        assertEquals(drains, s.maxNodes)
        // item 环更大：全部 197 个 item 都还在窗口内。
        assertEquals(drains, s.recentItems.size)
        val itemOverflow = CommentBindProbe.ITEM_CAPACITY * 2 + 3
        repeat(itemOverflow) { item(nodes = 1, bindable = 1, durationMs = 1) }
        assertEquals(CommentBindProbe.ITEM_CAPACITY, probe.snapshot().recentItems.size)
        assertEquals(drains + itemOverflow.toLong(), probe.snapshot().items)
    }

    @Test fun nearestRankPercentiles() {
        val hundred = CommentBindProbe.series(LongArray(100) { (100 - it).toLong() }) // 乱序输入
        assertEquals(CommentBindProbe.Series(100, 50, 95, 99, 100), hundred)
        assertEquals(CommentBindProbe.Series(1, 7, 7, 7, 7), CommentBindProbe.series(longArrayOf(7)))
        assertEquals(CommentBindProbe.Series(0, 0, 0, 0, 0), CommentBindProbe.series(LongArray(0)))
        // 少量样本时 p95/p99 都落到最大值：不会越界，也不会插值出没发生过的数。
        assertEquals(CommentBindProbe.Series(3, 2, 3, 3, 3), CommentBindProbe.series(longArrayOf(3, 1, 2)))
    }

    @Test fun seriesAreComputedOverTheRecordedWindow() {
        probe.enabled = true
        val drain = probe.beginDrain()
        for (d in 1..10) item(nodes = d * 10, bindable = 1, durationMs = d.toLong())
        probe.endDrain(drain, 10, 6, 4)
        val s = probe.snapshot()
        assertEquals(CommentBindProbe.Series(10, ms(5), ms(10), ms(10), ms(10)), s.itemNs)
        assertEquals(CommentBindProbe.Series(10, 50, 100, 100, 100), s.itemNodes)
        assertEquals(1, s.drainNs.count)
        assertEquals(ms(55), s.drainNs.max)
    }

    @Test fun disablingStopsRecordingButKeepsTheDataUntilReset() {
        probe.enabled = true
        val first = probe.beginDrain()
        item(nodes = 9, bindable = 2, durationMs = 1)
        probe.endDrain(first, 1, 1, 0)
        probe.enabled = false
        val readsBefore = clock.reads
        assertEquals(NO_TOKEN, probe.beginDrain())
        assertEquals(readsBefore, clock.reads)
        assertEquals(1L, probe.snapshot().drains)
        assertFalse(probe.snapshot().enabled)

        probe.reset()
        val s = probe.snapshot()
        assertEquals(0L, s.drains + s.items + s.totalNodes)
        assertTrue(s.recentDrains.isEmpty() && s.recentItems.isEmpty())
        probe.enabled = true
        val again = probe.beginDrain()
        probe.endDrain(again, 0, 0, 0)
        assertEquals(1L, probe.snapshot().drains)
    }

    @Test fun aDrainStartedWhileDisabledIsNotHalfRecordedAfterEnabling() {
        val drain = probe.beginDrain() // 关闭
        probe.enabled = true
        probe.endDrain(drain, queueBefore = 5, taken = 5, queueAfter = 0)
        val itemToken = NO_TOKEN
        probe.endItem(itemToken, 0L, valid = true)
        assertEquals(0L, probe.snapshot().drains + probe.snapshot().items)
    }

    @Test fun reportsAreRateLimitedAndNeverPerItem() {
        probe.enabled = true
        fun oneDrain() {
            val drain = probe.beginDrain()
            item(nodes = 10, bindable = 2, durationMs = 1)
            probe.endDrain(drain, 1, 1, 0)
        }
        assertNull("nothing to report before any drain", probe.takeReportIfDue(queueEmpty = true))
        oneDrain()
        assertNull("a non-empty queue waits for a full batch of drains", probe.takeReportIfDue(queueEmpty = false))
        val first = probe.takeReportIfDue(queueEmpty = true)
        assertNotNull("the first report is not delayed by the interval", first)
        oneDrain()
        assertNull("minimum interval since the last report", probe.takeReportIfDue(queueEmpty = true))
        clock.now += CommentBindProbe.REPORT_MIN_INTERVAL_NS
        assertNotNull(probe.takeReportIfDue(queueEmpty = true))
        assertNull("no new drain since the last report", probe.takeReportIfDue(queueEmpty = true))
        // 队列一直非空时每累计 REPORT_EVERY_DRAINS 次 drain 才报一次。
        clock.now += CommentBindProbe.REPORT_MIN_INTERVAL_NS
        repeat(CommentBindProbe.REPORT_EVERY_DRAINS - 1) { oneDrain() }
        assertNull(probe.takeReportIfDue(queueEmpty = false))
        oneDrain()
        assertNotNull(probe.takeReportIfDue(queueEmpty = false))
    }

    @Test fun reportTextCarriesTheMeasuredFigures() {
        probe.enabled = true
        probe.onEnqueue(replaced = 1, overflowed = 2, queueLength = 12)
        probe.onDeferred(CommentBindProbe.DEFER_SCROLL)
        val drain = probe.beginDrain()
        item(nodes = 250, bindable = 40, durationMs = 4)
        probe.noteStale()
        probe.endDrain(drain, 12, 6, 6)
        val text = probe.report()
        assertTrue(text, text.startsWith("[BIL] 评论绑定探针 drains=1 "))
        assertTrue(text, text.contains("defer(scroll/touch/quiet)=1/0/0"))
        assertTrue(text, text.contains("items=1(bound=1 invalid=0) detached=0 stale=1"))
        assertTrue(text, text.contains("enq=1 replaced=1 overflow=2 queueMax=12 nodes(total/max)=250/250"))
        assertTrue(text, text.contains("drain_ms(n=1) p50=4.00 p95=4.00 p99=4.00 max=4.00"))
        assertTrue(text, text.contains("item_nodes(n=1) p50=250 p95=250 p99=250 max=250"))
        assertTrue("one line, bounded", !text.contains('\n') && text.length < 700)
    }

    @Test fun theRecordingPathDoesNotAllocateWhenEnabled() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue("thread allocation counters unavailable", bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!
        probe.enabled = true
        fun cycle() {
            probe.onEnqueue(1, 0, 40)
            val drain = probe.beginDrain()
            val a = probe.beginItem()
            probe.countNode(); probe.countNode(); probe.noteBindable(2)
            clock.now += 2_000_000L
            probe.endItem(a, 1L, true)
            probe.noteStale()
            probe.noteDetached()
            probe.endDrain(drain, 8, 3, 5)
            probe.onDeferred(CommentBindProbe.DEFER_SCROLL)
        }
        repeat(30_000) { cycle() } // 预热：JIT、锁膨胀、环形缓冲分配
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        repeat(100_000) { cycle() }
        val allocated = bean.getThreadAllocatedBytes(id) - before
        assertTrue("allocated $allocated bytes over 100k cycles", allocated < 100_000L) // < 1 字节/周期
    }
}
