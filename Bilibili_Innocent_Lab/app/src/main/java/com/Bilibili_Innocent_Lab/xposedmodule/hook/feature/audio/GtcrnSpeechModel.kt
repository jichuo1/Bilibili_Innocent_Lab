package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicLongArray

/** One immutable session per process; all recurrent state is explicit and track-local. */
internal class GtcrnSpeechModel(model: ByteBuffer) : AudioSpeechModel {
    private val environment = OrtEnvironment.getEnvironment()
    private val timings = AtomicLongArray(4)
    private val lock = Any()
    private var closed = false
    private val mixBuffer = directFloatBuffer(514)
    private val cacheBuffers = arrayOf(directFloatBuffer(16896), directFloatBuffer(96), directFloatBuffer(1056))
    private val inputs = LinkedHashMap<String, OnnxTensor>(4)
    private val tensors = arrayOfNulls<OnnxTensor>(4)
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(1)
        options.setInterOpNumThreads(1)
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        options.addConfigEntry("session.intra_op.allow_spinning", "0")
        options.addConfigEntry("session.inter_op.allow_spinning", "0")
        if (OrtProvider.XNNPACK in OrtEnvironment.getAvailableProviders())
            options.addXnnpack(mapOf("intra_op_num_threads" to "1"))
        environment.createSession(model, options)
    }
    override fun profile() = LongArray(4) { timings.get(it) }
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            session.close()
        }
    }

    override fun infer(spectrum: FloatArray, caches: Array<FloatArray>): FloatArray =
        infer(spectrum, caches, FloatArray(514))

    override fun infer(spectrum: FloatArray, caches: Array<FloatArray>, output: FloatArray): FloatArray {
        require(spectrum.size == 514 && output.size == 514 && caches.size == 3)
        synchronized(lock) {
            check(!closed) { "GtcrnSpeechModel closed" }
            val start = System.nanoTime()
            try {
                mixBuffer.clear(); mixBuffer.put(spectrum); mixBuffer.flip()
                val mixTensor = OnnxTensor.createTensor(environment, mixBuffer, SHAPES[0])
                tensors[0] = mixTensor
                inputs[NAMES[0]] = mixTensor
                for (i in caches.indices) {
                    val buffer = cacheBuffers[i]
                    require(caches[i].size == buffer.capacity())
                    buffer.clear(); buffer.put(caches[i]); buffer.flip()
                    val tensor = OnnxTensor.createTensor(environment, buffer, SHAPES[i + 1])
                    tensors[i + 1] = tensor
                    inputs[NAMES[i + 1]] = tensor
                }
                val prepared = System.nanoTime()
                val result = session.run(inputs)
                val inferred = System.nanoTime()
                return result.use { ortResult ->
                    (ortResult[0] as OnnxTensor).floatBuffer.get(output)
                    for (i in caches.indices) (ortResult[i + 1] as OnnxTensor).floatBuffer.get(caches[i])
                    timings.incrementAndGet(0)
                    timings.addAndGet(1, prepared - start)
                    timings.addAndGet(2, inferred - prepared)
                    timings.addAndGet(3, System.nanoTime() - inferred)
                    output
                }
            } finally {
                for (i in tensors.indices) {
                    runCatching { tensors[i]?.close() }
                    tensors[i] = null
                }
                inputs.clear()
            }
        }
    }

    companion object {
        private val NAMES = arrayOf("mix", "conv_cache", "tra_cache", "inter_cache")
        private val SHAPES = arrayOf(longArrayOf(1, 257, 1, 2), longArrayOf(2, 1, 16, 16, 33), longArrayOf(2, 3, 1, 1, 16), longArrayOf(2, 1, 33, 16))
        private fun directFloatBuffer(capacity: Int): FloatBuffer =
            ByteBuffer.allocateDirect(capacity * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }
}
