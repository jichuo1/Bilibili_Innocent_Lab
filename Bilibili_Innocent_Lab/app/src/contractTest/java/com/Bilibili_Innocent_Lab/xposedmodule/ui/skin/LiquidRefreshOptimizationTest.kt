package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidControlGradientCache
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidRefreshVisibilityPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidSurfaceRefreshState
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after

class LiquidRefreshOptimizationTest {
    @Test fun `horizontal thumb travel does not rebuild the vertical gradient`() {
        val cache = LiquidControlGradientCache()
        var rebuilds = 0
        // 10,000 different horizontal positions have exactly the same vertical/color inputs.
        repeat(10_000) { if (cache.update(1f, 19f, 0x12345678, 0x23456789)) rebuilds++ }
        assertEquals(1, rebuilds)
    }

    @Test fun `vertical bounds and both colors invalidate independently`() {
        val cache = LiquidControlGradientCache()
        assertTrue(cache.update(1f, 19f, 1, 2))
        assertFalse(cache.update(1f, 19f, 1, 2))
        assertTrue(cache.update(2f, 19f, 1, 2))
        assertTrue(cache.update(2f, 20f, 1, 2))
        assertTrue(cache.update(2f, 20f, 3, 2))
        assertTrue(cache.update(2f, 20f, 3, 4))
        assertFalse(cache.update(2f, 20f, 3, 4))
        assertTrue(LiquidControlGradientCache().update(2f, 20f, 3, 4))
    }

    private fun visible(left: Float, top: Float, right: Float, bottom: Float, margin: Float = 10f) =
        LiquidRefreshVisibilityPolicy.intersectsWindow(left, top, right, bottom, 0f, 0f, 100f, 100f, margin)

    @Test fun `definitely off-window surfaces skip on all four edges`() {
        assertFalse(visible(-30f, 20f, -11f, 40f))
        assertFalse(visible(111f, 20f, 140f, 40f))
        assertFalse(visible(20f, -30f, 40f, -11f))
        assertFalse(visible(20f, 111f, 40f, 140f))
        assertTrue(visible(20f, 20f, 40f, 40f))
    }

    @Test fun `partial visibility and exact optical margin remain refreshable`() {
        assertTrue(visible(-30f, 20f, -10f, 40f))
        assertTrue(visible(110f, 20f, 140f, 40f))
        assertTrue(visible(20f, -30f, 40f, -10f))
        assertTrue(visible(20f, 110f, 40f, 140f))
        assertTrue(visible(-30f, 20f, 1f, 40f, 0f))
        assertTrue(visible(99f, 20f, 140f, 40f, 0f))
    }

    @Test fun `unknown or invalid geometry fails open`() {
        assertTrue(visible(Float.NaN, 0f, 10f, 10f))
        assertTrue(visible(0f, 0f, Float.POSITIVE_INFINITY, 10f))
        assertTrue(visible(0f, 0f, 0f, 10f))
        assertTrue(visible(0f, 10f, 10f, 0f))
        assertTrue(visible(500f, 500f, 510f, 510f, -1f))
        assertTrue(LiquidRefreshVisibilityPolicy.intersectsWindow(500f, 500f, 510f, 510f,
            0f, 0f, 0f, 0f, 0f))
    }

    @Test fun `window offsets do not assume the activity origin`() {
        assertTrue(LiquidRefreshVisibilityPolicy.intersectsWindow(220f, 320f, 250f, 350f,
            200f, 300f, 400f, 500f, 10f))
        assertFalse(LiquidRefreshVisibilityPolicy.intersectsWindow(20f, 20f, 50f, 50f,
            200f, 300f, 400f, 500f, 10f))
    }

    @Test fun `reentry refreshes even when returning to the exact last recorded origin`() {
        val state = LiquidSurfaceRefreshState()
        assertFalse(state.shouldRefresh(true, false, false))
        repeat(120) { assertFalse(state.shouldRefresh(false, true, true)) }
        assertTrue(state.shouldRefresh(true, false, false))
        assertFalse(state.shouldRefresh(true, false, false))
        assertTrue(state.shouldRefresh(true, true, false))
        assertTrue(state.shouldRefresh(true, false, true))
    }

