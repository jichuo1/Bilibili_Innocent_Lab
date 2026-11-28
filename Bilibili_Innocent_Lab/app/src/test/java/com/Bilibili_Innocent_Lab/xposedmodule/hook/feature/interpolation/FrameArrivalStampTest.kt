package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameAudioClockCompensation.Mode
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameAudioDelay.Plan
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameBufferLedger.Holder
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationBackend.Status
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameArrivalStampTest {
    private val period = 33_333_333L

    fun `a busy capture thread does not delay arrivals of a steady cadence`() {
        val stamp = FrameArrivalStamp()
        // 捕获线程落后一个帧间隔：锁存时刻 = 通知 + 33 ms。首两帧没有可比间隔，用锁存时刻。
        val arrivals = (0 until 6).map { i -> stamp.arrival(i * period, 1_000L + i * period, 1_000L + (i + 1) * period) }
        assertEquals(1_000L + period, arrivals[0])
        assertEquals(1_000L + 2 * period, arrivals[1])
        for (i in 2 until 6) assertEquals(1_000L + i * period, arrivals[i])
    }

    fun `a replaced buffer falls back to the latch time until the cadence is consistent again`() {
        val stamp = FrameArrivalStamp()
        for (i in 0 until 3) stamp.arrival(i * period, i * period, i * period + 1_000L)
        // 第 3 帧被顶替：锁存到第 4 帧，通知时刻属于更早的帧，不能使用。
        assertEquals(4 * period + 9_000L, stamp.arrival(4 * period, 3 * period, 4 * period + 9_000L))
        assertEquals(5 * period + 9_000L, stamp.arrival(5 * period, 5 * period, 5 * period + 9_000L))
        assertEquals(6 * period, stamp.arrival(6 * period, 6 * period, 6 * period + 9_000L))
    }

    fun `a notification after the latch is never used and a seek back resets the cadence`() {
        val stamp = FrameArrivalStamp()
        for (i in 0 until 3) stamp.arrival(i * period, i * period, i * period)
        assertEquals(3 * period, stamp.arrival(3 * period, 3 * period + 5L, 3 * period))
        assertEquals(10L, stamp.arrival(0L, 0L, 10L))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FrameArrivalStampTest) -> Unit) {
            val s = FrameArrivalStampTest()
            block(s)
        }
        runIsolated { s -> s.`a busy capture thread does not delay arrivals of a steady cadence`() }
        runIsolated { s -> s.`a replaced buffer falls back to the latch time until the cadence is consistent again`() }
        runIsolated { s -> s.`a notification after the latch is never used and a seek back resets the cadence`() }
    
        FrameAudioClockCompensationCases().verifyAllCases()
        FrameAudioDelayCases().verifyAllCases()
        FrameBufferLedgerAllocationCases().holderMetadataAllocationPerFrameLifecycle()
        FrameCapabilityProbeCases().verifyAllCases()
        FrameHighRateTargetCases().verifyAllCases()
    }
}

private class FrameAudioClockCompensationCases {
    fun `failed timestamp attempts cannot select the timestamp path`() {
        assertEquals(Mode.LATENCY, FrameAudioClockCompensation.choose(0, 0, 3))
        assertEquals(Mode.HEAD, FrameAudioClockCompensation.choose(0, 1, 3))
        assertEquals(Mode.SILENCE, FrameAudioClockCompensation.choose(0, 0, 0))
        assertEquals(Mode.TIMESTAMP, FrameAudioClockCompensation.choose(1, 1, 3))
    }

    fun `latency compensation is removed on native recovery`() {
        assertEquals(-110, FrameAudioClockCompensation.latencyMs(40, 150_000, Mode.LATENCY))
        assertEquals(40, FrameAudioClockCompensation.latencyMs(40, 0, Mode.LATENCY))
    }

