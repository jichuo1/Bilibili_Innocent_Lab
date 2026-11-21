package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.ClockSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.ClockState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.Decision
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.Epoch
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.Frame
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.Mode
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.Reason
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import kotlin.math.roundToLong
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameStatusTest {
    private val english = InjectedUiLocale.messages(explicitSelectionTag = InjectedUiLocale.TAG_ENGLISH)
    private val chinese = InjectedUiLocale.messages(explicitSelectionTag = InjectedUiLocale.TAG_SIMPLIFIED_CHINESE)

    fun `active status shows the source and target frame rates`() {
        assertEquals("插帧已生效：30 → 60 fps · QCOM", FrameStatus.text(FrameStatus.Active(30f, 60, FrameStatus.Backend.QCOM), chinese))
        assertEquals("Frame interpolation on: 29.97 → 60 fps · RIFE", FrameStatus.text(FrameStatus.Active(29.97f, 60), english))
        assertEquals("插帧已生效：50 fps · RIFE", FrameStatus.text(FrameStatus.Active(null, 50), chinese))
    }

    fun `inactive status names a user facing reason`() {
        assertEquals("插帧未生效：视频帧率已达上限", FrameStatus.text(FrameStatus.Inactive(FrameStatus.Reason.RATE), chinese))
        assertEquals("Frame interpolation not active: the model is still loading",
            FrameStatus.text(FrameStatus.Inactive(FrameStatus.Reason.LOADING), english))
        // 每个原因都有文案（格式化不会因缺少占位而抛出）。
        FrameStatus.Reason.entries.forEach { FrameStatus.text(FrameStatus.Inactive(it), english) }
    }

    fun `native hand back reasons map to status reasons`() {
        assertEquals(FrameStatus.Reason.MEDIA, FrameCaptureBridge.nativeStatusReason("media:HDR"))
        assertEquals(FrameStatus.Reason.RATE, FrameCaptureBridge.nativeStatusReason("cadence:24.0fps"))
        assertEquals(FrameStatus.Reason.THERMAL, FrameCaptureBridge.nativeStatusReason("thermal:0.90"))
        assertEquals(FrameStatus.Reason.FALLBACK, FrameCaptureBridge.nativeStatusReason("drive:IllegalStateException"))
        assertEquals(FrameStatus.Reason.FALLBACK, FrameCaptureBridge.nativeStatusReason(null))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FrameStatusTest) -> Unit) {
            val s = FrameStatusTest()
            block(s)
        }
        runIsolated { s -> s.`active status shows the source and target frame rates`() }
        runIsolated { s -> s.`inactive status names a user facing reason`() }
        runIsolated { s -> s.`native hand back reasons map to status reasons`() }
    
        FrameTakeoverPolicyCases().verifyAllCases()
        FrameThermalControlCases().verifyAllCases()
        FrameTimelineDelayCases().verifyAllCases()
        FrameTimelineCases().verifyAllCases()
        FrameVsyncGridCases().verifyAllCases()
    }
}

private class FrameTakeoverPolicyCases {
    fun surfaceHolderAndSurfacePathsRetainOnlyTheCurrentMainPlayerSurface() {
        val ownership = FrameSurfaceOwnership<Any, Any>()
        val player = Any()
        val holderSurface = Any()
        val replacement = Any()
        assertFalse(ownership.bind(Any(), holderSurface, false))
        assertTrue(ownership.bind(player, holderSurface, true))
        assertTrue(ownership.contains(holderSurface))
        assertTrue(ownership.bind(player, null, false))
        assertFalse(ownership.contains(holderSurface))
        assertTrue(ownership.bind(player, replacement, false))
        assertTrue(ownership.contains(replacement))
        ownership.release(player)
        assertFalse(ownership.contains(replacement))
        assertFalse(ownership.bind(player, replacement, false))
    }

    private class EqualObject {
        override fun equals(other: Any?) = other is EqualObject
        override fun hashCode() = 1
    }

    fun hostEqualityNeverGrantsAnUnrelatedPlayerOrSurfaceOwnership() {
        val ownership = FrameSurfaceOwnership<EqualObject, EqualObject>()
        val surface = EqualObject()
        assertTrue(ownership.bind(EqualObject(), surface, true))
        assertFalse(ownership.contains(EqualObject()))
        assertFalse(ownership.bind(EqualObject(), surface, false))
    }

    fun onePlayerReleasingASharedSurfaceDoesNotRevokeAnotherOwner() {
        val ownership = FrameSurfaceOwnership<Any, Any>(2)
        val first = Any(); val second = Any(); val shared = Any()
        assertTrue(ownership.bind(first, shared, true))
        assertTrue(ownership.bind(second, shared, true))
        assertFalse(ownership.bind(Any(), Any(), true))
        ownership.release(first)
        assertTrue(ownership.contains(shared))
        ownership.release(second)
        assertFalse(ownership.contains(shared))
    }

    fun playbackRatesFollowThePlayerAndDoNotLeakAcrossConcurrentServiceSurfaces() {
        val ownership = FrameSurfaceOwnership<Any, Any>(2)
        val first = Any(); val second = Any()
        val firstSurface = Any(); val secondSurface = Any(); val alias = Any()
        ownership.updateSpeed(first, 1.5f, allowNew = true)
        assertEquals(1.0, ownership.rateReader(alias, true)(), 0.0)
        ownership.bind(first, firstSurface, true)
        val originalReader = ownership.rateReader(alias, true)
        assertEquals(1.5, ownership.rateReader(firstSurface, false)(), 0.0)
        assertEquals(1.0, ownership.rateReader(alias, false)(), 0.0)
        assertEquals(1.5, ownership.rateReader(alias, true)(), 0.0)

        ownership.bind(second, secondSurface, true)
        ownership.updateSpeed(second, 2f, allowNew = false)
        ownership.updateSpeed(second, Float.NaN, allowNew = false)
        assertEquals(1.0, ownership.rateReader(alias, true)(), 0.0)
        assertEquals(2.0, ownership.rateReader(secondSurface, true)(), 0.0)
        ownership.bind(second, firstSurface, false)
        assertEquals(1.0, originalReader(), 0.0)
        assertEquals(1.0, ownership.rateReader(firstSurface, true)(), 0.0)
        ownership.bind(second, null, false)
        assertEquals(1.5, ownership.rateReader(alias, true)(), 0.0)
        val lastReader = ownership.rateReader(alias, true)
        ownership.release(first)
        assertEquals(1.0, lastReader(), 0.0)
        assertEquals(1.0, ownership.rateReader(alias, true)(), 0.0)
    }

    fun aSpeedNotificationWithoutASurfaceCannotDisplaceAnActivePlayer() {
        val ownership = FrameSurfaceOwnership<Any, Any>(1)
        val pending = Any(); val active = Any(); val surface = Any()
        ownership.updateSpeed(pending, 2f, true)
        assertTrue(ownership.bind(active, surface, true))
        ownership.updateSpeed(active, 1.5f, false)
        ownership.updateSpeed(pending, 2f, true)
        assertFalse(ownership.bind(pending, Any(), true))
        assertEquals(1.5, ownership.rateReader(surface, false)(), 0.0)
    }

