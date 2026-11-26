package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource
import org.junit.Assert.*
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before

class ModernMaterialIntegrationTest {
    private fun source(path: String): String = SourceContract.read("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/$path")

    fun neutralModulePaletteDoesNotChangeHostWallpaperPaletteImplementation() {
        val skin = source("ui/skin/activity/SkinnedActivity.kt")
        assertTrue(skin.contains("ModernPalette.resolve(this)"))
        assertFalse(source("ui/theme/MonetColors.kt").contains("ModernPalette"))
        val neutral = skin.after("internal fun neutralWindowBackground()").before("internal fun skinFloatingBackground")
        assertTrue(neutral.contains("lumen.neutralWindowBackground()"))
        assertFalse(neutral.contains("prepareSkinSession"))
        assertFalse(neutral.contains("SkinPrefs"))
        val delegate = LceMotionSource.engine("core/host/LumenActivityDelegate.kt")
        assertTrue(delegate.contains("ModernMaterialDrawables.neutralWindow(palette)"))
    }

    fun materialUsesOneSharedPreblurredUnderlayWithoutTakingLiquidOwnership() {
        val renderer = LceMotionSource.engine("core/material/FrostedMaterialRenderer.kt")
        assertTrue(renderer.contains("ModernBackdropBlur.blur(pixels"))
        assertTrue(renderer.contains("BitmapShader(result.blurred"))
        assertTrue(renderer.contains("worker.submit"))
        assertTrue(renderer.contains("if (!lifecycle.accepts(token)) return"))
        assertTrue(renderer.contains("failedWidth == newWidth && failedHeight == newHeight"))
        assertFalse(renderer.contains("PixelCopy"))
        assertFalse(renderer.contains("claimLiquidRenderSession"))
        assertFalse(renderer.before("private data class ModernBackdropFrame").contains(".recycle()"))
        assertFalse(renderer.after("private class ModernSurfaceDrawable").contains(".recycle()"))
        val session = LceMotionSource.engine("core/runtime/ActivitySkinSession.kt")
        assertTrue(session.contains("if (requestedSkin != SkinId.LIQUID) return materialRenderer.bindRoot(root)"))
        assertTrue(session.contains("val owner = if (requestedSkin == SkinId.LIQUID)"))
        assertTrue(session.contains("materialRenderer.close()"))
    }

    fun chromeRolesRemainDistinctAndGeometryRemainsCallerOwned() {
        val skin = source("ui/skin/activity/SkinnedActivity.kt")
        assertTrue(skin.contains("lumen.surface(color, radiusDp, SurfaceRole.FLOATING)"))
        assertTrue(skin.contains("lumen.surface(color, radiusDp, SurfaceRole.TOP_BAR)"))
        assertTrue(skin.contains("lumen.surface(color, radiusDp, SurfaceRole.SELECTED_ITEM)"))
        val renderer = LceMotionSource.engine("core/material/FrostedMaterialRenderer.kt")
        assertTrue(renderer.contains("radiusDp * density"))
        assertTrue(renderer.contains("observer.addOnScrollChangedListener(scroll)"))
        assertTrue(renderer.contains("observer.removeOnScrollChangedListener(state.scroll)"))
        assertTrue(renderer.contains("observer.addOnPreDrawListener(preDraw)"))
        assertTrue(renderer.contains("observer.removeOnPreDrawListener(state.preDraw)"))
        assertTrue(renderer.contains("observer.addOnDrawListener(draw)"))
        assertTrue(renderer.contains("observer.removeOnDrawListener(state.draw)"))
        // 登记与批量比较须用同一矩阵乘法次序，避免浮点结合差异造成静止表面反复失效。
        val registration = renderer.after("internal fun register(").before("private fun registerRefreshWindow")
        val refresh = renderer.after("private fun flushPositionChanges").before("fun releaseMemory")
        assertTrue(registration.contains("samplingMatrices.withAncestorMemo"))
        assertTrue(refresh.contains("samplingMatrices.withAncestorMemo"))
        assertTrue(renderer.contains("ValueAnimator.areAnimatorsEnabled()"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (ModernMaterialIntegrationTest) -> Unit) {
            val s = ModernMaterialIntegrationTest()
            block(s)
        }
        runIsolated { s -> s.neutralModulePaletteDoesNotChangeHostWallpaperPaletteImplementation() }
        runIsolated { s -> s.materialUsesOneSharedPreblurredUnderlayWithoutTakingLiquidOwnership() }
        runIsolated { s -> s.chromeRolesRemainDistinctAndGeometryRemainsCallerOwned() }
    }
}
