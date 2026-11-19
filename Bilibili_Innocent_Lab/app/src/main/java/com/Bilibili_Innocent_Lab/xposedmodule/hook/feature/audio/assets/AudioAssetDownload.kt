package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.assets

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.atomic.AtomicBoolean

internal class AudioDownloadControl {
    private val cancelled = AtomicBoolean()
    @Volatile private var connection: HttpURLConnection? = null
    fun cancel() { cancelled.set(true); connection?.disconnect() }
    fun check() { if (cancelled.get() || Thread.currentThread().isInterrupted) throw AudioAssetFailure("cancelled") }
    fun connect(value: HttpURLConnection) { connection = value; if (cancelled.get()) { value.disconnect(); check() } }
    fun disconnect() { connection?.disconnect(); connection = null }
}

/** Pinned content permits safe cross-mirror resume; even a valid 206 is never accepted without a final hash. */
internal class AudioAssetDownload(
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val clock: () -> Long = System::nanoTime
) {
    fun fetch(store: AudioAssetStore, part: AudioAsset, urls: List<String>, control: AudioDownloadControl,
              event: (String) -> Unit = {}, progress: (Long) -> Unit = {}) {
        if (store.valid(part)) return
        val partial = File(store.directory, "${part.remoteName}.resume")
        if (Files.isSymbolicLink(partial.toPath())) throw AudioAssetFailure("unsafe_partial", part.name)
        if (partial.length() > part.size) Files.deleteIfExists(partial.toPath())
        var last: AudioAssetFailure? = null
        for ((index, source) in urls.withIndex()) {
            control.check()
            try {
                event("source=${index + 1} part=${part.name} resume=${partial.length()}")
                if (partial.length() != part.size) transfer(part, source, partial, control, progress)
                control.check()
                store.install(part) { Files.newInputStream(partial.toPath(), NOFOLLOW_LINKS) }
                Files.deleteIfExists(partial.toPath())
                return
            } catch (failure: Exception) {
                control.check()
                last = failure as? AudioAssetFailure ?: AudioAssetFailure(
                    if (failure is java.net.SocketTimeoutException) "network_timeout" else "network_io",
                    part.name, failure.javaClass.simpleName, failure)
                event("source=${index + 1} code=${last.code} part=${last.part} ${last.detail}")
                if (last.code in setOf("hash_mismatch", "size_overflow", "range_invalid")) Files.deleteIfExists(partial.toPath())
            } finally { control.disconnect() }
        }
        throw last ?: AudioAssetFailure("no_source", part.name)
    }

    private fun transfer(part: AudioAsset, source: String, partial: File, control: AudioDownloadControl, progress: (Long) -> Unit) {
        val url = URL(source)
        if (url.protocol != "https" || url.userInfo != null) throw AudioAssetFailure("source_rejected", part.name)
        val start = partial.length()
        val connection = open(url)
        control.connect(connection)
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = false // Fixed endpoints; do not silently downgrade TLS or follow arbitrary hosts.
        connection.setRequestProperty("User-Agent", "BilibiliInnocentLab/AudioAssets-v1")
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (start > 0) connection.setRequestProperty("Range", "bytes=$start-")
        val status = connection.responseCode
        if (status != 200 && status != 206) throw AudioAssetFailure("http_status", part.name, "status=$status")
        if (connection.contentEncoding?.let { it != "identity" } == true) throw AudioAssetFailure("encoding_invalid", part.name)
        val offset = if (status == 206) start else 0L
        if (status == 206 && connection.getHeaderField("Content-Range") != "bytes $offset-${part.size - 1}/${part.size}")
            throw AudioAssetFailure("range_invalid", part.name)
        val length = connection.contentLengthLong
        if (length >= 0 && length != part.size - offset) throw AudioAssetFailure("response_size", part.name, "bytes=$length")
        val deadline = clock() + 180_000_000_000L
        connection.inputStream.use { input ->
            FileChannel.open(partial.toPath(), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                channel.truncate(offset); channel.position(offset)
                val output = Channels.newOutputStream(channel)
                var total = offset
                val bytes = ByteArray(64 * 1024)
                try {
                    while (true) {
                        control.check()
                        if (clock() >= deadline) throw AudioAssetFailure("network_deadline", part.name)
                        val count = input.read(bytes, 0, minOf(bytes.size.toLong(), part.size - total + 1).toInt())
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > part.size) throw AudioAssetFailure("size_overflow", part.name)
                        output.write(bytes, 0, count); progress(total)
                    }
                    if (total != part.size) throw AudioAssetFailure("size_mismatch", part.name, "bytes=$total expected=${part.size}")
                } finally { channel.force(true) }
            }
        }
    }
}
