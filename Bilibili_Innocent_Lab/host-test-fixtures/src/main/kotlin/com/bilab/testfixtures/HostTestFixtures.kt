package com.bilab.testfixtures

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Deterministic monotonic wall-clock test double.
 */
class FakeClock(initialMs: Long = 1_000_000L) {
    private val current = AtomicLong(initialMs)

    val currentMs: Long
        get() = current.get()

    fun nowMs(): Long = current.get()

    fun nowNs(): Long = current.get() * 1_000_000L

    fun setNowMs(epochMs: Long) {
        current.set(epochMs)
    }

    fun advanceBy(deltaMs: Long): Long {
        require(deltaMs >= 0L) { "deltaMs must be non-negative: $deltaMs" }
        return current.addAndGet(deltaMs)
    }

    fun asMillisProvider(): () -> Long = ::nowMs
}

/**
 * Helper for synthesizing ZIP archives (including adversarial traversal or high-ratio entries) in JVM tests.
 */
object TestZipBuilder {
    @JvmOverloads
    fun buildZipBytes(
        entries: Map<String, ByteArray>,
        compressionLevel: Int = Deflater.DEFAULT_COMPRESSION
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(compressionLevel)
            for ((name, payload) in entries) {
                zip.putNextEntry(ZipEntry(name).apply { time = 315532800_000L })
                zip.write(payload)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @JvmOverloads
    fun buildZip(
        target: File,
        entries: Map<String, ByteArray>,
        compressionLevel: Int = Deflater.DEFAULT_COMPRESSION
    ): File {
        target.parentFile?.mkdirs()
        target.writeBytes(buildZipBytes(entries, compressionLevel))
        return target
    }
}

/**
 * Builder for minimal valid little-endian ELF64 shared objects with configurable PT_LOAD alignment.
 */
object TestElf16KbBuilder {
    private const val ELF_HEADER_SIZE = 64
    private const val PHDR_SIZE = 56
    private const val PT_LOAD = 1
    private const val PF_RX = 5

    @JvmOverloads
    fun buildElf64(pageAlign: Long = 16384L): ByteArray =
        buildElf64WithSegments(listOf(pageAlign))

    fun buildElf64WithSegments(loadAlignments: List<Long>): ByteArray {
        require(loadAlignments.isNotEmpty()) { "At least one PT_LOAD segment is required" }
        val totalSize = ELF_HEADER_SIZE + loadAlignments.size * PHDR_SIZE
        val bytes = ByteArray(totalSize)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // ELF64 identification header
        bytes[0] = 0x7f
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2 // ELFCLASS64
        bytes[5] = 1 // ELFDATA2LSB
        bytes[6] = 1 // EV_CURRENT

        buf.putShort(16, 3.toShort()) // ET_DYN
        buf.putShort(18, 183.toShort()) // EM_AARCH64
        buf.putInt(20, 1) // EV_CURRENT
        buf.putLong(32, ELF_HEADER_SIZE.toLong()) // e_phoff
        buf.putShort(52, ELF_HEADER_SIZE.toShort()) // e_ehsize
        buf.putShort(54, PHDR_SIZE.toShort()) // e_phentsize
        buf.putShort(56, loadAlignments.size.toShort()) // e_phnum

        loadAlignments.forEachIndexed { index, align ->
            val off = ELF_HEADER_SIZE + index * PHDR_SIZE
            buf.putInt(off, PT_LOAD)
            buf.putInt(off + 4, PF_RX)
            buf.putLong(off + 8, 0L) // p_offset
            buf.putLong(off + 16, 0L) // p_vaddr
            buf.putLong(off + 24, 0L) // p_paddr
            buf.putLong(off + 32, totalSize.toLong()) // p_filesz
            buf.putLong(off + 40, totalSize.toLong()) // p_memsz
            buf.putLong(off + 48, align) // p_align
        }

        return bytes
    }
}
