package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Android-independent write transaction, tested with partial/error returns and reused buffers. */
internal class AudioRescueWrite(private val processor: AudioRescueProcessor, private val encoding: Int) {
    data class Metrics(val frames: Int, val inputPeak: Double, val outputPeak: Double,
        val inputStereoDifference: Double, val outputStereoDifference: Double,
        val inputRms: Double, val outputRms: Double)
    class Pending(
        val data: Any,
        val count: Int,
        val originalBuffer: ByteBuffer?,
        val originalPosition: Int,
        internal val input: FloatArray,
        internal val sampleCount: Int,
        internal val output: FloatArray,
        internal val checkpoint: DoubleArray,
        internal var postCheckpoint: DoubleArray?,
        internal val samplesPerUnit: Double
    )
    private var reusableInput: FloatArray? = null
    private var reusableOutput: FloatArray? = null
    private var reusableShorts: ShortArray? = null
    private var reusableBytes: ByteArray? = null
    private var reusableBytesWrapper: ByteBuffer? = null
    private var reusableBuffer: ByteBuffer? = null
    private var reusableContinuationInput: FloatArray? = null
    private var reusableContinuationOutput: FloatArray? = null
    private var continuationSamples = 0
    private var continuationPostCheckpoint: DoubleArray? = null
    private val checkpointPool = arrayOfNulls<DoubleArray>(3)

    fun prepare(data: Any?, offset: Int, count: Int): Pending? {
        if (offset < 0 || count <= 0 || count > MAX_UNITS) return null
        val bytesPerSample = when (encoding) { 2 -> 2; 3 -> 1; 4 -> 4; else -> return null }
        val input: FloatArray
        val sampleCount: Int
        val units: Double
        val original = data as? ByteBuffer
        when (data) {
            is ByteArray -> {
                if (encoding == 4 || offset > data.size - count || count % (bytesPerSample * processor.channelCount) != 0) return null
                sampleCount = count / bytesPerSample
                input = acquireInput(sampleCount)
                decodeByteArrayInto(data, offset, input, sampleCount)
                units = 1.0 / bytesPerSample
            }
            is ShortArray -> {
                if (encoding != 2 || offset > data.size - count || count % processor.channelCount != 0) return null
                sampleCount = count
                input = acquireInput(sampleCount)
                for (i in 0 until sampleCount) input[i] = data[offset + i] / 32768f
                units = 1.0
            }
            is FloatArray -> {
                if (encoding != 4 || offset > data.size - count || count % processor.channelCount != 0) return null
                sampleCount = count
                input = acquireInput(sampleCount)
                System.arraycopy(data, offset, input, 0, sampleCount)
                units = 1.0
            }
            is ByteBuffer -> {
                if (count > data.remaining() || count % (bytesPerSample * processor.channelCount) != 0) return null
                val buffer = data.duplicate().order(ByteOrder.nativeOrder())
                sampleCount = count / bytesPerSample
                input = acquireInput(sampleCount)
                decodeInto(buffer, input, sampleCount)
                units = 1.0 / bytesPerSample
            }
            else -> return null
        }
        val activeContinuation = continuationPostCheckpoint
        val activeContinuationSamples = continuationSamples
        val activeContinuationInput = reusableContinuationInput
        val activeContinuationOutput = reusableContinuationOutput
        continuationPostCheckpoint = null
        continuationSamples = 0
        val checkpoint = acquireCheckpoint(exclude1 = activeContinuation).also(processor::checkpointInto)
        val output = acquireOutput(sampleCount)
        val postCheckpoint: DoubleArray?
        if (activeContinuation != null &&
            activeContinuationSamples == sampleCount &&
            activeContinuationInput != null &&
            activeContinuationOutput != null &&
            arraysEqualPrefix(input, sampleCount, activeContinuationInput)
        ) {
            System.arraycopy(activeContinuationOutput, 0, output, 0, sampleCount)
            processor.restore(activeContinuation)
            postCheckpoint = activeContinuation
        } else {
            System.arraycopy(input, 0, output, 0, sampleCount)
            try {
                processor.process(output)
            } catch (failure: Throwable) {
                processor.restore(checkpoint)
                throw failure
            }
            postCheckpoint = null
        }
        val replacement: Any = when (data) {
            is ShortArray -> {
                val target = reusableShorts?.takeIf { it.size == count }
                    ?: ShortArray(count).also { reusableShorts = it }
                for (i in 0 until count) target[i] = pcm16(output[i])
                target
            }
            is FloatArray -> output
            is ByteBuffer -> {
                val buffer = reusableBuffer?.takeIf { it.capacity() >= count }
                    ?.apply { clear(); limit(count); order(ByteOrder.nativeOrder()) }
                    ?: ByteBuffer.allocateDirect(count).order(ByteOrder.nativeOrder()).also { reusableBuffer = it }
                encodeSamples(output, buffer)
                buffer.flip()
                buffer
            }
            else -> {
                val target = acquireBytes(count)
                val buffer = reusableBytesWrapper!!.apply { clear(); order(ByteOrder.nativeOrder()) }
                encodeSamples(output, buffer)
                target
            }
        }
        return Pending(replacement, count, original, original?.position() ?: 0, input, sampleCount, output, checkpoint, postCheckpoint, units)
    }

