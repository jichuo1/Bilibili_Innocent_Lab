package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry.ScrollSurfaceScope
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidEffectProfile
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidRefreshBatch
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidStretchSamplingPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidSuppressionMaskGeometry
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidSurfaceAlphaPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidSurfaceRefreshState
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidTokenResolver
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidVisualTuningPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidWindowPositionState
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.ModuleMemoryPressureHub
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.ModuleMemoryPressureListener
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidStretchSamplingPolicyTest {
    fun distanceIsFiniteBoundedAndResetsExactly() {
        assertEquals(0f, LiquidStretchSamplingPolicy.intensity(0f), 0f)
        assertEquals(0f, LiquidStretchSamplingPolicy.intensity(Float.NaN), 0f)
        var previous = 0f
        for (step in 0..100) {
            val current = LiquidStretchSamplingPolicy.intensity(step / 100f)
            assertTrue(current >= previous && current <= .032f)
            previous = current
        }
        assertEquals(previous, LiquidStretchSamplingPolicy.intensity(100f), 0f)
    }

    fun inverseReconstructsTheUnwarpedScreenCoordinateForEitherEdge() {
        for (distance in listOf(.01f, .1f, .5f, 1f)) {
            val intensity = LiquidStretchSamplingPolicy.intensity(distance)
            for (step in 0..100) {
                val screen = step / 100f
                // HWUI output -> input texture, derived independently from StretchEffect.cpp.
                val input = 1f / (1f + intensity) - (1f - screen) /
                    (1f + intensity * (1f - .7f * screen))
                assertEquals(screen, LiquidStretchSamplingPolicy.outputPosition(input, intensity), .00001f)
                assertEquals(1f - screen, LiquidStretchSamplingPolicy.outputPosition(1f - input, -intensity), .00001f)
            }
        }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (LiquidStretchSamplingPolicyTest) -> Unit) {
            val s = LiquidStretchSamplingPolicyTest()
            block(s)
        }
        runIsolated { s -> s.distanceIsFiniteBoundedAndResetsExactly() }
        runIsolated { s -> s.inverseReconstructsTheUnwarpedScreenCoordinateForEitherEdge() }
    
        LiquidSuppressionMaskGeometryCases().verifyAllCases()
        LiquidTokenResolverCases().verifyAllCases()
        LiquidWindowPositionStateCases().verifyAllCases()
        ModuleMemoryPressureHubCases().`release walks current listeners and unregister stops further calls`()
        ScrollSurfaceScopeCases().verifyAllCases()
    }
}

private class LiquidSuppressionMaskGeometryCases {
    private data class Shape(
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val rx: Float, val ry: Float = rx
    ) {
        fun contains(x: Float, y: Float): Boolean {
            if (x < left || x > right || y < top || y > bottom) return false
            val rX = rx.coerceIn(0f, (right - left) / 2f)
            val rY = ry.coerceIn(0f, (bottom - top) / 2f)
            if (rX == 0f || rY == 0f) return true
            val dx = (x - x.coerceIn(left + rX, right - rX)) / rX
            val dy = (y - y.coerceIn(top + rY, bottom - rY)) / rY
            return dx * dx + dy * dy <= 1.00001f
        }
    }

    private fun LiquidSuppressionMaskGeometry.shape() = Shape(left, top, right, bottom, radiusX, radiusY)

    fun reproducesTheOldFullScreenCornerHoleAndCoversAllFourCornersAfterFix() {
        for (density in listOf(1f, 2.75f, 3.5f, 4f)) {
            for (paddingDp in listOf(12f, 22f)) for (scale in listOf(0.25f, 0.47f, 1f)) {
                val padding = paddingDp * density
                val mask = LiquidSuppressionMaskGeometry()
                assertTrue(mask.set(0f, 0f, 1440f, 3000f, 0f, padding, 1440, 3000, scale, scale))
                val old = Shape(0f, 0f, 1440f * scale, 3000f * scale, padding * scale)
                for (x in listOf(0f, 1440f * scale)) for (y in listOf(0f, 3000f * scale)) {
                    assertFalse("Old clipping leaves the optical output unmasked", old.contains(x, y))
                    assertTrue("Full-screen optical output must be entirely replaced", mask.shape().contains(x, y))
                }
            }
        }
    }

    fun centeredCardsKeepExactlyTheExistingMaskGeometry() {
        val mask = LiquidSuppressionMaskGeometry()
        assertTrue(mask.set(100f, 200f, 900f, 600f, 52f, 22f, 1440, 3000, 0.5f, 0.5f))
        assertEquals(Shape(39f, 89f, 461f, 311f, 37f), mask.shape())
    }

