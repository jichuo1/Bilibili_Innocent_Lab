package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowContentSample
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowContentStatistics
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowLegibility
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowLegibilityPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowScrollEdgePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowSurfaceOptics
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidLegibilityTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after

/** 2026-09-23 悬浮栏可读性改造 A 期：补偿策略、溶解几何与探针统计的纯函数约束。 */
class GlowLegibilityPolicyTest {
    // 与真机主题同量级：浅色 colorTextGray #323B42 / 深色 #D3D3D3，表面近白 / 近黑。
    private val lightText = 0xFF323B42.toInt()
    private val darkText = 0xFFD3D3D3.toInt()
    private val lightOptics = GlowSurfaceOptics(0xFFF6F4F8.toInt(), 0.3f, 0.6f, 0.35f)
    private val darkOptics = GlowSurfaceOptics(0xFF1C1B1F.toInt(), 0.26f, 0.56f, 0.35f)

    private fun contrastAt(optics: GlowSurfaceOptics, foreground: Int, luma: Float, boost: Float): Float {
        val tint = optics.baseTintAlpha + (optics.maxTintAlpha - optics.baseTintAlpha) * boost
        val surface = GlowLegibilityPolicy.encodedLuma(optics.surfaceColor)
        val background = GlowLegibilityPolicy.linearize(tint * surface + (1f - tint) * luma)
        return GlowLegibilityPolicy.contrast(GlowLegibilityPolicy.relativeLuminance(foreground), background)
    }

    fun readableContentNeedsNoCompensation() {
        // 浅色主题、浅色底图、没有细节：原样即可，不能无端把栏糊厚。
        val light = GlowLegibilityPolicy.target(GlowContentSample(0.88f, 0.001f), lightText, lightOptics)
        assertEquals(GlowLegibility.NEUTRAL.boost, light.boost, 0f)
        val dark = GlowLegibilityPolicy.target(GlowContentSample(0.12f, 0.001f), darkText, darkOptics)
        assertEquals(0f, dark.boost, 0f)
        assertEquals(0f, dark.edgeDefinition, 0f)
    }

    fun contrastBoostIsTheSmallestThatRestoresBodyTextContrast() {
        // 浅色主题压在深色壁纸上：常态对比度不足，补偿后必须达到 4.5:1。
        val sample = GlowContentSample(0.25f, 0f)
        assertTrue(contrastAt(lightOptics, lightText, 0.25f, 0f) < GlowLegibilityPolicy.TARGET_CONTRAST)
        val result = GlowLegibilityPolicy.target(sample, lightText, lightOptics)
        assertTrue(result.boost > 0f && result.boost < 1f)
        // 量化是四舍五入到 1/16：允许在达标点下方最多半个量化步长。
        val tolerance = GlowLegibilityPolicy.QUANTUM * 0.5f
        assertTrue(contrastAt(lightOptics, lightText, 0.25f, (result.boost + tolerance).coerceAtMost(1f)) >=
            GlowLegibilityPolicy.TARGET_CONTRAST)
    }

    fun unreachableContrastSaturatesInsteadOfOvershooting() {
        val result = GlowLegibilityPolicy.target(GlowContentSample(0.95f, 0f), darkText, darkOptics)
        assertEquals(1f, result.boost, 0f)
        // 深色表面本身就能从亮背景里分出来，边缘加深只给一小份。
        assertTrue(result.edgeDefinition in 0.0001f..GlowLegibilityPolicy.DARK_SURFACE_EDGE_SHARE + 0.05f)
    }

    fun denseTextUnderTheBarRaisesBoostEvenWhenAverageContrastIsFine() {
        val calm = GlowLegibilityPolicy.target(GlowContentSample(0.85f, 0.002f), lightText, lightOptics)
        val busy = GlowLegibilityPolicy.target(GlowContentSample(0.85f, 0.2f), lightText, lightOptics)
        assertEquals(0f, calm.boost, 0f)
        assertEquals(GlowLegibilityPolicy.DETAIL_BOOST_CAP, busy.boost, GlowLegibilityPolicy.QUANTUM)
        // 直透为 0（玻璃完全不透）时，下方细节再密也漏不进来。
        val sealed = GlowLegibilityPolicy.target(
            GlowContentSample(0.85f, 0.2f), lightText, lightOptics.copy(seeThrough = 0f))
        assertEquals(0f, sealed.boost, 0f)
    }

