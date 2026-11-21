package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationBackend.Status
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Audio
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.AudioKind
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.AudioOutput
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Capture
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Evidence
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Range
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Reason
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Signal
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Snapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Standard
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Transfer
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FramePresentationRecovery.Cause
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FramePresentationRecovery.State
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameProcessingSize.Capability
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameProcessingSize.Size
import java.nio.ByteBuffer
import java.util.PriorityQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class FrameMediaPolicyTest {
    private val epoch = FrameTimeline.Epoch(1, 2, 3, 4, 5)
    private fun signal() = Signal("video/avc", "avc1.640028", Transfer.SDR, Standard.BT709,
        Range.LIMITED, 8, Evidence.NO, Evidence.NO)
    private fun media() = Snapshot(1920, 1080, signal(), signal(), Capture.VERIFIED_SDR_RGB8,
        Evidence.NO, Evidence.NO, Evidence.NO,
        Audio("audio/mp4a-latm", AudioOutput.PCM, Evidence.NO, Evidence.NO))
    private fun reason(media: Snapshot) = FrameMediaPolicy.evaluate(media).reason

    fun `positive SDR RGB8 and ordinary PCM qualify without modifying source facts`() {
        val original = media()
        assertEquals(Reason.READY, reason(original))
        assertTrue(FrameMediaPolicy.evaluate(original).eligible)
        assertEquals(Range.LIMITED, original.source.range)
        assertEquals(Reason.READY, reason(original.copy(width = 1080, height = 1920)))
        assertEquals(Reason.READY, reason(original.copy(source = signal().copy(standard = Standard.BT601))))
    }

    fun `unknown flags never stand in for explicit absence`() {
        val original = media()
        assertEquals(Reason.UNKNOWN_PROTECTION, reason(original.copy(protectedContent = Evidence.UNKNOWN)))
        assertEquals(Reason.UNKNOWN_PROTECTION, reason(original.copy(secureDecoder = Evidence.UNKNOWN)))
        assertEquals(Reason.UNKNOWN_VIDEO_PATH, reason(original.copy(videoTunneled = Evidence.UNKNOWN)))
        assertEquals(Reason.UNKNOWN_COLOR, reason(original.copy(source = signal().copy(hdrStatic = Evidence.UNKNOWN))))
        assertEquals(Reason.UNKNOWN_COLOR, reason(original.copy(decoded = signal().copy(hdrDynamic = Evidence.UNKNOWN))))
        assertEquals(Reason.CAPTURE_NOT_VERIFIED, reason(original.copy(capture = Capture.UNKNOWN)))
    }

    fun `protection secure and tunneled output are never admitted`() {
        assertEquals(Reason.PROTECTED_CONTENT, reason(media().copy(protectedContent = Evidence.YES)))
        assertEquals(Reason.SECURE_DECODER, reason(media().copy(secureDecoder = Evidence.YES)))
        assertEquals(Reason.VIDEO_TUNNELED, reason(media().copy(videoTunneled = Evidence.YES)))
    }

    fun `Dolby Vision MIME and all codec prefixes override a claimed SDR output`() {
        assertEquals(Reason.DOLBY_VISION, reason(media().copy(source = signal().copy(mime = " VIDEO/DOLBY-VISION "))))
        for (prefix in listOf("dvhe", "dvh1", "dvav", "dva1", "dav1")) {
            assertEquals(prefix, Reason.DOLBY_VISION,
                reason(media().copy(source = signal().copy(codecs = "avc1.640028, ${prefix.uppercase()}.05.06"))))
            assertEquals(prefix, Reason.DOLBY_VISION,
                reason(media().copy(decoded = signal().copy(codecs = "$prefix.08.04"))))
        }
    }

    fun `PQ HLG static and dynamic HDR in either layer retain original path`() {
        for (source in listOf(true, false)) {
            fun with(signal: Signal) = if (source) media().copy(source = signal) else media().copy(decoded = signal)
            assertEquals(Reason.HDR_PQ, reason(with(signal().copy(transfer = Transfer.PQ))))
            assertEquals(Reason.HDR_HLG, reason(with(signal().copy(transfer = Transfer.HLG))))
            assertEquals(Reason.HDR_METADATA, reason(with(signal().copy(hdrStatic = Evidence.YES))))
            assertEquals(Reason.HDR_DYNAMIC, reason(with(signal().copy(hdrDynamic = Evidence.YES))))
        }
        assertEquals(Reason.HDR_PQ, reason(media().copy(source = signal().copy(transfer = Transfer.PQ))))
        assertEquals(Transfer.SDR, media().decoded.transfer)
    }

    fun `wide gamut linear or unclassified colors never use SDR backend`() {
        assertEquals(Reason.WIDE_COLOR, reason(media().copy(source = signal().copy(standard = Standard.BT2020))))
        assertEquals(Reason.UNSUPPORTED_COLOR, reason(media().copy(decoded = signal().copy(transfer = Transfer.LINEAR))))
        assertEquals(Reason.UNSUPPORTED_COLOR, reason(media().copy(decoded = signal().copy(standard = Standard.OTHER))))
        for (s in listOf(signal().copy(standard = Standard.UNKNOWN), signal().copy(transfer = Transfer.UNKNOWN),
                signal().copy(range = Range.UNKNOWN))) {
            assertEquals(Reason.UNKNOWN_COLOR, reason(media().copy(source = s)))
            assertEquals(Reason.UNKNOWN_COLOR, reason(media().copy(decoded = s)))
        }
    }

    fun `ten bit SDR and missing bit depth do not silently become eight bit`() {
        assertEquals(Reason.UNSUPPORTED_BIT_DEPTH, reason(media().copy(source = signal().copy(bitDepth = 10))))
        assertEquals(Reason.UNSUPPORTED_BIT_DEPTH, reason(media().copy(decoded = signal().copy(bitDepth = 12))))
        assertEquals(Reason.UNKNOWN_BIT_DEPTH, reason(media().copy(source = signal().copy(bitDepth = null))))
        assertEquals(Reason.UNKNOWN_BIT_DEPTH, reason(media().copy(decoded = signal().copy(bitDepth = null))))
    }

    fun `bounded dimensions include portrait and reject overflow and oversized tensors`() {
        for ((width, height) in listOf(0 to 1, 1 to -1, 4096 to 2161, 1 to Int.MAX_VALUE,
                Int.MAX_VALUE to Int.MAX_VALUE, 4097 to 1)) {
            assertEquals("$width x $height", Reason.INVALID_SIZE, reason(media().copy(width = width, height = height)))
        }
        // 4K 源由捕获缩到处理尺寸后插帧；源尺寸本身只受 4096×2160 像素上限约束。
        for ((width, height) in listOf(1920 to 1081, 3840 to 2160, 2160 to 3840, 4096 to 2160))
            assertNotEquals("$width x $height", Reason.INVALID_SIZE, reason(media().copy(width = width, height = height)))
    }

    fun `DTS family decoded to verified PCM is a candidate without forcing audio conversion`() {
        val kinds = listOf("audio/vnd.dts" to AudioKind.DTS, "audio/vnd.dts.hd" to AudioKind.DTS_HD,
            "audio/vnd.dts.uhd" to AudioKind.DTS_UHD, "audio/eac3-joc" to AudioKind.DOLBY,
            "audio/vnd.dolby.mlp" to AudioKind.DOLBY, "audio/vnd.dolby.mat" to AudioKind.DOLBY)
        for ((mime, kind) in kinds) {
            val audio = Audio(mime, AudioOutput.PCM, Evidence.NO, Evidence.NO)
            val result = FrameMediaPolicy.evaluate(media().copy(audio = audio))
            assertTrue(mime, result.eligible)
            assertEquals(kind, result.audioKind)
            assertEquals(AudioOutput.PCM, audio.output)
        }
    }

    fun `DTS label never authorizes passthrough offload tunneling or unknown transport`() {
        val cases = listOf(AudioOutput.PASSTHROUGH to Reason.AUDIO_PASSTHROUGH,
            AudioOutput.OFFLOAD to Reason.AUDIO_OFFLOAD, AudioOutput.TUNNELED to Reason.AUDIO_TUNNELED,
            AudioOutput.UNKNOWN to Reason.UNKNOWN_AUDIO_PATH)
        for ((output, expected) in cases) {
            assertEquals(expected, reason(media().copy(audio = Audio("audio/vnd.dts", output, Evidence.NO, Evidence.NO))))
        }
        assertEquals(Reason.AUDIO_OFFLOAD,
            reason(media().copy(audio = media().audio.copy(offloaded = Evidence.YES))))
        assertEquals(Reason.AUDIO_TUNNELED,
            reason(media().copy(audio = media().audio.copy(tunneled = Evidence.YES))))
        assertEquals(Reason.UNKNOWN_AUDIO_PATH,
            reason(media().copy(audio = media().audio.copy(offloaded = Evidence.UNKNOWN))))
        assertEquals(Reason.UNKNOWN_AUDIO_PATH,
            reason(media().copy(audio = media().audio.copy(tunneled = Evidence.UNKNOWN))))
    }

    fun `no active audio must be explicit and does not authorize contradictory transport flags`() {
        assertEquals(Reason.READY, reason(media().copy(audio = Audio(output = AudioOutput.NONE))))
        assertEquals(Reason.AUDIO_OFFLOAD,
            reason(media().copy(audio = Audio(output = AudioOutput.NONE, offloaded = Evidence.YES))))
        assertEquals(Reason.AUDIO_TUNNELED,
            reason(media().copy(audio = Audio(output = AudioOutput.NONE, tunneled = Evidence.YES))))
    }

    fun `invalid MIME codec syntax and over budget data cannot hide special format evidence`() {
        for (s in listOf(signal().copy(mime = null), signal().copy(mime = "video/dolby-vision;profile=5"),
                signal().copy(mime = "video/avc\n" + "secret"), signal().copy(codecs = "avc1.640028,"),
                signal().copy(codecs = "x.dvh1.05"), signal().copy(codecs = "dvh1:05:06"),
                signal().copy(codecs = "a".repeat(257)))) {
            assertEquals(Reason.UNKNOWN_COLOR, reason(media().copy(source = s)))
        }
        assertEquals(Reason.UNKNOWN_AUDIO_PATH, reason(media().copy(audio = media().audio.copy(mime = "a".repeat(257)))))
    }

    fun `format changes revoke old permits even when returning to identical SDR facts`() {
        val gate = FrameMediaGate()
        val firstLease = gate.begin(epoch).lease
        val first = requireNotNull(gate.resolve(firstLease, media()).permit)
        val hdrBegin = gate.begin(epoch)
        assertSame(first, hdrBegin.revoked)
        assertFalse(gate.valid(first, firstLease))
        val hdr = gate.resolve(hdrBegin.lease, media().copy(source = signal().copy(transfer = Transfer.PQ)))
        assertNull(hdr.permit)
        val restoredLease = gate.begin(epoch).lease
        val restored = requireNotNull(gate.resolve(restoredLease, media()).permit)
        assertNotSame(first, restored)
        assertFalse(gate.valid(first, firstLease))
        assertTrue(gate.valid(restored, restoredLease))
    }

    fun `media decoder surface format and discontinuity epochs each invalidate permits`() {
        val epochs = listOf(epoch.copy(media = 7), epoch.copy(decoder = 7), epoch.copy(surface = 7),
            epoch.copy(format = 7), epoch.copy(discontinuity = 7))
        for (next in epochs) {
            val gate = FrameMediaGate()
            val firstLease = gate.begin(epoch).lease
            val first = requireNotNull(gate.resolve(firstLease, media()).permit)
            val begin = gate.begin(next)
            assertSame(first, begin.revoked)
            val current = requireNotNull(gate.resolve(begin.lease, media()).permit)
            assertFalse(gate.valid(first, firstLease))
            assertFalse(gate.valid(current, firstLease))
            assertTrue(gate.valid(current, begin.lease))
        }
    }

    fun `stale close and foreign gate permits cannot revoke the current format`() {
        val gate = FrameMediaGate()
        val oldLease = gate.begin(epoch).lease
        val old = requireNotNull(gate.resolve(oldLease, media()).permit)
        val currentLease = gate.begin(epoch).lease
        val current = requireNotNull(gate.resolve(currentLease, media().copy(width = 1280, height = 720)).permit)
        assertFalse(gate.close(oldLease))
        assertFalse(gate.valid(old, oldLease))
        assertTrue(gate.valid(current, currentLease))
        val other = FrameMediaGate()
        val foreignLease = other.begin(epoch).lease
        val foreign = requireNotNull(other.resolve(foreignLease, media()).permit)
        assertFalse(gate.close(foreignLease))
        assertFalse(gate.valid(foreign, foreignLease))
        assertTrue(gate.close(currentLease))
        assertFalse(gate.valid(current, currentLease))
        assertFalse(gate.close(currentLease))
    }

    fun `late old format result cannot mint a new permit after an HDR observation`() {
        val gate = FrameMediaGate()
        val old = gate.begin(epoch).lease
        assertNotNull(gate.resolve(old, media()).permit)
        val hdr = gate.begin(epoch.copy(format = 6)).lease
        assertNull(gate.resolve(hdr, media().copy(source = signal().copy(transfer = Transfer.PQ))).permit)
        val late = gate.resolve(old, media())
        assertEquals(Reason.STALE_LEASE, late.decision.reason)
        assertNull(late.permit)
        assertEquals(Reason.ALREADY_RESOLVED, gate.resolve(hdr, media()).decision.reason)
    }

    fun `a single format observation cannot resolve twice even with identical epoch values`() {
        val gate = FrameMediaGate()
        val first = gate.begin(epoch).lease
        assertNull(gate.resolve(first, media().copy(source = Signal())).permit)
        assertEquals(Reason.ALREADY_RESOLVED, gate.resolve(first, media()).decision.reason)
        val second = gate.begin(epoch).lease
        assertNotNull(gate.resolve(second, media()).permit)
        assertEquals(Reason.STALE_LEASE, gate.resolve(first, media()).decision.reason)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FrameMediaPolicyTest) -> Unit) {
            val s = FrameMediaPolicyTest()
            block(s)
        }
        runIsolated { s -> s.`positive SDR RGB8 and ordinary PCM qualify without modifying source facts`() }
        runIsolated { s -> s.`unknown flags never stand in for explicit absence`() }
        runIsolated { s -> s.`protection secure and tunneled output are never admitted`() }
        runIsolated { s -> s.`Dolby Vision MIME and all codec prefixes override a claimed SDR output`() }
        runIsolated { s -> s.`PQ HLG static and dynamic HDR in either layer retain original path`() }
        runIsolated { s -> s.`wide gamut linear or unclassified colors never use SDR backend`() }
        runIsolated { s -> s.`ten bit SDR and missing bit depth do not silently become eight bit`() }
        runIsolated { s -> s.`bounded dimensions include portrait and reject overflow and oversized tensors`() }
        runIsolated { s -> s.`DTS family decoded to verified PCM is a candidate without forcing audio conversion`() }
        runIsolated { s -> s.`DTS label never authorizes passthrough offload tunneling or unknown transport`() }
        runIsolated { s -> s.`no active audio must be explicit and does not authorize contradictory transport flags`() }
        runIsolated { s -> s.`invalid MIME codec syntax and over budget data cannot hide special format evidence`() }
        runIsolated { s -> s.`format changes revoke old permits even when returning to identical SDR facts`() }
        runIsolated { s -> s.`media decoder surface format and discontinuity epochs each invalidate permits`() }
        runIsolated { s -> s.`stale close and foreign gate permits cannot revoke the current format`() }
        runIsolated { s -> s.`late old format result cannot mint a new permit after an HDR observation`() }
        runIsolated { s -> s.`a single format observation cannot resolve twice even with identical epoch values`() }
    
        FramePipelinePressureCases().`150 and 200 ms pipelines fit the bounded pool with pulldown and inference jitter`()
        FramePlayerClockProbeCases().verifyAllCases()
        FramePreparationRetryCases().verifyAllCases()
        FramePresentationRecoveryCases().verifyAllCases()
        FrameProcessingSizeCases().verifyAllCases()
    }
}