    fun clippedRoundedCardsRetainTheirOriginalCornerCenters() {
        val mask = LiquidSuppressionMaskGeometry()
        assertTrue(mask.set(-40f, -20f, 400f, 300f, 60f, 22f, 360, 640, 0.5f, 0.25f))
        assertEquals((-40f + 60f) * 0.5f, mask.left + mask.radiusX, 0f)
        assertEquals((-20f + 60f) * 0.25f, mask.top + mask.radiusY, 0f)
        assertEquals((400f - 60f) * 0.5f, mask.right - mask.radiusX, 0f)
        assertTrue(mask.left < 0f)
        assertTrue(mask.right > 360f * 0.5f)
    }

    fun everyVisibleOpticalPixelRemainsCoveredThroughEntryAndPredictiveBack() {
        val mask = LiquidSuppressionMaskGeometry()
        for (step in 0..100) {
            val p = step / 100f
            val surface = Shape(20f * (1f - p), 160f * (1f - p), 340f + 20f * p,
                240f + 400f * p, 28f * (1f - p))
            assertTrue(mask.set(surface.left, surface.top, surface.right, surface.bottom,
                surface.rx, 22f, 360, 640, 0.47f, 0.51f))
            for (x in 0..360 step 5) for (y in 0..640 step 5) {
                if (surface.contains(x.toFloat(), y.toFloat())) {
                    assertTrue("Unmasked output at p=$p, ($x,$y)", mask.shape().contains(x * 0.47f, y * 0.51f))
                }
            }
        }
    }

    fun partialOffscreenSurfacesStayCoveredOnEveryEdge() {
        for (surface in listOf(
            Shape(-50f, 40f, 150f, 240f, 50f), Shape(210f, 40f, 410f, 240f, 50f),
            Shape(40f, -50f, 240f, 150f, 50f), Shape(40f, 540f, 240f, 740f, 50f)
        )) {
            val mask = LiquidSuppressionMaskGeometry()
            assertTrue(mask.set(surface.left, surface.top, surface.right, surface.bottom,
                surface.rx, 22f, 360, 640, 1f, 1f))
            for (x in 0..360 step 3) for (y in 0..640 step 3) {
                if (surface.contains(x.toFloat(), y.toFloat())) assertTrue(mask.shape().contains(x.toFloat(), y.toFloat()))
            }
        }
    }

    fun pixelsOutsideTheEffectFootprintRemainLive() {
        val mask = LiquidSuppressionMaskGeometry()
        assertTrue(mask.set(100f, 100f, 200f, 200f, 20f, 10f, 360, 640, 1f, 1f))
        for ((x, y) in listOf(89f to 150f, 211f to 150f, 150f to 89f, 150f to 211f)) {
            assertFalse(mask.shape().contains(x, y))
        }
    }

    fun onlyOffscreenExpandedFootprintsAreSkipped() {
        val mask = LiquidSuppressionMaskGeometry()
        assertFalse(mask.set(-100f, 100f, -30f, 200f, 0f, 22f, 360, 640, 1f, 1f))
        assertTrue(mask.set(-100f, 100f, -10f, 200f, 0f, 22f, 360, 640, 1f, 1f))
        assertTrue(mask.shape().contains(0f, 150f))
    }

    fun zeroPaddingAndLargeCornerRadiusMatchTheRenderedRoundRect() {
        val mask = LiquidSuppressionMaskGeometry()
        assertTrue(mask.set(10f, 20f, 110f, 60f, 100f, 0f, 360, 640, 1f, 1f))
        assertEquals(Shape(10f, 20f, 110f, 60f, 20f), mask.shape())
    }

