package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.assets

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.AudioPreparationRetry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.AudioModelInput
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AudioAssetsTest {
    @get:Rule val temporary = TemporaryFolder(File("build/tmp/audio-assets-tests").apply { mkdirs() })
    private val content = ByteArray(1000) { (it % 251).toByte() }
    private fun spec(data: ByteArray = content, name: String = "model.onnx", entry: String = name) =
        AudioAsset(name, data.size.toLong(), MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }, entry)
    private fun store() = AudioAssetStore(temporary.newFolder())
    private fun fail(code: String, work: () -> Unit) {
        try { work(); fail("expected $code") } catch (failure: AudioAssetFailure) { assertEquals(code, failure.code) }
    }
    private fun partial(store: AudioAssetStore, part: AudioAsset) = File(store.directory, "${part.remoteName}.resume")

    @Test fun `bounded verification rejects short long and changed bytes`() {
        fail("size_mismatch") { AudioAssetStore.verify(spec(), content.copyOf(999).inputStream()) }
        fail("size_overflow") { AudioAssetStore.verify(spec(), content.copyOf(1001).inputStream()) }
        fail("hash_mismatch") { AudioAssetStore.verify(spec(), ByteArray(1000).inputStream()) }
        AudioAssetStore.verify(spec(), content.inputStream())
    }

    @Test fun `the direct ORT input contains exactly the bytes verified on this open`() {
        val part = spec()
        val file = temporary.newFile()
        file.writeBytes(content)
        val input = ByteBuffer.allocateDirect(content.size)
        AudioModelInput.readVerified(file, part, input)
        assertArrayEquals(content, ByteArray(input.remaining()).also { input.get(it) })
        file.writeBytes(content.copyOf().also { it[0] = 42 })
        input.clear()
        fail("hash_mismatch") { AudioModelInput.readVerified(file, part, input) }
        file.appendBytes(byteArrayOf(10))
        input.clear()
        fail("size_overflow") { AudioModelInput.readVerified(file, part, input) }
    }
    @Test fun `failed staged install cannot replace an existing file and removes staging`() {
        val store = store(); val part = spec()
        store.file(part).writeBytes(byteArrayOf(99))
        fail("size_mismatch") { store.install(part) { content.copyOf(20).inputStream() } }
        assertArrayEquals(byteArrayOf(99), store.file(part).readBytes())
        assertFalse(store.directory.listFiles()!!.any { it.name.endsWith(".part") })
        store.install(part) { content.inputStream() }
        assertTrue(store.valid(part))
    }
    @Test fun `verified cache is reusable offline and corrupt bytes self repair`() {
        val store = store(); val part = spec()
        store.install(part) { content.inputStream() }
        store.install(part) { error("must not open remote") }
        store.file(part).writeBytes(ByteArray(content.size))
        store.install(part) { content.inputStream() }
        assertTrue(store.valid(part))
    }
    @Test fun `process death leftovers are cleaned without losing resume data`() {
        val store = store(); val stale = File(store.directory, ".audio-old.part").apply { writeText("partial") }
        val resume = partial(store, spec()).apply { writeText("keep") }
        store.locked { assertFalse(stale.exists()); assertTrue(resume.exists()) }
    }
    @Test fun `cache lease excludes overlapping publication`() {
        val store = store()
        store.locked { fail("busy") { store.locked {} } }
        store.locked { assertTrue(true) }
    }
    @Test fun `concurrent host staging has unique paths and preserves fixed identity`() {
        val root = temporary.newFolder(); val part = spec()
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
        } finally { workers.shutdownNow() }
    }
    @Test fun `archive only extracts fixed entries and rejects altered entry content`() {
        val part = spec(entry = "jni/arm64-v8a/model.onnx")
        val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("../../escape")); zip.write(byteArrayOf(1)); zip.closeEntry()
            zip.putNextEntry(ZipEntry(part.entry)); zip.write(content); zip.closeEntry()
        } }.toByteArray()
        val archive = spec(bytes, "runtime.aar"); val store = store()
        store.install(archive) { bytes.inputStream() }; store.unpackRuntime(archive, listOf(part))
        assertTrue(store.valid(part)); assertFalse(File(store.directory.parentFile, "escape").exists())
        val wrong = part.copy(sha256 = "0".repeat(64))
        fail("hash_mismatch") { store.unpackRuntime(archive, listOf(wrong)) }
    }
    private class Response(private val body: InputStream, private val status: Int = 200, private val length: Long = 1000,
                           private val range: String? = null) : HttpURLConnection(URL("https://fixture.invalid/file")) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = body
        override fun getContentLengthLong() = length
        override fun getHeaderField(name: String?) = if (name == "Content-Range") range else null
    }
    @Test fun `resume validates range and hashes complete file`() {
        val store = store(); val part = spec(); partial(store, part).writeBytes(content.copyOf(400))
        val response = Response(content.copyOfRange(400, 1000).inputStream(), 206, 600, "bytes 400-999/1000")
        AudioAssetDownload({ response }).fetch(store, part, listOf("https://fixture.invalid/file"), AudioDownloadControl())
        assertEquals("bytes=400-", response.getRequestProperty("Range")); assertTrue(store.valid(part)); assertFalse(partial(store, part).exists())
    }
    @Test fun `server ignoring range restarts without appending duplicate bytes`() {
        val store = store(); val part = spec(); partial(store, part).writeBytes(content.copyOf(400))
        AudioAssetDownload({ Response(content.inputStream()) }).fetch(store, part, listOf("https://fixture.invalid/file"), AudioDownloadControl())
        assertArrayEquals(content, store.file(part).readBytes())
    }
    @Test fun `wrong range never produces a published artifact`() {
        val store = store(); val part = spec(); partial(store, part).writeBytes(content.copyOf(400))
        fail("range_invalid") { AudioAssetDownload({ Response(content.inputStream(), 206, 1000, "bytes 0-999/1000") })
            .fetch(store, part, listOf("https://fixture.invalid/file"), AudioDownloadControl()) }
        assertFalse(store.file(part).exists()); assertFalse(partial(store, part).exists())
    }
    @Test fun `interrupted transfer resumes from another source and verifies everything`() {
        val store = store(); val part = spec(); var calls = 0
        val interrupted = object : InputStream() {
            var at = 0
            override fun read(): Int = if (at < 400) content[at++].toInt() and 255 else throw java.io.IOException("offline")
        }
        val fetcher = AudioAssetDownload({ if (++calls == 1) Response(interrupted)
            else Response(content.copyOfRange(400, 1000).inputStream(), 206, 600, "bytes 400-999/1000") })
        fetcher.fetch(store, part, listOf("https://a.invalid/file", "https://b.invalid/file"), AudioDownloadControl())
        assertEquals(2, calls); assertTrue(store.valid(part))
    }
    @Test fun `corrupt completed partial is removed before fallback download`() {
        val store = store(); val part = spec(); partial(store, part).writeBytes(ByteArray(1000))
        var calls = 0
        AudioAssetDownload({ calls++; Response(content.inputStream()) })
            .fetch(store, part, listOf("https://a.invalid/file", "https://b.invalid/file"), AudioDownloadControl())
        assertEquals(1, calls); assertTrue(store.valid(part))
    }
    @Test fun `cancellation keeps resume and never starts a mirror`() {
        val store = store(); val part = spec(); val control = AudioDownloadControl()
        val fetcher = AudioAssetDownload({ Response(content.inputStream()) })
        fail("cancelled") { fetcher.fetch(store, part, listOf("https://a.invalid/file", "https://b.invalid/file"), control,
            progress = { control.cancel() }) }
        assertFalse(store.file(part).exists()); assertEquals(1000, partial(store, part).length().toInt())
    }
    @Test fun `redirect status fails closed instead of following untrusted locations`() {
        fail("http_status") { AudioAssetDownload({ Response(content.inputStream(), 302) })
            .fetch(store(), spec(), listOf("https://a.invalid/file"), AudioDownloadControl()) }
    }
    @Test fun `insecure sources and wrong response lengths are rejected`() {
        fail("source_rejected") { AudioAssetDownload({ error("no request") })
            .fetch(store(), spec(), listOf("http://a.invalid/file"), AudioDownloadControl()) }
        fail("response_size") { AudioAssetDownload({ Response(content.inputStream(), length = 900) })
            .fetch(store(), spec(), listOf("https://a.invalid/file"), AudioDownloadControl()) }
    }
    @Test fun `slow successful reads cannot exceed overall deadline`() {
        var clock = 0L
        fail("network_deadline") { AudioAssetDownload({ Response(content.inputStream()) }, { clock.also { clock += 181_000_000_000L } })
            .fetch(store(), spec(), listOf("https://a.invalid/file"), AudioDownloadControl()) }
    }
    private class Remote : AudioRemoteFiles {
        val files = mutableMapOf<String, ByteArray>(); var failWrite = false; var writes = 0
        override fun names() = files.keys.toSet()
        override fun open(name: String) = ByteArrayInputStream(files.getValue(name))
        override fun write(name: String, copy: (java.io.OutputStream) -> Unit) {
            writes++
            val output = ByteArrayOutputStream(); copy(output)
            files[name] = if (failWrite) output.toByteArray().copyOf(10) else output.toByteArray()
            if (failWrite) throw java.io.IOException("framework died")
        }
    }
    @Test fun `failed publication preserves local files then reconnect repairs only invalid remote`() {
        val store = store(); val parts = listOf(spec(), spec(content.reversedArray(), "runtime.so")); val remote = Remote()
        store.install(parts[0]) { content.inputStream() }; store.install(parts[1]) { content.reversedArray().inputStream() }
        remote.files[parts[0].remoteName] = content; remote.failWrite = true
        try { AudioAssetPublication.publish(store, parts, remote, AudioDownloadControl()); fail() } catch (_: java.io.IOException) {}
        assertTrue(parts.all { store.valid(it) }); assertEquals("remote_invalid", AudioAssetPublication.inspect(parts, remote))
        remote.failWrite = false
        AudioAssetPublication.publish(store, parts, remote, AudioDownloadControl())
        assertEquals(2, remote.writes); assertEquals("published", AudioAssetPublication.inspect(parts, remote))
        AudioAssetPublication.publish(store, parts, remote, AudioDownloadControl()); assertEquals(2, remote.writes)
    }
    @Test fun `publication requires every local and remote part`() {
        val store = store(); val part = spec(); val remote = Remote()
        assertEquals("remote_missing", AudioAssetPublication.inspect(listOf(part), remote))
        fail("local_missing") { AudioAssetPublication.publish(store, listOf(part), remote, AudioDownloadControl()) }
        assertEquals(0, remote.writes)
    }
    @Test fun `host retry ends after bounded recovery and never retries native initialisation`() {
        assertEquals(listOf(1000L, 4000L, 15000L, 45000L, null), (0..4).map { AudioPreparationRetry.delay(it, false) })
        assertNull(AudioPreparationRetry.delay(0, true))
    }
}
