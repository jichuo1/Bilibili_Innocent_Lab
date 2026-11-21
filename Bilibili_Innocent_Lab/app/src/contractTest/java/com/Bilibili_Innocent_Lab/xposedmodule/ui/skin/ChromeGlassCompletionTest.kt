package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowContentSample
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowLegibilityPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowSurfaceOptics
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidChromeGlassPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.LensRefractionPolicy
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract

class ChromeGlassCompletionTest {
    fun edgeMappingHasNoClampedPlateauOrReversal() {
        for (size in listOf(48f, 96f, 382f, 1080f)) {
            for ((gain, push) in listOf(
                LensRefractionPolicy.CENTER_GAIN_X to LensRefractionPolicy.RIM_PUSH_X,
                LensRefractionPolicy.CENTER_GAIN_Y to LensRefractionPolicy.RIM_PUSH_Y
            )) {
                var previous = Float.NaN
                for (pixel in 0 until size.toInt()) {
                    val position = pixel + 0.5f
                    val sampled = LensRefractionPolicy.nodeSampleCoordinate(position, size, gain, push)
                    val room = minOf(position - 0.5f, size - 0.5f - position)
                    assertTrue(sampled in 0.5f..(size - 0.5f))
                    assertTrue(kotlin.math.abs(sampled - position) <= room * 0.5f + 0.001f)
                    if (previous.isFinite()) assertTrue("No repeated edge column: size=$size p=$position",
                        sampled - previous > 0.35f)
                    previous = sampled
                }
            }
        }
    }

    fun wideCapsulesReproduceOutOfNodeSamplesAtBothEnds() {
        val width = 382f
        val padding = LensRefractionPolicy.MARGIN_DP + 2f
        fun position(u: Float) = padding + (LensRefractionPolicy.lens(u,
            LensRefractionPolicy.CENTER_GAIN_X, LensRefractionPolicy.RIM_PUSH_X) + 1f) * width / 2f
        assertTrue(position(-1f) < 0f)
        assertTrue(position(1f) > width + 2f * padding)
        assertEquals(padding + 0.5f, position(-1f).coerceIn(padding + 0.5f, width + padding - 0.5f), 0f)
        assertEquals(width + padding - 0.5f,
            position(1f).coerceIn(padding + 0.5f, width + padding - 0.5f), 0f)
    }

    fun nodeSamplesStayInTheVisibleInputAndPreserveUnclampedInteriorCoordinates() {
        for (width in listOf(96, 382, 1080)) for (pad in listOf(14, 16, 48)) {
            val extent = width + 2 * pad
            for (pixel in 0 until width) {
                val u = (pixel + 0.5f) / width * 2f - 1f
                val lensed = LensRefractionPolicy.lens(u,
                    LensRefractionPolicy.CENTER_GAIN_X, LensRefractionPolicy.RIM_PUSH_X)
                val raw = pad + (lensed + 1f) * width / 2f
                val position = pixel + 0.5f
                val node = pad + LensRefractionPolicy.nodeSampleCoordinate(position, width.toFloat(),
                    LensRefractionPolicy.CENTER_GAIN_X, LensRefractionPolicy.RIM_PUSH_X)
                val software = ((lensed + 1f) * width / 2f + pad - 0.5f).coerceIn(0f, extent - 1f)
                assertTrue(node in (pad + 0.5f)..(pad + width - 0.5f))
                val room = minOf(position - 0.5f, width - 0.5f - position)
                if (room >= LensRefractionPolicy.nodeTravelBudget(width.toFloat(),
                        LensRefractionPolicy.CENTER_GAIN_X, LensRefractionPolicy.RIM_PUSH_X)) {
                    assertEquals(software, node - 0.5f, 0.0002f)
                }
            }
        }
    }

    fun softwareReferenceDoesNotIntroduceTransparentStripsAtCapsuleEnds() {
        val width = 382; val height = 64; val pad = 16
        val source = IntArray((width + pad * 2) * (height + pad * 2)) { 0xff405060.toInt() }
        val output = IntArray(width * height)
        LensRefractionPolicy.remap(source, width + pad * 2, height + pad * 2, pad, output, width, height)
        assertTrue(output.all { it == 0xff405060.toInt() })
    }

