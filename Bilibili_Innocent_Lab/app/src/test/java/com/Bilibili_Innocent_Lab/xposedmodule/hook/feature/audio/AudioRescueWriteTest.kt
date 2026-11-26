package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class AudioRescueWriteTest {
    fun `metrics prove mono on accepted encoded bytes without moving buffer positions`() {
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
        assertEquals(0, caller.position()); assertEquals(4, replacement.position())
    }

    fun `metrics exclude unwritten spikes and report actual quantized float short and eight bit output`() {
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

    fun `metrics do not advance DSP state and reject failed or out of bounds writes`() {
        val p = processor()
        val writer = AudioRescueWrite(p, 4)
        val pending = writer.prepare(floatArrayOf(1f, .2f), 0, 2)!!
        val checkpoint = p.checkpoint()
        for (accepted in listOf(-6, 0, 3)) assertNull(writer.metrics(pending, accepted))
        assertNotNull(writer.metrics(pending, 2))
        assertArrayEquals(checkpoint, p.checkpoint(), 0.0)
    }
    private fun processor(channels: Int = 2) = AudioRescueProcessor(48000, channels, AudioRescueConfig(true, 1, true, true))
    private fun bytes(vararg samples: Short): ByteArray = ByteBuffer.allocate(samples.size * 2)
        .order(ByteOrder.nativeOrder()).also { b -> samples.forEach { b.putShort(it) } }.array()

    fun `short writes copy the selected range and retain caller data`() {
        val caller = shortArrayOf(321, 32767, 0, -32768, 0, 123)
        val original = caller.copyOf()
        val writer = AudioRescueWrite(processor(), 2)
        val pending = writer.prepare(caller, 1, 4)!!
        assertEquals(4, (pending.data as ShortArray).size)
        writer.finish(pending, 4)
        assertArrayEquals(original, caller)
    }

    fun `byte writes preserve offsets input and result byte units`() {
        val input = byteArrayOf(42, 19) + bytes(32767, 0, -32768, 0) + byteArrayOf(83)
        val original = input.copyOf()
        val writer = AudioRescueWrite(processor(), 2)
        val pending = writer.prepare(input, 2, 8)!!
        assertEquals(8, (pending.data as ByteArray).size)
        writer.finish(pending, 4)
        assertArrayEquals(original, input)
    }

    fun `partial accepted prefix commits exactly the reference DSP state`() {
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

    fun `errors zero and invalid returns restore DSP and preserve buffer position`() {
        for (accepted in listOf(-6, -3, 0, 999)) {
            val p = processor(); val initial = p.checkpoint()
            val writer = AudioRescueWrite(p, 2)
            val data = ByteBuffer.wrap(bytes(32767, 0, 32767, 0)).apply { position(4) }
            val pending = writer.prepare(data, 0, 4)!!
            writer.finish(pending, accepted)
            assertArrayEquals(initial, p.checkpoint(), 0.0)
            assertEquals(4, data.position())
        }
    }

    fun `heap direct and readonly buffers retain bytes order limit and advance accepted bytes only`() {
        for (kind in 0..2) {
            val source = bytes(123, 456, 32767, 0, -32768, 0)
            val mutable = (if (kind == 1) ByteBuffer.allocateDirect(source.size) else ByteBuffer.allocate(source.size))
                .put(source).apply { flip(); position(4) }
            val caller = if (kind == 2) mutable.asReadOnlyBuffer() else mutable
            // Audio samples use native order regardless of the caller's ByteBuffer.order flag.
            caller.order(if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            val order = caller.order(); val limit = caller.limit()
            val writer = AudioRescueWrite(processor(), 2)
            val pending = writer.prepare(caller, 0, 8)!!
            assertEquals(4, caller.position())
            assertEquals(0, (pending.data as ByteBuffer).position())
            writer.finish(pending, 4)
            assertEquals(8, caller.position()); assertEquals(limit, caller.limit()); assertEquals(order, caller.order())
            val retained = ByteArray(source.size)
            caller.duplicate().apply { position(0); get(retained) }
            assertArrayEquals(source, retained)
        }
    }

    fun `PCM unsigned eight bit silence stays centered and mono fills both ears`() {
        val p = AudioRescueProcessor(48000, 2, AudioRescueConfig(channels = 1))
        val writer = AudioRescueWrite(p, 3)
        val pending = writer.prepare(byteArrayOf(128.toByte(), 128.toByte(), 255.toByte(), 129.toByte()), 0, 4)!!
        assertArrayEquals(byteArrayOf(128.toByte(), 128.toByte(), 192.toByte(), 192.toByte()), pending.data as ByteArray)
    }

    fun `PCM sixteen bit identity roundtrip preserves full signed range`() {
        val writer = AudioRescueWrite(AudioRescueProcessor(48000, 1, AudioRescueConfig()), 2)
        val input = bytes(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE)
        assertArrayEquals(input, writer.prepare(input, 0, input.size)!!.data as ByteArray)
    }

    fun `float buffer writes use byte units and sanitize nonfinite input`() {
        val data = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder())
            .putFloat(Float.NaN).putFloat(Float.POSITIVE_INFINITY).putFloat(1f).putFloat(-1f).apply { flip() }
        val p = processor()
        val writer = AudioRescueWrite(p, 4)
        val checkpoint = p.checkpoint()
        val pending = writer.prepare(data, 0, 16)!!
        val output = (pending.data as ByteBuffer).duplicate().order(ByteOrder.nativeOrder())
        assertTrue(FloatArray(4) { output.float }.all { it.isFinite() })
        writer.finish(pending, 0)
        assertArrayEquals(checkpoint, p.checkpoint(), 0.0)
        assertEquals(0, data.position())
    }

    fun `invalid ranges unknown encodings surround misalignment and huge writes stay with Android`() {
        val writer = AudioRescueWrite(processor(), 2)
        val input = ByteArray(16)
        for ((offset, count) in listOf(-1 to 4, 0 to -1, 0 to 0, 0 to 3, 15 to 4, Int.MAX_VALUE to 4, 0 to Int.MAX_VALUE))
            assertNull(writer.prepare(input, offset, count))
        assertNull(writer.prepare(FloatArray(4), 0, 4))
        assertNull(AudioRescueWrite(processor(), 4).prepare(input, 0, 16))
        assertNull(AudioRescueWrite(processor(), 5).prepare(input, 0, 16))
        assertNull(writer.prepare(ByteBuffer.allocate(2), 0, 4))
        assertNull(writer.prepare("not PCM", 0, 4))
    }

    fun `reuses buffers on matching frame sizes and avoids duplicate speech inference on non-blocking retries`() {
        var inferCalls = 0
        val countingModel = AudioSpeechModel { spectrum, caches ->
            inferCalls++
            caches[0][0] += .00001f
            spectrum.map { it * .9f }.toFloatArray()
        }
        val expectedProcessor = AudioRescueProcessor(48000, 2, AudioRescueConfig(voiceFocus = true),
            AudioSpeechEnhancer(48000, 2, AudioSpeechModel { s, c -> c[0][0] += .00001f; s.map { it * .9f }.toFloatArray() }))
        val actualProcessor = AudioRescueProcessor(48000, 2, AudioRescueConfig(voiceFocus = true),
            AudioSpeechEnhancer(48000, 2, countingModel))

        val writer = AudioRescueWrite(actualProcessor, 4)
        val input = FloatArray(4800) { if (it % 2 == 0) .2f else -.1f }
        val first = writer.prepare(input, 0, input.size)!!
        val callsAfterFirst = inferCalls
        assertTrue(callsAfterFirst > 0)
        // WRITE_NON_BLOCKING returns 0 -> retry identical buffer without running ONNX inference again.
        writer.finish(first, 0)
        val zeroRetry = writer.prepare(input, 0, input.size)!!
        assertEquals(callsAfterFirst, inferCalls)
        assertSame(first.data, zeroRetry.data)
        // Partial write of 1600 floats commits the accepted prefix, then retries remaining 3200 floats without running ONNX inference again.
        writer.finish(zeroRetry, 1600)
        val callsAfterPartialCommit = inferCalls
        expectedProcessor.process(input.copyOfRange(0, 1600))
        val tailRetry = writer.prepare(input, 1600, 3200)!!
        assertEquals(callsAfterPartialCommit, inferCalls)
        val expectedTail = input.copyOfRange(1600, 4800).also { expectedProcessor.process(it) }
        assertArrayEquals(expectedTail, tailRetry.data as FloatArray, 0f)
        writer.finish(tailRetry, tailRetry.count)
        assertEquals(callsAfterPartialCommit, inferCalls)
        assertArrayEquals(expectedProcessor.checkpoint(), actualProcessor.checkpoint(), 0.0)

        // ByteBuffer/ByteArray/ShortArray reuse when frame size matches, and bounds-safe limit() check.
        val bbWriter = AudioRescueWrite(processor(), 2)
        val caller = ByteBuffer.wrap(bytes(1000, -1000, 2000, -2000, 3000, -3000, 4000, -4000))
        val bb1 = bbWriter.prepare(caller, 0, 8)!!
        caller.limit(10) // Shrunk externally before finish: must not throw IllegalArgumentException.
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

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AudioRescueWriteTest) -> Unit) {
            val s = AudioRescueWriteTest()
            block(s)
        }
        runIsolated { s -> s.`metrics prove mono on accepted encoded bytes without moving buffer positions`() }
        runIsolated { s -> s.`metrics exclude unwritten spikes and report actual quantized float short and eight bit output`() }
        runIsolated { s -> s.`metrics do not advance DSP state and reject failed or out of bounds writes`() }
        runIsolated { s -> s.`short writes copy the selected range and retain caller data`() }
        runIsolated { s -> s.`byte writes preserve offsets input and result byte units`() }
        runIsolated { s -> s.`partial accepted prefix commits exactly the reference DSP state`() }
        runIsolated { s -> s.`errors zero and invalid returns restore DSP and preserve buffer position`() }
        runIsolated { s -> s.`heap direct and readonly buffers retain bytes order limit and advance accepted bytes only`() }
        runIsolated { s -> s.`PCM unsigned eight bit silence stays centered and mono fills both ears`() }
        runIsolated { s -> s.`PCM sixteen bit identity roundtrip preserves full signed range`() }
        runIsolated { s -> s.`float buffer writes use byte units and sanitize nonfinite input`() }
        runIsolated { s -> s.`invalid ranges unknown encodings surround misalignment and huge writes stay with Android`() }
        runIsolated { s -> s.`reuses buffers on matching frame sizes and avoids duplicate speech inference on non-blocking retries`() }
    }
}
