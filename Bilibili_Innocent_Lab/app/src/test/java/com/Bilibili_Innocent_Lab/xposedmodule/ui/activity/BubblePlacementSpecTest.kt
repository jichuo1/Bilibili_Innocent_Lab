package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePlacementSpecTest {

    private val windowWidth = 1440f
    private val windowHeight = 3077f
    private val width = 960f
    private val side = 36f
    private val edge = 48f
    private val gap = 18f
    private val tailHeight = 27f
    private val tailHalf = 33f
    private val radius = 84f

    private fun place(anchor: SettingsBackupMotionRect) = BubblePlacementSpec.place(
        anchor = anchor,
        windowWidth = windowWidth,
        windowHeight = windowHeight,
        desiredWidth = width,
        maxWidthPx = width,
        sideMarginPx = side,
        edgeMarginPx = edge,
        gapPx = gap,
        tailHeightPx = tailHeight,
        tailHalfWidthPx = tailHalf,
        cornerRadiusPx = radius
    )

    /** 工具栏右上角的 GitHub 图标。 */
    private val topRightIcon =
        SettingsBackupMotionRect(left = 1277f, top = 45f, right = 1371f, bottom = 139f)

    /** 工具栏左上角的搜索图标。 */
    private val topLeftIcon =
        SettingsBackupMotionRect(left = 69f, top = 45f, right = 163f, bottom = 139f)

    @Test
    fun `bubble hangs below a toolbar icon with the tail on top`() {
        val p = assertNotNull(place(topRightIcon)).let { place(topRightIcon)!! }
        assertEquals(BubbleTailEdge.TOP, p.tailEdge)
        assertEquals(topRightIcon.bottom + gap, p.top, 0f)
        assertTrue(p.isUsable)
    }

    @Test
    fun `bubble is clamped inside the window but the tail still points at the icon`() {
        val p = place(topRightIcon)!!
        // 右上角图标：气泡整体被右边距夹住，不能溢出屏幕。
        assertEquals(windowWidth - side - width, p.left, 0.01f)
        assertTrue("bubble must stay on screen", p.left >= side - 0.01f)
        // 小角绝对位置应当仍然落在图标的水平范围内。
        val tipAbsolute = p.left + p.tailCenterX
        assertTrue(
            "tail tip $tipAbsolute must sit under the icon [${topRightIcon.left}, ${topRightIcon.right}]",
            tipAbsolute in topRightIcon.left..topRightIcon.right
        )
    }

    @Test
    fun `left anchored bubble mirrors the same behaviour`() {
        val p = place(topLeftIcon)!!
        assertEquals(side, p.left, 0.01f)
        val tipAbsolute = p.left + p.tailCenterX
        assertTrue(tipAbsolute in topLeftIcon.left..topLeftIcon.right)
    }

    @Test
    fun `tail never grows onto the rounded corner`() {
        listOf(topLeftIcon, topRightIcon).forEach { anchor ->
            val p = place(anchor)!!
            assertTrue(
                "tail must clear the corner radius",
                p.tailCenterX >= radius + tailHalf - 0.01f &&
                    p.tailCenterX <= p.width - radius - tailHalf + 0.01f
            )
        }
    }

    @Test
    fun `an anchor near the bottom flips the bubble above it`() {
        val bottomIcon =
            SettingsBackupMotionRect(left = 640f, top = 2900f, right = 734f, bottom = 2994f)
        val p = place(bottomIcon)!!
        assertEquals(BubbleTailEdge.BOTTOM, p.tailEdge)
        val top = BubblePlacementSpec.resolveTop(p, bottomIcon, measuredHeight = 600f, gapPx = gap, edgeMarginPx = edge)
        // 向上的气泡要贴住锚点，底边落在图标上方一个 gap 处。
        assertEquals(bottomIcon.top - gap - 600f, top, 0.01f)
    }

    @Test
    fun `an upward bubble is never pushed off the top edge`() {
        val nearTop = SettingsBackupMotionRect(left = 640f, top = 2900f, right = 734f, bottom = 2994f)
        val p = place(nearTop)!!
        val top = BubblePlacementSpec.resolveTop(p, nearTop, measuredHeight = 9000f, gapPx = gap, edgeMarginPx = edge)
        assertEquals(edge, top, 0f)
    }

    @Test
    fun `invalid input is rejected so the caller can fall back to a centred dialog`() {
        assertNull(place(SettingsBackupMotionRect(0f, 0f, 0f, 0f)))
        assertNull(
            BubblePlacementSpec.place(
                anchor = topRightIcon, windowWidth = 0f, windowHeight = windowHeight,
                desiredWidth = width, maxWidthPx = width, sideMarginPx = side, edgeMarginPx = edge,
                gapPx = gap, tailHeightPx = tailHeight, tailHalfWidthPx = tailHalf,
                cornerRadiusPx = radius
            )
        )
        // 边距吃满整个窗口时没有可用宽度。
        assertNull(
            BubblePlacementSpec.place(
                anchor = topRightIcon, windowWidth = windowWidth, windowHeight = windowHeight,
                desiredWidth = width, maxWidthPx = width, sideMarginPx = windowWidth,
                edgeMarginPx = edge, gapPx = gap, tailHeightPx = tailHeight,
                tailHalfWidthPx = tailHalf, cornerRadiusPx = radius
            )
        )
    }

    @Test
    fun `width never exceeds the available space`() {
        val p = BubblePlacementSpec.place(
            anchor = topRightIcon, windowWidth = windowWidth, windowHeight = windowHeight,
            desiredWidth = 99_999f, maxWidthPx = 99_999f, sideMarginPx = side, edgeMarginPx = edge,
            gapPx = gap, tailHeightPx = tailHeight, tailHalfWidthPx = tailHalf,
            cornerRadiusPx = radius
        )!!
        assertEquals(windowWidth - 2f * side, p.width, 0.01f)
        assertEquals(side, p.left, 0.01f)
    }

    @Test
    fun `motion spec scales from the collapsed value to one and clamps out of range input`() {
        assertEquals(BubbleMotionSpec.COLLAPSED_SCALE, BubbleMotionSpec.scale(0f), 0f)
        assertEquals(1f, BubbleMotionSpec.scale(1f), 0f)
        assertEquals(BubbleMotionSpec.COLLAPSED_SCALE, BubbleMotionSpec.scale(-5f), 0f)
        assertEquals(1f, BubbleMotionSpec.scale(5f), 0f)
    }

    @Test
    fun `surface alpha reaches full opacity well before the scale finishes`() {
        assertEquals(0f, BubbleMotionSpec.surfaceAlpha(0f), 0f)
        assertEquals(1f, BubbleMotionSpec.surfaceAlpha(0.35f), 1e-6f)
        assertEquals(1f, BubbleMotionSpec.surfaceAlpha(1f), 0f)
        var previous = -1f
        var step = 0
        while (step <= 100) {
            val value = BubbleMotionSpec.surfaceAlpha(step / 100f)
            assertTrue(value in 0f..1f)
            assertTrue("alpha must not decrease at $step", value >= previous)
            previous = value
            step++
        }
    }
}
