package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IconAnchoredMotionSpecTest {

    /**
     * 工具栏**右上角**的 27dp 图标（对应 GitHub 按钮），卡片居中。
     * 折叠端**完全落在**展开端之外，这是图标锚点的正常形态。
     */
    private val icon =
        SettingsBackupMotionRect(left = 1250f, top = 90f, right = 1331f, bottom = 171f)
    private val card =
        SettingsBackupMotionRect(left = 96f, top = 900f, right = 1344f, bottom = 1700f)
    private val geometry = IconAnchoredMotionGeometry(
        collapsedBounds = icon,
        expandedBounds = card,
        collapsedRadiusPx = 40.5f,
        expandedRadiusPx = 84f,
        contentTravelCapPx = 60f
    )
    private val buffer = IconAnchoredMotionFrameBuffer()

    private fun frameAt(
        expansion: Float,
        timing: IconAnchoredContentTiming = IconAnchoredContentTiming.TIMED
    ): IconAnchoredMotionFrameBuffer {
        IconAnchoredMotionSpec.fillFrame(buffer, expansion, geometry, timing)
        return buffer
    }

    @Test
    fun `collapsed endpoint is exactly the source icon`() {
        val frame = frameAt(0f)
        assertEquals(icon.left, frame.left, 0f)
        assertEquals(icon.top, frame.top, 0f)
        assertEquals(icon.right, frame.right, 0f)
        assertEquals(icon.bottom, frame.bottom, 0f)
        assertEquals(40.5f, frame.radiusPx, 0f)
        // 表面与正文都必须从 0 起步，否则第一帧会在图标位置糊一块不透明色。
        assertEquals(0f, frame.surfaceAlpha, 0f)
        assertEquals(0f, frame.contentAlpha, 0f)
        // 正文起始位移必须朝着锚点方向：图标在卡片的右上方。
        assertTrue("content must start offset toward the anchor", frame.contentTranslationXPx > 0f)
        assertTrue("content must start offset toward the anchor", frame.contentTranslationYPx < 0f)
    }

    @Test
    fun `expanded endpoint is exactly the card`() {
        val frame = frameAt(1f)
        assertEquals(card.left, frame.left, 0f)
        assertEquals(card.top, frame.top, 0f)
        assertEquals(card.right, frame.right, 0f)
        assertEquals(card.bottom, frame.bottom, 0f)
        assertEquals(84f, frame.radiusPx, 0f)
        assertEquals(1f, frame.surfaceAlpha, 0f)
        assertEquals(1f, frame.contentAlpha, 0f)
        // 收尾必须精确归零，否则卡片会永久偏移几个像素。
        assertEquals(0f, frame.contentTranslationXPx, 0f)
        assertEquals(0f, frame.contentTranslationYPx, 0f)
    }

    @Test
    fun `content travel is capped per axis and decays to zero with the content fade`() {
        // 锚点中心到卡片中心的 Y 距离约 1200px，比例 0.10 后为 120px，必须被 60px 上限截断。
        assertEquals(-60f, frameAt(0f).contentTranslationYPx, 1e-3f)
        var previous = Float.MAX_VALUE
        var step = 0
        while (step <= 100) {
            val magnitude = kotlin.math.abs(frameAt(step / 100f).contentTranslationYPx)
            assertTrue("travel must not exceed the cap at step $step", magnitude <= 60f + 1e-3f)
            assertTrue("travel must not grow at step $step", magnitude <= previous + 1e-3f)
            previous = magnitude
            step++
        }
        assertEquals(0f, previous, 0f)
    }

    @Test
    fun `a zero cap disables the travel and leaves a pure fade`() {
        val noTravel = geometry.copy(contentTravelCapPx = 0f)
        IconAnchoredMotionSpec.fillFrame(buffer, 0f, noTravel, IconAnchoredContentTiming.TIMED)
        assertEquals(0f, buffer.contentTranslationXPx, 0f)
        assertEquals(0f, buffer.contentTranslationYPx, 0f)
    }

    @Test
    fun `out of range progress is clamped to the endpoints`() {
        val below = frameAt(-3f)
        assertEquals(icon.left, below.left, 0f)
        assertEquals(0f, below.contentAlpha, 0f)
        val above = frameAt(4f)
        assertEquals(card.right, above.right, 0f)
        assertEquals(1f, above.contentAlpha, 0f)
    }

    @Test
    fun `a geometry whose source lies outside the target is still usable`() {
        // 图标在工具栏、卡片在屏幕中央，两者不相交——不能被当成非法几何拒掉。
        assertTrue(geometry.isUsable)
    }

    @Test
    fun `degenerate or non finite geometry is rejected`() {
        assertFalse(
            geometry.copy(
                collapsedBounds = SettingsBackupMotionRect(10f, 10f, 10f, 40f)
            ).isUsable
        )
        assertFalse(geometry.copy(expandedRadiusPx = Float.NaN).isUsable)
        assertFalse(geometry.copy(collapsedRadiusPx = -1f).isUsable)
        assertFalse(geometry.copy(contentTravelCapPx = -1f).isUsable)
        assertFalse(geometry.copy(contentTravelCapPx = Float.NaN).isUsable)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `filling a frame from unusable geometry fails loudly`() {
        IconAnchoredMotionSpec.fillFrame(
            buffer,
            0.5f,
            geometry.copy(expandedRadiusPx = Float.NaN),
            IconAnchoredContentTiming.TIMED
        )
    }

    /**
     * 这是"不复用容器形变那套"的核心理由：图标锚点没有标题位移要等，正文窗口必须宽得多。
     * 若哪天有人把它改回 `SettingsBackupMotionSpec` 的 0.86..0.985，这条会立刻失败。
     */
    @Test
    fun `timed content window is much wider than the container morph profile`() {
        val ours = IconAnchoredMotionSpec.contentFraction(0.7f, IconAnchoredContentTiming.TIMED)
        val containerMorph = SettingsBackupMotionSpec.contentFraction(
            0.7f,
            SettingsBackupContentTiming.TIMED
        )
        assertTrue("icon anchored content must lead the container morph", ours > containerMorph)
        assertEquals(0f, containerMorph, 0f)
    }

    @Test
    fun `predictive window leads the timed window across the gesture range`() {
        listOf(0.3f, 0.5f, 0.6f).forEach { expansion ->
            val timed = IconAnchoredMotionSpec.contentFraction(
                expansion,
                IconAnchoredContentTiming.TIMED
            )
            val predictive = IconAnchoredMotionSpec.contentFraction(
                expansion,
                IconAnchoredContentTiming.PREDICTIVE
            )
            assertTrue(
                "predictive must stay ahead at $expansion (timed=$timed predictive=$predictive)",
                predictive > timed
            )
        }
    }

    @Test
    fun `content fraction is monotonic and bounded for both profiles`() {
        IconAnchoredContentTiming.entries.forEach { timing ->
            var previous = -1f
            var step = 0
            while (step <= 100) {
                val value = IconAnchoredMotionSpec.contentFraction(step / 100f, timing)
                assertTrue("$timing must stay within 0..1", value in 0f..1f)
                assertTrue("$timing must not decrease at step $step", value >= previous)
                previous = value
                step++
            }
            assertEquals(1f, previous, 0f)
        }
    }

    @Test
    fun `surface reaches full opacity early so the shape never fades with the content`() {
        assertEquals(1f, frameAt(0.12f).surfaceAlpha, 1e-6f)
        // 表面已经全不透明时正文还没开始，形状先立住、内容再接管。
        assertEquals(0f, frameAt(0.12f).contentAlpha, 0f)
    }

    @Test
    fun `bounds interpolate monotonically from the icon to the card`() {
        var previousWidth = -1f
        var step = 0
        while (step <= 100) {
            val frame = frameAt(step / 100f)
            val width = frame.right - frame.left
            assertTrue("width must not shrink at step $step", width >= previousWidth)
            previousWidth = width
            step++
        }
    }
}
