package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationBackend.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class FrameCapabilityProbeTest {
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
        val arena = FrameScratchArena()
        val probe = FrameCapabilityProbe(arena, 0, { nanoTimeReads += clock; clock }) { l, r, o, w, h, li, ri, fs ->
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

    @Test fun robustTimeIsTheSecondFastestOfFiveTimedSteps() {
        val harness = Harness()
        val result = harness.probe.measure(16, 8, 4) as FrameCapabilityProbe.Result.Measured
        assertEquals(5.0, result.robustMs, 1e-9)
        assertEquals(listOf(3.0, 5.0, 7.0, 8.0, 9.0), result.allMs)
        assertEquals("预运行 + 5 个样本", 6, harness.calls.size)
        // 计时只包住 process：5 个样本各读两次时钟。
        assertEquals(10, harness.nanoTimeReads.size)
    }

    @Test fun inputsStayIndependentZeroedAndIdsFollowTheHistoricalSequence() {
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

    @Test fun staleBytesInReusedBuffersAreClearedBeforeEachMeasurement() {
        val harness = Harness()
        harness.probe.measure(16, 8, 4)
        // 模拟上一次测量后缓冲里留有脏数据（输出会被引擎写入，输入也可能被其他用途污染）。
        for (slot in 0 until 5) {
            val buffer = harness.arena.scratch(slot, 16 * 8 * 3)
            for (i in 0 until 16 * 8 * 3) buffer.put(i, 0x55)
        }
        harness.inputsWereZero.clear()
        harness.calls.clear()
        harness.probe.measure(16, 8, 4)
        assertTrue(harness.inputsWereZero.isNotEmpty() && harness.inputsWereZero.all { it })
    }

    @Test fun preRunFailureEndsTheTierWithoutAnySample() {
        val harness = Harness(statuses = { if (it == 0) Status.INFERENCE_FAILED else Status.OK })
        val result = harness.probe.measure(16, 8, 4) as FrameCapabilityProbe.Result.Failed
        assertEquals(Status.INFERENCE_FAILED, result.status)
        assertEquals(0, result.step)
        assertEquals(1, harness.calls.size)
        assertTrue("失败不产生耗时样本", harness.nanoTimeReads.isEmpty())
    }

    @Test fun anyTimedSampleFailureInvalidatesTheWholeTierEvenIfItReturnedFast() {
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

    @Test fun oneArenaServesEveryTierAndSizeOfAProbeSession() {
        val harness = Harness()
        val hint = 64 * 36 * 3
        val probe = FrameCapabilityProbe(harness.arena, hint, { harness.clock }) { l, r, o, w, h, li, ri, fs ->
            harness.calls += Call(l, r, o, w, h, li, ri, fs); Status.OK
        }
        for ((w, h, scale) in listOf(Triple(16, 9, 4), Triple(32, 18, 4), Triple(64, 36, 4), Triple(64, 36, 6), Triple(64, 36, 8))) {
            probe.measure(w, h, scale)
        }
        // 旧实现每次测量 5 次 allocateDirect（共 25 次）；会话内 5 个槽只分配一次。
        assertEquals(5, harness.arena.allocations)
        assertEquals(5L * hint, harness.arena.allocatedBytes)
    }
}
