package com.bilab.testfixtures

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.Deflater
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTestFixturesTest {

    @Test
    fun `fake clock starts at 1_000_000 and advances deterministically`() {
        val clock = FakeClock()
        assertEquals(1_000_000L, clock.nowMs())
        assertEquals(1_002_500L, clock.advanceBy(2_500L))
        assertEquals(1_002_500L, clock.asMillisProvider().invoke())
        clock.setNowMs(42L)
        assertEquals(42L, clock.currentMs)
    }

    @Test
    fun `test zip builder creates bytes and files with exact entries`() {
        val tempDir = Files.createTempDirectory("bil-zip-fixture").toFile()
        try {
            val entries = linkedMapOf(
                "manifest.json" to "{\"id\":\"demo\"}".toByteArray(),
                "../traversal.txt" to byteArrayOf(1, 2, 3)
            )
            val bytes = TestZipBuilder.buildZipBytes(entries, Deflater.BEST_SPEED)
            val zipFile = TestZipBuilder.buildZip(tempDir.resolve("nested/pack.zip"), entries)
            assertTrue(zipFile.isFile)
            assertArrayEquals(bytes, zipFile.readBytes())

            val parsed = linkedMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    parsed[entry.name] = zis.readBytes()
                    zis.closeEntry()
                }
            }
            assertEquals(entries.keys, parsed.keys)
            assertArrayEquals(entries["manifest.json"], parsed["manifest.json"])
            assertArrayEquals(entries["../traversal.txt"], parsed["../traversal.txt"])
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test elf builder encodes ELF64 little endian PT_LOAD alignment`() {
        val elf16k = TestElf16KbBuilder.buildElf64()
        assertEquals(120, elf16k.size)
        assertEquals(0x7f.toByte(), elf16k[0])
        assertEquals('E'.code.toByte(), elf16k[1])
        assertEquals('L'.code.toByte(), elf16k[2])
        assertEquals('F'.code.toByte(), elf16k[3])
        assertEquals(2.toByte(), elf16k[4])
        assertEquals(1.toByte(), elf16k[5])

        val buf16k = ByteBuffer.wrap(elf16k).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64L, buf16k.getLong(32))
        assertEquals(56, buf16k.getShort(54).toInt())
        assertEquals(1, buf16k.getShort(56).toInt())
        assertEquals(1, buf16k.getInt(64))
        assertEquals(16384L, buf16k.getLong(64 + 48))

        val elf4k = TestElf16KbBuilder.buildElf64(pageAlign = 4096L)
        val buf4k = ByteBuffer.wrap(elf4k).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(4096L, buf4k.getLong(64 + 48))
    }
}
