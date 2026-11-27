package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameBufferLedger.Holder
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameHostClock.Update
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameHostMediaSignals.Values
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationBackend.Status
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationEntryGate.Condition
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationEntryGate.Evidence
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationSession.Outcome
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationSession.Phase
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Reason
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FramePresentationRecovery.State
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.ClockSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.ClockState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.Epoch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class FrameHostClockTest {
    private val periodUs = 33_333L

    /** 第 i 帧 PTS = i·33.333 ms，理想在 base + PTS 到达，另加 jitter(i) 的调度延迟。 */
    private fun FrameHostClock.feedFrames(from: Int, to: Int, baseNs: Long, jitterUs: (Int) -> Long = { 0L }) {
        for (i in from until to) assertEquals(Update.ACCEPTED, feed(i * periodUs, baseNs + (i * periodUs + jitterUs(i)) * 1_000L))
    }

    fun `needs a few samples before reporting a clock`() {
        val clock = FrameHostClock()
        clock.feedFrames(0, 11, 0L)
        assertNull(clock.snapshot(500_000_000L, 1L))
        clock.feedFrames(11, 12, 0L)
        assertNotNull(clock.snapshot(500_000_000L, 1L))
    }

    /**
     * 宿主进程的典型到达：普遍迟到 2–12 ms，另有约 10% 迟到 20–30 ms。真实映射已知（base + PTS）。
     * 估计误差应只有调度延迟的下限量级；旧的「最大值 − 中位数」会超过 8 ms 门限，新的误差上界不会。
     */
    fun `heavy tailed lateness keeps an accurate estimate and a bound under the gate`() {
        val random = java.util.Random(7)
        // 尾部到 30 ms：真实解码器按顺序交付，迟到不会超过一个帧间隔而让后一帧先到。
        val lateness = LongArray(400) { if (random.nextInt(10) == 0) 20_000L + random.nextInt(10_000) else 2_000L + random.nextInt(10_000) }
        val base = 1_000_000_000L
        var worstError = 0L
        var worstBound = 0L
        for (i in 40 until 400) {
            val fresh = FrameHostClock()
            fresh.feedFrames(i - 32, i, base) { lateness[it] }
            val now = base + i * periodUs * 1_000L
            val snapshot = fresh.snapshot(now, 1L)!!
            // 误差恰为窗口内最小迟到量（调度延迟下限），而非典型迟到量。
            val floor = (i - 32 until i).minOf { lateness[it] }
            assertTrue("estimate error at $i", kotlin.math.abs(snapshot.mediaUs - i * periodUs) <= floor + 100L)
            worstError = maxOf(worstError, kotlin.math.abs(snapshot.mediaUs - i * periodUs))
            worstBound = maxOf(worstBound, snapshot.uncertaintyUs)
        }
        assertTrue("estimate error $worstError", worstError <= 4_000L)
        assertTrue("bound $worstBound must stay under the 8 ms gate", worstBound <= 8_000L)
    }

    fun `a start up burst publishes nothing until the steady cadence has enough samples`() {
        val clock = FrameHostClock()
        // 开播突发：前 10 帧每 5 ms 释放一帧（相对 PTS 节奏越来越早），之后按 33.3 ms 节奏、带 2–6 ms 调度延迟。
        val base = 2_000_000_000L
        var arrival = base
        for (i in 0 until 10) { assertNotEquals(Update.REJECTED, clock.feed(i * periodUs, arrival)); arrival += 5_000_000L }
        // 突发后的稳态：映射 = 第 10 帧到达时刻。
        val steadyBase = arrival - 10 * periodUs * 1_000L
        for (i in 10 until 21) {
            assertNotEquals(Update.REJECTED, clock.feed(i * periodUs, steadyBase + (i * periodUs + 2_000L + (i % 3) * 2_000L) * 1_000L))
            assertNull("no snapshot from burst samples (frame $i)", clock.snapshot(steadyBase + (i * periodUs + 8_000L) * 1_000L, 1L))
        }
        clock.feed(21 * periodUs, steadyBase + (21 * periodUs + 2_000L) * 1_000L)
        val snapshot = clock.snapshot(steadyBase + (22 * periodUs) * 1_000L, 1L)!!
        assertTrue("accurate after the burst: ${snapshot.mediaUs}", kotlin.math.abs(snapshot.mediaUs - 22 * periodUs) <= 2_100L)
        assertTrue("bound under the gate: ${snapshot.uncertaintyUs}", snapshot.uncertaintyUs <= 8_000L)
    }

    fun `an isolated early sample is not trusted until others confirm it`() {
        val clock = FrameHostClock()
        clock.feedFrames(0, 20, 0L) { 6_000L }
        clock.feed(20 * periodUs, 20 * periodUs * 1_000L - 4_000_000L)
        // 只有一个比其余早 10 ms 的样本：下包络未被确认，误差上界至少反映这 10 ms。
        assertTrue(clock.snapshot(800_000_000L, 1L)!!.uncertaintyUs >= 9_000L)
    }

    fun `late arrivals do not drag the clock and jitter becomes the uncertainty`() {
        val clock = FrameHostClock()
        // 交替 0 / 3 ms 的调度延迟：最早到达的样本即真实映射。
        clock.feedFrames(0, 32, 5_000_000_000L) { if (it % 2 == 0) 0L else 3_000L }
        val now = 5_000_000_000L + 1_100_000_000L
        val snapshot = clock.snapshot(now, 7L)!!
        assertEquals(1_100_000L, snapshot.mediaUs)
        assertEquals(7L, snapshot.discontinuity)
        assertEquals(1.0, snapshot.speed, 0.0)
        assertTrue(snapshot.uncertaintyUs in 500L..3_000L)
    }

    fun `a better sample moves the estimate gradually and a large offset jumps at once`() {
        val clock = FrameHostClock()
        clock.feedFrames(0, 12, 0L) { 3_000L }
        val first = clock.snapshot(500_000_000L, 1L)!!
        // 一帧准时到达：下包络提前 3 ms，但每次快照最多移动 0.2 ms。
        clock.feed(12 * periodUs, 12 * periodUs * 1_000L)
        val second = clock.snapshot(500_000_000L, 1L)!!
        assertEquals(200L, second.mediaUs - first.mediaUs)
        assertTrue(second.uncertaintyUs >= 2_800L)
        // 大偏差经 envelopeRank 个样本确认后一次跳过去。
        val slow = FrameHostClock(jumpUs = 2_000L)
        slow.feedFrames(0, 12, 0L) { 3_000L }
        val before = slow.snapshot(500_000_000L, 1L)!!
        for (i in 12 until 16) slow.feed(i * periodUs, i * periodUs * 1_000L)
        assertEquals(3_000L, slow.snapshot(800_000_000L, 1L)!!.mediaUs - before.mediaUs - 300_000L)
    }

    fun `seek back, jump forward and resume after pause are discontinuities`() {
        val clock = FrameHostClock()
        clock.feedFrames(0, 10, 0L)
        assertEquals(Update.DISCONTINUITY, clock.feed(2 * periodUs, 400_000_000L))
        clock.feedFrames(3, 10, 400_000_000L - 2 * periodUs * 1_000L)
        // 同一 PTS 序列继续，但墙钟停了 2 s（暂停后恢复）。
        assertEquals(Update.DISCONTINUITY, clock.feed(10 * periodUs, 400_000_000L + 10 * periodUs * 1_000L + 2_000_000_000L))
        assertNull(clock.snapshot(3_000_000_000L, 1L))
    }

    fun `speed change restarts and the clock advances at the given speed`() {
        val clock = FrameHostClock()
        clock.feedFrames(0, 8, 0L)
        val doubled = (8 until 20).map { clock.feed(it * periodUs, 8 * periodUs * 1_000L + (it - 8) * periodUs * 500L, 2.0) }
        assertEquals(Update.DISCONTINUITY, doubled.first())
        assertTrue(doubled.drop(1).all { it == Update.ACCEPTED })
        val lastArrival = 8 * periodUs * 1_000L + 11 * periodUs * 500L
        val snapshot = clock.snapshot(lastArrival + 10_000_000L, 1L)!!
        assertEquals(19 * periodUs + 20_000L, snapshot.mediaUs)
        assertEquals(2.0, snapshot.speed, 0.0)
    }

    /** 宿主真机：GPU 忙时解码输出整体晚一帧并保持；已信任映射不变、误差上界一直在门限内。 */
    fun `a stable one frame later shift keeps the trusted mapping under the gate`() {
        val clock = FrameHostClock()
        val base = 3_000_000_000L
        clock.feedFrames(0, 32, base) { 2_000L }
        val locked = clock.snapshot(base + 32 * periodUs * 1_000L, 1L)!!
        for (i in 32 until 120) {
            clock.feed(i * periodUs, base + (i * periodUs + 35_400L + (i % 3) * 300L) * 1_000L)
            val now = base + (i * periodUs + 40_000L) * 1_000L
            val snapshot = clock.snapshot(now, 1L)!!
            assertTrue("bound at $i: ${snapshot.uncertaintyUs}", snapshot.uncertaintyUs <= 8_000L)
            // 映射不随晚到移动：媒体时间仍按锁定时的映射推进。
            assertEquals("held at $i", (locked.mediaUs + (now - (base + 32 * periodUs * 1_000L)) / 1_000L).toDouble(), snapshot.mediaUs.toDouble(), 1.0)
        }
    }

    fun `initial lock follows the steady regime when only the first frames were early`() {
        val clock = FrameHostClock()
        clock.feedFrames(0, 3, 0L)
        clock.feedFrames(3, 12, 0L) { 33_400L + (it % 2) * 200L }
        val snapshot = clock.snapshot((12 * periodUs + 40_000L) * 1_000L, 1L)!!
        assertTrue("bound ${snapshot.uncertaintyUs}", snapshot.uncertaintyUs <= 8_000L)
        assertEquals((12 * periodUs + 40_000L - 33_400L).toDouble(), snapshot.mediaUs.toDouble(), 300.0)
    }

    fun `a later shift beyond the hold limit moves to the new mapping`() {
        val clock = FrameHostClock()
        clock.feedFrames(0, 32, 0L)
        clock.snapshot(32 * periodUs * 1_000L, 1L)
        clock.feedFrames(32, 72, 0L) { 120_000L }
        val now = (72 * periodUs + 130_000L) * 1_000L
        assertEquals(72 * periodUs + 130_000L - 120_000L, clock.snapshot(now, 1L)!!.mediaUs)
    }

    /** 宿主锯齿：逐步晚 33、67 ms，再丢帧追回原映射；整个过程映射保持、误差上界不越线。 */
    fun `a lateness sawtooth up to two frames is held without rejection`() {
        val clock = FrameHostClock()
        val base = 4_000_000_000L
        clock.feedFrames(0, 32, base) { 1_000L }
        val locked = clock.snapshot(base + (32 * periodUs + 2_000L) * 1_000L, 1L)!!
        val lateness = { i: Int -> 1_000L + ((i - 32) / 40 % 3) * 33_400L }
        for (i in 32 until 300) {
            clock.feed(i * periodUs, base + (i * periodUs + lateness(i)) * 1_000L)
            val now = base + (i * periodUs + 70_000L) * 1_000L
            val snapshot = clock.snapshot(now, 1L)!!
            assertTrue("bound at $i: ${snapshot.uncertaintyUs}", snapshot.uncertaintyUs <= 8_000L)
            val expected = locked.mediaUs + (now - (base + (32 * periodUs + 2_000L) * 1_000L)) / 1_000L
            assertTrue("held at $i: ${snapshot.mediaUs - expected}", kotlin.math.abs(snapshot.mediaUs - expected) <= 300L)
        }
    }

    /** 宿主真机：保持在比晚档早 30 ms 的映射时，一个样本部分追回（比晚档早 13 ms）不能把误差上界推过门限。 */
    fun `a partly returning sample while holding stays under the gate`() {
        val clock = FrameHostClock()
        clock.feedFrames(0, 32, 0L)
        val locked = clock.snapshot((32 * periodUs + 1_000L) * 1_000L, 1L)!!
        clock.feedFrames(32, 64, 0L) { 30_000L + (it % 2) * 200L }
        clock.feed(64 * periodUs, (64 * periodUs + 17_000L) * 1_000L)
        val now = (64 * periodUs + 40_000L) * 1_000L
        val snapshot = clock.snapshot(now, 1L)!!
        assertTrue("bound ${snapshot.uncertaintyUs}", snapshot.uncertaintyUs <= 1_000L)
        assertEquals(locked.mediaUs + (now / 1_000L - (32 * periodUs + 1_000L)), snapshot.mediaUs)
    }

    /**
     * 宿主真机窗口：锁定在晚一档（33 ms）后 ijk 丢帧追回，一个过渡样本（早 22 ms）后三个样本回到原映射。
     * 无论映射锁在哪一档，过渡样本都不能把误差上界推过门限。
     */
    fun `a catch up with one in between sample stays under the gate`() {
        for (lockedLate in listOf(true, false)) {
            val clock = FrameHostClock()
            val late = 33_300L
            clock.feedFrames(0, 32, 0L) { if (lockedLate) late + (it % 3) * 100L else (it % 3) * 100L }
            clock.snapshot((32 * periodUs + 40_000L) * 1_000L, 1L)!!
            if (!lockedLate) clock.feedFrames(32, 60, 0L) { late + (it % 3) * 100L }
            val from = if (lockedLate) 32 else 60
            clock.feed(from * periodUs, (from * periodUs + late - 22_000L) * 1_000L)
            for (i in from + 1 until from + 4) {
                clock.feed(i * periodUs, (i * periodUs + 200L) * 1_000L)
                // 锁在晚档时早 33 ms 触发突发重置：窗口重新积累期间不发布快照（不会被拒）。
                val snapshot = clock.snapshot((i * periodUs + 40_000L) * 1_000L, 1L) ?: continue
                assertTrue("lockedLate=$lockedLate bound at $i: ${snapshot.uncertaintyUs}", snapshot.uncertaintyUs <= 8_000L)
            }
        }
    }

    fun `after lock an unconfirmed early sample is ignored`() {
        val clock = FrameHostClock()
        clock.feedFrames(0, 32, 0L) { 10_000L + (it % 2) * 300L }
        val before = clock.snapshot((32 * periodUs + 12_000L) * 1_000L, 1L)!!
        clock.feed(32 * periodUs, 32 * periodUs * 1_000L)
        val after = clock.snapshot((32 * periodUs + 12_000L) * 1_000L, 1L)!!
        assertEquals(before.mediaUs, after.mediaUs)
        assertTrue("bound ${after.uncertaintyUs}", after.uncertaintyUs <= 1_000L)
    }

    fun `describe lists samples in arrival order relative to the best`() {
        val clock = FrameHostClock()
        assertEquals("[]", clock.describe())
        clock.feedFrames(0, 3, 0L) { if (it == 1) 2_000L else 0L }
        assertEquals("[0.0/0,-2.0/33,0.0/33]", clock.describe())
    }

    fun `invalid input and time going backwards are rejected`() {
        val clock = FrameHostClock()
        assertEquals(Update.REJECTED, clock.feed(-1L, 0L))
        assertEquals(Update.REJECTED, clock.feed(0L, 0L, 0.0))
        clock.feedFrames(0, 5, 1_000_000_000L)
        assertEquals(Update.REJECTED, clock.feed(5 * periodUs, 0L))
        assertNull(clock.snapshot(0L, 1L))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FrameHostClockTest) -> Unit) {
            val s = FrameHostClockTest()
            block(s)
        }
        runIsolated { s -> s.`needs a few samples before reporting a clock`() }
        runIsolated { s -> s.`heavy tailed lateness keeps an accurate estimate and a bound under the gate`() }
        runIsolated { s -> s.`a start up burst publishes nothing until the steady cadence has enough samples`() }
        runIsolated { s -> s.`an isolated early sample is not trusted until others confirm it`() }
        runIsolated { s -> s.`late arrivals do not drag the clock and jitter becomes the uncertainty`() }
        runIsolated { s -> s.`a better sample moves the estimate gradually and a large offset jumps at once`() }
        runIsolated { s -> s.`seek back, jump forward and resume after pause are discontinuities`() }
        runIsolated { s -> s.`speed change restarts and the clock advances at the given speed`() }
        runIsolated { s -> s.`a stable one frame later shift keeps the trusted mapping under the gate`() }
        runIsolated { s -> s.`initial lock follows the steady regime when only the first frames were early`() }
        runIsolated { s -> s.`a later shift beyond the hold limit moves to the new mapping`() }
        runIsolated { s -> s.`a lateness sawtooth up to two frames is held without rejection`() }
        runIsolated { s -> s.`a partly returning sample while holding stays under the gate`() }
        runIsolated { s -> s.`a catch up with one in between sample stays under the gate`() }
        runIsolated { s -> s.`after lock an unconfirmed early sample is ignored`() }
        runIsolated { s -> s.`describe lists samples in arrival order relative to the best`() }
        runIsolated { s -> s.`invalid input and time going backwards are rejected`() }
    
        FrameHostMediaSignalsCases().verifyAllCases()
        FrameInterpolationEntryGateCases().verifyAllCases()
        FrameInterpolationSessionCases().verifyAllCases()
        FrameModelCacheCases().verifyAllCases()
        FramePendingTakeoversCases().verifyAllCases()
    }
}

