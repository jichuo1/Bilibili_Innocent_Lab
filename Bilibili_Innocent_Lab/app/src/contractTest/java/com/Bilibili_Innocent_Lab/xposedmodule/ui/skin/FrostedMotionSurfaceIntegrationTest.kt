package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.DiagnosticsEntryVisualSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrostedMotionSurfaceIntegrationTest {
    private fun source(relative: String): String =
        LceMotionSource.read("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/$relative.kt")

    @Test
    fun frostedMotionRendererAndHostPagesKeepContinuousBackdropAndSafeDisposal() {
        for (dark in listOf(false, true)) {
            val callerAlpha = DiagnosticsEntryVisualSpec.scrimAlpha(dark)
            assertTrue(callerAlpha in 1..255)
        }
        val renderer = LceMotionSource.engine("core/material/FrostedMaterialRenderer.kt")
        val draw = renderer.after("private class ModernSurfaceDrawable(")
            .before("internal object FrostedMotionSurfaceAlpha")
            .after("override fun draw(canvas: Canvas)").before("override fun setAlpha")
        assertTrue(draw.contains("FrostedMotionSurfaceAlpha.frameAlpha(drawColor, drawingAlpha)"))
        assertTrue(draw.contains("view as? LiquidMotionSurfaceFrameProvider"))
        assertTrue(draw.contains("motionProvider.copyLiquidMotionBounds(rect)"))
        assertTrue(draw.indexOf("if (rect.isEmpty") > draw.indexOf("motionProvider.copyLiquidMotionBounds(rect)"))
        assertFalse(draw.contains("saveLayer("))

        for (name in listOf("SettingsBackupActivity", "DiagnosticsActivity")) {
            val activity = source("activity/$name")
            assertTrue(name, activity.contains("motionHost.setMotionSurfaceBackground("))
            assertTrue(name, activity.contains("skinMotionSurfaceBackground("))
            assertTrue(name, activity.contains("bindPreparedSkinRoot(motionHost.liquidBackdropRoot())"))
        }

        val diagnostics = source("activity/DiagnosticsActivity")
        val preview = diagnostics.after("private fun showExportPreview(").before("private fun modalButton")
        assertTrue(preview.contains("background = skinModalBackground(monetColors.surface)"))
        assertFalse("must not install elastic interaction before setContentView", preview.contains("installDialogElasticInteraction("))
        assertTrue(preview.contains("ModalPresenter.presentCenteredDialog("))
        val modalPresenter = source("activity/ModalPresenter")
        val configure = modalPresenter.after("internal fun configureFullscreenWindow(").before("internal fun observeSafeMargins(")
        assertTrue(configure.contains("dialog.setContentView(contentRoot)"))
        val centered = modalPresenter.after("internal fun presentCenteredDialog(").before("internal fun surfaceBounds(")
        val setContent = centered.indexOf("configureFullscreenWindow(dialog, activity.window, root)")
        val install = centered.indexOf("val disposeElasticInteraction = activity.installDialogElasticInteraction(dialog)")
        assertTrue(setContent >= 0 && install > setContent)
        assertTrue(centered.indexOf("dialog.setOnDismissListener") > install)
        assertTrue(centered.contains("disposeElasticInteraction()"))
    }
}