    /** Never rewrite the return value. Android advances only the private replacement buffer. */
    fun finish(pending: Pending, accepted: Int) {
        val valid = accepted in 0..pending.count
        val units = if (valid) accepted else 0
        if (units != pending.count) {
            val rawSamples = units * pending.samplesPerUnit
            val samples = rawSamples.toInt()
            val frames = samples / processor.channelCount
            val alignedSamples = frames * processor.channelCount
            val exactSlice = units == 0 || (rawSamples == alignedSamples.toDouble() && alignedSamples < pending.output.size)
            val continuationCheckpoint = if (valid && exactSlice) {
                pending.postCheckpoint ?: acquireCheckpoint(exclude1 = pending.checkpoint).also {
                    processor.checkpointInto(it)
                    pending.postCheckpoint = it
                }
            } else {
                null
            }
            processor.restore(pending.checkpoint)
            processor.process(pending.input, frames)
            if (continuationCheckpoint != null) {
                val remaining = pending.sampleCount - alignedSamples
                val targetInput = reusableContinuationInput?.takeIf { it.size >= remaining }
                    ?: FloatArray(remaining).also { reusableContinuationInput = it }
                val targetOutput = reusableContinuationOutput?.takeIf { it.size >= remaining }
                    ?: FloatArray(remaining).also { reusableContinuationOutput = it }
                System.arraycopy(pending.input, alignedSamples, targetInput, 0, remaining)
                System.arraycopy(pending.output, alignedSamples, targetOutput, 0, remaining)
                continuationSamples = remaining
                continuationPostCheckpoint = continuationCheckpoint
            } else {
                continuationSamples = 0
                continuationPostCheckpoint = null
            }
        } else {
            continuationSamples = 0
            continuationPostCheckpoint = null
        }
        if (valid) {
            pending.originalBuffer?.let { buffer ->
                val target = pending.originalPosition.toLong() + units.toLong()
                if (target in 0L..buffer.limit().toLong()) {
                    buffer.position(target.toInt())
                }
            }
        }
    }

