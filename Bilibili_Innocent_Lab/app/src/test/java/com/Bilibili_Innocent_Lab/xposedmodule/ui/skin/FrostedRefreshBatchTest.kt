package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundConfigCodec
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundConfigIssue
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundMode
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundSizingPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowChromeBlurPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidBackdropSizingPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.FrostedRefreshBatch
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.LensRefractionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.ModernMaterialPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrostedRefreshBatchTest {
    fun aBurstOfAnimationAndScrollNotificationsScansOnlyOnceBeforeDraw() {
        val batch = FrostedRefreshBatch()
        repeat(120) {
            repeat(12) { assertFalse(batch.request()) }
            assertTrue(batch.beforeDraw())
            assertFalse(batch.beforeDraw())
            batch.drawn()
        }
    }

    fun laterPreDrawTransformsMustRefreshInTheSameFrame() {
        val batch = FrostedRefreshBatch()
        assertFalse(batch.request())
        assertTrue(batch.beforeDraw())
        // A later listener changes translation after the first sweep: never retain stale sampling.
        assertTrue(batch.request())
        assertTrue(batch.request())
        batch.drawn()
        assertFalse(batch.beforeDraw())
    }

    fun canceledTraversalKeepsLateChangesSafeUntilADrawActuallyCompletes() {
        val batch = FrostedRefreshBatch()
        assertFalse(batch.beforeDraw())
        // No draw callback: a canceled traversal cannot silently defer subsequent mutations.
        assertTrue(batch.request())
        assertFalse(batch.beforeDraw())
        assertTrue(batch.request())
        batch.drawn()
        assertFalse(batch.request())
        assertTrue(batch.beforeDraw())
    }

    fun idleWindowsDoNotScanAndWindowsDoNotConsumeEachOthersRequests() {
        val main = FrostedRefreshBatch()
        val dialog = FrostedRefreshBatch()
        assertFalse(main.request())
        assertFalse(dialog.beforeDraw())
        assertTrue(main.beforeDraw())
        main.drawn()
        dialog.drawn()
        repeat(10) {
            assertFalse(main.beforeDraw())
            main.drawn()
        }
    }

    fun stopAndObserverReplacementDiscardPendingAndLatePhaseState() {
        val batch = FrostedRefreshBatch()
        batch.request()
        batch.clear()
        assertFalse(batch.beforeDraw())
        batch.clear()
        assertFalse(batch.request())
        assertTrue(batch.beforeDraw())
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FrostedRefreshBatchTest) -> Unit) {
            val s = FrostedRefreshBatchTest()
            block(s)
        }
        runIsolated { s -> s.aBurstOfAnimationAndScrollNotificationsScansOnlyOnceBeforeDraw() }
        runIsolated { s -> s.laterPreDrawTransformsMustRefreshInTheSameFrame() }
        runIsolated { s -> s.canceledTraversalKeepsLateChangesSafeUntilADrawActuallyCompletes() }
        runIsolated { s -> s.idleWindowsDoNotScanAndWindowsDoNotConsumeEachOthersRequests() }
        runIsolated { s -> s.stopAndObserverReplacementDiscardPendingAndLatePhaseState() }
    
        GlowChromeBlurPolicyCases().verifyAllCases()
        LensRefractionPolicyCases().verifyAllCases()
        LiquidBackdropSizingPolicyCases().verifyAllCases()
        LiquidBackgroundConfigCodecCases().verifyAllCases()
        LiquidBackgroundSizingPolicyCases().verifyAllCases()
    }
}

private class GlowChromeBlurPolicyCases {
    fun twoStagesKeepTheRequestedGaussianVariance() {
        fun variance(radius: Float): Float = (radius * 0.57735f + 0.5f).let { it * it }
        for (radius in listOf(8f, 9f, 17f, 34f, 51f, 68f)) {
            val pre = GlowChromeBlurPolicy.prefilterRadius(radius)
            val main = GlowChromeBlurPolicy.mainRadius(radius)
            assertTrue(main > 0f && main < radius)
            assertEquals(variance(radius), variance(pre) + variance(main), 0.001f)
        }
    }
    fun smallKernelsDoNotPayForAnExtraStage() {
        for (radius in listOf(1f, 3f, 6f, 7.99f)) {
            assertEquals(0f, GlowChromeBlurPolicy.prefilterRadius(radius), 0f)
            assertEquals(radius, GlowChromeBlurPolicy.mainRadius(radius), 0f)
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (GlowChromeBlurPolicyCases) -> Unit) {
            val s = GlowChromeBlurPolicyCases()
            block(s)
        }
        runIsolated { s -> s.twoStagesKeepTheRequestedGaussianVariance() }
        runIsolated { s -> s.smallKernelsDoNotPayForAnExtraStage() }
    }
}

