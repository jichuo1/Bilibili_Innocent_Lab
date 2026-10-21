package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets

/** Bound successful and error response bodies before JSON parsing or caching. */
internal object SemanticHttpResponseReader {
    const val MAX_BODY_BYTES = 2 * 1024 * 1024

    fun read(connection: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        return stream?.use { read(it, connection.contentLengthLong) }.orEmpty()
    }

    internal fun read(stream: InputStream, declaredLength: Long = -1L): String {
        if (declaredLength > MAX_BODY_BYTES) throw IOException("semantic response body too large")
        val output = ByteArrayOutputStream(8 * 1024)
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            // Even an absent or understated header cannot bypass the actual byte limit.
            // Read one byte beyond it to distinguish exact-limit EOF from overflow.
            val count = stream.read(buffer, 0, minOf(buffer.size, MAX_BODY_BYTES - total + 1))
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > MAX_BODY_BYTES) throw IOException("semantic response body too large")
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }
}
