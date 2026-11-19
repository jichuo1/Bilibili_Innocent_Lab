package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

import kotlin.math.*

/** Causal PCM DSP: no frame insertion/removal, playback clock changes or platform audio effects.
 * One instance per track. The write adapter serializes access and commits only accepted frames.
 */
internal class AudioRescueProcessor(
    val sampleRate: Int,
    val channelCount: Int,
    private val config: AudioRescueConfig
) {
    init {
        require(sampleRate in 8000..192000)
        require(channelCount in 1..2)
    }

    // Persistent state is flat so a partial/failed AudioTrack.write can be rolled back exactly.
    private val state = DoubleArray(STATE_SIZE).apply { this[GAIN] = 1.0; this[BASELINE] = .04 }
    private val energyRate = 1 - exp(-1.0 / (.08 * sampleRate))
    private val baselineRate = 1 - exp(-1.0 / (1.5 * sampleRate))
    private val releaseRate = 1 - exp(-1.0 / (.18 * sampleRate))
    private val mixRate = 1 - exp(-1.0 / (.02 * sampleRate))

    fun checkpoint(): DoubleArray = state.copyOf()
    fun restore(checkpoint: DoubleArray) {
        checkpoint.copyInto(state, 0, 0, state.size)
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

    private companion object {
        const val GAIN = 0; const val BASELINE = 1; const val HOLD = 2
        const val LEFT_ENERGY = 3; const val RIGHT_ENERGY = 4
        const val LEFT_WAIT = 5; const val RIGHT_WAIT = 6
        const val LEFT_MIX = 7; const val RIGHT_MIX = 8
        const val STATE_SIZE = 9
    }
}
