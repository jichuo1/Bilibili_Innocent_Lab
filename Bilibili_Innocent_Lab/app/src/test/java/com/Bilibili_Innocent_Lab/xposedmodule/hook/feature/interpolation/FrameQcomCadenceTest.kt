package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameSourceCadence.Verdict
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ReadOnlyBufferException
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameQcomCadenceTest {
    fun repeatedForwardGapsRejectEvenWhenEveryPairNeedsANewAnchor() {
        val cadence = FrameQcomCadence(33_333_333L)
        repeat(7) { assertFalse(cadence.observe(166_666_667L)); assertFalse(cadence.unsuitable) }
        assertFalse(cadence.observe(166_666_667L))
        assertTrue(cadence.unsuitable)
    }

    fun validPairsAndExplicitFlushStartANewCadenceObservation() {
        val cadence = FrameQcomCadence(33_333_333L)
        repeat(7) { cadence.observe(500_000_000L) }
        assertTrue(cadence.observe(33_366_667L))
        assertFalse(cadence.observe(-1L))
        assertFalse(cadence.unsuitable)
        repeat(6) { cadence.observe(200_000_000L) }
        cadence.reset()
        assertFalse(cadence.observe(200_000_000L))
        assertFalse(cadence.unsuitable)
    }

    fun repeatedHostOutputCallsKeepTheProducerUntilTheLastPlatformReturn() {
        val lease = FrameQcomOutputBorrow<Any>()
        val display = Any()
        val input = Any()
        lease.connect(display, input)
        assertNull(lease.borrow(Any()))
        assertSame(input, lease.borrow(display))
        assertSame(input, lease.borrow(display))
        assertFalse(lease.revoke())
        assertNull(lease.borrow(display))
        assertFalse(lease.finish())
        assertTrue(lease.finish())
        assertTrue(lease.revoke())
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FrameQcomCadenceTest) -> Unit) {
            val s = FrameQcomCadenceTest()
            block(s)
        }
        runIsolated { s -> s.repeatedForwardGapsRejectEvenWhenEveryPairNeedsANewAnchor() }
        runIsolated { s -> s.validPairsAndExplicitFlushStartANewCadenceObservation() }
        runIsolated { s -> s.repeatedHostOutputCallsKeepTheProducerUntilTheLastPlatformReturn() }
    
        FrameRateTargetCases().verifyAllCases()
        FrameSceneChangeCases().verifyAllCases()
        FrameScratchArenaCases().verifyAllCases()
        FrameSourceCadenceCases().verifyAllCases()
        FrameStaticSceneCases().verifyAllCases()
    }
}

private class FrameRateTargetCases {
    private val ltpo = listOf(1f, 10f, 24f, 30f, 60f, 90f, 120f, 144f)
    private val rooted = ltpo + listOf(165f, 185f)
    private fun choose(source: Float?, ceiling: Int, modes: List<Float> = ltpo, measured: Float? = null, ms: Double = 5.0) =
        FrameRateTarget.choose(source, ceiling, modes, measured, ms)

    fun midpointRouteChoosesOneIntermediateWithoutAResearchModelTiming() {
        assertEquals(60, FrameRateTarget.midpoint(29.97f, 120, ltpo, null))
        assertEquals(50, FrameRateTarget.midpoint(25f, 60, listOf(60f), null))
        assertNull(FrameRateTarget.midpoint(30f, 120, ltpo, 45f))
        assertNull(FrameRateTarget.midpoint(60f, 60, ltpo, null))
    }

