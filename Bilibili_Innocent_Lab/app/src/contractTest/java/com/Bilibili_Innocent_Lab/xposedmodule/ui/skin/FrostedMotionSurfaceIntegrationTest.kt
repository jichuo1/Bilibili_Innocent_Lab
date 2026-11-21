package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.DiagnosticsEntryVisualSpec
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.FrostedMotionSurfaceAlpha
import org.junit.Assert.assertEquals
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
            val callerColor = (callerAlpha shl 24) or 0x00909090
            val frame = FrostedMotionSurfaceAlpha.frameAlpha(callerColor, 128)
            assertEquals(callerAlpha * 128 / 255, frame)
        }
        val renderer = source("skin/material/FrostedMaterialRenderer")
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
        val preview = diagnostics.after("private fun showExportPreview(").before("private fun dismissExportPreviewDialog")
        assertTrue(preview.contains("background = skinModalBackground(monetColors.surface)"))
        val install = preview.indexOf("val disposeElasticInteraction = installDialogElasticInteraction(dialog)")
        assertTrue(install > preview.indexOf("dialog.setContentView(root)"))
        assertTrue(preview.indexOf("dialog.setOnDismissListener") > install)
        assertTrue(preview.contains("disposeElasticInteraction()"))
    }
}