private class FramePipelinePressureCases {
    private class Event(val at: Long, val order: Int, val run: () -> Unit)

    fun `150 and 200 ms pipelines fit the bounded pool with pulldown and inference jitter`() {
        for (delayMs in listOf(150L, 200L)) for (sourceFps in listOf(25, 30)) for (pulldown in listOf(false, true)) {
            simulate(delayMs, sourceFps, pulldown)
        }
    }

    private fun simulate(delayMs: Long, sourceFps: Int, pulldown: Boolean) {
        var now = 0L
        var order = 0
        val events = PriorityQueue<Event>(compareBy<Event> { it.at }.thenBy { it.order })
        fun at(time: Long, task: () -> Unit) { events += Event(time, order++, task) }
        val ledger = FrameBufferLedger(16) { ByteBuffer.allocate(it) }
        val target = sourceFps * 2
        val timeline = FrameTimeline(target, presentationDelayNs = delayMs * 1_000_000L,
            maxLeadNs = 100_000_000L, minLeadNs = 32_000_000L, phaseLock = true,
            originalToleranceUs = 1_000_000L / target / 4,
            frameWindow = kotlin.math.ceil(delayMs / 1000.0 * sourceFps).toInt() + 2)
        var presented = 0
        var maxLive = 0
        var starved = 0
        var jobs = 0
        val presenter = object : FrameInterpolationSession.Presenter {
            override fun present(frameId: Long, buffer: ByteBuffer, format: FrameBufferLedger.Format,
                                 dueNs: Long, done: (FrameInterpolationSession.Outcome) -> Unit): Boolean {
                at(maxOf(now, dueNs)) { presented++; done(FrameInterpolationSession.Outcome.Presented(now)) }
                return true
            }
            override fun restoreNative(report: FramePresentationRecovery.Report) { fail("unexpected fallback: $report") }
        }
        val synth = object : FrameInterpolationSession.Synthesizer {
            override fun process(left: ByteBuffer, right: ByteBuffer, output: ByteBuffer,
                                 format: FrameBufferLedger.Format, alpha: Float, leftId: Long, rightId: Long) =
                FrameInterpolationBackend.Status.OK
            override fun close() = Unit
        }
        val session = FrameInterpolationSession(target, ledger, synth, { task ->
            // 正常 15 ms，每 31 个任务有一次 28 ms；任务完成后才交还 WORKER/输出缓冲。
            at(now + if (++jobs % 31 == 0) 28_000_000L else 15_000_000L, task)
            true
        }, presenter, { now }, timeline = timeline,
            duplicate = { a, b, _ -> a.getInt(0) == b.getInt(0) })
        val epoch = FrameTimeline.Epoch(1, 1, 1, 1, 1)
        assertTrue(session.begin(epoch, 4, 2))
        val seconds = 20
        repeat(seconds * sourceFps) { frame ->
            // 到达抖动不超过 6 ms，PTS 仍连续；每五张编码帧重复一张原画。
            val arrival = frame * 1_000_000_000L / sourceFps + (frame % 4) * 2_000_000L
            at(arrival) {
                val id = session.capture(1_000_000L + frame * 1_000_000L / sourceFps) { buffer, _ ->
                    buffer.putInt(0, if (pulldown) frame - frame / 5 else frame); true
                }
                if (id == null && (!pulldown || frame % 5 != 0 || frame == 0)) starved++
            }
        }
        repeat(seconds * 60) { tick ->
            at(tick * 1_000_000_000L / 60) {
                session.updateClock(FrameTimeline.ClockSnapshot(1_000_000L + now / 1000, now, 1.0, false, 1,
                    uncertaintyUs = 500L))
                session.tick(now)
            }
        }
        val end = seconds * 1_000_000_000L
        while (events.peek()?.let { it.at < end } == true) {
            val event = events.remove()
            now = event.at
            event.run()
            maxLive = maxOf(maxLive, ledger.stats().live)
        }
        val label = "delay=$delayMs source=$sourceFps pulldown=$pulldown peak=$maxLive ${session.snapshot()}"
        assertEquals(label, 0, starved)
        assertTrue(label, presented >= (seconds - 1) * target)
        assertTrue(label, ledger.stats().allocated <= 16)
        assertEquals(label, 0L, ledger.stats().exhausted)
        session.close()
        while (events.isNotEmpty()) { val event = events.remove(); now = event.at; event.run() }
        assertTrue(label, ledger.drained())
    }
}

