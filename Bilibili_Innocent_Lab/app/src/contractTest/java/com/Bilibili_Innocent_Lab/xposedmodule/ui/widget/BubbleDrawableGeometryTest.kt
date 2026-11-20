package com.Bilibili_Innocent_Lab.xposedmodule.ui.widget

import org.junit.Assert.assertTrue
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract

/** 气泡路径几何：箭头底边必须落在圆角之外的直边上，描边必须留在 View 边界内。 */
class BubbleDrawableGeometryTest {

    @Test fun updateBadgeUsesThePublishedBubbleDrawableAndFlipsOnlyItsArrowDirection() {
        val base = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/widget/"
        assertTrue(source(base + "BubbleDrawable.kt").contains(
            "typealias BubbleDrawable = com.lumen.coacervation.engine.widget.BubbleDrawable"))
        val badge = source(base + "GithubUpdateBadgeDrawable.kt")
        assertTrue(badge.contains("private val bubble = BubbleDrawable("))
        assertTrue(badge.contains("canvas.scale(1f, -1f)"))
        assertTrue(badge.contains("bubble.draw(canvas)"))
        assertTrue(badge.contains("bubble.alpha = alpha"))
        assertTrue(badge.contains("bubble.colorFilter = colorFilter"))
    }

    @Test fun newBadgeReservesHalfTheStrokeSoTheRimStaysInsideTheBounds() {
        val badge = source("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/widget/GithubUpdateBadgeDrawable.kt")
        // 描边以路径为中心内外各半：整体内缩半宽，箭头尖与四边才不会画出 View。
        assertTrue(badge.contains("strokeInset = strokeWidthPx / 2f"))
        assertTrue(badge.contains("2f * strokeInset"))
        assertTrue(badge.contains("ceil(strokeInset)"))
        assertTrue(badge.contains("floor(bounds.width() - strokeInset)"))
        assertTrue(badge.contains("bounds.top + strokeInset + bodyHeight.toFloat()"))
    }

    private fun source(relative: String): String =
        SourceContract.read(relative)
}