    fun nestedAdapterCallsAndExceptionalExitPreserveAndThenClearTheirScope() {
        val scope = FrameCallScope()
        scope.enter()
        try {
            scope.enter()
            try { assertTrue(scope.active); throw IllegalStateException() }
            catch (_: IllegalStateException) { }
            finally { scope.leave() }
            assertTrue(scope.active)
        } finally { scope.leave() }
        assertFalse(scope.active)
    }

    fun stoppedAndReconfiguredDecodersRejectLateBridgesWithoutCancellingTheirReplacement() {
        val generation = FrameDecoderGeneration<Any>()
        val codec = Any()
        val old = generation.begin(codec)
        generation.end(codec)
        assertFalse(generation.commit(codec, old) { fail("released decoder") })
        val next = generation.begin(codec)
        assertFalse(generation.end(codec, old) { fail("old failure must not remove a new bridge") })
        assertTrue(generation.commit(codec, next) {})
        val newer = generation.begin(codec)
        assertFalse(generation.commit(codec, next) { fail("reconfigured decoder") })
        assertTrue(generation.matches(codec, newer))
    }

    fun onlyTheTwoKnownConfigureAbisAreAcceptedAndEncryptedArgumentsKeepTheirOwnIndex() {
        val prefix = listOf("android.media.MediaFormat", "android.view.Surface")
        assertEquals(2, FrameCodecConfigureShape.cryptoIndex(prefix + listOf("android.media.MediaCrypto", "int")))
        assertEquals(3, FrameCodecConfigureShape.cryptoIndex(prefix + listOf("int", "android.media.MediaDescrambler")))
        assertNull(FrameCodecConfigureShape.cryptoIndex(prefix + listOf("int", "java.lang.Object")))
        assertNull(FrameCodecConfigureShape.cryptoIndex(prefix + listOf("android.media.MediaCrypto", "int", "int")))
    }

    fun nestedPrivateConfigureDelegationPreservesOuterRegistrationWhileTopLevelReconfigureDetaches() {
        val inConfigure = FrameCallScope()
        val generation = FrameDecoderGeneration<Any>()
        val configured = HashMap<Any, FrameDecoderGeneration.Ticket>()
        val codec = Any()
        val publicFourArg = listOf("android.media.MediaFormat", "android.view.Surface", "android.media.MediaCrypto", "int")
        val privateFiveArg = listOf("android.media.MediaFormat", "android.view.Surface", "android.media.MediaCrypto", "android.os.IHwBinder", "int")

        fun simulateConfigure(params: List<String>, body: () -> Unit = {}) {
            if (inConfigure.active) return
            inConfigure.enter()
            try {
                if (configured.containsKey(codec)) generation.end(codec) { configured.remove(codec) }
                val crypto = FrameCodecConfigureShape.cryptoIndex(params)
                if (crypto != null) {
                    val ticket = generation.begin(codec) { configured.remove(codec) }
                    generation.commit(codec, ticket) { configured[codec] = ticket }
                }
                body()
            } finally {
                inConfigure.leave()
            }
        }

        // Public 4-arg configure calls private 5-arg configure internally: registration must survive for start().
        simulateConfigure(publicFourArg) { simulateConfigure(privateFiveArg) }
        val registered = configured[codec]
        assertNotNull(registered)
        assertTrue(registered!!.isActive())
        assertFalse(inConfigure.active)

        // A genuine top-level reconfigure via an unsupported overload detaches the previously owned decoder.
        simulateConfigure(privateFiveArg)
        assertNull(configured[codec])
        assertFalse(registered.isActive())
        assertFalse(inConfigure.active)
    }

    fun optionalHooksCannotMaskAMissingConfigureOrSurfaceRole() {
        val required = setOf("display", "op", "surface", "surface.holder", "surface.release", "speed",
            "configure", "start", "output.surface", "flush", "stop", "reset", "release", "audio.ctor")
        val service = setOf("client.setSurface", "client.release", "client.speed")
        assertTrue(FrameHookCoverage.complete(required, false))
        assertFalse(FrameHookCoverage.complete(required - "configure" + setOf("display.resume", "display.pause"), false))
        assertFalse(FrameHookCoverage.complete(required - "surface" + setOf("display.resume", "display.pause"), false))
        assertTrue(FrameHookCoverage.complete(required + service, true))
        for (role in service) assertFalse(FrameHookCoverage.complete(required + service - role, true))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FrameTakeoverPolicyCases) -> Unit) {
            val s = FrameTakeoverPolicyCases()
            block(s)
        }
        runIsolated { s -> s.surfaceHolderAndSurfacePathsRetainOnlyTheCurrentMainPlayerSurface() }
        runIsolated { s -> s.hostEqualityNeverGrantsAnUnrelatedPlayerOrSurfaceOwnership() }
        runIsolated { s -> s.onePlayerReleasingASharedSurfaceDoesNotRevokeAnotherOwner() }
        runIsolated { s -> s.playbackRatesFollowThePlayerAndDoNotLeakAcrossConcurrentServiceSurfaces() }
        runIsolated { s -> s.aSpeedNotificationWithoutASurfaceCannotDisplaceAnActivePlayer() }
        runIsolated { s -> s.nestedAdapterCallsAndExceptionalExitPreserveAndThenClearTheirScope() }
        runIsolated { s -> s.stoppedAndReconfiguredDecodersRejectLateBridgesWithoutCancellingTheirReplacement() }
        runIsolated { s -> s.onlyTheTwoKnownConfigureAbisAreAcceptedAndEncryptedArgumentsKeepTheirOwnIndex() }
        runIsolated { s -> s.nestedPrivateConfigureDelegationPreservesOuterRegistrationWhileTopLevelReconfigureDetaches() }
        runIsolated { s -> s.optionalHooksCannotMaskAMissingConfigureOrSurfaceRole() }
    }
}

private class FrameThermalControlCases {
    fun `disabled temperature control never queries a sensor or pauses`() {
        var queries = 0
        assertNull(FrameThermalControl.pauseHeadroom(false) { queries++; 1f })
        assertEquals(0, queries)
    }

    fun `enabled temperature control preserves the original boundary`() {
        assertNull(FrameThermalControl.pauseHeadroom(true) { .849f })
        assertEquals(.85f, FrameThermalControl.pauseHeadroom(true) { .85f }!!, 0f)
        assertEquals(1f, FrameThermalControl.pauseHeadroom(true) { 1f }!!, 0f)
    }

    fun `missing and invalid temperature evidence keep the original no-pause behavior`() {
        assertNull(FrameThermalControl.pauseHeadroom(true) { null })
        assertNull(FrameThermalControl.pauseHeadroom(true) { Float.NaN })
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FrameThermalControlCases) -> Unit) {
            val s = FrameThermalControlCases()
            block(s)
        }
        runIsolated { s -> s.`disabled temperature control never queries a sensor or pauses`() }
        runIsolated { s -> s.`enabled temperature control preserves the original boundary`() }
        runIsolated { s -> s.`missing and invalid temperature evidence keep the original no-pause behavior`() }
    }
}

private class FrameTimelineDelayCases {
    private val epoch = Epoch(1L, 1L, 1L, 1L, 1L)
    private val ms = 1_000_000L
    private fun clock(mediaUs: Long, monotonicNs: Long, uncertaintyUs: Long = 0L) =
        ClockSnapshot(mediaUs, monotonicNs, 1.0, false, 1L, uncertaintyUs)
    private fun FrameTimeline.ready(lease: FrameTimeline.Lease, now: Long) =
        (plan(lease, now) as? Decision.Ready)?.proposal ?: fail("expected proposal, got ${plan(lease, now)}") as Nothing