private class LensRefractionPolicyCases {
    fun sampleScaleKeepsTheBitmapBudgetBounded() {
        for ((w, h) in listOf(1 to 1, 1080 to 240, 1440 to 320, 3840 to 2160, 20000 to 20000)) {
            val scale = LensRefractionPolicy.sampleScale(w, h)
            assertTrue(scale >= LensRefractionPolicy.MIN_SCALE)
            val pixels = (w / scale).toLong().coerceAtLeast(1) * (h / scale).coerceAtLeast(1)
            assertTrue("$w x $h → scale $scale", pixels <= LensRefractionPolicy.MAX_SAMPLE_PIXELS)
        }
        assertEquals(LensRefractionPolicy.MIN_SCALE, LensRefractionPolicy.sampleScale(0, 10))
    }

    fun lensIsOddMonotonicAndContinuous() {
        for ((gain, push) in listOf(
            LensRefractionPolicy.CENTER_GAIN_X to LensRefractionPolicy.RIM_PUSH_X,
            LensRefractionPolicy.CENTER_GAIN_Y to LensRefractionPolicy.RIM_PUSH_Y
        )) {
            assertEquals(0f, LensRefractionPolicy.lens(0f, gain, push), 1e-6f)
            var previous = LensRefractionPolicy.lens(-1f, gain, push)
            var u = -1f
            while (u < 1f) {
                u += 1f / 512f
                val value = LensRefractionPolicy.lens(u, gain, push)
                assertTrue(value.isFinite())
                assertTrue("单调 @ $u", value >= previous - 1e-6f)
                assertTrue("连续 @ $u", value - previous < 0.02f)
                assertEquals("奇函数 @ $u", -value, LensRefractionPolicy.lens(-u, gain, push), 1e-5f)
                previous = value
            }
            // 中心放大：内区比例 < 1；外沿推出：边缘越出 1。
            assertTrue(LensRefractionPolicy.lens(0.3f, gain, push) < 0.3f)
            assertTrue(LensRefractionPolicy.lens(1f, gain, push) > 1f)
            assertEquals(LensRefractionPolicy.lens(1f, gain, push), LensRefractionPolicy.lens(7f, gain, push), 0f)
        }
    }

    fun remapPreservesConstantImagesAndStaysInsideTheSource() {
        val sw = 40
        val sh = 16
        val margin = 4
        val source = IntArray(sw * sh) { 0x80402010.toInt() }
        val out = IntArray(90 * 30)
        LensRefractionPolicy.remap(source, sw, sh, margin, out, 90, 30)
        assertTrue(out.all { it == 0x80402010.toInt() })

        val gradient = IntArray(sw * sh) { i -> 0xFF000000.toInt() or ((i % sw) * 6) }
        LensRefractionPolicy.remap(gradient, sw, sh, margin, out, 90, 30)
        for (i in 0 until 90) {
            val a = out[i] ushr 24
            val b = out[i] and 0xFF
            assertEquals(0xFF, a)
            assertTrue(b in 0..(sw - 1) * 6)
        }
        assertTrue(runCatching { LensRefractionPolicy.remap(source, sw, sh, margin, out, 0, 30) }.isFailure)
    }

    fun premultiplyRoundTripsOpaqueAndDropsFullyTransparentColor() {
        val pixels = intArrayOf(0xFF10A0F0.toInt(), 0x00FFFFFF, 0x80FF0000.toInt())
        LensRefractionPolicy.premultiply(pixels)
        assertEquals(0xFF10A0F0.toInt(), pixels[0])
        assertEquals(0, pixels[1])
        LensRefractionPolicy.unpremultiply(pixels)
        assertEquals(0xFF10A0F0.toInt(), pixels[0])
        assertEquals(0, pixels[1])
        assertEquals(0x80, pixels[2] ushr 24)
        assertTrue((pixels[2] shr 16 and 0xFF) >= 0xFE)
    }