    fun truncatedIntegerOperatingRatesMapToNtscNominalRatesOnlyWithoutExplicitFrameRate() {
        assertEquals(30f, FrameRateTarget.adjustOperatingRateHint(29f, hasExplicitFrameRate = false), 0f)
        assertEquals(24f, FrameRateTarget.adjustOperatingRateHint(23f, hasExplicitFrameRate = false), 0f)
        assertEquals(29f, FrameRateTarget.adjustOperatingRateHint(29f, hasExplicitFrameRate = true), 0f)
        assertEquals(23f, FrameRateTarget.adjustOperatingRateHint(23f, hasExplicitFrameRate = true), 0f)
        assertEquals(25f, FrameRateTarget.adjustOperatingRateHint(25f, hasExplicitFrameRate = false), 0f)
        assertEquals(48, FrameRateTarget.midpoint(
            FrameRateTarget.adjustOperatingRateHint(23f, hasExplicitFrameRate = false), 120, ltpo, null))
        assertEquals(60, choose(
            FrameRateTarget.adjustOperatingRateHint(29f, hasExplicitFrameRate = false), 60))
    }

    fun `default ceiling keeps the validated 60 and 50 fps behaviour`() {
        assertEquals(60, choose(30f, 60))
        assertEquals(60, choose(29.97f, 60))
        assertEquals(50, choose(25f, 60))
        assertEquals(60, choose(null, 60))
    }

    fun `the system limit caps a higher ceiling`() {
        // 用户设 185，但系统实际只给 60（LTPO 对视频应用的策略）或 120：按实际上限补。
        assertEquals(60, choose(30f, 185, measured = 60f))
        assertEquals(120, choose(30f, 185, measured = 120f))
        assertEquals(144, choose(24f, 185, measured = 144f))
        // root 后实际给到 185：30 fps 源取 180（185 不是 30 的倍数，且需 180 或 360 Hz 模式）。
        assertEquals(180, choose(30f, 185, rooted + listOf(180f), measured = 180f))
        assertEquals(150, choose(30f, 185, rooted + listOf(150f), measured = 185f))
    }

    fun `targets must be presentable by a display mode`() {
        // 30→90 只有在存在 90 Hz（或 180 Hz）模式时才选；否则退到 60。
        assertEquals(90, choose(30f, 90))
        assertEquals(60, choose(30f, 90, listOf(60f, 120f)))
        assertEquals(120, choose(24f, 120))
        assertEquals(72, choose(24f, 90, listOf(60f, 72f, 144f)))
    }

    fun `inference capacity bounds the number of synthetic frames`() {
        // 每秒可持续补帧 0.8 × 1000 / ms：15 ms → 53 次/秒，30 fps 源最多补到 60（30 次/秒）而非 90（60 次/秒）。
        assertEquals(60, choose(30f, 185, measured = 144f, ms = 15.0))
        assertEquals(120, choose(30f, 185, measured = 144f, ms = 8.0))
        assertNull(choose(30f, 60, ms = 30.0))
    }

    fun `any tier the engine accepts keeps 30 to 60 interpolating`() {
        // 引擎档位上限：1080p 1/4 档 26 ms、其余 24 ms；被接受的档位不能再被帧率选择拒绝。
        for (ms in listOf(FrameInterpolationEngineHolder.FINE_TIER_LIMIT_MS, FrameInterpolationEngineHolder.CAPABILITY_LIMIT_MS))
            assertEquals("ms=$ms", 60, choose(30f, 60, ms = ms))
        assertEquals(50, choose(25f, 60, ms = FrameInterpolationEngineHolder.FINE_TIER_LIMIT_MS))
    }

    fun `sources already at or near the limit and unsupported rates are not interpolated`() {
        assertNull(choose(60f, 60))
        assertNull(choose(30f, 60, measured = 45f))
        assertNull(choose(15f, 60))
        assertNull(choose(50f, 90))
    }

