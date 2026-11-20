package com.Bilibili_Innocent_Lab.xposedmodule.ui.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BubbleDrawableDeviceTest {
    @Test fun publicBubbleClampsItsArrowAndOmitsEmptyBounds() {
        // Supersampling keeps the arrow tip measurable even for the four-pixel-wide bubble.
        fun checkAnchor(width: Int, height: Int, arrowWidth: Float, radius: Float, requested: Float,
                        expectedRadius: Float, expectedCenter: Float) {
            val image = Bitmap.createBitmap((width + 8) * 4, (height + 16) * 4, Bitmap.Config.ARGB_8888)
            try {
                val bubble = BubbleDrawable(Color.RED, arrowWidth, 4f, radius, requested)
                bubble.setBounds(0, 0, width, height)
                var paths = 0
                val canvas = object : Canvas(image) {
                    override fun drawPath(path: Path, paint: Paint) {
                        paths++
                        val points = path.approximate(.05f)
                        fun visits(x: Float, y: Float) = (points.indices step 3).any { i ->
                            kotlin.math.abs(points[i + 1] - x) < .05f && kotlin.math.abs(points[i + 2] - y) < .05f
                        }
                        assertTrue("upper right corner leaves the top at its radius", visits(width - expectedRadius, 0f))
                        assertTrue("upper right corner meets the side at its radius", visits(width.toFloat(), expectedRadius))
                        assertTrue("arrow tip", visits(expectedCenter, -4f))
                        assertTrue("arrow left shoulder", visits(expectedCenter - arrowWidth / 2f, 0f))
                        assertTrue("arrow right shoulder", visits(expectedCenter + arrowWidth / 2f, 0f))
                        super.drawPath(path, paint)
                    }
                }
                canvas.scale(4f, 4f)
                canvas.translate(4f, 8f)
                bubble.draw(canvas)
                assertEquals(1, paths)
                val xs = (0 until image.width).filter { Color.alpha(image.getPixel(it, 19)) >= 32 }
                assertTrue(xs.isNotEmpty())
                val center = (xs.first() + xs.last() + 1) / 8f - 4f
                assertEquals(expectedCenter, center, 0.5f)
                assertEquals(0, image.getPixel(0, 0))
                image.eraseColor(Color.TRANSPARENT)
                for ((w, h) in listOf(0 to 0, -1 to 10, width to 0)) {
                    bubble.setBounds(0, 0, w, h)
                    bubble.draw(canvas)
                    assertEquals("empty bounds issue no draw", 1, paths)
                }
            } finally { image.recycle() }
        }
        checkAnchor(22, 12, 6f, 5f, 5f, 5f, 8f)
        checkAnchor(320, 120, 12f, 14f, 6f, 14f, 20f)
        checkAnchor(320, 120, 12f, 14f, 160f, 14f, 160f)
        checkAnchor(10, 20, 6f, 5f, 1f, 2f, 5f)
        checkAnchor(4, 20, 6f, 5f, 1f, 0f, 2f)
    }

    @Test fun githubBadgeDrawsItsRimAndDownwardArrowInsideOffsetBounds() {
        val image = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888)
        try {
            val badge = GithubUpdateBadgeDrawable(Color.RED, 1f, Color.GREEN)
            badge.setBounds(3, 3, 25, 18)
            val canvas = Canvas(image).apply { scale(4f, 4f) }
            badge.draw(canvas)
            for (y in 0 until image.height) for (x in 0 until image.width) {
                if (x < 12 || y < 12 || x >= 100 || y >= 72) assertEquals("outside at $x,$y", 0, image.getPixel(x,y))
            }
            assertEquals(Color.RED, image.getPixel(60, 36))
            assertTrue((25..58).any { x -> Color.alpha(image.getPixel(x, 68)) > 48 })
            assertEquals(0, image.getPixel(72, 68))
            assertTrue((12 until 20).any { y -> Color.green(image.getPixel(60, y)) > Color.red(image.getPixel(60, y)) })
            image.eraseColor(Color.TRANSPARENT)
            badge.alpha = 80
            badge.draw(canvas)
            assertEquals(80, Color.alpha(image.getPixel(60, 36)))
        } finally { image.recycle() }
    }
}