    fun `head compensation is not applied twice through latency`() {
        for (mode in listOf(Mode.TIMESTAMP, Mode.HEAD, Mode.SILENCE, Mode.UNDECIDED))
            assertEquals(40, FrameAudioClockCompensation.latencyMs(40, 150_000, mode))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FrameAudioClockCompensationCases) -> Unit) {
            val s = FrameAudioClockCompensationCases()
            block(s)
        }
        runIsolated { s -> s.`failed timestamp attempts cannot select the timestamp path`() }
        runIsolated { s -> s.`latency compensation is removed on native recovery`() }
        runIsolated { s -> s.`head compensation is not applied twice through latency`() }
    }
}

private class FrameAudioDelayCases {
    // 48 kHz 双声道 16 bit：每帧 4 字节，每毫秒 192 字节。
    private fun delay() = FrameAudioDelay(48_000, 2, 2)

    fun `silence is inserted once before host data and is frame aligned`() {
        val delay = delay()
        delay.setTargetDelayUs(66_667L)
        val plan = delay.onWrite(4_096)
        assertEquals(0, plan.dropBytes)
        assertEquals(0, plan.silenceBytes % 4)
        assertEquals(12_800, plan.silenceBytes)
        assertEquals(Plan(0, 0), delay.onWrite(4_096))
        assertEquals(66_666L, delay.appliedDelayUs())
    }

    fun `lowering the target drops host bytes across writes`() {
        val delay = delay()
        delay.setTargetDelayUs(50_000L)
        delay.onWrite(1_024)
        delay.setTargetDelayUs(0L)
        assertEquals(Plan(0, 4_096), delay.onWrite(4_096))
        assertEquals(Plan(0, 4_096), delay.onWrite(4_096))
        assertEquals(Plan(0, 1_408), delay.onWrite(4_096))
        assertEquals(Plan(0, 0), delay.onWrite(4_096))
        assertEquals(0L, delay.appliedDelayUs())
    }

    fun `flush discards applied silence so it is inserted again`() {
        val delay = delay()
        delay.setTargetDelayUs(10_000L)
        assertEquals(1_920, delay.onWrite(100).silenceBytes)
        delay.onFlush()
        assertEquals(1_920, delay.onWrite(100).silenceBytes)
    }

    fun `invalid configuration and empty writes are rejected or ignored`() {
        assertThrows(IllegalArgumentException::class.java) { FrameAudioDelay(1_000, 2, 2) }
        val delay = delay()
        assertThrows(IllegalArgumentException::class.java) { delay.setTargetDelayUs(-1L) }
        delay.setTargetDelayUs(20_000L)
        assertEquals(Plan(0, 0), delay.onWrite(0))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FrameAudioDelayCases) -> Unit) {
            val s = FrameAudioDelayCases()
            block(s)
        }
        runIsolated { s -> s.`silence is inserted once before host data and is frame aligned`() }
        runIsolated { s -> s.`lowering the target drops host bytes across writes`() }
        runIsolated { s -> s.`flush discards applied silence so it is inserted again`() }
        runIsolated { s -> s.`invalid configuration and empty writes are rejected or ignored`() }
    }
}

private class FrameBufferLedgerAllocationCases {
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