    @Test fun `off-window content changes are not lost and state is per surface`() {
        val first = LiquidSurfaceRefreshState()
        val second = LiquidSurfaceRefreshState()
        assertFalse(first.shouldRefresh(false, false, true))
        assertFalse(second.shouldRefresh(true, false, false))
        assertTrue(first.shouldRefresh(true, false, false))
    }

    private fun source(file: String): String {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/skin/liquid/$file.kt"
        return SourceContract.read(path)
    }

    @Test fun `drawable bounds path gates shader creation and does not allocate switch ticks`() {
        val drawable = source("LiquidChoiceDrawable")
        assertTrue(drawable.contains("private val tick = if (checkbox) Path() else null"))
        assertTrue(drawable.contains("private val mark = if (checkbox) Paint"))
        val update = drawable.after("private fun updatePaints()").before("override fun draw")
        assertTrue(update.indexOf("if (gradientCache.update(") in 0 until update.indexOf("LinearGradient("))
        assertTrue(update.contains("gradientCache.update(rect.top, bottom, startColor, endColor)"))
    }

    @Test fun `culling retains scroll hooks transforms stretch and capture-time mask ordering`() {
        val renderer = source("LiquidActivityRenderer")
        val visibility = renderer.after("private fun isSurfacePotentiallyVisible").before("private fun configureRealtimeRefreshRate")
        assertTrue(visibility.contains("stretchOpticalIntensity > 1f"))
        assertTrue(visibility.contains("!ancestor.matrix.isIdentity"))
        assertTrue(visibility.contains("LiquidRefreshVisibilityPolicy.isTranslationOnly(visibilityMatrix)"))
        assertTrue(visibility.contains("ancestor.animation != null"))
        assertTrue(visibility.contains("ancestor is LiquidMotionSurfaceFrameProvider"))
        assertTrue(visibility.contains("val windowRoot = view.rootView"))
        assertTrue(visibility.contains("parameters.effectPaddingDp * density"))
        assertTrue(renderer.contains("addOnScrollChangedListener(scrollListener)"))
        val capture = renderer.after("private fun requestRealtimeCapture(").before("private fun handleRealtimeCaptureResult")
        assertTrue(capture.indexOf("feedback.buildSuppressionMask(") in 0 until capture.indexOf("PixelCopy.request("))
        val refresh = renderer.after("private fun invalidateMovedSurfaces").before("private fun isSurfacePotentiallyVisible")
        assertEquals(1, Regex("refreshWindowRoot = null").findAll(refresh).count())
        assertEquals(1, Regex("refreshState.shouldRefresh").findAll(refresh).count())
        assertTrue(refresh.contains("OnPreDrawListener"))
        assertTrue(refresh.contains("if (changes == 0) return"))
    }