    fun brightContentDefinesTheEdgeOfALightCapsule() {
        val result = GlowLegibilityPolicy.target(GlowContentSample(0.97f, 0f), lightText, lightOptics)
        assertEquals(1f, result.edgeDefinition, 0f)
        val midtone = GlowLegibilityPolicy.target(GlowContentSample(0.5f, 0f), lightText, lightOptics)
        assertEquals(0f, midtone.edgeDefinition, 0f)
    }

    fun outputsAreQuantized() {
        for (luma in 0..20) for (busy in 0..10) {
            val result = GlowLegibilityPolicy.target(GlowContentSample(luma / 20f, busy / 50f), lightText, lightOptics)
            val steps = result.boost / GlowLegibilityPolicy.QUANTUM
            assertEquals(Math.round(steps).toFloat(), steps, 1e-4f)
        }
    }

    fun hysteresisIgnoresJitterButAlwaysReachesTheEndpoints() {
        val current = GlowLegibility(0.5f, 0f)
        assertNull(GlowLegibilityPolicy.settle(current, GlowLegibility(0.5625f, 0f)))
        assertNotNull(GlowLegibilityPolicy.settle(current, GlowLegibility(0.75f, 0f)))
        // 残留的小补偿必须能撤干净，封顶也必须能到。
        assertEquals(GlowLegibility.NEUTRAL, GlowLegibilityPolicy.settle(GlowLegibility(0.0625f, 0f), GlowLegibility.NEUTRAL))
        assertEquals(GlowLegibility(1f, 0f), GlowLegibilityPolicy.settle(GlowLegibility(0.9375f, 0f), GlowLegibility(1f, 0f)))
        assertNull(GlowLegibilityPolicy.settle(current, current))
    }

    fun foregroundPushesAwayFromTheSurfaceAndKeepsAlpha() {
        val darker = GlowLegibilityPolicy.foreground(lightText, 1f)
        assertTrue(GlowLegibilityPolicy.relativeLuminance(darker) < GlowLegibilityPolicy.relativeLuminance(lightText))
        val lighter = GlowLegibilityPolicy.foreground(darkText, 1f)
        assertTrue(GlowLegibilityPolicy.relativeLuminance(lighter) > GlowLegibilityPolicy.relativeLuminance(darkText))
        assertEquals(lightText, GlowLegibilityPolicy.foreground(lightText, 0f))
        val translucent = 0x80D3D3D3.toInt()
        assertEquals(0x80, GlowLegibilityPolicy.foreground(translucent, 1f) ushr 24)
    }

    fun coverageStartsAtZeroAtRestAndSaturatesAfterOneBarHeight() {
        assertEquals(0f, GlowScrollEdgePolicy.topCoverage(0, 200f), 0f)
        assertEquals(0.5f, GlowScrollEdgePolicy.topCoverage(100, 200f), 1e-6f)
        assertEquals(1f, GlowScrollEdgePolicy.topCoverage(900, 200f), 0f)
        assertEquals(0f, GlowScrollEdgePolicy.topCoverage(900, 0f), 0f)
        // 底部：滚到底时最后一行停在栏上方，栏下没有内容。
        assertEquals(0f, GlowScrollEdgePolicy.bottomCoverage(1000, 1000, 250f), 0f)
        assertEquals(1f, GlowScrollEdgePolicy.bottomCoverage(0, 1000, 250f), 0f)
        // 内容不足一屏：没有可滚动范围，也就没有内容压在栏下。
        assertEquals(0f, GlowScrollEdgePolicy.bottomCoverage(0, 0, 250f), 0f)
    }