    fun holderMetadataAllocationPerFrameLifecycle() {
        val ledger = FrameBufferLedger(maxBuffers = 4) { ByteBuffer.allocateDirect(it) }
        assertTrue(ledger.configure(16, 8))
        repeat(2_000) { cycle(ledger) } // 预热：缓冲进入空闲池，之后只剩账本元数据分配
        val rounds = 10_000
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

private class FrameCapabilityProbeCases {
    private class Call(val left: ByteBuffer, val right: ByteBuffer, val output: ByteBuffer,
                       val width: Int, val height: Int, val leftId: Long, val rightId: Long, val flowScale: Int)

    private class Harness(
        val statuses: (Int) -> Status = { Status.OK },
        val durationsMs: List<Double> = listOf(9.0, 5.0, 7.0, 3.0, 8.0)
    ) {
        var clock = 0L
        val calls = ArrayList<Call>()
        val nanoTimeReads = ArrayList<Long>()
        val inputsWereZero = ArrayList<Boolean>()
        private val frameBytes = 64 * 36 * 3
        val arena = FrameScratchArena(ByteBuffer.allocateDirect(FrameScratchArena.storageBytes(frameBytes, 4)), frameBytes, 4)
        val probe = FrameCapabilityProbe(arena, { nanoTimeReads += clock; clock }) { l, r, o, w, h, li, ri, fs ->
            val index = calls.size
            calls += Call(l, r, o, w, h, li, ri, fs)
            inputsWereZero += listOf(l, r).all { buffer ->
                (0 until w * h * 3).all { buffer.get(it).toInt() == 0 }
            }
            // 预运行（index 0）不计时；样本 i 的耗时由 durationsMs 给出。
            if (index in 1..durationsMs.size) clock += (durationsMs[index - 1] * 1e6).toLong()
            statuses(index)
        }
    }

    fun robustTimeIsTheSecondFastestOfFiveTimedSteps() {
        val harness = Harness()
        val result = harness.probe.measure(16, 8, 4) as FrameCapabilityProbe.Result.Measured
        assertEquals(5.0, result.robustMs, 1e-9)
        assertEquals(listOf(3.0, 5.0, 7.0, 8.0, 9.0), result.allMs)
        assertEquals("预运行 + 5 个样本", 6, harness.calls.size)
        // 计时只包住 process：5 个样本各读两次时钟。
        assertEquals(10, harness.nanoTimeReads.size)
    }

    fun inputsStayIndependentZeroedAndIdsFollowTheHistoricalSequence() {
        val harness = Harness()
        harness.probe.measure(16, 8, 6)
        val base = FrameInterpolationEngineHolder.CAPABILITY_ID_BASE + (16L * 65_536L + 8) * 16L + 6
        harness.calls.forEachIndexed { i, call ->
            assertEquals(base + i * 0x1_0000_0000L, call.leftId)
            assertEquals(base + (i + 1) * 0x1_0000_0000L, call.rightId)
            assertEquals(6, call.flowScale)
            assertNotSame("left/right distinct", call.left, call.right)
            assertNotSame("output distinct from inputs", call.output, call.left)
            assertNotSame(call.output, call.right)
            assertTrue(call.left.isDirect && call.left.position() == 0 && call.left.limit() >= 16 * 8 * 3)
        }
        // 四个输入轮转：第 i 步用 frames[i % 4] 与 frames[(i + 1) % 4]。
        val distinct = harness.calls.flatMap { listOf(it.left, it.right) }.distinctBy { System.identityHashCode(it) }
        assertEquals(4, distinct.size)
        assertSame(harness.calls[0].right, harness.calls[1].left)
        assertSame(harness.calls[4].left, harness.calls[0].left)
        assertTrue("所有输入在调用时为零内容", harness.inputsWereZero.all { it })
    }

    fun writesToThePreviousOutputCannotPolluteTheNextMeasurementsInputs() {
        val harness = Harness()
        harness.probe.measure(16, 8, 4)
        val buffer = harness.arena.output(16 * 8 * 3)
        while (buffer.hasRemaining()) buffer.put(0x55)
        harness.inputsWereZero.clear()
        harness.calls.clear()
        harness.probe.measure(16, 8, 4)
        assertTrue(harness.inputsWereZero.isNotEmpty() && harness.inputsWereZero.all { it })
    }

    fun preRunFailureEndsTheTierWithoutAnySample() {
        val harness = Harness(statuses = { if (it == 0) Status.INFERENCE_FAILED else Status.OK })
        val result = harness.probe.measure(16, 8, 4) as FrameCapabilityProbe.Result.Failed
        assertEquals(Status.INFERENCE_FAILED, result.status)
        assertEquals(0, result.step)
        assertEquals(1, harness.calls.size)
        assertTrue("失败不产生耗时样本", harness.nanoTimeReads.isEmpty())
    }

    fun anyTimedSampleFailureInvalidatesTheWholeTierEvenIfItReturnedFast() {
        for (failing in 1..5) {
            // 失败样本几乎瞬间返回（0.001 ms）：若被计入分布会把档位误判为很快。
            val durations = MutableList(5) { 20.0 }.also { it[failing - 1] = 0.001 }
            val harness = Harness(statuses = { if (it == failing) Status.BUSY else Status.OK }, durationsMs = durations)
            val result = harness.probe.measure(16, 8, 4)
            assertTrue("failing=$failing", result is FrameCapabilityProbe.Result.Failed)
            result as FrameCapabilityProbe.Result.Failed
            assertEquals(Status.BUSY, result.status)
            assertEquals(failing, result.step)
            assertEquals(failing + 1, harness.calls.size)
        }
    }

    fun oneArenaServesEveryTierAndSizeOfAProbeSession() {
        val harness = Harness()
        val hint = 64 * 36 * 3
        val probe = FrameCapabilityProbe(harness.arena, { harness.clock }) { l, r, o, w, h, li, ri, fs ->
            harness.calls += Call(l, r, o, w, h, li, ri, fs); Status.OK
        }
        for ((w, h, scale) in listOf(Triple(16, 9, 4), Triple(32, 18, 4), Triple(64, 36, 4), Triple(64, 36, 6), Triple(64, 36, 8))) {
            probe.measure(w, h, scale)
        }
        assertEquals(5 * hint, harness.arena.allocatedBytes)
        assertTrue(harness.calls.all { it.output === harness.calls.first().output })
        assertEquals(4, harness.calls.flatMap { listOf(it.left, it.right) }.distinctBy(System::identityHashCode).size)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FrameCapabilityProbeCases) -> Unit) {
            val s = FrameCapabilityProbeCases()
            block(s)
        }
        runIsolated { s -> s.robustTimeIsTheSecondFastestOfFiveTimedSteps() }
        runIsolated { s -> s.inputsStayIndependentZeroedAndIdsFollowTheHistoricalSequence() }
        runIsolated { s -> s.writesToThePreviousOutputCannotPolluteTheNextMeasurementsInputs() }
        runIsolated { s -> s.preRunFailureEndsTheTierWithoutAnySample() }
        runIsolated { s -> s.anyTimedSampleFailureInvalidatesTheWholeTierEvenIfItReturnedFast() }
        runIsolated { s -> s.oneArenaServesEveryTierAndSizeOfAProbeSession() }
    }
}