    fun `delay shows media from the past so the future frame is already captured`() {
        val timeline = FrameTimeline(60, presentationDelayNs = 50 * ms, maxLeadNs = 34 * ms)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock(100_000L, 0L))
        // 主时钟 100 ms 时已到达 66.7 ms 与 100 ms 的原帧；延迟 50 ms 后槽位 0 显示 50 ms。
        listOf(33_333L, 66_667L, 100_000L).forEachIndexed { i, pts -> timeline.offer(lease, Frame(i.toLong(), pts)) }
        val first = timeline.ready(lease, 0L)
        assertEquals(50_000L, first.mediaUs)
        assertEquals(Mode.INTERPOLATE, first.mode)
        assertEquals(0L, first.before.id)
        assertEquals(1L, first.after.id)
        assertEquals(50_000L, timeline.effectiveDelayUs())
    }

    fun `lead allows planning a slot more than one period ahead`() {
        val oneSlot = FrameTimeline(60)
        val deep = FrameTimeline(60, presentationDelayNs = 50 * ms, maxLeadNs = 34 * ms)
        for (timeline in listOf(oneSlot, deep)) {
            val lease = timeline.begin(epoch).lease
            timeline.updateClock(lease, clock(100_000L, 0L))
            listOf(0L, 50_000L, 100_000L, 150_000L).forEachIndexed { i, pts -> timeline.offer(lease, Frame(i.toLong(), pts)) }
            assertTrue(timeline.commit(timeline.ready(lease, 0L), 0L))
        }
        // 槽位 1、2 分别在 16.7 ms、33.3 ms；此刻（1 ms）请求的是槽位 1，提前 15.7 ms。
        // 用 commit 推进到槽位 2 后在 1 ms 规划：普通模式超出一个周期，深流水线允许。
        val oneLease = oneSlot.begin(epoch).lease
        oneSlot.updateClock(oneLease, clock(100_000L, 0L))
        listOf(0L, 50_000L, 100_000L, 150_000L).forEachIndexed { i, pts -> oneSlot.offer(oneLease, Frame(10L + i, pts)) }
        val slot0 = oneSlot.ready(oneLease, 0L)
        assertTrue(oneSlot.commit(slot0, 0L))
        val slot1 = oneSlot.ready(oneLease, 1L)
        assertTrue(oneSlot.commit(slot1, 1L))
        assertEquals(Reason.AHEAD_OF_WINDOW, (oneSlot.plan(oneLease, 2L) as Decision.Waiting).reason)

        val deepLease = deep.begin(epoch).lease
        deep.updateClock(deepLease, clock(100_000L, 0L))
        listOf(0L, 50_000L, 100_000L, 150_000L).forEachIndexed { i, pts -> deep.offer(deepLease, Frame(20L + i, pts)) }
        assertTrue(deep.commit(deep.ready(deepLease, 0L), 0L))
        assertTrue(deep.commit(deep.ready(deepLease, 1L), 1L))
        assertEquals(33_333_333L, deep.ready(deepLease, 2L).dueNs)
    }

    fun `minimum lead skips ahead to a slot the worker can still meet`() {
        val timeline = FrameTimeline(60, presentationDelayNs = 80 * ms, maxLeadNs = 48 * ms, minLeadNs = 32 * ms)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock(200_000L, 0L))
        listOf(100_000L, 133_333L, 166_667L, 200_000L).forEachIndexed { i, pts -> timeline.offer(lease, Frame(i.toLong(), pts)) }
        val first = timeline.ready(lease, 0L)
        // 32 ms 之后的第一个槽位是第 2 槽（33.3 ms），而不是第 0 槽。
        assertEquals(33_333_333L, first.dueNs)
        assertTrue(first.dueNs - 0L >= 32 * ms)
        assertTrue(timeline.commit(first, 1L))
        // 已经领先时下一槽紧随其后，不再额外跳过。
        assertEquals(Reason.AHEAD_OF_WINDOW, (timeline.plan(lease, 2L) as Decision.Waiting).reason)
        assertEquals(50_000_000L, timeline.ready(lease, 3 * ms).dueNs)
        assertThrows(IllegalArgumentException::class.java) { FrameTimeline(60, maxLeadNs = 20 * ms, minLeadNs = 30 * ms) }
    }

    fun `phase lock aligns 30 to 60 so every other slot is an original frame`() {
        val timeline = FrameTimeline(60, presentationDelayNs = 50 * ms, maxLeadNs = 20 * ms,
            originalToleranceUs = 500L, phaseLock = true)
        val lease = timeline.begin(epoch).lease
        // 时钟相位故意与源帧错开 7 ms。
        timeline.updateClock(lease, clock(107_000L, 0L))
        val sourcePts = (0..8).map { it * 33_333L + it / 3 }
        sourcePts.forEachIndexed { i, pts -> if (pts <= 120_000L) timeline.offer(lease, Frame(i.toLong(), pts)) }
        val modes = ArrayList<Mode>()
        for (slot in 0 until 4) {
            val now = slot * 1_000_000_000L / 60
            if (slot > 0) timeline.updateClock(lease, clock(107_000L + now / 1_000, now))
            sourcePts.forEachIndexed { i, pts -> if (pts <= 107_000L + now / 1_000 + 20_000L) timeline.offer(lease, Frame(i.toLong(), pts)) }
            val proposal = timeline.ready(lease, now)
            modes += proposal.mode
            if (proposal.mode == Mode.INTERPOLATE) assertEquals(0.5, proposal.alpha, 0.03)
            assertTrue(timeline.commit(proposal, now))
        }
        assertEquals(listOf(Mode.ORIGINAL, Mode.INTERPOLATE, Mode.ORIGINAL, Mode.INTERPOLATE), modes)
        val delay = timeline.effectiveDelayUs()
        assertTrue("delay $delay", delay in 50_000L until 50_000L + 33_334L)
    }

    fun `phase tracking keeps original slots aligned while the clock estimate drifts`() {
        val timeline = FrameTimeline(60, presentationDelayNs = 50 * ms, maxLeadNs = 20 * ms, phaseLock = true)
        val lease = timeline.begin(epoch).lease
        val sourcePts = (0..40).map { it * 33_333L }
        val modes = ArrayList<Mode>()
        for (slot in 0 until 24) {
            val now = slot * 1_000_000_000L / 60
            // 时钟估计每槽漂移 +0.4 ms，24 槽累计约 9.6 ms，远超一次性锁相的容差。
            timeline.updateClock(lease, clock(107_000L + now / 1_000 + slot * 400L, now, 2_000L))
            sourcePts.forEachIndexed { i, pts -> if (pts <= 107_000L + now / 1_000 + slot * 400L + 20_000L) timeline.offer(lease, Frame(i.toLong(), pts)) }
            val proposal = timeline.ready(lease, now)
            modes += proposal.mode
            assertTrue(timeline.commit(proposal, now))
        }
        assertEquals(12, modes.count { it == Mode.ORIGINAL })
        assertTrue(modes.zipWithNext().all { (a, b) -> a != b })
    }

    fun `25 to 50 alternates originals and midpoint interpolations`() {
        val timeline = FrameTimeline(50, presentationDelayNs = 50 * ms, maxLeadNs = 20 * ms, phaseLock = true)
        val lease = timeline.begin(epoch).lease
        val modes = ArrayList<Mode>()
        for (slot in 0 until 10) {
            val now = slot * 20 * ms
            timeline.updateClock(lease, clock(107_000L + now / 1_000, now))
            (0..20).forEach { i -> val pts = i * 40_000L; if (pts <= 107_000L + now / 1_000 + 20_000L) timeline.offer(lease, Frame(i.toLong(), pts)) }
            val proposal = timeline.ready(lease, now)
            if (proposal.mode == Mode.INTERPOLATE) assertEquals(0.5, proposal.alpha, 0.03)
            modes += proposal.mode
            assertTrue(timeline.commit(proposal, now))
        }
        assertEquals(5, modes.count { it == Mode.ORIGINAL })
        assertTrue(modes.zipWithNext().all { (a, b) -> a != b })
    }

    fun `tolerance snaps near original slots and zero tolerance keeps exact matching`() {
        for ((tolerance, expected) in listOf(0L to Mode.INTERPOLATE, 500L to Mode.ORIGINAL)) {
            val timeline = FrameTimeline(60, originalToleranceUs = tolerance)
            val lease = timeline.begin(epoch).lease
            timeline.updateClock(lease, clock(300L, 0L))
            timeline.offer(lease, Frame(1L, 0L))
            timeline.offer(lease, Frame(2L, 33_333L))
            assertEquals(expected, timeline.ready(lease, 0L).mode)
        }
    }

    fun `configurable uncertainty is bounded and phase lock resets per epoch`() {
        assertThrows(IllegalArgumentException::class.java) { FrameTimeline(60, maxClockUncertaintyUs = 9_000L) }
        val timeline = FrameTimeline(60, maxClockUncertaintyUs = 4_000L, presentationDelayNs = 40 * ms, phaseLock = true)
        val lease = timeline.begin(epoch).lease
        assertEquals(FrameTimeline.ClockState.READY, timeline.updateClock(lease, clock(100_000L, 0L, 3_000L)).state)
        timeline.offer(lease, Frame(1L, 50_000L))
        timeline.offer(lease, Frame(2L, 83_333L))
        timeline.ready(lease, 0L)
        assertNotEquals(40_000L, timeline.effectiveDelayUs())
        timeline.begin(epoch)
        assertEquals(40_000L, timeline.effectiveDelayUs())
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FrameTimelineDelayCases) -> Unit) {
            val s = FrameTimelineDelayCases()
            block(s)
        }
        runIsolated { s -> s.`delay shows media from the past so the future frame is already captured`() }
        runIsolated { s -> s.`lead allows planning a slot more than one period ahead`() }
        runIsolated { s -> s.`minimum lead skips ahead to a slot the worker can still meet`() }
        runIsolated { s -> s.`phase lock aligns 30 to 60 so every other slot is an original frame`() }
        runIsolated { s -> s.`phase tracking keeps original slots aligned while the clock estimate drifts`() }
        runIsolated { s -> s.`25 to 50 alternates originals and midpoint interpolations`() }
        runIsolated { s -> s.`tolerance snaps near original slots and zero tolerance keeps exact matching`() }
        runIsolated { s -> s.`configurable uncertainty is bounded and phase lock resets per epoch`() }
    }
}