private class FrameHostMediaSignalsCases {
    private val sdr709 = Values("video/avc", colorStandard = 1, colorTransfer = 3, colorRange = 2)

    private fun decision(source: Values, decoded: Values, capture: FrameMediaPolicy.Capture =
        FrameMediaPolicy.Capture.VERIFIED_SDR_RGB8) = FrameMediaPolicy.evaluate(FrameMediaPolicy.Snapshot(
            1280, 720, FrameHostMediaSignals.signal(source.fillFrom(decoded)), FrameHostMediaSignals.signal(decoded, 8),
            capture, FrameMediaPolicy.Evidence.NO, FrameMediaPolicy.Evidence.NO, FrameMediaPolicy.Evidence.NO,
            FrameMediaPolicy.Audio("audio/mp4a-latm", FrameMediaPolicy.AudioOutput.PCM,
                FrameMediaPolicy.Evidence.NO, FrameMediaPolicy.Evidence.NO)))

    fun `sdr avc with color keys only in the decoder output is admitted`() {
        assertEquals(Reason.READY, decision(Values("video/avc"), sdr709).reason)
    }

    fun `hdr evidence in either layer is kept and rejected`() {
        assertEquals(Reason.HDR_PQ, decision(sdr709.copy(colorTransfer = 6), sdr709).reason)
        assertEquals(Reason.HDR_METADATA, decision(Values("video/avc", hdrStaticInfo = true), sdr709).reason)
        assertEquals(Reason.HDR_DYNAMIC, decision(sdr709, sdr709.copy(hdr10PlusInfo = true)).reason)
        assertEquals(Reason.WIDE_COLOR, decision(sdr709, sdr709.copy(colorStandard = 6)).reason)
    }