private class FrameHighRateTargetCases {
    fun `60 fps source retains the source frame needed for delayed 120 fps startup`() {
        fun timeline(window: Int): FrameTimeline.Decision {
            val t = FrameTimeline(120, presentationDelayNs = 100_000_000L, frameWindow = window, phaseLock = true)
            val epoch = FrameTimeline.Epoch(1, 1, 1, 1, 1)
            val lease = t.begin(epoch).lease
            repeat(16) { t.offer(lease, FrameTimeline.Frame(it.toLong(), it * 16_667L)) }
            t.updateClock(lease, FrameTimeline.ClockSnapshot(250_000L, 1_000_000_000L, 1.0, false, 1))
            return t.plan(lease, 1_000_000_000L)
        }
        assertTrue(timeline(4) is FrameTimeline.Decision.Waiting)
        assertTrue(timeline(8) is FrameTimeline.Decision.Ready)
    }
    fun `explicit 120 target is retained for integer source divisors`() {
        for (target in listOf(15, 30, 45, 50, 60, 120, 144, 165, 185))
            assertEquals(target, FrameCaptureBridge.targetFpsFor(30f, target))
        for (source in listOf(24f, 30f, 60f)) {
            assertEquals(120, FrameCaptureBridge.targetFpsFor(source, 120))
            val cadence = FrameSourceCadence(120)
            repeat(20) { cadence.add((it * 1_000_000.0 / source).toLong()) }
            assertEquals(FrameSourceCadence.Verdict.SUITABLE, cadence.verdict())
        }
        assertEquals(50, FrameCaptureBridge.targetFpsFor(25f))
        assertEquals(60, FrameCaptureBridge.targetFpsFor(30f))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FrameHighRateTargetCases) -> Unit) {
            val s = FrameHighRateTargetCases()
            block(s)
        }
        runIsolated { s -> s.`60 fps source retains the source frame needed for delayed 120 fps startup`() }
        runIsolated { s -> s.`explicit 120 target is retained for integer source divisors`() }
    }
}
