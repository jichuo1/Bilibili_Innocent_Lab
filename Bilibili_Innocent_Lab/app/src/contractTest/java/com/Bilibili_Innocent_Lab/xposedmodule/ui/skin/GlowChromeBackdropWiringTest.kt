package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before

/**
 * 2026-09-23 悬浮栏可读性改造 B 期：内容节点玻璃的装配约束。
 * RenderNode/RenderEffect 在 JVM 上不可用，这里约束源码里不能退化的几条边界。
 */
class GlowChromeBackdropWiringTest {
    private fun source(relative: String): String =
        SourceContract.read(relative).replace("\r\n", "\n")

    private val base = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/skin"

    fun contentIsRecordedOnceAndDrawnOnceOnlyOnHardwareCanvases() {
        val target = source("$base/engine/GlowBackdropTarget.kt")
        val draw = target.after("override fun dispatchDraw(canvas: Canvas) {").before("\n    }\n")
        // 软件画布（探针、截图）照常画，不碰内容节点。
        assertTrue(draw.contains("!canvas.isHardwareAccelerated"))
        assertTrue(draw.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.S"))
        // 录制失败也必须结束录制；录完只把节点画一次。
        assertTrue(draw.contains("super.dispatchDraw(recording)\n        } finally {\n            active.end()"))
        assertTrue(draw.contains("active.drawInto(canvas)"))
        // 节点不能比原来的 clipChildren=false 更严。
        assertTrue(target.contains("RenderNode(\"BIL-GlowContent\").apply { setClipToBounds(false) }"))
    }

    fun chromeGlassBlursBeforeRefractingAndSamplesInNodeSpace() {
        val chrome = source("$base/liquid/LiquidChromeBackdropApi31.kt")
        assertTrue(chrome.contains(
            "RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, \"content\"), blur)"))
        assertTrue(chrome.contains("shader.setFloatUniform(\"offset\", -padding.toFloat(), -padding.toFloat())"))
        assertTrue(chrome.contains("shader.setFloatUniform(\"backdropOrigin\", padding.toFloat(), padding.toFloat())"))
        // 外沿要覆盖折射 + 散射的最大取样距离，否则 rim 被越界收敛压平。
        assertTrue(chrome.contains("(parameters.refractionAmountDp + parameters.scatteringRadiusDp) * MAX_OPTICAL_INTENSITY"))
        // 与窗口玻璃同一段 AGSL、同一份光学 uniform。
        assertTrue(chrome.contains("RuntimeShader(ROUNDED_RECT_REFRACTION_SHADER)"))
        assertTrue(chrome.contains("applyLiquidOpticalUniforms(parameters, density)"))
        val backend = source("$base/liquid/LiquidRefractionBackendApi33.kt")
        assertTrue(backend.contains("shader.applyLiquidOpticalUniforms(parameters, density)"))
    }

    fun nodeFailuresStayLocalAndFallBackToWindowGlass() {
        val renderer = source("$base/liquid/LiquidActivityRenderer.kt")
        // 只接管主窗口里的悬浮栏；弹窗表面仍走稳定底图的光学副本。
        assertTrue(renderer.contains(
            "val chrome = if (role == SurfaceRole.FLOATING && host != null && !foreignWindow) chromeBackdrops[host] else null"))
        assertTrue(renderer.contains("val drewChrome = chrome != null && host != null &&\n" +
            "                        drawChromeBackdrop(chrome, canvas, bounds, effectiveRadiusPx, alpha, host)\n" +
            "                    if (!drewChrome) {"))
        val chromeDraw = renderer.after("private fun drawChromeBackdrop(").before("\n    }\n")
        // 自带 runCatching：drawWithFallback 不能把它算成主后端失败。
        assertTrue(chromeDraw.contains("return runCatching {"))
        assertTrue(chromeDraw.contains("chromeBackdropBroken = true"))
        assertTrue(renderer.contains("!chromeBackdropBroken && hardwareAccelerated"))
        // 清透档节点玻璃只轻微模糊，可读性策略仍按残留细节估计漏字。
        assertTrue(renderer.contains("chromeBackdropActive -> CHROME_DETAIL_SEE_THROUGH"))
    }

    fun chromeUnregistersBarsBeforeStoppingTheContentRecording() {
        val chrome = source("$base/engine/GlowFloatingChrome.kt")
        val sync = chrome.after("private fun syncBackdrops(").before("\n    }\n")
        val disabled = sync.after("} else {")
        assertTrue(disabled.indexOf("setSurfaceBackdrop(surface.host, null)") <
            disabled.indexOf("target.contentCaptureEnabled = false"))
        val enabled = sync.before("} else {")
        assertTrue(enabled.indexOf("target.contentCaptureEnabled = true") <
            enabled.indexOf("setSurfaceBackdrop(surface.host, target)"))
        val dispose = chrome.after("fun dispose() {").before("\n    }\n")
        assertTrue(dispose.indexOf("setSurfaceBackdrop(surface.host, null)") <
            dispose.indexOf("target.contentCaptureEnabled = false"))
        assertFalse(chrome.contains("setSurfaceBackdrop(it, target)"))
        // 按钮节点只给声明支持完整相对矩阵的引擎；Liquid 不额外增加三个效果层。
        assertTrue(enabled.contains("supportsDescendantSurfaceBackdrop"))
        assertTrue(enabled.contains("setSurfaceBackdrop(companion, companionTarget)"))
        assertTrue(disabled.contains("setSurfaceBackdrop(companion, null)"))
        assertTrue(dispose.contains("setSurfaceBackdrop(companion, null)"))
        assertTrue(chrome.contains("if (engineChanged) clearBackdrops(lastEngine)"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (GlowChromeBackdropWiringTest) -> Unit) {
            val s = GlowChromeBackdropWiringTest()
            block(s)
        }
        runIsolated { s -> s.contentIsRecordedOnceAndDrawnOnceOnlyOnHardwareCanvases() }
        runIsolated { s -> s.chromeGlassBlursBeforeRefractingAndSamplesInNodeSpace() }
        runIsolated { s -> s.nodeFailuresStayLocalAndFallBackToWindowGlass() }
        runIsolated { s -> s.chromeUnregistersBarsBeforeStoppingTheContentRecording() }
    }
}