    fun `missing color keys in both layers or unknown bit depth are rejected`() {
        assertEquals(Reason.UNKNOWN_COLOR, decision(Values("video/avc"), Values("video/avc")).reason)
        val hevc = sdr709.copy(mime = "video/hevc")
        assertEquals(Reason.UNKNOWN_BIT_DEPTH, decision(hevc, hevc).reason)
        assertEquals(Reason.READY, decision(hevc.copy(profile = 1), hevc).reason)
        assertEquals(Reason.UNSUPPORTED_BIT_DEPTH, decision(hevc.copy(profile = 2), hevc).reason)
    }

    fun `all zero or empty hdr metadata reported for sdr is not hdr evidence`() {
        assertFalse(FrameHostMediaSignals.hdrMetadataPresent(null))
        assertFalse(FrameHostMediaSignals.hdrMetadataPresent(ByteArray(0)))
        assertFalse(FrameHostMediaSignals.hdrMetadataPresent(ByteArray(25)))
        assertTrue(FrameHostMediaSignals.hdrMetadataPresent(ByteArray(25).also { it[3] = 0x10 }))
    }

    fun `only bt601 and bt709 have a verified converter`() {
        assertEquals(0, FrameHostMediaSignals.converterStandard(FrameMediaPolicy.Standard.BT601))
        assertEquals(1, FrameHostMediaSignals.converterStandard(FrameMediaPolicy.Standard.BT709))
        assertNull(FrameHostMediaSignals.converterStandard(FrameMediaPolicy.Standard.BT2020))
        assertNull(FrameHostMediaSignals.converterStandard(FrameMediaPolicy.Standard.UNKNOWN))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FrameHostMediaSignalsCases) -> Unit) {
            val s = FrameHostMediaSignalsCases()
            block(s)
        }
        runIsolated { s -> s.`sdr avc with color keys only in the decoder output is admitted`() }
        runIsolated { s -> s.`hdr evidence in either layer is kept and rejected`() }
        runIsolated { s -> s.`missing color keys in both layers or unknown bit depth are rejected`() }
        runIsolated { s -> s.`all zero or empty hdr metadata reported for sdr is not hdr evidence`() }
        runIsolated { s -> s.`only bt601 and bt709 have a verified converter`() }
    }
}

private class FrameInterpolationEntryGateCases {
    private val complete = Evidence(true, true, true, true, true, true, true, true, true)

    fun `entry opens only when every condition is evidenced`() {
        assertTrue(FrameInterpolationEntryGate.evaluate(complete).open)
        val missing = listOf(
            complete.copy(nativeReady = false) to Condition.NATIVE_READY,
            complete.copy(modelVerified = false) to Condition.MODEL_VERIFIED,
            complete.copy(mediaPermit = false) to Condition.MEDIA_PERMIT,
            complete.copy(verifiedClock = false) to Condition.VERIFIED_CLOCK,
            complete.copy(verifiedCapture = false) to Condition.VERIFIED_CAPTURE,
            complete.copy(presentationOwned = false) to Condition.PRESENTATION_OWNED,
            complete.copy(ownershipAndRecovery = false) to Condition.OWNERSHIP_AND_RECOVERY,
            complete.copy(inferenceWithinBudget = false) to Condition.INFERENCE_WITHIN_BUDGET,
            complete.copy(deviceValidated = false) to Condition.DEVICE_VALIDATED)
        missing.forEach { (evidence, condition) ->
            val verdict = FrameInterpolationEntryGate.evaluate(evidence)
            assertFalse(verdict.open)
            assertEquals(listOf(condition), verdict.missing)
        }
    }

