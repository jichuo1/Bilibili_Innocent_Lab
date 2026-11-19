package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Uses the production Kotlin processor, not a Python reimplementation. */
fun main(args: Array<String>) {
    val directory = File(args[0])
    val destination = File(args[1]).apply { mkdirs() }
    val priorNeural = args.getOrNull(4) == "prior-neural"
    var rendered = 0
    for (line in File(directory, "index.tsv").readLines().drop(1)) {
        val fields = line.split('\t')
        val name = fields[0]
        if (args.size > 3 && !Regex(args[3]).containsMatchIn(name)) continue
        val config = AudioRescueConfig(fields[1] == "1", fields[2].toInt(), fields[3] == "1", fields[4] == "1")
        val bytes = File(directory, "$name.f32").readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val input = FloatArray(buffer.remaining()).also { buffer.get(it) }
        val model = if (args.size > 2 && (config.voiceFocus || config.noiseReduction)) SharedModel.get(File(args[2])) else null
        val processor = AudioRescueProcessor(48000, 2, config, model?.let {
            val wet = if (config.voiceFocus) {
                if (priorNeural) .9 else AudioSpeechEnhancer.VOICE_WET
            } else AudioSpeechEnhancer.NOISE_WET
            AudioSpeechEnhancer(48000, 2, it, wet, preserveTreble = config.voiceFocus && !priorNeural)
        })
        val times = ArrayList<Double>()
        var at = 0
        while (at < input.size) {
            val count = minOf(1920, input.size - at)
            val block = input.copyOfRange(at, at + count)
            val start = System.nanoTime()
            processor.process(block)
            times += (System.nanoTime() - start) / 1e6
            block.copyInto(input, at)
            at += count
        }
        val output = ByteBuffer.allocate(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        input.forEach(output::putFloat)
        File(destination, "$name.f32").writeBytes(output.array())
        rendered++
        val sorted = times.sorted()
        println("$name meanMs=${times.average()} p99Ms=${sorted[(sorted.size * .99).toInt().coerceAtMost(sorted.lastIndex)]} maxMs=${sorted.last()}")
    }
    check(rendered > 0) { "No fixtures matched the filter; do not reuse stale outputs" }
    println("rendered=$rendered")
}

private object SharedModel {
    private var model: GtcrnSpeechModel? = null
    fun get(file: File): GtcrnSpeechModel = model ?: GtcrnSpeechModel(file.readBytes()).also { model = it }
}