    fun `measured vsync snaps to the nearest panel mode`() {
        assertEquals(120f, FrameRateTarget.snap(118.7f, ltpo))
        assertEquals(60f, FrameRateTarget.snap(60.4f, ltpo))
        assertEquals(75f, FrameRateTarget.snap(75f, ltpo))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FrameRateTargetCases) -> Unit) {
            val s = FrameRateTargetCases()
            block(s)
        }
        runIsolated { s -> s.midpointRouteChoosesOneIntermediateWithoutAResearchModelTiming() }
        runIsolated { s -> s.truncatedIntegerOperatingRatesMapToNtscNominalRatesOnlyWithoutExplicitFrameRate() }
        runIsolated { s -> s.`default ceiling keeps the validated 60 and 50 fps behaviour`() }
        runIsolated { s -> s.`the system limit caps a higher ceiling`() }
        runIsolated { s -> s.`targets must be presentable by a display mode`() }
        runIsolated { s -> s.`inference capacity bounds the number of synthetic frames`() }
        runIsolated { s -> s.`any tier the engine accepts keeps 30 to 60 interpolating`() }
        runIsolated { s -> s.`sources already at or near the limit and unsupported rates are not interpolated`() }
        runIsolated { s -> s.`measured vsync snaps to the nearest panel mode`() }
    }
}

private class FrameSceneChangeCases {
    private val format = FrameBufferLedger.Format(128, 72, 128 * 3 + 7)
    private fun frame(pixel: (Int, Int, Int) -> Int) = ByteBuffer.allocate(format.bytes).also { b ->
        for (y in 0 until format.height) for (x in 0 until format.width) for (c in 0..2)
            b.put(y * format.stride + x * 3 + c, pixel(x, y, c).toByte())
    }

    fun `hard cuts and full flashes hold the source instead of morphing`() {
        assertTrue(FrameSceneChange.isCut(frame { _, _, _ -> 10 }, frame { _, _, _ -> 240 }, format))
        assertTrue(FrameSceneChange.isCut(frame { _, _, c -> if (c == 0) 255 else 0 },
            frame { _, _, c -> if (c == 2) 255 else 0 }, format))
    }

    fun `large pan preserving color distribution is not a cut`() {
        val a = frame { x, y, _ -> if ((x / 8 + y / 8) % 2 == 0) 10 else 240 }
        val b = frame { x, y, _ -> if (((x + 8) / 8 + y / 8) % 2 == 0) 10 else 240 }
        assertFalse(FrameSceneChange.isCut(a, b, format))
    }

    fun `subtitle replacement and gradual fades do not disable the whole frame`() {
        val a = frame { x, y, _ -> (x + y) % 128 }
        val subtitle = frame { x, y, _ -> if (y in 60..65 && x in 10..100) 255 else (x + y) % 128 }
        assertFalse(FrameSceneChange.isCut(a, subtitle, format))
        assertFalse(FrameSceneChange.isCut(a, frame { x, y, _ -> (x + y) % 128 + 12 }, format))
        assertFalse(FrameSceneChange.isCut(a, a, format))
        assertEquals(0, a.position())
    }

    fun `tiny dimensions and row padding remain valid`() {
        assertTrue(FrameSceneChange.isCut(ByteBuffer.wrap(byteArrayOf(0, 0, 0)),
            ByteBuffer.wrap(byteArrayOf(-1, -1, -1)), FrameBufferLedger.Format(1, 1, 3)))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FrameSceneChangeCases) -> Unit) {
            val s = FrameSceneChangeCases()
            block(s)
        }
        runIsolated { s -> s.`hard cuts and full flashes hold the source instead of morphing`() }
        runIsolated { s -> s.`large pan preserving color distribution is not a cut`() }
        runIsolated { s -> s.`subtitle replacement and gradual fades do not disable the whole frame`() }
        runIsolated { s -> s.`tiny dimensions and row padding remain valid`() }
    }
}