    fun illuminateLiftsPremultipliedChannelsAndKeepsAlpha() {
        val pixels = intArrayOf(0xFF102030.toInt(), 0x80FF0000.toInt(), 0x00000000, 0xFFF0F0F0.toInt())
        LensRefractionPolicy.illuminate(pixels)
        // 提亮只动预乘 RGB：alpha 通道原样保留，全透明像素不产出颜色。
        assertEquals(0xFF, pixels[0] ushr 24)
        assertTrue((pixels[0] ushr 16 and 255) > 0x10)
        assertTrue((pixels[0] and 255) > 0x30)
        assertEquals(0x80, pixels[1] ushr 24)
        assertEquals(0, pixels[2])
        // 接近满亮的像素被钳到 255，不溢出回绕。
        assertEquals(0xFFFFFFFF.toInt(), pixels[3])
    }

    fun onlyFloatingTopBarAndModalUseTheLiveBackdrop() {
        for (dark in listOf(false, true)) {
            for (role in SurfaceRole.values()) {
                val style = ModernMaterialPolicy.surface(role, dark)
                val expected = role == SurfaceRole.FLOATING || role == SurfaceRole.TOP_BAR ||
                    role == SurfaceRole.MODAL
                assertEquals(role.name, expected, style.live)
            }
        }
    }

    fun remapFlatIsAPlainResizeOfTheInnerRegion() {
        val sw = 48
        val sh = 20
        val margin = 5
        // 竖直渐变源：无折射时输出应逐行等值，且始终落在内区（外沿 ring 完全不参与）。
        val source = IntArray(sw * sh) { i -> 0xFF000000.toInt() or ((i / sw) * 10) }
        val out = IntArray(120 * 30)
        LensRefractionPolicy.remapFlat(source, sw, sh, margin, out, 120, 30)
        for (y in 0 until 30) {
            val row = out.copyOfRange(y * 120, y * 120 + 120)
            assertTrue("行内等值 @ $y", row.all { it == row[0] })
            assertTrue("内区 @ $y: ${row[0] and 0xFF}", (row[0] and 0xFF) in 0..(sh - 1) * 10)
        }
        val values = (0 until 30).map { out[it * 120] and 0xFF }
        assertEquals("单调", values.sorted(), values)
        // 双线性取样落在内区 [margin-0.5, sh-margin-0.5)：永远不会取到外沿 ring 深处。
        assertTrue("只在內区取值: $values", values.all { it in (margin - 1) * 10..(sh - margin) * 10 })
        assertTrue("覆盖整个内区: $values", values.last() - values.first() > (sh - 2 * margin - 2) * 10)
    }

    fun fadeVerticallyHoldsThenDecaysToTransparentSmoothly() {
        val width = 4
        val height = 100
        val pixels = IntArray(width * height) { 0xFF3366CC.toInt() }
        LensRefractionPolicy.fadeVertically(pixels, width, height, hold = 0.5f, end = 1f)

        val alpha = { y: Int -> pixels[y * width] ushr 24 }
        // 满强度区原样保留；末行完全透明（融合带下沿必须与未模糊的内容严丝合缝）。
        assertEquals(0xFF, alpha(0))
        assertEquals(0xFF, alpha(49))
        assertEquals(0, alpha(height - 1))
        // 单调，且没有任何一行跳变：100 行上的 smoothstep 单行最大斜率约 3/100 × 255 ≈ 8。
        var previous = alpha(50)
        for (y in 51 until height) {
            val current = alpha(y)
            assertTrue("单调 @ $y", current <= previous)
            assertTrue("无跳变 @ $y: $previous → $current", previous - current <= 9)
            previous = current
        }
        // RGB 原样保留；全透明行也不再泄露颜色。
        assertEquals(0x003366CC, pixels[(height - 1) * width])
        assertEquals(0xFF3366CC.toInt(), pixels[10 * width])
    }

