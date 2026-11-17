package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundSizingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidBackgroundSizingPolicyTest {

    @Test
    fun `normalization preserves aspect ratio within hard limits`() {
        val size = LiquidBackgroundSizingPolicy.resolveNormalizedSize(8000, 4000)

        assertEquals(4096, size.width)
        assertEquals(2048, size.height)
        assertTrue(size.width.toLong() * size.height <=
            LiquidBackgroundSizingPolicy.MAX_NORMALIZED_PIXELS)
    }

    @Test fun `camera photo retains enough pixels after portrait center crop`() {
        val size = LiquidBackgroundSizingPolicy.resolveNormalizedSize(4000, 3000)
        assertEquals(4000, size.width)
        assertEquals(3000, size.height)
        val crop = LiquidBackgroundSizingPolicy.centerCrop(size.width, size.height, 1080, 2400)
        assertTrue(crop.scale <= 1f)
    }

    @Test fun `square import is bounded by both edge and pixel limits`() {
        val size = LiquidBackgroundSizingPolicy.resolveNormalizedSize(8000, 8000)
        assertEquals(size.width, size.height)
        assertTrue(size.width <= LiquidBackgroundSizingPolicy.MAX_EDGE)
        assertTrue(size.width.toLong() * size.height <= LiquidBackgroundSizingPolicy.MAX_NORMALIZED_PIXELS)
    }

    @Test fun `normalization rounding cannot exceed the config pixel limit`() {
        val size = LiquidBackgroundSizingPolicy.resolveNormalizedSize(4001, 3500)
        assertTrue(size.width.toLong() * size.height <= LiquidBackgroundSizingPolicy.MAX_NORMALIZED_PIXELS)
        assertEquals(4001.0 / 3500, size.width.toDouble() / size.height, 0.001)
    }

    @Test
    fun `portrait source center crops horizontally into portrait target`() {
        val transform = LiquidBackgroundSizingPolicy.centerCrop(
            sourceWidth = 1200,
            sourceHeight = 2400,
            targetWidth = 360,
            targetHeight = 800
        )

        assertEquals(1f / 3f, transform.scale, 0.0001f)
        assertTrue(transform.translateX < 0f)
        assertEquals(0f, transform.translateY, 0.0001f)
    }

    @Test
    fun `landscape source center crops horizontally for portrait target`() {
        val transform = LiquidBackgroundSizingPolicy.centerCrop(
            sourceWidth = 2400,
            sourceHeight = 1200,
            targetWidth = 360,
            targetHeight = 800
        )

        assertTrue(transform.translateX < 0f)
        assertEquals(0f, transform.translateY, 0.0001f)
    }

    @Test
    fun `decode sample remains power of two without undershooting target`() {
        val sample = LiquidBackgroundSizingPolicy.decodeSampleSize(4096, 2048, 500, 400)

        assertEquals(4, sample)
        assertTrue(4096 / sample >= 500)
        assertTrue(2048 / sample >= 400)
    }

    @Test
    fun `declared image bomb is rejected before decode`() {
        assertTrue(!LiquidBackgroundSizingPolicy.isSupportedDeclaredSize(16_384, 16_384))
        assertTrue(!LiquidBackgroundSizingPolicy.isSupportedDeclaredSize(40_000, 100))
    }

    @Test
    fun `panorama and high resolution photos stay within the widened declared limits`() {
        // 长边全景与高像素方图现在可导入，规范化后仍落在资产边界内。
        assertTrue(LiquidBackgroundSizingPolicy.isSupportedDeclaredSize(20_000, 100))
        assertTrue(LiquidBackgroundSizingPolicy.isSupportedDeclaredSize(8000, 8000))
        val panorama = LiquidBackgroundSizingPolicy.resolveNormalizedSize(20_000, 100)
        assertEquals(LiquidBackgroundSizingPolicy.MAX_EDGE, panorama.width)
        assertTrue(panorama.height >= 1)
        val square = LiquidBackgroundSizingPolicy.resolveNormalizedSize(8000, 8000)
        assertTrue(square.width.toLong() * square.height <=
            LiquidBackgroundSizingPolicy.MAX_NORMALIZED_PIXELS)
    }
}
