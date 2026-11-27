package com.Bilibili_Innocent_Lab.xposedmodule.runtime.payload

import android.system.ErrnoException
import android.system.Os
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

/**
 * 纯字节/文件通道 ELF64 (16KB) 与 ELF32 (4KB) 页对齐及安全不变量校验器，供动态原生库加载前预检与打包后核验共用。
 */
internal object Elf16KbVerifier {
    internal const val PAGE_SIZE_16K = 16384L
    internal const val PAGE_SIZE_4K = 4096L
    internal const val DEFAULT_MAX_ELF_BYTES = 64L * 1024L * 1024L

    private const val ELF_HEADER_64_MIN_BYTES = 64
    private const val PHDR_64_MIN_BYTES = 56
    private const val ELF_HEADER_32_MIN_BYTES = 52
    private const val PHDR_32_MIN_BYTES = 32
    private const val ELF_CLASS_32 = 1.toByte()
    private const val ELF_CLASS_64 = 2.toByte()
    private const val ELF_DATA_LSB = 1.toByte()
    private const val PT_LOAD = 1L
    private const val PT_DYNAMIC = 2L
    private const val DT_NULL = 0L
    private const val DT_TEXTREL = 22L
    private const val DT_FLAGS = 30L
    private const val DF_TEXTREL = 0x4L
    private const val MAX_PHDR_BULK_READ_BYTES = 256L * 1024L

    private data class LoadSegmentRange(
        val firstPage: Long,
        val lastPage: Long,
        val flags: Int,
        val index: Int
    )

    private interface ElfSource {
        val size: Long
        fun bufferAt(offset: Long, length: Int): ByteBuffer
    }

    private class ByteArrayElfSource(private val bytes: ByteArray) : ElfSource {
        override val size: Long get() = bytes.size.toLong()
        override fun bufferAt(offset: Long, length: Int): ByteBuffer {
            require(offset >= 0L && length >= 0 && length.toLong() <= size && offset <= size - length.toLong()) {
                "ELF slice out of bounds (offset=$offset, length=$length, size=$size)"
            }
            return ByteBuffer.wrap(bytes, offset.toInt(), length).slice().order(ByteOrder.LITTLE_ENDIAN)
        }
    }

    private class FileChannelElfSource(
        private val channel: FileChannel,
        override val size: Long
    ) : ElfSource {
        override fun bufferAt(offset: Long, length: Int): ByteBuffer {
            require(offset >= 0L && length >= 0 && length.toLong() <= size && offset <= size - length.toLong()) {
                "ELF read out of bounds (offset=$offset, length=$length, size=$size)"
            }
            val buf = ByteBuffer.allocate(length)
            var pos = offset
            while (buf.hasRemaining()) {
                val read = channel.read(buf, pos)
                if (read < 0) throw IOException("Unexpected EOF while reading ELF at offset=$pos")
                if (read == 0) continue
                pos += read.toLong()
            }
            buf.flip()
            return buf.order(ByteOrder.LITTLE_ENDIAN)
        }
    }

    /**
     * 校验 [bytes] 为小端 ELF64 二进制，program header 表不越界，至少存在一个 `PT_LOAD` 段，
     * 每个 `PT_LOAD` 满足 `p_align >= 16384 && p_align % 16384 == 0`、`p_offset`/`p_vaddr` 16KB 同余、
     * 文件与内存边界合法、不同 `p_flags` 的 `PT_LOAD` 段不共占同一 16KB 虚拟页，且 `PT_DYNAMIC` 不含 TEXTREL。
     */
    fun verifyElf64PageAlignment(bytes: ByteArray, label: String): Result<Unit> = runCatching {
        verifyElf64Source(ByteArrayElfSource(bytes), label)
    }

    /**
     * 以 `NOFOLLOW_LINKS` + 头部/段表有界读取校验本地 ELF64 文件的 16KB 页对齐与 Bionic 安全不变量。
     */
    fun verifyElf64FilePageAlignment(
        file: File,
        label: String = file.name,
        maxBytes: Long = DEFAULT_MAX_ELF_BYTES
    ): Result<Unit> = verifyElfFile(file, label, maxBytes, ELF_HEADER_64_MIN_BYTES) { source ->
        verifyElf64Source(source, label)
    }

