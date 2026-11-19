package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

import kotlin.math.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class AudioRescueProcessorTest {
    private val rate = 48000
    private fun tone(hz: Double, seconds: Double = 1.0, amplitude: Double = .2, opposite: Boolean = false) =
        FloatArray((rate * seconds).toInt() * 2) { index ->
            (sin(2 * PI * hz * (index / 2) / rate) * amplitude * if (opposite && index % 2 == 1) -1 else 1).toFloat()
        }
    private fun rms(data: FloatArray, start: Int = data.size / 2) = sqrt(data.drop(start).sumOf { it.toDouble() * it } / (data.size - start))
    private fun run(config: AudioRescueConfig, input: FloatArray) = input.copyOf().also { AudioRescueProcessor(rate, 2, config).process(it) }

    @Test fun `all off is bit exact`() {
        val input = floatArrayOf(-1f, 0f, .123456f, Float.NaN, Float.POSITIVE_INFINITY, -0f)
        assertArrayEquals(input, run(AudioRescueConfig(), input), 0f)
    }

    @Test fun `first sudden sample is held with linked channels`() {
        val processor = AudioRescueProcessor(rate, 2, AudioRescueConfig(peakGuard = true))
        processor.process(tone(1000.0, amplitude = .04))
        val impulse = floatArrayOf(1f, .5f)
        processor.process(impulse)
        assertTrue(impulse[0] in .249f.. .27f)
        assertEquals(impulse[0] / 2, impulse[1], 1e-6f)
    }

    @Test fun `absolute ceiling handles sustained loud audio and full scale startup`() {
        val data = run(AudioRescueConfig(peakGuard = true), FloatArray(rate * 4) { if (it % 4 < 2) 1f else -1f })
        assertTrue(data.all { abs(it) <= .500001f })
        assertTrue(data.take(2).all { abs(it) <= .321f })
    }

    @Test fun `guard preserves normal speech level and releases after a burst`() {
        val processor = AudioRescueProcessor(rate, 2, AudioRescueConfig(peakGuard = true))
        val quiet = tone(1000.0, amplitude = .04)
        val reference = quiet.copyOf()
        processor.process(quiet)
        assertArrayEquals(reference, quiet, 1e-6f)
        processor.process(floatArrayOf(1f, 1f))
        val recovery = FloatArray(rate * 2) { .04f }
        processor.process(recovery)
        assertTrue(recovery.first() <= .011f)
        assertTrue(recovery.last() > .039f)
        assertTrue((1 until recovery.size).all { abs(recovery[it] - recovery[it - 1]) < .001f })
    }

    @Test fun `mono average copies one ear without gain and documents phase cancellation`() {
        val config = AudioRescueConfig(channels = 1)
        assertArrayEquals(floatArrayOf(.2f, .2f, -.4f, -.4f), run(config, floatArrayOf(.4f, 0f, 0f, -.8f)), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f), run(config, floatArrayOf(.5f, -.5f)), 0f)
    }

    @Test fun `auto fills either missing ear after sustained imbalance`() {
        for (missing in 0..1) {
            val input = tone(1000.0)
            for (index in missing until input.size step 2) input[index] = 0f
            val output = run(AudioRescueConfig(channels = 2), input)
            for (index in rate until output.size step 2) assertEquals(output[index], output[index + 1], 1e-5f)
            assertTrue(rms(output) > .13)
        }
    }

    @Test fun `auto preserves normal stereo and short artistic pans`() {
        val stereo = tone(500.0, opposite = true)
        assertArrayEquals(stereo, run(AudioRescueConfig(channels = 2), stereo), 0f)
        val shortPan = tone(1000.0, seconds = .15)
        for (index in 0 until shortPan.size step 2) shortPan[index] = 0f
        assertArrayEquals(shortPan, run(AudioRescueConfig(channels = 2), shortPan), 0f)
    }

    @Test fun `auto does not amplify near silent microphone noise`() {
        val input = FloatArray(rate * 2) { if (it % 2 == 0) .00001f else .0001f }
        assertArrayEquals(input, run(AudioRescueConfig(channels = 2), input), 0f)
    }

    @Test fun `confirmed missing ear survives pauses then releases when normal stereo returns`() {
        val processor = AudioRescueProcessor(rate, 2, AudioRescueConfig(channels = 2))
        fun oneEar(seconds: Double) = tone(900.0, seconds = seconds).also { x ->
            for (i in x.indices step 2) x[i] = 0f
        }
        processor.process(oneEar(.5))
        processor.process(FloatArray(rate * 2))
        val nextSentence = oneEar(.15)
        processor.process(nextSentence)
        for (i in nextSentence.indices step 2) assertEquals(nextSentence[i], nextSentence[i + 1], 1e-6f)
        val stereo = tone(700.0, seconds = .5, opposite = true)
        val expected = stereo.copyOf()
        processor.process(stereo)
        assertArrayEquals(expected.takeLast(1000).toFloatArray(), stereo.takeLast(1000).toFloatArray(), 1e-6f)
    }

    @Test fun `noise filters suppress mains fundamentals and harmonics but preserve speech`() {
        val config = AudioRescueConfig(noiseReduction = true)
        for (hz in listOf(50.0, 60.0, 100.0, 120.0)) {
            val input = tone(hz, seconds = 2.0)
            assertTrue("hum $hz", rms(run(config, input)) < rms(input) * .12)
        }
        val input = tone(1000.0)
        assertTrue(rms(run(config, input)) > rms(input) * .9)
    }

    @Test fun `noise expansion attenuates quiet broadband hiss without unstable output`() {
        val random = Random(7)
        val input = FloatArray(rate * 4) { random.nextFloat() * .001f - .0005f }
        val output = run(AudioRescueConfig(noiseReduction = true), input)
        assertTrue(rms(output) < rms(input) * .3)
        assertTrue(output.all { it.isFinite() })
    }

    @Test fun `unavailable voice model preserves centered panned and antiphase speech`() {
        val config = AudioRescueConfig(voiceFocus = true)
        for (input in listOf(tone(1000.0), tone(1000.0, opposite = true), tone(60.0)))
            assertArrayEquals(input, run(config, input), 0f)
    }

    @Test fun `processing is invariant to write partition and checkpoint replay`() {
        val config = AudioRescueConfig(true, 2, true, true)
        val input = tone(323.0)
        val expected = run(config, input)
        val processor = AudioRescueProcessor(rate, 2, config)
        val actual = FloatArray(input.size)
        var at = 0
        while (at < input.size) {
            val end = min(input.size, at + 514)
            val part = input.copyOfRange(at, end)
            processor.process(part); part.copyInto(actual, at); at = end
        }
        assertArrayEquals(expected, actual, 0f)
        val checkpoint = processor.checkpoint()
        val burst = floatArrayOf(1f, -1f, .1f, .1f)
        val first = burst.copyOf().also { processor.process(it) }
        processor.restore(checkpoint)
        assertArrayEquals(first, burst.copyOf().also { processor.process(it) }, 0f)
    }

    @Test fun `all combinations keep finite bounded output across supported rates and channels`() {
        for (sampleRate in listOf(8000, 44100, 48000, 192000)) for (channels in 1..2) {
            for (mask in 0..15) {
                val processor = AudioRescueProcessor(sampleRate, channels,
                    AudioRescueConfig(mask and 1 != 0, if (mask and 2 != 0) 2 else 0, mask and 4 != 0, mask and 8 != 0))
                val data = FloatArray(4000 * channels) { if (it % 3 == 0) 1f else -1f }
                processor.process(data)
                assertTrue(data.all { it.isFinite() && abs(it) <= 1f })
            }
        }
    }

    @Test fun `enabled processor handles nonfinite samples and rejects unknown formats`() {
        val output = run(AudioRescueConfig(peakGuard = true), floatArrayOf(Float.NaN, Float.NEGATIVE_INFINITY))
        assertArrayEquals(floatArrayOf(0f, 0f), output, 0f)
        for ((rate, channels) in listOf(0 to 2, 48000 to 6, 384000 to 1)) {
            assertTrue(runCatching { AudioRescueProcessor(rate, channels, AudioRescueConfig()) }.isFailure)
        }
    }
}