private class FrameTimelineCases {
    private val epoch = Epoch(1L, 1L, 1L, 1L, 1L)
    private fun clock(mediaUs: Long = 0L, monotonicNs: Long = 0L, speed: Double = 1.0,
                      paused: Boolean = false, discontinuity: Long = epoch.discontinuity, uncertaintyUs: Long = 0L) =
        ClockSnapshot(mediaUs, monotonicNs, speed, paused, discontinuity, uncertaintyUs)
    private fun ready(timeline: FrameTimeline, lease: FrameTimeline.Lease, nowNs: Long): FrameTimeline.Proposal {
        val result = timeline.plan(lease, nowNs)
        assertTrue("Expected a proposal, got $result", result is Decision.Ready)
        return (result as Decision.Ready).proposal
    }
    private fun waiting(timeline: FrameTimeline, lease: FrameTimeline.Lease, nowNs: Long): Reason {
        val result = timeline.plan(lease, nowNs)
        assertTrue("Expected a wait, got $result", result is Decision.Waiting)
        return (result as Decision.Waiting).reason
    }

    fun `disabled target and missing verified clock never generate an output proposal`() {
        val disabled = FrameTimeline(0)
        val off = disabled.begin(epoch).lease
        assertEquals(ClockState.READY, disabled.updateClock(off, clock()).state)
        assertEquals(listOf(11L), disabled.offer(off, Frame(11L, 0L)).discardedIds)
        assertEquals(Reason.DISABLED, waiting(disabled, off, 0L))

        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        assertTrue(timeline.offer(lease, Frame(1L, 0L)).accepted)
        assertEquals(Reason.NO_CLOCK, waiting(timeline, lease, 0L))
        val original = readyAfterClock(timeline, lease)
        assertTrue(original.isOriginal)
        assertEquals(0.0, original.alpha, 0.0)
    }

    private fun readyAfterClock(timeline: FrameTimeline, lease: FrameTimeline.Lease): FrameTimeline.Proposal {
        assertEquals(ClockState.READY, timeline.updateClock(lease, clock()).state)
        return ready(timeline, lease, 0L)
    }

    fun `24 to 45 uses different actual PTS pairs and arbitrary alpha`() {
        val timeline = FrameTimeline(45)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock())
        listOf(0L, 41_667L, 83_333L).forEachIndexed { id, pts -> timeline.offer(lease, Frame(id.toLong(), pts)) }
        assertTrue(timeline.commit(ready(timeline, lease, 0L), 0L))

