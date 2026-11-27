package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

import dalvik.system.PathClassLoader
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.assets.*
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ModuleNativeLoader
import java.io.File
import java.io.InputStream
import java.lang.reflect.Constructor
import java.nio.ByteBuffer
import java.util.WeakHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Only verified remote files or host-private cache; no network or work on AudioTrack writers. */
internal object AudioRescueModel {
    private val started = AtomicBoolean()
    private val modelLock = Any()
    private val activeHolders = WeakHashMap<Any, Boolean>()
    @Volatile private var cachedLoader: ModelLoader? = null
    @Volatile private var cachedConstructor: Constructor<*>? = null
    @Volatile private var ready: AudioSpeechModel? = null
    @Volatile private var state = "not_requested"
    fun current(): AudioSpeechModel? = ready
    fun status(): String = state

    fun acquire(holder: Any): AudioSpeechModel? = synchronized(modelLock) {
        val model = ready ?: return null
        activeHolders[holder] = true
        model
    }

    fun release(holder: Any) {
        synchronized(modelLock) {
            activeHolders.remove(holder)
        }
    }

    /** Closes and drops the idle ORT session when no active track holder retains it. */
    fun releaseIfIdle(): Boolean {
        val toClose = synchronized(modelLock) {
            if (activeHolders.isNotEmpty()) return false
            val current = ready ?: return false
            ready = null
            state = "not_requested"
            started.set(false)
            current
        }
        runCatching { toClose.close() }
        return true
    }

    internal fun selectModelAbi(runtimeAbi: String?): String? = when (runtimeAbi) {
        "arm64-v8a", "armeabi-v7a" -> runtimeAbi
        else -> null
    }

    internal fun <T> withVerifiedThenLoad(
        verify: ((ByteBuffer) -> Unit) -> Unit,
        markLoading: () -> Unit,
        load: (ByteBuffer) -> T
    ): T {
        var loaded: T? = null
        verify { bytes ->
            markLoading()
            loaded = load(bytes)
        }
        @Suppress("UNCHECKED_CAST")
        return loaded as T
    }

    fun prepare(moduleApk: File, hostCodeCache: File, openRemote: (String) -> InputStream?, log: (String, String) -> Unit) {
        if (!started.compareAndSet(false, true)) return
        val abi = selectModelAbi(ModuleNativeLoader.runtimeAbi())
        if (abi == null) { state = "unsupported_abi"; log("player_audio_rescue.model.abi", state); return }
        val worker = ScheduledThreadPoolExecutor(1) { Thread(it, "bil-audio-model").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
        fun report(code: String, attempt: Int, detail: String = "") {
            state = code
            log("player_audio_rescue.model.$code.$attempt", "[BIL] audio_assets id=${AudioAssetSpec.ID} stage=$code abi=$abi attempt=$attempt $detail")
        }
        fun attempt(number: Int) {
            var loading = false
            var unpublished: AudioSpeechModel? = null
            try {
                report("materializing", number)
                val store = AudioAssetStore(hostCodeCache, abi)
                store.locked {
                    for (part in AudioAssetSpec.parts(abi)) store.install(part) {
                        openRemote(part.remoteName) ?: throw AudioAssetFailure("remote_missing", part.name)
                    }
                }
                val result = withVerifiedThenLoad(
                    verify = { consume -> AudioModelInput.withVerified(store.file(AudioAssetSpec.model), consume) },
                    markLoading = {
                        loading = true
                        report("loading", number)
                    },
                    load = { bytes ->
                        // ORT calls loadLibrary. Search the verified native directory using a private
                        // loader, without mutating host/framework loaders or invoking hidden APIs.
                        // Reuse a succeeded ModelLoader so re-prepare after releaseIfIdle never loads ORT JNI twice.
                        val constructor = cachedConstructor ?: run {
                            val loader = cachedLoader ?: ModelLoader(moduleApk.canonicalPath, store.directory.canonicalPath,
                                checkNotNull(AudioRescueModel::class.java.classLoader)).also { cachedLoader = it }
                            val type = checkNotNull(Lookup.classOrNull(loader, MODEL_CLASS))
                            checkNotNull(Lookup.constructorOrNull(type, ByteBuffer::class.java)).also { cachedConstructor = it }
                        }
                        (constructor.newInstance(bytes) as AudioSpeechModel).also { unpublished = it }
                    }
                )
                report("warming", number)
                result.infer(FloatArray(514), arrayOf(FloatArray(16896), FloatArray(96), FloatArray(1056)))
                val warm = AudioSpeechEnhancer(48000, 2, result)
                repeat(12000) { warm.process(0.0, 0.0) }
                synchronized(modelLock) {
                    ready = result
                    state = "ready"
                }
                unpublished = null
                log("player_audio_rescue.model.ready.$number", "[BIL] audio_assets id=${AudioAssetSpec.ID} stage=ready abi=$abi attempt=$number ")
                worker.shutdown()
            } catch (failure: Throwable) {
                runCatching { unpublished?.close() }
                val code = if (loading) "runtime_failed" else (failure as? AudioAssetFailure)?.code ?: "cache_io"
                report(code, number, "part=${(failure as? AudioAssetFailure)?.part.orEmpty()} cause=${failure.javaClass.simpleName} root=${failure.cause?.javaClass?.simpleName.orEmpty()}")
                // Native initialisation may poison a classloader; retry preparation failures only.
                val delay = AudioPreparationRetry.delay(number, loading)
                if (delay != null) worker.schedule({ attempt(number + 1) }, delay, TimeUnit.MILLISECONDS)
                else {
                    if (!loading) started.set(false)
                    report("fallback", number, "reason=$code restart_required=$loading")
                    worker.shutdown()
                }
            }
        }
        try {
            worker.execute { attempt(0) }
        } catch (rejected: Throwable) {
            started.set(false)
            worker.shutdownNow()
            throw rejected
        }
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
