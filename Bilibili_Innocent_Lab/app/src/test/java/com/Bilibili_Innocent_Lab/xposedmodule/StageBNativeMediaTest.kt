package com.Bilibili_Innocent_Lab.xposedmodule

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.AudioRescueConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.AudioRescueModel
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.AudioRescueProcessor
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.AudioRescueWrite
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.AudioSpeechEnhancer
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.AudioSpeechModel
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FramePowerAdvisor
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FramePreparationMemory
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameThermalControl
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing.FrameNativeLoader
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ModuleNativeLoader
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Stage B focused JVM verification for native ISA detection, fallback resilience, thermal/ADPF, and audio tuning. */
class StageBNativeMediaTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("stageb-native-media-").toFile()
        ModuleNativeLoader.clearRegisteredApkForTest()
        FramePreparationMemory.resetFallbackForTest()
    }

    @After
    fun tearDown() {
        ModuleNativeLoader.clearRegisteredApkForTest()
        FramePreparationMemory.resetFallbackForTest()
        tempDir.deleteRecursively()
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
        buf.put(4, 2) // ELFCLASS64
        buf.put(5, 1) // ELFDATA2LSB
        buf.putShort(16, 3) // ET_DYN
        buf.putShort(18, 183) // EM_AARCH64
        buf.putInt(20, 1) // EV_CURRENT
        buf.putLong(32, 64L) // e_phoff
        buf.putShort(52, 64) // e_ehsize
        buf.putShort(54, 56) // e_phentsize
        buf.putShort(56, 1) // e_phnum

        val phOff = 64
        buf.putInt(phOff, 1) // PT_LOAD
        buf.putInt(phOff + 4, 5) // PF_R | PF_X
        buf.putLong(phOff + 8, 0L) // p_offset
        buf.putLong(phOff + 16, 0L) // p_vaddr
        buf.putLong(phOff + 24, 0L) // p_paddr
        buf.putLong(phOff + 32, totalSize.toLong()) // p_filesz
        buf.putLong(phOff + 40, totalSize.toLong()) // p_memsz
        buf.putLong(phOff + 48, align) // p_align
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
    fun parseElfMachineAbiAndResolveProcessAbiRejectHoudiniFalseArm() {
        assertEquals("arm64-v8a", ModuleNativeLoader.parseElfMachineAbi(elfHeader(2, 183)))
        assertEquals("armeabi-v7a", ModuleNativeLoader.parseElfMachineAbi(elfHeader(1, 40)))
        assertEquals("x86_64", ModuleNativeLoader.parseElfMachineAbi(elfHeader(2, 62)))
        assertEquals("x86", ModuleNativeLoader.parseElfMachineAbi(elfHeader(1, 3)))
        assertEquals("riscv64", ModuleNativeLoader.parseElfMachineAbi(elfHeader(2, 243)))
        assertNull(ModuleNativeLoader.parseElfMachineAbi(ByteArray(12)))
        assertNull(ModuleNativeLoader.parseElfMachineAbi(ByteArray(64)))

        // WSA / x86_64 emulator + libhoudini: Build.SUPPORTED_64_BIT_ABIS lists arm64-v8a,
        // but /proc/self/exe is x86_64 and/or Os.uname().machine is x86_64.
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
    }

    @Test
    fun frameNativeLoaderSelectRuntimeAbiRejectsTranslatedX86AndNonArmElf() {
        val select = FrameNativeLoader::class.java.getDeclaredMethod(
            "selectRuntimeAbi",
            Boolean::class.javaPrimitiveType,
            ByteArray::class.java,
            String::class.java,
            Array<String>::class.java
        ).apply { isAccessible = true }

        // Even with arm64-v8a in Build.SUPPORTED_64_BIT_ABIS, x86_64 host or x86_64 /proc/self/exe returns null.
        assertNull(select.invoke(null, true, elfHeader(2, 62), "x86_64", arrayOf("x86_64", "arm64-v8a")))
        assertNull(select.invoke(null, true, elfHeader(2, 183), "x86_64", arrayOf("x86_64", "arm64-v8a")))
        assertNull(select.invoke(null, false, elfHeader(1, 3), "i686", arrayOf("x86", "armeabi-v7a")))

        // Native 64-bit ARM and 32-bit ARM on aarch64 kernel pass cleanly.
        assertEquals("arm64-v8a", select.invoke(null, true, elfHeader(2, 183), "aarch64", arrayOf("arm64-v8a")))
        assertEquals("armeabi-v7a", select.invoke(null, false, elfHeader(1, 40), "aarch64", arrayOf("armeabi-v7a")))
    }

    @Test
    fun moduleNativeLoaderSearchesNativeLibDirAndSplitApksWithPageAlignmentChecks() {
        val libDir = File(tempDir, "nativeLib").apply { mkdirs() }
        val baseApk = File(tempDir, "base.apk")
        val splitApk = File(tempDir, "split_config.arm64_v8a.apk")

        val validSo = File(libDir, "libtest_wire.so")
        validSo.writeBytes(minimalElf64(16384L))
        baseApk.writeBytes(minimalElf64(16384L)) // non-empty placeholder without entry -> falls back to nativeLibraryDir
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

        // When nativeLibraryDir doesn't have the lib, search splitSourceDirs with 16KB STORED alignment verification.
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

        // Reject unaligned (4KB) ZIP entry when runtime page size is 16KB.
        val unalignedSplit = File(tempDir, "split_unaligned.apk")
        writeStoredApk(unalignedSplit, "lib/arm64-v8a/libbad_align.so", minimalElf64(16384L), alignment = 4)
        ModuleNativeLoader.registerModuleApk(baseApk.path, libDir.path, arrayOf(unalignedSplit.path))
        var rejected = false
        try {
            ModuleNativeLoader.loadWith(
                name = "bad_align",
                loadLibrary = { throw UnsatisfiedLinkError("not extracted") },
                loadPath = { error("must not load unaligned split entry") },
                abiProvider = { "arm64-v8a" },
                runtimePageSize = 16384L
            )
        } catch (_: UnsatisfiedLinkError) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun framePreparationMemoryFallsBackToDirectBufferOnLinkageErrorAndPropagatesCallbackErrors() {
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

    @Test
    fun audioRescueModelMarksLoadingOnlyAfterVerificationSucceedsAndRejectsNonArmAbi() {
        assertEquals("arm64-v8a", AudioRescueModel.selectModelAbi("arm64-v8a"))
        assertEquals("armeabi-v7a", AudioRescueModel.selectModelAbi("armeabi-v7a"))
        assertNull(AudioRescueModel.selectModelAbi("x86_64"))
        assertNull(AudioRescueModel.selectModelAbi("x86"))
        assertNull(AudioRescueModel.selectModelAbi(null))

        // Failure inside verification must NOT flip loading = true.
        var loadingAfterVerifyFailure = false
        var failedVerify = false
        try {
            AudioRescueModel.withVerifiedThenLoad<String>(
                verify = { throw IllegalStateException("corrupt hash") },
                markLoading = { loadingAfterVerifyFailure = true },
                load = { "never" }
            )
        } catch (_: IllegalStateException) {
            failedVerify = true
        }
        assertTrue(failedVerify)
        assertFalse(loadingAfterVerifyFailure)

        // Success in verification marks loading BEFORE calling load().
        val events = mutableListOf<String>()
        val loaded = AudioRescueModel.withVerifiedThenLoad(
            verify = { consume ->
                events += "verified"
                consume(ByteBuffer.allocateDirect(8))
            },
            markLoading = { events += "loading" },
            load = {
                events += "load"
                "model"
            }
        )
        assertEquals("model", loaded)
        assertEquals(listOf("verified", "loading", "load"), events)
    }

    @Test
    fun audioSpeechEnhancerCacheArrayCopyAndWriteContinuationReuseMatchExactState() {
        val model = AudioSpeechModel { spectrum, caches ->
            caches[0][0] += 0.001f
            caches[1][0] += 0.002f
            caches[2][0] += 0.003f
            FloatArray(spectrum.size) { spectrum[it] * 0.9f }
        }
        val enhancer = AudioSpeechEnhancer(48000, 2, model)
        val snapA = FloatArray(16896 + 96 + 1056)
        repeat(600) { i -> enhancer.process(kotlin.math.sin(i * 0.05), kotlin.math.cos(i * 0.05)) }
        enhancer.saveCaches(snapA)

        repeat(600) { i -> enhancer.process(0.1, -0.1) }
        val snapB = FloatArray(snapA.size)
        enhancer.saveCaches(snapB)
        assertFalse(snapA.contentEquals(snapB))

        enhancer.restoreCaches(snapA)
        val snapRestored = FloatArray(snapA.size)
        enhancer.saveCaches(snapRestored)
        assertArrayEquals(snapA, snapRestored, 0f)

        // Exercise partial byte[] write & 0-byte retry reuse in AudioRescueWrite without ByteBuffer.wrap churn.
        var inferences = 0
        val countingModel = AudioSpeechModel { spectrum, _ -> inferences++; spectrum.copyOf() }
        fun makeWriter() = AudioRescueWrite(
            AudioRescueProcessor(48000, 2, AudioRescueConfig(voiceFocus = true), AudioSpeechEnhancer(48000, 2, countingModel)),
            encoding = 2
        )
        val writer = makeWriter()
        val pcmBytes = ByteArray(1024 * 4) { idx -> (idx * 37).toByte() }
        val p1 = writer.prepare(pcmBytes, 0, pcmBytes.size)!!
        val countAfterFirst = inferences
        assertTrue(countAfterFirst > 0)
        // Partial acceptance (1024 bytes = 256 stereo frames) saves tail into reusable FloatArray continuation.
        writer.finish(p1, 1024)
        // Retry tail twice (0 acceptance then full acceptance): cached continuation prevents extra inference!
        val p2 = writer.prepare(pcmBytes, 1024, pcmBytes.size - 1024)!!
        assertEquals(countAfterFirst, inferences)
        writer.finish(p2, 0)
        val p3 = writer.prepare(pcmBytes, 1024, pcmBytes.size - 1024)!!
        assertEquals(countAfterFirst, inferences)
        writer.finish(p3, pcmBytes.size - 1024)
    }

    @Test
    fun framePowerAdvisorRefreshesAsynchronouslyAndHandlesApi27BatteryAndApi35Thresholds() {
        val pendingTasks = ArrayDeque<Runnable>()
        val manualExecutor = Executor { runnable -> pendingTasks.addLast(runnable) }
        val samplerCalls = AtomicInteger(0)
        var currentSample = FramePowerAdvisor.ThermalSample(headroom = 0.65f, severeStatus = false, batteryC = 37.0f)
        val advisor = FramePowerAdvisor(
            context = null,
            targetNs = 16_666_666L,
            thermalSampler = {
                samplerCalls.incrementAndGet()
                currentSample
            },
            thermalExecutor = manualExecutor
        )

        // First call on GL thread returns immediately with null and queues async refresh (no synchronous IPC).
        assertNull(advisor.thermalHeadroom(0L))
        assertEquals(0, samplerCalls.get())
        assertEquals(1, pendingTasks.size)

        // Subsequent GL thread calls within 1s do not enqueue duplicate work.
        assertNull(advisor.thermalHeadroom(500_000_000L))
        assertEquals(1, pendingTasks.size)

        // Background task completes; hot path reads @Volatile snapshot directly with zero IPC.
        pendingTasks.removeFirst().run()
        assertEquals(1, samplerCalls.get())
        assertEquals(0.65f, advisor.thermalHeadroom(600_000_000L)!!, 0.0001f)
        assertNull(FrameThermalControl.pauseHeadroom(true) { advisor.thermalHeadroom(700_000_000L) })

        // After >= 1s with hot battery on API 27-29 (headroom=NaN), background refresh publishes 1.0f pause.
        currentSample = FramePowerAdvisor.ThermalSample(headroom = Float.NaN, severeStatus = false, batteryC = 43.2f)
        // Before background runs, hot path keeps prior snapshot and queues next refresh non-blockingly.
        assertEquals(0.65f, advisor.thermalHeadroom(1_500_000_000L)!!, 0.0001f)
        assertEquals(1, pendingTasks.size)
        pendingTasks.removeFirst().run()
        assertEquals(2, samplerCalls.get())
        assertEquals(1.0f, FrameThermalControl.pauseHeadroom(true) { advisor.thermalHeadroom(1_600_000_000L) }!!, 0.0001f)

        advisor.close()
        assertNull(advisor.thermalHeadroom(3_000_000_000L))

        // API 27-29 & API 35 threshold unit assertions.
        assertNull(FramePowerAdvisor.computeEffectiveHeadroom(FramePowerAdvisor.ThermalSample(Float.NaN, false, 42.9f)))
        assertEquals(1.0f, FramePowerAdvisor.computeEffectiveHeadroom(FramePowerAdvisor.ThermalSample(Float.NaN, false, 43.0f))!!, 0.0001f)
        assertEquals(0.85f, FramePowerAdvisor.computeEffectiveHeadroom(FramePowerAdvisor.ThermalSample(0.68f, false, 35.0f, 0.80f))!!, 0.0001f)
        assertEquals(0.75f, FramePowerAdvisor.computeEffectiveHeadroom(FramePowerAdvisor.ThermalSample(0.90f, false, 35.0f, 1.20f))!!, 0.0001f)
        assertNull(FrameThermalControl.pauseHeadroom(true, threshold = 0.90f) { 0.89f })
        assertEquals(0.90f, FrameThermalControl.pauseHeadroom(true, threshold = 0.90f) { 0.90f }!!, 0.0001f)
    }

    @Test
    fun audioTrackBuilderRegistrationHelperFiltersNonMediaOffloadAndInvalidFormats() {
        assertTrue(
            FrameInterpolationFeatureInstaller.canRegisterAudioTrack(
                initialized = true,
                offloaded = false,
                usage = android.media.AudioAttributes.USAGE_MEDIA,
                streamType = android.media.AudioManager.STREAM_MUSIC,
                encoding = 2,
                channels = 2,
                sampleRate = 48_000
            )
        )
        assertTrue(
            FrameInterpolationFeatureInstaller.canRegisterAudioTrack(
                initialized = true,
                offloaded = false,
                usage = android.media.AudioAttributes.USAGE_UNKNOWN,
                streamType = android.media.AudioManager.STREAM_MUSIC,
                encoding = 4,
                channels = 1,
                sampleRate = 44_100
            )
        )
        assertTrue(
            FrameInterpolationFeatureInstaller.canRegisterAudioTrack(
                initialized = true,
                offloaded = false,
                usage = null,
                streamType = android.media.AudioManager.STREAM_MUSIC,
                encoding = 2,
                channels = 2,
                sampleRate = 48_000
            )
        )
        assertFalse(
            FrameInterpolationFeatureInstaller.canRegisterAudioTrack(
                initialized = false,
                offloaded = false,
                usage = android.media.AudioAttributes.USAGE_MEDIA,
                streamType = android.media.AudioManager.STREAM_MUSIC,
                encoding = 2,
                channels = 2,
                sampleRate = 48_000
            )
        )
        assertFalse(
            FrameInterpolationFeatureInstaller.canRegisterAudioTrack(
                initialized = true,
                offloaded = true,
                usage = android.media.AudioAttributes.USAGE_MEDIA,
                streamType = android.media.AudioManager.STREAM_MUSIC,
                encoding = 2,
                channels = 2,
                sampleRate = 48_000
            )
        )
        assertFalse(
            FrameInterpolationFeatureInstaller.canRegisterAudioTrack(
                initialized = true,
                offloaded = false,
                usage = android.media.AudioAttributes.USAGE_ALARM,
                streamType = android.media.AudioManager.STREAM_ALARM,
                encoding = 2,
                channels = 2,
                sampleRate = 48_000
            )
        )
        assertFalse(
            FrameInterpolationFeatureInstaller.canRegisterAudioTrack(
                initialized = true,
                offloaded = false,
                usage = android.media.AudioAttributes.USAGE_MEDIA,
                streamType = android.media.AudioManager.STREAM_MUSIC,
                encoding = 5, // AC3 passthrough
                channels = 2,
                sampleRate = 48_000
            )
        )
    }
}
