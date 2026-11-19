package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Fixed corpus is pushed explicitly to targetContext.filesDir/audio-benchmark/fixtures.
 * Executes the production PCM transaction, model and rate conversion on the actual device.
 */
@RunWith(AndroidJUnit4::class)
class AudioRescueBenchmarkDeviceTest {
    @Test fun profileStages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AudioRescueModel.prepare(File(context.applicationInfo.sourceDir), context.codeCacheDir, { name -> File(context.filesDir, "audio-benchmark/assets/$name").takeIf { it.isFile }?.inputStream() }) { _, line -> report(line) }
        val deadline = System.nanoTime() + 20_000_000_000L
        while (AudioRescueModel.current() == null && System.nanoTime() < deadline) Thread.sleep(25)
        val model = checkNotNull(AudioRescueModel.current())
        var nativeNs = 0L
        val measured = AudioSpeechModel { spectrum, caches ->
            val start = System.nanoTime()
            model.infer(spectrum, caches).also { nativeNs += System.nanoTime() - start }
        }
        val block = ShortArray(1920) { (kotlin.math.sin(it * .09) * 6000).toInt().toShort() }
        for ((name, implementation) in listOf("identity" to AudioSpeechModel { x, _ -> x }, "neural" to measured)) {
            val processor = AudioRescueProcessor(48000, 2, AudioRescueConfig(voiceFocus = true), AudioSpeechEnhancer(48000, 2, implementation))
            val writer = AudioRescueWrite(processor, 2)
            repeat(50) { val p = writer.prepare(block, 0, block.size)!!; writer.finish(p, block.size) }
            nativeNs = 0
            val profileBefore = model.profile()
            val start = System.nanoTime()
            repeat(200) { val p = writer.prepare(block, 0, block.size)!!; writer.finish(p, block.size) }
            report("profile $name average_ms=${(System.nanoTime() - start) / 200e6} native_average_ms=${nativeNs / 200e6}")
            val profileAfter = model.profile()
            if (profileAfter.size == 4) report("profile $name model_count/input_ms/run_ms/output_ms=" +
                profileAfter.indices.joinToString("/") { i -> if (i == 0) (profileAfter[i] - profileBefore[i]).toString() else ((profileAfter[i] - profileBefore[i]) / 200e6).toString() })
            val checkpointStart = System.nanoTime()
            repeat(200) { processor.checkpoint() }
            report("profile snapshot_average_ms=${(System.nanoTime() - checkpointStart) / 200e6}")
        }
    }

    @Test fun renderCorpusAndExerciseAudioTrack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.filesDir, "audio-benchmark/fixtures")
        assumeTrue("fixed corpus not pushed", File(source, "index.tsv").isFile)
        val target = File(context.filesDir, "audio-benchmark/device").apply { mkdirs() }
        val start = System.nanoTime()
        AudioRescueModel.prepare(File(context.applicationInfo.sourceDir), context.codeCacheDir, { name -> File(context.filesDir, "audio-benchmark/assets/$name").takeIf { it.isFile }?.inputStream() }) { key, line ->
            instrumentation.sendStatus(0, Bundle().apply { putString(key, line) })
        }
        val deadline = System.nanoTime() + 20_000_000_000L
        while (AudioRescueModel.current() == null && System.nanoTime() < deadline) Thread.sleep(25)
        val model = checkNotNull(AudioRescueModel.current()) { "production speech model failed to prepare" }
        report("model_init_ms=${(System.nanoTime() - start) / 1e6}")
        val summary = StringBuilder("name\tmean_ms\tp99_ms\tmax_ms\tframes\n")
        for (line in File(source, "index.tsv").readLines().drop(1)) {
            val fields = line.split('\t'); val name = fields[0]
            val config = AudioRescueConfig(fields[1] == "1", fields[2].toInt(), fields[3] == "1", fields[4] == "1")
            val floats = ByteBuffer.wrap(File(source, "$name.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val original = FloatArray(floats.remaining()).also { floats.get(it) }
            val speech = if (config.voiceFocus || config.noiseReduction) AudioSpeechEnhancer(48000, 2, model,
                if (config.voiceFocus) AudioSpeechEnhancer.VOICE_WET else AudioSpeechEnhancer.NOISE_WET, preserveTreble = config.voiceFocus) else null
            val processor = AudioRescueProcessor(48000, 2, config, speech)
            val writer = AudioRescueWrite(processor, AudioFormat.ENCODING_PCM_16BIT)
            val rendered = ByteBuffer.allocate(original.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            val times = ArrayList<Double>()
            var at = 0
            while (at < original.size) {
                val size = minOf(1920, original.size - at)
                val pcm = ShortArray(size) { (original[at + it] * 32768).roundToInt().coerceIn(-32768, 32767).toShort() }
                val before = pcm.copyOf()
                val tic = System.nanoTime()
                val pending = checkNotNull(writer.prepare(pcm, 0, size))
                writer.finish(pending, size)
                times += (System.nanoTime() - tic) / 1e6
                assertArrayEquals("caller PCM reused", before, pcm)
                for (sample in pending.data as ShortArray) {
                    val value = sample / 32768f
                    assertTrue(value.isFinite() && (!config.peakGuard || abs(value) <= .500031f))
                    rendered.putFloat(value)
                }
                at += size
            }
            File(target, "$name.f32").writeBytes(rendered.array())
            times.sort()
            val p99 = times[(times.size * .99).toInt().coerceAtMost(times.lastIndex)]
            summary.append("$name\t${times.average()}\t$p99\t${times.last()}\t${original.size / 2}\n")
            report("$name mean_ms=${times.average()} p99_ms=$p99 max_ms=${times.last()}")
            // A 20 ms block must keep at least 20% mean processing headroom. Tail latency
            // is recorded separately and must also be validated against host queue underruns.
            assertTrue("real-time average budget: $name", times.average() < 16)
        }
        File(target, "runtime.tsv").writeText(summary.toString())
        // Actual stream writes at zero track volume, without changing system music volume.
        // BetterAndroid has no equivalent PCM AudioTrack construction/write API.
        val minimum = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack(AudioManager.STREAM_MUSIC, 48000, AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 19200), AudioTrack.MODE_STREAM)
        try {
            assertEquals(AudioTrack.STATE_INITIALIZED, track.state)
            track.setVolume(0f); track.play()
            val pcm = ShortArray(1920) { if (it % 17 == 0) 16000 else -8000 }
            repeat(25) { assertEquals(pcm.size, track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)) }
            track.pause(); track.flush(); track.play()
            repeat(25) { assertEquals(pcm.size, track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)) }
            track.stop()
        } finally { track.release() }
        report("AudioTrack_pause_flush_resume=passed pss_kb=${Debug.getPss()}")
    }

    private fun report(line: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putString("audio_benchmark", line) })
}
