package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.config.SnapshotHookConfigSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.*
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingValue
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class AudioRescueInstallAndWriteTest {
    private val loader = javaClass.classLoader!!

    private class Registrar(private val failure: String? = null) : HookRegistrar by TestHookRegistrar {
        val methods = mutableListOf<String>()
        override fun exact(
            id: String,
            owner: Class<*>,
            methodName: String,
            vararg parameterTypes: Class<*>,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            methods += "$methodName:${parameterTypes.joinToString { it.simpleName }}"
            if (methodName == failure) error("registration rejected")
            // "write-long" fails only the timestamped overload, as on Android versions without it.
            if (failure == "write-long" && methodName == "write" && parameterTypes.any { it.simpleName == "long" }) {
                error("registration rejected")
            }
        }
    }

    private fun environment(registrar: HookRegistrar = TestHookRegistrar, process: String = AudioRescueFeatureInstaller.PLAYER_PROCESS) =
        HookEnvironment(process, loader, HookPointRegistry(loader), registrar, { _, _ -> }, { _, _ -> }, { _, _ -> })

    private fun processor(channels: Int = 2) =
        AudioRescueProcessor(48000, channels, AudioRescueConfig(true, 1, true, true))

    private fun bytes(vararg samples: Short): ByteArray =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder()).also { b ->
            samples.forEach { b.putShort(it) }
        }.array()

    @Test
    fun verifiesConfigCatalogModelLoadingAndInstallerHooks() {
        // Defaults are off and unknown channel mode does not activate audio hooks.
        for (value in listOf(-1, 0, 3, Int.MAX_VALUE)) {
            val config = readAudioRescueConfig(SnapshotHookConfigSource(mapOf(FeaturePreferences.PLAYER_AUDIO_CHANNELS to value)))
            assertFalse(config.enabled)
            assertEquals(0, config.channels)
        }

        // Each control activates independently.
        for (key in listOf(
            FeaturePreferences.PLAYER_AUDIO_PEAK_GUARD,
            FeaturePreferences.PLAYER_AUDIO_NOISE_REDUCTION,
            FeaturePreferences.PLAYER_AUDIO_VOICE_FOCUS
        )) {
            assertTrue(readAudioRescueConfig(SnapshotHookConfigSource(mapOf(key to true))).enabled)
        }
        for (mode in 1..2) assertTrue(AudioRescueConfig(channels = mode).enabled)

        // Audio settings are typed, backed up, and delivered through catalog.
        val specs = SettingsCatalog.specs.filter { it.id.startsWith("player.audio.") }
        assertEquals(4, specs.size)
        assertEquals(setOf(81, 82), specs.map { it.introducedCatalogVersion }.toSet())
        assertEquals(81, specs.single { it.id == "player.audio.peak_guard" }.introducedCatalogVersion)
        assertEquals(82, specs.single { it.id == "player.audio.voice_focus" }.introducedCatalogVersion)
        assertEquals(3, specs.count { it.defaultValue == SettingValue.Bool(false) })
        assertEquals(SettingValue.IntValue(0), specs.single { it.id == "player.audio.channels" }.defaultValue)

        // AudioRescueModel marks loading only after verification succeeds and rejects non-ARM ABIs.
        assertEquals("arm64-v8a", AudioRescueModel.selectModelAbi("arm64-v8a"))
        assertEquals("armeabi-v7a", AudioRescueModel.selectModelAbi("armeabi-v7a"))
        assertNull(AudioRescueModel.selectModelAbi("x86_64"))
        assertNull(AudioRescueModel.selectModelAbi("x86"))
        assertNull(AudioRescueModel.selectModelAbi(null))

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

        // Disabled config and unrelated processes register nothing.
        val skippedRegistrar = Registrar()
        assertEquals(
            FeatureInstallResult.Skipped("disabled"),
            AudioRescueFeatureInstaller(AudioRescueConfig()).install(environment(skippedRegistrar))
        )
        assertEquals(
            FeatureInstallResult.Skipped("non-main-process"),
            AudioRescueFeatureInstaller(AudioRescueConfig(true)).install(environment(skippedRegistrar, "tv.danmaku.bili:download"))
        )
        assertEquals(
            FeatureInstallResult.Skipped("non-main-process"),
            AudioRescueFeatureInstaller(AudioRescueConfig(peakGuard = true)).install(environment(process = "tv.danmaku.bili:web"))
        )
        assertTrue(skippedRegistrar.methods.isEmpty())

        // Main process video backend and dedicated service register seven write forms and all reset paths.
        val mainRegistrar = Registrar()
        assertEquals(
            FeatureInstallResult.Installed(10),
            AudioRescueFeatureInstaller(AudioRescueConfig(true)).install(environment(mainRegistrar, "tv.danmaku.bili"))
        )
        assertEquals(7, mainRegistrar.methods.count { it.startsWith("write:") })
        assertTrue(mainRegistrar.methods.contains("write:ByteBuffer, int, int, long"))
        assertTrue(mainRegistrar.methods.containsAll(listOf("flush:", "stop:", "release:")))

        val serviceRegistrar = Registrar()
        assertEquals(
            FeatureInstallResult.Installed(10),
            AudioRescueFeatureInstaller(AudioRescueConfig(true)).install(environment(serviceRegistrar))
        )
        assertEquals(7, serviceRegistrar.methods.count { it.startsWith("write:") })
        assertTrue(serviceRegistrar.methods.contains("write:ByteBuffer, int, int, long"))
        assertTrue(serviceRegistrar.methods.containsAll(listOf("flush:", "stop:", "release:")))
        assertTrue(serviceRegistrar.methods.none { it.startsWith("setVolume:") || it.startsWith("setPlaybackParams:") })

        // Missing timestamped write keeps the required entries active.
        val noLongRegistrar = Registrar("write-long")
        assertEquals(
            FeatureInstallResult.Installed(9),
            AudioRescueFeatureInstaller(AudioRescueConfig(true)).install(environment(noLongRegistrar))
        )
        assertEquals(6, noLongRegistrar.methods.count { it.startsWith("write:") && !it.contains("long") })
        assertTrue(noLongRegistrar.methods.containsAll(listOf("flush:", "stop:", "release:")))

        // Missing lifecycle or write method leaves the whole processor inactive.
        for (method in listOf("write", "flush", "stop", "release")) {
            assertEquals(
                FeatureInstallResult.Skipped("registration-failed"),
                AudioRescueFeatureInstaller(AudioRescueConfig(true)).install(environment(Registrar(method)))
            )
        }

        // Exact hook count & idle release check.
        val installed = AudioRescueFeatureInstaller(AudioRescueConfig(peakGuard = true)).install(environment(process = "tv.danmaku.bili"))
        assertTrue(installed is FeatureInstallResult.Installed && installed.hookCount == 10)
        assertFalse(AudioRescueModel.releaseIfIdle())
    }

    @Test
    fun verifiesWriteMetricsDspContinuationAndBufferReuse() {
        // Metrics prove mono on accepted encoded bytes without moving buffer positions.
        run {
            val writer = AudioRescueWrite(AudioRescueProcessor(48000, 2, AudioRescueConfig(channels = 1)), 2)
            val caller = ByteBuffer.wrap(bytes(16000, 0, -8000, 4000))
            val pending = writer.prepare(caller, 0, 8)!!
            val replacement = pending.data as ByteBuffer
            replacement.position(4)
            val stats = writer.metrics(pending, 4)!!
            assertEquals(1, stats.frames)
            assertEquals(16000 / 32768.0, stats.inputPeak, 0.0)
            assertEquals(8000 / 32768.0, stats.outputPeak, 0.0)
            assertTrue(stats.inputStereoDifference > 0.0)
            assertEquals(0.0, stats.outputStereoDifference, 0.0)
            assertEquals(0, caller.position())
            assertEquals(4, replacement.position())
        }

        // Metrics exclude unwritten spikes and report quantized float, short, and 8-bit output.
        run {
            val writer = AudioRescueWrite(AudioRescueProcessor(48000, 2, AudioRescueConfig(peakGuard = true)), 4)
            val pending = writer.prepare(floatArrayOf(.01f, .01f, 1f, -1f), 0, 4)!!
            val stats = writer.metrics(pending, 2)!!
            assertEquals(1, stats.frames)
            assertTrue(stats.inputPeak < .011)
            assertEquals(stats.inputPeak, stats.outputPeak, 0.0)
            val shortWriter = AudioRescueWrite(AudioRescueProcessor(48000, 1, AudioRescueConfig()), 2)
            val shortStats = shortWriter.metrics(shortWriter.prepare(shortArrayOf(16384), 0, 1)!!, 1)!!
            assertEquals(.5, shortStats.outputRms, 0.0)
            val byteWriter = AudioRescueWrite(AudioRescueProcessor(48000, 1, AudioRescueConfig()), 3)
            val byteStats = byteWriter.metrics(byteWriter.prepare(byteArrayOf(192.toByte()), 0, 1)!!, 1)!!
            assertEquals(.5, byteStats.outputPeak, 0.0)
        }

        // Metrics do not advance DSP state and reject failed or out of bounds writes.
        run {
            val p = processor()
            val writer = AudioRescueWrite(p, 4)
            val pending = writer.prepare(floatArrayOf(1f, .2f), 0, 2)!!
            val checkpoint = p.checkpoint()
            for (accepted in listOf(-6, 0, 3)) assertNull(writer.metrics(pending, accepted))
            assertNotNull(writer.metrics(pending, 2))
            assertArrayEquals(checkpoint, p.checkpoint(), 0.0)
        }

        // Short writes copy selected range and retain caller data.
        run {
            val caller = shortArrayOf(321, 32767, 0, -32768, 0, 123)
            val original = caller.copyOf()
            val writer = AudioRescueWrite(processor(), 2)
            val pending = writer.prepare(caller, 1, 4)!!
            assertEquals(4, (pending.data as ShortArray).size)
            writer.finish(pending, 4)
            assertArrayEquals(original, caller)
        }

        // Byte writes preserve offsets, input, and result byte units.
        run {
            val input = byteArrayOf(42, 19) + bytes(32767, 0, -32768, 0) + byteArrayOf(83)
            val original = input.copyOf()
            val writer = AudioRescueWrite(processor(), 2)
            val pending = writer.prepare(input, 2, 8)!!
            assertEquals(8, (pending.data as ByteArray).size)
            writer.finish(pending, 4)
            assertArrayEquals(original, input)
        }

        // Partial accepted prefix commits exact reference DSP state.
        run {
            val input = FloatArray(4800) { if (it < 400) .04f else 1f }
            val actualProcessor = processor()
            val expectedProcessor = processor()
            val writer = AudioRescueWrite(actualProcessor, 4)
            val pending = writer.prepare(input, 0, input.size)!!
            writer.finish(pending, 200)
            expectedProcessor.process(input.copyOfRange(0, 200))
            assertArrayEquals(expectedProcessor.checkpoint(), actualProcessor.checkpoint(), 0.0)
            val retry = writer.prepare(input, 200, input.size - 200)!!
            val reference = input.copyOfRange(200, input.size).also { expectedProcessor.process(it) }
            assertArrayEquals(reference, retry.data as FloatArray, 0f)
            writer.finish(retry, retry.count)
            assertArrayEquals(expectedProcessor.checkpoint(), actualProcessor.checkpoint(), 0.0)
        }

        // Errors, zero, and invalid returns restore DSP and preserve buffer position.
        for (accepted in listOf(-6, -3, 0, 999)) {
            val p = processor()
            val initial = p.checkpoint()
            val writer = AudioRescueWrite(p, 2)
            val data = ByteBuffer.wrap(bytes(32767, 0, 32767, 0)).apply { position(4) }
            val pending = writer.prepare(data, 0, 4)!!
            writer.finish(pending, accepted)
            assertArrayEquals(initial, p.checkpoint(), 0.0)
            assertEquals(4, data.position())
        }

        // Heap, direct, and readonly buffers retain bytes, order, limit, and advance accepted bytes only.
        for (kind in 0..2) {
            val source = bytes(123, 456, 32767, 0, -32768, 0)
            val mutable = (if (kind == 1) ByteBuffer.allocateDirect(source.size) else ByteBuffer.allocate(source.size))
                .put(source).apply { flip(); position(4) }
            val caller = if (kind == 2) mutable.asReadOnlyBuffer() else mutable
            caller.order(if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            val order = caller.order()
            val limit = caller.limit()
            val writer = AudioRescueWrite(processor(), 2)
            val pending = writer.prepare(caller, 0, 8)!!
            assertEquals(4, caller.position())
            assertEquals(0, (pending.data as ByteBuffer).position())
            writer.finish(pending, 4)
            assertEquals(8, caller.position())
            assertEquals(limit, caller.limit())
            assertEquals(order, caller.order())
            val retained = ByteArray(source.size)
            caller.duplicate().apply { position(0); get(retained) }
            assertArrayEquals(source, retained)
        }

        // PCM 8-bit silence/mono, 16-bit identity roundtrip, and float buffer sanitization.
        run {
            val p8 = AudioRescueProcessor(48000, 2, AudioRescueConfig(channels = 1))
            val w8 = AudioRescueWrite(p8, 3)
            val pending8 = w8.prepare(byteArrayOf(128.toByte(), 128.toByte(), 255.toByte(), 129.toByte()), 0, 4)!!
            assertArrayEquals(byteArrayOf(128.toByte(), 128.toByte(), 192.toByte(), 192.toByte()), pending8.data as ByteArray)

            val w16 = AudioRescueWrite(AudioRescueProcessor(48000, 1, AudioRescueConfig()), 2)
            val input16 = bytes(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE)
            assertArrayEquals(input16, w16.prepare(input16, 0, input16.size)!!.data as ByteArray)

            val data = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder())
                .putFloat(Float.NaN).putFloat(Float.POSITIVE_INFINITY).putFloat(1f).putFloat(-1f).apply { flip() }
            val pf = processor()
            val wf = AudioRescueWrite(pf, 4)
            val checkpoint = pf.checkpoint()
            val pendingF = wf.prepare(data, 0, 16)!!
            val output = (pendingF.data as ByteBuffer).duplicate().order(ByteOrder.nativeOrder())
            assertTrue(FloatArray(4) { output.float }.all { it.isFinite() })
            wf.finish(pendingF, 0)
            assertArrayEquals(checkpoint, pf.checkpoint(), 0.0)
            assertEquals(0, data.position())
        }

        // Invalid ranges, unknown encodings, surround misalignment, and huge writes stay with Android.
        run {
            val writer = AudioRescueWrite(processor(), 2)
            val input = ByteArray(16)
            for ((offset, count) in listOf(-1 to 4, 0 to -1, 0 to 0, 0 to 3, 15 to 4, Int.MAX_VALUE to 4, 0 to Int.MAX_VALUE)) {
                assertNull(writer.prepare(input, offset, count))
            }
            assertNull(writer.prepare(FloatArray(4), 0, 4))
            assertNull(AudioRescueWrite(processor(), 4).prepare(input, 0, 16))
            assertNull(AudioRescueWrite(processor(), 5).prepare(input, 0, 16))
            assertNull(writer.prepare(ByteBuffer.allocate(2), 0, 4))
            assertNull(writer.prepare("not PCM", 0, 4))
        }

        // Reuse buffers on matching frame sizes and avoid duplicate speech inference on non-blocking retries.
        run {
            var inferCalls = 0
            val countingModel = AudioSpeechModel { spectrum, caches ->
                inferCalls++
                caches[0][0] += .00001f
                spectrum.map { it * .9f }.toFloatArray()
            }
            val expectedProcessor = AudioRescueProcessor(
                48000, 2, AudioRescueConfig(voiceFocus = true),
                AudioSpeechEnhancer(48000, 2, AudioSpeechModel { s, c -> c[0][0] += .00001f; s.map { it * .9f }.toFloatArray() })
            )
            val actualProcessor = AudioRescueProcessor(
                48000, 2, AudioRescueConfig(voiceFocus = true),
                AudioSpeechEnhancer(48000, 2, countingModel)
            )
            val writer = AudioRescueWrite(actualProcessor, 4)
            val input = FloatArray(4800) { if (it % 2 == 0) .2f else -.1f }
            val first = writer.prepare(input, 0, input.size)!!
            val callsAfterFirst = inferCalls
            assertTrue(callsAfterFirst > 0)
            assertNull(first.postCheckpoint)
            writer.finish(first, 0)
            assertNotNull(first.postCheckpoint)
            assertTrue(first.checkpoint !== first.postCheckpoint)
            val zeroRetry = writer.prepare(input, 0, input.size)!!
            assertEquals(callsAfterFirst, inferCalls)
            assertSame(first.data, zeroRetry.data)
            assertSame(first.postCheckpoint, zeroRetry.postCheckpoint)
            assertTrue(zeroRetry.checkpoint !== zeroRetry.postCheckpoint)
            writer.finish(zeroRetry, 1600)
            val callsAfterPartialCommit = inferCalls
            expectedProcessor.process(input.copyOfRange(0, 1600))
            val tailRetry = writer.prepare(input, 1600, 3200)!!
            assertEquals(callsAfterPartialCommit, inferCalls)
            assertTrue(tailRetry.checkpoint !== tailRetry.postCheckpoint)
            val expectedTail = input.copyOfRange(1600, 4800).also { expectedProcessor.process(it) }
            assertArrayEquals(expectedTail, tailRetry.data as FloatArray, 0f)
            writer.finish(tailRetry, tailRetry.count)
            assertEquals(callsAfterPartialCommit, inferCalls)
            val expectedState = expectedProcessor.checkpoint()
            assertArrayEquals(expectedState, actualProcessor.checkpoint(), 0.0)
            val targetState = DoubleArray(actualProcessor.stateSize)
            actualProcessor.checkpointInto(targetState)
            assertArrayEquals(expectedState, targetState, 0.0)
            assertThrows(IllegalArgumentException::class.java) {
                actualProcessor.checkpointInto(DoubleArray(actualProcessor.stateSize - 1))
            }

            val missSeed = writer.prepare(input, 0, input.size)!!
            writer.finish(missSeed, 0)
            assertNotNull(missSeed.postCheckpoint)
            val differentInput = FloatArray(4800) { if (it % 2 == 0) -.15f else .25f }
            val missActual = writer.prepare(differentInput, 0, differentInput.size)!!
            assertTrue(missActual.checkpoint !== missSeed.postCheckpoint)
            assertNull(missActual.postCheckpoint)
            writer.finish(missActual, missActual.count)
            assertNull(missActual.postCheckpoint)

            val bbWriter = AudioRescueWrite(processor(), 2)
            val caller = ByteBuffer.wrap(bytes(1000, -1000, 2000, -2000, 3000, -3000, 4000, -4000))
            val bb1 = bbWriter.prepare(caller, 0, 8)!!
            assertTrue((bb1.data as ByteBuffer).isDirect)
            caller.limit(10)
            bbWriter.finish(bb1, 8)
            assertEquals(8, caller.position())
            val caller2 = ByteBuffer.wrap(bytes(500, -500, 600, -600))
            val bb2 = bbWriter.prepare(caller2, 0, 8)!!
            assertSame(bb1.data, bb2.data)
            bbWriter.finish(bb2, 8)

            val baWriter = AudioRescueWrite(processor(), 2)
            val rawBytes = bytes(100, 200, 300, 400)
            val ba1 = baWriter.prepare(rawBytes, 0, 8)!!
            baWriter.finish(ba1, 8)
            val ba2 = baWriter.prepare(rawBytes, 0, 8)!!
            assertSame(ba1.data, ba2.data)
            assertEquals(8, (ba2.data as ByteArray).size)
        }

        // Full, error, and unaligned writes skip postCheckpoint while larger input scratch is reused.
        run {
            val writer = AudioRescueWrite(processor(), 4)
            val large = FloatArray(4800) { if (it % 2 == 0) .2f else -.1f }
            val largePending = writer.prepare(large, 0, large.size)!!
            assertNull(largePending.postCheckpoint)
            writer.finish(largePending, largePending.count)
            assertNull(largePending.postCheckpoint)

            val small = FloatArray(3200) { .05f }
            val smallPending = writer.prepare(small, 0, small.size)!!
            assertSame(largePending.input, smallPending.input)
            assertEquals(3200, smallPending.sampleCount)
            assertEquals(3200, (smallPending.data as FloatArray).size)
            assertNull(smallPending.postCheckpoint)
            writer.finish(smallPending, -1)
            assertNull(smallPending.postCheckpoint)

            val pcmWriter = AudioRescueWrite(processor(), 2)
            val raw = bytes(100, -100, 200, -200, 300, -300, 400, -400)
            val unaligned = pcmWriter.prepare(raw, 0, raw.size)!!
            pcmWriter.finish(unaligned, 2)
            assertNull(unaligned.postCheckpoint)
        }

        // AudioSpeechEnhancer cache array copy & PCM16 continuation reuse match exact state.
        run {
            val model = AudioSpeechModel { spectrum, caches ->
                caches[0][0] += 0.001f
                caches[1][0] += 0.002f
                caches[2][0] += 0.003f
                FloatArray(spectrum.size) { spectrum[it] * 0.9f }
            }
            val enhancer = AudioSpeechEnhancer(48000, 2, model)
            val snapA = FloatArray(16896 + 96 + 1056)
            repeat(600) { i -> enhancer.process(sin(i * 0.05), cos(i * 0.05)) }
            enhancer.saveCaches(snapA)

            repeat(600) { enhancer.process(0.1, -0.1) }
            val snapB = FloatArray(snapA.size)
            enhancer.saveCaches(snapB)
            assertFalse(snapA.contentEquals(snapB))

            enhancer.restoreCaches(snapA)
            val snapRestored = FloatArray(snapA.size)
            enhancer.saveCaches(snapRestored)
            assertArrayEquals(snapA, snapRestored, 0f)

            var inferences = 0
            val countingModel = AudioSpeechModel { spectrum, _ -> inferences++; spectrum.copyOf() }
            val writer = AudioRescueWrite(
                AudioRescueProcessor(48000, 2, AudioRescueConfig(voiceFocus = true), AudioSpeechEnhancer(48000, 2, countingModel)),
                encoding = 2
            )
            val pcmBytes = ByteArray(1024 * 4) { idx -> (idx * 37).toByte() }
            val p1 = writer.prepare(pcmBytes, 0, pcmBytes.size)!!
            val countAfterFirst = inferences
            assertTrue(countAfterFirst > 0)
            writer.finish(p1, 1024)
            val p2 = writer.prepare(pcmBytes, 1024, pcmBytes.size - 1024)!!
            assertEquals(countAfterFirst, inferences)
            writer.finish(p2, 0)
            val p3 = writer.prepare(pcmBytes, 1024, pcmBytes.size - 1024)!!
            assertEquals(countAfterFirst, inferences)
            writer.finish(p3, pcmBytes.size - 1024)
        }
    }
}
