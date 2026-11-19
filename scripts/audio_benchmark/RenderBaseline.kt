package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Compile with the immutable pre-change production source, then use the same fixture manifest. */
fun main(args: Array<String>) {
    val source = File(args[0]); val target = File(args[1]).apply { mkdirs() }
    for (line in File(source, "index.tsv").readLines().drop(1)) {
        val f = line.split('\t'); val name = f[0]
        val bytes = File(source, "$name.f32").readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val data = FloatArray(b.remaining()).also { b.get(it) }
        val processor = AudioRescueProcessor(48000, 2, AudioRescueConfig(f[1] == "1", f[2].toInt(), f[3] == "1", f[4] == "1"))
        var at = 0
        while (at < data.size) {
            val block = data.copyOfRange(at, minOf(at + 1920, data.size))
            processor.process(block); block.copyInto(data, at); at += block.size
        }
        val output = ByteBuffer.allocate(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        data.forEach(output::putFloat)
        File(target, "$name.f32").writeBytes(output.array())
        println(name)
    }
}
