package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidControlGradientCache
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidRefreshVisibilityPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidSurfaceRefreshState
import java.io.File
import org.junit.Assert.*
import org.junit.Test

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
        return sequenceOf(File(path), File("app/$path")).first(File::isFile).readText()
    }

    @Test fun `drawable bounds path gates shader creation and does not allocate switch ticks`() {
        val drawable = source("LiquidChoiceDrawable")
        assertTrue(drawable.contains("private val tick = if (checkbox) Path() else null"))
        assertTrue(drawable.contains("private val mark = if (checkbox) Paint"))
        val update = drawable.substringAfter("private fun updatePaints()").substringBefore("override fun draw")
        assertTrue(update.indexOf("if (gradientCache.update(") in 0 until update.indexOf("LinearGradient("))
        assertTrue(update.contains("gradientCache.update(rect.top, bottom, startColor, endColor)"))
    }

    @Test fun `culling retains scroll hooks transforms stretch and capture-time mask ordering`() {
        val renderer = source("LiquidActivityRenderer")
        val visibility = renderer.substringAfter("private fun isSurfacePotentiallyVisible").substringBefore("private fun configureRealtimeRefreshRate")
        assertTrue(visibility.contains("stretchOpticalIntensity > 1f"))
        assertTrue(visibility.contains("!ancestor.matrix.isIdentity"))
        assertTrue(visibility.contains("ancestor.animation != null"))
        assertTrue(visibility.contains("ancestor is LiquidMotionSurfaceFrameProvider"))
        assertTrue(visibility.contains("val windowRoot = view.rootView"))
        assertTrue(visibility.contains("parameters.effectPaddingDp * density"))
        assertTrue(renderer.contains("addOnScrollChangedListener(scrollListener)"))
        val capture = renderer.substringAfter("private fun requestRealtimeCapture(").substringBefore("private fun handleRealtimeCaptureResult")
        assertTrue(capture.indexOf("buildSuppressionMask(root, captureSource)") in 0 until capture.indexOf("PixelCopy.request("))
        val refresh = renderer.substringAfter("private fun invalidateMovedSurfaces").substringBefore("private fun isSurfacePotentiallyVisible")
        assertEquals(2, Regex("refreshWindowRoot = null").findAll(refresh).count())
        assertEquals(2, Regex("refreshState.shouldRefresh").findAll(refresh).count())
    }
}
