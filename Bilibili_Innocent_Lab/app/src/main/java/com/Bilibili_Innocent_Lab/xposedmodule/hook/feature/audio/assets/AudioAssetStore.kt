package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.assets

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.payload.ArchiveVerifier
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.payload.Elf16KbVerifier
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.payload.PayloadVerifyException
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.payload.SecurePayloadStore
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.zip.ZipFile

/** Only fixed filenames under an app-owned root. All writers use unique temporary files + atomic replacement. */
internal class AudioAssetStore(
    root: File,
    private val hostAbi: String? = null,
    private val posixChmod: ((Path, Int) -> Unit)? = null
) {
    val directory: File
    init {
        require(hostAbi == null || hostAbi in AudioAssetSpec.native)
        val subdir = "bil-audio-${AudioAssetSpec.ID}" + (hostAbi?.let { "-$it" } ?: "")
        directory = SecurePayloadStore.prepareDirectory(
            root = root,
            relativePath = subdir,
            mode = MODE_DIR,
            enforcePermissions = hostAbi != null,
            posixChmod = posixChmod
        ).getOrElse { failure ->
            throw mapStoreFailure(failure, subdir, defaultCode = "unsafe_directory")
        }
    }
    fun file(part: AudioAsset) = File(directory, if (hostAbi == null) part.remoteName else part.name)
    fun valid(part: AudioAsset): Boolean = valid(part, file(part))
    fun valid(part: AudioAsset, file: File): Boolean = runCatching {
        SecurePayloadStore.isSafeRegularFile(file) && file.length() == part.size &&
            Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { verify(part, it); true } &&
            isValidNativeElf(part, file)
    }.getOrDefault(false)

    fun install(part: AudioAsset, input: () -> InputStream): File {
        return SecurePayloadStore.installVerified(
            directory = directory,
            targetFileName = if (hostAbi == null) part.remoteName else part.name,
            expectedSize = part.size,
            expectedSha256 = part.sha256,
            input = input,
            tempPrefix = ".audio-",
            tempSuffix = ".part",
            enforcePermissions = hostAbi != null,
            fileMode = MODE_FILE,
            posixChmod = posixChmod,
            publicationLock = publicationLock,
            isExistingValid = { existing -> valid(part, existing) },
            checkCancelled = {
                if (Thread.currentThread().isInterrupted) throw AudioAssetFailure("cancelled", part.name)
            },
            validateStagedFile = { staged -> verifyNativeElfIfRequired(part, staged.toFile()) }
        ).getOrElse { failure ->
            throw mapStoreFailure(failure, part.name)
        }
    }

    private fun isValidNativeElf(part: AudioAsset, file: File): Boolean = runCatching {
        verifyNativeElfIfRequired(part, file)
        true
    }.getOrDefault(false)

    private fun verifyNativeElfIfRequired(part: AudioAsset, file: File) {
        if (!part.name.endsWith(".so")) return
        val isArm64Entry = part.entry.startsWith("jni/arm64-v8a/") || part.entry.startsWith("lib/arm64-v8a/")
        val isArm32Entry = part.entry.startsWith("jni/armeabi-v7a/") || part.entry.startsWith("lib/armeabi-v7a/")
        val checkResult = when {
            isArm64Entry -> Elf16KbVerifier.verifyElf64FilePageAlignment(file, part.name)
            isArm32Entry -> Elf16KbVerifier.verifyElf32FilePageAlignment(file, part.name)
            else -> {
                val elfClass = Elf16KbVerifier.readElfClassIfElf(file) ?: return
                when {
                    hostAbi == "arm64-v8a" -> Elf16KbVerifier.verifyElf64FilePageAlignment(file, part.name)
                    hostAbi == "armeabi-v7a" -> Elf16KbVerifier.verifyElf32FilePageAlignment(file, part.name)
                    elfClass == 2 -> Elf16KbVerifier.verifyElf64FilePageAlignment(file, part.name)
                    elfClass == 1 -> Elf16KbVerifier.verifyElf32FilePageAlignment(file, part.name)
                    else -> Result.failure(IllegalArgumentException("${part.name} has unsupported ELF class=$elfClass"))
                }
            }
        }
        checkResult.getOrElse { failure ->
            throw AudioAssetFailure("elf_alignment_invalid", part.name, cause = failure)
        }
    }

    /** Fixed archive entries only; never extract archive-supplied paths or dex/classes. */
    fun unpackRuntime(archive: AudioAsset = AudioAssetSpec.runtime, parts: List<AudioAsset> = AudioAssetSpec.native.values.flatten()) {
        if (!valid(archive)) throw AudioAssetFailure("archive_invalid")
        ZipFile(file(archive)).use { zip ->
            for (part in parts) {
                if (!ArchiveVerifier.isSafeRelativePath(part.entry)) {
                    throw AudioAssetFailure("archive_entry_invalid", part.name)
                }
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
        internal const val MODE_FILE = SecurePayloadStore.MODE_FILE_READONLY
        internal const val MODE_DIR = SecurePayloadStore.MODE_DIR_PRIVATE
        private val processLock = Any()
        private val publicationLock = Any()

        private fun mapStoreFailure(
            failure: Throwable,
            partName: String,
            defaultCode: String? = null
        ): Throwable {
            if (failure is AudioAssetFailure) return failure
            if (failure is PayloadVerifyException) {
                return when (failure.code) {
                    "chmod_failed" -> AudioAssetFailure("chmod_failed", failure.detail.ifEmpty { partName }, cause = failure.cause ?: failure)
                    "cancelled", "size_overflow", "size_mismatch", "hash_mismatch" ->
                        AudioAssetFailure(failure.code, partName, failure.detail, failure.cause)
                    else -> AudioAssetFailure(defaultCode ?: failure.code, partName, failure.detail, failure.cause ?: failure)
                }
            }
            return if (defaultCode != null) AudioAssetFailure(defaultCode, partName, cause = failure) else failure
        }

        fun verify(part: AudioAsset, input: InputStream, output: OutputStream? = null, progress: (Long) -> Unit = {}) {
            try {
                SecurePayloadStore.verifyStream(
                    input = input,
                    expectedSize = part.size,
                    expectedSha256 = part.sha256,
                    output = output,
                    checkCancelled = {
                        if (Thread.currentThread().isInterrupted) throw AudioAssetFailure("cancelled", part.name)
                    },
                    progress = progress
                )
            } catch (failure: PayloadVerifyException) {
                throw mapStoreFailure(failure, part.name)
            }
        }
    }
}