    fun pagerCoverageInterpolatesBetweenNeighbouringPages() {
        val pages = floatArrayOf(0f, 1f, 0.5f, 0f)
        assertEquals(0f, GlowScrollEdgePolicy.pagerCoverage(0f, 4) { pages[it] }, 0f)
        assertEquals(0.25f, GlowScrollEdgePolicy.pagerCoverage(0.25f, 4) { pages[it] }, 1e-6f)
        assertEquals(0.75f, GlowScrollEdgePolicy.pagerCoverage(1.5f, 4) { pages[it] }, 1e-6f)
        assertEquals(0f, GlowScrollEdgePolicy.pagerCoverage(9f, 4) { pages[it] }, 0f)
        assertEquals(0f, GlowScrollEdgePolicy.pagerCoverage(0f, 0) { error("no pages") }, 0f)
    }

    fun dissolveProfileFadesOutwardToInwardWithStrictlyIncreasingStops() {
        val (positions, alphas) = GlowScrollEdgePolicy.profile(12f, 186f, 258f)
        assertEquals(positions.size, alphas.size)
        assertEquals(0f, positions.first(), 0f)
        assertEquals(1f, positions.last(), 0f)
        for (i in 1 until positions.size) assertTrue(positions[i] > positions[i - 1])
        for (i in 1 until alphas.size) assertTrue(alphas[i] < alphas[i - 1])
        assertEquals(0f, alphas.last(), 0f)
        // 退化几何（胶囊贴边、没有尾段）仍然给出合法渐变。
        val (degenerate, _) = GlowScrollEdgePolicy.profile(0f, 100f, 100f)
        for (i in 1 until degenerate.size) assertTrue(degenerate[i] > degenerate[i - 1])
        assertEquals(1f, degenerate.last(), 0f)
    }