    fun `default evidence carries only what this repository proves`() {
        val verdict = FrameInterpolationEntryGate.evaluate(Evidence())
        assertFalse(Condition.OWNERSHIP_AND_RECOVERY in verdict.missing)
        assertFalse(Condition.DEVICE_VALIDATED in verdict.missing)
        assertTrue(verdict.missing.containsAll(FrameInterpolationEntryGate.RUNTIME_ENFORCED))
    }

    fun `production entry leaves only runtime enforced conditions to playback`() {
        val verdict = FrameInterpolationEntryGate.production(nativeReady = false)
        assertFalse(verdict.open)
        assertTrue(FrameInterpolationEntryGate.entryOpen(nativeReady = false))
        assertEquals(FrameInterpolationEntryGate.RUNTIME_ENFORCED, verdict.missing.toSet())
        assertEquals(setOf(Condition.NATIVE_READY, Condition.MODEL_VERIFIED, Condition.MEDIA_PERMIT,
            Condition.INFERENCE_WITHIN_BUDGET), FrameInterpolationEntryGate.RUNTIME_ENFORCED)
        val ready = FrameInterpolationEntryGate.production(nativeReady = true)
        assertEquals(FrameInterpolationEntryGate.RUNTIME_ENFORCED - Condition.NATIVE_READY, ready.missing.toSet())
        assertTrue(FrameInterpolationEntryGate.entryOpen(nativeReady = true))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FrameInterpolationEntryGateCases) -> Unit) {
            val s = FrameInterpolationEntryGateCases()
            block(s)
        }
        runIsolated { s -> s.`entry opens only when every condition is evidenced`() }
        runIsolated { s -> s.`default evidence carries only what this repository proves`() }
        runIsolated { s -> s.`production entry leaves only runtime enforced conditions to playback`() }
    }
}

private class FrameInterpolationSessionCases {
    private val slot1 = 16_666_666L
    private var now = 0L
    private val tasks = ArrayDeque<() -> Unit>()
    private var acceptWork = true

    private class Synth : FrameInterpolationSession.Synthesizer {
        var status = Status.OK
        var calls = 0
        var closed = 0
        override fun process(left: ByteBuffer, right: ByteBuffer, output: ByteBuffer,
                             format: FrameBufferLedger.Format, alpha: Float, leftId: Long, rightId: Long): Status {
            calls++
            output.put(0, 7)
            return status
        }
        override fun close() { closed++ }
    }

    private class Shown(val id: Long, val dueNs: Long, val buffer: ByteBuffer, val done: (Outcome) -> Unit)
    private class Screen : FrameInterpolationSession.Presenter {
        val shown = ArrayList<Shown>()
        val restores = ArrayList<FramePresentationRecovery.Report>()
        var accept = true
        override fun present(frameId: Long, buffer: ByteBuffer, format: FrameBufferLedger.Format, dueNs: Long,
                             done: (Outcome) -> Unit): Boolean {
            if (accept) shown += Shown(frameId, dueNs, buffer, done)
            return accept
        }
        override fun restoreNative(report: FramePresentationRecovery.Report) { restores += report }
    }