    fun emptySurfacesAndZeroSizeCapturesDoNotCreateMasks() {
        val mask = LiquidSuppressionMaskGeometry()
        assertFalse(mask.set(0f, 0f, 0f, 100f, 0f, 22f, 360, 640, 1f, 1f))
        assertFalse(mask.set(0f, 0f, 100f, 100f, 0f, 22f, 0, 640, 1f, 1f))
        assertFalse(mask.set(0f, 0f, 100f, 100f, 0f, 22f, 360, 640, 0f, 1f))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (LiquidSuppressionMaskGeometryCases) -> Unit) {
            val s = LiquidSuppressionMaskGeometryCases()
            block(s)
        }
        runIsolated { s -> s.reproducesTheOldFullScreenCornerHoleAndCoversAllFourCornersAfterFix() }
        runIsolated { s -> s.centeredCardsKeepExactlyTheExistingMaskGeometry() }
        runIsolated { s -> s.clippedRoundedCardsRetainTheirOriginalCornerCenters() }
        runIsolated { s -> s.everyVisibleOpticalPixelRemainsCoveredThroughEntryAndPredictiveBack() }
        runIsolated { s -> s.partialOffscreenSurfacesStayCoveredOnEveryEdge() }
        runIsolated { s -> s.pixelsOutsideTheEffectFootprintRemainLive() }
        runIsolated { s -> s.onlyOffscreenExpandedFootprintsAreSkipped() }
        runIsolated { s -> s.zeroPaddingAndLargeCornerRadiusMatchTheRenderedRoundRect() }
        runIsolated { s -> s.emptySurfacesAndZeroSizeCapturesDoNotCreateMasks() }
    }
}

private class LiquidTokenResolverCases {

    fun `resolver preserves visual tuning mapping`() {
        listOf(false, true).forEach { dark ->
            val tuning = LiquidVisualTuningPolicy.resolve(dark)
            val parameters = LiquidTokenResolver.resolve(tuning)

            assertEquals(tuning.cardGlassAlpha, parameters.surfaceAlpha, 0f)
            assertEquals(tuning.modalGlassAlpha, parameters.modalSurfaceAlpha, 0f)
            assertEquals(tuning.motionGlassAlpha, parameters.motionSurfaceAlpha, 0f)
            assertEquals(tuning.cardFallbackAlpha, parameters.fallbackSurfaceAlpha, 0f)
            assertEquals(tuning.modalFallbackAlpha, parameters.fallbackModalSurfaceAlpha, 0f)
            assertEquals(
                tuning.motionFallbackAlpha,
                parameters.fallbackMotionSurfaceAlpha,
                0f
            )
            assertEquals(tuning.saturation, parameters.saturation, 0f)
            assertEquals(0f, parameters.scatteringStrength, 0f)
            assertTrue(parameters.highlightWidthDp <= 0.5f)
            // 标准档使用共享预滤波背景与细折射边，额外定向光学仍只在实时档启用。
            assertEquals(0f, parameters.specularStrength, 0f)
            assertEquals(0f, parameters.fresnelStrength, 0f)
            assertEquals(0f, parameters.causticLuminanceGain, 0f)
            assertEquals(0f, parameters.innerShadowStrength, 0f)
        }
    }

    fun `surface role and backend matrix selects the intended alpha`() {
        val parameters = LiquidTokenResolver.resolve(LiquidVisualTuningPolicy.resolve(dark = true))

        assertEquals(
            parameters.surfaceAlpha,
            LiquidSurfaceAlphaPolicy.resolve(SurfaceRole.CARD, false, parameters),
            0f
        )
        assertEquals(
            parameters.modalSurfaceAlpha,
            LiquidSurfaceAlphaPolicy.resolve(SurfaceRole.MODAL, false, parameters),
            0f
        )
        assertEquals(
            parameters.fallbackSurfaceAlpha,
            LiquidSurfaceAlphaPolicy.resolve(SurfaceRole.CARD, true, parameters),
            0f
        )
        assertEquals(
            parameters.fallbackModalSurfaceAlpha,
            LiquidSurfaceAlphaPolicy.resolve(SurfaceRole.MODAL, true, parameters),
            0f
        )
        assertEquals(
            parameters.motionSurfaceAlpha,
            LiquidSurfaceAlphaPolicy.resolve(SurfaceRole.MOTION_SURFACE, false, parameters),
            0f
        )
        assertEquals(
            parameters.fallbackMotionSurfaceAlpha,
            LiquidSurfaceAlphaPolicy.resolve(SurfaceRole.MOTION_SURFACE, true, parameters),
            0f
        )
    }

