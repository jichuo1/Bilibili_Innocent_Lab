package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.assets

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Only fixed filenames under an app-owned root. All writers use unique temporary files + atomic replacement. */
internal class AudioAssetStore(root: File, private val hostAbi: String? = null) {
    val directory: File
    init {
        val parent = root.canonicalFile.toPath()
        Files.createDirectories(parent)
        require(hostAbi == null || hostAbi in AudioAssetSpec.native)
        val path = parent.resolve("bil-audio-${AudioAssetSpec.ID}" + (hostAbi?.let { "-$it" } ?: ""))
        Files.createDirectories(path)
        if (Files.isSymbolicLink(path) || path.toRealPath().parent != parent) throw AudioAssetFailure("unsafe_directory")
        directory = path.toFile()
    }
    fun file(part: AudioAsset) = File(directory, if (hostAbi == null) part.remoteName else part.name)
    fun valid(part: AudioAsset): Boolean = valid(part, file(part))
    fun valid(part: AudioAsset, file: File): Boolean = runCatching {
        Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() == part.size &&
            Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { verify(part, it); true }
    }.getOrDefault(false)

    fun install(part: AudioAsset, input: () -> InputStream): File {
        val target = file(part)
        if (valid(part)) return target
        val temporary = Files.createTempFile(directory.toPath(), ".audio-", ".part")
        try {
            input().use { source ->
                FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { channel ->
                    val output = java.nio.channels.Channels.newOutputStream(channel)
                    verify(part, source, output)
                    channel.force(true)
                }
            }
            synchronized(publicationLock) {
                // Another writer may have published the verified asset while this copy was prepared.
                // Avoid replacing that file while its caller is already reading it.
                if (!valid(part)) Files.move(temporary, target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            }
            return target
        } finally { Files.deleteIfExists(temporary) }
    }

    /** Fixed archive entries only; never extract archive-supplied paths or dex/classes. */
    fun unpackRuntime(archive: AudioAsset = AudioAssetSpec.runtime, parts: List<AudioAsset> = AudioAssetSpec.native.values.flatten()) {
        if (!valid(archive)) throw AudioAssetFailure("archive_invalid")
        ZipFile(file(archive)).use { zip ->
            for (part in parts) {
                val entry = zip.getEntry(part.entry) ?: throw AudioAssetFailure("archive_entry_missing", part.name)
                if (entry.size != part.size) throw AudioAssetFailure("archive_entry_size", part.name)
                install(part) { zip.getInputStream(entry) }
            }
        }
    }

    /** Short nonblocking cross-process lease: no UI thread or second process waits behind a network request. */
    fun <T> locked(work: () -> T): T = synchronized(processLock) {
        FileChannel.open(File(directory, "transfer.lock").toPath(), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            val lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
            (lock ?: throw AudioAssetFailure("busy")).use {
                // A killed process cannot run finally. Only clean our unique staging files under the lease.
                directory.listFiles()?.filter { (it.name.startsWith(".audio-") || it.name.startsWith(".import-")) && it.name.endsWith(".part") }
                    ?.forEach { Files.deleteIfExists(it.toPath()) }
                work()
            }
        }
    }

    companion object {
        private val processLock = Any()
        private val publicationLock = Any()
        fun verify(part: AudioAsset, input: InputStream, output: OutputStream? = null, progress: (Long) -> Unit = {}) {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                if (Thread.currentThread().isInterrupted) throw AudioAssetFailure("cancelled", part.name)
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), part.size - total + 1).toInt())
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > part.size) throw AudioAssetFailure("size_overflow", part.name, "bytes=$total")
                digest.update(buffer, 0, count)
                output?.write(buffer, 0, count)
                progress(total)
            }
            if (total != part.size) throw AudioAssetFailure("size_mismatch", part.name, "bytes=$total expected=${part.size}")
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            if (hash != part.sha256) throw AudioAssetFailure("hash_mismatch", part.name, "actual=$hash expected=${part.sha256}")
        }
    }
}
