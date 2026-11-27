package com.Bilibili_Innocent_Lab.xposedmodule.runtime.payload

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.assets.AudioAsset
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.assets.AudioAssetFailure
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.assets.AudioAssetStore
import com.bilab.testfixtures.TestElf16KbBuilder
import com.bilab.testfixtures.TestZipBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VerifiedPayloadDeliveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun elf16KbVerifierAccepts16KbAnd64KbAndRejects4KbOrMalformedHeaders() {
        val valid16k = TestElf16KbBuilder.buildElf64(16384L)
        assertTrue(Elf16KbVerifier.verifyElf64PageAlignment(valid16k, "libvalid16k.so").isSuccess)

        val valid64k = TestElf16KbBuilder.buildElf64(65536L)
        assertTrue(Elf16KbVerifier.verifyElf64PageAlignment(valid64k, "libvalid64k.so").isSuccess)

        val invalid4k = TestElf16KbBuilder.buildElf64(4096L)
        val fail4k = Elf16KbVerifier.verifyElf64PageAlignment(invalid4k, "libbad4k.so")
        assertTrue(fail4k.isFailure)
        assertTrue(fail4k.exceptionOrNull()?.message.orEmpty().contains("16KB"))

        // 任一 PT_LOAD 段未 16KB 对齐（首段 16KB + 次段 4KB）均必须拒绝
        val mixedAlign = TestElf16KbBuilder.buildElf64WithSegments(listOf(16384L, 4096L))
        assertTrue(Elf16KbVerifier.verifyElf64PageAlignment(mixedAlign, "libmixed.so").isFailure)

        // 非 16KB 整数倍（如 24576）同样必须拒绝
        val unaligned = TestElf16KbBuilder.buildElf64(24576L)
        assertTrue(Elf16KbVerifier.verifyElf64PageAlignment(unaligned, "lib24k.so").isFailure)

        // 截断头、错误魔数、32 位/大端、phoff 溢出、无 PT_LOAD 均需 fail-closed
        assertTrue(Elf16KbVerifier.verifyElf64PageAlignment(ByteArray(32), "tiny").isFailure)

        val badMagic = valid16k.copyOf().apply { this[0] = 0 }
        assertTrue(Elf16KbVerifier.verifyElf64PageAlignment(badMagic, "bad-magic").isFailure)

        val bigEndian = valid16k.copyOf().apply { this[5] = 2 }
        assertTrue(Elf16KbVerifier.verifyElf64PageAlignment(bigEndian, "big-endian").isFailure)

        val overflowPhoff = valid16k.copyOf()
        ByteBuffer.wrap(overflowPhoff).order(ByteOrder.LITTLE_ENDIAN).putLong(32, Long.MAX_VALUE)
        assertTrue(Elf16KbVerifier.verifyElf64PageAlignment(overflowPhoff, "overflow-phoff").isFailure)

        val noLoad = valid16k.copyOf()
        val buf = ByteBuffer.wrap(noLoad).order(ByteOrder.LITTLE_ENDIAN)
        val phoff = buf.getLong(32).toInt()
        buf.putInt(phoff, 2) // PT_DYNAMIC instead of PT_LOAD
        assertTrue(Elf16KbVerifier.verifyElf64PageAlignment(noLoad, "no-load").isFailure)

        val elfFile = tempFolder.newFile("libfile16k.so").apply { writeBytes(valid16k) }
        assertTrue(Elf16KbVerifier.verifyElf64FilePageAlignment(elfFile).isSuccess)
    }

    @Test
    fun elf16KbVerifierEnforcesCongruenceBoundsOverlapAndNoTextrel() {
        // 同权限多 PT_LOAD（例如 TestElf16KbBuilder 构造的最小双 PF_RX 头）允许共页
        val sameFlagMultiSeg = TestElf16KbBuilder.buildElf64WithSegments(listOf(16384L, 16384L))
        assertTrue(Elf16KbVerifier.verifyElf64PageAlignment(sameFlagMultiSeg, "same-flags").isSuccess)

        // 非 0 同余偏移（p_offset % 16384 == p_vaddr % 16384）放行
        val congruent = buildElf64Custom(
            totalBytes = 8192,
            segments = listOf(
                ElfSegSpec(type = 1, flags = 5, offset = 4096L, vaddr = 16384L + 4096L, filesz = 128L, memsz = 128L, align = 16384L)
            )
        )
        assertTrue(Elf16KbVerifier.verifyElf64PageAlignment(congruent, "congruent").isSuccess)

        // 段同余错位（p_offset % 16384 != p_vaddr % 16384）拒绝
        val incongruent = buildElf64Custom(
            totalBytes = 8192,
            segments = listOf(
                ElfSegSpec(type = 1, flags = 5, offset = 4096L, vaddr = 0L, filesz = 128L, memsz = 128L, align = 16384L)
            )
        )
        assertTrue(Elf16KbVerifier.verifyElf64PageAlignment(incongruent, "incongruent").isFailure)

        // p_memsz < p_filesz 或文件越界拒绝
        val memLtFile = buildElf64Custom(
            totalBytes = 1024,
            segments = listOf(
                ElfSegSpec(type = 1, flags = 5, offset = 0L, vaddr = 0L, filesz = 256L, memsz = 128L, align = 16384L)
            )
        )
        assertTrue(Elf16KbVerifier.verifyElf64PageAlignment(memLtFile, "memsz-lt-filesz").isFailure)

        val fileOob = buildElf64Custom(
            totalBytes = 1024,
            segments = listOf(
                ElfSegSpec(type = 1, flags = 5, offset = 512L, vaddr = 512L, filesz = 600L, memsz = 600L, align = 16384L)
            )
        )
        assertTrue(Elf16KbVerifier.verifyElf64PageAlignment(fileOob, "file-oob").isFailure)

        // 异权 PT_LOAD（PF_RX=5 与 PF_RW=6）落入同一 16KB 虚拟页必须拒绝
        val overlapDiffFlags = buildElf64Custom(
            totalBytes = 8192,
            segments = listOf(
                ElfSegSpec(type = 1, flags = 5, offset = 0L, vaddr = 0L, filesz = 4096L, memsz = 4096L, align = 16384L),
                ElfSegSpec(type = 1, flags = 6, offset = 4096L, vaddr = 4096L, filesz = 128L, memsz = 128L, align = 16384L)
            )
        )
        assertTrue(Elf16KbVerifier.verifyElf64PageAlignment(overlapDiffFlags, "diff-flags-overlap").isFailure)

        // 异权 PT_LOAD 位于不交叠 16KB 虚拟页时允许
        val disjointDiffFlags = buildElf64Custom(
            totalBytes = 20480,
            segments = listOf(
                ElfSegSpec(type = 1, flags = 5, offset = 0L, vaddr = 0L, filesz = 4096L, memsz = 4096L, align = 16384L),
                ElfSegSpec(type = 1, flags = 6, offset = 16384L, vaddr = 16384L, filesz = 128L, memsz = 128L, align = 16384L)
            )
        )
        assertTrue(Elf16KbVerifier.verifyElf64PageAlignment(disjointDiffFlags, "diff-flags-disjoint").isSuccess)

        // PT_DYNAMIC 包含 DT_TEXTREL (22) 或 DT_FLAGS (30) & DF_TEXTREL (0x4) 必须拒绝
        val dtTextrel = buildElf64WithDynamic(dTag = 22L, dVal = 0L)
        assertTrue(Elf16KbVerifier.verifyElf64PageAlignment(dtTextrel, "dt-textrel").isFailure)

        val dfTextrel = buildElf64WithDynamic(dTag = 30L, dVal = 0x4L)
        assertTrue(Elf16KbVerifier.verifyElf64PageAlignment(dfTextrel, "df-textrel").isFailure)

        val dfBindNow = buildElf64WithDynamic(dTag = 30L, dVal = 0x8L)
        assertTrue(Elf16KbVerifier.verifyElf64PageAlignment(dfBindNow, "df-bind-now").isSuccess)

        val badElfFile = tempFolder.newFile("bad-textrel.so").apply { writeBytes(dtTextrel) }
        assertThrows(IOException::class.java) {
            Elf16KbVerifier.requireElf64FilePageAlignment(badElfFile, "bad-textrel.so")
        }
    }

    @Test
    fun elf32VerifierEnforcesAlignmentCongruenceOverlapAndNoTextrel() {
        val valid4k = buildElf32Custom(
            totalBytes = 512,
            segments = listOf(
                ElfSegSpec(type = 1, flags = 5, offset = 0L, vaddr = 0L, filesz = 512L, memsz = 512L, align = 4096L)
            )
        )
        assertTrue(Elf16KbVerifier.verifyElf32PageAlignment(valid4k, "elf32-4k").isSuccess)
        val elf32File = tempFolder.newFile("lib32.so").apply { writeBytes(valid4k) }
        assertTrue(Elf16KbVerifier.verifyElf32FilePageAlignment(elf32File).isSuccess)

        val bad2k = buildElf32Custom(
            totalBytes = 512,
            segments = listOf(
                ElfSegSpec(type = 1, flags = 5, offset = 0L, vaddr = 0L, filesz = 512L, memsz = 512L, align = 2048L)
            )
        )
        assertTrue(Elf16KbVerifier.verifyElf32PageAlignment(bad2k, "elf32-2k").isFailure)

        val incongruent32 = buildElf32Custom(
            totalBytes = 4096,
            segments = listOf(
                ElfSegSpec(type = 1, flags = 5, offset = 1024L, vaddr = 0L, filesz = 128L, memsz = 128L, align = 4096L)
            )
        )
        assertTrue(Elf16KbVerifier.verifyElf32PageAlignment(incongruent32, "elf32-incongruent").isFailure)

        val overlap32 = buildElf32Custom(
            totalBytes = 4096,
            segments = listOf(
                ElfSegSpec(type = 1, flags = 5, offset = 0L, vaddr = 0L, filesz = 1024L, memsz = 1024L, align = 4096L),
                ElfSegSpec(type = 1, flags = 6, offset = 1024L, vaddr = 1024L, filesz = 128L, memsz = 128L, align = 4096L)
            )
        )
        assertTrue(Elf16KbVerifier.verifyElf32PageAlignment(overlap32, "elf32-overlap").isFailure)

        val textrel32 = buildElf32WithDynamic(dTag = 22, dVal = 0L)
        assertTrue(Elf16KbVerifier.verifyElf32PageAlignment(textrel32, "elf32-textrel").isFailure)
    }

    @Test
    fun archiveVerifierAllowsSmallHighRatioZeroPaddedSoBelow256KbAndRejects1MbZipBomb() {
        // 200 KiB (< 256 KiB 阈值) 高压缩比全零载荷（模拟 16KB 零填充小 .so）应放行
        val smallZeroPaddedSo = ByteArray(200 * 1024)
        val smallZip = TestZipBuilder.buildZip(
            tempFolder.newFile("small-padded-so.zip"),
            mapOf("lib/arm64-v8a/libsmall_16kb.so" to smallZeroPaddedSo)
        )
        val smallVerified = ArchiveVerifier.verifyZip(
            smallZip,
            ZipLimits(
                maxEntries = 16,
                maxEntryBytes = 4L * 1024L * 1024L,
                maxTotalBytes = 4L * 1024L * 1024L,
                maxCompressionRatio = 50.0,
                forbiddenExtensions = emptySet()
            )
        )
        assertTrue(smallVerified.isSuccess)
        assertEquals(200L * 1024L, smallVerified.getOrThrow().single().size)

        // 1 MiB 真正高压缩 Zip 炸弹必须继续拦截
        val bomb1Mb = ByteArray(1024 * 1024)
        val bombZip = TestZipBuilder.buildZip(
            tempFolder.newFile("bomb-1mb.zip"),
            mapOf("payload.json" to bomb1Mb)
        )
        val bombResult = ArchiveVerifier.verifyZip(
            bombZip,
            ZipLimits(
                maxEntries = 16,
                maxEntryBytes = 4L * 1024L * 1024L,
                maxTotalBytes = 4L * 1024L * 1024L,
                maxCompressionRatio = 50.0
            )
        )
        assertTrue(bombResult.isFailure)
    }

    @Test
    fun audioAssetStoreRejects4KbArm64ElfAndAccepts16KbElf() {
        val store = AudioAssetStore(tempFolder.newFolder("audio-elf-store"), hostAbi = "arm64-v8a")
        val bad4k = TestElf16KbBuilder.buildElf64(4096L)
        val badAsset = AudioAsset("libonnxruntime.so", bad4k.size.toLong(), sha256Hex(bad4k), "jni/arm64-v8a/libonnxruntime.so")
        val ex = assertThrows(AudioAssetFailure::class.java) {
            store.install(badAsset) { ByteArrayInputStream(bad4k) }
        }
        assertEquals("elf_alignment_invalid", ex.code)
        assertFalse(store.file(badAsset).exists())

        val good16k = TestElf16KbBuilder.buildElf64(16384L)
        val goodAsset = AudioAsset("libonnxruntime.so", good16k.size.toLong(), sha256Hex(good16k), "jni/arm64-v8a/libonnxruntime.so")
        val installed = store.install(goodAsset) { ByteArrayInputStream(good16k) }
        assertTrue(installed.isFile)
        assertTrue(store.valid(goodAsset))
    }

    @Test
    fun archiveVerifierAcceptsSafeEntriesAndRejectsTraversalBombLimitsAndForbiddenExtensions() {
        val jsonPayload = """{"rules":[{"id":"r1","pattern":"spam"}]}""".toByteArray()
        val emptyPayload = ByteArray(0)
        val validZip = TestZipBuilder.buildZip(
            tempFolder.newFile("valid.bilp"),
            linkedMapOf(
                "manifest.json" to jsonPayload,
                "rules/empty.txt" to emptyPayload
            )
        )
        val entries = ArchiveVerifier.verifyZip(validZip, ZipLimits.TIER_B_DEFAULTS).getOrThrow()
        assertEquals(2, entries.size)
        assertEquals("manifest.json", entries[0].name)
        assertEquals(jsonPayload.size.toLong(), entries[0].size)
        assertEquals(sha256Hex(jsonPayload), entries[0].sha256)
        assertEquals("rules/empty.txt", entries[1].name)
        assertEquals(0L, entries[1].size)
        assertEquals(sha256Hex(emptyPayload), entries[1].sha256)

        // 路径穿越（.. / 前导 / / 反斜杠 \）必须拒绝
        val traversalPaths = listOf("../escape.json", "a/../b.json", "/etc/passwd", "dir\\file.json", "./dot.json")
        for (badPath in traversalPaths) {
            val zip = TestZipBuilder.buildZip(
                tempFolder.newFile("traversal-${badPath.hashCode()}.zip"),
                mapOf(badPath to jsonPayload)
            )
            assertTrue("Expected failure for $badPath", ArchiveVerifier.verifyZip(zip, ZipLimits.TIER_B_DEFAULTS).isFailure)
        }

        // Zip Bomb 高压缩比防御
        val bombBytes = ByteArray(256 * 1024)
        val bombZipBytes = TestZipBuilder.buildZipBytes(mapOf("payload.json" to bombBytes))
        val bombZip = tempFolder.newFile("bomb.zip").apply { writeBytes(bombZipBytes) }
        val bombResult = ArchiveVerifier.verifyZip(
            bombZip,
            ZipLimits(maxEntries = 16, maxEntryBytes = 1024 * 1024, maxTotalBytes = 1024 * 1024, maxCompressionRatio = 20.0)
        )
        assertTrue(bombResult.isFailure)

        // 条目数上限 / 单条目字节上限 / 总字节上限
        val multiZip = TestZipBuilder.buildZip(
            tempFolder.newFile("multi.zip"),
            linkedMapOf(
                "a.json" to ByteArray(60) { 1 },
                "b.json" to ByteArray(60) { 2 },
                "c.json" to ByteArray(60) { 3 }
            )
        )
        assertTrue(ArchiveVerifier.verifyZip(multiZip, ZipLimits(maxEntries = 2)).isFailure)
        assertTrue(ArchiveVerifier.verifyZip(multiZip, ZipLimits(maxEntryBytes = 50L)).isFailure)
        assertTrue(ArchiveVerifier.verifyZip(multiZip, ZipLimits(maxTotalBytes = 150L)).isFailure)

        // Tier-B 禁止扩展名（不区分大小写）
        for (forbiddenName in listOf("classes.dex", "lib/arm64-v8a/libfoo.so", "nested/evil.JAR", "app.Apk")) {
            val zip = TestZipBuilder.buildZip(
                tempFolder.newFile("forbid-${forbiddenName.hashCode()}.zip"),
                mapOf(forbiddenName to byteArrayOf(1, 2, 3, 4))
            )
            assertTrue(
                "Forbidden extension $forbiddenName must fail",
                ArchiveVerifier.verifyZip(zip, ZipLimits.TIER_B_DEFAULTS).isFailure
            )
        }

        // 允许关闭扩展名黑名单供 Tier-C 验包前按需核验
        val tierCZip = TestZipBuilder.buildZip(
            tempFolder.newFile("tierc.zip"),
            mapOf("classes.dex" to byteArrayOf(1, 2, 3, 4))
        )
        assertTrue(
            ArchiveVerifier.verifyZip(
                tierCZip,
                ZipLimits(forbiddenExtensions = emptySet(), maxCompressionRatio = 100.0)
            ).isSuccess
        )
    }

    @Test
    fun securePayloadStorePreparesDirectoryEnforcesPermissionsAndPublishesAtomically() {
        val root = tempFolder.newFolder("store-root")
        val chmodEvents = mutableListOf<Pair<Path, Int>>()
        val dir = SecurePayloadStore.prepareDirectory(
            root = root,
            relativePath = "plugins/demo",
            posixChmod = { path, mode -> chmodEvents += path to mode }
        ).getOrThrow()
        assertTrue(dir.isDirectory)
        assertEquals(2, chmodEvents.size)
        assertTrue(chmodEvents.all { it.second == SecurePayloadStore.MODE_DIR_PRIVATE })
        assertTrue(SecurePayloadStore.prepareDirectory(root, "../escape").isFailure)

        val payload = ByteArray(16 * 1024) { (it * 13).toByte() }
        val sha = sha256Hex(payload)
        chmodEvents.clear()
        val target = SecurePayloadStore.installVerified(
            directory = dir,
            targetFileName = "plugin.bilp",
            expectedSize = payload.size.toLong(),
            expectedSha256 = sha,
            input = { ByteArrayInputStream(payload) },
            posixChmod = { path, mode -> chmodEvents += path to mode }
        ).getOrThrow()
        assertArrayEquals(payload, target.readBytes())
        assertTrue(chmodEvents.any { it.second == SecurePayloadStore.MODE_FILE_READONLY })
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".part") })

        // 命中缓存时不再读取远程流，并修复文件权限
        chmodEvents.clear()
        val cached = SecurePayloadStore.installVerified(
            directory = dir,
            targetFileName = "plugin.bilp",
            expectedSize = payload.size.toLong(),
            expectedSha256 = sha,
            input = { error("must reuse verified file") },
            posixChmod = { path, mode -> chmodEvents += path to mode }
        ).getOrThrow()
        assertEquals(target.canonicalPath, cached.canonicalPath)
        assertEquals(listOf(target.toPath() to SecurePayloadStore.MODE_FILE_READONLY), chmodEvents)

        // 损坏流、长度不足、长度溢出均不可覆盖既有已验证文件且不留临时文件
        val badHash = SecurePayloadStore.installVerified(
            directory = dir,
            targetFileName = "plugin.bilp",
            expectedSize = payload.size.toLong(),
            expectedSha256 = "0".repeat(64),
            input = { ByteArrayInputStream(payload) }
        )
        assertTrue(badHash.isFailure)
        assertEquals("hash_mismatch", (badHash.exceptionOrNull() as? PayloadVerifyException)?.code)

        val shortFile = SecurePayloadStore.installVerified(
            directory = dir,
            targetFileName = "short.bilp",
            expectedSize = payload.size.toLong(),
            expectedSha256 = sha,
            input = { ByteArrayInputStream(payload.copyOf(payload.size - 1)) }
        )
        assertTrue(shortFile.isFailure)
        assertEquals("size_mismatch", (shortFile.exceptionOrNull() as? PayloadVerifyException)?.code)
        assertFalse(File(dir, "short.bilp").exists())

        val overflowFile = SecurePayloadStore.installVerified(
            directory = dir,
            targetFileName = "overflow.bilp",
            expectedSize = payload.size.toLong(),
            expectedSha256 = sha,
            input = { ByteArrayInputStream(payload + 1) }
        )
        assertTrue(overflowFile.isFailure)
        assertEquals("size_overflow", (overflowFile.exceptionOrNull() as? PayloadVerifyException)?.code)
        assertFalse(File(dir, "overflow.bilp").exists())

        assertArrayEquals(payload, target.readBytes())
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".part") })

        val atomicFile = File(dir, "atomic.json")
        val atomicBytes = """{"ok":true}""".toByteArray()
        SecurePayloadStore.writeAtomically(atomicFile, atomicBytes, enforcePermissions = false).getOrThrow()
        assertArrayEquals(atomicBytes, atomicFile.readBytes())

        // 并发原子发布同一个文件保持完整正确且无残留临时文件
        val concurrentDir = SecurePayloadStore.prepareDirectory(root, "concurrent", enforcePermissions = false).getOrThrow()
        val pool = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        try {
            val futures = (0..1).map {
                pool.submit<File> {
                    SecurePayloadStore.installVerified(
                        directory = concurrentDir,
                        targetFileName = "model.bin",
                        expectedSize = payload.size.toLong(),
                        expectedSha256 = sha,
                        input = {
                            barrier.await(5, TimeUnit.SECONDS)
                            ByteArrayInputStream(payload)
                        },
                        enforcePermissions = false
                    ).getOrThrow()
                }
            }
            futures.forEach { assertArrayEquals(payload, it.get(10, TimeUnit.SECONDS).readBytes()) }
            assertEquals(listOf("model.bin"), concurrentDir.list()?.toList())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun securePayloadStoreRejectsSymbolicLinksViaNoFollowLinks() {
        val root = tempFolder.newFolder("symlink-root")
        val realDir = File(root, "real").apply { mkdirs() }
        val linkPath = root.toPath().resolve("linked")
        val linkCreated = try {
            Files.createSymbolicLink(linkPath, realDir.toPath())
            true
        } catch (_: Throwable) {
            false
        }
        assumeTrue("Host OS does not allow creating symlinks in test sandbox", linkCreated)

        assertFalse(SecurePayloadStore.isSafeDirectory(linkPath.toFile()))
        assertTrue(SecurePayloadStore.prepareDirectory(root, "linked", enforcePermissions = false).isFailure)
    }

    @Test
    fun dualChannelFdOpenerPrefersRemoteAndFallsBackToProvider() {
        var providerCalled = 0
        val primary = DualChannelFdOpener.openReadOnly(
            openRemote = { ByteArrayInputStream("remote".toByteArray()) },
            openProvider = {
                providerCalled++
                ByteArrayInputStream("provider".toByteArray())
            }
        )
        assertNotNull(primary)
        assertEquals("remote", primary!!.use { String(it.readBytes()) })
        assertEquals(0, providerCalled)

        val fallbackOnNull = DualChannelFdOpener.openReadOnly(
            openRemote = { null },
            openProvider = { ByteArrayInputStream("provider-null".toByteArray()) }
        )
        assertEquals("provider-null", fallbackOnNull!!.use { String(it.readBytes()) })

        val fallbackOnThrow = DualChannelFdOpener.openReadOnly(
            openRemote = { error("remote binder died") },
            openProvider = { ByteArrayInputStream("provider-throw".toByteArray()) }
        )
        assertEquals("provider-throw", fallbackOnThrow!!.use { String(it.readBytes()) })

        val bothFailed = DualChannelFdOpener.openReadOnly(
            openRemote = { error("remote failed") },
            openProvider = { error("provider failed") }
        )
        assertNull(bothFailed)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        SecurePayloadStore.sha256Hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private data class ElfSegSpec(
        val type: Int,
        val flags: Int,
        val offset: Long,
        val vaddr: Long,
        val filesz: Long,
        val memsz: Long,
        val align: Long
    )

    private fun buildElf64Custom(totalBytes: Int, segments: List<ElfSegSpec>): ByteArray {
        val ehdrSize = 64
        val phdrSize = 56
        val minBytes = ehdrSize + segments.size * phdrSize
        require(totalBytes >= minBytes)
        val bytes = ByteArray(totalBytes)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes[0] = 0x7f
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2
        bytes[5] = 1
        bytes[6] = 1
        buf.putShort(16, 3.toShort())
        buf.putShort(18, 183.toShort())
        buf.putInt(20, 1)
        buf.putLong(32, ehdrSize.toLong())
        buf.putShort(52, ehdrSize.toShort())
        buf.putShort(54, phdrSize.toShort())
        buf.putShort(56, segments.size.toShort())
        segments.forEachIndexed { idx, seg ->
            val off = ehdrSize + idx * phdrSize
            buf.putInt(off, seg.type)
            buf.putInt(off + 4, seg.flags)
            buf.putLong(off + 8, seg.offset)
            buf.putLong(off + 16, seg.vaddr)
            buf.putLong(off + 24, seg.vaddr)
            buf.putLong(off + 32, seg.filesz)
            buf.putLong(off + 40, seg.memsz)
            buf.putLong(off + 48, seg.align)
        }
        return bytes
    }

    private fun buildElf64WithDynamic(dTag: Long, dVal: Long): ByteArray {
        val dynOff = 64 + 2 * 56
        val total = dynOff + 32
        val bytes = buildElf64Custom(
            totalBytes = total,
            segments = listOf(
                ElfSegSpec(type = 1, flags = 5, offset = 0L, vaddr = 0L, filesz = total.toLong(), memsz = total.toLong(), align = 16384L),
                ElfSegSpec(type = 2, flags = 6, offset = dynOff.toLong(), vaddr = dynOff.toLong(), filesz = 32L, memsz = 32L, align = 8L)
            )
        )
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(dynOff, dTag)
        buf.putLong(dynOff + 8, dVal)
        buf.putLong(dynOff + 16, 0L)
        buf.putLong(dynOff + 24, 0L)
        return bytes
    }

    private fun buildElf32Custom(totalBytes: Int, segments: List<ElfSegSpec>): ByteArray {
        val ehdrSize = 52
        val phdrSize = 32
        val minBytes = ehdrSize + segments.size * phdrSize
        require(totalBytes >= minBytes)
        val bytes = ByteArray(totalBytes)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes[0] = 0x7f
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 1
        bytes[5] = 1
        bytes[6] = 1
        buf.putShort(16, 3.toShort())
        buf.putShort(18, 40.toShort()) // EM_ARM
        buf.putInt(20, 1)
        buf.putInt(28, ehdrSize)
        buf.putShort(40, ehdrSize.toShort())
        buf.putShort(42, phdrSize.toShort())
        buf.putShort(44, segments.size.toShort())
        segments.forEachIndexed { idx, seg ->
            val off = ehdrSize + idx * phdrSize
            buf.putInt(off, seg.type)
            buf.putInt(off + 4, seg.offset.toInt())
            buf.putInt(off + 8, seg.vaddr.toInt())
            buf.putInt(off + 12, seg.vaddr.toInt())
            buf.putInt(off + 16, seg.filesz.toInt())
            buf.putInt(off + 20, seg.memsz.toInt())
            buf.putInt(off + 24, seg.flags)
            buf.putInt(off + 28, seg.align.toInt())
        }
        return bytes
    }

    private fun buildElf32WithDynamic(dTag: Int, dVal: Long): ByteArray {
        val dynOff = 52 + 2 * 32
        val total = dynOff + 16
        val bytes = buildElf32Custom(
            totalBytes = total,
            segments = listOf(
                ElfSegSpec(type = 1, flags = 5, offset = 0L, vaddr = 0L, filesz = total.toLong(), memsz = total.toLong(), align = 4096L),
                ElfSegSpec(type = 2, flags = 6, offset = dynOff.toLong(), vaddr = dynOff.toLong(), filesz = 16L, memsz = 16L, align = 4L)
            )
        )
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(dynOff, dTag)
        buf.putInt(dynOff + 4, dVal.toInt())
        buf.putInt(dynOff + 8, 0)
        buf.putInt(dynOff + 12, 0)
        return bytes
    }
}