    fun `floating surfaces composite over real content while solids stay opaque`() {
        // "对下取色"（2026-09-21）：底部导航胶囊/选中滑块这类浮在滚动内容上的表面，
        // 玻璃层必须以部分 alpha 输出，真实下层内容才能透入合成——只折射合成底图
        // 永远拿不到底下的列表内容。呼出面板同理透入 scrim 压暗的底页；
        // 卡片/顶栏下层就是窗口底色，保持全不透明。
        assertTrue(LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.FLOATING) < 1f)
        assertTrue(LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.SELECTED_ITEM) < 1f)
        // 呼出面板与浮动条同一套做法：透出 scrim 压暗后的底页，读作通透玻璃。
        assertTrue(LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.MODAL) < 1f)
        assertEquals(1f, LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.CARD), 0f)
        assertEquals(1f, LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.TOP_BAR), 0f)
        // 透出量必须够明显——只留一层近乎不可见的膜不算"通透"。
        assertTrue(LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.FLOATING) <= 0.7f)
        assertTrue(LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.SELECTED_ITEM) <= 0.7f)
        // 模态层透入的是被 scrim 压暗的内容，通透性可以比浮动条更收一些，
        // 但要留下可读出的下层映射。
        assertTrue(LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.MODAL) <= 0.75f)
        // 2026-09-23 悬浮栏可读性改造：浮动条直透从 58% 降到 35%（0.42→0.65），栏里不再叠一层
        // 锐利文字；"对下取色"改由滚动边缘溶解 + 自适应补偿承担。原先"模态层比浮动条更不透"
        // 的次序约束随之撤销，改为给浮动条设可读性下限。
        assertTrue(LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.FLOATING) >= 0.6f)
    }

    fun `realtime profile keeps optics visible while preserving readable fallback`() {
        val tuning = LiquidVisualTuningPolicy.resolve(dark = true)
        val standard = LiquidTokenResolver.resolve(tuning)
        val realtime = LiquidTokenResolver.resolve(
            tuning,
            LiquidEffectProfile.REALTIME_CAPTURE
        )

        assertTrue(realtime.refractionHeightDp > standard.refractionHeightDp)
        assertTrue(realtime.refractionAmountDp > standard.refractionAmountDp)
        assertTrue(realtime.interiorDistortionDp > 0f)
        // 色散限域在 rim 带内（shader 按 edgeWeight 缩放、<0.02px 跳过取样）后重新开启：
        // 亚像素量级，整块彩边由限域排除；上界钉死防止调参漂移。标准档仍为 0。
        assertTrue(realtime.chromaticShiftDp > 0f)
        assertTrue(realtime.chromaticShiftDp <= 0.75f)
        assertEquals(0f, standard.chromaticShiftDp, 0f)
        // 抖动只给实时档；标准档为 0 时 shader 整条分支被 uniform 门掉。
        assertTrue(realtime.ditherAmplitude > 0f)
        assertTrue(realtime.ditherAmplitude <= 2f / 255f)
        assertEquals(0f, standard.ditherAmplitude, 0f)
        // 实时档采真实内容，微提饱和；标准档采已调好的光学底图，不动。
        assertTrue(realtime.saturation > standard.saturation)
        assertTrue(realtime.saturation <= 1.06f)
        assertEquals(tuning.saturation, standard.saturation, 0f)
        assertTrue(realtime.scatteringRadiusDp > 0f)
        assertTrue(realtime.scatteringStrength > 0f)
        assertTrue(realtime.surfaceAlpha < standard.surfaceAlpha)
        assertTrue(realtime.highlightWidthDp <= standard.highlightWidthDp)
        assertTrue(realtime.highlightAlpha < standard.highlightAlpha)
        assertEquals(standard.fallbackSurfaceAlpha, realtime.fallbackSurfaceAlpha, 0f)
    }

    fun `realtime profile enables directional optics and standard profile does not`() {
        val tuning = LiquidVisualTuningPolicy.resolve(dark = true)
        val standard = LiquidTokenResolver.resolve(tuning)
        val realtime = LiquidTokenResolver.resolve(
            tuning,
            LiquidEffectProfile.REALTIME_CAPTURE
        )

        assertTrue(realtime.specularStrength > 0f)
        assertTrue(realtime.fresnelStrength > 0f)
        assertTrue(realtime.causticLuminanceGain > 0f)
        assertTrue(realtime.innerShadowStrength > 0f)
        // 边缘亮度由 shader 的定向高光承担后，均匀白描边必须明显让位。
        assertTrue(realtime.highlightAlpha <= 0.22f)
        assertTrue(realtime.specularStrength <= 0.20f)
        assertTrue(realtime.fresnelStrength <= 0.08f)
        assertTrue(realtime.causticLuminanceGain <= 0.55f)
        // 光源方位角约定 L = (cos θ, sin θ)、y 轴向下；-145° 指向左上方。
        assertEquals(-145f, realtime.highlightAngleDegrees, 0f)
        assertEquals(standard.highlightAngleDegrees, realtime.highlightAngleDegrees, 0f)
    }

    fun `diffused surfaces retain a narrow lens without wide bright or recessed bands`() {
        listOf(false, true).forEach { dark ->
            val tuning = LiquidVisualTuningPolicy.resolve(dark)
            LiquidEffectProfile.entries.forEach { profile ->
                val parameters = LiquidTokenResolver.resolve(tuning, profile)
                assertTrue(parameters.refractionHeightDp in 1f..12f)
                assertTrue(parameters.refractionAmountDp in 1f..7f)
                assertTrue(parameters.depthEffect <= .16f)
                assertTrue(parameters.interiorDistortionDp <= 1.75f)
                assertTrue(parameters.specularStrength <= .06f)
                assertTrue(parameters.fresnelStrength <= .025f)
                assertTrue(parameters.innerShadowStrength <= .018f)
                // 采样触达上界要把色散位移一并算进 padding 预算，否则 rim 色散会采到
                // effect 区之外。
                assertTrue(parameters.effectPaddingDp >= parameters.refractionHeightDp +
                    parameters.interiorDistortionDp + parameters.scatteringRadiusDp +
                    parameters.chromaticShiftDp)
                assertTrue(parameters.saturation >= tuning.saturation)
                assertTrue(parameters.saturation <= 1.06f)
            }
        }
    }

    /** 2026-09-24：浅色玻璃不画内阴影（rim 中段压暗在近白表面上读作"向里发灰"），深色保留。 */
    fun lightPaletteDropsTheInnerShadow() {
        val tuning = LiquidVisualTuningPolicy.resolve(dark = false)
        val light = LiquidTokenResolver.resolve(tuning, LiquidEffectProfile.REALTIME_CAPTURE, dark = false)
        val dark = LiquidTokenResolver.resolve(tuning, LiquidEffectProfile.REALTIME_CAPTURE, dark = true)
        assertEquals(0f, light.innerShadowStrength, 0f)
        assertTrue(dark.innerShadowStrength > 0f)
        assertEquals(light.copy(innerShadowStrength = dark.innerShadowStrength), dark)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (LiquidTokenResolverCases) -> Unit) {
            val s = LiquidTokenResolverCases()
            block(s)
        }
        runIsolated { s -> s.`resolver preserves visual tuning mapping`() }
        runIsolated { s -> s.`surface role and backend matrix selects the intended alpha`() }
        runIsolated { s -> s.`floating surfaces composite over real content while solids stay opaque`() }
        runIsolated { s -> s.`realtime profile keeps optics visible while preserving readable fallback`() }
        runIsolated { s -> s.`realtime profile enables directional optics and standard profile does not`() }
        runIsolated { s -> s.`diffused surfaces retain a narrow lens without wide bright or recessed bands`() }
        runIsolated { s -> s.lightPaletteDropsTheInnerShadow() }
    }
}

