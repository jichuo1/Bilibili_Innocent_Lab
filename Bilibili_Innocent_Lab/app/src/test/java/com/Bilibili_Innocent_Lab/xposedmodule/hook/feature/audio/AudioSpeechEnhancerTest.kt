package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class AudioSpeechEnhancerTest {
    private val identity = AudioSpeechModel { spectrum, _ -> spectrum.copyOf() }
    private fun processor(rate: Int = 48000, model: AudioSpeechModel = identity) = AudioRescueProcessor(rate, 2,
        AudioRescueConfig(voiceFocus = true), AudioSpeechEnhancer(rate, 2, model))

    @Test fun `FFT round trips impulses tones and noise without phase loss`() {
        for (length in listOf(16, 512)) {
            val real = DoubleArray(length) { sin(it * .37) + if (it == 3) .5 else 0.0 }
            val original = real.copyOf(); val imaginary = DoubleArray(length)
            AudioFft.transform(real, imaginary, false); AudioFft.transform(real, imaginary, true)
            assertArrayEquals(original, real, 1e-11)
            assertTrue(imaginary.all { abs(it) < 1e-11 })
        }
    }

    @Test fun `resampled identity preserves opposite phase and bounded level across rates`() {
        for (rate in listOf(16000, 44100, 48000, 96000)) {
            val input = FloatArray(rate) { i -> (sin(2 * PI * 900 * (i / 2) / rate) * .2 * if (i % 2 == 0) 1 else -1).toFloat() }
            processor(rate).process(input)
            assertTrue(input.all { it.isFinite() && abs(it) < .21 })
            for (i in (rate / 4 / 2 * 2) until input.size step 2) assertEquals("rate=$rate index=$i", input[i], -input[i + 1], 1e-6f)
            val energy = input.takeLast(rate / 4).sumOf { it.toDouble() * it } / (rate / 4)
            assertTrue("rate=$rate energy=$energy", energy in .018.. .022)
        }
    }

    @Test fun `neural hop recurrent states and rate conversion replay exactly after partial writes`() {
        val model = AudioSpeechModel { spectrum, caches ->
            caches[0][0] += .00001f
            spectrum.map { it * (.8f + caches[0][0]) }.toFloatArray()
        }
        val expected = processor(model = model); val actual = processor(model = model)
        val warm = FloatArray(3800) { (sin(it * .1) * .15).toFloat() }
        expected.process(warm.copyOf()); actual.process(warm.copyOf())
        val input = FloatArray(1920) { (cos(it * .2) * .1).toFloat() }
        val writer = AudioRescueWrite(actual, 4)
        val pending = writer.prepare(input, 0, input.size)!!
        writer.finish(pending, 600)
        expected.process(input.copyOfRange(0, 600))
        assertArrayEquals(expected.checkpoint(), actual.checkpoint(), 0.0)
        val failed = writer.prepare(input, 0, input.size)!!
        writer.finish(failed, -6)
        assertArrayEquals(expected.checkpoint(), actual.checkpoint(), 0.0)
        val tail = input.copyOfRange(600, input.size)
        val a = tail.copyOf(); val b = tail.copyOf()
        expected.process(a); actual.process(b)
        assertArrayEquals(a, b, 0f)
    }

    @Test fun `identity model preserves full band treble at the fixed production delay`() {
        val rate = 48000
        for (frequency in listOf(900, 10000, 16000)) {
            val original = FloatArray(rate * 2) { (sin(2 * PI * frequency * (it / 2) / rate) * .1).toFloat() }
            val output = original.copyOf()
            processor(rate).process(output)
            for (i in rate until output.size)
                assertEquals("frequency=$frequency index=$i", original[i - 1582 * 2], output[i], 1e-6f)
        }
    }

    @Test fun `speech suppression retains original high band instead of low passing the dry mix`() {
        val rate = 48000
        val silence = AudioSpeechModel { _, _ -> FloatArray(514) }
        for ((frequency, expectedGain) in listOf(900 to .3, 12000 to 1.0)) {
            val original = FloatArray(rate * 2) { (sin(2 * PI * frequency * (it / 2) / rate) * .1).toFloat() }
            val output = original.copyOf()
            processor(rate, silence).process(output)
            val start = rate
            val inputEnergy = original.drop(start).sumOf { it.toDouble() * it }
            val outputEnergy = output.drop(start).sumOf { it.toDouble() * it }
            assertEquals("frequency=$frequency", expectedGain, sqrt(outputEnergy / inputEnergy), .025)
        }
    }

    @Test fun `dedicated denoising still removes high band hiss`() {
        val rate = 48000
        val output = FloatArray(rate * 2) { (sin(2 * PI * 12000 * (it / 2) / rate) * .1).toFloat() }
        val enhancer = AudioSpeechEnhancer(rate, 2, identity, AudioSpeechEnhancer.NOISE_WET, preserveTreble = false)
        AudioRescueProcessor(rate, 2, AudioRescueConfig(noiseReduction = true), enhancer).process(output)
        val energy = output.drop(rate).sumOf { it.toDouble() * it } / rate
        assertTrue("high band residue=$energy", energy < 1e-6)
    }
}
