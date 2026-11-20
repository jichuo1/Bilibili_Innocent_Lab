package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.assets

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class AudioAssetsHttpTest {
    @get:Rule val temporary = TemporaryFolder(File("build/tmp/audio-http-tests").apply { mkdirs() })
    @Test fun `real HTTP interruption resumes pinned bytes with the production transfer`() {
        val data = ByteArray(32768) { (it % 213).toByte() }
        val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
        val part = AudioAsset("runtime.aar", data.size.toLong(), hash)
        val store = AudioAssetStore(temporary.newFolder())
        val server = InterruptingServer(data, cut = 4096)
        try {
            // Only test transport is redirected to loopback. Production still rejects every non-HTTPS URL.
            val download = AudioAssetDownload({ URL("http://127.0.0.1:${server.port}/file").openConnection() as HttpURLConnection })
            download.fetch(store, part, listOf("https://primary.invalid/file", "https://mirror.invalid/file"), AudioDownloadControl())
            // Exactly one cut request, then one resume that asks for the bytes already on disk.
            assertEquals(listOf<String?>(null, "bytes=4096-"), server.ranges.toList())
            assertNull(server.failure)
            assertTrue(store.valid(part))
        } finally { server.close() }
    }
}

/**
 * One-request-per-connection HTTP/1.1 server. The first connection is cut after [cut] body bytes with an orderly
 * flush + FIN, so the interruption is identical on every OS and JDK. com.sun.net.httpserver leaves the fate of an
 * unfinished fixed-length body (flushed, reset or dropped) to the runtime. On Linux/JDK 27 CI that version of this test
 * saw no resume offset (Range was null) although it passed on Windows/JDK 17 and 21.
 */
private class InterruptingServer(private val data: ByteArray, private val cut: Int) : AutoCloseable {
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = listener.localPort
    /** Range header of every request, in arrival order; null when the request carried none. */
    val ranges = CopyOnWriteArrayList<String?>()
    @Volatile var failure: Throwable? = null
    private val acceptor = thread(isDaemon = true, name = "audio-http-test-acceptor") {
        try {
            while (true) {
                val client = listener.accept()
                thread(isDaemon = true, name = "audio-http-test-exchange") { serve(client) }
            }
        } catch (closed: SocketException) {
            // close() ends the accept loop.
        }
    }

    private fun serve(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 10_000
                val range = readRange(socket)
                val first = synchronized(ranges) { ranges.add(range); ranges.size == 1 }
                val out = socket.getOutputStream()
                if (first) {
                    out.write(head("200 OK", data.size.toLong()).toByteArray(Charsets.ISO_8859_1))
                    out.write(data, 0, cut)
                    out.flush()
                    socket.shutdownOutput()
                    // Hang up only after the client does, so close() is a FIN and never an RST that could drop the cut bytes.
                    try { while (socket.getInputStream().read(ByteArray(256)) >= 0) { /* discard */ } } catch (ignored: IOException) { }
                } else {
                    val offset = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                    val extra = if (range == null) "" else "Content-Range: bytes $offset-${data.size - 1}/${data.size}\r\n"
                    out.write(head(if (range == null) "200 OK" else "206 Partial Content", (data.size - offset).toLong(), extra)
                        .toByteArray(Charsets.ISO_8859_1))
                    out.write(data, offset, data.size - offset)
                    out.flush()
                }
            }
        } catch (error: Throwable) {
            if (!listener.isClosed) failure = error
        }
    }

    private fun head(status: String, length: Long, extra: String = "") =
        "HTTP/1.1 $status\r\nContent-Length: $length\r\n${extra}Connection: close\r\n\r\n"

    private fun readRange(socket: Socket): String? {
        val input = socket.getInputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val next = input.read()
            check(next >= 0 && head.length < 16_384) { "malformed request head" }
            head.append(next.toChar())
        }
        return head.lineSequence().firstOrNull { it.startsWith("Range:", ignoreCase = true) }?.substringAfter(':')?.trim()
    }

    override fun close() {
        listener.close()
        acceptor.join(2_000)
    }
}
