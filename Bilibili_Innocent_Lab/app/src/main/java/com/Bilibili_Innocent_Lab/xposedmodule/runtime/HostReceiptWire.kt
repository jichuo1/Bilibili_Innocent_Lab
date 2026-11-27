package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** 仅使用系统 Parcel 类型，跨宿主/模块 ClassLoader 不传应用对象。 */
internal object HostReceiptWire {
    const val METHOD = "publish_host_receipt"
    const val VERSION = 1
    const val DESCRIPTOR = "bilab.host.receipt.v1"
    const val QUERY = IBinder.FIRST_CALL_TRANSACTION
    const val RESPONSE = IBinder.FIRST_CALL_TRANSACTION
    const val DIAGNOSTICS = "diagnostics"
    const val PLUGIN_DIAGNOSTICS = "plugin_diagnostics"
    const val REGISTER = "register"
    const val TIMEOUT_MS = 750L
    const val MAX_PARCEL_BYTES = 768 * 1024
    const val MAX_ONEWAY_PARCEL_BYTES = 256 * 1024
    const val GZIP_THRESHOLD_BYTES = 64 * 1024
    const val BUNDLE_OVERHEAD_BYTES = 4096
    const val MAX_DECOMPRESSED_BYTES = 1024 * 1024
    const val KEY_PAYLOAD_ENCODING = "payload_encoding"
    const val KEY_PAYLOAD_GZIP = "payload_gzip"
    const val ENCODING_GZIP = "gzip"

    sealed class EncodedPayload {
        data class Plain(val text: String) : EncodedPayload()
        data class Gzip(val bytes: ByteArray) : EncodedPayload()
    }

    fun executor(name: String) = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(8),
        { runnable -> Thread(runnable, name).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    ).apply { allowCoreThreadTimeOut(true) }

    fun estimateUtf16ParcelBytes(payload: String, overheadBytes: Int = BUNDLE_OVERHEAD_BYTES): Int {
        val total = payload.length.toLong() * 2L + overheadBytes.toLong()
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun estimateBundleParcelBytes(extras: Bundle, overheadBytes: Int = BUNDLE_OVERHEAD_BYTES): Int {
        val stringBytes = (extras.getString("payload")?.length?.toLong() ?: 0L) * 2L
        val gzipBytes = extras.getByteArray(KEY_PAYLOAD_GZIP)?.size?.toLong() ?: 0L
        return (overheadBytes.toLong() + stringBytes + gzipBytes)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    fun measureParcelBytes(bundle: Bundle): Int = runCatching {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }.getOrElse { estimateBundleParcelBytes(bundle) }

    fun encodePayload(payload: String): EncodedPayload {
        if (payload.isEmpty()) return EncodedPayload.Plain(payload)
        val utf8Bytes = payload.toByteArray(Charsets.UTF_8)
        if (utf8Bytes.size > GZIP_THRESHOLD_BYTES) {
            val compressed = gzipCompressBytes(utf8Bytes)
            if (compressed.size < payload.length.toLong() * 2L) {
                return EncodedPayload.Gzip(compressed)
            }
        }
        return EncodedPayload.Plain(payload)
    }

    fun withinOnewayBudget(encoded: EncodedPayload, overheadBytes: Int = BUNDLE_OVERHEAD_BYTES): Boolean =
        when (encoded) {
            is EncodedPayload.Plain ->
                encoded.text.length.toLong() * 2L + overheadBytes <= MAX_ONEWAY_PARCEL_BYTES
            is EncodedPayload.Gzip ->
                encoded.bytes.size.toLong() + overheadBytes <= MAX_ONEWAY_PARCEL_BYTES
        }

    fun decodePayload(
        encoding: String?,
        rawPayload: String?,
        gzipPayload: ByteArray?,
        maxDecompressedBytes: Int = MAX_DECOMPRESSED_BYTES
    ): String? = when (encoding) {
        null -> rawPayload.orEmpty()
        ENCODING_GZIP -> gzipPayload?.let { gzipDecompressBounded(it, maxDecompressedBytes) }
        else -> null
    }

    fun writePayloadToBundle(bundle: Bundle, payload: String): EncodedPayload {
        val encoded = encodePayload(payload)
        when (encoded) {
            is EncodedPayload.Plain -> {
                bundle.remove(KEY_PAYLOAD_ENCODING)
                bundle.remove(KEY_PAYLOAD_GZIP)
                bundle.putString("payload", encoded.text)
            }
            is EncodedPayload.Gzip -> {
                bundle.putString(KEY_PAYLOAD_ENCODING, ENCODING_GZIP)
                bundle.putByteArray(KEY_PAYLOAD_GZIP, encoded.bytes)
                bundle.putString("payload", "")
            }
        }
        return encoded
    }

    fun readPayloadFromBundle(
        extras: Bundle,
        maxDecompressedBytes: Int = MAX_DECOMPRESSED_BYTES
    ): String? = decodePayload(
        encoding = extras.getString(KEY_PAYLOAD_ENCODING),
        rawPayload = extras.getString("payload"),
        gzipPayload = extras.getByteArray(KEY_PAYLOAD_GZIP),
        maxDecompressedBytes = maxDecompressedBytes
    )

    fun gzipCompress(text: String): ByteArray = gzipCompressBytes(text.toByteArray(Charsets.UTF_8))

    private fun gzipCompressBytes(rawBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(rawBytes.size.coerceAtMost(GZIP_THRESHOLD_BYTES))
        GZIPOutputStream(out).use { gzip ->
            gzip.write(rawBytes)
        }
        return out.toByteArray()
    }

    fun gzipDecompressBounded(
        compressed: ByteArray,
        maxBytes: Int = MAX_DECOMPRESSED_BYTES
    ): String? {
        if (compressed.isEmpty() || compressed.size > MAX_PARCEL_BYTES || maxBytes <= 0) return null
        return runCatching {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
                while (true) {
                    val read = gzip.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) return@runCatching null
                    out.write(buffer, 0, read)
                }
            }
            out.toString(Charsets.UTF_8.name())
        }.getOrNull()
    }
}

