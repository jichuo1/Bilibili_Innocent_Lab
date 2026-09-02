package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DexSourceFingerprintTest {

    @Test
    fun `same dex produces stable fingerprint and ignores resources`() {
        withTempDirectory { directory ->
            val first = File(directory, "first.apk")
            val second = File(directory, "second.apk")
            writeArchive(first, mapOf("classes.dex" to byteArrayOf(1, 2, 3), "res/a" to byteArrayOf(4)))
            writeArchive(second, mapOf("classes.dex" to byteArrayOf(1, 2, 3), "res/b" to byteArrayOf(9)))

            val firstResult = DexSourceFingerprint.inspectCodePaths(listOf(first.absolutePath))
            val secondResult = DexSourceFingerprint.inspectCodePaths(listOf(second.absolutePath))

            assertNotNull(firstResult)
            assertEquals(firstResult?.value, secondResult?.value)
            assertEquals(1, firstResult?.dexEntryCount)
        }
    }

    @Test
    fun `changed secondary dex changes fingerprint`() {
        withTempDirectory { directory ->
            val first = File(directory, "first.apk")
            val second = File(directory, "second.apk")
            writeArchive(
                first,
                mapOf("classes.dex" to byteArrayOf(1), "classes2.dex" to byteArrayOf(2))
            )
            writeArchive(
                second,
                mapOf("classes.dex" to byteArrayOf(1), "classes2.dex" to byteArrayOf(3))
            )

            val firstResult = DexSourceFingerprint.inspectCodePaths(listOf(first.absolutePath))
            val secondResult = DexSourceFingerprint.inspectCodePaths(listOf(second.absolutePath))

            assertNotEquals(firstResult?.value, secondResult?.value)
        }
    }

    @Test
    fun `archive without dex is rejected`() {
        withTempDirectory { directory ->
            val archive = File(directory, "resources.apk")
            writeArchive(archive, mapOf("resources.arsc" to byteArrayOf(1)))

            assertNull(DexSourceFingerprint.inspectCodePaths(listOf(archive.absolutePath)))
        }
    }

    private fun writeArchive(target: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(target.outputStream()).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content)
                output.closeEntry()
            }
        }
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("dex-source-fingerprint").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
