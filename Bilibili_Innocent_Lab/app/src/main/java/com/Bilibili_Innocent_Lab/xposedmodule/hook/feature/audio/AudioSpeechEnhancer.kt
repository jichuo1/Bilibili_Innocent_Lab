package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

import kotlin.math.*

/** Stateful 16 kHz spectral model. Cache arrays belong to one track's shared reference. */
internal fun interface AudioSpeechModel : AutoCloseable {
    fun infer(spectrum: FloatArray, caches: Array<FloatArray>): FloatArray
    fun infer(spectrum: FloatArray, caches: Array<FloatArray>, output: FloatArray): FloatArray = infer(spectrum, caches)
    fun profile(): LongArray = longArrayOf()
    override fun close() = Unit
}

/** Causal rate conversion + 512/256 sqrt-Hann GTCRN. No frames are inserted or removed.
 * One shared complex speech mask is applied to each original ear spectrum, preserving stereo.
 * Cancellation-resistant reference selection retains panned and opposite-phase speech.
 * The fixed analysis/rate-conversion delay is about 33 ms, also applied to the dry mix.
 * Every persistent sample and recurrent state participates in write-transaction rollback.
 */
internal class AudioSpeechEnhancer(
    private val rate: Int, channels: Int, private val model: AudioSpeechModel,
    private val wet: Double = VOICE_WET, private val preserveTreble: Boolean = true
) {
    // Preserve the full-band original at the same delay as the down/up-sampled correction.
    // Mixing dry audio inside the 16 kHz path discards its treble as well as the model's.
    private val delay = (512 + 7.5) * rate / 16000 + (TAPS - 1) * .5
    private val dryLag = floor(delay).toInt()
    private val drySize = ceil(delay).toInt() + 4
    private val fraction = delay - dryLag
    private val dryWeights = doubleArrayOf(
        -fraction * (fraction - 1) * (fraction - 2) / 6,
        (fraction + 1) * (fraction - 1) * (fraction - 2) / 2,
        -(fraction + 1) * fraction * (fraction - 2) / 2,
        (fraction + 1) * fraction * (fraction - 1) / 6
    )
    private class Channel(drySize: Int) {
        val dry = DoubleArray(drySize)
        val input = DoubleArray(TAPS)
        val analysis = DoubleArray(512)
        val overlap = DoubleArray(256)
        val output = DoubleArray(256)
        val upsample = DoubleArray(16)
        val dryOutput = DoubleArray(256)
        val dryUpsample = DoubleArray(16)
        val real = DoubleArray(512)
        val imaginary = DoubleArray(512)
    }
    private val ears = Array(channels) { Channel(drySize) }
    private val caches = arrayOf(FloatArray(16896), FloatArray(96), FloatArray(1056))
    private var inputAt = 0
    private var phase = 0
    private var hopAt = 0
    private var upAt = 0
    private var dryAt = 0
    var left = 0.0; private set
    var right = 0.0; private set
    private val spectrum = FloatArray(514)
    private val enhancedScratch = FloatArray(514)
    private val reusableCacheSnapshot = FloatArray(CACHE_TOTAL)
    val stateSize = 5 + ears.size * (drySize + TAPS + 512 + 256 + 256 + 16 + 256 + 16) + CACHE_TOTAL

    init { require(rate in 16000..192000 && channels in 1..2 && wet in 0.0..1.0) }

    private val down = downTable(rate)

    fun saveCaches(target: FloatArray, offset: Int = 0) {
        var at = offset
        for (cache in caches) {
            System.arraycopy(cache, 0, target, at, cache.size)
            at += cache.size
        }
    }

    fun restoreCaches(source: FloatArray, offset: Int = 0) {
        var at = offset
        for (cache in caches) {
            System.arraycopy(source, at, cache, 0, cache.size)
            at += cache.size
        }
    }

    fun save(target: DoubleArray, offset: Int) {
        var at = offset
        target[at++] = inputAt.toDouble(); target[at++] = phase.toDouble()
        target[at++] = hopAt.toDouble(); target[at++] = upAt.toDouble()
        target[at++] = dryAt.toDouble()
        for (ear in ears) {
            System.arraycopy(ear.dry, 0, target, at, ear.dry.size); at += ear.dry.size
            System.arraycopy(ear.input, 0, target, at, ear.input.size); at += ear.input.size
            System.arraycopy(ear.analysis, 0, target, at, ear.analysis.size); at += ear.analysis.size
            System.arraycopy(ear.overlap, 0, target, at, ear.overlap.size); at += ear.overlap.size
            System.arraycopy(ear.output, 0, target, at, ear.output.size); at += ear.output.size
            System.arraycopy(ear.upsample, 0, target, at, ear.upsample.size); at += ear.upsample.size
            System.arraycopy(ear.dryOutput, 0, target, at, ear.dryOutput.size); at += ear.dryOutput.size
            System.arraycopy(ear.dryUpsample, 0, target, at, ear.dryUpsample.size); at += ear.dryUpsample.size
        }
        val flatCaches = reusableCacheSnapshot
        saveCaches(flatCaches, 0)
        for (index in 0 until flatCaches.size) target[at++] = flatCaches[index].toDouble()
    }

    fun restore(source: DoubleArray, offset: Int) {
        var at = offset
        inputAt = source[at++].toInt(); phase = source[at++].toInt()
        hopAt = source[at++].toInt(); upAt = source[at++].toInt()
        dryAt = source[at++].toInt()
        for (ear in ears) {
            System.arraycopy(source, at, ear.dry, 0, ear.dry.size); at += ear.dry.size
            System.arraycopy(source, at, ear.input, 0, ear.input.size); at += ear.input.size
            System.arraycopy(source, at, ear.analysis, 0, ear.analysis.size); at += ear.analysis.size
            System.arraycopy(source, at, ear.overlap, 0, ear.overlap.size); at += ear.overlap.size
            System.arraycopy(source, at, ear.output, 0, ear.output.size); at += ear.output.size
            System.arraycopy(source, at, ear.upsample, 0, ear.upsample.size); at += ear.upsample.size
            System.arraycopy(source, at, ear.dryOutput, 0, ear.dryOutput.size); at += ear.dryOutput.size
            System.arraycopy(source, at, ear.dryUpsample, 0, ear.dryUpsample.size); at += ear.dryUpsample.size
        }
        val flatCaches = reusableCacheSnapshot
        for (index in 0 until flatCaches.size) flatCaches[index] = source[at++].toFloat()
        restoreCaches(flatCaches, 0)
    }

    fun process(inputLeft: Double, inputRight: Double) {
        ears[0].dry[dryAt] = inputLeft
        if (ears.size == 2) ears[1].dry[dryAt] = inputRight
        ears[0].input[inputAt] = inputLeft
        if (ears.size == 2) ears[1].input[inputAt] = inputRight
        phase += 16000
        if (phase >= rate) {
            phase -= rate
            val coefficients = down[(phase.toLong() * 128 / 16000).toInt().coerceIn(0, 127)]
            for (ear in ears) {
                var value = 0.0
                for (tap in 0 until TAPS) value += ear.input[(inputAt - tap + TAPS) % TAPS] * coefficients[tap]
                ear.analysis[256 + hopAt] = value
                // Consume the previous inferred hop before publishing the new one.
                ear.upsample[upAt] = ear.output[hopAt]
                if (!preserveTreble) ear.dryUpsample[upAt] = ear.dryOutput[hopAt]
            }
            upAt = (upAt + 1) and 15
            if (++hopAt == 256) {
                enhanceHop()
                hopAt = 0
            }
        }
        inputAt = (inputAt + 1) % TAPS
        val coefficients = UP[(phase.toLong() * 128 / rate).toInt().coerceIn(0, 127)]
        left = sample(ears[0], coefficients)
        right = if (ears.size == 2) sample(ears[1], coefficients) else left
        dryAt = (dryAt + 1) % drySize
    }

    private fun sample(ear: Channel, coefficients: DoubleArray): Double {
        var value = if (preserveTreble && fraction < 1e-9) ear.dry[(dryAt - dryLag + drySize) % drySize] else 0.0
        if (preserveTreble && fraction >= 1e-9) for (tap in dryWeights.indices)
            value += ear.dry[(dryAt - dryLag + 1 - tap + drySize) % drySize] * dryWeights[tap]
        for (tap in 0 until 16) {
            val at = (upAt - 1 - tap + 32) and 15
            value += (ear.upsample[at] + if (preserveTreble) 0.0 else ear.dryUpsample[at]) * coefficients[tap]
        }
        return value
    }

    private fun enhanceHop() {
        for (ear in ears) {
            for (i in 0 until 512) { ear.real[i] = ear.analysis[i] * WINDOW[i]; ear.imaginary[i] = 0.0 }
            AudioFft.transform(ear.real, ear.imaginary, false)
        }
        val spectrum = this.spectrum
        for (i in 0..256) {
            val first = ears[0]; val second = if (ears.size == 2) ears[1] else first
            val leftReal = first.real[i]; val leftImag = first.imaginary[i]
            val rightReal = second.real[i]; val rightImag = second.imaginary[i]
            var real = (leftReal + rightReal) * .5
            var imaginary = (leftImag + rightImag) * .5
            val leftPower = leftReal * leftReal + leftImag * leftImag
            val rightPower = rightReal * rightReal + rightImag * rightImag
            // Averaging inverted channels can erase the voice. Use an actual ear in bins
            // with cancellation, while ordinary centred speech benefits from stereo averaging.
            if (real * real + imaginary * imaginary < max(leftPower, rightPower) * .25) {
                val selected = if (leftPower >= rightPower) first else second
                real = selected.real[i]; imaginary = selected.imaginary[i]
            }
            spectrum[2 * i] = real.toFloat(); spectrum[2 * i + 1] = imaginary.toFloat()
        }
        val enhanced = model.infer(spectrum, caches, enhancedScratch)
        require(enhanced.size == 514 && enhanced.all { it.isFinite() })
        for (i in 0..256) {
            val r = spectrum[2 * i].toDouble(); val im = spectrum[2 * i + 1].toDouble()
            val energy = r * r + im * im
            var maskR = 1.0; var maskI = 0.0
            if (energy > 1e-16) {
                maskR = (enhanced[2 * i] * r + enhanced[2 * i + 1] * im) / energy
                maskI = (enhanced[2 * i + 1] * r - enhanced[2 * i] * im) / energy
                val magnitude = sqrt(maskR * maskR + maskI * maskI)
                if (magnitude > 2) { maskR *= 2 / magnitude; maskI *= 2 / magnitude }
            }
            for (ear in ears) {
                val real = ear.real[i]; val imaginary = ear.imaginary[i]
                ear.real[i] = real * maskR - imaginary * maskI
                ear.imaginary[i] = imaginary * maskR + real * maskI
            }
        }
        for (ear in ears) {
            for (i in 257 until 512) { ear.real[i] = ear.real[512 - i]; ear.imaginary[i] = -ear.imaginary[512 - i] }
            AudioFft.transform(ear.real, ear.imaginary, true)
            for (i in 0 until 256) {
                val value = ear.real[i] * WINDOW[i] + ear.overlap[i]
                ear.output[i] = (value - ear.analysis[i]) * wet
                if (!preserveTreble) ear.dryOutput[i] = ear.analysis[i]
                ear.overlap[i] = ear.real[256 + i] * WINDOW[256 + i]
            }
            System.arraycopy(ear.analysis, 256, ear.analysis, 0, 256)
        }
    }

    companion object {
        const val VOICE_WET = .7
        const val NOISE_WET = .75
        private const val TAPS = 48
        private const val CACHE_TOTAL = 16896 + 96 + 1056
        private val WINDOW = DoubleArray(512) { sqrt(.5 - .5 * cos(2 * PI * it / 512)) }
        private val UP = Array(128) { fraction -> coefficients(16, .45, -fraction / 128.0) }
        private val DOWN_CACHE = object : LinkedHashMap<Int, Array<DoubleArray>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Array<DoubleArray>>?) = size > 16
        }
        private fun downTable(rate: Int): Array<DoubleArray> = synchronized(DOWN_CACHE) {
            DOWN_CACHE.getOrPut(rate) {
                Array(128) { fraction -> coefficients(TAPS, min(7200.0 / rate, .45), fraction / 128.0) }
            }
        }
        private fun coefficients(size: Int, cutoff: Double, fraction: Double): DoubleArray {
            val result = DoubleArray(size) { i ->
                val x = i - (size - 1) * .5 - fraction
                val sinc = if (abs(x) < 1e-12) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
                sinc * (.54 - .46 * cos(2 * PI * i / (size - 1)))
            }
            val sum = result.sum()
            for (i in result.indices) result[i] /= sum
            return result
        }
    }
}

/** Fixed radix-2 FFT: Android-independent and allocation-free after caller scratch creation. */
internal object AudioFft {
    fun transform(real: DoubleArray, imaginary: DoubleArray, inverse: Boolean) {
        val n = real.size
        require(n > 0 && n and (n - 1) == 0 && imaginary.size == n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val im = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = im
            }
        }
        var length = 2
        while (length <= n) {
            val angle = (if (inverse) 2 else -2) * PI / length
            val stepR = cos(angle); val stepI = sin(angle)
            var start = 0
            while (start < n) {
                var wr = 1.0; var wi = 0.0
                for (k in 0 until length / 2) {
                    val a = start + k; val b = a + length / 2
                    val br = real[b] * wr - imaginary[b] * wi
                    val bi = real[b] * wi + imaginary[b] * wr
                    real[b] = real[a] - br; imaginary[b] = imaginary[a] - bi
                    real[a] += br; imaginary[a] += bi
                    val next = wr * stepR - wi * stepI; wi = wr * stepI + wi * stepR; wr = next
                }
                start += length
            }
            length *= 2
        }
        if (inverse) for (i in real.indices) { real[i] /= n; imaginary[i] /= n }
    }
}