    fun clearNodeGlassCompensatesBusyContentEvenThoughItsCompositeIsOpaque() {
        val optics = GlowSurfaceOptics(0xff202020.toInt(), 0.18f, 0.48f,
            LiquidChromeGlassPolicy.DETAIL_SEE_THROUGH)
        val text = 0xffd3d3d3.toInt()
        val busy = GlowContentSample(0.15f, 0.16f)
        assertEquals(0f, GlowLegibilityPolicy.target(busy, text, optics.copy(seeThrough = 0f)).boost, 0f)
        assertTrue(GlowLegibilityPolicy.target(busy, text, optics).boost > 0f)
        assertEquals(0f, GlowLegibilityPolicy.target(GlowContentSample(0.15f, 0f), text, optics).boost, 0f)
    }

    fun detailsIncreaseCompensationMonotonicallyInBothThemes() {
        for ((surface, text, luma) in listOf(
            Triple(0xff202020.toInt(), 0xffd3d3d3.toInt(), 0.15f),
            Triple(0xfff0f0f0.toInt(), 0xff323b42.toInt(), 0.9f)
        )) {
            val optics = GlowSurfaceOptics(surface, 0.18f, 0.48f, LiquidChromeGlassPolicy.DETAIL_SEE_THROUGH)
            var previous = 0f
            for (step in 0..100) {
                val target = GlowLegibilityPolicy.target(GlowContentSample(luma, step / 1000f), text, optics)
                assertTrue(target.boost >= previous)
                assertTrue(target.boost in 0f..1f)
                previous = target.boost
            }
        }
    }

    fun finalChromeProfileKeepsFullOpticsAndConnectsBothParameters() {
        fun source(name: String): String = SourceContract.read("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/skin/$name.kt")
        assertEquals(3f, LiquidChromeGlassPolicy.BLUR_RADIUS_DP, 0f)
        val node = source("liquid/LiquidChromeBackdropApi31")
        assertTrue(node.contains("CHROME_BLUR_RADIUS_DP = LiquidChromeGlassPolicy.BLUR_RADIUS_DP"))
        assertTrue(node.contains("setFloatUniform(\"motionLite\", 0f)"))
        assertTrue(node.contains("setFloatUniform(\"nodeInput\", 1f)"))
        assertTrue(node.contains("applyLiquidOpticalUniforms(parameters, density)"))
        val window = source("liquid/LiquidRefractionBackendApi33")
        assertTrue(window.contains("setFloatUniform(\"nodeInput\", 0f)"))
        assertTrue(window.contains("if (nodeInput > 0.5)"))
        assertTrue(window.contains("backdropOrigin + size - float2(0.5)"))
        assertTrue(source("liquid/LiquidActivityRenderer")
            .contains("CHROME_DETAIL_SEE_THROUGH = LiquidChromeGlassPolicy.DETAIL_SEE_THROUGH"))
        assertTrue(source("material/FrostedChromeGlassApi31")
            .contains("content.eval(clamp(source, origin + float2(0.5), origin + size - float2(0.5)))"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (ChromeGlassCompletionTest) -> Unit) {
            val s = ChromeGlassCompletionTest()
            block(s)
        }
        runIsolated { s -> s.edgeMappingHasNoClampedPlateauOrReversal() }
        runIsolated { s -> s.wideCapsulesReproduceOutOfNodeSamplesAtBothEnds() }
        runIsolated { s -> s.nodeSamplesStayInTheVisibleInputAndPreserveUnclampedInteriorCoordinates() }
        runIsolated { s -> s.softwareReferenceDoesNotIntroduceTransparentStripsAtCapsuleEnds() }
        runIsolated { s -> s.clearNodeGlassCompensatesBusyContentEvenThoughItsCompositeIsOpaque() }
        runIsolated { s -> s.detailsIncreaseCompensationMonotonicallyInBothThemes() }
        runIsolated { s -> s.finalChromeProfileKeepsFullOpticsAndConnectsBothParameters() }
    }
}
