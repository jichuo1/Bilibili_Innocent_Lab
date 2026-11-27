package com.Bilibili_Innocent_Lab.xposedmodule.runtime.payload

import android.system.ErrnoException
import android.system.Os
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

/**
 * 纯字节 ELF64 16KB 页对齐校验器，供动态原生库加载前预检与打包后核验共用。
 */
internal object Elf16KbVerifier {
    internal const val PAGE_SIZE_16K = 16384L
    internal const val DEFAULT_MAX_ELF_BYTES = 64L * 1024L * 1024L

    private const val ELF_HEADER_MIN_BYTES = 64
    private const val PHDR_MIN_BYTES = 56
    private const val ELF_CLASS_64 = 2.toByte()
    private const val ELF_DATA_LSB = 1.toByte()
    private const val PT_LOAD = 1L

    /**
     * 校验 [bytes] 为小端 ELF64 二进制，program header 表不越界，至少存在一个 `PT_LOAD` 段，
     * 且每个 `PT_LOAD` 段的 `p_align >= 16384 && p_align % 16384 == 0`。
     */
    fun verifyElf64PageAlignment(bytes: ByteArray, label: String): Result<Unit> = runCatching {
        require(bytes.size >= ELF_HEADER_MIN_BYTES) {
            "$label is too small for ELF64 header (${bytes.size})"
        }
        require(
            bytes[0] == 0x7f.toByte() &&
                bytes[1] == 'E'.code.toByte() &&
                bytes[2] == 'L'.code.toByte() &&
                bytes[3] == 'F'.code.toByte()
        ) {
            "$label is not an ELF binary"
        }
        require(bytes[4] == ELF_CLASS_64 && bytes[5] == ELF_DATA_LSB) {
            "$label must be little-endian ELF64"
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val phoff = buf.getLong(32)
        val phentsize = buf.getShort(54).toInt() and 0xFFFF
        val phnum = buf.getShort(56).toInt() and 0xFFFF
        require(phentsize >= PHDR_MIN_BYTES && phnum > 0) {
            "$label has invalid ELF64 program header dimensions (phentsize=$phentsize, phnum=$phnum)"
        }
        val tableBytes = phnum.toLong() * phentsize.toLong()
        val totalBytes = bytes.size.toLong()
        require(phoff >= ELF_HEADER_MIN_BYTES.toLong() && tableBytes <= totalBytes && phoff <= totalBytes - tableBytes) {
            "$label has invalid ELF64 program headers (phoff=$phoff, table=$tableBytes, size=$totalBytes)"
        }
        var loadSegments = 0
        for (i in 0 until phnum) {
            val off = (phoff + i.toLong() * phentsize.toLong()).toInt()
            val pType = buf.getInt(off).toLong() and 0xFFFF_FFFFL
            if (pType == PT_LOAD) {
                loadSegments++
                val pAlign = buf.getLong(off + 48)
                require(pAlign >= PAGE_SIZE_16K && pAlign % PAGE_SIZE_16K == 0L) {
                    "$label PT_LOAD[$i] p_align=$pAlign is not 16KB aligned"
                }
            }
        }
        require(loadSegments > 0) { "$label has no PT_LOAD segments" }
    }

    /**
     * 以 `NOFOLLOW_LINKS` + 有界读取校验本地 ELF64 文件的 16KB 页对齐属性。
     */
    fun verifyElf64FilePageAlignment(
        file: File,
        label: String = file.name,
        maxBytes: Long = DEFAULT_MAX_ELF_BYTES
    ): Result<Unit> = runCatching {
        require(maxBytes >= ELF_HEADER_MIN_BYTES) { "maxBytes must be >= $ELF_HEADER_MIN_BYTES" }
        require(SecurePayloadStore.isSafeRegularFile(file)) { "$label is not a regular non-symlink file" }
        val fileLen = file.length()
        require(fileLen in ELF_HEADER_MIN_BYTES..maxBytes) {
            "$label length $fileLen outside [$ELF_HEADER_MIN_BYTES, $maxBytes]"
        }
        val out = ByteArrayOutputStream(fileLen.toInt())
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
            while (true) {
                val remaining = maxBytes - total
                val maxRead = if (remaining < buffer.size.toLong()) (remaining + 1L).toInt() else buffer.size
                val count = input.read(buffer, 0, maxRead)
                if (count < 0) break
                if (count == 0) continue
                total += count
                require(total <= maxBytes) { "$label exceeded maxBytes=$maxBytes while reading" }
                out.write(buffer, 0, count)
            }
        }
        verifyElf64PageAlignment(out.toByteArray(), label).getOrThrow()
    }
}

