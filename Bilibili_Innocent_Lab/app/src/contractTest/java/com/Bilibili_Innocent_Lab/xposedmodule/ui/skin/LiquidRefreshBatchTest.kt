package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidRefreshBatch
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidRefreshVisibilityPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before

class LiquidRefreshBatchTest {
    fun preDrawReadsFinalTransformsOnceAndMergesNewContentInTheSameWindow() {
        val batch = LiquidRefreshBatch()
        var position = 0
        var scans = 0
        position = 10; batch.mark(false)
        position = 35; batch.mark(false)
        batch.mark(true)
        position = 80; batch.mark(false)
        val flags = batch.take()
        if (flags != 0) scans++
        assertEquals(80, position)
        assertEquals(LiquidRefreshBatch.POSITION or LiquidRefreshBatch.CONTENT, flags)
        assertEquals(1, scans)
        repeat(120) { assertEquals("Idle pre-draw never starts more work", 0, batch.take()) }
    }

    fun dialogAndActivityPendingRefreshesAreIndependent() {
        val activity = LiquidRefreshBatch(); val dialog = LiquidRefreshBatch()
        activity.mark(false); dialog.mark(true)
        assertEquals(LiquidRefreshBatch.CONTENT, dialog.take())
        assertEquals(LiquidRefreshBatch.POSITION, activity.take())
        dialog.mark(false)
        assertEquals(0, activity.take())
        assertEquals(LiquidRefreshBatch.POSITION, dialog.take())
    }

    fun onlyFinitePureTranslationMayUseTheUnscaledScreenAabb() {
        fun matrix() = floatArrayOf(1f, 0f, 123.25f, 0f, 1f, -89.5f, 0f, 0f, 1f)
        assertTrue(LiquidRefreshVisibilityPolicy.isTranslationOnly(matrix()))
        for ((index, value) in listOf(0 to .98f, 1 to .2f, 3 to -.2f, 4 to 1.1f, 6 to .01f, 7 to .01f, 8 to .9f, 2 to Float.NaN)) {
            val transformed = matrix().also { it[index] = value }
            assertFalse(LiquidRefreshVisibilityPolicy.isTranslationOnly(transformed))
        }
        // Only window rejection is used: no assumption that an ancestor clips its children/padding.
        assertFalse(LiquidRefreshVisibilityPolicy.intersectsWindow(10f, 300f, 90f, 360f, 0f, 0f, 100f, 200f, 34f))
        assertTrue(LiquidRefreshVisibilityPolicy.intersectsWindow(10f, 210f, 90f, 230f, 0f, 0f, 100f, 200f, 34f))
    }
    /**
     * 实时截图换代只刷新读截图的表面（2026-09-23 动画性能第二批）。内容节点玻璃栏的输入是
     * 内容节点 + 稳定底图，截图换一张不改变它的像素；其余内容变化（底图、后端、回弹强度）
     * 仍然刷新所有表面。
     */
    fun captureOnlyChangesSkipNodeBackedChromeButNothingElse() {
        val batch = LiquidRefreshBatch()
        assertTrue(batch.mark(contentChanged = true, captureOnly = true))
        assertFalse("同一批次重复标记不再调度", batch.mark(contentChanged = true, captureOnly = true))
        val capture = batch.take()
        assertEquals(LiquidRefreshBatch.CAPTURE, capture)
        assertTrue(LiquidRefreshBatch.anyContent(capture))
        assertTrue("读截图的表面照常刷新", LiquidRefreshBatch.surfaceContentChanged(capture, captureIndependent = false))
        assertFalse("节点玻璃栏跳过", LiquidRefreshBatch.surfaceContentChanged(capture, captureIndependent = true))

        // 同一批次里混入真正的内容变化：节点玻璃栏也必须刷新。
        batch.mark(contentChanged = true, captureOnly = true); batch.mark(contentChanged = true)
        val mixed = batch.take()
        assertTrue(LiquidRefreshBatch.surfaceContentChanged(mixed, captureIndependent = true))

        // 纯位移不是内容变化；captureOnly 对位移标记无效。
        batch.mark(contentChanged = false, captureOnly = true)
        val position = batch.take()
        assertEquals(LiquidRefreshBatch.POSITION, position)
        assertFalse(LiquidRefreshBatch.anyContent(position))
        assertFalse(LiquidRefreshBatch.surfaceContentChanged(position, captureIndependent = false))
    }

    fun onlyTheCaptureCommitIsCaptureOnlyAndNodeUseIsTrackedPerDraw() {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/skin/liquid/LiquidActivityRenderer.kt"
        val renderer = SourceContract.read(path)
        // 定义一处 + 调用一处：只有截图提交走 captureOnly。
        assertEquals(2, renderer.split("invalidateRealtimeCaptureConsumers()").size - 1)
        assertEquals(1, renderer.split("captureOnly = true").size - 1)
        val commit = renderer.after("private fun handleRealtimeCaptureResult(")
            .before("private fun applyCaptureThroughputSample(")
        assertTrue(commit.contains("invalidateRealtimeCaptureConsumers()"))
        assertFalse(commit.contains("invalidateRegisteredSurfaces()"))
        // 每次绘制先复位、节点玻璃画成功才置位：退回窗口玻璃的那一帧必须重新跟随截图。
        val draw = renderer.after("internal fun drawSurface(").before("private fun drawSurfaceLayers(")
        assertTrue(draw.indexOf("chrome?.drewByNode = false") in 0 until draw.indexOf("drawWithFallback {"))
        assertTrue(draw.contains("chrome?.drewByNode = drewChrome"))
        val flush = renderer.after("private fun flushSurfaceRefresh(").before("private fun isSurfacePotentiallyVisible(")
        assertTrue(flush.contains("LiquidRefreshBatch.surfaceContentChanged("))
        assertTrue(flush.contains("captureIndependent = isCaptureIndependent(view)"))
        // 截图换代仍算内容变化：不能因此误判为纯位移而进入滚动抑制。
        assertTrue(flush.contains("val contentChanged = LiquidRefreshBatch.anyContent(changes)"))
        assertTrue(flush.contains("if (surfaceMoved && !contentChanged) suppressRealtimeSamplingWhileScrolling()"))
        val trigger = renderer.after("private fun triggerSurfaceFrame(").before("private fun flushSurfaceRefresh(")
        assertTrue(trigger.contains("if (captureOnly && isCaptureIndependent(view)) continue"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (LiquidRefreshBatchTest) -> Unit) {
            val s = LiquidRefreshBatchTest()
            block(s)
        }
        runIsolated { s -> s.preDrawReadsFinalTransformsOnceAndMergesNewContentInTheSameWindow() }
        runIsolated { s -> s.dialogAndActivityPendingRefreshesAreIndependent() }
        runIsolated { s -> s.onlyFinitePureTranslationMayUseTheUnscaledScreenAabb() }
        runIsolated { s -> s.captureOnlyChangesSkipNodeBackedChromeButNothingElse() }
        runIsolated { s -> s.onlyTheCaptureCommitIsCaptureOnlyAndNodeUseIsTrackedPerDraw() }
    }
}