    /** Debug-only evidence of the exact accepted, encoded prefix; never retains PCM or alters
     * either buffer's position. Call before finish(), whose rollback replay may reuse input.
     */
    fun metrics(pending: Pending, accepted: Int): Metrics? {
        if (accepted !in 1..pending.count) return null
        val frames = (accepted * pending.samplesPerUnit).toInt() / processor.channelCount
        if (frames == 0) return null
        val data = pending.data
        val buffer = when (data) {
            is ByteArray -> ByteBuffer.wrap(data).order(ByteOrder.nativeOrder())
            is ByteBuffer -> data.duplicate().order(ByteOrder.nativeOrder())
            else -> null
        }
        fun output(index: Int): Double = when (data) {
            is ShortArray -> data[index] / 32768.0
            is FloatArray -> data[index].toDouble()
            else -> when (encoding) {
                2 -> checkNotNull(buffer).getShort(index * 2) / 32768.0
                3 -> ((checkNotNull(buffer).get(index).toInt() and 255) - 128) / 128.0
                else -> checkNotNull(buffer).getFloat(index * 4).toDouble()
            }
        }
        var inputPeak = 0.0; var outputPeak = 0.0
        var inputDifference = 0.0; var outputDifference = 0.0
        var inputEnergy = 0.0; var outputEnergy = 0.0
        for (frame in 0 until frames) {
            val at = frame * processor.channelCount
            for (channel in 0 until processor.channelCount) {
                val source = pending.input[at + channel].takeIf { it.isFinite() }?.toDouble() ?: 0.0
                val result = output(at + channel)
                inputPeak = maxOf(inputPeak, kotlin.math.abs(source))
                outputPeak = maxOf(outputPeak, kotlin.math.abs(result))
                inputEnergy += source * source; outputEnergy += result * result
            }
            if (processor.channelCount == 2) {
                inputDifference = maxOf(inputDifference, kotlin.math.abs(pending.input[at].toDouble() - pending.input[at + 1]))
                outputDifference = maxOf(outputDifference, kotlin.math.abs(output(at) - output(at + 1)))
            }
        }
        val samples = frames * processor.channelCount
        return Metrics(frames, inputPeak, outputPeak, inputDifference, outputDifference,
            kotlin.math.sqrt(inputEnergy / samples), kotlin.math.sqrt(outputEnergy / samples))
    }

    private fun acquireCheckpoint(exclude1: DoubleArray? = null, exclude2: DoubleArray? = null): DoubleArray {
        val needed = processor.stateSize
        for (i in checkpointPool.indices) {
            val slot = checkpointPool[i]
            if (slot !== exclude1 && slot !== exclude2) {
                return if (slot != null && slot.size >= needed) slot
                else DoubleArray(needed).also { checkpointPool[i] = it }
            }
        }
        return DoubleArray(needed)
    }

    private fun arraysEqualPrefix(a: FloatArray, count: Int, b: FloatArray): Boolean {
        if (b.size < count) return false
        for (i in 0 until count) {
            if (a[i] != b[i]) return false
        }
        return true
    }

    private fun decodeByteArrayInto(data: ByteArray, offset: Int, target: FloatArray, size: Int) {
        if (encoding == 3) {
            for (i in 0 until size) {
                target[i] = ((data[offset + i].toInt() and 0xFF) - 128) / 128f
            }
            return
        }
        val littleEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
        var at = offset
        for (i in 0 until size) {
            val sampleBits = if (littleEndian) {
                (data[at].toInt() and 0xFF) or (data[at + 1].toInt() shl 8)
            } else {
                (data[at].toInt() shl 8) or (data[at + 1].toInt() and 0xFF)
            }
            target[i] = sampleBits.toShort() / 32768f
            at += 2
        }
    }

    private fun acquireInput(size: Int): FloatArray =
        reusableInput?.takeIf { it.size >= size } ?: FloatArray(size).also { reusableInput = it }

    private fun acquireOutput(size: Int): FloatArray =
        reusableOutput?.takeIf { it.size == size } ?: FloatArray(size).also { reusableOutput = it }

    private fun acquireBytes(size: Int): ByteArray {
        val existing = reusableBytes
        if (existing != null && existing.size == size) return existing
        return ByteArray(size).also {
            reusableBytes = it
            reusableBytesWrapper = ByteBuffer.wrap(it).order(ByteOrder.nativeOrder())
        }
    }

    private fun encodeSamples(output: FloatArray, buffer: ByteBuffer) {
        for (sample in output) when (encoding) {
            2 -> buffer.putShort(pcm16(sample))
            3 -> buffer.put((sample * 128 + 128).roundToInt().coerceIn(0, 255).toByte())
            4 -> buffer.putFloat(sample)
        }
    }

    private fun decodeInto(buffer: ByteBuffer, target: FloatArray, size: Int) {
        for (i in 0 until size) {
            target[i] = when (encoding) {
                2 -> buffer.short / 32768f
                3 -> ((buffer.get().toInt() and 255) - 128) / 128f
                else -> buffer.float
            }
        }
    }

    private fun pcm16(sample: Float) = (sample * 32768).roundToInt().coerceIn(-32768, 32767).toShort()
    private companion object { const val MAX_UNITS = 1024 * 1024 }
}