/**
 * 归档安全校验上限配置。
 */
internal data class ZipLimits(
    val maxEntries: Int = 256,
    val maxEntryBytes: Long = 8L * 1024L * 1024L,
    val maxTotalBytes: Long = 32L * 1024L * 1024L,
    val maxCompressionRatio: Double = 50.0,
    val forbiddenExtensions: Set<String> = DEFAULT_FORBIDDEN_EXTENSIONS
) {
    init {
        require(maxEntries > 0) { "maxEntries must be > 0" }
        require(maxEntryBytes >= 0L) { "maxEntryBytes must be >= 0" }
        require(maxTotalBytes >= 0L) { "maxTotalBytes must be >= 0" }
        require(maxCompressionRatio > 0.0 && maxCompressionRatio.isFinite()) {
            "maxCompressionRatio must be finite and > 0"
        }
    }

    companion object {
        val DEFAULT_FORBIDDEN_EXTENSIONS: Set<String> = setOf(".dex", ".so", ".jar", ".apk")

        val TIER_B_DEFAULTS = ZipLimits(
            maxEntries = 128,
            maxEntryBytes = 4L * 1024L * 1024L,
            maxTotalBytes = 4L * 1024L * 1024L,
            maxCompressionRatio = 50.0,
            forbiddenExtensions = DEFAULT_FORBIDDEN_EXTENSIONS
        )
    }
}

/**
 * 经过归档校验的文件条目摘要（真实解压字节数 + SHA-256）。
 */
internal data class VerifiedZipEntry(
    val name: String,
    val size: Long,
    val compressedSize: Long,
    val sha256: String
)

/**
 * ZIP 归档安全校验器：防御路径穿越、条目/体积膨胀、Zip Bomb 与危险载荷扩展名。
 */
