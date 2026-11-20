package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InsightThumbnailMemoryDeviceTest {
    private fun response(body: ByteArray, length: Long) = object : HttpURLConnection(URL("https://example.invalid")) {
        override fun getContentLengthLong() = length
        override fun getContentEncoding(): String? = null
        override fun getInputStream() = body.inputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }

    @Test fun nativeAndUnknownLengthPagesDecodeToTheSameSampledPixelsAfterFailedReads() {
        val random = Random(20261120)
        val size = 512
        val pixels = IntArray(size * size) { random.nextInt() or 0xff000000.toInt() }
        val source = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        val encoded = try {
            ByteArrayOutputStream().use { out ->
                check(source.compress(Bitmap.CompressFormat.PNG, 100, out))
                out.toByteArray()
            }
        } finally {
            source.recycle()
        }
        assertTrue(encoded.size >= 256 * 1024)
        val length = encoded.size.toLong()
        for (declared in listOf(length - 1, length + 1, 12L * 1024 * 1024 + 1)) {
            assertThrows(IllegalArgumentException::class.java) {
                InsightThumbnailPage.read(response(encoded, declared), 2, 2)
            }
        }
        val actual = InsightThumbnailPage.read(response(encoded, length), 2, 2)
        try {
            val expected = InsightThumbnailPage.read(response(encoded, -1), 2, 2)
            try {
                assertEquals(expected.config, actual.config)
                assertEquals(256, actual.width)
                assertEquals(256, actual.height)
                assertTrue(actual.sameAs(expected))
            } finally {
                expected.recycle()
            }
        } finally {
            actual.recycle()
        }
    }
}
