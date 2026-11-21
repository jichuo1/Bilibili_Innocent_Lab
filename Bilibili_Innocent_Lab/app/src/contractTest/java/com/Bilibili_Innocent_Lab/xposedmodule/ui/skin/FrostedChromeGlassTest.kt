package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.LensRefractionPolicy
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before

/**
 * 2026-09-23 悬浮栏可读性改造 C 期：柔光悬浮栏的内容节点玻璃必须与软件透镜同一套光学。
 */
class FrostedChromeGlassTest {
    fun lensIlluminationPreservesPremultipliedAlphaAtTransparentEdges() {
        val gain = LensRefractionPolicy.LUMINANCE_GAIN
        val bias = LensRefractionPolicy.LUMINANCE_BIAS / 255f
        for (alphaByte in 0..255) {
            val alpha = alphaByte / 255f
            for (channelByte in 0..255) {
                val channel = channelByte / 255f
                val shader = (channel * alpha * gain + bias * alpha).coerceIn(0f, alpha)
                val straight = (channel * gain + bias).coerceIn(0f, 1f) * alpha
                assertTrue(abs(shader - straight) < 0.000001f)
                assertTrue(shader in 0f..alpha)
                if (alphaByte == 0) assertTrue(shader == 0f)
            }
        }
    }

    private fun source(relative: String): String =
        SourceContract.read(relative).replace("\r\n", "\n")

    private val base = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/skin"

    /**
     * 软件管线在预乘空间做 `c·gain + bias·a`；GPU 端用 ColorMatrixColorFilter（作用于非预乘色）
     * 做 `c·gain + bias`。两者展开后相同，这里逐像素核对（容差只来自整数舍入）。
     */
    fun colorMatrixIlluminationMatchesThePremultipliedSoftwareStep() {
        val gain = LensRefractionPolicy.LUMINANCE_GAIN
        val bias = LensRefractionPolicy.LUMINANCE_BIAS
        for (alpha in intArrayOf(255, 200, 128, 64)) for (value in intArrayOf(0, 17, 90, 180, 230, 255)) {
            val straight = (alpha shl 24) or (value shl 16) or ((255 - value) shl 8) or (value / 2)
            val software = intArrayOf(straight)
            LensRefractionPolicy.premultiply(software)
            LensRefractionPolicy.illuminate(software)
            LensRefractionPolicy.unpremultiply(software)
            for (shift in intArrayOf(16, 8, 0)) {
                val channel = straight ushr shift and 255
                val gpu = (channel * gain + bias).roundToInt().coerceIn(0, 255)
                val sw = software[0] ushr shift and 255
                // 误差全部来自软件管线的整数截断：预乘截断（<1，经提亮 ×gain）+ 提亮截断（<1）
                // 在预乘空间合计 <2.08，反预乘放大 255/a 倍，再加反预乘截断与 GPU 端舍入 1.5。
                val tolerance = ceil(2.1f * 255f / alpha + 1.5f).toInt()
                assertTrue("a=$alpha c=$channel sw=$sw gpu=$gpu", abs(sw - gpu) <= tolerance)
            }
        }
    }

    fun gpuLensUsesTheSoftwareLensFunctionAndConstants() {
        val glass = source("$base/material/FrostedChromeGlassApi31.kt")
        // 同一条 C¹ 透镜：中心放大 + 边沿平方外推。
        assertTrue(glass.contains("float magnified = c * (1.0 - centerGain * (1.0 - c * c));"))
        assertTrue(glass.contains("float rim = smoothRim((abs(c) - rimStart) / (1.0 - rimStart));"))
        assertTrue(glass.contains("float pushed = rimPush * rim * rim;"))
        // 动态子输入可能裁到输出区域；即便节点更大，也不能按节点全幅取样。
        assertTrue(glass.contains("content.eval(clamp(source, origin + float2(0.5), origin + size - float2(0.5)))"))
        assertTrue(glass.contains("return f * f * (3.0 - 2.0 * f);"))
        assertTrue(glass.contains(
            "setFloatUniform(\"gain\", LensRefractionPolicy.CENTER_GAIN_X, LensRefractionPolicy.CENTER_GAIN_Y)"))
        assertTrue(glass.contains(
            "setFloatUniform(\"push\", LensRefractionPolicy.RIM_PUSH_X, LensRefractionPolicy.RIM_PUSH_Y)"))
        assertTrue(glass.contains("setFloatUniform(\"rimStart\", LensRefractionPolicy.RIM_START)"))
        // 外沿与软件管线的外沿采样区一致；节点里不垫底图（静态磨砂由表面自己画在下面）。
        assertTrue(glass.contains("LensRefractionPolicy.marginPx(density)"))
        val software = source("$base/material/LensRefractionPolicy.kt")
        assertTrue(software.contains("val magnified = clamped * (1f - centerGain * (1f - clamped * clamped))"))
        assertTrue(software.contains("val push = rimPush * rim * rim"))
    }

    fun nodeGlassReplacesSoftwareSamplingOnlyWhenItActuallyDrew() {
        val renderer = source("$base/material/FrostedMaterialRenderer.kt")
        val live = renderer.after("internal fun drawLiveSample(").before("\n    }\n")
        assertTrue(live.contains("canvas.isHardwareAccelerated && drawChromeGlass("))
        // 接管成功才摘掉软件采样；节点暂不可用时照常登记，栏不会一帧空白。
        assertTrue(live.indexOf("live.unregister(view)") < live.indexOf("live.register(view)"))
        assertTrue(renderer.contains("underlay = null"))
        val set = renderer.after("override fun setSurfaceBackdrop(").before("\n    }\n")
        assertTrue(set.contains("runCatching { FrostedChromeGlassApi31.create(density) }"))
        val close = renderer.after("override fun close() {").before("\n    }\n")
        assertTrue(close.contains("chromeGlass.values.forEach(::closeChromeGlass)"))
        val sampler = source("$base/material/LiveBackdropSampler.kt")
        val unregister = sampler.after("fun unregister(view: View) {").before("\n    }\n")
        // 不 recycle：宿主上一份 display list 可能还引用着纹理。
        assertTrue(unregister.contains("entry.generation++"))
        assertTrue(!unregister.contains("recycle"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FrostedChromeGlassTest) -> Unit) {
            val s = FrostedChromeGlassTest()
            block(s)
        }
        runIsolated { s -> s.lensIlluminationPreservesPremultipliedAlphaAtTransparentEdges() }
        runIsolated { s -> s.colorMatrixIlluminationMatchesThePremultipliedSoftwareStep() }
        runIsolated { s -> s.gpuLensUsesTheSoftwareLensFunctionAndConstants() }
        runIsolated { s -> s.nodeGlassReplacesSoftwareSamplingOnlyWhenItActuallyDrew() }
    }
}
