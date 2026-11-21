package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 采样源选择、截图抑制和窗口取样必须遵循各自的清晰度与线程边界。 */
class LiquidRefractionSourceWiringTest {
    private fun source(name: String) = SourceContract.read("ui/skin/liquid/$name.kt")

    fun realtimeProfileUsesPresentationWithoutChangingStandardOrSharedSources() {
        val backdrop = source("LiquidBackdropSource")
        assertTrue(backdrop.contains("crispRefraction: Boolean = false"))
        assertTrue(backdrop.contains(
            "private val refractionBitmap = if (crispRefraction && opticalBitmap !== bitmap) bitmap else opticalBitmap"))
        assertTrue(backdrop.contains("BitmapShader(refractionBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)"))
        val custom = backdrop.after("fun fromCustomBitmap(").before("fun fromRealtimeBitmap(")
        assertTrue(custom.contains("crispRefraction = crispRefraction"))
        val renderer = source("LiquidActivityRenderer")
        assertTrue(renderer.contains("crispRefraction = effectProfile == LiquidEffectProfile.REALTIME_CAPTURE"))
        assertTrue(renderer.contains("if (realtimeCaptureRequested && realtimeCaptureSupported)"))
    }

    fun agslMappingUsesDimensionsOfTheActuallyBoundRefractionInput() {
        val backdrop = source("LiquidBackdropSource")
        assertTrue(backdrop.contains("val refractionWidth: Int get() = refractionBitmap.width"))
        assertTrue(backdrop.contains("val refractionHeight: Int get() = refractionBitmap.height"))
        val binding = source("LiquidRefractionBackendApi33")
            .after("override fun bindBackdrop(").before("override fun drawBackdrop(")
        assertTrue(binding.contains("shader.setInputShader(\"content\", source.bitmapShader)"))
        assertTrue(binding.contains("source.refractionWidth.toFloat() / source.fullWidth.toFloat()"))
        assertTrue(binding.contains("source.refractionHeight.toFloat() / source.fullHeight.toFloat()"))
        assertFalse(binding.contains("source.bitmap.width.toFloat() / source.fullWidth.toFloat()"))
    }

    fun cachedAndAllocationFailureSuppressionFillWithTheSamePresentationPixels() {
        val backdrop = source("LiquidBackdropSource")
        val cached = backdrop.after("fun drawSuppressionBackdrop(").before("private val suppressionBackdropPaint")
        assertTrue(cached.contains("canvas.drawBitmap(bitmap, null, bounds, suppressionBackdropPaint)"))
        assertFalse(cached.contains("opticalBitmap"))
        assertFalse(cached.contains("rootPaint"))
        val masked = backdrop.after("fun drawSuppressionBackdropMasked(").before("private val suppressionShader")
        assertTrue(masked.contains("bitmap.width.toFloat()"))
        assertTrue(masked.contains("bitmap.height.toFloat()"))
        assertTrue(masked.contains("suppressionShader.setLocalMatrix(suppressionMatrix)"))
        assertFalse(masked.contains("opticalBitmap"))
        val suppressor = source("LiquidFeedbackSuppressor")
        assertTrue(suppressor.contains("drawSuppressionBackdrop(scaleCanvas, scaleBounds, 255)"))
        assertTrue(suppressor.contains("drawSuppressionBackdropMasked("))
        assertFalse(suppressor.contains("drawOpticalBackdrop"))
        assertFalse(suppressor.contains("drawRootMasked"))
    }

    fun dialogOpticalFallbackAndCaptureSuppressionCannotRewriteEachOthersMatrices() {
        val backdrop = source("LiquidBackdropSource")
        val optical = backdrop.after("fun drawOpticalRegion(").before("fun drawPresentationRegion(")
        assertTrue(optical.contains("refractionWidth.toFloat()"))
        assertTrue(optical.contains("opticalRegionShader.setLocalMatrix(opticalRegionMatrix)"))
        assertFalse(optical.contains("suppressionShader"))
        assertFalse(optical.contains("bitmapShader.setLocalMatrix"))
        val suppression = backdrop.after("fun drawSuppressionBackdropMasked(").before("override fun close()")
        assertFalse(suppression.contains("opticalRegionShader"))
        assertFalse(suppression.contains("bitmapShader.setLocalMatrix"))
        assertTrue(backdrop.contains("BitmapShader(refractionBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply"))
    }

    fun geometryTimingAndCaptureThreadOwnershipRemainWithExistingCoordinator() {
        val suppressor = source("LiquidFeedbackSuppressor")
        val geometry = suppressor.after("fun buildSuppressionMask(").before("fun sanitizeRealtimeCapture(")
        assertTrue(geometry.contains("footprint.originX"))
        assertFalse(geometry.contains("getLocationOnScreen"))
        val renderer = source("LiquidActivityRenderer")
        assertTrue(renderer.contains("feedback.sanitizeRealtimeCapture(request.source, request.stableBackdrop"))
        val suspend = renderer.after("private fun releaseRealtimeCaptureSources(")
            .before("private fun ")
        assertTrue(suspend.contains("bindPreparedBackendsToBackdrop(stableBackdrop)"))
        assertTrue(suspend.contains("invalidateRegisteredSurfaces()"))
    }

    fun customPresentationAndOpticalSampleUseSeparateBudgets() {
        val backdrop = source("LiquidBackdropSource")
        val custom = backdrop.after("fun fromCustomBitmap(").before("fun fromRealtimeBitmap(")
        assertTrue(custom.contains("LiquidBackdropSizingPolicy.resolve(fullWidth, fullHeight)"))
        assertTrue(custom.contains("createBitmap(sample.width, sample.height, Bitmap.Config.ARGB_8888)"))
        assertTrue(custom.contains("LiquidOpticalSamplingPolicy.soften("))
        assertTrue(custom.contains("resolvePresentation(fullWidth, fullHeight)"))
        val renderer = source("LiquidActivityRenderer")
        assertTrue(renderer.contains("resolvePresentation(width, height)"))
        val sizing = source("LiquidCapabilityPolicy")
        assertTrue(sizing.contains("const val MAX_BUFFER_BYTES = 2L * 1024L * 1024L"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (LiquidRefractionSourceWiringTest) -> Unit) {
            val s = LiquidRefractionSourceWiringTest()
            block(s)
        }
        runIsolated { s -> s.realtimeProfileUsesPresentationWithoutChangingStandardOrSharedSources() }
        runIsolated { s -> s.agslMappingUsesDimensionsOfTheActuallyBoundRefractionInput() }
        runIsolated { s -> s.cachedAndAllocationFailureSuppressionFillWithTheSamePresentationPixels() }
        runIsolated { s -> s.dialogOpticalFallbackAndCaptureSuppressionCannotRewriteEachOthersMatrices() }
        runIsolated { s -> s.geometryTimingAndCaptureThreadOwnershipRemainWithExistingCoordinator() }
        runIsolated { s -> s.customPresentationAndOpticalSampleUseSeparateBudgets() }
    }
}