        val first = ready(timeline, lease, 22_222_222L)
        assertFalse(first.isOriginal)
        assertEquals(22_222L, first.mediaUs)
        assertEquals(0L, first.before.id)
        assertEquals(1L, first.after.id)
        assertEquals(0.5333, first.alpha, 0.0001)
        assertTrue(timeline.commit(first, first.dueNs))
        val second = ready(timeline, lease, 44_444_444L)
        assertEquals(1L, second.before.id)
        assertEquals(2L, second.after.id)
        assertEquals(0.0666, second.alpha, 0.0001)
    }

    fun `all target cadences keep nanosecond remainder without long term drift`() {
        for (fps in listOf(15, 30, 45, 60, 120, 144, 165, 185)) {
            val timeline = FrameTimeline(fps)
            val lease = timeline.begin(epoch).lease
            for (index in 0..fps * 4) {
                val due = index.toLong() * 1_000_000_000L / fps
                val media = (due / 1_000.0).roundToLong()
                timeline.updateClock(lease, clock(media, due))
                assertTrue(timeline.offer(lease, Frame(index.toLong(), media)).accepted)
                val proposal = ready(timeline, lease, due)
                assertEquals(due, proposal.dueNs)
                assertTrue(proposal.isOriginal)
                assertTrue(timeline.commit(proposal, due))
            }
        }
    }

    fun `low frame sources reserve motion interpolation at every higher target slot`() {
        for (fps in listOf(120, 144, 165, 185)) {
            for (sourceIntervalUs in listOf(33_333L, 41_667L)) {
                val timeline = FrameTimeline(fps)
                val lease = timeline.begin(epoch).lease
                timeline.updateClock(lease, clock())
                timeline.offer(lease, Frame(1L, 0L))
                timeline.offer(lease, Frame(2L, sourceIntervalUs))
                assertTrue(timeline.commit(ready(timeline, lease, 0L), 0L))
                var tick = 1L
                while ((tick * 1_000_000_000L / fps / 1_000.0).roundToLong() < sourceIntervalUs) {
                    val due = tick * 1_000_000_000L / fps
                    val proposal = ready(timeline, lease, due)
                    assertEquals(due, proposal.dueNs)
                    assertEquals(Mode.INTERPOLATE, proposal.mode)
                    assertEquals(1L, proposal.before.id)
                    assertEquals(2L, proposal.after.id)
                    assertEquals((due / 1_000.0).roundToLong().toDouble() / sourceIntervalUs,
                        proposal.alpha, 0.0000001)
                    assertTrue(timeline.commit(proposal, due))
                    tick++
                }
                assertEquals(Reason.NO_FUTURE_FRAME,
                    waiting(timeline, lease, sourceIntervalUs * 1_000L + 1_000L))
            }
        }
    }

    fun `real time target respects double speed instead of doubling target FPS`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock(5_000L, speed = 2.0))
        listOf(0L, 33_333L, 66_667L).forEachIndexed { id, pts -> timeline.offer(lease, Frame(id.toLong(), pts)) }
        assertTrue(timeline.commit(ready(timeline, lease, 0L), 0L))
        val proposal = ready(timeline, lease, 16_666_666L)
        assertEquals(16_666_666L, proposal.dueNs)
        assertEquals(38_333L, proposal.mediaUs)
        assertTrue(proposal.isOriginal)
        assertEquals(Mode.ORIGINAL, proposal.mode)
        assertEquals(1L, proposal.before.id)
    }

    fun `VFR uses enclosing PTS instead of inferring a frame number`() {
        val timeline = FrameTimeline(30)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock())
        listOf(0L, 40_000L, 100_000L).forEachIndexed { id, pts -> timeline.offer(lease, Frame(id.toLong(), pts)) }
        timeline.commit(ready(timeline, lease, 0L), 0L)
        val proposal = ready(timeline, lease, 66_666_666L)
        assertEquals(1L, proposal.before.id)
        assertEquals(2L, proposal.after.id)
        assertEquals(0.44445, proposal.alpha, 0.00001)
        assertEquals(Mode.INTERPOLATE, proposal.mode)
    }

    fun `high frame source selects nearest originals at 15 and 30 without model work`() {
        for (fps in listOf(15, 30)) {
            val timeline = FrameTimeline(fps)
            val lease = timeline.begin(epoch).lease
            timeline.updateClock(lease, clock(5_000L))
            listOf(0L, 16_667L, 33_333L, 50_000L).forEachIndexed { id, pts -> timeline.offer(lease, Frame(id.toLong(), pts)) }
            assertTrue(timeline.commit(ready(timeline, lease, 0L), 0L))
            if (fps == 15) {
                timeline.offer(lease, Frame(4L, 66_667L)); timeline.offer(lease, Frame(5L, 83_333L))
            }
            val due = 1_000_000_000L / fps
            val proposal = ready(timeline, lease, due)
            assertEquals(Mode.ORIGINAL, proposal.mode)
            assertEquals(proposal.before, proposal.after)
            assertEquals(0.0, proposal.alpha, 0.0)
            assertEquals(due, proposal.dueNs)
            assertEquals(if (fps == 15) 66_667L else 33_333L, proposal.before.ptsUs)
            assertEquals(if (fps == 15) 71_667L else 38_333L, proposal.mediaUs)
        }
    }

    fun `VFR switches between source selection and interpolation using actual interval`() {
        val timeline = FrameTimeline(30)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock(5_000L))
        listOf(0L, 10_000L, 20_000L, 70_000L).forEachIndexed { id, pts -> timeline.offer(lease, Frame(id.toLong(), pts)) }
        val fast = ready(timeline, lease, 0L)
        assertEquals(Mode.ORIGINAL, fast.mode)
        assertEquals(0L, fast.before.ptsUs) // 距离相同，保留前帧。
        timeline.commit(fast, 0L)
        val slow = ready(timeline, lease, 33_333_333L)
        assertEquals(Mode.INTERPOLATE, slow.mode)
        assertEquals(20_000L, slow.before.ptsUs)
        assertEquals(70_000L, slow.after.ptsUs)
        assertEquals(0.36666, slow.alpha, 0.00001)
        timeline.commit(slow, slow.dueNs)
        timeline.offer(lease, Frame(4L, 90_000L))
        val fastAgain = ready(timeline, lease, 66_666_666L)
        assertEquals(Mode.ORIGINAL, fastAgain.mode)
        assertEquals(70_000L, fastAgain.before.ptsUs)
    }

    fun `missing adjacent frame waits without repeating or fabricating a source frame`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock())
        timeline.offer(lease, Frame(1L, 33_333L))
        assertEquals(Reason.NO_PREVIOUS_FRAME, waiting(timeline, lease, 0L))
        timeline.begin(epoch).also { fresh ->
            timeline.updateClock(fresh.lease, clock())
            timeline.offer(fresh.lease, Frame(2L, 0L))
            timeline.commit(ready(timeline, fresh.lease, 0L), 0L)
            assertEquals(Reason.NO_FUTURE_FRAME, waiting(timeline, fresh.lease, 16_666_666L))
            timeline.offer(fresh.lease, Frame(3L, 33_333L))
            assertFalse(ready(timeline, fresh.lease, 16_666_666L).isOriginal)
        }
    }

    fun `single pending proposal cannot schedule duplicates and expired slots are skipped`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock())
        timeline.offer(lease, Frame(1L, 0L)); timeline.offer(lease, Frame(2L, 100_000L))
        val old = ready(timeline, lease, 0L)
        assertEquals(Reason.IN_FLIGHT, waiting(timeline, lease, 0L))
        val fresh = ready(timeline, lease, 50_000_000L)
        assertEquals(50_000_000L, fresh.dueNs)
        assertEquals(0.5, fresh.alpha, 0.0)
        assertFalse(timeline.valid(old, 50_000_000L))
        assertFalse(timeline.commit(old, 50_000_000L))
        assertTrue(timeline.valid(fresh, 50_000_000L))
        assertTrue(timeline.commit(fresh, 50_000_000L))
    }

    fun `output preparation never runs more than one target period ahead`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock())
        timeline.offer(lease, Frame(1L, 0L)); timeline.offer(lease, Frame(2L, 100_000L))
        timeline.commit(ready(timeline, lease, 0L), 0L)
        val next = ready(timeline, lease, 0L)
        assertEquals(16_666_666L, next.dueNs)
        assertTrue(timeline.commit(next, 0L))
        assertEquals(Reason.AHEAD_OF_WINDOW, waiting(timeline, lease, 0L))
    }

    fun `fresh clock revision invalidates in flight work without losing frame ownership`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock())
        timeline.offer(lease, Frame(1L, 0L)); timeline.offer(lease, Frame(2L, 33_333L))
        timeline.commit(ready(timeline, lease, 0L), 0L)
        val old = ready(timeline, lease, 0L)
        val update = timeline.updateClock(lease, clock(2_000L, 1_000_000L))
        assertEquals(ClockState.READY, update.state)
        assertTrue(update.discardedIds.isEmpty())
        assertFalse(timeline.valid(old, 1_000_000L))
        val fresh = ready(timeline, lease, 1_000_000L)
        assertEquals(old.dueNs, fresh.dueNs)
        assertEquals(listOf(1L, 2L), timeline.close(lease))
    }

    fun `consistent frequent master clock updates preserve useful in flight work`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock())
        timeline.offer(lease, Frame(1L, 0L)); timeline.offer(lease, Frame(2L, 33_333L))
        timeline.commit(ready(timeline, lease, 0L), 0L)
        val proposal = ready(timeline, lease, 0L)
        for (millis in 1L..10L) {
            assertEquals(ClockState.READY, timeline.updateClock(lease, clock(millis * 1_000L, millis * 1_000_000L)).state)
            assertTrue(timeline.valid(proposal, millis * 1_000_000L))
        }
        assertTrue(timeline.commit(proposal, 10_000_000L))
    }

    fun `bounded millisecond clock quantization does not starve in flight interpolation`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock(100_000L, uncertaintyUs = 1_000L))
        timeline.offer(lease, Frame(1L, 100_000L)); timeline.offer(lease, Frame(2L, 133_333L))
        timeline.commit(ready(timeline, lease, 0L), 0L)
        val proposal = ready(timeline, lease, 0L)
        assertEquals(1_000L, proposal.uncertaintyUs)
        listOf(400L, -300L, 900L, -800L, 100L, -900L, 700L, -600L, 200L, -100L).forEachIndexed { index, jitter ->
            val millis = index + 1L
            assertEquals(ClockState.READY, timeline.updateClock(lease,
                clock(100_000L + millis * 1_000L + jitter, millis * 1_000_000L, uncertaintyUs = 1_000L)).state)
            assertTrue(timeline.valid(proposal, millis * 1_000_000L))
        }
        assertTrue(timeline.commit(proposal, 10_000_000L))
    }

    fun `clock error budget is compared to original proposal and never accumulates drift`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock(100_000L, uncertaintyUs = 1_000L))
        timeline.offer(lease, Frame(1L, 100_000L)); timeline.offer(lease, Frame(2L, 133_333L))
        timeline.commit(ready(timeline, lease, 0L), 0L)
        val proposal = ready(timeline, lease, 0L)
        for (index in 1L..4L) {
            timeline.updateClock(lease, clock(100_000L + index * 1_500L, index * 1_000_000L, uncertaintyUs = 1_000L))
            assertTrue(timeline.valid(proposal, index * 1_000_000L))
        }
        timeline.updateClock(lease, clock(107_500L, 5_000_000L, uncertaintyUs = 1_000L))
        assertFalse(timeline.valid(proposal, 5_000_000L))
        val fresh = ready(timeline, lease, 5_000_000L)
        assertNotEquals(proposal.mediaUs, fresh.mediaUs)
    }

    fun `stale clock blocks proposals and delayed snapshots cannot revoke a fresh one`() {
        val timeline = FrameTimeline(60, maxClockAgeNs = 100_000_000L)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock(1_000L, 1_000_000L))
        timeline.offer(lease, Frame(1L, 1_000L)); timeline.offer(lease, Frame(2L, 100_000L))
        val proposal = ready(timeline, lease, 1_000_000L)
        assertEquals(ClockState.STALE_SNAPSHOT, timeline.updateClock(lease, clock()).state)
        assertTrue(timeline.valid(proposal, 1_000_000L))
        assertEquals(Reason.STALE_CLOCK, waiting(timeline, lease, 101_000_001L))
        assertFalse(timeline.valid(proposal, 101_000_001L))
        assertFalse(timeline.valid(proposal, 1_000_000L))
    }

    fun `pause speed and unpause changes revoke lease and return owned frame IDs`() {
        for (changed in listOf(clock(paused = true), clock(speed = 2.0))) {
            val timeline = FrameTimeline(60)
            val oldLease = timeline.begin(epoch).lease
            timeline.updateClock(oldLease, clock())
            timeline.offer(oldLease, Frame(1L, 0L))
            val pending = ready(timeline, oldLease, 0L)
            val update = timeline.updateClock(oldLease, changed)
            assertEquals(ClockState.RESTART_REQUIRED, update.state)
            assertEquals(listOf(1L), update.discardedIds)
            assertFalse(timeline.valid(pending, 0L))
            assertEquals(listOf(2L), timeline.offer(oldLease, Frame(2L, 33_333L)).discardedIds)
            val fresh = timeline.begin(epoch).lease
            timeline.updateClock(fresh, changed)
            timeline.offer(fresh, Frame(3L, 0L))
            if (changed.paused) {
                assertEquals(Reason.PAUSED, waiting(timeline, fresh, 0L))
                assertEquals(ClockState.RESTART_REQUIRED, timeline.updateClock(fresh, clock()).state)
            } else assertTrue(ready(timeline, fresh, 0L).isOriginal)
        }
    }

    fun `all generation changes and same epoch reentry revoke old results and late frame tasks`() {
        val changed = listOf(epoch, epoch.copy(media = 2L), epoch.copy(decoder = 2L), epoch.copy(surface = 2L),
            epoch.copy(format = 2L), epoch.copy(discontinuity = 2L))
        for (nextEpoch in changed) {
            val timeline = FrameTimeline(60)
            val oldLease = timeline.begin(epoch).lease
            timeline.updateClock(oldLease, clock())
            timeline.offer(oldLease, Frame(1L, 0L))
            val old = ready(timeline, oldLease, 0L)
            val next = timeline.begin(nextEpoch)
            assertNotSame(oldLease, next.lease)
            assertEquals(listOf(1L), next.discardedIds)
            assertFalse(timeline.valid(old, 0L))
            assertEquals(listOf(2L), timeline.offer(oldLease, Frame(2L, 0L)).discardedIds)
            timeline.updateClock(next.lease, clock(discontinuity = nextEpoch.discontinuity))
            timeline.offer(next.lease, Frame(3L, 0L))
            val fresh = ready(timeline, next.lease, 0L)
            assertFalse(timeline.commit(old, 0L))
            assertTrue(timeline.valid(fresh, 0L))
        }
    }

    fun `changing target or disabling releases inputs and cannot revive an old proposal`() {
        for (fps in listOf(45, 120, 144, 165, 185)) {
            val timeline = FrameTimeline(60)
            val oldLease = timeline.begin(epoch).lease
            timeline.offer(oldLease, Frame(1L, 0L))
            val old = readyAfterClock(timeline, oldLease)
            val next = timeline.begin(epoch, targetFps = fps)
            assertEquals(listOf(1L), next.discardedIds)
            assertFalse(timeline.valid(old, 0L))
            timeline.updateClock(next.lease, clock())
            timeline.offer(next.lease, Frame(2L, 0L))
            assertTrue(timeline.commit(ready(timeline, next.lease, 0L), 0L))
            timeline.offer(next.lease, Frame(3L, 100_000L))
            assertEquals(1_000_000_000L / fps, ready(timeline, next.lease, 0L).dueNs)
            val off = timeline.begin(epoch, targetFps = 0)
            assertEquals(listOf(2L, 3L), off.discardedIds)
            assertEquals(Reason.DISABLED, waiting(timeline, off.lease, 0L))
        }
    }

    fun `leases are bound to timeline identity even with equal epoch values`() {
        val first = FrameTimeline(60)
        val second = FrameTimeline(60)
        val firstLease = first.begin(epoch).lease
        val secondLease = second.begin(epoch).lease
        second.updateClock(secondLease, clock())
        second.offer(secondLease, Frame(1L, 0L))
        assertEquals(Reason.STALE_LEASE, waiting(second, firstLease, 0L))
        assertEquals(ClockState.STALE_LEASE, second.updateClock(firstLease, clock(paused = true)).state)
        assertTrue(second.close(firstLease).isEmpty())
        assertTrue(ready(second, secondLease, 0L).isOriginal)
    }

    fun `bounded eviction replacement and close expose every transferred frame ID`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock())
        for (id in 1L..4L) assertTrue(timeline.offer(lease, Frame(id, (id - 1L) * 10_000L)).discardedIds.isEmpty())
        val old = ready(timeline, lease, 0L)
        assertEquals(listOf(1L), timeline.offer(lease, Frame(5L, 40_000L)).discardedIds)
        assertFalse(timeline.valid(old, 0L))
        assertEquals(listOf(5L), timeline.offer(lease, Frame(6L, 40_000L)).discardedIds)
        assertFalse(timeline.offer(lease, Frame(6L, 50_000L)).accepted)
        assertTrue(timeline.offer(lease, Frame(6L, 50_000L)).discardedIds.isEmpty())
        assertEquals(listOf(2L, 3L, 4L, 6L), timeline.close(lease))
        assertTrue(timeline.close(lease).isEmpty())
    }

    fun `replacement revokes pending input while rejected out of order frame leaves it intact`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock())
        timeline.offer(lease, Frame(1L, 0L)); timeline.offer(lease, Frame(2L, 33_333L))
        timeline.commit(ready(timeline, lease, 0L), 0L)
        val old = ready(timeline, lease, 0L)
        assertEquals(listOf(3L), timeline.offer(lease, Frame(3L, 10_000L)).discardedIds)
        assertTrue(timeline.valid(old, 0L))
        assertEquals(listOf(2L), timeline.offer(lease, Frame(4L, 33_333L)).discardedIds)
        assertFalse(timeline.valid(old, 0L))
        assertEquals(4L, ready(timeline, lease, 0L).after.id)
    }

    fun `unknown invalid or discontinuous clock releases frames and requires a fresh lease`() {
        for (snapshot in listOf(null, clock(mediaUs = -1L), clock(speed = Double.NaN),
            clock(speed = Double.POSITIVE_INFINITY), clock(speed = -1.0), clock(speed = 0.0),
            clock(discontinuity = 2L), clock(uncertaintyUs = -1L), clock(uncertaintyUs = 2_001L))) {
            val timeline = FrameTimeline(60)
            val lease = timeline.begin(epoch).lease
            timeline.offer(lease, Frame(1L, 0L))
            val pending = readyAfterClock(timeline, lease)
            val update = timeline.updateClock(lease, snapshot)
            assertEquals(listOf(1L), update.discardedIds)
            assertFalse(timeline.valid(pending, 0L))
            assertEquals(ClockState.STALE_LEASE, timeline.updateClock(lease, clock()).state)
        }
    }

    fun `unflagged backward media clock cannot interpolate across a seek`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock(100_000L, 0L))
        timeline.offer(lease, Frame(1L, 100_000L))
        val pending = ready(timeline, lease, 0L)
        assertEquals(ClockState.RESTART_REQUIRED, timeline.updateClock(lease, clock(50_000L, 1_000L)).state)
        assertFalse(timeline.valid(pending, 0L))
        assertEquals(Reason.STALE_LEASE, waiting(timeline, lease, 1_000L))
    }

    fun `deadline and future clock checks prevent expired or time reversed commits`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock(0L, 1_000L))
        timeline.offer(lease, Frame(1L, 0L))
        assertEquals(Reason.INVALID_TIME, waiting(timeline, lease, 999L))
        val proposal = ready(timeline, lease, 1_000L)
        assertFalse(timeline.valid(proposal, 999L))
        assertFalse(timeline.commit(proposal, 1_001L))
        assertFalse(timeline.commit(proposal, 1_000L))
    }

    fun `timestamp overflow or unrepresentable media mapping never creates a proposal`() {
        val timeline = FrameTimeline(60)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock(0L, Long.MAX_VALUE - 1L))
        timeline.offer(lease, Frame(1L, 0L))
        timeline.commit(ready(timeline, lease, Long.MAX_VALUE - 1L), Long.MAX_VALUE - 1L)
        assertEquals(Reason.INVALID_TIME, waiting(timeline, lease, Long.MAX_VALUE - 1L))

        val fresh = timeline.begin(epoch).lease
        timeline.updateClock(fresh, clock(speed = Double.MAX_VALUE))
        timeline.offer(fresh, Frame(2L, 0L))
        timeline.commit(ready(timeline, fresh, 0L), 0L)
        assertEquals(Reason.INVALID_TIME, waiting(timeline, fresh, 16_666_666L))
    }

    fun `unsupported target and nonpositive clock age are rejected before use`() {
        for (fps in listOf(-1, 24, 119, 121, 143, 145, 164, 166, 184, 186, 240)) {
            try { FrameTimeline(fps); fail("Unsupported target") } catch (_: IllegalArgumentException) { }
        }
        try { FrameTimeline(60, 0L); fail("Invalid clock lifetime") } catch (_: IllegalArgumentException) { }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FrameTimelineCases) -> Unit) {
            val s = FrameTimelineCases()
            block(s)
        }
        runIsolated { s -> s.`disabled target and missing verified clock never generate an output proposal`() }
        runIsolated { s -> s.`24 to 45 uses different actual PTS pairs and arbitrary alpha`() }
        runIsolated { s -> s.`all target cadences keep nanosecond remainder without long term drift`() }
        runIsolated { s -> s.`low frame sources reserve motion interpolation at every higher target slot`() }
        runIsolated { s -> s.`real time target respects double speed instead of doubling target FPS`() }
        runIsolated { s -> s.`VFR uses enclosing PTS instead of inferring a frame number`() }
        runIsolated { s -> s.`high frame source selects nearest originals at 15 and 30 without model work`() }
        runIsolated { s -> s.`VFR switches between source selection and interpolation using actual interval`() }
        runIsolated { s -> s.`missing adjacent frame waits without repeating or fabricating a source frame`() }
        runIsolated { s -> s.`single pending proposal cannot schedule duplicates and expired slots are skipped`() }
        runIsolated { s -> s.`output preparation never runs more than one target period ahead`() }
        runIsolated { s -> s.`fresh clock revision invalidates in flight work without losing frame ownership`() }
        runIsolated { s -> s.`consistent frequent master clock updates preserve useful in flight work`() }
        runIsolated { s -> s.`bounded millisecond clock quantization does not starve in flight interpolation`() }
        runIsolated { s -> s.`clock error budget is compared to original proposal and never accumulates drift`() }
        runIsolated { s -> s.`stale clock blocks proposals and delayed snapshots cannot revoke a fresh one`() }
        runIsolated { s -> s.`pause speed and unpause changes revoke lease and return owned frame IDs`() }
        runIsolated { s -> s.`all generation changes and same epoch reentry revoke old results and late frame tasks`() }
        runIsolated { s -> s.`changing target or disabling releases inputs and cannot revive an old proposal`() }
        runIsolated { s -> s.`leases are bound to timeline identity even with equal epoch values`() }
        runIsolated { s -> s.`bounded eviction replacement and close expose every transferred frame ID`() }
        runIsolated { s -> s.`replacement revokes pending input while rejected out of order frame leaves it intact`() }
        runIsolated { s -> s.`unknown invalid or discontinuous clock releases frames and requires a fresh lease`() }
        runIsolated { s -> s.`unflagged backward media clock cannot interpolate across a seek`() }
        runIsolated { s -> s.`deadline and future clock checks prevent expired or time reversed commits`() }
        runIsolated { s -> s.`timestamp overflow or unrepresentable media mapping never creates a proposal`() }
        runIsolated { s -> s.`unsupported target and nonpositive clock age are rejected before use`() }
    }
}

