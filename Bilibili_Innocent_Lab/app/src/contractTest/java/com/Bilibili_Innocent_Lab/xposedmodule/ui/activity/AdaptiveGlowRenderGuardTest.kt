package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 光晕渲染路径的结构护栏。
 *
 * 拦的是三类会**静默退化**的写法（编译照过、界面照动、只有掉帧或跳变）：
 * ① 每帧重建 shader；② 离屏模糊；③ 纯几何层被 android.graphics 污染（JVM 单测随之失效）。
 * 都以源码文本断言——本仓 ModalTitleMotionConventionTest / SettingsDialogExtractionTest 的同款思路。
 */
class AdaptiveGlowRenderGuardTest {

    private fun source(relative: String): String =
        if (relative.startsWith("core/") || relative.startsWith("widget/")) {
            com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource.engine(relative)
        } else {
            com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource.read(relative)
        }

    private val surfaces = listOf(
        "widget/LumenNavigationBar.kt",
        "widget/LumenSegmentScrubBar.kt",
        "ui/interaction/ElasticInteractionController.kt"
    )

    fun geometryLayerStaysFreeOfAndroidGraphics() {
        val policy = source("core/touch/AdaptiveGlowPolicy.kt")
        val imports = policy.lineSequence().filter { it.startsWith("import ") }.toList()
        assertTrue("纯几何层不得 import android.graphics", imports.none { it.contains("android.graphics") })
        assertTrue("纯几何层不得 import Canvas/Paint/Shader",
            imports.none { it.contains(".Canvas") || it.contains(".Paint") || it.contains(".Shader") })
    }

    fun surfacesNeverBuildAShaderOrGoOffscreen() {
        for (path in surfaces) {
            val text = source(path)
            assertTrue("$path 不得自建 RadialGradient（shader 只在 TouchGlowRenderer 构造）",
                !text.contains("RadialGradient("))
            assertTrue("$path 不得离屏模糊", !text.contains("BlurMaskFilter"))
            assertTrue("$path 不得 RenderEffect", !text.contains("RenderEffect"))
            assertTrue("$path 不得 saveLayer", !text.contains("saveLayer"))
        }
    }

    fun rendererBuildsTheShaderExactlyOnceAndOutsideDraw() {
        val renderer = source("core/touch/TouchGlowRenderer.kt")
        assertEquals("shader 只准建一次", 1, Regex("RadialGradient\\(").findAll(renderer).count())
        val buildIndex = renderer.indexOf("RadialGradient(")
        val drawIndex = renderer.indexOf("fun draw(")
        assertTrue("构造点必须早于 draw", buildIndex in 0 until drawIndex)
        assertTrue("draw 内不得重建 shader", !renderer.substring(drawIndex).contains("RadialGradient("))
        // 几何圆必须包住前移后的渐变支持域：否则形变方向会被几何硬切出一道非零 alpha 直边
        assertTrue("draw 必须按 coreOffsetX 扩大几何圆",
            renderer.substring(drawIndex).contains("coreOffsetX"))
        assertTrue("draw 不得再用基准半径作几何",
            !renderer.substring(drawIndex).contains("drawCircle(0f, 0f, radius, paint)"))
    }

    fun allThreeSurfacesShareTheSameGeometry() {
        assertTrue(source("widget/LumenNavigationBar.kt").contains("GlowState"))
        assertTrue(source("widget/LumenSegmentScrubBar.kt").contains("GlowState"))
        assertTrue(source("ui/interaction/ElasticInteractionController.kt").contains("GlowState"))
        // 底栏与 scrub 条必须共用同一个渲染器，而不是各自画
        assertTrue(source("widget/LumenNavigationBar.kt").contains("TouchGlowRenderer"))
        assertTrue(source("widget/LumenSegmentScrubBar.kt").contains("TouchGlowRenderer"))
        assertTrue(source("ui/interaction/ElasticInteractionController.kt").contains("TouchGlowRenderer"))
    }

    /**
     * 触点坐标必须换算到当前系（减去视图自身平移）再喂 GlowFrame：downLocal + 原始 delta
     * 是按下时刻坐标系，而 bounds/SDF/pileRoomPx 都在当前系——不换算会把视图平移同时
     * 计入越界量（+|t|）与可触达空间（-|t|），堆积强度被弹簧振荡调制出跳变
     * （用户 2026-09-21 抓帧实证）。三处宿主同一修正。
     */
    fun touchPositionIsConvertedToTheCurrentFrame() {
        val controller = source("ui/interaction/ElasticInteractionController.kt")
        assertTrue("TouchHighlight 触点必须减去视图平移",
            controller.contains("val touchX = centerX - viewShiftX"))
        val nav = source("widget/LumenNavigationBar.kt")
        assertTrue("底栏 GlowView 触点必须减去视图平移",
            nav.contains("val touchX = centerX - viewShiftX"))
        assertTrue("底栏必须把应用平移增量传给 GlowView",
            nav.contains("viewShiftX = x - initialOffsetX"))
        val scrub = source("widget/LumenSegmentScrubBar.kt")
        assertTrue("scrub 条 GlowView 触点必须减去视图平移",
            scrub.contains("val touchX = centerX - viewShiftX"))
        assertTrue("scrub 条必须把应用平移增量传给 GlowView",
            scrub.contains("viewShiftX = translationX - initialOffsetX"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AdaptiveGlowRenderGuardTest) -> Unit) {
            val s = AdaptiveGlowRenderGuardTest()
            block(s)
        }
        runIsolated { s -> s.geometryLayerStaysFreeOfAndroidGraphics() }
        runIsolated { s -> s.surfacesNeverBuildAShaderOrGoOffscreen() }
        runIsolated { s -> s.rendererBuildsTheShaderExactlyOnceAndOutsideDraw() }
        runIsolated { s -> s.allThreeSurfacesShareTheSameGeometry() }
        runIsolated { s -> s.touchPositionIsConvertedToTheCurrentFrame() }
    }
}