    private val synth = Synth()
    private val screen = Screen()
    private val recovery = FramePresentationRecovery(window = 20)
    /** 默认 1 ms 提前量：每个节拍只能规划当期槽位，单个场景的持有关系可逐步断言；连续规划另有专测。 */
    private fun session(ledger: FrameBufferLedger = FrameBufferLedger(6) { ByteBuffer.allocate(it) },
                        leadNs: Long = 1_000_000L) =
        FrameInterpolationSession(60, ledger, synth, { if (acceptWork) { tasks += it; true } else false },
            screen, { now }, recovery, timeline = FrameTimeline(60, maxLeadNs = leadNs))
    private fun epoch(id: Long) = Epoch(id, 1L, 1L, 1L, 1L)
    private fun clock(paused: Boolean = false) = ClockSnapshot(0L, 0L, 1.0, paused, 1L)
    private fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst()() }
    private fun FrameInterpolationSession.capture(pts: Long, marker: Byte) =
        capture(pts) { buffer, _ -> buffer.put(0, marker); true }

    /** 30 fps 源（0、33.333 ms）→ 60 fps：第 0 槽为原帧，第 1 槽需要插帧。 */
    private fun playing(ledger: FrameBufferLedger = FrameBufferLedger(6) { ByteBuffer.allocate(it) }):
        Triple<FrameInterpolationSession, FrameBufferLedger, Pair<Long, Long>> {
        val session = session(ledger)
        assertTrue(session.begin(epoch(1L), 4, 2))
        assertEquals(ClockState.READY, session.updateClock(clock()))
        val first = session.capture(0L, 1)!!
        val second = session.capture(33_333L, 2)!!
        return Triple(session, ledger, first to second)
    }

    fun `source duplicates are kept out of the timeline at most twice in a row and do not leak buffers`() {
        val ledger = FrameBufferLedger(8) { ByteBuffer.allocate(it) }
        // 标记相同即视为重复（模拟一拍二/下拉产生的重复画面）。
        val session = FrameInterpolationSession(60, ledger, synth, { tasks += it; true }, screen, { now }, recovery,
            timeline = FrameTimeline(60, maxLeadNs = 1_000_000L),
            duplicate = { previous, current, _ -> previous.get(0) == current.get(0) })
        assertTrue(session.begin(epoch(1L), 4, 2))
        assertEquals(ClockState.READY, session.updateClock(clock()))
        assertNotNull(session.capture(0L, 1))
        assertNull("first duplicate dropped", session.capture(33_333L, 1))
        assertNull("second duplicate dropped", session.capture(66_667L, 1))
        assertNotNull("a third identical frame is kept so still scenes keep advancing", session.capture(100_000L, 1))
        assertNotNull("new drawing accepted", session.capture(133_333L, 2))
        assertNull(session.capture(166_667L, 2))
        assertEquals(3L, session.snapshot().deduplicated)
        // 丢弃的重复帧只持有过 CAPTURE，已全部归还：活跃缓冲只剩时间线里的 3 帧。
        assertEquals(3, ledger.outstanding(Holder.TIMELINE).size)
        assertTrue(ledger.outstanding(Holder.CAPTURE).isEmpty())
        // 新 epoch 重置去重状态：首帧不会与上一 epoch 的帧比较。
        assertTrue(session.begin(epoch(2L), 4, 2))
        assertEquals(ClockState.READY, session.updateClock(clock()))
        assertNotNull(session.capture(0L, 2))
    }

    fun `original then interpolated frame are presented and every buffer returns to the pool`() {
        val (session, ledger, ids) = playing()
        session.tick(0L)
        assertEquals(ids.first, screen.shown.single().id)
        assertEquals(1.toByte(), screen.shown.single().buffer.get(0))

        now = slot1
        session.tick(now)
        assertEquals(1, tasks.size)
        assertEquals(setOf(Holder.TIMELINE, Holder.WORKER, Holder.PRESENTER), ledger.holders(ids.first))
        drain()
        assertEquals(1, synth.calls)
        val interpolated = screen.shown[1]
        assertEquals(slot1, interpolated.dueNs)
        assertEquals(7.toByte(), interpolated.buffer.get(0))
        assertEquals(setOf(Holder.PRESENTER), ledger.holders(interpolated.id))
        assertFalse(Holder.WORKER in ledger.holders(ids.first))

        screen.shown.forEach { it.done(Outcome.Presented(it.dueNs)) }
        assertTrue(ledger.holders(interpolated.id).isEmpty())
        assertEquals(State.INTERPOLATING, session.snapshot().recovery.state)
        session.close()
        drain()
        assertTrue(ledger.drained())
        assertEquals(1, synth.closed)
    }

    fun `teardown cannot recycle the previous frame while duplicate comparison is reading it`() {
        val ledger = FrameBufferLedger(3) { ByteBuffer.allocate(it) }
        val comparing = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val session = FrameInterpolationSession(60, ledger, synth, { tasks += it; true }, screen, { now },
            duplicate = { previous, _, _ ->
                comparing.countDown()
                check(resume.await(5, java.util.concurrent.TimeUnit.SECONDS))
                if (previous.get(0) != 1.toByte()) failure.set(AssertionError("previous pixels changed during teardown"))
                false
            })
        assertTrue(session.begin(epoch(1L), 4, 2))
        val previous = session.capture(0L, 1)!!
        val capturing = Thread {
            try { assertNull(session.capture(33_333L, 2)) } catch (t: Throwable) { failure.set(t) }
        }
        capturing.start()
        try {
            assertTrue(comparing.await(5, java.util.concurrent.TimeUnit.SECONDS))
            session.close()
            assertNotNull("comparison needs a hold independent of the revoked timeline", ledger.format(previous))
            assertFalse(ledger.drained())
        } finally {
            resume.countDown()
            capturing.join(5_000)
        }
        assertFalse(capturing.isAlive)
        failure.get()?.let { throw AssertionError("capture failed", it) }
        drain()
        assertTrue(ledger.drained())
    }

    fun `seek during duplicate comparison cannot reuse its pixels or admit its stale capture`() {
        val ledger = FrameBufferLedger(3) { ByteBuffer.allocate(it) }
        lateinit var session: FrameInterpolationSession
        var comparisons = 0
        var restarted = false
        var nextFrame: Long? = null
        var liveDuringComparison = 0
        var previousPixel: Byte? = null
        session = FrameInterpolationSession(60, ledger, synth, { tasks += it; true }, screen, { now },
            duplicate = { previous, _, _ ->
                comparisons++
                restarted = session.begin(epoch(2L), 4, 2)
                nextFrame = session.capture(0L, 9)
                liveDuringComparison = ledger.stats().live
                previousPixel = previous.get(0)
                false
            })
        assertTrue(session.begin(epoch(1L), 4, 2))
        assertNotNull(session.capture(0L, 1))
        assertNull(session.capture(33_333L, 2))
        assertEquals(1, comparisons)
        assertTrue(restarted)
        assertNotNull(nextFrame)
        assertEquals("old frame is still pinned beside the new capture", 3, liveDuringComparison)
        assertEquals(1.toByte(), previousPixel)
        assertEquals(1, ledger.stats().live)
        assertTrue(ledger.outstanding(Holder.COMPARISON).isEmpty())
        session.close()
        drain()
        assertTrue(ledger.drained())
    }

    fun `duplicate comparator failure releases its hold and accepts the source frame`() {
        val ledger = FrameBufferLedger(3) { ByteBuffer.allocate(it) }
        val session = FrameInterpolationSession(60, ledger, synth, { tasks += it; true }, screen, { now },
            duplicate = { _, _, _ -> throw IllegalStateException("comparison failed") })
        assertTrue(session.begin(epoch(1L), 4, 2))
        assertNotNull(session.capture(0L, 1))
        assertNotNull(session.capture(33_333L, 2))
        assertTrue(ledger.outstanding(Holder.COMPARISON).isEmpty())
        assertEquals(0L, session.snapshot().deduplicated)
        session.close()
        drain()
        assertTrue(ledger.drained())
    }

    fun `seek during inference discards the stale result and releases all its holds`() {
        val (session, ledger, ids) = playing()
        session.tick(0L)
        now = slot1
        session.tick(now)
        assertTrue(session.begin(epoch(2L), 4, 2))
        assertEquals(setOf(Holder.PRESENTER, Holder.WORKER), ledger.holders(ids.first))
        assertEquals(setOf(Holder.WORKER), ledger.holders(ids.second))
        drain()
        assertEquals(1, screen.shown.size)
        assertTrue(ledger.holders(ids.second).isEmpty())
        screen.shown.single().done(Outcome.Presented(0L))
        assertEquals(0, ledger.stats().live)
        assertEquals(0, session.snapshot().recovery.samples)
    }

    fun `teardown defers synthesizer close until the in flight task ends and drains after presenter returns`() {
        val (session, ledger, ids) = playing()
        session.tick(0L)
        now = slot1
        session.tick(now)
        assertEquals(listOf(ids.first), session.close())
        assertEquals(0, synth.closed)
        assertNull(session.capture(66_666L, 3))
        assertFalse(session.begin(epoch(2L), 4, 2))
        drain()
        assertEquals(1, synth.closed)
        assertFalse(ledger.drained())
        screen.shown.single().done(Outcome.Dropped(now))
        assertTrue(ledger.drained())
        assertEquals(Phase.CLOSED, session.snapshot().phase)
    }

    fun `frames superseded by a slower panel return their buffers without counting as failures`() {
        val (session, ledger, _) = playing()
        session.tick(0L)
        now = slot1
        session.tick(now)
        drain()
        val before = session.snapshot().recovery
        screen.shown.forEach { it.done(Outcome.Superseded(now)) }
        val after = session.snapshot()
        assertEquals(State.INTERPOLATING, after.recovery.state)
        assertEquals(before.samples, after.recovery.samples)
        assertEquals(0, after.recovery.failures)
        assertTrue(screen.restores.isEmpty())
        assertTrue(ledger.outstanding(Holder.PRESENTER).isEmpty())
        session.close()
        drain()
        assertTrue(ledger.drained())
    }

    fun `surface loss restores native once, returns queued frames and retries on a later surface epoch`() {
        val (session, ledger, _) = playing()
        session.tick(0L)
        session.onSurfaceLost(0L)
        assertEquals(Phase.NATIVE, session.snapshot().phase)
        assertEquals(State.FALLBACK, screen.restores.single().state)
        assertEquals(listOf(Holder.PRESENTER), ledger.holders(screen.shown.single().id).toList())
        screen.shown.single().done(Outcome.SurfaceLost(0L))
        assertEquals(0, ledger.stats().live)
        assertEquals(1, screen.restores.size)

        now = 500_000_000L
        assertFalse(session.begin(epoch(2L), 4, 2))
        assertEquals(1, screen.restores.size)
        now = 1_000_000_000L
        assertTrue(session.begin(epoch(3L), 4, 2))
        assertEquals(Phase.ACTIVE, session.snapshot().phase)
        assertEquals(1, session.snapshot().recovery.retries)
    }

    fun `steady playback fallback retries by itself once the backoff expires`() {
        val (session, _, _) = playing()
        screen.accept = false
        repeat(3) { now = it * slot1; session.tick(now); drain() }
        assertEquals(Phase.NATIVE, session.snapshot().phase)
        assertEquals(State.FALLBACK, screen.restores.single().state)
        screen.accept = true
        now = 999_999_999L
        session.tick(now)
        assertEquals(Phase.NATIVE, session.snapshot().phase)
        now = 1_100_000_000L
        session.tick(now)
        assertEquals(Phase.ACTIVE, session.snapshot().phase)
        assertEquals(1, session.snapshot().recovery.retries)
    }

    fun `after surface loss only an epoch with a different surface is retried automatically`() {
        val (session, _, _) = playing()
        session.onSurfaceLost(0L)
        now = 2_000_000_000L
        session.tick(now)
        assertEquals(Phase.NATIVE, session.snapshot().phase)
        assertTrue(session.begin(Epoch(2L, 1L, 2L, 1L, 1L), 4, 2))

        session.onSurfaceLost(now)
        now = 2_500_000_000L
        assertFalse(session.begin(Epoch(3L, 1L, 3L, 1L, 1L), 4, 2))
        now = 3_999_999_999L
        session.tick(now)
        assertEquals(Phase.NATIVE, session.snapshot().phase)
        now = 4_000_000_000L
        session.tick(now)
        assertEquals(Phase.ACTIVE, session.snapshot().phase)
        assertEquals(2, session.snapshot().recovery.retries)
    }

    fun `fatal native error disables interpolation and hands playback back to the host`() {
        val (session, ledger, _) = playing()
        session.tick(0L)
        now = slot1
        session.tick(now)
        synth.status = Status.GPU_UNAVAILABLE
        drain()
        assertEquals(State.DISABLED, screen.restores.single().state)
        assertEquals(Status.GPU_UNAVAILABLE, screen.restores.single().nativeStatus)
        assertEquals(1, screen.shown.size)
        screen.shown.single().done(Outcome.Presented(0L))
        assertEquals(0, ledger.stats().live)
        now = 100_000_000_000L
        assertFalse(session.begin(epoch(2L), 4, 2))
    }

    fun `a single worker timeout counts as a failure and its late result is never presented`() {
        val (session, ledger, ids) = playing()
        session.tick(0L)
        now = slot1
        session.tick(now)
        now = slot1 + 100_000_001L
        session.tick(now)
        assertTrue(screen.restores.isEmpty())
        assertEquals(1, session.snapshot().recovery.failures)
        drain()
        // 迟到的推理结果被丢弃：只呈现过第 0 槽的原帧。
        assertEquals(1, screen.shown.size)
        screen.shown.single().done(Outcome.Presented(now))
        assertEquals(setOf(FrameBufferLedger.Holder.TIMELINE), ledger.holders(ids.first))
    }

    fun `repeated worker timeouts fall back to native`() {
        val recovery = FramePresentationRecovery(window = 20).also { it.start(0L) }
        repeat(3) { recovery.onWorkerTimeout(it.toLong()) }
        assertEquals(State.FALLBACK, recovery.report().state)
        assertEquals(FramePresentationRecovery.Cause.WORKER_TIMEOUT, recovery.report().cause)
    }


    fun `inference finishing after its slot is a dropped frame and not a late burst`() {
        val (session, ledger, _) = playing()
        session.tick(0L)
        now = slot1
        session.tick(now)
        now = slot1 + 1L
        drain()
        assertEquals(1, screen.shown.size)
        assertEquals(1, session.snapshot().recovery.failures)
        assertEquals(2, ledger.stats().live)
    }

    fun `originals are planned back to back and the next slot starts as soon as inference ends`() {
        val session = session(leadNs = 34_000_000L)
        assertTrue(session.begin(epoch(1L), 4, 2))
        session.updateClock(clock())
        listOf(0L, 33_333L, 66_667L, 100_000L).forEachIndexed { i, pts -> session.capture(pts, (i + 1).toByte()) }
        // 一个节拍内：槽 0 原帧，随即规划槽 1（需推理）并提交任务，不再等下一个节拍。
        session.tick(0L)
        assertEquals(1, screen.shown.size)
        assertEquals(1, tasks.size)
        // 推理完成即呈现槽 1，并在同一回调里继续规划：槽 2 原帧立即呈现；槽 3 尚在提前量之外。
        drain()
        assertEquals(listOf(0L, slot1, 2 * slot1 + 1), screen.shown.map { it.dueNs })
        assertTrue(tasks.isEmpty())
        now = 20_000_000L
        session.tick(now)
        assertEquals(1, tasks.size)
    }

    fun `replayed presenter callbacks release and count only once`() {
        val (session, ledger, ids) = playing()
        session.tick(0L)
        val shown = screen.shown.single()
        shown.done(Outcome.Presented(0L))
        shown.done(Outcome.Presented(0L))
        shown.done(Outcome.Dropped(0L))
        assertEquals(1, session.snapshot().recovery.samples)
        assertEquals(0, session.snapshot().recovery.failures)
        assertEquals(setOf(Holder.TIMELINE), ledger.holders(ids.first))
    }

    fun `degraded state presents nearest originals without submitting inference`() {
        val (session, _, ids) = playing()
        repeat(9) { recovery.onPresented(0L, 0L, 1L) }
        recovery.onDropped(0L)
        assertEquals(State.DEGRADED, recovery.report().state)
        session.tick(0L)
        now = slot1
        session.tick(now)
        assertTrue(tasks.isEmpty())
        assertEquals(0, synth.calls)
        assertEquals(2, screen.shown.size)
        assertTrue(screen.shown[1].id in setOf(ids.first, ids.second))
    }

    fun `pause revokes the epoch and releases the timeline window without presenting`() {
        val (session, ledger, _) = playing()
        assertEquals(ClockState.RESTART_REQUIRED, session.updateClock(clock(paused = true)))
        assertEquals(Phase.IDLE, session.snapshot().phase)
        assertEquals(0, ledger.stats().live)
        session.tick(0L)
        assertTrue(screen.shown.isEmpty())
        assertNull(session.capture(0L, 1))
        assertTrue(session.begin(epoch(2L), 4, 2))
        assertTrue(screen.restores.isEmpty())
    }

    fun `rejected presentation and rejected work release holds and feed recovery`() {
        val (session, ledger, ids) = playing()
        screen.accept = false
        session.tick(0L)
        assertEquals(setOf(Holder.TIMELINE), ledger.holders(ids.first))
        assertEquals(1, session.snapshot().recovery.failures)

        screen.accept = true
        acceptWork = false
        now = slot1
        session.tick(now)
        assertEquals(setOf(Holder.TIMELINE), ledger.holders(ids.first))
        assertEquals(setOf(Holder.TIMELINE), ledger.holders(ids.second))
        assertEquals(2, session.snapshot().recovery.failures)
        assertEquals(Status.BUSY, session.snapshot().recovery.nativeStatus)
        assertEquals(2, ledger.stats().live)
    }

    fun `exhausted pool rejects capture and presents originals instead of over allocating`() {
        var allocations = 0
        val (session, ledger, ids) = playing(FrameBufferLedger(2) { allocations++; ByteBuffer.allocate(it) })
        assertNull(session.capture(66_666L, 3))
        session.tick(0L)
        now = slot1
        session.tick(now)
        assertTrue(tasks.isEmpty())
        assertEquals(2, allocations)
        assertTrue(screen.shown[1].id in setOf(ids.first, ids.second))
        assertEquals(2, ledger.stats().allocated)
    }

    fun `repeated rejected clocks restore native and drain old worker and presenter frames`() {
        val (session, ledger, _) = playing()
        session.tick(0L)
        now = slot1
        session.tick(now)
        assertEquals(1, tasks.size)
        repeat(3) { i ->
            val invalid = ClockSnapshot(0L, 0L, 1.0, false, 1L, uncertaintyUs = 10_000L)
            assertEquals(ClockState.RESTART_REQUIRED, session.updateClock(invalid))
            assertEquals(i < 2, session.onClockRejected(now))
            if (i < 2) assertTrue(session.begin(epoch(2L + i), 4, 2))
        }
        assertEquals(Phase.NATIVE, session.snapshot().phase)
        assertEquals(FramePresentationRecovery.Cause.UNVERIFIED_CLOCK, screen.restores.single().cause)
        drain()
        screen.shown.forEach { it.done(Outcome.Dropped(now)) }
        session.close()
        drain()
        assertTrue(ledger.drained())
        assertEquals(1, synth.closed)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FrameInterpolationSessionCases) -> Unit) {
            val s = FrameInterpolationSessionCases()
            block(s)
        }
        runIsolated { s -> s.`source duplicates are kept out of the timeline at most twice in a row and do not leak buffers`() }
        runIsolated { s -> s.`original then interpolated frame are presented and every buffer returns to the pool`() }
        runIsolated { s -> s.`teardown cannot recycle the previous frame while duplicate comparison is reading it`() }
        runIsolated { s -> s.`seek during duplicate comparison cannot reuse its pixels or admit its stale capture`() }
        runIsolated { s -> s.`duplicate comparator failure releases its hold and accepts the source frame`() }
        runIsolated { s -> s.`seek during inference discards the stale result and releases all its holds`() }
        runIsolated { s -> s.`teardown defers synthesizer close until the in flight task ends and drains after presenter returns`() }
        runIsolated { s -> s.`frames superseded by a slower panel return their buffers without counting as failures`() }
        runIsolated { s -> s.`surface loss restores native once, returns queued frames and retries on a later surface epoch`() }
        runIsolated { s -> s.`steady playback fallback retries by itself once the backoff expires`() }
        runIsolated { s -> s.`after surface loss only an epoch with a different surface is retried automatically`() }
        runIsolated { s -> s.`fatal native error disables interpolation and hands playback back to the host`() }
        runIsolated { s -> s.`a single worker timeout counts as a failure and its late result is never presented`() }
        runIsolated { s -> s.`repeated worker timeouts fall back to native`() }
        runIsolated { s -> s.`inference finishing after its slot is a dropped frame and not a late burst`() }
        runIsolated { s -> s.`originals are planned back to back and the next slot starts as soon as inference ends`() }
        runIsolated { s -> s.`replayed presenter callbacks release and count only once`() }
        runIsolated { s -> s.`degraded state presents nearest originals without submitting inference`() }
        runIsolated { s -> s.`pause revokes the epoch and releases the timeline window without presenting`() }
        runIsolated { s -> s.`rejected presentation and rejected work release holds and feed recovery`() }
        runIsolated { s -> s.`exhausted pool rejects capture and presents originals instead of over allocating`() }
        runIsolated { s -> s.`repeated rejected clocks restore native and drain old worker and presenter frames`() }
    }
}

