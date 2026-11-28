package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DexSourceFingerprintTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private fun newCleanDir(): File = tempFolder.newFolder()

    private fun writePatched(target: File, original: ByteArray, loader: Byte, carrier: String = "npatch") = writeArchive(
        target,
        mapOf(
            "classes.dex" to byteArrayOf(loader),
            "assets/$carrier/config.json" to "{}".toByteArray(),
            "assets/$carrier/origin.apk" to original
        )
    )

    /** Random bytes deflate to roughly their own size, so damage lands in the payload, not the directory. */
    private fun incompressible() = ByteArray(256).also { java.util.Random(7).nextBytes(it) }

    private fun writeArchive(target: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(target.outputStream()).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content)
                output.closeEntry()
            }
        }
    }

    private fun sameDexProducesStableFingerprintAndIgnoresResources() {
        val directory = newCleanDir()
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

    private fun changedSecondaryDexChangesFingerprint() {
        val directory = newCleanDir()
        val first = File(directory, "first.apk")
        val second = File(directory, "second.apk")
        writeArchive(first, mapOf("classes.dex" to byteArrayOf(1), "classes2.dex" to byteArrayOf(2)))
        writeArchive(second, mapOf("classes.dex" to byteArrayOf(1), "classes2.dex" to byteArrayOf(3)))

        val firstResult = DexSourceFingerprint.inspectCodePaths(listOf(first.absolutePath))
        val secondResult = DexSourceFingerprint.inspectCodePaths(listOf(second.absolutePath))

        assertNotEquals(firstResult?.value, secondResult?.value)
    }

    private fun archiveWithoutDexIsRejected() {
        val directory = newCleanDir()
        val archive = File(directory, "resources.apk")
        writeArchive(archive, mapOf("resources.arsc" to byteArrayOf(1)))
        assertNull(DexSourceFingerprint.inspectCodePaths(listOf(archive.absolutePath)))
    }

    private fun npatchFingerprintAndQueryPathUseOriginalInsteadOfLoaderDex() {
        val directory = newCleanDir()
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

    private fun repatchingLoaderRetainsFingerprintButChangingOriginalInvalidatesIt() {
        val directory = newCleanDir()
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

    private fun lspatchOriginalAndCodeSplitsProvideQueryPathWithOrWithoutIoRedirection() {
        val directory = newCleanDir()
        val original = File(directory, "original.apk")
        val outer = File(directory, "lspatched.apk")
        val split = File(directory, "split.apk")
        writeArchive(original, mapOf("classes.dex" to byteArrayOf(1), "classes2.dex" to byteArrayOf(2)))
        writeArchive(split, mapOf("classes.dex" to byteArrayOf(3)))
        writePatched(outer, original.readBytes(), 7, "lspatch")
        val expected = DexSourceFingerprint.inspectCodePaths(listOf(original.path, split.path))!!
        for (materialize in listOf(false, true)) {
            val cache = File(directory, "cache-$materialize")
            val actual = DexSourceFingerprint.inspectCodePaths(
                listOf(outer.path, split.path), cache, materializeArchiveView = materialize
            )!!
            assertEquals(expected.value, actual.value)
            assertEquals(3, actual.dexEntryCount)
            assertTrue(File(actual.codePaths.first()).readBytes().contentEquals(original.readBytes()))
            assertNotEquals(outer.path, actual.codePaths.first())
            assertTrue(actual.codePaths.all { File(it).isFile })
        }
    }

    private fun lspatchRepatchingAndCacheRepairPreserveOriginalIdentity() {
        val directory = newCleanDir()
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

    private fun lspatchAndNpatchMissingCorruptOrOversizedOriginalsNeverSelectLoaderDex() {
        val directory = newCleanDir()
        val original = File(directory, "original.apk")
        val cache = File(directory, "cache")
        writeArchive(original, mapOf("classes.dex" to byteArrayOf(1)))
        for (carrier in listOf("lspatch", "npatch")) {
            val outer = File(directory, "$carrier-outer.apk")
            writePatched(outer, original.readBytes(), 7, carrier)
            assertNull(DexSourceFingerprint.inspectCodePaths(listOf(outer.path)))
            assertNull(DexSourceFingerprint.inspectCodePaths(listOf(outer.path), cache, 1))
            writePatched(outer, byteArrayOf(1, 2, 3), 7, carrier)
            assertNull(DexSourceFingerprint.inspectCodePaths(listOf(outer.path), cache))
            for (partial in listOf("config.json", "origin.apk")) {
                writeArchive(outer, mapOf("classes.dex" to byteArrayOf(7), "assets/$carrier/$partial" to original.readBytes()))
                assertNull(DexSourceFingerprint.inspectCodePaths(listOf(outer.path), cache))
            }
        }
    }

    private fun mixedPatchCarriersCannotChooseAnArbitraryOriginal() {
        val directory = newCleanDir()
        val original = File(directory, "original.apk")
        val outer = File(directory, "mixed.apk")
        writeArchive(original, mapOf("classes.dex" to byteArrayOf(1)))
        val entries = mapOf(
            "classes.dex" to byteArrayOf(7),
            "assets/npatch/config.json" to "{}".toByteArray(),
            "assets/npatch/origin.apk" to original.readBytes()
        )
        for (other in listOf(
            mapOf("assets/lspatch/config.json" to "{}".toByteArray()),
            mapOf("assets/lspatch/config.json" to "{}".toByteArray(), "assets/lspatch/origin.apk" to original.readBytes())
        )) {
            writeArchive(outer, entries + other)
            assertNull(DexSourceFingerprint.inspectCodePaths(listOf(outer.path), File(directory, "cache")))
        }
    }

    private fun sourceCacheIsRebuiltAfterLossAndSameLengthCorruption() {
        val directory = newCleanDir()
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

    private fun originalAndCodeSplitsShareSameFingerprintBoundaryWithBoundedCache() {
        val directory = newCleanDir()
        val original = File(directory, "original.apk")
        val outer = File(directory, "patched.apk")
        val split = File(directory, "split.apk")
        val cache = File(directory, "cache")
        writeArchive(split, mapOf("classes.dex" to byteArrayOf(3)))
        repeat(4) { index ->
            writeArchive(original, mapOf("classes.dex" to byteArrayOf(index.toByte())))
            writePatched(outer, original.readBytes(), 7)
            assertEquals(
                DexSourceFingerprint.inspectCodePaths(listOf(original.path, split.path))?.value,
                DexSourceFingerprint.inspectCodePaths(listOf(outer.path, split.path), cache)?.value
            )
            cache.listFiles()?.forEach { it.setWritable(true) }
        }
        assertTrue(cache.listFiles()!!.count { it.name.endsWith(".apk") } <= 2)
        assertTrue(
            cache.listFiles()!!.filter { it.name.endsWith(".ok") }
                .all { File(cache, it.name.removeSuffix(".ok")).isFile }
        )
    }

    private fun publishedSourceWritesMarkerOnceAndKeepsTimestamp() {
        for (frozenView in listOf(false, true)) {
            val directory = newCleanDir()
            val original = File(directory, "original.apk")
            val outer = File(directory, "patched.apk")
            val cache = File(directory, "cache")
            writeArchive(original, mapOf("classes.dex" to incompressible()))
            val source = if (frozenView) original else outer.also { writePatched(it, original.readBytes(), 7) }
            val first = DexSourceFingerprint.inspectCodePaths(listOf(source.path), cache, materializeArchiveView = frozenView)!!
            val cached = File(first.codePaths.single())
            val marker = File(cache, cached.name + ".ok")
            assertTrue("publishing must leave a verified marker", marker.isFile)
            assertTrue(DexSourceFingerprint.isVerified(cached))
            val published = cached.lastModified()
            assertEquals(first, DexSourceFingerprint.inspectCodePaths(listOf(source.path), cache, materializeArchiveView = frozenView))
            assertEquals("use time is recorded on the marker, never on the source", published, cached.lastModified())
            cache.walkBottomUp().forEach { it.setWritable(true) }
        }
    }

    private fun anyRealModificationOfPublishedSourceIsCaughtAndRepaired() {
        val directory = newCleanDir()
        val original = File(directory, "original.apk")
        val outer = File(directory, "patched.apk")
        val cache = File(directory, "cache")
        writeArchive(original, mapOf("classes.dex" to incompressible()))
        writePatched(outer, original.readBytes(), 7)
        val first = DexSourceFingerprint.inspectCodePaths(listOf(outer.path), cache)!!
        val cached = File(first.codePaths.single())
        val good = cached.readBytes()
        val published = cached.lastModified()
        cached.setWritable(true)
        cached.writeBytes(ByteArray(good.size))
        cached.setLastModified(published + 5_000)
        cached.setReadOnly()
        assertFalse(DexSourceFingerprint.isVerified(cached))
        if (System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)) cached.setWritable(true)
        assertEquals(first, DexSourceFingerprint.inspectCodePaths(listOf(outer.path), cache))
        assertTrue("the repaired source must match the original again", File(first.codePaths.single()).readBytes().contentEquals(good))
        assertTrue(DexSourceFingerprint.isVerified(File(first.codePaths.single())))
        cache.walkBottomUp().forEach { it.setWritable(true) }
    }

    private fun cachePublishedBeforeMarkersExistedIsVerifiedOnceAndThenMarked() {
        val directory = newCleanDir()
        val original = File(directory, "original.apk")
        val outer = File(directory, "patched.apk")
        val cache = File(directory, "cache")
        writeArchive(original, mapOf("classes.dex" to byteArrayOf(1, 2, 3)))
        writePatched(outer, original.readBytes(), 7)
        val first = DexSourceFingerprint.inspectCodePaths(listOf(outer.path), cache)!!
        val cached = File(first.codePaths.single())
        val marker = File(cache, cached.name + ".ok")
        assertTrue(marker.delete())
        assertFalse(DexSourceFingerprint.isVerified(cached))
        assertEquals(first, DexSourceFingerprint.inspectCodePaths(listOf(outer.path), cache))
        assertTrue(marker.isFile)
        assertTrue(DexSourceFingerprint.isVerified(cached))
        marker.writeText("1:1")
        assertFalse(DexSourceFingerprint.isVerified(cached))
        assertFalse(DexSourceFingerprint.isVerified(File(cache, "missing.apk")))
        cache.walkBottomUp().forEach { it.setWritable(true) }
    }

    private fun alreadyRedirectedJavaViewIsFrozenForNativeCallers() {
        val directory = newCleanDir()
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
        cache.walkBottomUp().forEach { it.setWritable(true) }
    }

    private fun activeCodeSplitsAndNonCodeSplitsAndMultiArchiveLimit() {
        val directory = newCleanDir()
        val archives = (1..4).map { index ->
            File(directory, "$index.apk").also { writeArchive(it, mapOf("classes.dex" to byteArrayOf(index.toByte()))) }
        }
        val cache = File(directory, "cache")
        val active = DexSourceFingerprint.inspectCodePaths(archives.map(File::getPath), cache, materializeArchiveView = true)!!
        assertEquals(4, active.codePaths.size)
        assertTrue(active.codePaths.all { File(it).isFile })

        // Filters non-code resource/locale/density splits before checking maxArchives
        val splitDir = newCleanDir()
        val base = File(splitDir, "base.apk").also { writeArchive(it, mapOf("classes.dex" to byteArrayOf(1, 2, 3))) }
        val nonCodeSplits = (1..6).map { idx ->
            val name = when {
                idx % 3 == 0 -> "split_config.xxhdpi_$idx.apk"
                idx % 2 == 0 -> "split_config.zh_$idx.apk"
                else -> "split_resources_$idx.apk"
            }
            File(splitDir, name).also { writeArchive(it, mapOf("resources.arsc" to byteArrayOf(4))) }
        }
        val filtered = DexSourceFingerprint.inspectCodePathsWithSplitNames(
            paths = listOf(base.absolutePath) + nonCodeSplits.map { it.absolutePath },
            splitNames = listOf<String?>(null) + nonCodeSplits.mapIndexed { i, _ -> if (i % 2 == 0) "config.en" else "config.xxhdpi" },
            maxArchives = 4
        )
        assertNotNull("Non-code splits should not trigger maxArchives=4 rejection", filtered)
        assertEquals(listOf(base.canonicalPath), filtered!!.codePaths)

        // Exceeding maxArchives with real code APKs fails closed (5 > maxArchives=4)
        val codeSplits = (1..5).map { idx ->
            File(splitDir, "code_$idx.apk").also { writeArchive(it, mapOf("classes.dex" to byteArrayOf(idx.toByte()))) }
        }
        assertNull(DexSourceFingerprint.inspectCodePaths(codeSplits.map { it.absolutePath }, maxArchives = 4))
        cache.walkBottomUp().forEach { it.setWritable(true) }
    }

    private fun resourceSplitsDoNotInvalidateEitherPatchedSourceWithFrozenViews() {
        val directory = newCleanDir()
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
                val cache = File(directory, "$carrier-$materialize")
                val actual = requireNotNull(
                    DexSourceFingerprint.inspectCodePaths(
                        listOf(outer.path, resources.path, split.path),
                        cache,
                        materializeArchiveView = materialize
                    )
                )
                assertEquals(expected.value, actual.value)
                assertEquals(3, actual.dexEntryCount)
                assertEquals(2, actual.codePaths.size)
                assertFalse(resources.absolutePath in actual.codePaths)
                cache.walkBottomUp().forEach { it.setWritable(true) }
            }
        }
    }

    private fun canonicalizesDotPathsFallsBackToPublicDirsAndPreservesRealSplitNames() {
        val directory = newCleanDir()
        val subDir = File(directory, "nested").apply { mkdirs() }
        val baseApk = File(directory, "base.apk")
        val splitFeat = File(directory, "split_feat.apk")
        val splitRes = File(directory, "split_res.apk")
        writeArchive(baseApk, mapOf("classes.dex" to byteArrayOf(1, 2, 3)))
        writeArchive(splitFeat, mapOf("classes.dex" to byteArrayOf(4, 5, 6)))
        writeArchive(splitRes, mapOf("resources.arsc" to byteArrayOf(7, 8, 9)))

        val dotPath = File(subDir, "../base.apk").path
        val canonicalized = requireNotNull(DexSourceFingerprint.inspectCodePaths(listOf(dotPath)))
        assertEquals(listOf(baseApk.canonicalPath), canonicalized.codePaths)
        assertFalse(canonicalized.codePaths.single().contains(".."))

        val info = ApplicationInfo().apply {
            sourceDir = null
            publicSourceDir = dotPath
            splitSourceDirs = null
            splitPublicSourceDirs = arrayOf(splitRes.path, splitFeat.path)
            splitNames = arrayOf("config.arm64_v8a", "feature_video")
        }
        val result = requireNotNull(DexSourceFingerprint.inspect(info))
        assertEquals(listOf(baseApk.canonicalPath, splitFeat.canonicalPath), result.codePaths)
        assertEquals(listOf<String?>(null, "feature_video"), result.splitNames)
        assertEquals(listOf<String?>(null, "feature_video"), DexSourceFingerprint.splitNamesFor(result.codePaths))
    }

    @Test
    fun `resolve cache root rejects read only directory when enforced`() {
        for (frozenView in listOf(false, true)) {
            val directory = newCleanDir()
            val original = File(directory, "original.apk")
            val outer = File(directory, "patched.apk")
            val cache = File(directory, "cache")
            try {
                writeArchive(original, mapOf("classes.dex" to incompressible()))
                val source = if (frozenView) original else outer.also { writePatched(it, original.readBytes(), 7) }
                val first = DexSourceFingerprint.inspectCodePaths(listOf(source.path), cache, materializeArchiveView = frozenView)!!
                val cached = File(first.codePaths.single())
                val published = cached.lastModified()
                val damaged = cached.readBytes().also { bytes -> bytes[bytes.size / 3] = (bytes[bytes.size / 3] + 1).toByte() }
                cached.setWritable(true)
                cached.writeBytes(damaged)
                cached.setLastModified(published)
                cached.setReadOnly()
                assumeFalse("root can write read-only files, so the marker never applies", cached.canWrite())
                val trusted = DexSourceFingerprint.inspectCodePaths(listOf(source.path), cache, materializeArchiveView = frozenView)
                assertEquals(first, trusted)
                assertTrue(cached.readBytes().contentEquals(damaged))
            } finally {
                directory.walkBottomUp().forEach { it.setWritable(true) }
            }
        }
    }

    @Test
    fun verifyAllCases() {
        sameDexProducesStableFingerprintAndIgnoresResources()
        changedSecondaryDexChangesFingerprint()
        archiveWithoutDexIsRejected()
        npatchFingerprintAndQueryPathUseOriginalInsteadOfLoaderDex()
        repatchingLoaderRetainsFingerprintButChangingOriginalInvalidatesIt()
        lspatchOriginalAndCodeSplitsProvideQueryPathWithOrWithoutIoRedirection()
        lspatchRepatchingAndCacheRepairPreserveOriginalIdentity()
        lspatchAndNpatchMissingCorruptOrOversizedOriginalsNeverSelectLoaderDex()
        mixedPatchCarriersCannotChooseAnArbitraryOriginal()
        sourceCacheIsRebuiltAfterLossAndSameLengthCorruption()
        originalAndCodeSplitsShareSameFingerprintBoundaryWithBoundedCache()
        publishedSourceWritesMarkerOnceAndKeepsTimestamp()
        anyRealModificationOfPublishedSourceIsCaughtAndRepaired()
        cachePublishedBeforeMarkersExistedIsVerifiedOnceAndThenMarked()
        alreadyRedirectedJavaViewIsFrozenForNativeCallers()
        activeCodeSplitsAndNonCodeSplitsAndMultiArchiveLimit()
        resourceSplitsDoNotInvalidateEitherPatchedSourceWithFrozenViews()
        canonicalizesDotPathsFallsBackToPublicDirsAndPreservesRealSplitNames()
    }
}
