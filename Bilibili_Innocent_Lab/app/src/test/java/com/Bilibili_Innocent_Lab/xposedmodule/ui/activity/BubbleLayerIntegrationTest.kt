package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import java.io.File
import org.junit.Assert.assertEquals
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
        val icon = source("BubbleIconProxy")
        assertTrue(icon.contains("source.alpha = originalAlpha"))
        // 原始 alpha 按 View 记账而不是每个代理各记一份：硬关时 Dialog 只把收尾监听器 post
        // 出去，新代理会在真正 dispose 之前构造，把动画中途的 alpha 当成"原始值"，
        // 连续打断后图标永久消失。
        assertTrue(icon.contains("SourceIconAlpha.acquire(source)"))
        assertTrue(icon.contains("SourceIconAlpha.release(source)"))
        assertTrue(icon.contains("WeakHashMap<ImageView, Entry>"))
        // 惰性代理（捕获失败）不许替别人还原，dispose 的记账必须幂等。
        assertTrue(icon.contains("if (!tookOver) return"))
        assertTrue(icon.contains("if (disposed) {"))
    }

    /**
     * 系统返回（手势）必须和三键返回走同一条退场动画。
     *
     * 回归：注册发生在 `dialog.show()` 之前，那时 decor 还没挂上 `ViewRootImpl`，
     * API 33 也没有 API 34 才加的 `ProxyOnBackInvokedDispatcher` 缓存 attach 前的注册，
     * 于是注册被丢弃、`Dialog.show()` 自己那个 system 级默认回调（直接 dismiss、无动画）
     * 赢下手势派发；而三键返回走 `Dialog.dispatchKeyEvent` → `mOnKeyListener`，仍有动画。
     */
    @Test fun systemBackGestureIsRegisteredAfterTheWindowIsAttached() {
        val code = source("MainActivity")
        val present = code.substringAfter("private fun presentSizedModalDialog(")
            .substringBefore("private fun createModalContainer")
            .ifEmpty { code.substringAfter("private fun presentSizedModalDialog(") }
        val show = present.indexOf("dialog.show()")
        val register = present.indexOf("registerBackCallback()", show)
        assertTrue("dialog.show() not found", show > 0)
        assertTrue("back callback must be registered after dialog.show()", register > show)
        // 注册前不能有第二处抢跑
        assertEquals(-1, present.substring(0, show).indexOf(".registerOnBackInvokedCallback("))
        // 注销必须冲着当时注册成功的那个 dispatcher，而不是重新取一次
        assertTrue(present.contains("val dispatcher = registeredBackDispatcher"))
        assertTrue(present.contains("dispatcher.unregisterOnBackInvokedCallback(callback)"))
        // 失败不许再静默
        assertTrue(present.contains("register OnBackInvokedCallback failed"))
        // 三键/按键回退路径保留
        assertTrue(present.contains("keyCode == KeyEvent.KEYCODE_BACK"))
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