private class FrameModelCacheCases {
    private val root: File = Files.createTempDirectory("frame-model").toFile()

    fun cleanUp() { root.deleteRecursively() }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun `spec matches only the exact size and hash and streams what it read`() {
        val bytes = ByteArray(200_000) { (it * 31).toByte() }
        val part = FrameModelSpec.Part("x.bin", bytes.size, sha256(bytes))
        val copy = ByteArrayOutputStream()
        assertTrue(FrameModelSpec.matches(part, ByteArrayInputStream(bytes)) { chunk, count -> copy.write(chunk, 0, count) })
        assertArrayEquals(bytes, copy.toByteArray())
        assertFalse(FrameModelSpec.matches(part, ByteArrayInputStream(bytes.copyOf(bytes.size - 1))))
        assertFalse(FrameModelSpec.matches(part, ByteArrayInputStream(bytes + 0)))
        assertFalse(FrameModelSpec.matches(part, ByteArrayInputStream(bytes.copyOf().also { it[7] = 0 })))
        val file = File(root, "x.bin").apply { writeBytes(bytes) }
        assertTrue(FrameModelSpec.matches(part, file))
        assertFalse(FrameModelSpec.matches(part, File(root, "absent")))
    }

    fun `pinned parts come from the fixed commit under distinct remote names`() {
        FrameModelSpec.PARTS.forEach { part ->
            assertTrue(part.sourceUrl.startsWith("https://raw.githubusercontent.com/nihui/rife-ncnn-vulkan/${FrameModelSpec.SOURCE_COMMIT}/"))
            assertTrue(part.sourceUrl.endsWith("/models/rife-v4.6/${part.fileName}"))
            assertEquals(64, part.sha256.length)
        }
        assertEquals(listOf("frame_model_rife_v4_6_flownet.param", "frame_model_rife_v4_6_flownet.bin"),
            FrameModelSpec.PARTS.map { it.remoteName })
    }

