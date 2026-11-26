package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidBackgroundPreviewContractTest {
    fun previewWaitsForLayoutAndUsesBoundedViewDimensions() {
        val preview = SourceContract.read("ui/activity/AppearanceDialogs.kt")
            .after("private fun MainActivity.loadLiquidBackgroundPreview(")
            .before("private fun MainActivity.restoreAutomaticLiquidBackground(")
        assertTrue(preview.contains("preview.doOnLayout { view ->"))
        assertTrue(preview.contains("view.width <= 0 || view.height <= 0"))
        assertTrue(preview.indexOf("val viewWidth = view.width") < preview.indexOf("liquidBackgroundWorker.execute"))
        assertTrue(preview.contains("LiquidBackgroundStore.decodePreview("))
        assertTrue(preview.contains("viewWidth = viewWidth"))
        assertTrue(preview.contains("viewHeight = viewHeight"))
        assertFalse(preview.contains("targetWidth = 640"))
        assertFalse(preview.contains("targetHeight = 360"))
        val store = LceMotionSource.engine("core/background/LiquidBackgroundStore.kt")
            .after("fun decodePreview(")
            .before("internal fun decodeBackdrop(")
        assertTrue(store.contains("LiquidBackdropSizingPolicy.resolvePreview(viewWidth, viewHeight)"))
        assertTrue(store.contains("target.width, target.height"))
    }

    fun staleOrDetachedPreviewCannotAcceptDecodedBitmap() {
        val preview = SourceContract.read("ui/activity/AppearanceDialogs.kt")
            .after("private fun MainActivity.loadLiquidBackgroundPreview(")
            .before("private fun MainActivity.restoreAutomaticLiquidBackground(")
        assertTrue(preview.indexOf("liquidBackgroundDialog !== dialog") < preview.indexOf("liquidBackgroundWorker.execute"))
        val delivery = preview.after("runOnUiThread {")
        assertTrue(delivery.contains("!isFinishing && !isDestroyed && dialog.isShowing"))
        assertTrue(delivery.contains("liquidBackgroundDialog === dialog && preview.isAttachedToWindow"))
        assertTrue(delivery.contains("else bitmap.recycle()"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (LiquidBackgroundPreviewContractTest) -> Unit) {
            val s = LiquidBackgroundPreviewContractTest()
            block(s)
        }
        runIsolated { s -> s.previewWaitsForLayoutAndUsesBoundedViewDimensions() }
        runIsolated { s -> s.staleOrDetachedPreviewCannotAcceptDecodedBitmap() }
    }
}
