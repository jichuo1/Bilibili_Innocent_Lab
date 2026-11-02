package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun `npatch fingerprint and query path use the original instead of loader dex`() {
        withTempDirectory { directory ->
            val original = File(directory, "original.apk")
            writeArchive(original, mapOf("classes.dex" to byteArrayOf(1), "classes2.dex" to byteArrayOf(2)))
            val outer = File(directory, "patched.apk")
            writePatched(outer, original.readBytes(), 7)
            val result = DexSourceFingerprint.inspectCodePaths(listOf(outer.path), File(directory, "cache"))!!
            assertEquals(DexSourceFingerprint.inspectCodePaths(listOf(original.path))?.value, result.value)
            assertEquals(2, result.dexEntryCount)
            assertTrue(File(result.codePaths.single()).readBytes().contentEquals(original.readBytes()))
            assertNotEquals(outer.path, result.codePaths.single())
        }
    }

    @Test fun `repatching loader retains fingerprint but changing original dex invalidates it`() {
        withTempDirectory { directory ->
            val original = File(directory, "original.apk")
            val outer = File(directory, "patched.apk")
            val cache = File(directory, "cache")
            writeArchive(original, mapOf("classes.dex" to byteArrayOf(1)))
            writePatched(outer, original.readBytes(), 7)
            val first = DexSourceFingerprint.inspectCodePaths(listOf(outer.path), cache)!!
            writePatched(outer, original.readBytes(), 8)
            val repatched = DexSourceFingerprint.inspectCodePaths(listOf(outer.path), cache)!!
            assertEquals(first, repatched)
            writeArchive(original, mapOf("classes.dex" to byteArrayOf(2)))
            writePatched(outer, original.readBytes(), 8)
            assertNotEquals(first.value, DexSourceFingerprint.inspectCodePaths(listOf(outer.path), cache)?.value)
        }
    }

    @Test fun `lspatch original and code splits provide the query path with or without io redirection`() {
        withTempDirectory { directory ->
            val original = File(directory, "original.apk")
            val outer = File(directory, "lspatched.apk")
            val split = File(directory, "split.apk")
            writeArchive(original, mapOf("classes.dex" to byteArrayOf(1), "classes2.dex" to byteArrayOf(2)))
            writeArchive(split, mapOf("classes.dex" to byteArrayOf(3)))
            writePatched(outer, original.readBytes(), 7, "lspatch")
            val expected = DexSourceFingerprint.inspectCodePaths(listOf(original.path, split.path))!!
            for (materialize in listOf(false, true)) {
                val cache = File(directory, "cache-$materialize")
                val actual = DexSourceFingerprint.inspectCodePaths(listOf(outer.path, split.path), cache,
                    materializeArchiveView = materialize)!!
                assertEquals(expected.value, actual.value)
                assertEquals(3, actual.dexEntryCount)
                assertTrue(File(actual.codePaths.first()).readBytes().contentEquals(original.readBytes()))
                assertNotEquals(outer.path, actual.codePaths.first())
                assertTrue(actual.codePaths.all { File(it).isFile })
            }
        }
    }

    @Test fun `lspatch repatching and cache repair preserve original identity`() {
        withTempDirectory { directory ->
            val original = File(directory, "original.apk")
            val outer = File(directory, "lspatched.apk")
            val cache = File(directory, "cache")
            writeArchive(original, mapOf("classes.dex" to byteArrayOf(1)))
            writePatched(outer, original.readBytes(), 7, "lspatch")
            val first = DexSourceFingerprint.inspectCodePaths(listOf(outer.path), cache)!!
            writePatched(outer, original.readBytes(), 8, "lspatch")
            assertEquals(first, DexSourceFingerprint.inspectCodePaths(listOf(outer.path), cache))
            val cached = File(first.codePaths.single())
            cached.setWritable(true)
            cached.writeBytes(ByteArray(cached.length().toInt()))
            assertEquals(first, DexSourceFingerprint.inspectCodePaths(listOf(outer.path), cache))
            cached.setWritable(true)
            assertTrue(cached.delete())
            assertEquals(first, DexSourceFingerprint.inspectCodePaths(listOf(outer.path), cache))
            writeArchive(original, mapOf("classes.dex" to byteArrayOf(2)))
            writePatched(outer, original.readBytes(), 8, "lspatch")
            assertNotEquals(first.value, DexSourceFingerprint.inspectCodePaths(listOf(outer.path), cache)?.value)
        }
    }

    @Test fun `lspatch missing corrupt or oversized original never selects loader dex`() {
        withTempDirectory { directory ->
            val original = File(directory, "original.apk")
            val outer = File(directory, "lspatched.apk")
            val cache = File(directory, "cache")
            writeArchive(original, mapOf("classes.dex" to byteArrayOf(1)))
            writePatched(outer, original.readBytes(), 7, "lspatch")
            assertNull(DexSourceFingerprint.inspectCodePaths(listOf(outer.path)))
            assertNull(DexSourceFingerprint.inspectCodePaths(listOf(outer.path), cache, 1))
            writePatched(outer, byteArrayOf(1, 2, 3), 7, "lspatch")
            assertNull(DexSourceFingerprint.inspectCodePaths(listOf(outer.path), cache))
            for (partial in listOf("config.json", "origin.apk")) {
                writeArchive(outer, mapOf("classes.dex" to byteArrayOf(7),
                    "assets/lspatch/$partial" to original.readBytes()))
                assertNull(DexSourceFingerprint.inspectCodePaths(listOf(outer.path), cache))
            }
        }
    }

    @Test fun `mixed patch carriers cannot choose an arbitrary original`() {
        withTempDirectory { directory ->
            val original = File(directory, "original.apk")
            val outer = File(directory, "mixed.apk")
            writeArchive(original, mapOf("classes.dex" to byteArrayOf(1)))
            val entries = mapOf("classes.dex" to byteArrayOf(7),
                "assets/npatch/config.json" to "{}".toByteArray(), "assets/npatch/origin.apk" to original.readBytes())
            for (other in listOf(mapOf("assets/lspatch/config.json" to "{}".toByteArray()),
                mapOf("assets/lspatch/config.json" to "{}".toByteArray(), "assets/lspatch/origin.apk" to original.readBytes()))) {
                writeArchive(outer, entries + other)
                assertNull(DexSourceFingerprint.inspectCodePaths(listOf(outer.path), File(directory, "cache")))
            }
        }
    }

    @Test fun `unavailable oversized or invalid original never falls back to loader fingerprint`() {
        withTempDirectory { directory ->
            val outer = File(directory, "patched.apk")
            val original = File(directory, "original.apk")
            val cache = File(directory, "cache")
            writeArchive(original, mapOf("classes.dex" to byteArrayOf(1)))
            writePatched(outer, original.readBytes(), 7)
            assertNull(DexSourceFingerprint.inspectCodePaths(listOf(outer.path)))
            assertNull(DexSourceFingerprint.inspectCodePaths(listOf(outer.path), cache, 1))
            writePatched(outer, byteArrayOf(1, 2, 3), 7)
            assertNull(DexSourceFingerprint.inspectCodePaths(listOf(outer.path), cache))
            writeArchive(outer, mapOf("assets/npatch/config.json" to byteArrayOf(1), "classes.dex" to byteArrayOf(7)))
            assertNull(DexSourceFingerprint.inspectCodePaths(listOf(outer.path), cache))
        }
    }

    @Test fun `source cache is rebuilt after loss and same length corruption`() {
        withTempDirectory { directory ->
            val original = File(directory, "original.apk")
            val outer = File(directory, "patched.apk")
            val cache = File(directory, "cache")
            writeArchive(original, mapOf("classes.dex" to byteArrayOf(1)))
            writePatched(outer, original.readBytes(), 7)
            val first = DexSourceFingerprint.inspectCodePaths(listOf(outer.path), cache)!!
            val cached = File(first.codePaths.single())
            cached.setWritable(true)
            cached.writeBytes(ByteArray(cached.length().toInt()))
            assertEquals(first, DexSourceFingerprint.inspectCodePaths(listOf(outer.path), cache))
            cached.setWritable(true)
            assertTrue(cached.delete())
            assertEquals(first, DexSourceFingerprint.inspectCodePaths(listOf(outer.path), cache))
        }
    }

    @Test fun `original and code splits share the same fingerprint boundary with bounded cache`() {
        withTempDirectory { directory ->
            val original = File(directory, "original.apk")
            val outer = File(directory, "patched.apk")
            val split = File(directory, "split.apk")
            val cache = File(directory, "cache")
            writeArchive(split, mapOf("classes.dex" to byteArrayOf(3)))
            repeat(4) { index ->
                writeArchive(original, mapOf("classes.dex" to byteArrayOf(index.toByte())))
                writePatched(outer, original.readBytes(), 7)
                assertEquals(DexSourceFingerprint.inspectCodePaths(listOf(original.path, split.path))?.value,
                    DexSourceFingerprint.inspectCodePaths(listOf(outer.path, split.path), cache)?.value)
                // Windows 不允许删除只读文件；下一轮仅放开旧测试夹具的删除属性。
                cache.listFiles()?.forEach { it.setWritable(true) }
            }
            assertTrue(cache.listFiles()!!.size <= 2)
        }
    }

    private fun writePatched(target: File, original: ByteArray, loader: Byte, carrier: String = "npatch") = writeArchive(target,
        mapOf("classes.dex" to byteArrayOf(loader), "assets/$carrier/config.json" to "{}".toByteArray(),
            "assets/$carrier/origin.apk" to original))

    @Test fun `already redirected java view is frozen for native callers`() {
        withTempDirectory { directory ->
            val original = File(directory, "visible.apk")
            val cache = File(directory, "cache")
            writeArchive(original, mapOf("classes.dex" to byteArrayOf(1), "classes2.dex" to byteArrayOf(2)))
            val direct = DexSourceFingerprint.inspectCodePaths(listOf(original.path))!!
            val frozen = DexSourceFingerprint.inspectCodePaths(listOf(original.path), cache, materializeArchiveView = true)!!
            assertEquals(direct.value, frozen.value)
            assertNotEquals(direct.codePaths, frozen.codePaths)
            assertTrue(File(frozen.codePaths.single()).readBytes().contentEquals(original.readBytes()))
            assertEquals(frozen, DexSourceFingerprint.inspectCodePaths(listOf(original.path), cache, materializeArchiveView = true))
            assertNull(DexSourceFingerprint.inspectCodePaths(listOf(original.path), File(directory, "small"), 1, true))
        }
    }

    @Test fun `all active code splits remain readable during source cache eviction`() {
        withTempDirectory { directory ->
            val archives = (1..4).map { index -> File(directory, "$index.apk").also {
                writeArchive(it, mapOf("classes.dex" to byteArrayOf(index.toByte())))
            } }
            val result = DexSourceFingerprint.inspectCodePaths(archives.map(File::getPath),
                File(directory, "cache"), materializeArchiveView = true)!!
            assertEquals(4, result.codePaths.size)
            assertTrue(result.codePaths.all { File(it).isFile })
        }
    }

    @Test fun `resource splits do not invalidate either patched source with frozen views`() {
        withTempDirectory { directory ->
            val original = File(directory, "original.apk")
            val split = File(directory, "code.apk")
            val resources = File(directory, "resources.apk")
            writeArchive(original, mapOf("classes.dex" to byteArrayOf(1), "classes2.dex" to byteArrayOf(2)))
            writeArchive(split, mapOf("classes.dex" to byteArrayOf(3)))
            writeArchive(resources, mapOf("resources.arsc" to byteArrayOf(4)))
            val expected = requireNotNull(DexSourceFingerprint.inspectCodePaths(listOf(original.path, split.path)))
            for (carrier in listOf("npatch", "lspatch")) {
                val outer = File(directory, "$carrier.apk")
                writePatched(outer, original.readBytes(), 7, carrier)
                for (materialize in listOf(false, true)) {
                    val actual = requireNotNull(DexSourceFingerprint.inspectCodePaths(
                        listOf(outer.path, resources.path, split.path), File(directory, "$carrier-$materialize"),
                        materializeArchiveView = materialize))
                    assertEquals(expected.value, actual.value)
                    assertEquals(3, actual.dexEntryCount)
                    assertEquals(2, actual.codePaths.size)
                    assertFalse(resources.absolutePath in actual.codePaths)
                }
            }
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
            directory.walkBottomUp().forEach { it.setWritable(true) }
            directory.deleteRecursively()
        }
    }
}