private class FramePlayerClockProbeCases {
    class Player {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val calls = AtomicInteger()
        val fail = AtomicBoolean(false)
        fun getCurrentPosition(): Long {
            calls.incrementAndGet(); entered.countDown()
            check(gate.await(2, TimeUnit.SECONDS))
            check(!fail.get())
            return 100L
        }
        fun isPlaying() = true
        fun getPropertyFloat(key: Int, fallback: Float) = if (key == 10003) 1f else fallback
    }

    fun `release does not wait on the player query and stops subsequent polling`() {
        val player = Player()
        val probe = FramePlayerClockProbe({ _, _ -> }, { true })
        try {
            probe.attach(player)
            probe.attach(player)
            assertTrue(player.entered.await(1, TimeUnit.SECONDS))
            probe.remove(player) // 当前查询仍在等 gate；不得在宿主 release 上等它。
            player.gate.countDown()
            Thread.sleep(80)
            assertEquals(1, player.calls.get())
        } finally { player.gate.countDown(); probe.remove(player) }
    }

    fun `disabled marker stops the source before querying it`() {
        val detached = CountDownLatch(1)
        val player = Player().apply { gate.countDown() }
        val probe = FramePlayerClockProbe({ event, _ -> if (event == "clock.detached") detached.countDown() }, { false })
        try {
            probe.attach(player)
            assertTrue(detached.await(1, TimeUnit.SECONDS))
            assertEquals(0, player.calls.get())
        } finally { probe.remove(player) }
    }