    fun fadeVerticallyKeepsZeroAlphaAndRejectsMismatchedBuffers() {
        val pixels = intArrayOf(0x00FF0000, 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt())
        LensRefractionPolicy.fadeVertically(pixels, 1, 4, hold = 0f, end = 0f)
        assertEquals(0x00FF0000, pixels[0]) // end ≤ hold：不渐隐
        LensRefractionPolicy.fadeVertically(pixels, 1, 4, hold = 0.75f, end = 1f)
        assertEquals(0, pixels[0] ushr 24) // 原来就全透明的像素保持全透明
        assertEquals(0xFF, pixels[1] ushr 24) // 满强度区原样
        assertEquals(0xFF, pixels[2] ushr 24)
        // 末行落在曲线中点（0.875 → smoothstep(0.5) = 0.5），alpha 恰好减半。
        assertEquals(128, pixels[3] ushr 24)
        assertTrue(runCatching { LensRefractionPolicy.fadeVertically(pixels, 1, 5, 0f, 1f) }.isFailure)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (LensRefractionPolicyCases) -> Unit) {
            val s = LensRefractionPolicyCases()
            block(s)
        }
        runIsolated { s -> s.sampleScaleKeepsTheBitmapBudgetBounded() }
        runIsolated { s -> s.lensIsOddMonotonicAndContinuous() }
        runIsolated { s -> s.remapPreservesConstantImagesAndStaysInsideTheSource() }
        runIsolated { s -> s.premultiplyRoundTripsOpaqueAndDropsFullyTransparentColor() }
        runIsolated { s -> s.illuminateLiftsPremultipliedChannelsAndKeepsAlpha() }
        runIsolated { s -> s.onlyFloatingTopBarAndModalUseTheLiveBackdrop() }
        runIsolated { s -> s.remapFlatIsAPlainResizeOfTheInnerRegion() }
        runIsolated { s -> s.fadeVerticallyHoldsThenDecaysToTransparentSmoothly() }
        runIsolated { s -> s.fadeVerticallyKeepsZeroAlphaAndRejectsMismatchedBuffers() }
    }
}

private class LiquidBackdropSizingPolicyCases {

    fun `custom presentation retains native phone pixels`() {
        for ((width, height) in listOf(1080 to 2340, 1440 to 3200, 3200 to 1440)) {
            val size = LiquidBackdropSizingPolicy.resolvePresentation(width, height)
            assertEquals(width, size.width)
            assertEquals(height, size.height)
        }
    }

    fun `oversized presentation preserves aspect ratio within its own budget`() {
        val size = LiquidBackdropSizingPolicy.resolvePresentation(16000, 4000)
        assertEquals(4.0, size.width.toDouble() / size.height, 0.02)
        assertTrue(size.width.toLong() * size.height <= LiquidBackdropSizingPolicy.MAX_PRESENTATION_PIXELS)
    }

    fun `extreme presentation dimensions remain bounded`() {
        for ((width, height) in listOf(Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE,
            Int.MAX_VALUE to Int.MAX_VALUE)) {
            val size = LiquidBackdropSizingPolicy.resolvePresentation(width, height)
            assertTrue(size.width > 0 && size.height > 0)
            assertTrue(size.width.toLong() * size.height <= LiquidBackdropSizingPolicy.MAX_PRESENTATION_PIXELS)
        }
    }

    fun `unmeasured presentation is rejected`() {
        LiquidBackdropSizingPolicy.resolvePresentation(1080, 0)
    }

    fun `normal display uses quarter resolution`() {
        val size = LiquidBackdropSizingPolicy.resolve(1080, 2400)

        assertEquals(270, size.width)
        assertEquals(600, size.height)
        assertEquals(648_000L, size.byteCount)
    }

    fun `fractional quarter dimensions round up`() {
        val size = LiquidBackdropSizingPolicy.resolve(101, 203)

        assertEquals(26, size.width)
        assertEquals(51, size.height)
    }

    fun `very large display stays within two mebibytes`() {
        val size = LiquidBackdropSizingPolicy.resolve(16_000, 16_000)

        assertTrue(size.width > 0)
        assertTrue(size.height > 0)
        assertTrue(size.width.toLong() * size.height.toLong() <= 524_288L)
        assertTrue(size.byteCount <= LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES)
        assertTrue(size.byteCount >= LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES * 9 / 10)
    }

