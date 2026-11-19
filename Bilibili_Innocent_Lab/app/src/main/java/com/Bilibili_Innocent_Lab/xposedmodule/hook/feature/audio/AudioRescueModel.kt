package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

import android.os.Build
import android.os.Process
import dalvik.system.PathClassLoader
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.assets.*
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.io.File
import java.io.InputStream
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Only verified remote files or host-private cache; no network or work on AudioTrack writers. */
internal object AudioRescueModel {
    private val started = AtomicBoolean()
    @Volatile private var ready: AudioSpeechModel? = null
    @Volatile private var state = "not_requested"
    fun current(): AudioSpeechModel? = ready
    fun status(): String = state

    fun prepare(moduleApk: File, hostCodeCache: File, openRemote: (String) -> InputStream?, log: (String, String) -> Unit) {
        if (!started.compareAndSet(false, true)) return
        val abi = if (Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
        if (abi !in Build.SUPPORTED_ABIS) { state = "unsupported_abi"; log("player_audio_rescue.model.abi", state); return }
        val worker = ScheduledThreadPoolExecutor(1) { Thread(it, "bil-audio-model").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
        fun report(code: String, attempt: Int, detail: String = "") {
            state = code
            log("player_audio_rescue.model.$code.$attempt", "[BIL] audio_assets id=${AudioAssetSpec.ID} stage=$code abi=$abi attempt=$attempt $detail")
        }
        fun attempt(number: Int) {
            var loading = false
            try {
                report("materializing", number)
                val store = AudioAssetStore(hostCodeCache, abi)
                store.locked {
                    for (part in AudioAssetSpec.parts(abi)) store.install(part) {
                        openRemote(part.remoteName) ?: throw AudioAssetFailure("remote_missing", part.name)
                    }
                }
                loading = true
                report("loading", number)
                // ORT calls loadLibrary. Search the verified native directory using a private
                // loader, without mutating host/framework loaders or invoking hidden APIs.
                val loader = ModelLoader(moduleApk.canonicalPath, store.directory.canonicalPath,
                    checkNotNull(AudioRescueModel::class.java.classLoader))
                val type = checkNotNull(Lookup.classOrNull(loader, MODEL_CLASS))
                val model = store.file(AudioAssetSpec.model).readBytes()
                val result = checkNotNull(Lookup.constructorOrNull(type, ByteArray::class.java)).newInstance(model) as AudioSpeechModel
                report("warming", number)
                result.infer(FloatArray(514), arrayOf(FloatArray(16896), FloatArray(96), FloatArray(1056)))
                val warm = AudioSpeechEnhancer(48000, 2, result)
                repeat(12000) { warm.process(0.0, 0.0) }
                ready = result
                report("ready", number)
                worker.shutdown()
            } catch (failure: Throwable) {
                val code = if (loading) "runtime_failed" else (failure as? AudioAssetFailure)?.code ?: "cache_io"
                report(code, number, "part=${(failure as? AudioAssetFailure)?.part.orEmpty()} cause=${failure.javaClass.simpleName} root=${failure.cause?.javaClass?.simpleName.orEmpty()}")
                // Native initialisation may poison a classloader; retry preparation failures only.
                val delay = AudioPreparationRetry.delay(number, loading)
                if (delay != null) worker.schedule({ attempt(number + 1) }, delay, TimeUnit.MILLISECONDS)
                else { report("fallback", number, "reason=$code restart_required=true"); worker.shutdown() }
            }
        }
        worker.execute { attempt(0) }
    }

    private const val MODEL_CLASS = "com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.GtcrnSpeechModel"
    private class ModelLoader(apk: String, nativePath: String, parent: ClassLoader) : PathClassLoader(apk, nativePath, parent) {
        @Synchronized override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (!name.startsWith("ai.onnxruntime.") && name != MODEL_CLASS && !name.startsWith("$MODEL_CLASS$"))
                return super.loadClass(name, resolve)
            val type = findLoadedClass(name) ?: findClass(name)
            if (resolve) resolveClass(type)
            return type
        }
    }
}
