package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ModuleNativeLoader
import io.github.jichuo1.meguviz.memory.android.ManagedMemoryDomain
import java.io.InputStream
import java.net.HttpURLConnection
import java.nio.ByteBuffer

/** Both BitmapFactory passes run on the cover worker while the encoded page is borrowed. */
internal object InsightThumbnailPage {
    private const val MAX_BYTES = 12 * 1024 * 1024
    private const val DIRECT_THRESHOLD = 256 * 1024
    private const val SKIP_NATIVE_LOAD_PROP = "io.github.jichuo1.meguviz.memory.skipNativeLoad"
    private val memory by lazy {
        try {
            if (!java.lang.Boolean.getBoolean(SKIP_NATIVE_LOAD_PROP)) {
                ModuleNativeLoader.load("mgmem")
                ModuleNativeLoader.load("mgmem_pressure_jni")
                System.setProperty(SKIP_NATIVE_LOAD_PROP, "true")
            }
            ManagedMemoryDomain.system(MAX_BYTES.toLong())
        } catch (_: LinkageError) {
            null
        }
    }

    fun read(connection: HttpURLConnection, columns: Int, rows: Int): Bitmap {
        // A content-encoded response may advertise the compressed transport length.
        val length = if (connection.contentEncoding == null || connection.contentEncoding.equals("identity", true))
            connection.contentLengthLong else -1L
        require(length <= MAX_BYTES) { "public-response-too-large" }
        val domain = if (length >= DIRECT_THRESHOLD) memory else null
        if (domain == null) {
            val bytes = InsightContextClient.readPublicBytes(connection, MAX_BYTES)
            return decode(columns, rows) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
        }

        var result: Bitmap? = null
        try {
            domain.allocate(length, 64L).use { allocation ->
                allocation.borrowWrite().use { lease ->
                    lease.withDirectBuffer { storage ->
                        val count = InsightContextClient.readPublicBody(connection, length.toInt()) { bytes, size ->
                            storage.put(bytes, 0, size)
                        }
                        require(count.toLong() == length) { "incomplete-thumbnail" }
                        storage.flip()
                        result = decode(columns, rows) { options ->
                            BitmapFactory.decodeStream(BufferInput(storage.duplicate()), null, options)
                        }
                    }
                }
            }
            return checkNotNull(result)
        } catch (failure: Throwable) {
            result?.recycle()
            throw failure
        }
    }

    private inline fun decode(columns: Int, rows: Int, decodeBitmap: (BitmapFactory.Options) -> Bitmap?): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decodeBitmap(bounds)
        require(bounds.outWidth in columns..16_000 && bounds.outHeight in rows..16_000 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000)
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (bounds.outWidth / inSampleSize > columns * 240 || bounds.outHeight / inSampleSize > rows * 150)
                inSampleSize *= 2
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return decodeBitmap(options) ?: error("thumbnail-decode")
    }

    private class BufferInput(private val data: ByteBuffer) : InputStream() {
        override fun read(): Int = if (data.hasRemaining()) data.get().toInt() and 255 else -1
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (!data.hasRemaining()) return -1
            val count = minOf(length, data.remaining())
            data.get(bytes, offset, count)
            return count
        }
    }
}