    fun statisticsMeasureLumaAndDetailAndIgnoreTransparentCorners() {
        val grey = 0xFF808080.toInt()
        val flat = GlowContentStatistics.measure(IntArray(16) { grey }, 4, 4, FloatArray(4))!!
        assertEquals(128f / 255f, flat.luma, 1e-4f)
        assertEquals(0f, flat.busyness, 0f)
        val checker = IntArray(16) { if ((it % 4 + it / 4) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val busy = GlowContentStatistics.measure(checker, 4, 4, FloatArray(4))!!
        assertEquals(0.5f, busy.luma, 1e-4f)
        assertEquals(1f, busy.busyness, 1e-4f)
        // 透明角落不计入均值，也不和相邻像素形成"边缘"。
        val cornered = IntArray(16) { if (it == 0) 0 else grey }
        val corner = GlowContentStatistics.measure(cornered, 4, 4, FloatArray(4))!!
        assertEquals(128f / 255f, corner.luma, 1e-4f)
        assertEquals(0f, corner.busyness, 0f)
        assertNull(GlowContentStatistics.measure(IntArray(4), 2, 2, FloatArray(2)))
    }

    fun liquidTintMappingMatchesTheOpticsItReports() {
        for (base in listOf(0.25f, 0.3f, 0.75f, 0.81f, 0.95f)) {
            val ceiling = LiquidLegibilityTuning.ceiling(base)
            assertTrue(ceiling >= base && ceiling <= maxOf(base, LiquidLegibilityTuning.MAX_TINT_ALPHA))
            assertEquals(base, LiquidLegibilityTuning.tintAlpha(base, 0f), 0f)
            assertEquals(ceiling, LiquidLegibilityTuning.tintAlpha(base, 1f), 1e-6f)
            assertEquals((base + ceiling) / 2f, LiquidLegibilityTuning.tintAlpha(base, 0.5f), 1e-6f)
        }
    }

    /** 2026-09-24 浅色模式：暗带由等宽匀色改为贴边最深、向内单调缓出归零，内沿不能有台阶。 */
    fun edgeBandFadesInwardWithoutAStep() {
        val steps = LiquidLegibilityTuning.EDGE_BAND_STEPS
        assertTrue(steps >= 12)
        val depth = (1..steps).map(LiquidLegibilityTuning::edgeBandDepthAlpha)
        for (j in 1 until steps) assertTrue(depth[j] < depth[j - 1])
        assertTrue(depth.first() <= LiquidLegibilityTuning.EDGE_BAND_PEAK_ALPHA)
        // 最内一级接近 0：内沿与中心之间没有可见台阶。
        assertTrue(depth.last() < LiquidLegibilityTuning.EDGE_BAND_PEAK_ALPHA * 0.001f)
        for (strength in listOf(1f, 0.5f, 0.25f)) {
            val alphas = (1..steps).map { LiquidLegibilityTuning.edgeBandStepAlpha255(it, strength) }
            assertTrue(alphas.all { it >= 0 })
            // 各条之和就是贴边处的总暗度（先取整再差分，累积不丢量化余数）。
            assertEquals(Math.round(255f * depth.first() * strength), alphas.sum())
            // 每级 8 位差不超过 4（每级约 3px，折合每像素约 1 级灰度）：没有肉眼可见的细台阶。
            assertTrue(alphas.all { it <= 4 })
        }
        assertEquals(0, LiquidLegibilityTuning.edgeBandStepAlpha255(steps + 1, 1f))
    }

    /** 2026-09-24：边缘颜色随主题，浅色画亮边而不是和深色一样的暗边（用户反馈浅色下边缘发脏）。 */
    fun edgeDefinitionFollowsTheTheme() {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/skin/liquid/LiquidActivityRenderer.kt"
        val renderer = SourceContract.read(path)
        assertTrue(renderer.contains("private val legibilityEdgeColor = if (darkPalette) Color.BLACK else Color.WHITE"))
        val paints = renderer.after("private val legibilityRingPaint").before("/** 悬浮栏宿主")
        assertTrue(!paints.contains("Color.BLACK"))
        val edge = renderer.after("private fun drawLegibilityEdge(").before("private inline fun drawWithFallback")
        assertTrue(edge.contains("legibilityRingAlpha"))
        assertTrue(edge.contains("edgeBandStepAlpha255(step, strength, legibilityBandPeak,"))
        assertTrue(edge.contains("lightProfile = !darkPalette"))
        assertTrue(LiquidLegibilityTuning.EDGE_BAND_DP_LIGHT >= LiquidLegibilityTuning.EDGE_BAND_DP)
        // 亮边要比暗边强：白色叠在近白表面上，同样的 alpha 几乎看不见。
        assertTrue(LiquidLegibilityTuning.EDGE_RING_ALPHA_LIGHT > LiquidLegibilityTuning.EDGE_RING_ALPHA)
        assertTrue(LiquidLegibilityTuning.EDGE_BAND_PEAK_ALPHA_LIGHT > LiquidLegibilityTuning.EDGE_BAND_PEAK_ALPHA)
        assertEquals(LiquidLegibilityTuning.edgeBandDepthAlpha(1) * 3f,
            LiquidLegibilityTuning.edgeBandDepthAlpha(1, LiquidLegibilityTuning.EDGE_BAND_PEAK_ALPHA * 3f), 1e-6f)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (GlowLegibilityPolicyTest) -> Unit) {
            val s = GlowLegibilityPolicyTest()
            block(s)
        }
        runIsolated { s -> s.readableContentNeedsNoCompensation() }
        runIsolated { s -> s.contrastBoostIsTheSmallestThatRestoresBodyTextContrast() }
        runIsolated { s -> s.unreachableContrastSaturatesInsteadOfOvershooting() }
        runIsolated { s -> s.denseTextUnderTheBarRaisesBoostEvenWhenAverageContrastIsFine() }
        runIsolated { s -> s.brightContentDefinesTheEdgeOfALightCapsule() }
        runIsolated { s -> s.outputsAreQuantized() }
        runIsolated { s -> s.hysteresisIgnoresJitterButAlwaysReachesTheEndpoints() }
        runIsolated { s -> s.foregroundPushesAwayFromTheSurfaceAndKeepsAlpha() }
        runIsolated { s -> s.coverageStartsAtZeroAtRestAndSaturatesAfterOneBarHeight() }
        runIsolated { s -> s.pagerCoverageInterpolatesBetweenNeighbouringPages() }
        runIsolated { s -> s.dissolveProfileFadesOutwardToInwardWithStrictlyIncreasingStops() }
        runIsolated { s -> s.statisticsMeasureLumaAndDetailAndIgnoreTransparentCorners() }
        runIsolated { s -> s.liquidTintMappingMatchesTheOpticsItReports() }
        runIsolated { s -> s.edgeBandFadesInwardWithoutAStep() }
        runIsolated { s -> s.edgeDefinitionFollowsTheTheme() }
    }
}
