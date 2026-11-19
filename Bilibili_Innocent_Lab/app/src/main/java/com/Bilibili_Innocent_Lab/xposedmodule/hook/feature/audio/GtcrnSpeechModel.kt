package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtProvider
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicLongArray

/** One immutable session per process; all recurrent state is explicit and track-local. */
internal class GtcrnSpeechModel(model: ByteArray) : AudioSpeechModel {
    private val environment = OrtEnvironment.getEnvironment()
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
    private val timings = AtomicLongArray(4)
    override fun profile() = LongArray(4) { timings.get(it) }

    override fun infer(spectrum: FloatArray, caches: Array<FloatArray>): FloatArray {
        val arrays = arrayOf(spectrum, *caches)
        val inputs = LinkedHashMap<String, OnnxTensor>()
        val start = System.nanoTime()
        try {
            for (i in arrays.indices) inputs[NAMES[i]] = OnnxTensor.createTensor(environment, FloatBuffer.wrap(arrays[i]), SHAPES[i])
            val prepared = System.nanoTime()
            val result = session.run(inputs)
            val inferred = System.nanoTime()
            return result.use { output ->
                val enhanced = FloatArray(514)
                (output[0] as OnnxTensor).floatBuffer.get(enhanced)
                for (i in caches.indices) (output[i + 1] as OnnxTensor).floatBuffer.get(caches[i])
                timings.incrementAndGet(0)
                timings.addAndGet(1, prepared - start)
                timings.addAndGet(2, inferred - prepared)
                timings.addAndGet(3, System.nanoTime() - inferred)
                enhanced
            }
        } finally { inputs.values.forEach { it.close() } }
    }

    companion object {
        private val NAMES = arrayOf("mix", "conv_cache", "tra_cache", "inter_cache")
        private val SHAPES = arrayOf(longArrayOf(1, 257, 1, 2), longArrayOf(2, 1, 16, 16, 33), longArrayOf(2, 3, 1, 1, 16), longArrayOf(2, 1, 33, 16))
    }
}