    fun `a failing query is reported without killing the sampler`() {
        val failure = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val player = Player().apply { gate.countDown(); fail.set(true) }
        val probe = FramePlayerClockProbe({ event, detail ->
            if (event == "clock.player" && detail.contains("failures=1")) failure.countDown()
            if (event == "clock.jump") recovered.countDown()
        }, { true })
        try {
            probe.attach(player)
            assertTrue(failure.await(1, TimeUnit.SECONDS))
            player.fail.set(false)
            // 固定媒体位置配连续墙钟在下一对成功样本上产生跳变，证明异常之后仍在采样。
            assertTrue(recovered.await(1, TimeUnit.SECONDS))
        } finally { probe.remove(player) }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FramePlayerClockProbeCases) -> Unit) {
            val s = FramePlayerClockProbeCases()
            block(s)
        }
        runIsolated { s -> s.`release does not wait on the player query and stops subsequent polling`() }
        runIsolated { s -> s.`disabled marker stops the source before querying it`() }
        runIsolated { s -> s.`a failing query is reported without killing the sampler`() }
    }
}

private class FramePreparationRetryCases {
    fun transientLoadFailuresRetryWithoutAnExistingBackendButNeverBeforeTheDeadline() {
        val retry = FramePreparationRetry(10, 2, 100)
        retry.failed(FramePreparationRetry.Work.LOAD, 0)
        assertNull(retry.take(9, false))
        assertEquals(FramePreparationRetry.Work.LOAD, retry.take(10, false))
        assertNull(retry.take(11, true))
        retry.failed(FramePreparationRetry.Work.LOAD, 12)
        assertEquals(FramePreparationRetry.Work.LOAD, retry.take(22, false))
        retry.failed(FramePreparationRetry.Work.LOAD, 23)
        assertFalse(retry.canSchedule())
        assertNull(retry.take(1000, false))
    }