    fun `large wide display preserves aspect ratio while using the budget`() {
        val size = LiquidBackdropSizingPolicy.resolve(16_000, 4_000)

        assertEquals(4.0, size.width.toDouble() / size.height.toDouble(), 0.02)
        assertTrue(size.byteCount <= LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES)
        assertTrue(size.byteCount >= LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES * 9 / 10)
    }

    fun `zero dimension is rejected`() {
        LiquidBackdropSizingPolicy.resolve(0, 100)
    }

    fun `phone preview uses actual view pixels rather than a fixed canvas`() {
        val size = LiquidBackdropSizingPolicy.resolvePreview(1080, 420)
        assertEquals(1080, size.width)
        assertEquals(420, size.height)
        assertTrue(size.width > 640)
        assertTrue(size.byteCount <= LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES)
    }

    fun `small preview is not upscaled`() {
        val size = LiquidBackdropSizingPolicy.resolvePreview(320, 180)
        assertEquals(320, size.width)
        assertEquals(180, size.height)
    }

    fun `preview at the budget stays at native resolution`() {
        val size = LiquidBackdropSizingPolicy.resolvePreview(1024, 512)
        assertEquals(1024, size.width)
        assertEquals(512, size.height)
        assertEquals(LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES, size.byteCount)
    }

    fun `tablet preview scales within existing budget without distortion`() {
        val size = LiquidBackdropSizingPolicy.resolvePreview(2560, 900)
        assertEquals(2560.0 / 900, size.width.toDouble() / size.height, 0.02)
        assertTrue(size.byteCount <= LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES)
        assertTrue(size.byteCount >= LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES * 9 / 10)
    }

    fun `portrait preview preserves aspect ratio`() {
        val size = LiquidBackdropSizingPolicy.resolvePreview(900, 2560)
        assertEquals(900.0 / 2560, size.width.toDouble() / size.height, 0.01)
        assertTrue(size.byteCount <= LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES)
    }

    fun `extreme aspect ratios and dimensions have bounded cost`() {
        for ((width, height) in listOf(Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE,
            Int.MAX_VALUE to Int.MAX_VALUE)) {
            val size = LiquidBackdropSizingPolicy.resolvePreview(width, height)
            assertTrue(size.width > 0 && size.height > 0)
            assertTrue(size.byteCount <= LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES)
        }
    }

    fun `unmeasured preview is rejected`() {
        LiquidBackdropSizingPolicy.resolvePreview(0, 420)
    }

    fun `negative preview height is rejected`() {
        LiquidBackdropSizingPolicy.resolvePreview(1080, -1)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (LiquidBackdropSizingPolicyCases) -> Unit) {
            val s = LiquidBackdropSizingPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`custom presentation retains native phone pixels`() }
        runIsolated { s -> s.`oversized presentation preserves aspect ratio within its own budget`() }
        runIsolated { s -> s.`extreme presentation dimensions remain bounded`() }
        runIsolated { s -> try { s.`unmeasured presentation is rejected`(); org.junit.Assert.fail("Expected IllegalArgumentException") } catch (_: IllegalArgumentException) {} }
        runIsolated { s -> s.`normal display uses quarter resolution`() }
        runIsolated { s -> s.`fractional quarter dimensions round up`() }
        runIsolated { s -> s.`very large display stays within two mebibytes`() }
        runIsolated { s -> s.`large wide display preserves aspect ratio while using the budget`() }
        runIsolated { s -> try { s.`zero dimension is rejected`(); org.junit.Assert.fail("Expected IllegalArgumentException") } catch (_: IllegalArgumentException) {} }
        runIsolated { s -> s.`phone preview uses actual view pixels rather than a fixed canvas`() }
        runIsolated { s -> s.`small preview is not upscaled`() }
        runIsolated { s -> s.`preview at the budget stays at native resolution`() }
        runIsolated { s -> s.`tablet preview scales within existing budget without distortion`() }
        runIsolated { s -> s.`portrait preview preserves aspect ratio`() }
        runIsolated { s -> s.`extreme aspect ratios and dimensions have bounded cost`() }
        runIsolated { s -> try { s.`unmeasured preview is rejected`(); org.junit.Assert.fail("Expected IllegalArgumentException") } catch (_: IllegalArgumentException) {} }
        runIsolated { s -> try { s.`negative preview height is rejected`(); org.junit.Assert.fail("Expected IllegalArgumentException") } catch (_: IllegalArgumentException) {} }
    }
}

