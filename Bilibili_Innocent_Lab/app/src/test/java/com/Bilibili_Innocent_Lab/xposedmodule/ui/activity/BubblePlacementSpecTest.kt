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
    fun `tail base clears rounded corners while tip stays exactly aligned`() {
        listOf(topLeftIcon, topRightIcon).forEach { anchor ->
            val p = place(anchor)!!
            assertTrue(
                "tail must clear the corner radius",
                p.tailBaseCenterX >= radius + tailHalf - 0.01f &&
                    p.tailBaseCenterX <= p.width - radius - tailHalf + 0.01f
            )
            assertEquals((anchor.left + anchor.right) / 2f, p.left + p.tailCenterX, 0.01f)
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

    @Test
    fun `closing stays visible until the bubble is close to the icon`() {
        assertEquals(1f, BubbleMotionSpec.surfaceAlpha(0.12f), 0f)
        assertTrue(BubbleMotionSpec.scale(0.12f) < 0.15f)
        assertEquals(0f, BubbleMotionSpec.scale(0f), 0f)
    }

    @Test
    fun `every corner converges to the icon rather than the tail tip`() {
        listOf(topLeftIcon, topRightIcon).forEach { anchor ->
            val p = place(anchor)!!
            val centerX = (anchor.left + anchor.right) / 2f
            val centerY = (anchor.top + anchor.bottom) / 2f
            val pivotX = centerX - p.left
            val pivotY = centerY - p.top
            listOf(0f to 0f, p.width to 600f).forEach { (x, y) ->
                val scale = BubbleMotionSpec.scale(0f)
                assertEquals(centerX, p.left + pivotX + (x - pivotX) * scale, 0.001f)
                assertEquals(centerY, p.top + pivotY + (y - pivotY) * scale, 0.001f)
            }
        }
    }

    @Test
    fun `predictive return interrupted during entry does not jump to expanded`() {
        listOf(0.1f, 0.4f, 0.85f, 1f).forEach { start ->
            assertEquals(start, BubbleMotionSpec.predictiveExpansion(start, 0f), 0f)
            assertEquals(start / 2f, BubbleMotionSpec.predictiveExpansion(start, 0.5f), 0f)
            assertEquals(0f, BubbleMotionSpec.predictiveExpansion(start, 1f), 0f)
        }
    }

    @Test
    fun `screen origin conversion preserves gap and tip alignment`() {
        val offsetX = 30f
        val offsetY = 96f
        val local = SettingsBackupMotionRect(topRightIcon.left - offsetX, topRightIcon.top - offsetY,
            topRightIcon.right - offsetX, topRightIcon.bottom - offsetY)
        val p = place(local)!!
        assertEquals(topRightIcon.bottom + gap, p.top + offsetY, 0.01f)
        assertEquals((topRightIcon.left + topRightIcon.right) / 2f,
            p.left + p.tailCenterX + offsetX, 0.01f)
    }

    @Test
    fun `tiny bubble has bounded tail geometry instead of inverted clamp bounds`() {
        val p = BubblePlacementSpec.place(
            anchor = SettingsBackupMotionRect(20f, 10f, 30f, 20f),
            windowWidth = 60f, windowHeight = 400f,
            desiredWidth = 40f, maxWidthPx = 40f, sideMarginPx = 10f,
            edgeMarginPx = 10f, gapPx = 4f, tailHeightPx = 9f,
            tailHalfWidthPx = 11f, cornerRadiusPx = 28f
        )!!
        assertEquals(20f, p.tailBaseCenterX, 0f)
        assertTrue(p.tailCenterX in 0f..p.width)
    }

    @Test
    fun `nonfinite window or dimensions safely reject placement`() {
        assertNull(BubblePlacementSpec.place(
            anchor = topRightIcon, windowWidth = Float.NaN, windowHeight = windowHeight,
            desiredWidth = width, maxWidthPx = width, sideMarginPx = side,
            edgeMarginPx = edge, gapPx = gap, tailHeightPx = tailHeight,
            tailHalfWidthPx = tailHalf, cornerRadiusPx = radius
        ))
    }

    @Test
    fun `entry and exit scale and opacity share the same continuous path`() {
        var previousScale = -1f
        for (step in 0..100) {
            val expansion = step / 100f
            val scale = BubbleMotionSpec.scale(expansion)
            assertTrue(scale in 0f..1f && scale >= previousScale)
            previousScale = scale
            if (step > 0) {
                assertTrue(kotlin.math.abs(BubbleMotionSpec.surfaceAlpha(expansion) -
                    BubbleMotionSpec.surfaceAlpha((step - 1) / 100f)) < 0.13f)
            }
        }
    }
}