    fun `missing remote file reports missing without creating model files`() {
        val result = FrameModelCache.materialize(root) { null }
        assertEquals(FrameModelCache.Result.Missing(FrameModelSpec.PARAM.fileName), result)
        assertEquals(emptyList<String>(), File(root, "innocent-frame-model/rife-v4.6").list()!!.toList())
    }

    fun `corrupt remote file is rejected and leaves no partial copy`() {
        val opened = mutableListOf<String>()
        val result = FrameModelCache.materialize(root) { name -> opened += name; ByteArrayInputStream(ByteArray(16_532)) }
        assertEquals(FrameModelCache.Result.Invalid(FrameModelSpec.PARAM.fileName), result)
        assertEquals(listOf(FrameModelSpec.PARAM.remoteName), opened)
        assertEquals(emptyList<String>(), File(root, "innocent-frame-model/rife-v4.6").list()!!.toList())
    }

    fun `throwing opener counts as missing`() {
        val result = FrameModelCache.materialize(root) { error("service gone") }
        assertTrue(result is FrameModelCache.Result.Missing)
    }

    fun concurrentPlaybackProcessesPublishTheirOwnTemporaryFilesAndReuseACompleteCache() {
        val payload = "verified model".toByteArray()
        val part = FrameModelSpec.Part("small.bin", payload.size, sha256(payload))
        val barrier = java.util.concurrent.CyclicBarrier(2)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                pool.submit<Result<FrameModelCache.Result>> {
                    runCatching { FrameModelCache.materialize(root, listOf(part)) {
                        barrier.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        ByteArrayInputStream(payload)
                    } }
                }
            }.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS).getOrThrow() }
            assertTrue("concurrent outcomes: $results", results.all { it is FrameModelCache.Result.Ready })
            val cached = FrameModelCache.materialize(root, listOf(part)) { error("must reuse verified cache") }
            assertTrue(cached is FrameModelCache.Result.Ready)
            assertEquals(listOf(part.fileName), (cached as FrameModelCache.Result.Ready).directory.list()!!.toList())
        } finally { pool.shutdownNow() }
    }

    fun `provider fallback materializes and verifies when remote opener is unavailable`() {
        val payload = "provider-verified-model".toByteArray()
        val part = FrameModelSpec.Part("provider.bin", payload.size, sha256(payload))
        var providerCalls = 0
        val ready = FrameModelCache.materialize(
            hostPrivateRoot = root,
            parts = listOf(part),
            openProvider = { providerCalls++; ByteArrayInputStream(payload) },
            openRemote = { null }
        )
        assertTrue(ready is FrameModelCache.Result.Ready)
        assertEquals(1, providerCalls)
        val cached = FrameModelCache.materialize(
            hostPrivateRoot = root,
            parts = listOf(part),
            openProvider = { error("must reuse verified cache") },
            openRemote = { error("must reuse verified cache") }
        )
        assertTrue(cached is FrameModelCache.Result.Ready)

        val badPart = FrameModelSpec.Part("bad-provider.bin", payload.size, sha256("other".toByteArray()))
        val invalid = FrameModelCache.materialize(
            hostPrivateRoot = root,
            parts = listOf(badPart),
            openProvider = { ByteArrayInputStream(payload) },
            openRemote = { null }
        )
        assertEquals(FrameModelCache.Result.Invalid(badPart.fileName), invalid)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FrameModelCacheCases) -> Unit) {
            val s = FrameModelCacheCases()
            try {
                block(s)
            } finally {
                s.cleanUp()
            }
        }
        runIsolated { s -> s.`spec matches only the exact size and hash and streams what it read`() }
        runIsolated { s -> s.`pinned parts come from the fixed commit under distinct remote names`() }
        runIsolated { s -> s.`missing remote file reports missing without creating model files`() }
        runIsolated { s -> s.`corrupt remote file is rejected and leaves no partial copy`() }
        runIsolated { s -> s.`throwing opener counts as missing`() }
        runIsolated { s -> s.concurrentPlaybackProcessesPublishTheirOwnTemporaryFilesAndReuseACompleteCache() }
        runIsolated { s -> s.`provider fallback materializes and verifies when remote opener is unavailable`() }
    }
}

