package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.Context
import android.os.Bundle
import android.os.StatFs
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerBufferLocator
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Creation-time, normal current-video options. Native owns reading, waiting, seek and cleanup. */
internal class PlayerBufferFeatureInstaller(
    mode: Int, seconds: Int, memoryMb: Int, diskMb: Int, wifiOnly: Boolean,
    private val resolver: (ClassLoader) -> PlayerBufferLocator.Points? = PlayerBufferLocator::resolve
) : FeatureInstaller {
    override val id = ID
    private val settings = PlayerBufferPolicy.Settings(PlayerBufferMode.fromValue(mode), seconds, memoryMb, diskMb, wifiOnly)
    override val capabilityIds = when (settings.mode) {
        PlayerBufferMode.FOLLOW_HOST -> emptyList()
        PlayerBufferMode.WHOLE -> listOf(STRATEGY, PRELOAD)
        else -> listOf(STRATEGY)
    }

    private val sessions = PlayerBufferSessions()
    private val sourceCore = ThreadLocal<WeakReference<Any>?>()
    private val creation = ThreadLocal<PlayerBufferSessions.Ticket?>()
    private val options = ThreadLocal<OptionFrame?>()
    private val registeredCores = ConcurrentHashMap.newKeySet<Class<*>>()
    private val registeredCreatorMethods = HashSet<Method>()
    private val creatorReady = AtomicBoolean(false)
    @Volatile private var installedBaseUnits = 0
    @Volatile private var expectedBaseUnits = 0
    private val context = AtomicReference<WeakReference<Context>?>(null)
    private val network = PlayerBufferNetworkState()
    private val worker by lazy {
        ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(2), { runnable ->
            Thread(runnable, "BIL-BufferDisk").apply { isDaemon = true }
        }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    }
    private val disk by lazy {
        PlayerBufferDiskBudget(PlayerBufferConfig.diskBytes(settings.diskMb) ?: 0L, schedule = { task ->
            runCatching { worker.execute { HostThreadGuard.run("player_buffer_disk", task) } }.isSuccess
        }, availableBytes = { directory ->
            context.get()?.get()?.let(network::start)
            StatFs(directory).availableBytes
        })
    }

    private class CreationFrame(val previous: PlayerBufferSessions.Ticket?, val ticket: PlayerBufferSessions.Ticket?)
    private class OptionFrame(val previous: OptionFrame?, val entry: PlayerBufferSessions.Entry?,
        val media: PlayerBufferPolicy.Media) {
        var consumerReady = false
    }

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (settings.mode == PlayerBufferMode.FOLLOW_HOST) return skipped(environment, "disabled")
        if (environment.processName != TARGET) return skipped(environment, "non-main-process")
        if (PlayerBufferConfig.secondsToMillis(settings.seconds) == null ||
            PlayerBufferConfig.memoryBytes(settings.memoryMb) == null || PlayerBufferConfig.diskBytes(settings.diskMb) == null) {
            return skipped(environment, "invalid-settings")
        }
        val loader = environment.classLoader ?: return skipped(environment, "missing-class-loader")
        val point = resolver(loader) ?: return skipped(environment, "missing-host-structure")
        var expected = 0
        var installed = 0
        expectedBaseUnits = 3 + point.bundleMethods.size + point.lifecycle.size + point.playerSelection.size
        fun attempt(method: Method, suffix: String, block: com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator.() -> Unit) {
            expected++
            if (runCatching { environment.registrar.exact("$ID.$suffix", method.declaringClass,
                    method.name, *method.parameterTypes, block = block) }.isSuccess) installed++
            else environment.logError("$ID.register.$suffix", "[BIL] 缓冲策略注册失败，保持宿主策略")
            installedBaseUnits = installed
        }
        attempt(point.handlerGetter, "core-discovery") {
            after {
                sourceCore.remove()
                if (hasThrowable || !point.normalHandler.isInstance(instance)) return@after
                val core = result?.takeIf(point.core::isInstance) ?: return@after
                if (installCurrentCreator(environment, point, core.javaClass)) sourceCore.set(WeakReference(core))
                else {
                    environment.reportStatus(STATUS, "partial:creator-unavailable")
                    environment.logInfo("$ID.creator-unavailable", "[BIL] 当前播放器缺少唯一创建入口，保持宿主缓冲策略")
                }
            }
        }
        attempt(point.init, "init") {
            before {
                val ticket = creation.get() ?: return@before
                val item = instance ?: return@before
                val appContext = point.context.get(item) as? Context
                // ApplicationInfo is already in LoadedApk; getCacheDir would create directories here.
                val root = appContext?.applicationInfo?.dataDir?.let { File(it, "cache").path }
                val probe = root?.let { "$it/.bil_buffer_space" }
                sessions.register(item, ticket, root, probe)
                if (settings.mode == PlayerBufferMode.WHOLE && appContext != null && probe != null) {
                    context.set(WeakReference(appContext))
                    disk.request(probe)
                }
            }
        }
        attempt(point.options, "options-scope") {
            before {
                val item = instance ?: return@before
                val frame = OptionFrame(options.get(), sessions.get(item), snapshot(point, item))
                setObjectExtra(SCOPE_EXTRA, frame)
                options.set(frame)
                if (frame.entry != null && frame.media.eligible) environment.reportRuntimeEvidence(STRATEGY, FeatureRuntimeStage.OBSERVED)
            }
            after {
                val frame = getObjectExtra(SCOPE_EXTRA) as? OptionFrame ?: return@after
                if (frame.previous == null) options.remove() else options.set(frame.previous)
            }
        }
        point.bundleMethods.forEachIndexed { index, method -> attempt(method, "bundle.$index") {
            before {
                val frame = options.get() ?: return@before
                val entry = frame.entry ?: return@before
                val category = (argOrNull(0) as? Int) ?: return@before
                if (category !in 1..4 || category == 2) return@before
                val source = argOrNull(1) as? Bundle ?: return@before
                // Only this explicit Normal play/autoStart source can prepare phase one early.
                // A disk task must wait for the actual current item's identity readback.
                val awaitingCurrent = settings.mode == PlayerBufferMode.WHOLE && !sessions.current(entry)
                val applicableSettings = if (awaitingCurrent) settings.copy(mode = PlayerBufferMode.CUSTOM,
                    seconds = PlayerBufferConfig.DEFAULT_SECONDS) else settings
                val requested = PlayerBufferPolicy.decide(applicableSettings, frame.media.copy(current = sessions.hasPlayIntent(entry)),
                    entry.probePath?.let { if (settings.mode == PlayerBufferMode.WHOLE) disk.available(it) else null }, network.isWifi())
                val decision = if (awaitingCurrent && requested.active) requested.copy(reason = "awaiting-current") else requested
                if (!decision.active) {
                    environment.reportStatus(STATUS, "partial:${decision.reason}")
                    environment.logInfo("$ID.skip.${decision.reason}", "[BIL] 缓冲策略未覆盖当前准备时机：${decision.reason}")
                    return@before
                }
                if (category != 3 && !frame.consumerReady) return@before
                val changes = changes(category, source, decision, entry.cacheRoot)
                if (category == 3 && source.get("max-buffer-size") is Long &&
                    (source.get("max-buffer-size") as Long) in 1L..planConsumerLimit(decision)) {
                    setObjectExtra(CONSUMER_READY_EXTRA, true)
                }
                if (changes.isEmpty()) {
                    if (category == 1 && settings.mode == PlayerBufferMode.WHOLE) {
                        val reason = if (decision.fullPreload) cacheSourceReason({ source.get(it) }, entry.cacheRoot)
                            ?: "options-already-match" else decision.reason
                        environment.reportStatus(PRELOAD_STATUS, if (reason == "options-already-match") reason else "fallback:$reason")
                        environment.logInfo("$ID.preload.$reason", "[BIL] 整段预取参数状态：$reason；完成情况仍需播放验证")
                    }
                    return@before
                }
                val copy = Bundle(source)
                changes.forEach { (key, value) -> copy.putLong(key, value) }
                args[1] = copy
                setObjectExtra(CHANGED_EXTRA, category)
                if (category == 3) setObjectExtra(CONSUMER_READY_EXTRA, true)
            }
            after {
                if (hasThrowable) return@after
                if (getObjectExtra(CONSUMER_READY_EXTRA) == true) options.get()?.consumerReady = true
                val category = getObjectExtra(CHANGED_EXTRA) as? Int ?: return@after
                val capability = if (category == 1) PRELOAD else STRATEGY
                environment.reportRuntimeEvidence(capability, FeatureRuntimeStage.APPLIED)
                environment.reportStatus(if (category == 1) PRELOAD_STATUS else STATUS, "options-applied")
                environment.logInfo("$ID.applied.$category", "[BIL] 缓冲参数已施加；实际缓冲与预取完成仍需播放验证")
            }
        } }
        point.lifecycle.forEach { method -> attempt(method, "lifecycle.${method.name}") {
            before { instance?.let(sessions::remove) }
        } }
        point.playerSelection.forEach { method -> attempt(method, "selection.${method.name}") {
            after {
                val player = instance ?: return@after
                val actual = runCatching { point.playerCurrent.invoke(player) }.getOrNull()
                sessions.select(player, actual)
            }
        } }
        installedBaseUnits = installed
        expectedBaseUnits = expected
        val creators = if (creatorReady.get()) point.creatorCount else 0
        capabilityIds.forEach {
            environment.reportCapabilityCoverage(it, true, installed + creators, expected + point.creatorCount)
        }
        if (installed == 0) return skipped(environment, "registration-failed")
        capabilityIds.forEach { environment.reportRuntimeEvidence(it, FeatureRuntimeStage.ADAPTED) }
        environment.reportStatus(STATUS, if (installed != expected) "partial:$installed/$expected"
            else if (creatorReady.get()) "ready" else "awaiting-current-creator")
        return FeatureInstallResult.Installed(installed + creators,
            complete = installed == expected && creators == point.creatorCount)
    }

    private fun installCurrentCreator(env: HookEnvironment, point: PlayerBufferLocator.Points, owner: Class<*>): Boolean {
        synchronized(registeredCores) {
            if (owner in registeredCores) return true
            if (registeredCores.size >= 8) return false
            val methods = PlayerBufferLocator.currentCreationMethods(owner, point)
            if (methods.size != point.creatorCount) return false
            for ((index, entry) in methods.withIndex()) {
                val method = entry.method
                if (method in registeredCreatorMethods) continue
                if (runCatching {
                        env.registrar.exact("$ID.creator.${method.declaringClass.name}.$index", method.declaringClass, method.name, *method.parameterTypes) {
                            before {
                                val core = instance ?: return@before
                                if (sourceCore.get()?.get() !== core) return@before
                                sourceCore.remove()
                                val approved = entry.autoStartIndex?.let { argOrNull(it) == true } ?: true
                                val frame = CreationFrame(creation.get(), if (approved) PlayerBufferSessions.Ticket() else null)
                                setObjectExtra(CREATION_EXTRA, frame)
                                if (frame.ticket == null) creation.remove() else creation.set(frame.ticket)
                            }
                            after {
                                val frame = getObjectExtra(CREATION_EXTRA) as? CreationFrame ?: return@after
                                try {
                                    frame.ticket?.let { ticket ->
                                        val actual = if (!hasThrowable) runCatching {
                                            val current = point.coreCurrent.invoke(instance)
                                            if (point.realItem.declaringClass.isInstance(current)) point.realItem.invoke(current) else null
                                        }.getOrNull() else null
                                        if (!sessions.commit(ticket, actual)) sessions.cancel(ticket)
                                    }
                                } finally {
                                    if (frame.previous == null) creation.remove() else creation.set(frame.previous)
                                }
                            }
                        }
                    }.isFailure) return false
                registeredCreatorMethods.add(method)
            }
            registeredCores.add(owner)
            if (creatorReady.compareAndSet(false, true)) {
                capabilityIds.forEach {
                    env.reportCapabilityCoverage(it, true, installedBaseUnits + methods.size, expectedBaseUnits + point.creatorCount)
                }
                if (env.runtimePhase?.invoke() == true) {
                    env.installationEvidence?.invoke(FeatureInstallRecord(ID,
                        FeatureInstallResult.Installed(installedBaseUnits + methods.size,
                            installedBaseUnits == expectedBaseUnits), null))
                }
                env.reportStatus(STATUS, if (installedBaseUnits == expectedBaseUnits) "ready" else "partial")
            }
            return true
        }
    }

    private fun snapshot(point: PlayerBufferLocator.Points, item: Any): PlayerBufferPolicy.Media = runCatching {
        val applicable = (point.scheme.invoke(item) as? Int) in setOf(2, 3) &&
            !point.stopped.getBoolean(item) && !point.released.getBoolean(item)
        val asset = point.assetField.get(item) ?: return@runCatching notApplicable()
        if (!applicable || point.assetDrm.invoke(asset) != 0) return@runCatching notApplicable()
        val streams = point.streams.invoke(asset) as? List<*> ?: return@runCatching notApplicable()
        if (streams.isEmpty() || streams.size > 64 || streams.any { it == null || point.streamDrm.invoke(it) != 0 }) {
            return@runCatching notApplicable()
        }
        val segments = if (streams.size == 1) point.segments.invoke(streams[0]) as? List<*> else null
        val segment = segments?.singleOrNull()
        val url = segment?.let { point.segmentUrl.invoke(it) as? String }
        val uri = url?.let { runCatching { URI(it) }.getOrNull() }
        val path = uri?.path.orEmpty()
        val progressive = (point.vodType.get(item) as? Enum<*>)?.name == "VOD_NORMAL" && segment != null &&
            uri?.scheme in setOf("http", "https") && (path.endsWith(".mp4", true) || path.endsWith(".flv", true))
        PlayerBufferPolicy.Media(false, true, progressive,
            (segment?.let(point.segmentSize::invoke) as? Long) ?: 0L,
            (segment?.let(point.segmentDuration::invoke) as? Int)?.toLong() ?: 0L)
    }.getOrElse { notApplicable() }

    private fun notApplicable() = PlayerBufferPolicy.Media(false, false, false, 0L, 0L)

    private fun skipped(env: HookEnvironment, reason: String): FeatureInstallResult.Skipped {
        env.reportStatus(STATUS, reason)
        val result = FeatureInstallResult.Skipped(reason)
        capabilityIds.forEach { env.reportCapability(it, result) }
        return result
    }

    companion object {
        const val ID = "player_buffer"
        const val STRATEGY = "player_buffer_strategy"
        const val PRELOAD = "player_buffer_preload"
        const val STATUS = "player_buffer_status"
        const val PRELOAD_STATUS = "player_buffer_preload_status"
        private const val TARGET = "tv.danmaku.bili"
        private const val CREATION_EXTRA = "player_buffer.creation"
        private const val SCOPE_EXTRA = "player_buffer.options"
        private const val CHANGED_EXTRA = "player_buffer.changed"
        private const val CONSUMER_READY_EXTRA = "player_buffer.consumer-ready"
        private fun planConsumerLimit(plan: PlayerBufferPolicy.Decision) = plan.consumerBytes ?: 0L

        internal fun changes(category: Int, source: Bundle, plan: PlayerBufferPolicy.Decision, root: String?): Map<String, Long> {
            return changes(category, { source.get(it) }, plan, root)
        }

        internal fun changes(category: Int, read: (String) -> Any?, plan: PlayerBufferPolicy.Decision, root: String?): Map<String, Long> {
            if (!plan.active) return emptyMap()
            fun validLong(key: String) = (read(key) as? Long)?.takeIf { it >= 0L }
            val changed = when (category) {
                3 -> validLong("max-buffer-size")?.let { original ->
                    if (original > PlayerBufferConfig.MAX_MEMORY_MB * PlayerBufferConfig.MIB) emptyMap()
                    else mapOf("max-buffer-size" to if (original == 0L) plan.consumerBytes!! else minOf(original, plan.consumerBytes!!))
                } ?: emptyMap()
                4 -> validLong("max-cache-time")?.let {
                    mapOf("max-cache-time" to plan.itemTimeMs!!, "max-cache-bytes" to plan.itemBytes!!)
                } ?: emptyMap()
                1 -> {
                    if (!plan.fullPreload || cacheSourceReason(read, root) != null) emptyMap()
                    else mapOf("cache_file_forwards_capacity" to plan.forwardBytes!!, "cache_max_capacity" to plan.diskCapacity!!)
                }
                else -> emptyMap()
            }
            return changed.filter { (key, value) -> read(key) != value }
        }

        internal fun cacheSourceReason(read: (String) -> Any?, root: String?): String? {
            if (root.isNullOrBlank()) return "cache-root-unverified"
            val path = read("cache_file_path") as? String ?: return "source-option-unsupported"
            val map = read("cache_map_path") as? String ?: return "source-option-unsupported"
            val capacity = read("cache_max_capacity") as? Long ?: return "source-option-unsupported"
            val close = read("cache_file_close") as? Long ?: return "source-option-unsupported"
            if (capacity <= 0L || close != 0L) return "host-cache-disabled"
            if (!path.startsWith("$root/") || !map.startsWith("$root/") ||
                path.split('/').any { it == ".." } || map.split('/').any { it == ".." }) return "disk-path-unverified"
            return null
        }
    }
}