private class FrameVsyncGridCases {
    private val display120 = 8_333_333L
    private val content90 = 11_111_111L

    private fun FrameVsyncGrid.feed(from: Long, count: Int, period: Long, skip: (Int) -> Boolean = { false }) {
        for (i in 0 until count) if (!skip(i)) onVsync(from + i * period)
    }

    fun `display period converges to the panel and ignores skipped callbacks`() {
        val grid = FrameVsyncGrid(content90)
        // 每 5 个回调漏 1 个：整倍数间隔不能拉长估计。
        grid.feed(1_000_000_000L, 120, display120) { it % 5 == 4 }
        assertEquals(display120.toDouble(), grid.periodNs.toDouble(), 50_000.0)
    }

    fun `a panel idling below the target is followed at its real period`() {
        val grid = FrameVsyncGrid(content90)
        val period60 = 16_666_666L
        grid.feed(1L, 40, period60)
        assertEquals(period60, grid.periodNs)
        assertTrue(grid.slowerThanContent())
        // 面板随后升到 120 Hz：约 10 个短间隔（不到 0.1 s）后生效。
        grid.feed(grid.lastNs + display120, 10, display120)
        assertEquals(display120, grid.periodNs)
        assertFalse(grid.slowerThanContent())
    }

    fun `a skipped callback still advances the grid one refresh at a time`() {
        val grid = FrameVsyncGrid(content90)
        grid.feed(0L + 1, 60, display120)
        val last = grid.lastNs
        // 驱动线程漏掉下一个回调：逾期 2 ms 后外推，仍落在真实 vsync 上；逾期前沿用最近一次回调。
        assertEquals(last + grid.periodNs, grid.current(last + display120 + 2_500_000L))
        assertEquals(last, grid.current(last + display120 + 1_000_000L))
        assertEquals(last, grid.current(last + 1_000_000L))
    }

