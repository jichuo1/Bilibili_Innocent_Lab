package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** Produces a private copy. The official archive, extracted files and download metadata are never edited. */
internal class SubtitlePackagePatcher(private val runtime: String) {
    private data class Stamp(val path: String, val size: Long, val modified: Long, val styleSize: Long, val styleModified: Long)
    private val prepared = LinkedHashMap<Stamp, File>()
    @Volatile var lastFailure: String? = null
        private set

    fun cached(source: File, style: File): File? {
        val key = stamp(source, style)
        // Main-thread lookups must not wait for the worker's archive/font compression lock.
        return synchronized(prepared) { prepared[key] }?.takeIf(File::isFile)
    }

    /** Only call on a worker; cache misses must never rewrite an archive on the Android main thread. */
    @Synchronized fun prepare(source: File, styleFile: File, outputDirectory: File): File? = runCatching {
        cached(source, styleFile)?.let { return it }
        synchronized(prepared) { if (prepared.size >= 32) prepared.remove(prepared.keys.first()) }
        if (!source.isFile || source.length() !in 1..MAX_ARCHIVE) return null
        lastFailure = "archive"
        val before = stamp(source, styleFile)
        ZipFile(source).use { zip ->
            val entries = zip.entries().asSequence().take(MAX_ENTRIES + 1).toList()
            if (entries.size > MAX_ENTRIES || entries.map { it.name }.distinct().size != entries.size ||
                entries.any { !safeEntry(it.name) || it.name.startsWith("res/${SubtitleStylePayload.DIRECTORY}/") }) return null
            lastFailure = "identity"
            val info = JSONObject(zip.getInputStream(zip.getEntry("info.json") ?: return null).use { it.boundedBytes(8192) }.toString(Charsets.UTF_8))
            if (info.optString("name") != "danmaku-flame-master" ||
                !SUBTITLE_VERSION_REGEX.matches(info.optString("version")) ||
                info.has("innocent_subtitle_adapter")) return null
            lastFailure = "worker"
            val entry = zip.getEntry(SubtitleScriptAdapter.WORKER) ?: return null
            val worker = zip.getInputStream(entry).use { it.boundedBytes(4_000_000) }.toString(Charsets.UTF_8)
            lastFailure = "script-abi"
            val patched = SubtitleScriptAdapter.patch(worker, runtime, SubtitleStylePayload.CONFIG) ?: return null
            lastFailure = "source-digest"
            val originalHash = source.inputStream().use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= MAX_ARCHIVE)
                    digest.update(buffer, 0, count)
                }
                digest.digest().hex()
            }
            val payload = SubtitleStylePayload.read(styleFile)
            val key = hash(originalHash + patched + payload.identity)
            // A distinct commit also isolates the native extraction cache from the official package.
            info.put("commit", key.take(40))
            info.put("innocent_subtitle_adapter", 1)
            val patchedBytes = patched.toByteArray(Charsets.UTF_8)
            if (stamp(source, styleFile) != before) return null
            lastFailure = "private-copy"
            check(outputDirectory.isDirectory || outputDirectory.mkdirs())
            val destination = File(outputDirectory, "$key.zip")
            if (!validCached(destination, patchedBytes, payload)) {
                val temporary = File.createTempFile("subtitle-", ".tmp", outputDirectory)
                try {
                    ZipOutputStream(temporary.outputStream().buffered()).use { output ->
                        var total = 0L
                        val buffer = ByteArray(32 * 1024)
                        for (old in entries) {
                            output.putNextEntry(ZipEntry(old.name).apply { time = 0 })
                            when (old.name) {
                                SubtitleScriptAdapter.WORKER -> output.write(patchedBytes)
                                "info.json" -> output.write(info.toString().toByteArray(Charsets.UTF_8))
                                else -> if (!old.isDirectory) zip.getInputStream(old).use { input ->
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        total += count
                                        check(total <= MAX_EXPANDED)
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                            output.closeEntry()
                        }
                        output.putNextEntry(ZipEntry("res/${SubtitleStylePayload.CONFIG}"))
                        output.write(payload.json.toByteArray(Charsets.UTF_8)); output.closeEntry()
                        payload.font?.let { font ->
                            output.putNextEntry(ZipEntry("res/${payload.fontEntry}"))
                            font.inputStream().use { it.copyTo(output) }; output.closeEntry()
                        }
                    }
                    check(stamp(source, styleFile) == before && validCached(temporary, patchedBytes, payload))
                    check(temporary.renameTo(destination))
                } finally { temporary.delete() }
            }
            lastFailure = null
            synchronized(prepared) { prepared[before] = destination }
            destination
        }
    }.getOrNull()

    private fun stamp(file: File, style: File) = Stamp(file.absolutePath, file.length(), file.lastModified(), style.length(), style.lastModified())
    private fun validCached(file: File, expected: ByteArray, payload: SubtitleStylePayload): Boolean = runCatching {
        if (!file.isFile || file.length() !in 1..80_000_000) return false
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(SubtitleScriptAdapter.WORKER) ?: return false
            if (!zip.getInputStream(entry).use { it.boundedBytes(4_100_000) }.contentEquals(expected)) return false
            val config = zip.getEntry("res/${SubtitleStylePayload.CONFIG}") ?: return false
            if (zip.getInputStream(config).use { it.boundedBytes(2048) }.toString(Charsets.UTF_8) != payload.json) return false
            payload.fontEntry == null || zip.getEntry("res/${payload.fontEntry}")?.size == payload.font?.length()
        }
    }.getOrDefault(false)

    companion object {
        private val SUBTITLE_VERSION_REGEX = Regex("""3\.\d+\.\d+""")
        private const val MAX_ARCHIVE = 8_000_000L
        private const val MAX_EXPANDED = 64_000_000L
        private const val MAX_ENTRIES = 4096
        internal fun safeEntry(name: String): Boolean = name.length in 1..512 &&
            !name.startsWith('/') && '\\' !in name && ':' !in name && name.none { it.code < 32 } &&
            name.trimEnd('/').split('/').all { it.isNotEmpty() && it != "." && it != ".." }
        private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).hex()
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

internal fun InputStream.boundedBytes(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (count < 0) return output.toByteArray()
        output.write(buffer, 0, count)
        check(output.size() <= limit) { "input-too-large" }
    }
}
