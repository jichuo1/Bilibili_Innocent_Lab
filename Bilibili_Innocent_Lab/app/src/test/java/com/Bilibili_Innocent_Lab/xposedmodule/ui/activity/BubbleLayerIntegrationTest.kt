package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 结构门禁不代替设备上的 Liquid/IME/图标尾帧验收。 */
class BubbleLayerIntegrationTest {
    private fun source(name: String): String {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/$name.kt"
        return sequenceOf(File(path), File("app/$path")).first(File::isFile).readText()
    }

    @Test fun bubbleContentIsNotScaledWithItsSkinSurface() {
        val controller = source("BubbleMotionController")
        assertFalse(controller.contains(".scaleX ="))
        assertFalse(controller.contains(".scaleY ="))
        val layer = source("BubblePanelLayer")
        assertTrue(layer.contains("row.view.alpha = row.alpha * fraction"))
        assertTrue(layer.contains("row.y - rowTravel * (1f - fraction)"))
        assertTrue(layer.contains("rows.forEach(Row::restore)"))
        assertTrue(layer.contains("viewport.blocked = false"))
    }

    @Test fun bubbleBorrowsTheModalSkinInsteadOfReplacingItWithAnOpaquePath() {
        val main = source("MainActivity")
        val bubble = main.substringAfter("fun applyBubbleSurface(").substringBefore("val morphLayer =")
        assertFalse(bubble.contains("BubbleSurfaceDrawable("))
        assertTrue(bubble.contains("modalBackground ?: skinModalBackground"))
        assertTrue(bubble.contains("skinModalBackground(monetColors.surface, 0f)"))
        val surface = source("BubbleSkinSurfaceView")
        assertTrue(surface.contains("surface.callback = this"))
        assertTrue(surface.contains("who === surface || super.verifyDrawable(who)"))
        assertTrue(surface.contains("surface.callback = null"))
        assertTrue(surface.contains("LiquidMotionSurfaceFrameProvider"))
        assertFalse(surface.contains("LayerDrawable"))
        assertFalse(surface.contains("LiquidActivityRenderer("))
    }

    @Test fun fadingAndContourMasksAreBoundedInsteadOfUsingWholeWindowAlpha() {
        val surface = source("BubbleSkinSurfaceView")
        assertTrue(surface.contains("frameOpacity == 255"))
        assertTrue(surface.contains("fadeBounds.intersect(0f, 0f, width.toFloat(), height.toFloat())"))
        assertTrue(surface.contains("canvas.getClipBounds(canvasClipBounds)"))
        assertTrue(surface.contains("surface.alpha = 255"))
        val layer = source("BubblePanelLayer")
        assertTrue(layer.contains("PorterDuff.Mode.DST_IN"))
        assertTrue(layer.contains("canvas.saveLayer(boundedLayer"))
        assertTrue(layer.contains("boundedLayer.intersect(0f, 0f, width.toFloat(), height.toFloat())"))
    }

    @Test fun iconSnapshotOnlyDrawsTheRealDrawableAndDoesNotIncludeRippleOrBadge() {
        val icon = source("BubbleIconProxy")
        assertTrue(icon.contains("MAX_CAPTURE_SIDE = 192"))
        assertTrue(icon.contains("if (prepared) return"))
        assertTrue(icon.contains("concat(matrix)"))
        assertTrue(icon.contains("drawable.draw(this)"))
        assertTrue(icon.contains("PorterDuff.Mode.SRC_IN"))
        assertFalse(icon.contains("source.draw("))
        assertFalse(icon.contains("drawable.setBounds("))
        assertFalse(icon.contains(".recycle()"))
        val frame = icon.substringAfter("fun updateFrame(").substringBefore("fun drawIcon(")
        assertFalse(frame.contains("Bitmap.createBitmap"))
    }

    @Test fun closingAndWindowFallbackRestoreRowsAndOriginalIconAlpha() {
        val controller = source("BubbleMotionController")
        assertTrue(controller.contains("layer.settleExpanded()"))
        assertTrue(controller.contains("layer.dispose()"))
        val layer = source("BubblePanelLayer")
        assertTrue(layer.contains("icon?.settleExpanded()"))
        assertTrue(layer.contains("icon?.dispose()"))
        assertTrue(source("BubbleIconProxy").contains("source.alpha = originalAlpha"))
    }

    @Test fun keyboardWaitsForFirstSettledEntryWithoutADelayedCloseRace() {
        val main = source("MainActivity")
        val search = main.substringAfter("private fun showSettingsSearchDialog(")
            .substringBefore("private fun clearSettingsSearchTargetHighlight(")
        assertTrue(search.contains("AnchorStyle.BUBBLE, onExpanded ="))
        assertFalse(search.contains("editor.postDelayed"))
        assertTrue(search.contains("SOFT_INPUT_STATE_ALWAYS_HIDDEN"))
        for (name in listOf("BubbleMotionController", "IconAnchoredMotionController")) {
            assertTrue(source(name).contains("if (!entryNotified)"))
            val close = source(name).substringAfter("fun requestClose(")
                .substringBefore("fun handleWindowSizeChange(")
            assertFalse(close.contains("onExpanded()"))
        }
    }

    @Test fun independentGeometryRefreshWaitsForActualLayoutAndKeepsPendingChanges() {
        val code = source("MainActivity").substringAfter("fun updateBubbleGeometry(): Boolean")
            .substringBefore("val bubbleLayoutListener")
        assertTrue(code.contains("bubbleGeometryPending || geometryChanged || actualBoundsChanged"))
        assertTrue(code.contains("!layoutChanged && !container.isLayoutRequested"))
        assertTrue(code.indexOf("bubbleLayer?.setAnchor(localAnchor)") < code.indexOf("handleWindowSizeChange()"))
        assertTrue(code.indexOf("container.layoutParams = params") < code.indexOf("handleWindowSizeChange()"))
        assertTrue(code.contains("previousBubbleBottom = container.bottom"))
    }
}
