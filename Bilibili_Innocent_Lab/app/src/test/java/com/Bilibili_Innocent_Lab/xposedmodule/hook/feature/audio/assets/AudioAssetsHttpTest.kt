package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.assets

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.security.MessageDigest

class AudioAssetsHttpTest {
    @get:Rule val temporary = TemporaryFolder(File("build/tmp/audio-http-tests").apply { mkdirs() })
    @Test fun `real HTTP interruption resumes pinned bytes with the production transfer`() {
        val data = ByteArray(32768) { (it % 213).toByte() }
        val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
        val part = AudioAsset("runtime.aar", data.size.toLong(), hash)
        val store = AudioAssetStore(temporary.newFolder())
        var requests = 0
        var range: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { exchange ->
            try {
                requests++
                range = exchange.requestHeaders.getFirst("Range")
                val offset = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                if (requests == 1) {
                    exchange.sendResponseHeaders(200, data.size.toLong())
                    exchange.responseBody.write(data, 0, 4096)
                } else {
                    exchange.responseHeaders.set("Content-Range", "bytes $offset-${data.size - 1}/${data.size}")
                    exchange.sendResponseHeaders(206, (data.size - offset).toLong())
                    exchange.responseBody.write(data, offset, data.size - offset)
                }
            } finally { exchange.close() }
        }
        server.start()
        try {
            // Only test transport is redirected to loopback. Production still rejects every non-HTTPS URL.
            val download = AudioAssetDownload({ URL("http://127.0.0.1:${server.address.port}/file").openConnection() as HttpURLConnection })
            download.fetch(store, part, listOf("https://primary.invalid/file", "https://mirror.invalid/file"), AudioDownloadControl())
            assertEquals(2, requests)
            assertEquals("bytes=4096-", range)
            assertTrue(store.valid(part))
        } finally { server.stop(0) }
    }
}