private class LiquidWindowPositionStateCases {
    fun unnotifiedImePanAndReturnEachProduceOneRefresh() {
        val state = LiquidWindowPositionState()
        val matrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertTrue(state.update(matrix))
        repeat(120) { assertFalse(state.update(matrix)) }
        matrix[5] = -80.25f
        assertTrue(state.update(matrix))
        repeat(120) { assertFalse(state.update(matrix)) }
        matrix[5] = 0f
        assertTrue(state.update(matrix))
        assertFalse(state.update(matrix))
    }

    fun tinyWindowMotionAndScaleAreNotRoundedAway() {
        val state = LiquidWindowPositionState()
        val matrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        state.update(matrix)
        matrix[5] = .25f
        assertTrue(state.update(matrix))
        matrix[4] = .99f
        assertTrue(state.update(matrix))
        assertFalse(state.update(matrix))
    }

    fun invalidGeometryCannotPoisonTheLastKnownWindowPosition() {
        val state = LiquidWindowPositionState()
        val valid = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        state.update(valid)
        assertFalse(state.update(floatArrayOf(1f)))
        val invalid = valid.clone().apply { this[5] = Float.NaN }
        assertFalse(state.update(invalid))
        assertFalse(state.update(valid))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (LiquidWindowPositionStateCases) -> Unit) {
            val s = LiquidWindowPositionStateCases()
            block(s)
        }
        runIsolated { s -> s.unnotifiedImePanAndReturnEachProduceOneRefresh() }
        runIsolated { s -> s.tinyWindowMotionAndScaleAreNotRoundedAway() }
        runIsolated { s -> s.invalidGeometryCannotPoisonTheLastKnownWindowPosition() }
    }
}