private class FramePendingTakeoversCases {
    fun rifeAhbWaitsForItsModelAndOnlyRunsOncePerDecoderAttempt() {
        val attempt = FramePlaybackRoutes.Attempt()
        assertFalse(attempt.claimRifeAhb(false))
        assertTrue(attempt.claimRifeAhb(true))
        assertFalse(attempt.claimRifeAhb(true))
        assertTrue(FramePlaybackRoutes.Attempt().claimRifeAhb(true))
    }

    fun qcomWindowResizeKeepsQcomEligibleWithABoundedZeroMidpointCap() {
        val attempt = FramePlaybackRoutes.Attempt()
        assertTrue(attempt.retireQcom("window_size", hadMidpoint = false))
        assertFalse(attempt.rifeOnly)
        assertTrue(attempt.retireQcom("window_size", hadMidpoint = true))
        assertFalse(attempt.rifeOnly)
        // A successful midpoint resets the zero-midpoint resize budget so fullscreen toggles keep QCOM.
        assertTrue(attempt.retireQcom("window_size", hadMidpoint = false))
        assertTrue(attempt.retireQcom("window_size", hadMidpoint = false))
        assertFalse(attempt.rifeOnly)
        assertFalse(attempt.retireQcom("window_size", hadMidpoint = false))
        assertTrue(attempt.rifeOnly)

        val nonResize = FramePlaybackRoutes.Attempt()
        assertFalse(nonResize.retireQcom("speed", hadMidpoint = true))
        assertTrue(nonResize.rifeOnly)
    }

    fun delayedMediaKeepsAdmissionOpenWithCappedProbesOnlyForMissingEvidence() {
        val attempt = FramePlaybackRoutes.Attempt()
        val reasons = listOf(
            FrameMediaPolicy.Reason.UNKNOWN_AUDIO_PATH,
            FrameMediaPolicy.Reason.UNKNOWN_COLOR,
            FrameMediaPolicy.Reason.UNKNOWN_BIT_DEPTH
        )
        val delays = List(FramePlaybackRoutes.MAX_MEDIA_WAIT_ATTEMPTS) { index ->
            assertTrue(attempt.waitForMedia(reasons[index % reasons.size]))
            attempt.mediaRetryDelayMs
        }
        assertEquals(150L, delays.first())
        assertEquals(3_000L, delays.last())
        assertTrue(delays.zipWithNext().all { (previous, next) -> next in previous..3_000L })
        assertFalse(attempt.waitForMedia(FrameMediaPolicy.Reason.UNKNOWN_AUDIO_PATH))
        assertFalse(attempt.waitForMedia(FrameMediaPolicy.Reason.UNKNOWN_COLOR))

        val fresh = FramePlaybackRoutes.Attempt()
        assertFalse(fresh.waitForMedia(FrameMediaPolicy.Reason.HDR_PQ))
        assertFalse(fresh.waitForMedia(FrameMediaPolicy.Reason.PROTECTED_CONTENT))
        assertFalse(fresh.waitForMedia(FrameMediaPolicy.Reason.READY))

        fresh.limitDisplayHz(90f)
        fresh.limitDisplayHz(60f)
        assertEquals(60f, fresh.displayLimitHz)
        fresh.resetDisplayLimit()
        assertNull(fresh.displayLimitHz)
    }

    fun pendingRifeModelDoesNotHoldOtherQcomCandidates() {
        val pending = FramePendingTakeovers<Any, String>()
        val a = Any()
        val b = Any()
        pending.await(a, "rife", { false }) {}
        pending.await(b, "qcom", { true }) {}
        assertEquals(listOf(b to "qcom"), pending.takeReady { it == "qcom" })
        assertFalse(pending.hasReady { it == "qcom" })
        assertEquals(listOf(a to "rife"), pending.takeReady { true })
    }
    fun readyBetweenTheInitialCheckAndRegistrationStillWakesTheDecoder() {
        val pending = FramePendingTakeovers<Any, String>()
        val codec = Any()
        val initialReady = false
        var ready = initialReady
        assertFalse(initialReady)
        // The worker announces Ready before the configure callback stores its pending decoder.
        ready = true
        assertTrue(pending.take().isEmpty())
        var wakes = 0
        pending.await(codec, "video", { ready }) { wakes++ }
        assertEquals(1, wakes)
        assertEquals(listOf(codec to "video"), pending.take())
    }

    fun readyAfterRegistrationAndNewArrivalsDuringADrainAreBothRetained() {
        val pending = FramePendingTakeovers<Any, Int>()
        val first = Any(); val second = Any()
        var ready = false; var wakes = 0
        pending.await(first, 1, { ready }) { wakes++ }
        assertEquals(0, wakes)
        ready = true
        assertEquals(listOf(first to 1), pending.take())
        pending.await(second, 2, { ready }) { wakes++ }
        assertTrue(pending.hasPending())
        assertEquals(1, wakes)
        assertEquals(listOf(second to 2), pending.take())
        assertFalse(pending.hasPending())
    }

    fun replacementRevokesAlreadyQueuedRestorationAndAudioCleanupCallbacks() {
        val generation = FrameDecoderGeneration<Any>()
        val codec = Any()
        val old = generation.begin(codec)
        var surface = "new"
        var delay = 150
        val queuedRestore = { if (old.isActive()) { surface = "old"; delay = 0 } }
        val queuedAudioCleanup = { if (old.isActive()) delay = 0 }
        val next = generation.replace(codec, old) {}!!
        queuedRestore()
        queuedAudioCleanup()
        assertEquals("new", surface)
        assertEquals(150, delay)
        assertFalse(old.isActive())
        assertTrue(next.isActive())
        generation.end(codec)
        assertFalse(next.isActive())
        assertNull(generation.replace(codec, next) { fail("released decoder cannot acquire a replacement") })
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FramePendingTakeoversCases) -> Unit) {
            val s = FramePendingTakeoversCases()
            block(s)
        }
        runIsolated { s -> s.rifeAhbWaitsForItsModelAndOnlyRunsOncePerDecoderAttempt() }
        runIsolated { s -> s.qcomWindowResizeKeepsQcomEligibleWithABoundedZeroMidpointCap() }
        runIsolated { s -> s.delayedMediaKeepsAdmissionOpenWithCappedProbesOnlyForMissingEvidence() }
        runIsolated { s -> s.pendingRifeModelDoesNotHoldOtherQcomCandidates() }
        runIsolated { s -> s.readyBetweenTheInitialCheckAndRegistrationStillWakesTheDecoder() }
        runIsolated { s -> s.readyAfterRegistrationAndNewArrivalsDuringADrainAreBothRetained() }
        runIsolated { s -> s.replacementRevokesAlreadyQueuedRestorationAndAudioCleanupCallbacks() }
    }
}
