package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidVisualTuningPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before

class LiquidVisualTuningPolicyTest {

    /**
     * 未设自定义图时 Liquid 的自动 underlay 必须与标准磨砂皮肤共用 [AmbientBackdropScene]：
     * 两种材质下用户看到的是同一个 Monet 氛围背景；去颗粒不改变原配色和暗角。
     */
    fun `auto backdrop shares the clean ambient scene with the frosted skin`() {
        val liquid = source("liquid/LiquidBackdropSource")
        val frosted = source("material/FrostedMaterialRenderer")
        val scene = source("background/AmbientBackdropScene")

        assertTrue(liquid.contains("AmbientBackdropScene.paint(canvas, palette"))
        assertTrue(frosted.contains("AmbientBackdropScene.paint(canvas, palette"))
        assertFalse(scene.contains("addGrain"))
        assertFalse(liquid.contains("addGrain"))
        assertFalse(frosted.contains("addGrain"))
        assertTrue(scene.contains("Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG"))
        assertTrue(scene.contains("color = palette.primary, alpha = if (dark) 0x2E else 0x3A"))
        assertTrue(scene.contains("ColorUtils.setAlphaComponent(Color.BLACK, if (dark) 0x38 else 0x14)"))
    }

    fun `automatic liquid source shares one bitmap without changing custom image optics`() {
        val liquid = source("liquid/LiquidBackdropSource")
        val automatic = liquid.after("fun create(").before("fun fromCustomBitmap(")
        assertEquals(1, Regex("createBitmap\\(").findAll(automatic).count())
        assertFalse(automatic.contains("IntArray("))
        assertFalse(automatic.contains("getPixels("))
        assertFalse(automatic.contains("setPixels("))
        assertFalse(automatic.contains("opticalBitmap ="))
        assertTrue(liquid.contains("private val opticalBitmap: Bitmap = bitmap"))
        val custom = liquid.after("fun fromCustomBitmap(").before("fun fromRealtimeBitmap(")
        assertTrue(custom.contains("LiquidOpticalSamplingPolicy.soften("))
        assertTrue(custom.contains("opticalBitmap = optical"))
        val frosted = source("material/FrostedMaterialRenderer")
            .after("private object ModernBackdropFactory").before("private class ModernSurfaceDrawable")
        assertTrue(frosted.contains("ModernBackdropBlur.blur("))
    }

    fun `shared bitmap publication and disposal preserve ownership guards`() {
        val liquid = source("liquid/LiquidBackdropSource")
        val publish = liquid.after("fun markPublished()").before("fun discardUnpublished()")
        assertTrue(publish.contains("if (opticalBitmap !== bitmap) opticalBitmap.prepareToDraw()"))
        val discard = liquid.after("fun discardUnpublished()").before("fun drawSuppressionBackdropMasked(")
        assertTrue(discard.contains("check(!published && !isRealtime)"))
        assertTrue(discard.contains("if (opticalBitmap !== bitmap && !opticalBitmap.isRecycled) opticalBitmap.recycle()"))
        assertTrue(discard.contains("if (!bitmap.isRecycled) bitmap.recycle()"))
        val close = liquid.after("override fun close()").before("companion object")
        assertFalse(close.contains(".recycle()"))
    }

    fun `saturation stays near the source palette`() {
        listOf(false, true).forEach { dark ->
            val tuning = LiquidVisualTuningPolicy.resolve(dark)

            assertTrue(tuning.saturation in .94f..1f)
        }
    }

    fun `gpu glass is lighter than translucent fallback`() {
        listOf(false, true).forEach { dark ->
            val tuning = LiquidVisualTuningPolicy.resolve(dark)

            assertTrue(tuning.cardGlassAlpha < tuning.cardFallbackAlpha)
            assertTrue(tuning.modalGlassAlpha < tuning.modalFallbackAlpha)
            assertTrue(tuning.motionGlassAlpha < tuning.motionFallbackAlpha)
            assertTrue(tuning.cardGlassAlpha in .24f..0.34f)
            assertTrue(tuning.modalGlassAlpha in .4f..0.9f)
            assertTrue(tuning.cardFallbackAlpha < 0.8f)
            assertTrue(tuning.modalFallbackAlpha < 0.95f)
        }
    }

    fun `modal remains more opaque than card`() {
        listOf(false, true).forEach { dark ->
            val tuning = LiquidVisualTuningPolicy.resolve(dark)

            assertTrue(tuning.modalGlassAlpha > tuning.cardGlassAlpha)
            assertTrue(tuning.modalFallbackAlpha > tuning.cardFallbackAlpha)
            assertTrue(tuning.motionGlassAlpha > tuning.cardGlassAlpha)
            assertTrue(tuning.motionGlassAlpha <= tuning.modalGlassAlpha)
            assertTrue(tuning.motionFallbackAlpha > tuning.cardFallbackAlpha)
            assertTrue(tuning.motionFallbackAlpha < tuning.modalFallbackAlpha)
        }
    }

    private fun source(name: String): String = SourceContract.read("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/skin/$name.kt")

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (LiquidVisualTuningPolicyTest) -> Unit) {
            val s = LiquidVisualTuningPolicyTest()
            block(s)
        }
        runIsolated { s -> s.`auto backdrop shares the clean ambient scene with the frosted skin`() }
        runIsolated { s -> s.`automatic liquid source shares one bitmap without changing custom image optics`() }
        runIsolated { s -> s.`shared bitmap publication and disposal preserve ownership guards`() }
        runIsolated { s -> s.`saturation stays near the source palette`() }
        runIsolated { s -> s.`gpu glass is lighter than translucent fallback`() }
        runIsolated { s -> s.`modal remains more opaque than card`() }
    }
}
