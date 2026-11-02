package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.FrostedMotionSurfaceAlpha
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.DiagnosticsEntryVisualSpec
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before

class FrostedMotionSurfaceIntegrationTest {
    private fun source(relative: String): String {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/$relative.kt"
        return LceMotionSource.read(path)
    }

    @Test fun ordinaryDiagnosticEntryKeepsItsCallerArgbInSampledAndFallbackDrawing() {
        for (dark in listOf(false, true)) {
            val callerAlpha = DiagnosticsEntryVisualSpec.scrimAlpha(dark)
            val callerColor = (callerAlpha shl 24) or 0x00909090
            for (drawableAlpha in listOf(0, 64, 128, 255)) {
                val expected = callerAlpha * drawableAlpha / 255
                val frame = FrostedMotionSurfaceAlpha.frameAlpha(callerColor, drawableAlpha)
                // The fallback fill uses frame directly; it must not become the Drawable's full alpha.
                assertEquals(expected, frame)
                assertTrue(frame <= callerAlpha)
                for (tint in listOf(216, 218, 255)) {
                    val overlay = frame * tint / 255
                    val sample = FrostedMotionSurfaceAlpha.sampleAlpha(frame, overlay)
                    assertEquals(expected.toFloat(), overlay + sample * (1f - overlay / 255f), .51f)
                }
            }
        }
        val draw = source("skin/material/FrostedMaterialRenderer")
            .after("private class ModernSurfaceDrawable(")
            .after("override fun draw(canvas: Canvas)").before("override fun setAlpha")
        assertTrue(draw.contains("FrostedMotionSurfaceAlpha.frameAlpha(drawColor, drawingAlpha)"))
        assertFalse(draw.contains("if (motionProvider != null) drawingAlpha"))
    }

    @Test fun opaqueCardsKeepTheirPreviousAlphaWhileTransparentArgbRemainsTransparent() {
        for (drawableAlpha in 0..255) {
            assertEquals(drawableAlpha, FrostedMotionSurfaceAlpha.frameAlpha(0xFFFAFAFA.toInt(), drawableAlpha))
            assertEquals(drawableAlpha, FrostedMotionSurfaceAlpha.frameAlpha(0xFF17191B.toInt(), drawableAlpha))
            assertEquals(0, FrostedMotionSurfaceAlpha.frameAlpha(0x00FAFAFA, drawableAlpha))
        }
        assertEquals(255, FrostedMotionSurfaceAlpha.frameAlpha(0xFFFFFFFF.toInt(), 300))
        assertEquals(0, FrostedMotionSurfaceAlpha.frameAlpha(0xFFFFFFFF.toInt(), -1))
    }

    @Test fun tintAndSampleCompositePreserveHostOpacityIncludingTransparentDiagnosticOrigins() {
        for (frame in 0..255) for (tint in listOf(0, 64, 180, 216, 255)) {
            val overlay = frame * tint / 255
            val sample = FrostedMotionSurfaceAlpha.sampleAlpha(frame, overlay)
            val combined = overlay + sample * (1f - overlay / 255f)
            assertEquals(frame.toFloat(), combined, .51f)
            assertTrue(sample in 0..255)
        }
        assertEquals(0, FrostedMotionSurfaceAlpha.sampleAlpha(0, 0))
        assertEquals(0, FrostedMotionSurfaceAlpha.sampleAlpha(255, 255))
        assertEquals(255, FrostedMotionSurfaceAlpha.sampleAlpha(255, 216))
    }

    @Test fun frostedDrawableUsesLiveMotionGeometryAndNeverSubstitutesFullBoundsForAnEmptyFrame() {
        val renderer = source("skin/material/FrostedMaterialRenderer")
        val drawable = renderer.after("private class ModernSurfaceDrawable(")
            .before("internal object FrostedMotionSurfaceAlpha")
        val draw = drawable.after("override fun draw(canvas: Canvas)").before("override fun setAlpha")
        assertTrue(draw.contains("view as? LiquidMotionSurfaceFrameProvider"))
        assertTrue(draw.contains("motionProvider.copyLiquidMotionBounds(rect)"))
        assertTrue(draw.contains("drawRadius = motionProvider.liquidMotionCornerRadiusPx()"))
        assertTrue(draw.contains("drawColor = motionProvider.liquidMotionFallbackColor()"))
        assertTrue(draw.indexOf("if (rect.isEmpty") > draw.indexOf("motionProvider.copyLiquidMotionBounds(rect)"))
        assertTrue(draw.contains("drawSample(canvas, rect, drawRadius, view, sampleAlpha)"))
        assertTrue(draw.contains("if (motionProvider == null)"))
        assertFalse(draw.contains("LinearGradient("))
        assertFalse(draw.contains("saveLayer("))
    }

    @Test fun bothFullPageHostsKeepTheBackdropContinuousBehindSystemBarsAndToolbar() {
        for (name in listOf("SettingsBackupActivity", "DiagnosticsActivity")) {
            val activity = source("activity/$name")
            assertTrue(name, activity.contains("motionHost.setMotionSurfaceBackground("))
            assertTrue(name, activity.contains("skinMotionSurfaceBackground("))
            assertFalse(name, activity.contains("background = skinTopBarBackground(monetColors.background)"))
            assertTrue(name, activity.indexOf("motionHost.installContentInsets()") >
                activity.indexOf("setContentView(motionHost)"))
            assertTrue(name, activity.contains("bindPreparedSkinRoot(motionHost.liquidBackdropRoot())"))
            assertFalse(name, activity.contains("liquidMotionSurfaceBackgroundOrNull("))
        }
        val host = source("activity/SettingsBackupMotionHost")
        val insets = host.after("fun installContentInsets()").before("fun setMotionSurfaceBackground")
        assertTrue(insets.contains("WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()"))
        assertTrue(insets.contains("setPadding(0, 0, 0, 0)"))
        assertTrue(insets.contains("pageClip.setPadding(safe.left, safe.top, safe.right, safe.bottom)"))
        assertTrue(host.contains("fun setMotionSurfaceBackground(background: Drawable?)"))
        assertTrue(host.contains("surface.background = background"))
        assertTrue(host.contains("if (background == null)"))
        assertTrue(host.contains("override fun copyLiquidMotionBounds(outBounds: RectF)"))
        assertTrue(host.contains("override fun liquidMotionFallbackColor(): Int = paint.color"))
    }

    @Test fun diagnosticPreviewRetainsModalFactoryAndDisposesElasticWithinItsExistingDismissHandler() {
        val diagnostics = source("activity/DiagnosticsActivity")
        val preview = diagnostics.after("private fun showExportPreview(").before("private fun dismissExportPreviewDialog")
        assertTrue(preview.contains("supportBundle: Boolean = false"))
        assertTrue(preview.contains("background = skinModalBackground(monetColors.surface)"))
        val install = preview.indexOf("val disposeElasticInteraction = installDialogElasticInteraction(dialog)")
        assertTrue(install > preview.indexOf("dialog.setContentView(root)"))
        assertTrue(preview.indexOf("dialog.setOnDismissListener") > install)
        assertEquals(1, Regex("dialog.setOnDismissListener").findAll(preview).count())
        assertTrue(preview.contains("disposeElasticInteraction()"))
        assertTrue(preview.contains("if (activeDialog === dialog) activeDialog = null"))
    }
}