    /**
     * 30→90 fps 在 120 Hz 面板上，驱动回调频繁漏掉：每个刷新至多放行一帧，按 vsync 逐个交付不作废任何一帧
     * （只看最近回调时，漏掉一个回调会让两帧同时可提交、前一帧被作废）。
     */
    fun `90 fps content on a 120 Hz panel never releases two frames in one refresh`() {
        val grid = FrameVsyncGrid(content90)
        val start = 1_000_000_000L
        grid.feed(start, 30, display120)
        val base = grid.lastNs
        val dues = ArrayDeque((0 until 200).map { base + 30_000_000L + it * content90 })
        var dropped = 0
        var released = 0
        for (k in 1..300) {
            val vsync = base + k * display120
            // 驱动线程忙：约三分之一的 vsync 没有回调；呈现线程在回调后 0.5 ms，或逾期外推时刻之后复查。
            val delivered = k % 3 != 0
            if (delivered) grid.onVsync(vsync)
            val now = vsync + if (delivered) 500_000L else 2_500_000L
            val head = dues.firstOrNull() ?: break
            if (!grid.releasable(head, now)) continue
            dues.removeFirst()
            val next = dues.firstOrNull()
            if (next != null && grid.releasable(next, now)) dropped++ else released++
        }
        assertEquals(0, dropped)
        assertTrue("released $released", released > 150)
    }