private class LiquidBackgroundConfigCodecCases {

    fun `empty preferences use clean automatic background`() {
        val decoded = LiquidBackgroundConfigCodec.decode(emptyMap<String, Any>())

        assertEquals(LiquidBackgroundConfig.AUTOMATIC, decoded.config)
        assertEquals(LiquidBackgroundConfigIssue.NONE, decoded.issue)
        assertFalse(decoded.needsRepair)
    }

    fun `custom config round trips without external path`() {
        val config = customConfig()

        val encoded = LiquidBackgroundConfigCodec.encode(config)
        val decoded = LiquidBackgroundConfigCodec.decode(encoded)

        assertEquals(config, decoded.config)
        assertFalse(decoded.needsRepair)
        assertFalse(encoded.keys.any { it.contains("uri", ignoreCase = true) })
        assertFalse(encoded.keys.any { it.contains("path", ignoreCase = true) })
    }

    fun `custom mode without asset repairs to automatic`() {
        val decoded = LiquidBackgroundConfigCodec.decode(
            mapOf(
                LiquidBackgroundConfigCodec.KEY_SCHEMA_VERSION to 1,
                LiquidBackgroundConfigCodec.KEY_MODE to LiquidBackgroundMode.CUSTOM.name
            )
        )

        assertEquals(LiquidBackgroundConfig.AUTOMATIC, decoded.config)
        assertEquals(LiquidBackgroundConfigIssue.TYPE_MISMATCH, decoded.issue)
        assertTrue(decoded.needsRepair)
    }

    fun `future schema does not enable broken custom background`() {
        val values = LiquidBackgroundConfigCodec.encode(customConfig()).toMutableMap()
        values[LiquidBackgroundConfigCodec.KEY_SCHEMA_VERSION] = 99

        val decoded = LiquidBackgroundConfigCodec.decode(values)

        assertEquals(LiquidBackgroundConfig.AUTOMATIC, decoded.config)
        assertEquals(LiquidBackgroundConfigIssue.MISSING_OR_INVALID_SCHEMA, decoded.issue)
        assertTrue(decoded.needsRepair)
    }

    fun `automatic mode removes stale custom fields`() {
        val values = LiquidBackgroundConfigCodec.encode(customConfig()).toMutableMap()
        values[LiquidBackgroundConfigCodec.KEY_MODE] = LiquidBackgroundMode.AUTOMATIC.name

        val decoded = LiquidBackgroundConfigCodec.decode(values)

        assertEquals(LiquidBackgroundConfig.AUTOMATIC, decoded.config)
        assertTrue(decoded.needsRepair)
    }

    private fun customConfig() = LiquidBackgroundConfig(
        mode = LiquidBackgroundMode.CUSTOM,
        assetId = "asset-123",
        assetSha256 = "ab".repeat(32),
        normalizedWidth = 1600,
        normalizedHeight = 900,
        displayName = "background.webp"
    )

    fun verifyAllCases() {
        fun runIsolated(block: (LiquidBackgroundConfigCodecCases) -> Unit) {
            val s = LiquidBackgroundConfigCodecCases()
            block(s)
        }
        runIsolated { s -> s.`empty preferences use clean automatic background`() }
        runIsolated { s -> s.`custom config round trips without external path`() }
        runIsolated { s -> s.`custom mode without asset repairs to automatic`() }
        runIsolated { s -> s.`future schema does not enable broken custom background`() }
        runIsolated { s -> s.`automatic mode removes stale custom fields`() }
    }
}

private class LiquidBackgroundSizingPolicyCases {

    fun `normalization preserves aspect ratio within hard limits`() {
        val size = LiquidBackgroundSizingPolicy.resolveNormalizedSize(8000, 4000)

        assertEquals(4096, size.width)
        assertEquals(2048, size.height)
        assertTrue(size.width.toLong() * size.height <=
            LiquidBackgroundSizingPolicy.MAX_NORMALIZED_PIXELS)
    }

