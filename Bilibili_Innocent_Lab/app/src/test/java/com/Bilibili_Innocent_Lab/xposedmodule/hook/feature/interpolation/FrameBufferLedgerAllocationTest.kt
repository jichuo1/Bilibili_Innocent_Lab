package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameBufferLedger.Holder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer

/** 性能报告 P2-05（账本固定计数）：每个帧的持有者元数据不再用 HashMap；分配按周期计量，语义由 FrameBufferLedgerTest 覆盖。 */
class FrameBufferLedgerAllocationTest {
    private fun allocatedBytes(): Long =
        (ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean).getThreadAllocatedBytes(Thread.currentThread().id)

    /** 一帧的典型生命周期：采集 -> 时间线 -> worker -> presenter x2，逐一释放。 */
    private fun cycle(ledger: FrameBufferLedger) {
        val slot = checkNotNull(ledger.acquire())
        val id = slot.frameId
        ledger.retain(id, Holder.TIMELINE)
        ledger.release(id, Holder.CAPTURE)
        ledger.retain(id, Holder.WORKER)
        ledger.retain(id, Holder.PRESENTER)
        ledger.retain(id, Holder.PRESENTER)
        ledger.release(id, Holder.TIMELINE)
        ledger.release(id, Holder.WORKER)
        ledger.release(id, Holder.PRESENTER)
        ledger.release(id, Holder.PRESENTER)
    }

    @Test fun holderMetadataAllocationPerFrameLifecycle() {
        val ledger = FrameBufferLedger(maxBuffers = 4) { ByteBuffer.allocateDirect(it) }
        assertTrue(ledger.configure(16, 8))
        repeat(20_000) { cycle(ledger) } // 预热：缓冲进入空闲池，之后只剩账本元数据分配
        val rounds = 100_000
        val before = allocatedBytes()
        repeat(rounds) { cycle(ledger) }
        val perCycle = (allocatedBytes() - before) / rounds.toDouble()
        println("FrameBufferLedger metadata allocation: $perCycle B per frame lifecycle")
        val stats = ledger.stats()
        assertEquals("pool reuse: no extra direct buffers", 1, stats.allocated)
        // 基线（HashMap 持有者元数据）实测 576.0 B；打包计数后 352.0 B。剩余的是 Long 装箱、Entry 与 Slot。
        assertTrue("holder metadata must not regress to per-frame HashMaps: $perCycle B", perCycle < 450.0)
    }
}