internal object ArchiveVerifier {
    fun isSafeRelativePath(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name.indexOf('\u0000') >= 0) return false
        if (name.startsWith("/") || name.endsWith("/")) return false
        if (name.contains('\\')) return false
        if (name.length >= 2 && name[1] == ':' && name[0].isLetter()) return false
        val segments = name.split('/')
        return segments.all { segment -> segment.isNotEmpty() && segment != "." && segment != ".." }
    }

    /**
     * 校验 [zipFile] 全部条目路径、数量、单条目与总字节上限、压缩比及禁用扩展名，
     * 并流式计算每个文件条目的精确字节数与 SHA-256。
     */
    fun verifyZip(zipFile: File, limits: ZipLimits = ZipLimits()): Result<List<VerifiedZipEntry>> = runCatching {
        require(SecurePayloadStore.isSafeRegularFile(zipFile)) {
            "Zip archive is missing or not a regular non-symlink file: ${zipFile.name}"
        }
        val archiveLength = zipFile.length()
        require(archiveLength > 0L) { "Zip archive is empty: ${zipFile.name}" }
        val normalizedForbidden = limits.forbiddenExtensions
            .map { raw ->
                val lower = raw.trim().lowercase(Locale.ROOT)
                if (lower.startsWith(".")) lower else ".$lower"
            }
            .filter { it.length > 1 }
            .toSet()

        val verified = ArrayList<VerifiedZipEntry>()
        val seenNames = HashSet<String>()
        val buffer = ByteArray(64 * 1024)
        val digest = MessageDigest.getInstance("SHA-256")
        var entryCount = 0
        var totalBytes = 0L

        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entryCount++
                require(entryCount <= limits.maxEntries) {
                    "Zip entry count exceeds limit ${limits.maxEntries}"
                }
                val name = entry.name
                require(seenNames.add(name)) { "Duplicate zip entry: $name" }

                if (entry.isDirectory) {
                    require(name.endsWith("/")) { "Invalid zip directory entry: $name" }
                    val trimmed = name.dropLast(1)
                    require(trimmed.isNotEmpty() && isSafeRelativePath(trimmed)) {
                        "Unsafe zip directory path: $name"
                    }
                    continue
                }

                require(isSafeRelativePath(name)) { "Unsafe zip entry path: $name" }
                val lowerName = name.lowercase(Locale.ROOT)
                require(normalizedForbidden.none { lowerName.endsWith(it) }) {
                    "Forbidden zip entry extension: $name"
                }

                val compressedSize = entry.compressedSize
                require(compressedSize in 0L..archiveLength) {
                    "Invalid compressed size for $name: $compressedSize"
                }
                val declaredSize = entry.size
                if (declaredSize >= 0L) {
                    require(declaredSize <= limits.maxEntryBytes) {
                        "Zip entry $name declared size $declaredSize exceeds ${limits.maxEntryBytes}"
                    }
                }

                digest.reset()
                var entryBytes = 0L
                zip.getInputStream(entry).use { input ->
                    while (true) {
                        val remainingEntry = limits.maxEntryBytes - entryBytes
                        val remainingTotal = limits.maxTotalBytes - totalBytes
                        val remaining = minOf(remainingEntry, remainingTotal)
                        val maxRead = if (remaining < buffer.size.toLong()) (remaining + 1L).toInt() else buffer.size
                        val count = input.read(buffer, 0, maxRead)
                        if (count < 0) break
                        if (count == 0) continue
                        entryBytes += count
                        totalBytes += count
                        require(entryBytes <= limits.maxEntryBytes) {
                            "Zip entry $name exceeds maxEntryBytes=${limits.maxEntryBytes}"
                        }
                        require(totalBytes <= limits.maxTotalBytes) {
                            "Zip archive exceeds maxTotalBytes=${limits.maxTotalBytes}"
                        }
                        if (entryBytes > 0L) {
                            require(compressedSize > 0L) {
                                "Zip entry $name has non-empty payload with compressedSize=$compressedSize"
                            }
                            val ratio = entryBytes.toDouble() / compressedSize.toDouble()
                            require(ratio <= limits.maxCompressionRatio) {
                                "Zip entry $name compression ratio $ratio exceeds ${limits.maxCompressionRatio}"
                            }
                        }
                        digest.update(buffer, 0, count)
                    }
                }
                if (declaredSize >= 0L) {
                    require(entryBytes == declaredSize) {
                        "Zip entry $name size mismatch: actual=$entryBytes declared=$declaredSize"
                    }
                }
                verified += VerifiedZipEntry(
                    name = name,
                    size = entryBytes,
                    compressedSize = compressedSize,
                    sha256 = SecurePayloadStore.sha256Hex(digest.digest())
                )
            }
        }
        verified
    }
}

/**
 * 载荷存储与流校验错误码，避免暴露私有绝对路径或敏感信息。
 */
internal class PayloadVerifyException(
    val code: String,
    val detail: String = "",
    cause: Throwable? = null
) : IOException(if (detail.isEmpty()) code else "$code:$detail", cause)

/**
 * 流式校验摘要结果。
 */
internal data class VerifiedStreamDigest(
    val size: Long,
    val sha256: String
)

/**
 * 安全载荷存储与原子落地底座：
 * - `NOFOLLOW_LINKS` 规范化目录与文件检查，拒绝符号链接替换；
 * - 统一 `0700` 目录 / `0400` 文件权限设置（兼容 Android `Os.chmod`、JVM POSIX 与 Windows）；
 * - 有界流式 SHA-256 核验；
 * - 唯一临时文件 + `FileChannel.force(true)` + `ATOMIC_MOVE` 原子替换。
 */
internal object SecurePayloadStore {
    internal const val MODE_FILE_READONLY = 0x100 // 0400
    internal const val MODE_DIR_PRIVATE = 0x1C0 // 0700