    fun newPlaybackRearmsAnExhaustedBudgetOnlyAfterAQuietWindow() {
        val retry = FramePreparationRetry(10, 1, 100)
        retry.failed(FramePreparationRetry.Work.CAPABILITY, 0)
        assertEquals(FramePreparationRetry.Work.CAPABILITY, retry.take(10, false))
        retry.failed(FramePreparationRetry.Work.CAPABILITY, 11)
        assertNull(retry.take(109, true))
        assertEquals(FramePreparationRetry.Work.CAPABILITY, retry.take(110, true))
    }

    fun unsupportedAbiOrRestartRequiredHasNoAutomaticOrPlaybackRetry() {
        val retry = FramePreparationRetry(10, 3, 100)
        retry.failed(null, 0)
        assertFalse(retry.canSchedule())
        assertNull(retry.take(1000, true))
    }

    fun successfulPreparationRevokesPendingRetries() {
        val retry = FramePreparationRetry(10, 3, 100)
        retry.failed(FramePreparationRetry.Work.LOAD, 0)
        retry.ready()
        assertFalse(retry.canSchedule())
        assertNull(retry.take(1000, true))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FramePreparationRetryCases) -> Unit) {
            val s = FramePreparationRetryCases()
            block(s)
        }
        runIsolated { s -> s.transientLoadFailuresRetryWithoutAnExistingBackendButNeverBeforeTheDeadline() }
        runIsolated { s -> s.newPlaybackRearmsAnExhaustedBudgetOnlyAfterAQuietWindow() }
        runIsolated { s -> s.unsupportedAbiOrRestartRequiredHasNoAutomaticOrPlaybackRetry() }
        runIsolated { s -> s.successfulPreparationRevokesPendingRetries() }
    }
}