    fun `camera photo retains enough pixels after portrait center crop`() {
        val size = LiquidBackgroundSizingPolicy.resolveNormalizedSize(4000, 3000)
        assertEquals(4000, size.width)
        assertEquals(3000, size.height)
        val crop = LiquidBackgroundSizingPolicy.centerCrop(size.width, size.height, 1080, 2400)
        assertTrue(crop.scale <= 1f)
    }

    fun `square import is bounded by both edge and pixel limits`() {
        val size = LiquidBackgroundSizingPolicy.resolveNormalizedSize(8000, 8000)
        assertEquals(size.width, size.height)
        assertTrue(size.width <= LiquidBackgroundSizingPolicy.MAX_EDGE)
        assertTrue(size.width.toLong() * size.height <= LiquidBackgroundSizingPolicy.MAX_NORMALIZED_PIXELS)
    }

    fun `normalization rounding cannot exceed the config pixel limit`() {
        val size = LiquidBackgroundSizingPolicy.resolveNormalizedSize(4001, 3500)
        assertTrue(size.width.toLong() * size.height <= LiquidBackgroundSizingPolicy.MAX_NORMALIZED_PIXELS)
        assertEquals(4001.0 / 3500, size.width.toDouble() / size.height, 0.001)
    }

    fun `portrait source center crops horizontally into portrait target`() {
        val transform = LiquidBackgroundSizingPolicy.centerCrop(
            sourceWidth = 1200,
            sourceHeight = 2400,
            targetWidth = 360,
            targetHeight = 800
        )

        assertEquals(1f / 3f, transform.scale, 0.0001f)
        assertTrue(transform.translateX < 0f)
        assertEquals(0f, transform.translateY, 0.0001f)
    }

    fun `landscape source center crops horizontally for portrait target`() {
        val transform = LiquidBackgroundSizingPolicy.centerCrop(
            sourceWidth = 2400,
            sourceHeight = 1200,
            targetWidth = 360,
            targetHeight = 800
        )

        assertTrue(transform.translateX < 0f)
        assertEquals(0f, transform.translateY, 0.0001f)
    }

    fun `decode sample remains power of two without undershooting target`() {
        val sample = LiquidBackgroundSizingPolicy.decodeSampleSize(4096, 2048, 500, 400)

        assertEquals(4, sample)
        assertTrue(4096 / sample >= 500)
        assertTrue(2048 / sample >= 400)
    }

    fun `declared image bomb is rejected before decode`() {
        assertTrue(!LiquidBackgroundSizingPolicy.isSupportedDeclaredSize(16_384, 16_384))
        assertTrue(!LiquidBackgroundSizingPolicy.isSupportedDeclaredSize(40_000, 100))
    }

    fun `panorama and high resolution photos stay within the widened declared limits`() {
        // 长边全景与高像素方图现在可导入，规范化后仍落在资产边界内。
        assertTrue(LiquidBackgroundSizingPolicy.isSupportedDeclaredSize(20_000, 100))
        assertTrue(LiquidBackgroundSizingPolicy.isSupportedDeclaredSize(8000, 8000))
        val panorama = LiquidBackgroundSizingPolicy.resolveNormalizedSize(20_000, 100)
        assertEquals(LiquidBackgroundSizingPolicy.MAX_EDGE, panorama.width)
        assertTrue(panorama.height >= 1)
        val square = LiquidBackgroundSizingPolicy.resolveNormalizedSize(8000, 8000)
        assertTrue(square.width.toLong() * square.height <=
            LiquidBackgroundSizingPolicy.MAX_NORMALIZED_PIXELS)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (LiquidBackgroundSizingPolicyCases) -> Unit) {
            val s = LiquidBackgroundSizingPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`normalization preserves aspect ratio within hard limits`() }
        runIsolated { s -> s.`camera photo retains enough pixels after portrait center crop`() }
        runIsolated { s -> s.`square import is bounded by both edge and pixel limits`() }
        runIsolated { s -> s.`normalization rounding cannot exceed the config pixel limit`() }
        runIsolated { s -> s.`portrait source center crops horizontally into portrait target`() }
        runIsolated { s -> s.`landscape source center crops horizontally for portrait target`() }
        runIsolated { s -> s.`decode sample remains power of two without undershooting target`() }
        runIsolated { s -> s.`declared image bomb is rejected before decode`() }
        runIsolated { s -> s.`panorama and high resolution photos stay within the widened declared limits`() }
    }
}
