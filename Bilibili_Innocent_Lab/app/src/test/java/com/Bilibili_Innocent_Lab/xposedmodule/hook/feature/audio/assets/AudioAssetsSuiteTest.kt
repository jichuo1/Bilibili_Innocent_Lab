package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.assets

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.AudioModelInput
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.AudioPreparationRetry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioAssetsSuiteTest {
    @get:Rule
    val temporary = TemporaryFolder(File("build/tmp/audio-assets-tests").apply { mkdirs() })

    private val content = ByteArray(1000) { (it % 251).toByte() }

    private fun spec(data: ByteArray = content, name: String = "model.onnx", entry: String = name) =
        AudioAsset(
            name,
            data.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) },
            entry
        )

    private fun store() = AudioAssetStore(temporary.newFolder())

    private fun failCode(code: String, work: () -> Unit) {
        try {
            work()
            fail("expected $code")
        } catch (failure: AudioAssetFailure) {
            assertEquals(code, failure.code)
        }
    }

    private fun partial(store: AudioAssetStore, part: AudioAsset) = File(store.directory, "${part.remoteName}.resume")

    private class MockResponse(
        private val body: InputStream,
        private val status: Int = 200,
        private val length: Long = 1000,
        private val range: String? = null
    ) : HttpURLConnection(URL("https://fixture.invalid/file")) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = body
        override fun getContentLengthLong() = length
        override fun getHeaderField(name: String?) = if (name == "Content-Range") range else null
    }

    private class Remote : AudioRemoteFiles {
        val files = mutableMapOf<String, ByteArray>()
        var failWrite = false
        var writes = 0
        override fun names() = files.keys.toSet()
        override fun open(name: String) = ByteArrayInputStream(files.getValue(name))
        override fun write(name: String, copy: (java.io.OutputStream) -> Unit) {
            writes++
            val output = ByteArrayOutputStream()
            copy(output)
            files[name] = if (failWrite) output.toByteArray().copyOf(10) else output.toByteArray()
            if (failWrite) throw IOException("framework died")
        }
    }

    @Test
    fun verifiesVerificationStoreLeasesDownloadsPublicationAndPermissions() {
        // Bounded verification rejects short, long, and changed bytes.
        failCode("size_mismatch") { AudioAssetStore.verify(spec(), content.copyOf(999).inputStream()) }
        failCode("size_overflow") { AudioAssetStore.verify(spec(), content.copyOf(1001).inputStream()) }
        failCode("hash_mismatch") { AudioAssetStore.verify(spec(), ByteArray(1000).inputStream()) }
        AudioAssetStore.verify(spec(), content.inputStream())

        // Direct ORT input contains exact verified bytes on open.
        run {
            val part = spec()
            val file = temporary.newFile()
            file.writeBytes(content)
            val input = ByteBuffer.allocateDirect(content.size)
            AudioModelInput.readVerified(file, part, input)
            assertArrayEquals(content, ByteArray(input.remaining()).also { input.get(it) })
            file.writeBytes(content.copyOf().also { it[0] = 42 })
            input.clear()
            failCode("hash_mismatch") { AudioModelInput.readVerified(file, part, input) }
            file.appendBytes(byteArrayOf(10))
            input.clear()
            failCode("size_overflow") { AudioModelInput.readVerified(file, part, input) }
        }

        // Failed staged install preserves existing file and removes staging.
        run {
            val store = store()
            val part = spec()
            store.file(part).writeBytes(byteArrayOf(99))
            failCode("size_mismatch") { store.install(part) { content.copyOf(20).inputStream() } }
            assertArrayEquals(byteArrayOf(99), store.file(part).readBytes())
            assertFalse(store.directory.listFiles()!!.any { it.name.endsWith(".part") })
            store.install(part) { content.inputStream() }
            assertTrue(store.valid(part))
        }

        // Verified cache is reusable offline and corrupt bytes self-repair.
        run {
            val store = store()
            val part = spec()
            store.install(part) { content.inputStream() }
            store.install(part) { error("must not open remote") }
            store.file(part).writeBytes(ByteArray(content.size))
            store.install(part) { content.inputStream() }
            assertTrue(store.valid(part))
        }

        // Process death leftovers cleaned without losing resume data; cache lease excludes overlap.
        run {
            val store = store()
            val stale = File(store.directory, ".audio-old.part").apply { writeText("partial") }
            val resume = partial(store, spec()).apply { writeText("keep") }
            store.locked {
                assertFalse(stale.exists())
                assertTrue(resume.exists())
            }
            val leasedStore = store()
            leasedStore.locked { failCode("busy") { leasedStore.locked {} } }
            leasedStore.locked { assertTrue(true) }
        }

        // Concurrent host staging has unique paths and preserves fixed identity.
        run {
            val root = temporary.newFolder()
            val part = spec()
            val workers = Executors.newFixedThreadPool(2)
            val bothStaged = CyclicBarrier(2)
            try {
                val done = (0..1).map {
                    workers.submit<File> {
                        AudioAssetStore(root).install(part) {
                            bothStaged.await(5, TimeUnit.SECONDS)
                            content.inputStream()
                        }
                    }
                }
                done.forEach { assertArrayEquals(content, it.get().readBytes()) }
            } finally {
                workers.shutdownNow()
            }
        }

        // Archive only extracts fixed entries and rejects altered entry content.
        run {
            val part = spec(entry = "jni/arm64-v8a/model.onnx")
            val bytes = ByteArrayOutputStream().also { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry("../../escape")); zip.write(byteArrayOf(1)); zip.closeEntry()
                    zip.putNextEntry(ZipEntry(part.entry)); zip.write(content); zip.closeEntry()
                }
            }.toByteArray()
            val archive = spec(bytes, "runtime.aar")
            val store = store()
            store.install(archive) { bytes.inputStream() }
            store.unpackRuntime(archive, listOf(part))
            assertTrue(store.valid(part))
            assertFalse(File(store.directory.parentFile, "escape").exists())
            val wrong = part.copy(sha256 = "0".repeat(64))
            failCode("hash_mismatch") { store.unpackRuntime(archive, listOf(wrong)) }
        }

        // Download resume, wrong range, interruption, partial repair, cancel, redirects, insecure URLs, deadlines.
        run {
            val store = store()
            val part = spec()
            partial(store, part).writeBytes(content.copyOf(400))
            val response = MockResponse(content.copyOfRange(400, 1000).inputStream(), 206, 600, "bytes 400-999/1000")
            AudioAssetDownload({ response }).fetch(store, part, listOf("https://fixture.invalid/file"), AudioDownloadControl())
            assertEquals("bytes=400-", response.getRequestProperty("Range"))
            assertTrue(store.valid(part))
            assertFalse(partial(store, part).exists())
        }
        run {
            val store = store()
            val part = spec()
            partial(store, part).writeBytes(content.copyOf(400))
            AudioAssetDownload({ MockResponse(content.inputStream()) }).fetch(store, part, listOf("https://fixture.invalid/file"), AudioDownloadControl())
            assertArrayEquals(content, store.file(part).readBytes())
        }
        run {
            val store = store()
            val part = spec()
            partial(store, part).writeBytes(content.copyOf(400))
            failCode("range_invalid") {
                AudioAssetDownload({ MockResponse(content.inputStream(), 206, 1000, "bytes 0-999/1000") })
                    .fetch(store, part, listOf("https://fixture.invalid/file"), AudioDownloadControl())
            }
            assertFalse(store.file(part).exists())
            assertFalse(partial(store, part).exists())
        }
        run {
            val store = store()
            val part = spec()
            var calls = 0
            val interrupted = object : InputStream() {
                var at = 0
                override fun read(): Int = if (at < 400) content[at++].toInt() and 255 else throw IOException("offline")
            }
            val fetcher = AudioAssetDownload({
                if (++calls == 1) MockResponse(interrupted)
                else MockResponse(content.copyOfRange(400, 1000).inputStream(), 206, 600, "bytes 400-999/1000")
            })
            fetcher.fetch(store, part, listOf("https://a.invalid/file", "https://b.invalid/file"), AudioDownloadControl())
            assertEquals(2, calls)
            assertTrue(store.valid(part))
        }
        run {
            val store = store()
            val part = spec()
            partial(store, part).writeBytes(ByteArray(1000))
            var calls = 0
            AudioAssetDownload({ calls++; MockResponse(content.inputStream()) })
                .fetch(store, part, listOf("https://a.invalid/file", "https://b.invalid/file"), AudioDownloadControl())
            assertEquals(1, calls)
            assertTrue(store.valid(part))
        }
        run {
            val store = store()
            val part = spec()
            val control = AudioDownloadControl()
            val fetcher = AudioAssetDownload({ MockResponse(content.inputStream()) })
            failCode("cancelled") {
                fetcher.fetch(store, part, listOf("https://a.invalid/file", "https://b.invalid/file"), control, progress = { control.cancel() })
            }
            assertFalse(store.file(part).exists())
            assertEquals(1000, partial(store, part).length().toInt())
        }
        failCode("http_status") {
            AudioAssetDownload({ MockResponse(content.inputStream(), 302) })
                .fetch(store(), spec(), listOf("https://a.invalid/file"), AudioDownloadControl())
        }
        failCode("source_rejected") {
            AudioAssetDownload({ error("no request") })
                .fetch(store(), spec(), listOf("http://a.invalid/file"), AudioDownloadControl())
        }
        failCode("response_size") {
            AudioAssetDownload({ MockResponse(content.inputStream(), length = 900) })
                .fetch(store(), spec(), listOf("https://a.invalid/file"), AudioDownloadControl())
        }
        run {
            var clock = 0L
            failCode("network_deadline") {
                AudioAssetDownload({ MockResponse(content.inputStream()) }, { clock.also { clock += 181_000_000_000L } })
                    .fetch(store(), spec(), listOf("https://a.invalid/file"), AudioDownloadControl())
            }
        }

        // Publication, repair, and retry policy.
        run {
            val store = store()
            val parts = listOf(spec(), spec(content.reversedArray(), "runtime.so"))
            val remote = Remote()
            store.install(parts[0]) { content.inputStream() }
            store.install(parts[1]) { content.reversedArray().inputStream() }
            remote.files[parts[0].remoteName] = content
            remote.failWrite = true
            try {
                AudioAssetPublication.publish(store, parts, remote, AudioDownloadControl())
                fail()
            } catch (_: IOException) {
            }
            assertTrue(parts.all { store.valid(it) })
            assertEquals("remote_invalid", AudioAssetPublication.inspect(parts, remote))
            remote.failWrite = false
            AudioAssetPublication.publish(store, parts, remote, AudioDownloadControl())
            assertEquals(2, remote.writes)
            assertEquals("published", AudioAssetPublication.inspect(parts, remote))
            AudioAssetPublication.publish(store, parts, remote, AudioDownloadControl())
            assertEquals(2, remote.writes)
        }
        run {
            val store = store()
            val part = spec()
            val remote = Remote()
            assertEquals("remote_missing", AudioAssetPublication.inspect(listOf(part), remote))
            failCode("local_missing") { AudioAssetPublication.publish(store, listOf(part), remote, AudioDownloadControl()) }
            assertEquals(0, remote.writes)
        }
        assertEquals(listOf(1000L, 4000L, 15000L, 45000L, null), (0..4).map { AudioPreparationRetry.delay(it, false) })
        assertNull(AudioPreparationRetry.delay(0, true))

        // POSIX 0700 dir and 0400 file permissions for hostAbi & repair existing cache.
        run {
            val bytes = ByteArray(128) { (it * 7).toByte() }
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            val part = AudioAsset("libonnxruntime.so", bytes.size.toLong(), sha)
            val events = mutableListOf<Pair<Path, Int>>()
            val hostStore = AudioAssetStore(temporary.newFolder(), hostAbi = "arm64-v8a") { path, mode ->
                events += path to mode
            }
            assertEquals(1, events.size)
            assertEquals(hostStore.directory.toPath(), events[0].first)
            assertEquals(AudioAssetStore.MODE_DIR, events[0].second)
            hostStore.install(part) { bytes.inputStream() }
            assertTrue(events.any { it.first.fileName.toString().endsWith(".part") && it.second == AudioAssetStore.MODE_FILE })
            events.clear()
            hostStore.install(part) { error("must not re-download valid file") }
            assertEquals(listOf(hostStore.file(part).toPath() to AudioAssetStore.MODE_FILE), events)

            val moduleEvents = mutableListOf<Pair<Path, Int>>()
            val moduleStore = AudioAssetStore(temporary.newFolder(), hostAbi = null) { path, mode ->
                moduleEvents += path to mode
            }
            moduleStore.install(part) { bytes.inputStream() }
            assertTrue(moduleEvents.isEmpty())
            assertTrue(moduleStore.file(part).canWrite())
        }
    }

    @Test
    fun realHttpInterruptionResumesPinnedBytesWithProductionTransfer() {
        val data = ByteArray(32768) { (it % 213).toByte() }
        val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
        val part = AudioAsset("runtime.aar", data.size.toLong(), hash)
        val store = AudioAssetStore(temporary.newFolder())
        val server = InterruptingServer(data, cut = 4096)
        try {
            val download = AudioAssetDownload({ URL("http://127.0.0.1:${server.port}/file").openConnection() as HttpURLConnection })
            download.fetch(store, part, listOf("https://primary.invalid/file", "https://mirror.invalid/file"), AudioDownloadControl())
            assertEquals(listOf<String?>(null, "bytes=4096-"), server.ranges.toList())
            assertNull(server.failure)
            assertTrue(store.valid(part))
        } finally {
            server.close()
        }
    }
}

private class InterruptingServer(private val data: ByteArray, private val cut: Int) : AutoCloseable {
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = listener.localPort
    val ranges = CopyOnWriteArrayList<String?>()
    @Volatile var failure: Throwable? = null
    private val acceptor = thread(isDaemon = true, name = "audio-http-test-acceptor") {
        try {
            while (true) {
                val client = listener.accept()
                thread(isDaemon = true, name = "audio-http-test-exchange") { serve(client) }
            }
        } catch (_: SocketException) {
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
                    try { while (socket.getInputStream().read(ByteArray(256)) >= 0) { } } catch (_: IOException) { }
                } else {
                    val offset = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                    val extra = if (range == null) "" else "Content-Range: bytes $offset-${data.size - 1}/${data.size}\r\n"
                    out.write(
                        head(if (range == null) "200 OK" else "206 Partial Content", (data.size - offset).toLong(), extra)
                            .toByteArray(Charsets.ISO_8859_1)
                    )
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