private class FrameScratchArenaCases {
    fun oddSizedFramesUseDisjointAlignedRangesOfOneAllocation() {
        val size = 99
        val total = FrameScratchArena.storageBytes(size, 4)
        assertEquals(5 * 128, total)
        val storage = ByteBuffer.allocateDirect(total)
        val arena = FrameScratchArena(storage, size, 4)
        // 通过底层偏移区分四份输入，验证它们没有别名。
        repeat(4) { index -> storage.put(index * 128, (index + 1).toByte()) }
        repeat(4) { index ->
            val input = arena.input(index, size)
            assertTrue(input.isReadOnly && input.isDirect)
            assertEquals(index + 1, input.get(0).toInt())
            assertThrows(ReadOnlyBufferException::class.java) { input.put(0, 42) }
        }
        val output = arena.output(size)
        output.put(0, 42)
        assertEquals(42, storage.get(4 * 128).toInt())
        assertEquals(total, arena.allocatedBytes)
    }

    fun reusingInputAndOutputRestoresBoundsAndByteOrder() {
        val size = 200
        val arena = FrameScratchArena(ByteBuffer.allocateDirect(FrameScratchArena.storageBytes(size, 2)), size, 2)
        val input = arena.input(1, size)
        input.order(ByteOrder.LITTLE_ENDIAN).position(80)
        val output = arena.output(size)
        output.put(7, 42)
        output.position(85)
        assertSame(input, arena.input(1, 12))
        assertSame(output, arena.output(8))
        assertEquals(0, input.position())
        assertEquals(12, input.limit())
        assertEquals(ByteOrder.BIG_ENDIAN, input.order())
        assertEquals(0, output.position())
        assertEquals(8, output.limit())
        assertEquals(42, output.get(7).toInt())
    }

    fun invalidSessionOrAccessIsRejectedBeforeUse() {
        assertThrows(IllegalArgumentException::class.java) { FrameScratchArena.storageBytes(0, 2) }
        assertThrows(IllegalArgumentException::class.java) { FrameScratchArena.storageBytes(Int.MAX_VALUE, 4) }
        assertThrows(IllegalArgumentException::class.java) { FrameScratchArena.storageBytes(10, 5) }
        val total = FrameScratchArena.storageBytes(100, 2)
        assertThrows(IllegalArgumentException::class.java) { FrameScratchArena(ByteBuffer.allocate(total), 100, 2) }
        assertThrows(IllegalArgumentException::class.java) { FrameScratchArena(ByteBuffer.allocateDirect(total - 1), 100, 2) }
        val arena = FrameScratchArena(ByteBuffer.allocateDirect(total), 100, 2)
        assertThrows(IllegalArgumentException::class.java) { arena.input(2, 1) }
        assertThrows(IllegalArgumentException::class.java) { arena.output(101) }
        assertEquals(3 * 128, total)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FrameScratchArenaCases) -> Unit) {
            val s = FrameScratchArenaCases()
            block(s)
        }
        runIsolated { s -> s.oddSizedFramesUseDisjointAlignedRangesOfOneAllocation() }
        runIsolated { s -> s.reusingInputAndOutputRestoresBoundsAndByteOrder() }
        runIsolated { s -> s.invalidSessionOrAccessIsRejectedBeforeUse() }
    }
}

private class FrameSourceCadenceCases {
    private fun verdictFor(fps: Double, frames: Int = 20): Verdict {
        val cadence = FrameSourceCadence()
        for (i in 0 until frames) cadence.add((i * 1_000_000.0 / fps).toLong())
        return cadence.verdict()
    }

    fun `integer divisors of 60 including 29_97 are suitable`() {
        for (fps in listOf(30.0, 29.97, 20.0, 15.0, 60.0)) assertEquals("$fps", Verdict.SUITABLE, verdictFor(fps))
    }

    fun `playback requires at least one synthetic frame per source frame`() {
        fun verdict(fps: Double, target: Int) = FrameSourceCadence(target, interpolationRequired = true).also { c ->
            for (i in 0 until 20) c.add((i * 1_000_000.0 / fps).toLong())
        }.verdict()
        assertEquals(Verdict.UNSUITABLE, verdict(60.0, 60))
        assertEquals(Verdict.UNSUITABLE, verdict(59.94, 60))
        assertEquals(Verdict.UNSUITABLE, verdict(50.0, 50))
        assertEquals(Verdict.SUITABLE, verdict(30.0, 60))
        assertEquals(Verdict.SUITABLE, verdict(25.0, 50))
    }