    fun `60 fps content on a 60 Hz panel keeps the previous release window`() {
        val period = 16_666_666L
        val grid = FrameVsyncGrid(period)
        grid.feed(1L, 30, period)
        val vsync = grid.lastNs
        val now = vsync + 2_000_000L
        assertTrue(grid.releasable(vsync + period + period / 2, now))
        assertFalse(grid.releasable(vsync + period + period / 2 + 1_000L, now))
    }

    /** 120 Hz 面板播 60 fps：迟交的本帧与下一帧都可提交时，下一帧属于后一次刷新，本帧不应作废。 */
    fun `a faster panel leaves a spare refresh before the next frame`() {
        val content = 16_666_666L
        val grid = FrameVsyncGrid(content)
        grid.feed(1L, 40, display120)
        val v = grid.lastNs
        val now = v + 500_000L
        val head = v + 2_000_000L
        val next = head + content
        assertTrue(grid.releasable(next, now))
        assertFalse(grid.sameRefresh(next, now))
        // 60 Hz 面板：同样的两帧落在同一次刷新，前一帧让位。
        val grid60 = FrameVsyncGrid(content)
        grid60.feed(1L, 40, content)
        val v60 = grid60.lastNs
        assertTrue(grid60.sameRefresh(v60 + 2_000_000L + content, v60 + 500_000L))
    }

    /** Choreographer 抖动校正偶尔给出偏短的间隔：估计不被拉短，外推的 vsync 不早于真实 vsync。 */
    fun `occasional short intervals do not shorten the period`() {
        val grid = FrameVsyncGrid(16_666_666L)
        var t = 1L
        for (i in 0 until 64) { t += if (i % 10 == 5) 12_000_000L else if (i % 10 == 6) 21_333_332L else 16_666_666L; grid.onVsync(t) }
        assertEquals(16_666_666.0, grid.periodNs.toDouble(), 1_000.0)
    }

    fun `stops following when callbacks stop`() {
        val grid = FrameVsyncGrid(content90)
        assertTrue(grid.stale(0L))
        grid.onVsync(1_000L)
        assertFalse(grid.stale(1_000L + 3 * content90))
        assertTrue(grid.stale(1_000L + 4 * content90))
        assertFalse("time not advancing is ignored", grid.onVsync(1_000L))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FrameVsyncGridCases) -> Unit) {
            val s = FrameVsyncGridCases()
            block(s)
        }
        runIsolated { s -> s.`display period converges to the panel and ignores skipped callbacks`() }
        runIsolated { s -> s.`a panel idling below the target is followed at its real period`() }
        runIsolated { s -> s.`a skipped callback still advances the grid one refresh at a time`() }
        runIsolated { s -> s.`90 fps content on a 120 Hz panel never releases two frames in one refresh`() }
        runIsolated { s -> s.`60 fps content on a 60 Hz panel keeps the previous release window`() }
        runIsolated { s -> s.`a faster panel leaves a spare refresh before the next frame`() }
        runIsolated { s -> s.`occasional short intervals do not shorten the period`() }
        runIsolated { s -> s.`stops following when callbacks stop`() }
    }
}