private class FramePresentationRecoveryCases {
    private val second = 1_000_000_000L
    private fun started() = FramePresentationRecovery(window = 20).also { assertTrue(it.start(0L)) }
    private fun FramePresentationRecovery.onTime(count: Int) = repeat(count) { onPresented(0L, 0L, 8_000_000L) }
    private fun FramePresentationRecovery.late() = onPresented(0L, 20_000_000L, 8_000_000L)

    fun `repeated clock rejection falls back even with successful presentations in between`() {
        val recovery = started()
        repeat(3) { i ->
            recovery.onTime(120)
            recovery.onClockRejected(i * second)
        }
        assertEquals(State.FALLBACK, recovery.report().state)
        assertEquals(Cause.UNVERIFIED_CLOCK, recovery.report().cause)
        assertFalse(recovery.report().pipelinePresents)
        assertTrue(recovery.start(3 * second))
        assertTrue("a retry starts a fresh clock budget", recovery.onClockRejected(3 * second).pipelinePresents)
    }

    fun `isolated clock rejections expire instead of disabling a long playing video`() {
        val recovery = started()
        for (time in listOf(0L, 2L, 8L, 10L)) assertTrue(recovery.onClockRejected(time * second).pipelinePresents)
        assertEquals(State.FALLBACK, recovery.onClockRejected(12 * second).state)
        assertEquals(Cause.UNVERIFIED_CLOCK, recovery.report().cause)
    }

    fun `on time presentation keeps interpolating`() {
        val recovery = started()
        recovery.onTime(40)
        assertEquals(State.INTERPOLATING, recovery.report().state)
        assertTrue(recovery.report().interpolationAllowed)
    }

    fun `scattered late frames degrade to originals then recover after a clean window`() {
        val recovery = started()
        recovery.onTime(9)
        recovery.late()
        assertEquals(State.DEGRADED, recovery.report().state)
        assertEquals(Cause.LATE_FRAMES, recovery.report().cause)
        assertFalse(recovery.report().interpolationAllowed)
        assertTrue(recovery.report().pipelinePresents)
        recovery.onTime(19)
        assertEquals(State.DEGRADED, recovery.report().state)
        recovery.onTime(1)
        assertEquals(State.INTERPOLATING, recovery.report().state)
        assertEquals(Cause.RECOVERED, recovery.report().cause)
    }

    fun `consecutive drops fall back to native and retry only after backoff on a new epoch`() {
        val recovery = started()
        repeat(3) { recovery.onDropped(0L) }
        assertEquals(State.FALLBACK, recovery.report().state)
        assertFalse(recovery.report().pipelinePresents)
        assertFalse(recovery.start(second - 1))
        assertTrue(recovery.start(second))
        assertEquals(Cause.RETRY, recovery.report().cause)
        assertEquals(1, recovery.report().retries)
        assertEquals(0, recovery.report().failures)
    }