private class ModuleMemoryPressureHubCases {

    fun `release walks current listeners and unregister stops further calls`() {
        val counts = mutableListOf(0, 0)
        val first = ModuleMemoryPressureListener { counts[0] += 1 }
        val second = ModuleMemoryPressureListener { counts[1] += 1 }
        ModuleMemoryPressureHub.addListener(first)
        ModuleMemoryPressureHub.addListener(second)
        try {
            ModuleMemoryPressureHub.releaseGraphics()
            assertEquals(listOf(1, 1), counts)
            ModuleMemoryPressureHub.removeListener(second)
            ModuleMemoryPressureHub.releaseGraphics()
            assertEquals(listOf(2, 1), counts)
            ModuleMemoryPressureHub.persistForImminentKill()
            assertEquals(listOf(2, 1), counts)
        } finally {
            ModuleMemoryPressureHub.removeListener(first)
            ModuleMemoryPressureHub.removeListener(second)
        }
    }
}

private class ScrollSurfaceScopeCases {
    private class Node(val parent: Node? = null)

    fun onlyDescendantsOfTheActualScrollHostAreAffected() {
        val root = Node()
        val page = Node(root)
        val anotherPage = Node(root)
        assertTrue(ScrollSurfaceScope.contains(Node(Node(page)), page) { it.parent })
        assertFalse(ScrollSurfaceScope.contains(page, page) { it.parent })
        assertFalse(ScrollSurfaceScope.contains(root, page) { it.parent })
        assertFalse(ScrollSurfaceScope.contains(Node(anotherPage), page) { it.parent })
        assertFalse(ScrollSurfaceScope.contains(Node(), page) { it.parent })
    }

    fun deepHierarchyHasNoArbitraryRejectionThreshold() {
        val host = Node()
        var child = host
        repeat(512) { child = Node(child) }
        assertTrue(ScrollSurfaceScope.contains(child, host) { it.parent })
    }

    fun lateScrollNotificationIsConsumedBeforeDrawAndDrawResetsItsPhase() {
        val batch = LiquidRefreshBatch()
        batch.mark(contentChanged = false)
        assertEquals(LiquidRefreshBatch.POSITION, batch.take())
        batch.beforeDraw()
        assertTrue(batch.isAfterPreDraw)
        batch.mark(contentChanged = false)
        assertEquals(LiquidRefreshBatch.POSITION, batch.take())
        batch.drawn()
        assertFalse(batch.isAfterPreDraw)
        assertEquals(0, batch.take())
    }

    fun synchronousScrollDoesNotConsumeOtherPendingContentOrAdvanceTheRecordedOrigin() {
        val batch = LiquidRefreshBatch()
        val state = LiquidSurfaceRefreshState()
        batch.mark(contentChanged = true)
        batch.mark(contentChanged = true, captureOnly = true)
        var recorded = 0
        var actual = 0
        fun refresh(): Boolean = state.shouldRefresh(true, actual != recorded, false)
        assertFalse(refresh())
        actual = 12
        assertTrue(refresh())
        // Invalidating is not recording. A second offset in this same frame still needs a redraw.
        actual = 24
        assertTrue(refresh())
        assertEquals(0, recorded)
        recorded = actual
        repeat(120) { assertFalse(refresh()) }
        assertEquals(LiquidRefreshBatch.CONTENT or LiquidRefreshBatch.CAPTURE, batch.take())
    }

    fun scopedVisibilityStillRefreshesHiddenSurfacesOnReentry() {
        val state = LiquidSurfaceRefreshState()
        assertFalse(state.shouldRefresh(false, false, false))
        assertTrue(state.shouldRefresh(true, false, false))
        assertFalse(state.shouldRefresh(true, false, false))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ScrollSurfaceScopeCases) -> Unit) {
            val s = ScrollSurfaceScopeCases()
            block(s)
        }
        runIsolated { s -> s.onlyDescendantsOfTheActualScrollHostAreAffected() }
        runIsolated { s -> s.deepHierarchyHasNoArbitraryRejectionThreshold() }
        runIsolated { s -> s.lateScrollNotificationIsConsumedBeforeDrawAndDrawResetsItsPhase() }
        runIsolated { s -> s.synchronousScrollDoesNotConsumeOtherPendingContentOrAdvanceTheRecordedOrigin() }
        runIsolated { s -> s.scopedVisibilityStillRefreshesHiddenSurfacesOnReentry() }
    }
}
