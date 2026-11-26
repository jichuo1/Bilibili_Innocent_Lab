package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrostedCustomBackgroundContractTest {
    fun ordinarySessionPassesApplicationContextAndWorkerUsesTheExistingAssetDecoder() {
        val session = LceMotionSource.engine("core/runtime/ActivitySkinSession.kt")
        assertTrue(session.contains("backgroundContext = activity.applicationContext"))
        val renderer = LceMotionSource.engine("core/material/FrostedMaterialRenderer.kt")
        val request = renderer.after("private fun requestBackdrop").before("private fun acceptBackdrop")
        val worker = request.after("work = worker.submit")
        assertTrue(worker.contains("LiquidBackgroundStore.decodeBackdrop(context, config"))
        assertFalse(worker.contains("SkinRepository"))
        val factory = renderer.after("private object ModernBackdropFactory").before("private class ModernSurfaceDrawable")
        assertTrue(factory.contains("ModernMaterialPolicy.sampleSize(width, height)"))
        assertTrue(factory.indexOf("customBackground?.invoke(w, h)") >= 0)
        assertTrue(factory.indexOf("customBackground?.invoke(w, h)") < factory.indexOf("AmbientBackdropScene.paint"))
        assertFalse(renderer.contains("PixelCopy"))
    }

    fun foregroundMemoryReleaseKeepsTheDisplayUnderlayUntilStopOrClose() {
        val renderer = LceMotionSource.engine("core/material/FrostedMaterialRenderer.kt")
        val root = renderer.after("fun bindRoot(view: View)").before("override fun surface")
        assertTrue(root.contains("val current = rootBackdrop"))
        val release = renderer.after("fun releaseMemory()").before("fun stop()")
        assertTrue(release.contains("if (!lifecycle.canWork) {\n            rootBackdrop = null"))
        assertTrue(release.contains("frame = null; sampleShader = null; samplePaint.shader = null"))
    }

    fun customImageSummaryDoesNotRequireAdvancedMaterial() {
        val presenter = SourceContract.read("ui/activity/SkinSummaryPresenter.kt")
        val background = presenter.after("fun MainActivity.currentLiquidBackgroundSummary()").before("@StringRes")
        assertTrue(background.contains("R.string.liquid_background_summary_active"))
        assertFalse(background.contains("isLiquidSkinRequested"))
        assertFalse(background.contains("liquid_background_summary_saved"))
    }


    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FrostedCustomBackgroundContractTest) -> Unit) {
            val s = FrostedCustomBackgroundContractTest()
            block(s)
        }
        runIsolated { s -> s.ordinarySessionPassesApplicationContextAndWorkerUsesTheExistingAssetDecoder() }
        runIsolated { s -> s.foregroundMemoryReleaseKeepsTheDisplayUnderlayUntilStopOrClose() }
        runIsolated { s -> s.customImageSummaryDoesNotRequireAdvancedMaterial() }
    }
}