    fun `backoff doubles and retries are bounded per session`() {
        val recovery = FramePresentationRecovery(window = 20, maxRetries = 2).also { it.start(0L) }
        recovery.onSurfaceLost(0L)
        assertTrue(recovery.start(second))
        recovery.onSurfaceLost(second)
        assertFalse(recovery.start(second + 2 * second - 1))
        assertTrue(recovery.start(second + 2 * second))
        recovery.onSurfaceLost(10 * second)
        assertFalse(recovery.start(100 * second))
        assertEquals(State.DISABLED, recovery.report().state)
        assertEquals(Cause.RETRIES_EXHAUSTED, recovery.report().cause)
    }

    fun `fatal native status disables for the session and survives stop`() {
        val recovery = started()
        recovery.onNativeStatus(Status.GPU_UNAVAILABLE, 0L)
        assertEquals(State.DISABLED, recovery.report().state)
        assertEquals(Status.GPU_UNAVAILABLE, recovery.report().nativeStatus)
        recovery.stop()
        assertFalse(recovery.start(100 * second))
    }

    fun `transient native errors count as failures and report the status`() {
        val recovery = started()
        recovery.onTime(9)
        assertEquals(State.DEGRADED, recovery.onNativeStatus(Status.INFERENCE_FAILED, 0L).state)
        assertEquals(Status.INFERENCE_FAILED, recovery.report().nativeStatus)
        recovery.onNativeStatus(Status.BUSY, 0L)
        recovery.onNativeStatus(Status.INVALID_OUTPUT, 0L)
        assertEquals(State.FALLBACK, recovery.report().state)
    }

    fun `warm up samples after start are ignored and transitions keep the real failure cause`() {
        val recovery = FramePresentationRecovery(window = 20, warmupSamples = 3).also { it.start(0L) }
        repeat(3) { recovery.onDropped(0L) }
        assertEquals(State.INTERPOLATING, recovery.report().state)
        assertEquals(0, recovery.report().samples)
        recovery.onTime(6)
        recovery.onDropped(0L)
        recovery.onTime(1)
        recovery.onDropped(0L)
        // 第 9 个样本是成功样本，但 2/9 ≥ 10% 触发降级：原因应为最近一次失败，而不是 NONE。
        recovery.onTime(1)
        assertEquals(State.DEGRADED, recovery.report().state)
        assertEquals(Cause.DROPPED_FRAMES, recovery.report().cause)
    }

    fun `listeners observe every transition and outcomes outside the pipeline are ignored`() {
        val recovery = FramePresentationRecovery(window = 20)
        val seen = ArrayList<State>()
        recovery.addListener { seen += it.state }
        recovery.onDropped(0L)
        assertEquals(State.INACTIVE, recovery.report().state)
        recovery.start(0L)
        recovery.onSurfaceLost(0L)
        repeat(5) { recovery.onDropped(0L) }
        recovery.stop()
        assertEquals(listOf(State.INTERPOLATING, State.FALLBACK), seen)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FramePresentationRecoveryCases) -> Unit) {
            val s = FramePresentationRecoveryCases()
            block(s)
        }
        runIsolated { s -> s.`repeated clock rejection falls back even with successful presentations in between`() }
        runIsolated { s -> s.`isolated clock rejections expire instead of disabling a long playing video`() }
        runIsolated { s -> s.`on time presentation keeps interpolating`() }
        runIsolated { s -> s.`scattered late frames degrade to originals then recover after a clean window`() }
        runIsolated { s -> s.`consecutive drops fall back to native and retry only after backoff on a new epoch`() }
        runIsolated { s -> s.`backoff doubles and retries are bounded per session`() }
        runIsolated { s -> s.`fatal native status disables for the session and survives stop`() }
        runIsolated { s -> s.`transient native errors count as failures and report the status`() }
        runIsolated { s -> s.`warm up samples after start are ignored and transitions keep the real failure cause`() }
        runIsolated { s -> s.`listeners observe every transition and outcomes outside the pipeline are ignored`() }
    }
}

private class FrameProcessingSizeCases {
    // 2026-10-19 真机：1/4 光流档 1080p 可持续，1/8 光流档 1440p 可持续；面板 3168×1440。
    private val phone = Capability(standard = 1920L * 1080L, high = 2560L * 1440L)
    private fun choose(w: Int, h: Int, capability: Capability = phone, long: Int = 3168, short: Int = 1440) =
        FrameProcessingSize.choose(w, h, capability, long, short)

    fun `sources within the panel and capability keep their size`() {
        assertEquals(Size(1280, 720), choose(1280, 720))
        assertEquals(Size(1920, 1080), choose(1920, 1080))
        assertEquals(Size(2560, 1440), choose(2560, 1440))
    }

    fun `4k is reduced to the panel and never processed above what the screen shows`() {
        assertEquals(Size(2560, 1440), choose(3840, 2160))
        val dci = choose(4096, 2160)!!
        assertTrue("$dci", dci.pixels <= 2560L * 1440L && dci.height <= 1440)
        assertEquals(4096.0 / 2160.0, dci.width.toDouble() / dci.height, 0.01)
        val portrait = choose(2160, 3840)!!
        assertTrue(portrait.height <= 3168 && portrait.width <= 1440)
        assertEquals(2160.0 / 3840.0, portrait.width.toDouble() / portrait.height, 0.01)
    }