    /**
     * 校验 [bytes] 为小端 ELF32 二进制，program header 表不越界，至少存在一个 `PT_LOAD` 段，
     * 每个 `PT_LOAD` 满足 `p_align >= 4096 && p_align % 4096 == 0`、`p_offset`/`p_vaddr` 4KB 同余、
     * 文件与内存边界合法、不同 `p_flags` 的 `PT_LOAD` 段不共占同一 4KB 虚拟页，且 `PT_DYNAMIC` 不含 TEXTREL。
     */
    fun verifyElf32PageAlignment(bytes: ByteArray, label: String): Result<Unit> = runCatching {
        verifyElf32Source(ByteArrayElfSource(bytes), label)
    }

    /**
     * 以 `NOFOLLOW_LINKS` + 头部/段表有界读取校验本地 ELF32 文件的 4KB 页对齐与安全不变量。
     */
    fun verifyElf32FilePageAlignment(
        file: File,
        label: String = file.name,
        maxBytes: Long = DEFAULT_MAX_ELF_BYTES
    ): Result<Unit> = verifyElfFile(file, label, maxBytes, ELF_HEADER_32_MIN_BYTES) { source ->
        verifyElf32Source(source, label)
    }

    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun requireElf64FilePageAlignment(
        file: File,
        label: String = file.name,
        maxBytes: Long = DEFAULT_MAX_ELF_BYTES
    ) {
        verifyElf64FilePageAlignment(file, label, maxBytes).getOrElse { err ->
            throw (err as? IOException ?: IOException(err.message ?: "elf64_alignment_invalid", err))
        }
    }

    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun requireElf32FilePageAlignment(
        file: File,
        label: String = file.name,
        maxBytes: Long = DEFAULT_MAX_ELF_BYTES
    ) {
        verifyElf32FilePageAlignment(file, label, maxBytes).getOrElse { err ->
            throw (err as? IOException ?: IOException(err.message ?: "elf32_alignment_invalid", err))
        }
    }