    fun `24 and 25 fps cannot be phase aligned to 60 and are unsuitable`() {
        assertEquals(Verdict.UNSUITABLE, verdictFor(24.0))
        assertEquals(Verdict.UNSUITABLE, verdictFor(25.0))
        assertEquals(Verdict.UNSUITABLE, verdictFor(23.976))
    }

    fun `50 fps target accepts 25 fps and rejects 30 fps sources`() {
        fun verdict(fps: Double) = FrameSourceCadence(targetFps = 50).also { c ->
            for (i in 0 until 20) c.add((i * 1_000_000.0 / fps).toLong())
        }.verdict()
        assertEquals(Verdict.SUITABLE, verdict(25.0))
        assertEquals(Verdict.UNSUITABLE, verdict(29.97))
        assertEquals(Verdict.UNSUITABLE, verdict(24.0))
    }

    fun `too few frames is pending and dropped source frames do not skew the estimate`() {
        assertEquals(Verdict.PENDING, verdictFor(30.0, frames = 8))
        val cadence = FrameSourceCadence()
        var pts = 0L
        repeat(30) { i -> pts += if (i % 7 == 6) 66_667L else 33_333L; cadence.add(pts) }
        assertEquals(Verdict.SUITABLE, cadence.verdict())
        assertEquals(30.0, cadence.sourceFps()!!, 0.1)
        assertTrue(cadence.describe().contains("gaps=4"))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FrameSourceCadenceCases) -> Unit) {
            val s = FrameSourceCadenceCases()
            block(s)
        }
        runIsolated { s -> s.`integer divisors of 60 including 29_97 are suitable`() }
        runIsolated { s -> s.`playback requires at least one synthetic frame per source frame`() }
        runIsolated { s -> s.`24 and 25 fps cannot be phase aligned to 60 and are unsuitable`() }
        runIsolated { s -> s.`50 fps target accepts 25 fps and rejects 30 fps sources`() }
        runIsolated { s -> s.`too few frames is pending and dropped source frames do not skew the estimate`() }
    }
}

private class FrameStaticSceneCases {
    private val format = FrameBufferLedger.Format(64, 32, 64 * 3)
    private fun frame(fill: (x: Int, y: Int) -> Int) = ByteBuffer.allocate(format.bytes).also { buffer ->
        for (y in 0 until format.height) for (x in 0 until format.width) for (c in 0 until 3)
            buffer.put(y * format.stride + x * 3 + c, fill(x, y).toByte())
    }

    fun `identical and sensor noise level frames are static`() {
        val base = frame { x, y -> (x * 3 + y) % 200 }
        assertTrue(FrameStaticScene.isStatic(base, frame { x, y -> (x * 3 + y) % 200 }, format))
        assertTrue(FrameStaticScene.isStatic(base, frame { x, y -> (x * 3 + y) % 200 + if ((x + y) % 9 == 0) 1 else 0 }, format))
    }

    fun `global brightness change or a moving block is not static`() {
        val base = frame { x, y -> (x * 3 + y) % 200 }
        assertFalse(FrameStaticScene.isStatic(base, frame { x, y -> (x * 3 + y) % 200 + 2 }, format))
        assertFalse(FrameStaticScene.isStatic(base, frame { x, y -> if (x in 8..23 && y in 8..23) 255 else (x * 3 + y) % 200 }, format))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FrameStaticSceneCases) -> Unit) {
            val s = FrameStaticSceneCases()
            block(s)
        }
        runIsolated { s -> s.`identical and sensor noise level frames are static`() }
        runIsolated { s -> s.`global brightness change or a moving block is not static`() }
    }
}