    private val defaultPublicationLock = Any()
    private val hexDigits = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    )

    fun sha256Hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = hexDigits[v ushr 4]
            chars[i * 2 + 1] = hexDigits[v and 0x0F]
        }
        return String(chars)
    }

    fun isSafeRegularFile(file: File): Boolean = runCatching {
        val path = file.toPath()
        !Files.isSymbolicLink(path) && Files.isRegularFile(path, NOFOLLOW_LINKS)
    }.getOrDefault(false)

    fun isSafeDirectory(directory: File, expectedParent: File? = null): Boolean = runCatching {
        val path = directory.toPath()
        !Files.isSymbolicLink(path) &&
            Files.isDirectory(path, NOFOLLOW_LINKS) &&
            (expectedParent == null || safeRealPath(path).parent == safeRealPath(expectedParent.canonicalFile.toPath()))
    }.getOrDefault(false)

    private fun safeRealPath(path: Path): Path = try {
        path.toRealPath()
    } catch (_: AccessDeniedException) {
        path.toFile().canonicalFile.toPath()
    }

    /**
     * 在规范化 [root] 下逐级创建并校验相对子目录 [relativePath]（禁止符号链接与路径逃逸），
     * 可选施加 `0700` 目录权限。
     */
    fun prepareDirectory(
        root: File,
        relativePath: String,
        mode: Int = MODE_DIR_PRIVATE,
        enforcePermissions: Boolean = true,
        posixChmod: ((Path, Int) -> Unit)? = null
    ): Result<File> = runCatching {
        if (!ArchiveVerifier.isSafeRelativePath(relativePath)) {
            throw PayloadVerifyException("unsafe_directory", relativePath)
        }
        val base = root.canonicalFile.toPath()
        Files.createDirectories(base)
        if (Files.isSymbolicLink(base) || !Files.isDirectory(base, NOFOLLOW_LINKS)) {
            throw PayloadVerifyException("unsafe_directory", base.fileName?.toString().orEmpty())
        }
        var current = base
        var currentReal = safeRealPath(base)
        for (segment in relativePath.split('/')) {
            val next = current.resolve(segment)
            if (!Files.exists(next, NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(next)
                } catch (_: FileAlreadyExistsException) {
                    // 并发创建同一目录时按已存在目录继续执行安全校验。
                }
            }
            if (Files.isSymbolicLink(next) || !Files.isDirectory(next, NOFOLLOW_LINKS)) {
                throw PayloadVerifyException("unsafe_directory", segment)
            }
            val nextReal = safeRealPath(next)
            if (nextReal.parent != currentReal) {
                throw PayloadVerifyException("unsafe_directory", segment)
            }
            if (enforcePermissions) {
                enforcePosixMode(next, mode, posixChmod)
            }
            current = next
            currentReal = nextReal
        }
        current.toFile()
    }

    /**
     * 设置 POSIX 权限模式；优先走注入钩子，其次在非 Windows 平台调用 Android `Os.chmod`，
     * 当处于 Android JVM Stub 测试环境时自动回退至 `Files.setPosixFilePermissions`。
     */
    fun enforcePosixMode(
        path: Path,
        mode: Int,
        posixChmod: ((Path, Int) -> Unit)? = null
    ) {
        if (posixChmod != null) {
            posixChmod(path, mode)
            return
        }
        if (isWindows()) return
        try {
            Os.chmod(path.toString(), mode)
        } catch (failure: RuntimeException) {
            if (failure.message == "Stub!") {
                try {
                    if ("posix" in path.fileSystem.supportedFileAttributeViews()) {
                        Files.setPosixFilePermissions(
                            path,
                            PosixFilePermissions.fromString(posixModeString(mode))
                        )
                    }
                } catch (posixFailure: Exception) {
                    throw PayloadVerifyException(
                        "chmod_failed",
                        path.fileName?.toString().orEmpty(),
                        posixFailure
                    )
                }
            } else {
                throw PayloadVerifyException(
                    "chmod_failed",
                    path.fileName?.toString().orEmpty(),
                    failure
                )
            }
        } catch (failure: ErrnoException) {
            throw PayloadVerifyException(
                "chmod_failed",
                path.fileName?.toString().orEmpty(),
                failure
            )
        }
    }

    /**
     * 流式核验 [input] 的精确字节长度 [expectedSize] 与小写十六进制 SHA-256 [expectedSha256]，
     * 可同步写入 [output]（若提供）。超出期望长度时立即失败，绝不读取无界流。
     */
    fun verifyStream(
        input: InputStream,
        expectedSize: Long,
        expectedSha256: String,
        output: OutputStream? = null,
        checkCancelled: () -> Unit = {
            if (Thread.currentThread().isInterrupted) throw PayloadVerifyException("cancelled")
        },
        progress: (Long) -> Unit = {}
    ): VerifiedStreamDigest {
        require(expectedSize >= 0L) { "expectedSize must be >= 0" }
        val normalizedHash = expectedSha256.trim().lowercase(Locale.ROOT)
        require(normalizedHash.length == 64 && normalizedHash.all { it in '0'..'9' || it in 'a'..'f' }) {
            "expectedSha256 must be a 64-char hex digest"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        val bufferedOut = output?.let { if (it is BufferedOutputStream) it else BufferedOutputStream(it, 64 * 1024) }
        var total = 0L
        while (true) {
            checkCancelled()
            val remaining = expectedSize - total
            val maxRead = if (remaining < buffer.size.toLong()) (remaining + 1L).toInt() else buffer.size
            val count = input.read(buffer, 0, maxRead)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > expectedSize) {
                throw PayloadVerifyException("size_overflow", "bytes=$total")
            }
            digest.update(buffer, 0, count)
            bufferedOut?.write(buffer, 0, count)
            progress(total)
        }
        bufferedOut?.flush()
        if (total != expectedSize) {
            throw PayloadVerifyException("size_mismatch", "bytes=$total expected=$expectedSize")
        }
        val actualHash = sha256Hex(digest.digest())
        if (actualHash != normalizedHash) {
            throw PayloadVerifyException("hash_mismatch", "actual=$actualHash expected=$normalizedHash")
        }
        return VerifiedStreamDigest(size = total, sha256 = actualHash)
    }

    /**
     * 流式计算 [input] 的大小与 SHA-256（受 [maxBytes] 上界约束），可同步透传至 [output]。
     */
    fun digestStream(
        input: InputStream,
        maxBytes: Long = Long.MAX_VALUE,
        output: OutputStream? = null,
        checkCancelled: () -> Unit = {
            if (Thread.currentThread().isInterrupted) throw PayloadVerifyException("cancelled")
        },
        progress: (Long) -> Unit = {}
    ): VerifiedStreamDigest {
        require(maxBytes >= 0L) { "maxBytes must be >= 0" }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        val bufferedOut = output?.let { if (it is BufferedOutputStream) it else BufferedOutputStream(it, 64 * 1024) }
        var total = 0L
        while (true) {
            checkCancelled()
            val remaining = maxBytes - total
            val maxRead = if (remaining < buffer.size.toLong()) (remaining + 1L).toInt() else buffer.size
            val count = input.read(buffer, 0, maxRead)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > maxBytes) {
                throw PayloadVerifyException("size_overflow", "bytes=$total")
            }
            digest.update(buffer, 0, count)
            bufferedOut?.write(buffer, 0, count)
            progress(total)
        }
        bufferedOut?.flush()
        return VerifiedStreamDigest(size = total, sha256 = sha256Hex(digest.digest()))
    }

    /**
     * 以 `NOFOLLOW_LINKS` 核验本地文件是否存在、大小一致且 SHA-256 精确匹配。
     */
    fun verifyFile(file: File, expectedSize: Long, expectedSha256: String): Boolean = runCatching {
        if (!isSafeRegularFile(file) || file.length() != expectedSize) return@runCatching false
        Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
            verifyStream(input, expectedSize, expectedSha256)
            true
        }
    }.getOrDefault(false)

    /**
     * 使用唯一临时文件 + `verifyStream` + `FileChannel.force(true)` + 权限锁定 + `ATOMIC_MOVE`
     * 将输入流原子落地到 `[directory]/[targetFileName]`。若已存在有效缓存则直接复用并修复权限。
     */
    fun installVerified(
        directory: File,
        targetFileName: String,
        expectedSize: Long,
        expectedSha256: String,
        input: () -> InputStream,
        tempPrefix: String = ".payload-",
        tempSuffix: String = ".part",
        enforcePermissions: Boolean = true,
        fileMode: Int = MODE_FILE_READONLY,
        posixChmod: ((Path, Int) -> Unit)? = null,
        publicationLock: Any = defaultPublicationLock,
        isExistingValid: ((File) -> Boolean)? = null,
        checkCancelled: () -> Unit = {
            if (Thread.currentThread().isInterrupted) throw PayloadVerifyException("cancelled")
        },
        progress: (Long) -> Unit = {}
    ): Result<File> = runCatching {
        if (!isSingleFileName(targetFileName)) {
            throw PayloadVerifyException("unsafe_path", targetFileName)
        }
        val dirPath = directory.toPath()
        if (!isSafeDirectory(directory)) {
            throw PayloadVerifyException("unsafe_directory", dirPath.fileName?.toString().orEmpty())
        }
        val target = File(directory, targetFileName)
        val alreadyValid = isExistingValid?.invoke(target) ?: verifyFile(target, expectedSize, expectedSha256)
        if (alreadyValid) {
            if (enforcePermissions) {
                enforcePosixMode(target.toPath(), fileMode, posixChmod)
            }
            return@runCatching target
        }

        val safePrefix = if (tempPrefix.length >= 3) tempPrefix else tempPrefix.padEnd(3, '_')
        val temporary = Files.createTempFile(dirPath, safePrefix, tempSuffix)
        try {
            input().use { source ->
                FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { channel ->
                    val output = Channels.newOutputStream(channel)
                    verifyStream(
                        input = source,
                        expectedSize = expectedSize,
                        expectedSha256 = expectedSha256,
                        output = output,
                        checkCancelled = checkCancelled,
                        progress = progress
                    )
                    channel.force(true)
                }
            }
            if (enforcePermissions) {
                enforcePosixMode(temporary, fileMode, posixChmod)
            }
            synchronized(publicationLock) {
                val racedValid = isExistingValid?.invoke(target)
                    ?: verifyFile(target, expectedSize, expectedSha256)
                if (!racedValid) {
                    Files.move(temporary, target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                } else if (enforcePermissions) {
                    enforcePosixMode(target.toPath(), fileMode, posixChmod)
                }
            }
            target
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /**
     * 将内存字节通过与 [installVerified] 相同的 SHA-256 核验与原子落盘链路持久化到 [target]。
     */
    fun writeAtomically(
        target: File,
        bytes: ByteArray,
        enforcePermissions: Boolean = true,
        fileMode: Int = MODE_FILE_READONLY,
        posixChmod: ((Path, Int) -> Unit)? = null,
        publicationLock: Any = defaultPublicationLock
    ): Result<File> {
        val parent = target.parentFile
            ?: return Result.failure(PayloadVerifyException("unsafe_directory", target.name))
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sha256 = sha256Hex(digest)
        return installVerified(
            directory = parent,
            targetFileName = target.name,
            expectedSize = bytes.size.toLong(),
            expectedSha256 = sha256,
            input = { bytes.inputStream() },
            enforcePermissions = enforcePermissions,
            fileMode = fileMode,
            posixChmod = posixChmod,
            publicationLock = publicationLock
        )
    }

    private fun isSingleFileName(name: String): Boolean =
        name.isNotEmpty() &&
            name != "." &&
            name != ".." &&
            name.indexOf('\u0000') < 0 &&
            !name.contains('/') &&
            !name.contains('\\')

    private fun isWindows(): Boolean =
        System.getProperty("os.name", "").orEmpty().startsWith("Windows", ignoreCase = true)

    private fun posixModeString(mode: Int): String = buildString(9) {
        val bits = intArrayOf(0x100, 0x080, 0x040, 0x020, 0x010, 0x008, 0x004, 0x002, 0x001)
        val chars = charArrayOf('r', 'w', 'x', 'r', 'w', 'x', 'r', 'w', 'x')
        for (i in bits.indices) {
            append(if ((mode and bits[i]) != 0) chars[i] else '-')
        }
    }
}

/**
 * 双通道只读输入流打开器：优先尝试框架远程文件通道 [openRemote]，为空或抛出非致命异常时自动降级至
 * [openProvider]（如 `ContentProvider.openFile` 只读 FD 通道）。
 */
internal object DualChannelFdOpener {
    fun openReadOnly(
        openRemote: () -> InputStream?,
        openProvider: () -> InputStream?
    ): InputStream? {
        val remote = try {
            openRemote()
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            null
        }
        if (remote != null) return remote
        return try {
            openProvider()
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            null
        }
    }
}
