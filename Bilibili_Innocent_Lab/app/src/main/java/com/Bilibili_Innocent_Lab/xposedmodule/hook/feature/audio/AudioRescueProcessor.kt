package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

import kotlin.math.*

/** Causal PCM DSP: no frame insertion/removal, playback clock changes or platform audio effects.
 * Optional speech inference uses a bounded analysis window and fixed ~33 ms delay.
 * One instance per track. The write adapter serializes access and commits only accepted frames.
 */
internal class AudioRescueProcessor(
    val sampleRate: Int,
    val channelCount: Int,
    private val config: AudioRescueConfig,
    private val speech: AudioSpeechEnhancer? = null
) {
    init {
        require(sampleRate in 8000..192000)
        require(channelCount in 1..2)
    }

    // Persistent state is flat so a partial/failed AudioTrack.write can be rolled back exactly.
    private val state = DoubleArray(40).apply { this[GAIN] = 1.0; this[BASELINE] = .04 }
    private val energyRate = 1 - exp(-1.0 / (.08 * sampleRate))
    private val baselineRate = 1 - exp(-1.0 / (1.5 * sampleRate))
    private val releaseRate = 1 - exp(-1.0 / (.18 * sampleRate))
    private val mixRate = 1 - exp(-1.0 / (.02 * sampleRate))
    private val filters = listOf(
        Biquad.highPass(sampleRate, 80.0),
        Biquad.notch(sampleRate, 50.0), Biquad.notch(sampleRate, 60.0),
        Biquad.notch(sampleRate, 100.0), Biquad.notch(sampleRate, 120.0),
        Biquad.highPass(sampleRate, 180.0), Biquad.lowPass(sampleRate, min(3800.0, sampleRate * .4))
    )

    fun checkpoint(): DoubleArray = DoubleArray(state.size + (speech?.stateSize ?: 0)).also {
        state.copyInto(it); speech?.save(it, state.size)
    }
    fun restore(checkpoint: DoubleArray) {
        checkpoint.copyInto(state, 0, 0, state.size); speech?.restore(checkpoint, state.size)
    }

    /** Interleaved normalized floats. Calls may be split at any complete PCM frame. */
    fun process(samples: FloatArray, frames: Int = samples.size / channelCount) {
        require(frames in 0..samples.size / channelCount)
        if (!config.enabled) return
        for (frame in 0 until frames) {
            val offset = frame * channelCount
            var left = finite(samples[offset])
            var right = if (channelCount == 2) finite(samples[offset + 1]) else left
            state[LEFT_ENERGY] += energyRate * (left * left - state[LEFT_ENERGY])
            state[RIGHT_ENERGY] += energyRate * (right * right - state[RIGHT_ENERGY])

            if (channelCount == 2) when (config.channels) {
                AudioRescueConfig.MONO -> { left = (left + right) * .5; right = left }
                AudioRescueConfig.AUTO_REPAIR -> {
                    val le = state[LEFT_ENERGY]; val re = state[RIGHT_ENERGY]
                    // Require a sustained >24 dB imbalance and an audible good channel. No gain
                    // is applied to the quiet channel (which would amplify its microphone noise).
                    val missingLeft = re > .0001 && le < re * .004
                    val missingRight = le > .0001 && re < le * .004
                    val wait = sampleRate * .2
                    // A confirmed missing ear stays repaired through speech pauses. Resetting
                    // on silence made each new sentence lose its first 200 ms in the corpus.
                    if (max(le, re) > .0001) {
                        state[LEFT_WAIT] = if (missingLeft) min(wait, state[LEFT_WAIT] + 1) else 0.0
                        state[RIGHT_WAIT] = if (missingRight) min(wait, state[RIGHT_WAIT] + 1) else 0.0
                    } else {
                        if (state[LEFT_WAIT] < wait) state[LEFT_WAIT] = 0.0
                        if (state[RIGHT_WAIT] < wait) state[RIGHT_WAIT] = 0.0
                    }
                    state[LEFT_MIX] += mixRate * ((if (state[LEFT_WAIT] >= sampleRate * .2) 1.0 else 0.0) - state[LEFT_MIX])
                    state[RIGHT_MIX] += mixRate * ((if (state[RIGHT_WAIT] >= sampleRate * .2) 1.0 else 0.0) - state[RIGHT_MIX])
                    val originalLeft = left
                    left += state[LEFT_MIX] * (right - left)
                    right += state[RIGHT_MIX] * (originalLeft - right)
                }
            }

            if (speech != null && (config.noiseReduction || config.voiceFocus)) {
                speech.process(left, right)
                left = speech.left; right = speech.right
            }

            if (config.noiseReduction && speech == null) {
                for (index in 0..4) {
                    left = filter(index, 0, left)
                    right = filter(index, 1, right)
                }
                val energy = max(state[LEFT_ENERGY], state[RIGHT_ENERGY])
                // Gentle linked downward expansion, not speech/source separation. Steady loud
                // environmental sound cannot be removed by this lightweight filter.
                val target = (.15 + .85 * sqrt(energy) / .006).coerceIn(.15, 1.0)
                state[NOISE_GAIN] += (if (target > state[NOISE_GAIN]) energyRate * 8 else energyRate) * (target - state[NOISE_GAIN])
                left *= state[NOISE_GAIN]; right *= state[NOISE_GAIN]
            }

            // If the model is unavailable, voice focus preserves the original signal instead
            // of the old centre/band filter, which damaged centred-BGM and panned-speech fixtures.

            if (config.peakGuard) {
                val peak = max(abs(left), abs(right))
                // A fixed -6 dBFS sample ceiling plus a relative rise threshold. Attack is
                // immediate (including the first offending sample); release is smoothed.
                // Speech naturally has high crest factors. The old 3.5x mean-absolute rule
                // clamped ordinary syllables; reserve the relative guard for larger jumps.
                val ceiling = min(.5, max(.25, state[BASELINE] * 8.0))
                val target = if (peak > ceiling) ceiling / peak else 1.0
                if (target < state[GAIN]) {
                    state[GAIN] = target
                    state[HOLD] = sampleRate * .05
                } else if (state[HOLD] > 0) state[HOLD]--
                else state[GAIN] += releaseRate * (target - state[GAIN])
                left *= state[GAIN]; right *= state[GAIN]
                // Slow input baseline adapts to genuine loud sections; the absolute ceiling
                // remains active. Avoid a single impulse lifting the baseline immediately.
                state[BASELINE] += baselineRate * (min(peak, state[BASELINE] * 2 + .01) - state[BASELINE])
            }
            samples[offset] = left.coerceIn(-1.0, 1.0).toFloat()
            if (channelCount == 2) samples[offset + 1] = right.coerceIn(-1.0, 1.0).toFloat()
        }
    }

    private fun finite(value: Float) = if (value.isFinite()) value.toDouble().coerceIn(-1.0, 1.0) else 0.0
    private fun filter(index: Int, channel: Int, value: Double): Double {
        val at = FILTERS + index * 4 + channel * 2
        val f = filters[index]
        val output = f.b0 * value + state[at]
        state[at] = f.b1 * value - f.a1 * output + state[at + 1]
        state[at + 1] = f.b2 * value - f.a2 * output
        return output
    }

    private data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        companion object {
            private fun make(rate: Int, frequency: Double, q: Double, kind: Int): Biquad {
                val w = 2 * PI * frequency / rate
                val c = cos(w); val alpha = sin(w) / (2 * q); val a0 = 1 + alpha
                val b = when (kind) {
                    0 -> doubleArrayOf((1 + c) / 2, -(1 + c), (1 + c) / 2)
                    1 -> doubleArrayOf((1 - c) / 2, 1 - c, (1 - c) / 2)
                    else -> doubleArrayOf(1.0, -2 * c, 1.0)
                }
                return Biquad(b[0] / a0, b[1] / a0, b[2] / a0, -2 * c / a0, (1 - alpha) / a0)
            }
            fun highPass(rate: Int, frequency: Double) = make(rate, frequency, sqrt(.5), 0)
            fun lowPass(rate: Int, frequency: Double) = make(rate, frequency, sqrt(.5), 1)
            fun notch(rate: Int, frequency: Double) = make(rate, frequency, 12.0, 2)
        }
    }

    private companion object {
        const val GAIN = 0; const val BASELINE = 1; const val HOLD = 2
        const val LEFT_ENERGY = 3; const val RIGHT_ENERGY = 4
        const val LEFT_WAIT = 5; const val RIGHT_WAIT = 6
        const val LEFT_MIX = 7; const val RIGHT_MIX = 8; const val NOISE_GAIN = 9
        const val FILTERS = 10
    }
}
