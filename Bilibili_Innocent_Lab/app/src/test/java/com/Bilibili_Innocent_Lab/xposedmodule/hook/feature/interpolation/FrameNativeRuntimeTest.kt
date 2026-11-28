package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing.FrameNativeLoader
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ModuleNativeLoader
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FrameNativeRuntimeTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        ModuleNativeLoader.clearRegisteredApkForTest()
        FramePreparationMemory.resetFallbackForTest()
    }

    @After
    fun tearDown() {
        ModuleNativeLoader.clearRegisteredApkForTest()
        FramePreparationMemory.resetFallbackForTest()
    }

    private fun elfHeader(eiClass: Int, eMachine: Int, eiData: Int = 1): ByteArray = ByteArray(64).apply {
        this[0] = 0x7f
        this[1] = 'E'.code.toByte()
        this[2] = 'L'.code.toByte()
        this[3] = 'F'.code.toByte()
        this[4] = eiClass.toByte()
        this[5] = eiData.toByte()
        if (eiData == 1) {
            this[18] = (eMachine and 0xFF).toByte()
            this[19] = ((eMachine ushr 8) and 0xFF).toByte()
        } else {
            this[18] = ((eMachine ushr 8) and 0xFF).toByte()
            this[19] = (eMachine and 0xFF).toByte()
        }
    }

    private fun minimalElf64(align: Long = 16384L): ByteArray {
        val totalSize = 64 + 56
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0, 0x7f)
        buf.put(1, 'E'.code.toByte())
        buf.put(2, 'L'.code.toByte())
        buf.put(3, 'F'.code.toByte())
        buf.put(4, 2)
        buf.put(5, 1)
        buf.putShort(16, 3)
        buf.putShort(18, 183)
        buf.putInt(20, 1)
        buf.putLong(32, 64L)
        buf.putShort(52, 64)
        buf.putShort(54, 56)
        buf.putShort(56, 1)

        val phOff = 64
        buf.putInt(phOff, 1)
        buf.putInt(phOff + 4, 5)
        buf.putLong(phOff + 8, 0L)
        buf.putLong(phOff + 16, 0L)
        buf.putLong(phOff + 24, 0L)
        buf.putLong(phOff + 32, totalSize.toLong())
        buf.putLong(phOff + 40, totalSize.toLong())
        buf.putLong(phOff + 48, align)
        return buf.array()
    }

    private fun writeStoredApk(target: File, entryName: String, payload: ByteArray, alignment: Int) {
        val nameBytesLen = entryName.toByteArray(Charsets.UTF_8).size
        val baseHeader = 30 + nameBytesLen
        val extraLen = if (alignment > 1) (alignment - (baseHeader % alignment)) % alignment else 0
        val crc = CRC32().apply { update(payload) }.value
        ZipOutputStream(FileOutputStream(target)).use { zos ->
            val entry = ZipEntry(entryName).apply {
                method = ZipEntry.STORED
                size = payload.size.toLong()
                compressedSize = payload.size.toLong()
                this.crc = crc
                if (extraLen > 0) extra = ByteArray(extraLen)
            }
            zos.putNextEntry(entry)
            zos.write(payload)
            zos.closeEntry()
        }
    }

    @Test
    fun resolvesProcessAndNativeAbiAndSearchesAlignedApkEntries() {
        // ELF machine ABI parsing and Houdini false-ARM rejection.
        assertEquals("arm64-v8a", ModuleNativeLoader.parseElfMachineAbi(elfHeader(2, 183)))
        assertEquals("armeabi-v7a", ModuleNativeLoader.parseElfMachineAbi(elfHeader(1, 40)))
        assertEquals("x86_64", ModuleNativeLoader.parseElfMachineAbi(elfHeader(2, 62)))
        assertEquals("x86", ModuleNativeLoader.parseElfMachineAbi(elfHeader(1, 3)))
        assertEquals("riscv64", ModuleNativeLoader.parseElfMachineAbi(elfHeader(2, 243)))
        assertNull(ModuleNativeLoader.parseElfMachineAbi(ByteArray(12)))
        assertNull(ModuleNativeLoader.parseElfMachineAbi(ByteArray(64)))

        assertEquals(
            "x86_64",
            ModuleNativeLoader.resolveProcessAbi(
                is64Bit = true,
                elfHeader = elfHeader(2, 62),
                unameMachine = "x86_64",
                supported64 = arrayOf("x86_64", "arm64-v8a"),
                supported32 = arrayOf("x86", "armeabi-v7a")
            )
        )
        assertEquals(
            "x86_64",
            ModuleNativeLoader.resolveProcessAbi(
                is64Bit = true,
                elfHeader = elfHeader(2, 183),
                unameMachine = "x86_64",
                supported64 = arrayOf("arm64-v8a"),
                supported32 = arrayOf("armeabi-v7a")
            )
        )
        assertEquals(
            "arm64-v8a",
            ModuleNativeLoader.resolveProcessAbi(
                is64Bit = true,
                elfHeader = elfHeader(2, 183),
                unameMachine = "aarch64",
                supported64 = arrayOf("arm64-v8a"),
                supported32 = arrayOf("armeabi-v7a")
            )
        )

        // FrameNativeLoader rejects translated x86/x86_64 and accepts native ARM.
        val select = FrameNativeLoader::class.java.getDeclaredMethod(
            "selectRuntimeAbi",
            Boolean::class.javaPrimitiveType,
            ByteArray::class.java,
            String::class.java,
            Array<String>::class.java
        ).apply { isAccessible = true }
        assertNull(select.invoke(null, true, elfHeader(2, 62), "x86_64", arrayOf("x86_64", "arm64-v8a")))
        assertNull(select.invoke(null, true, elfHeader(2, 183), "x86_64", arrayOf("x86_64", "arm64-v8a")))
        assertNull(select.invoke(null, false, elfHeader(1, 3), "i686", arrayOf("x86", "armeabi-v7a")))
        assertEquals("arm64-v8a", select.invoke(null, true, elfHeader(2, 183), "aarch64", arrayOf("arm64-v8a")))
        assertEquals("armeabi-v7a", select.invoke(null, false, elfHeader(1, 40), "aarch64", arrayOf("armeabi-v7a")))

        // ModuleNativeLoader searches nativeLibraryDir and 16KB page-aligned split APKs.
        val tempDir = tempFolder.newFolder("native-loader")
        val libDir = File(tempDir, "nativeLib").apply { mkdirs() }
        val baseApk = File(tempDir, "base.apk")
        val splitApk = File(tempDir, "split_config.arm64_v8a.apk")

        val validSo = File(libDir, "libtest_wire.so")
        validSo.writeBytes(minimalElf64(16384L))
        writeStoredApk(baseApk, "META-INF/MANIFEST.MF", byteArrayOf(1, 2, 3), alignment = 4)

        ModuleNativeLoader.registerModuleApk(baseApk.path, libDir.path, null)
        val loadedPaths = mutableListOf<String>()
        ModuleNativeLoader.loadWith(
            name = "test_wire",
            loadLibrary = { throw UnsatisfiedLinkError("not on host path") },
            loadPath = { path -> loadedPaths += path },
            abiProvider = { "arm64-v8a" },
            runtimePageSize = 16384L
        )
        assertEquals(listOf(validSo.canonicalPath), loadedPaths)

        validSo.delete()
        writeStoredApk(splitApk, "lib/arm64-v8a/libsplit_mod.so", minimalElf64(16384L), alignment = 16384)
        ModuleNativeLoader.registerModuleApk(baseApk.path, libDir.path, arrayOf(splitApk.path))
        val splitLoaded = mutableListOf<String>()
        ModuleNativeLoader.loadWith(
            name = "split_mod",
            loadLibrary = { throw UnsatisfiedLinkError("not extracted") },
            loadPath = { path -> splitLoaded += path },
            abiProvider = { "arm64-v8a" },
            runtimePageSize = 16384L
        )
        assertEquals(listOf("${splitApk.canonicalPath}!/lib/arm64-v8a/libsplit_mod.so"), splitLoaded)

        val unalignedSplit = File(tempDir, "split_unaligned.apk")
        writeStoredApk(unalignedSplit, "lib/arm64-v8a/libbad_align.so", minimalElf64(16384L), alignment = 4)
        ModuleNativeLoader.registerModuleApk(baseApk.path, libDir.path, arrayOf(unalignedSplit.path))
        assertThrows(UnsatisfiedLinkError::class.java) {
            ModuleNativeLoader.loadWith(
                name = "bad_align",
                loadLibrary = { throw UnsatisfiedLinkError("not extracted") },
                loadPath = { error("must not load unaligned split entry") },
                abiProvider = { "arm64-v8a" },
                runtimePageSize = 16384L
            )
        }

        // Canonical APK fallback and missing APK failure preservation.
        ModuleNativeLoader.clearRegisteredApkForTest()
        val fakeApk = tempFolder.newFile("module.apk").canonicalFile
        ModuleNativeLoader.registerModuleApk(fakeApk.path)
        val fallbackPaths = mutableListOf<String>()
        ModuleNativeLoader.loadWith(
            name = "innocent_danmaku_wire",
            loadLibrary = { throw UnsatisfiedLinkError("not on namespace") },
            loadPath = { path -> fallbackPaths += path },
            abiProvider = { "arm64-v8a" }
        )
        assertEquals(listOf("${fakeApk.path}!/lib/arm64-v8a/libinnocent_danmaku_wire.so"), fallbackPaths)
        assertEquals("arm64-v8a", ModuleNativeLoader.selectAbi(true, arrayOf("x86_64", "arm64-v8a"), arrayOf("armeabi-v7a")))
        assertEquals("armeabi-v7a", ModuleNativeLoader.selectAbi(false, arrayOf("arm64-v8a"), arrayOf("armeabi-v7a", "armeabi")))

        ModuleNativeLoader.clearRegisteredApkForTest()
        ModuleNativeLoader.registerModuleApk(File(tempFolder.root, "missing.apk").path)
        val primary = UnsatisfiedLinkError("missing")
        val thrown = assertThrows(UnsatisfiedLinkError::class.java) {
            ModuleNativeLoader.loadWith(
                name = "bil_frame_window",
                loadLibrary = { throw primary },
                loadPath = { error("must not call loadPath without canonical existing APK") },
                abiProvider = { "arm64-v8a" }
            )
        }
        assertSame(primary, thrown)
    }

    @Test
    fun fallsBackToDirectBufferOnLinkageErrorAndPropagatesCallbackErrors() {
        var seenBuffer: ByteBuffer? = null
        FramePreparationMemory.withStorageUsing(
            bytes = 256,
            allocateManaged = { _, _ -> throw UnsatisfiedLinkError("mgmem missing") }
        ) { buffer ->
            seenBuffer = buffer
            assertTrue(buffer.isDirect)
            assertEquals(256, buffer.capacity())
            buffer.putInt(0, 0x12345678)
        }
        assertNotNull(seenBuffer)

        var propagated = false
        try {
            FramePreparationMemory.withStorageUsing(
                bytes = 64,
                allocateManaged = { size, run -> run(ByteBuffer.allocateDirect(size)) }
            ) {
                throw LinkageError("caller bug inside callback")
            }
        } catch (_: LinkageError) {
            propagated = true
        }
        assertTrue(propagated)
    }
}