    /**
     * 实时采集静止门控的接线（2026-09-23）。静止空转既让 GPU 持续 30% 占空，又把"采集管线节奏"
     * 喂给吞吐统计，误判跟不上而把窗口压到 60Hz。
     */
    @Test fun `realtime capture idles on identical frames without breaking triple buffering`() {
        val renderer = source("LiquidActivityRenderer")
        val result = renderer.after("private fun handleRealtimeCaptureResult(")
            .before("private fun applyCaptureThroughputSample(")
        val unchanged = result.after("if (unchanged) {").before("identicalCaptureStreak = 0")
        assertTrue("相同截图不得绑定、不得失效表面", !unchanged.contains("bindPreparedBackendsToBackdrop") &&
            !unchanged.contains("invalidateRegisteredSurfaces"))
        assertTrue("轮转必须退回这块未绑定的缓冲，下一次探测不能写到被显示列表引用的那块",
            unchanged.contains("realtimeCaptureNextIndex = realtimeCaptureSources.indexOf(captureSource)"))
        assertTrue("相同截图必须重置吞吐统计，否则空转节奏会被误判为跟不上而降到 60Hz",
            unchanged.contains("refreshRate.resetThroughput()"))
        // 2026-09-23 逐像素比较移到截图线程，基准在发起时冻结；提交时基准必须仍是绑定源。
        val postProcess = renderer.after("private fun postProcessRealtimeCapture(").before("\n}")
        assertTrue("逐像素比较在 PixelCopy 回调里",
            postProcess.contains("realtimeCaptureMatches(request.source.bitmap, baseline.bitmap)"))
        val compare = renderer.after("private fun realtimeCaptureMatches(").before("\n}")
        assertTrue("主线程回退跳过逐像素比较，异常只能当作有变化",
            compare.indexOf("if (Looper.myLooper() == Looper.getMainLooper()) return false") in
                0 until compare.indexOf("runCatching { source.sameAs(baseline) }.getOrDefault(false)"))
        assertTrue("基准换了就不能采信后台比较结论", result.contains("bound === request.baseline"))
        val frame = renderer.after("private fun onRealtimeFrame(").before("private fun requestRealtimeCapture(")
        assertTrue("静止期不得继续逐帧回调", frame.contains("|| realtimeIdle"))
        assertTrue("窗口任何绘制都必须唤醒采集", renderer.contains("addOnDrawListener(realtimeDrawListener)"))
        assertTrue("静止判定只和真正绑定给后端的底图比", renderer.contains("lastBoundBackdrop === bound"))
    }
    /**
     * 实时截图后处理移出 UI 线程（2026-09-23 动画性能第二批）。反馈抑制（约 1,000,000 px 的软件
     * 路径填充）与整图 `sameAs` 原来在 PixelCopy 的主线程回调里；现在回调投到截图线程，主线程只做
     * 验票与提交。单飞必须覆盖"截图 → 后处理 → 提交"全程，否则下一次请求会在后台还在读时改写遮罩。
     */
    @Test fun `realtime capture post-processing runs off the ui thread under one flight`() {
        val renderer = source("LiquidActivityRenderer")
        val request = renderer.after("private fun requestRealtimeCapture(")
            .before("private fun handleRealtimeCaptureResult(")
        assertTrue("PixelCopy 优先使用截图线程并保留主线程回退", request.contains("callbackHandler\n") &&
            request.contains("val callbackHandler = captureWorker() ?: mainHandler"))
        assertTrue("后处理在回调线程执行、之后才回主线程提交",
            request.indexOf("postProcessRealtimeCapture(suppressor, request, result)") in
                0 until request.indexOf("mainHandler.post { recipient.get()?.handleRealtimeCaptureResult(request, result) }"))
        assertTrue("回调不得强引用渲染器", !request.after("OnPixelCopyFinishedListener").before("val requested")
            .contains("feedback."))
        assertTrue("基准在发起时冻结", request.indexOf("val baseline = realtimeBackdropSource") in
            0 until request.indexOf("LiquidCaptureRequest(ticket"))

        val postProcess = renderer.after("@AnyThread\nprivate fun postProcessRealtimeCapture(").before("\n}")
        assertTrue(postProcess.contains("sanitizeRealtimeCapture"))
        assertTrue("失败结果不得做逐像素比较", postProcess.indexOf("outcome == LiquidCaptureOutcome.FAILED") in
            0 until postProcess.indexOf("realtimeCaptureMatches("))

        val result = renderer.after("private fun handleRealtimeCaptureResult(")
            .before("private fun applyCaptureThroughputSample(")
        assertFalse("主线程提交不得再做抑制或逐像素比较",
            result.contains("sanitizeRealtimeCapture") || result.contains("sameAs("))
        assertTrue("单飞只在主线程提交时结束", result.indexOf("realtimeCaptureInFlight = null") in
            0 until result.indexOf("request.outcome"))
        assertEquals("在飞标记只能由提交清除", 1, Regex("realtimeCaptureInFlight = null").findAll(renderer).count())

        // 抑制器的截图侧状态只在截图线程上改：主线程的释放必须投递过去，关闭时排在在飞后处理之后。
        assertFalse(renderer.contains("feedback.releaseSuppressionUnderlay()"))
        assertTrue(renderer.contains("onCaptureWorker(feedback::releaseSuppressionUnderlay)"))
        val close = renderer.after("override fun close()")
        assertTrue(close.indexOf("onCaptureWorker(feedback::close)") in 0 until close.indexOf("captureThread?.quitSafely()"))
        val suppressor = source("LiquidFeedbackSuppressor")
        assertFalse("抑制器不能再整体标成主线程类",
            Regex("@MainThread\\s+internal class").containsMatchIn(suppressor))
        assertTrue(suppressor.before("fun sanitizeRealtimeCapture(").trimEnd().endsWith("@AnyThread"))
        assertTrue(suppressor.before("fun buildSuppressionMask(").trimEnd().endsWith("@MainThread"))
    }
}