    /**
     * 读取文件前 5 字节判断是否为 ELF；若为 ELF 则返回 `e_ident[EI_CLASS]`（1=ELF32，2=ELF64），否则返回 null。
     */
    fun readElfClassIfElf(file: File): Int? = runCatching {
        if (!SecurePayloadStore.isSafeRegularFile(file) || file.length() < 5L) return@runCatching null
        val head = ByteArray(5)
        Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
            var readTotal = 0
            while (readTotal < head.size) {
                val n = input.read(head, readTotal, head.size - readTotal)
                if (n < 0) return@runCatching null
                readTotal += n
            }
        }
        if (head[0] == 0x7f.toByte() &&
            head[1] == 'E'.code.toByte() &&
            head[2] == 'L'.code.toByte() &&
            head[3] == 'F'.code.toByte()
        ) {
            head[4].toInt() and 0xFF
        } else {
            null
        }
    }.getOrNull()

    private fun verifyElfFile(
        file: File,
        label: String,
        maxBytes: Long,
        minHeaderBytes: Int,
        block: (ElfSource) -> Unit
    ): Result<Unit> = runCatching {
        require(maxBytes >= minHeaderBytes.toLong()) { "maxBytes must be >= $minHeaderBytes" }
        require(SecurePayloadStore.isSafeRegularFile(file)) { "$label is not a regular non-symlink file" }
        FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
            val fileLen = channel.size()
            require(fileLen in minHeaderBytes.toLong()..maxBytes) {
                "$label length $fileLen outside [$minHeaderBytes, $maxBytes]"
            }
            block(FileChannelElfSource(channel, fileLen))
        }
    }

    private fun verifyElfHeader(source: ElfSource, label: String, minBytes: Int, expectedClass: Byte, archLabel: String): ByteBuffer {
        val totalBytes = source.size
        require(totalBytes >= minBytes.toLong()) {
            "$label is too small for $archLabel header ($totalBytes)"
        }
        val ehdr = source.bufferAt(0L, minBytes)
        require(
            ehdr.get(0) == 0x7f.toByte() &&
                ehdr.get(1) == 'E'.code.toByte() &&
                ehdr.get(2) == 'L'.code.toByte() &&
                ehdr.get(3) == 'F'.code.toByte()
        ) {
            "$label is not an ELF binary"
        }
        require(ehdr.get(4) == expectedClass && ehdr.get(5) == ELF_DATA_LSB) {
            "$label must be little-endian $archLabel"
        }
        return ehdr
    }

    private fun verifyElf64Source(source: ElfSource, label: String) {
        val ehdr = verifyElfHeader(source, label, ELF_HEADER_64_MIN_BYTES, ELF_CLASS_64, "ELF64")
        val phoff = ehdr.getLong(32)
        val phentsize = ehdr.getShort(54).toInt() and 0xFFFF
        val phnum = ehdr.getShort(56).toInt() and 0xFFFF
        require(phentsize >= PHDR_64_MIN_BYTES && phnum > 0) {
            "$label has invalid ELF64 program header dimensions (phentsize=$phentsize, phnum=$phnum)"
        }
        val tableBytes = phnum.toLong() * phentsize.toLong()
        val totalBytes = source.size
        require(phoff >= ELF_HEADER_64_MIN_BYTES.toLong() && tableBytes <= totalBytes && phoff <= totalBytes - tableBytes) {
            "$label has invalid ELF64 program headers (phoff=$phoff, table=$tableBytes, size=$totalBytes)"
        }
        val bulkTable = if (tableBytes <= MAX_PHDR_BULK_READ_BYTES) source.bufferAt(phoff, tableBytes.toInt()) else null
        var loadSegments = 0
        val loadRanges = ArrayList<LoadSegmentRange>(phnum)
        for (i in 0 until phnum) {
            val phdr = bulkTable ?: source.bufferAt(phoff + i.toLong() * phentsize.toLong(), PHDR_64_MIN_BYTES)
            val off = if (bulkTable != null) i * phentsize else 0
            val pType = phdr.getInt(off).toLong() and 0xFFFF_FFFFL
            if (pType == PT_LOAD) {
                loadSegments++
                val pFlags = phdr.getInt(off + 4)
                val pOffset = phdr.getLong(off + 8)
                val pVaddr = phdr.getLong(off + 16)
                val pFilesz = phdr.getLong(off + 32)
                val pMemsz = phdr.getLong(off + 40)
                val pAlign = phdr.getLong(off + 48)
                require(pAlign >= PAGE_SIZE_16K && pAlign % PAGE_SIZE_16K == 0L) {
                    "$label PT_LOAD[$i] p_align=$pAlign is not 16KB aligned"
                }
                require(Math.floorMod(pOffset, PAGE_SIZE_16K) == Math.floorMod(pVaddr, PAGE_SIZE_16K)) {
                    "$label PT_LOAD[$i] p_offset and p_vaddr are not 16KB congruent"
                }
                require(pFilesz >= 0L && pMemsz >= pFilesz && pOffset >= 0L && pFilesz <= totalBytes && pOffset <= totalBytes - pFilesz) {
                    "$label PT_LOAD[$i] has invalid file or memory bounds"
                }
                if (pMemsz > 0L) {
                    val lastVaddr = pVaddr + (pMemsz - 1L)
                    require(java.lang.Long.compareUnsigned(lastVaddr, pVaddr) >= 0) {
                        "$label PT_LOAD[$i] virtual address wraps 64-bit range"
                    }
                    val firstPage = java.lang.Long.divideUnsigned(pVaddr, PAGE_SIZE_16K)
                    val lastPage = java.lang.Long.divideUnsigned(lastVaddr, PAGE_SIZE_16K)
                    loadRanges += LoadSegmentRange(firstPage, lastPage, pFlags, i)
                }
            } else if (pType == PT_DYNAMIC) {
                val dynOffset = phdr.getLong(off + 8)
                val dynFilesz = phdr.getLong(off + 32)
                scanDynamicTable(source, label, dynOffset, dynFilesz, entrySize = 16, is64Bit = true)
            }
        }
        require(loadSegments > 0) { "$label has no PT_LOAD segments" }
        verifyLoadSegmentOverlap(loadRanges, label, "16KB")
    }

    private fun verifyElf32Source(source: ElfSource, label: String) {
        val ehdr = verifyElfHeader(source, label, ELF_HEADER_32_MIN_BYTES, ELF_CLASS_32, "ELF32")
        val phoff = ehdr.getInt(28).toLong() and 0xFFFF_FFFFL
        val phentsize = ehdr.getShort(42).toInt() and 0xFFFF
        val phnum = ehdr.getShort(44).toInt() and 0xFFFF
        require(phentsize >= PHDR_32_MIN_BYTES && phnum > 0) {
            "$label has invalid ELF32 program header dimensions (phentsize=$phentsize, phnum=$phnum)"
        }
        val tableBytes = phnum.toLong() * phentsize.toLong()
        val totalBytes = source.size
        require(phoff >= ELF_HEADER_32_MIN_BYTES.toLong() && tableBytes <= totalBytes && phoff <= totalBytes - tableBytes) {
            "$label has invalid ELF32 program headers (phoff=$phoff, table=$tableBytes, size=$totalBytes)"
        }
        val bulkTable = if (tableBytes <= MAX_PHDR_BULK_READ_BYTES) source.bufferAt(phoff, tableBytes.toInt()) else null
        var loadSegments = 0
        val loadRanges = ArrayList<LoadSegmentRange>(phnum)
        for (i in 0 until phnum) {
            val phdr = bulkTable ?: source.bufferAt(phoff + i.toLong() * phentsize.toLong(), PHDR_32_MIN_BYTES)
            val off = if (bulkTable != null) i * phentsize else 0
            val pType = phdr.getInt(off).toLong() and 0xFFFF_FFFFL
            if (pType == PT_LOAD) {
                loadSegments++
                val pOffset = phdr.getInt(off + 4).toLong() and 0xFFFF_FFFFL
                val pVaddr = phdr.getInt(off + 8).toLong() and 0xFFFF_FFFFL
                val pFilesz = phdr.getInt(off + 16).toLong() and 0xFFFF_FFFFL
                val pMemsz = phdr.getInt(off + 20).toLong() and 0xFFFF_FFFFL
                val pFlags = phdr.getInt(off + 24)
                val pAlign = phdr.getInt(off + 28).toLong() and 0xFFFF_FFFFL
                require(pAlign >= PAGE_SIZE_4K && pAlign % PAGE_SIZE_4K == 0L) {
                    "$label PT_LOAD[$i] p_align=$pAlign is not 4KB aligned"
                }
                require(Math.floorMod(pOffset, PAGE_SIZE_4K) == Math.floorMod(pVaddr, PAGE_SIZE_4K)) {
                    "$label PT_LOAD[$i] p_offset and p_vaddr are not 4KB congruent"
                }
                require(pMemsz >= pFilesz && pFilesz <= totalBytes && pOffset <= totalBytes - pFilesz) {
                    "$label PT_LOAD[$i] has invalid file or memory bounds"
                }
                if (pMemsz > 0L) {
                    val lastVaddr = pVaddr + (pMemsz - 1L)
                    require(lastVaddr <= 0xFFFF_FFFFL) {
                        "$label PT_LOAD[$i] virtual address wraps 32-bit range"
                    }
                    val firstPage = pVaddr / PAGE_SIZE_4K
                    val lastPage = lastVaddr / PAGE_SIZE_4K
                    loadRanges += LoadSegmentRange(firstPage, lastPage, pFlags, i)
                }
            } else if (pType == PT_DYNAMIC) {
                val dynOffset = phdr.getInt(off + 4).toLong() and 0xFFFF_FFFFL
                val dynFilesz = phdr.getInt(off + 16).toLong() and 0xFFFF_FFFFL
                scanDynamicTable(source, label, dynOffset, dynFilesz, entrySize = 8, is64Bit = false)
            }
        }
        require(loadSegments > 0) { "$label has no PT_LOAD segments" }
        verifyLoadSegmentOverlap(loadRanges, label, "4KB")
    }

    private fun scanDynamicTable(
        source: ElfSource,
        label: String,
        dynOffset: Long,
        dynFilesz: Long,
        entrySize: Int,
        is64Bit: Boolean
    ) {
        val totalBytes = source.size
        if (dynFilesz < entrySize.toLong() || dynOffset < 0L || dynFilesz > totalBytes || dynOffset > totalBytes - dynFilesz) {
            return
        }
        val chunkLimit = 16 * 1024
        var offset = dynOffset
        val dynEnd = dynOffset + dynFilesz
        while (offset <= dynEnd - entrySize.toLong()) {
            val remainingBytes = dynEnd - offset
            val alignedChunk = ((remainingBytes / entrySize.toLong()) * entrySize.toLong())
                .coerceAtMost(chunkLimit.toLong())
                .toInt()
            val chunk = source.bufferAt(offset, alignedChunk)
            var pos = 0
            while (pos <= alignedChunk - entrySize) {
                val dTag = if (is64Bit) chunk.getLong(pos) else chunk.getInt(pos).toLong()
                val dVal = if (is64Bit) chunk.getLong(pos + 8) else (chunk.getInt(pos + 4).toLong() and 0xFFFF_FFFFL)
                if (dTag == DT_NULL) return
                require(dTag != DT_TEXTREL && !(dTag == DT_FLAGS && (dVal and DF_TEXTREL) != 0L)) {
                    "$label contains forbidden TEXTREL in PT_DYNAMIC (d_tag=$dTag)"
                }
                pos += entrySize
            }
            offset += alignedChunk.toLong()
        }
    }

    private fun verifyLoadSegmentOverlap(ranges: List<LoadSegmentRange>, label: String, pageLabel: String) {
        for (i in ranges.indices) {
            val a = ranges[i]
            for (j in i + 1 until ranges.size) {
                val b = ranges[j]
                require(!(a.flags != b.flags && a.firstPage <= b.lastPage && b.firstPage <= a.lastPage)) {
                    "$label PT_LOAD[${a.index}] (flags=${a.flags}) and PT_LOAD[${b.index}] (flags=${b.flags}) share a $pageLabel virtual page"
                }
            }
        }
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
    private const val MIN_COMPRESSION_RATIO_CHECK_BYTES = 262_144L

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
                            if (entryBytes >= MIN_COMPRESSION_RATIO_CHECK_BYTES) {
                                val ratio = entryBytes.toDouble() / compressedSize.toDouble()
                                require(ratio <= limits.maxCompressionRatio) {
                                    "Zip entry $name compression ratio $ratio exceeds ${limits.maxCompressionRatio}"
                                }
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
        progress: (Long) -> Unit = {},
        validateStagedFile: ((Path) -> Unit)? = null
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
            validateStagedFile?.invoke(temporary)
            if (enforcePermissions) {
                enforcePosixMode(temporary, fileMode, posixChmod)
            }
            synchronized(publicationLock) {
                val racedValid = isExistingValid?.invoke(target)
                    ?: verifyFile(target, expectedSize, expectedSha256)
                if (!racedValid) {
                    if (!isSafeDirectory(directory)) {
                        throw PayloadVerifyException("unsafe_directory", dirPath.fileName?.toString().orEmpty())
                    }
                    val targetPath = target.toPath()
                    if (Files.isSymbolicLink(targetPath) || Files.isDirectory(targetPath, NOFOLLOW_LINKS)) {
                        throw PayloadVerifyException("unsafe_path", targetFileName)
                    }
                    try {
                        Files.move(temporary, targetPath, ATOMIC_MOVE, REPLACE_EXISTING)
                    } catch (_: AtomicMoveNotSupportedException) {
                        if (!isSafeDirectory(directory) || Files.isSymbolicLink(targetPath)) {
                            throw PayloadVerifyException("unsafe_path", targetFileName)
                        }
                        Files.move(temporary, targetPath, REPLACE_EXISTING)
                        if (enforcePermissions) {
                            enforcePosixMode(targetPath, fileMode, posixChmod)
                        }
                    }
                    syncDirectoryBestEffort(dirPath)
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

    private fun syncDirectoryBestEffort(directory: Path) {
        if (isWindows()) return
        runCatching {
            FileChannel.open(directory, READ, NOFOLLOW_LINKS).use { dirChannel ->
                dirChannel.force(true)
            }
        }
    }

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