    fun `a device without the high flow class falls back to the standard budget`() {
        assertEquals(Size(1920, 1080), choose(3840, 2160, Capability(1920L * 1080L, 0L)))
        assertEquals(Size(1280, 720), choose(3840, 2160, Capability(1280L * 720L, 0L)))
    }

    fun `a 1080p source on a device where only 720p passes the standard class is reduced to 720p`() {
        // 1440p 在 1/8 档可持续不代表 1080p 在 1/4 档可持续：1080p 源不能借用高档预算。
        assertEquals(Size(1280, 720), choose(1920, 1080, Capability(1280L * 720L, 2560L * 1440L)))
    }

    fun `a 4k panel with 4k capability processes 4k`() {
        assertEquals(Size(3840, 2160), choose(3840, 2160, Capability(1920L * 1080L, 3840L * 2160L), 3840, 2160))
    }

    fun `no capability or degenerate input yields no processing size`() {
        assertNull(choose(1920, 1080, Capability(0L, 0L)))
        assertNull(choose(0, 1080))
        assertNull(choose(32, 32))
    }

    fun `fit keeps even dimensions and the pixel budget`() {
        for ((w, h) in listOf(1921 to 1081, 3840 to 1634, 1366 to 768, 2880 to 2160)) {
            val size = FrameProcessingSize.fit(w, h, 1280L * 720L, 0, 0)!!
            assertTrue("$size", size.width % 2 == 0 && size.height % 2 == 0)
            assertTrue("$size", size.pixels <= 1280L * 720L)
            assertEquals(w.toDouble() / h, size.width.toDouble() / size.height, 0.01)
        }
    }

    fun `odd source dimensions never round up beyond the source`() {
        assertEquals(Size(64, 64), FrameProcessingSize.fit(65, 65, Long.MAX_VALUE, 0, 0))
        assertEquals(Size(1918, 1080), FrameProcessingSize.fit(1919, 1081, Long.MAX_VALUE, 0, 0))
        val large = FrameProcessingSize.fit(Int.MAX_VALUE, Int.MAX_VALUE, Long.MAX_VALUE, 0, 0)!!
        assertEquals(Int.MAX_VALUE - 1, large.width)
        assertEquals(Int.MAX_VALUE - 1, large.height)
    }

    fun `rounding never exceeds odd display edges in either orientation`() {
        val landscape = FrameProcessingSize.fit(1920, 1080, Long.MAX_VALUE, 1441, 811)!!
        assertTrue("$landscape", landscape.width <= 1441 && landscape.height <= 811)
        val portrait = FrameProcessingSize.fit(1080, 1920, Long.MAX_VALUE, 1441, 811)!!
        assertTrue("$portrait", portrait.width <= 811 && portrait.height <= 1441)
    }

    fun `valid results stay within source display and pixel bounds`() {
        val random = java.util.Random(0x6d656775L)
        repeat(1000) {
            val w = 64 + random.nextInt(4096)
            val h = 64 + random.nextInt(4096)
            val long = 64 + random.nextInt(4096)
            val short = 64 + random.nextInt(long - 63)
            val budget = 4096L + random.nextInt(8_000_000)
            val result = FrameProcessingSize.fit(w, h, budget, long, short) ?: return@repeat
            val widthLimit = if (w >= h) long else short
            val heightLimit = if (w >= h) short else long
            assertTrue("$w x $h -> $result", result.width in 64..minOf(w, widthLimit))
            assertTrue("$w x $h -> $result", result.height in 64..minOf(h, heightLimit))
            assertTrue("$result", result.width % 2 == 0 && result.height % 2 == 0 && result.pixels <= budget)
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FrameProcessingSizeCases) -> Unit) {
            val s = FrameProcessingSizeCases()
            block(s)
        }
        runIsolated { s -> s.`sources within the panel and capability keep their size`() }
        runIsolated { s -> s.`4k is reduced to the panel and never processed above what the screen shows`() }
        runIsolated { s -> s.`a device without the high flow class falls back to the standard budget`() }
        runIsolated { s -> s.`a 1080p source on a device where only 720p passes the standard class is reduced to 720p`() }
        runIsolated { s -> s.`a 4k panel with 4k capability processes 4k`() }
        runIsolated { s -> s.`no capability or degenerate input yields no processing size`() }
        runIsolated { s -> s.`fit keeps even dimensions and the pixel budget`() }
        runIsolated { s -> s.`odd source dimensions never round up beyond the source`() }
        runIsolated { s -> s.`rounding never exceeds odd display edges in either orientation`() }
        runIsolated { s -> s.`valid results stay within source display and pixel bounds`() }
    }
}
