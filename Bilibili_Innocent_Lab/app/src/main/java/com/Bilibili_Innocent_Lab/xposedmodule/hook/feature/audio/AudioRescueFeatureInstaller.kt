package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

import android.media.AudioManager
import android.media.AudioTrack
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import java.nio.ByteBuffer
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** Older ijk backends render in ijkservice; Bilibili 9.13.0 also renders video PCM in main.
 * Scope follows these two media-producing processes and STREAM_MUSIC, never system audio.
 * Android's PCM write contract needs sample access; BetterAndroid offers no equivalent DSP API.
 * No AudioTrack/Activity/player instance is retained by the session values.
 */
internal class AudioRescueFeatureInstaller(
    private val config: AudioRescueConfig,
    private val debugTrace: Boolean = false
) : FeatureInstaller {
    override val id = "player_audio_rescue"
    private class Session(val rate: Int, val channels: Int, val encoding: Int, val config: AudioRescueConfig) {
        val lock = ReentrantLock()
        val generation = AtomicInteger()
        var appliedGeneration = 0
        var writer = newWriter()
        var evidenceCaptured = false
        fun reset() { writer = newWriter() }
        private fun newWriter() = AudioRescueWrite(AudioRescueProcessor(rate, channels, config), encoding)
    }
    private data class InFlight(val session: Session, val writer: AudioRescueWrite, val pending: AudioRescueWrite.Pending, val generation: Int, val entry: Int)
    private val tracks = Collections.synchronizedMap(WeakHashMap<AudioTrack, Session>())
    // Overloads may delegate to each other; nested writes must not process the buffer twice.
    private val inWrite = ThreadLocal<Boolean>()
    private val ready = AtomicBoolean(false)

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!config.enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != MAIN_PROCESS && environment.processName != PLAYER_PROCESS)
            return FeatureInstallResult.Skipped("non-main-process")
        val int = Int::class.javaPrimitiveType!!
        val long = Long::class.javaPrimitiveType!!
        // Every public PCM write entry of AudioTrack. The timestamped ByteBuffer overload that
        // media3 ExoPlayer calls directly only exists on newer Android versions, so it is
        // best-effort: requiring it would disable the whole feature below the API level that
        // introduced it, while hosts on those levels write through the required forms instead.
        val requiredSignatures = listOf(
            arrayOf(ByteArray::class.java, int, int), arrayOf(ByteArray::class.java, int, int, int),
            arrayOf(ShortArray::class.java, int, int), arrayOf(ShortArray::class.java, int, int, int),
            arrayOf(FloatArray::class.java, int, int, int), arrayOf(ByteBuffer::class.java, int, int)
        )
        val optionalSignatures = listOf(
            arrayOf(ByteBuffer::class.java, int, int, long)
        )
        var installed = 0
        var requiredInstalled = 0
        for ((index, signature) in (requiredSignatures + optionalSignatures).withIndex()) {
            val required = index < requiredSignatures.size
            runCatching {
                environment.registrar.exact("$id.write.$index", AudioTrack::class.java, "write", *signature) {
                    before {
                        if (!ready.get() || inWrite.get() == true) return@before
                        val track = instance as? AudioTrack ?: return@before
                        val session = session(track) ?: return@before
                        // Flush/stop must not wait for a blocking writer: they invalidate its
                        // generation, so state is discarded after that write returns.
                        session.lock.lock()
                        try {
                            val generation = session.generation.get()
                            if (session.appliedGeneration != generation) {
                                session.reset(); session.appliedGeneration = generation
                            }
                            val buffer = argOrNull(0) is ByteBuffer
                            val count = argOrNull(if (buffer) 1 else 2) as? Int ?: return@before
                            val offset = if (buffer) 0 else argOrNull(1) as? Int ?: return@before
                            val writer = session.writer
                            val pending = writer.prepare(argOrNull(0), offset, count) ?: return@before
                            setObjectExtra(TRANSACTION, InFlight(session, writer, pending, generation, index))
                            args[0] = pending.data
                            if (!buffer) args[1] = 0
                            inWrite.set(true)
                        } finally {
                            if (getObjectExtra(TRANSACTION) == null) session.lock.unlock()
                        }
                    }
                    after {
                        val transaction = getObjectExtra(TRANSACTION) as? InFlight ?: return@after
                        try {
                            val accepted = if (hasThrowable) -1 else (result as? Int ?: -1)
                            val evidence = if (debugTrace && !transaction.session.evidenceCaptured)
                                runCatching { transaction.writer.metrics(transaction.pending, accepted) }.getOrNull() else null
                            transaction.writer.finish(transaction.pending, accepted)
                            if (evidence != null && evidence.inputPeak >= .01 && evidence.outputPeak >= .01) {
                                transaction.session.evidenceCaptured = true
                                // One aggregate per process via logInfo's existing once-key; no waveform,
                                // content identifier or PCM buffer is recorded. Diagnostics cannot fail a write.
                                runCatching { environment.logInfo("$id.pcm", "[BIL] audio_rescue PCM " +
                                    "entry=${transaction.entry} rate=${transaction.session.rate} channels=${transaction.session.channels} encoding=${transaction.session.encoding} frames=${evidence.frames} " +
                                    "inPeakPpm=${(evidence.inputPeak * 1_000_000).toInt()} outPeakPpm=${(evidence.outputPeak * 1_000_000).toInt()} " +
                                    "inStereoPpm=${(evidence.inputStereoDifference * 1_000_000).toInt()} outStereoPpm=${(evidence.outputStereoDifference * 1_000_000).toInt()} " +
                                    "inRmsPpm=${(evidence.inputRms * 1_000_000).toInt()} outRmsPpm=${(evidence.outputRms * 1_000_000).toInt()}") }
                            }
                            if (transaction.generation != transaction.session.generation.get()) transaction.session.reset()
                        } catch (failure: Throwable) {
                            ready.set(false)
                            environment.logError("$id.write.failed", "[BIL] 音频拯救已停用，写入状态恢复失败: $failure")
                        } finally {
                            inWrite.remove()
                            transaction.session.lock.unlock()
                        }
                    }
                }
                installed++
                if (required) requiredInstalled++
            }.onFailure { environment.logError("$id.register.$index", "[BIL] 音频拯救写入入口注册失败: $it") }
        }
        for (name in listOf("flush", "stop", "release")) {
            runCatching {
                environment.registrar.exact("$id.$name", AudioTrack::class.java, name) {
                    after {
                        if (hasThrowable) return@after
                        val track = instance as? AudioTrack ?: return@after
                        tracks[track]?.generation?.incrementAndGet()
                        if (name == "release") tracks.remove(track)
                    }
                }
                installed++
                requiredInstalled++
            }.onFailure { environment.logError("$id.register.$name", "[BIL] 音频拯救生命周期入口注册失败: $it") }
        }
        // A partially registered required set cannot safely preserve stream state after
        // seeks/flushes. Already registered callbacks stay inert until it is complete.
        ready.set(requiredInstalled == requiredSignatures.size + 3)
        if (debugTrace) runCatching { environment.logInfo("$id.ready", "[BIL] audio_rescue hooks=$installed ready=${ready.get()} process=${environment.processName}") }
        environment.reportStatus("audio_rescue", "installed:$installed;runtime-unverified")
        return if (!ready.get()) FeatureInstallResult.Skipped("registration-failed")
        else FeatureInstallResult.Installed(installed)
    }

    private fun session(track: AudioTrack): Session? = synchronized(tracks) {
        tracks[track]?.let { return@synchronized it }
        // Compressed passthrough, surround and unknown formats are never interpreted as PCM.
        // getAudioAttributes requires API 29. The public volume stream getter works at minSdk
        // 27 and matches ijk's legacy STREAM_MUSIC AudioTrack construction without reflection.
        if (track.state != AudioTrack.STATE_INITIALIZED || track.streamType != AudioManager.STREAM_MUSIC ||
            track.audioFormat !in setOf(2, 3, 4) || track.channelCount !in 1..2 || track.sampleRate !in 8000..192000) return@synchronized null
        Session(track.sampleRate, track.channelCount, track.audioFormat, config).also { tracks[track] = it }
    }

    companion object {
        const val MAIN_PROCESS = "tv.danmaku.bili"
        const val PLAYER_PROCESS = "tv.danmaku.bili:ijkservice"
        private const val TRANSACTION = "bil.audio.rescue.write"
    }
}
