package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after

/**
 * 2026-09-23 悬浮栏可读性改造 A 期的装配约束：层级边界、生命周期与底图同源。
 */
class GlowFloatingChromeWiringTest {
    private fun source(relative: String): String =
        SourceContract.read(relative).replace("\r\n", "\n")

    private val base = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui"

    @Test fun contentContainerHoldsOnlyThePagerAndSitsBelowBothBars() {
        val home = source("$base/activity/SettingsHomePresenter.kt")
        // 探针录的是"栏下方有什么"：栏必须是内容容器的兄弟，不能被录进去。
        val target = home.indexOf("backdropTarget.addView(pager, FrameLayout.LayoutParams(-1, -1))")
        val layer = home.indexOf("addView(backdropTarget, FrameLayout.LayoutParams(-1, -1))")
        val dock = home.indexOf("pageLayer.addView(dock")
        val header = home.indexOf("pageLayer.addView(header")
        assertTrue(target in 0 until layer)
        assertTrue(layer < dock && dock < header)
        assertFalse(home.contains("backdropTarget.addView(dock"))
        assertFalse(home.contains("backdropTarget.addView(header"))
        // 柔光透镜仍以 pager 为内容源，不受容器替换影响。
        assertTrue(home.contains("skinContentSource(pager)"))
    }

    @Test fun chromeFollowsPagerMotionAndIsDisposedWithThePresenter() {
        val home = source("$base/activity/SettingsHomePresenter.kt")
        val position = home.after("pager.onPositionChanged = {").before("}")
        assertTrue(position.contains("floatingChrome?.onContentMoved()"))
        val dispose = home.after("fun dispose() {")
        assertTrue(dispose.contains("floatingChrome?.dispose()"))
        // 引擎每次现取：Liquid 失败回落后必须换成柔光，不能缓存旧引擎。
        assertTrue(home.contains("GlowFloatingChrome(backdropTarget, { activity.glowEngine }, ::edgeCoverage)"))
        assertTrue(home.contains("chrome.attach(dock, GlowScrollEdge.BOTTOM"))
        assertTrue(home.contains("header, GlowScrollEdge.TOP"))
    }

    @Test fun chromeRemovesEveryObserverItAdds() {
        val chrome = source("$base/skin/engine/GlowFloatingChrome.kt")
        for (kind in listOf("ScrollChangedListener", "GlobalLayoutListener", "PreDrawListener")) {
            assertEquals(kind, 1, Regex("addOn$kind\\(").findAll(chrome).count())
            assertEquals(kind, 1, Regex("removeOn$kind\\(").findAll(chrome).count())
        }
        val dispose = chrome.after("fun dispose() {").before("\n    }\n")
        assertTrue(dispose.contains("probe.close()"))
        assertTrue(dispose.contains("setSurfaceLegibility(surface.host, null)"))
        assertTrue(dispose.contains("removeCallbacks(probeRunnable)"))
    }

    @Test fun dissolveDrawsTheVisibleRootBitmapNotTheOpticalCopy() {
        val source = source("$base/skin/liquid/LiquidBackdropSource.kt")
        val presentation = source.after("fun drawPresentationRegion(").before("\n    }\n")
        // 溶解区必须与根背景逐像素一致：根背景画的是 bitmap（含颗粒），不是光学副本。
        assertTrue(source.contains("BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)"))
        assertFalse(presentation.contains("opticalBitmap"))
        assertFalse(presentation.contains("bitmapShader.setLocalMatrix"))
        assertFalse(presentation.contains("maskShader"))
        assertTrue(presentation.contains("PorterDuff.Mode.DST_IN"))
    }

    @Test fun everyBackdropReplacementAdvancesTheGeneration() {
        val renderer = source("$base/skin/liquid/LiquidActivityRenderer.kt")
        val assignments = Regex("\\bbackdropSource = (created|source)\\b").findAll(renderer).count()
        assertEquals(2, assignments)
        // 两次换源 + 同尺寸位图的窗口映射更新（updateFullSize）。
        assertEquals(3, Regex("backdropGeneration\\+\\+").findAll(renderer).count())
        assertTrue(renderer.after("existing.updateFullSize(width, height)").trimStart()
            .startsWith("backdropGeneration++"))
    }

    @Test fun softwareCanvasDrawsNeverRefreshTheRecordedFootprint() {
        // 探针在软件画布上录内容：若表面也登记，footprint 的"上次录制位置"被刷新却没有重录，
        // 滚动后该表面不再失效，玻璃里的背景停在旧位置（Phase A review 发现）。
        val drawables = source("$base/skin/liquid/LiquidSurfaceDrawables.kt")
        assertTrue(drawables.contains("if (canvas.isHardwareAccelerated) {\n" +
            "                renderer.registerSurfaceView("))
        assertEquals(1, Regex("registerSurfaceView\\(").findAll(drawables).count())
    }

    @Test fun legibilityOnlyThickensFloatingSurfaces() {
        val renderer = source("$base/skin/liquid/LiquidActivityRenderer.kt")
        assertTrue(renderer.contains(
            "legibility = if (role == SurfaceRole.FLOATING && host != null) surfaceLegibility[host] else null"))
        assertTrue(renderer.contains("LiquidLegibilityTuning.tintAlpha(baseFraction, legibility.boost)"))
        // 光学参数与绘制同一条映射，策略算出的补偿与实际加厚一致。
        assertTrue(renderer.contains("maxTintAlpha = LiquidLegibilityTuning.ceiling(base)"))
    }

    /** 顶栏胶囊本体没有文字：补偿只加在圆按钮上，本体保持清透；底栏的标签压在玻璃上，照旧加厚。 */
    @Test fun topCapsuleBodyStaysClearWhileItsButtonsCarryCompensation() {
        val chrome = SourceContract.read("ui/skin/engine/GlowFloatingChrome.kt")
        assertTrue(chrome.contains("glow?.setSurfaceLegibility(surface.host, value.takeIf { surface.thickenHost })"))
        assertTrue(chrome.contains("surface.companions.forEach { glow?.setSurfaceLegibility(it, value) }"))
        val home = SourceContract.read("ui/activity/SettingsHomePresenter.kt")
        assertTrue(home.contains("companions = icons, thickenHost = false"))
        assertTrue(home.contains("chrome.attach(dock, GlowScrollEdge.BOTTOM, activity.getColor(R.color.colorTextGray)) { boost ->"))
    }
}
