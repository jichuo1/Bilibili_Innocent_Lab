package com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostResourceRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostTrimSeverity
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookLog
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.container.VerifiedBilpBundle
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.diagnostics.PluginRuntimeStatus
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.runtime.ContentFilterPipeline
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.settings.PluginSettingsStore
import com.bilab.core.settings.PluginSettingKeySpace
import com.bilab.plugin.api.model.HostAppInfo
import com.bilab.plugin.api.model.HostThemeSnapshot
import com.bilab.plugin.api.model.PluginApiVersion
import com.bilab.plugin.api.model.PluginManifest
import com.bilab.plugin.api.model.PluginPermission
import com.bilab.plugin.api.model.PluginQuarantineReason
import com.bilab.plugin.api.model.PluginTier
import com.bilab.plugin.api.spi.AgentToolExtension
import com.bilab.plugin.api.spi.BilPlugin
import com.bilab.plugin.api.spi.ContentFilterExtension
import com.bilab.plugin.api.spi.DanmakuBatchFilter
import com.bilab.plugin.api.spi.PluginHostContext
import com.bilab.plugin.api.spi.PluginHookRegistrar
import com.bilab.plugin.api.spi.PluginResourceScope
import com.bilab.plugin.api.spi.PluginSession
import com.bilab.plugin.api.spi.ResolvedFieldRef
import com.bilab.plugin.api.spi.ResolvedMemberRef
import com.bilab.plugin.api.spi.WireExtension
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

/**
 * Read-only settings access surface injected into [TierCPluginRuntime] for plugin-namespaced keys.
 */
internal interface PluginSettingsReader {
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun getInt(key: String, defaultValue: Int): Int
    fun getString(key: String, defaultValue: String): String

    companion object {
        val EMPTY: PluginSettingsReader = object : PluginSettingsReader {
            override fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue
            override fun getInt(key: String, defaultValue: Int): Int = defaultValue
            override fun getString(key: String, defaultValue: String): String = defaultValue
        }
    }
}

/**
 * Sanitized lifecycle diagnostic sink invoked on plugin activation, unload, or quarantine.
 */
internal fun interface PluginDiagnosticSink {
    fun record(
        pluginId: String,
        tier: PluginTier,
        status: PluginRuntimeStatus,
        quarantineReason: PluginQuarantineReason?,
        faultCount: Int,
        hookCount: Int
    )
}

/**
 * Host extension bridge connecting active Tier-C plugins to host pipelines and member resolution.
 */
internal interface TierCExtensionSink {
    fun registerContentFilter(pluginId: String, extension: ContentFilterExtension): AutoCloseable =
        AutoCloseable { }

    fun registerDanmakuBatchFilter(pluginId: String, filter: DanmakuBatchFilter): AutoCloseable =
        AutoCloseable { }

    fun registerWireExtension(pluginId: String, extension: WireExtension): AutoCloseable =
        AutoCloseable { }

    fun registerAgentToolExtension(pluginId: String, extension: AgentToolExtension): AutoCloseable =
        AutoCloseable { }

    fun resolveMember(
        pluginId: String,
        queryId: String,
        registrar: SandboxedPluginHookRegistrar
    ): HostResolvedMemberRef? = null

    fun resolveField(
        pluginId: String,
        queryId: String,
        registrar: SandboxedPluginHookRegistrar
    ): HostResolvedFieldRef? = null

    companion object {
        val NO_OP: TierCExtensionSink = object : TierCExtensionSink {}
    }
}

internal class TierCPluginResourceScope(
    val pluginId: String
) : PluginResourceScope {
    private class ScopedHandle(
        private val onClose: () -> Unit
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { onClose() }
        }
    }

    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val resources = LinkedHashSet<ScopedHandle>()
    private val trimCallbacks = LinkedHashMap<ScopedHandle, (Int) -> Unit>()

    private val registryLease: AutoCloseable =
        HostResourceRegistry.register("tier_c:$pluginId") { severity ->
            val level = when (severity) {
                HostTrimSeverity.HIDDEN -> PluginResourceScope.TRIM_HIDDEN
                HostTrimSeverity.MODERATE -> PluginResourceScope.TRIM_MODERATE
                HostTrimSeverity.CRITICAL -> PluginResourceScope.TRIM_CRITICAL
            }
            dispatchTrim(level)
        }

    val isClosed: Boolean
        get() = closed.get()

    override fun register(tag: String, resource: AutoCloseable): AutoCloseable {
        if (closed.get()) {
            runCatching { resource.close() }
            return AutoCloseable { }
        }
        lateinit var handle: ScopedHandle
        handle = ScopedHandle {
            synchronized(lock) { resources.remove(handle) }
            resource.close()
        }
        val accepted = synchronized(lock) {
            if (closed.get()) {
                false
            } else {
                resources.add(handle)
                true
            }
        }
        if (!accepted) {
            runCatching { resource.close() }
            return AutoCloseable { }
        }
        return handle
    }

    override fun registerTrimCallback(tag: String, onTrim: (severityLevel: Int) -> Unit): AutoCloseable {
        if (closed.get()) return AutoCloseable { }
        lateinit var handle: ScopedHandle
        handle = ScopedHandle {
            synchronized(lock) { trimCallbacks.remove(handle) }
        }
        val accepted = synchronized(lock) {
            if (closed.get()) {
                false
            } else {
                trimCallbacks[handle] = onTrim
                true
            }
        }
        if (!accepted) return AutoCloseable { }
        return handle
    }

    fun dispatchTrim(severityLevel: Int): Int {
        if (closed.get()) return 0
        val snapshot = synchronized(lock) { trimCallbacks.values.toList() }
        var count = 0
        for (callback in snapshot) {
            if (closed.get()) break
            runCatching { callback(severityLevel) }
            count++
        }
        return count
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { registryLease.close() }
        val toClose = synchronized(lock) {
            trimCallbacks.clear()
            resources.toList().also { resources.clear() }
        }
        for (handle in toClose.asReversed()) {
            handle.close()
        }
    }
}

/**
 * Capability-gated and namespace-isolated [PluginHostContext] exposed to Tier-C plugins.
 */
internal class PluginHostContextImpl(
    val manifest: PluginManifest,
    override val appInfo: HostAppInfo,
    override val themeSnapshot: HostThemeSnapshot,
    override val resourceScope: PluginResourceScope,
    private val sandboxedHookRegistrar: SandboxedPluginHookRegistrar?,
    private val settingsReader: PluginSettingsReader,
    private val extensionSink: TierCExtensionSink,
    private val filterPipeline: ContentFilterPipeline?,
    private val agentTools: SandboxedPluginAgentTools,
    private val onPluginFault: (Throwable) -> Unit,
    private val logSink: (isError: Boolean, pluginId: String, tag: String, message: String) -> Unit
) : PluginHostContext {

    private val pluginId: String = manifest.id
    private val rejectedSettingsReads = AtomicInteger(0)

    override val hookRegistrar: PluginHookRegistrar?
        get() = if (isPermissionGranted(PluginPermission.L2_ADAPT_AND_HOOK)) sandboxedHookRegistrar else null

    val rejectedSettingReadCount: Int
        get() = rejectedSettingsReads.get()

    fun resolveSettingsReadAllowed(key: String): Boolean =
        PluginSettingKeySpace.belongsToPlugin(key, pluginId)

    override fun isPermissionGranted(permission: PluginPermission): Boolean =
        manifest.permissions.any { it.includes(permission) }

    override fun resolveMember(queryId: String): ResolvedMemberRef? {
        if (!isPermissionGranted(PluginPermission.L2_ADAPT_AND_HOOK)) return null
        val registrar = sandboxedHookRegistrar ?: return null
        return extensionSink.resolveMember(pluginId, queryId, registrar)
    }

    override fun resolveField(queryId: String): ResolvedFieldRef? {
        if (!isPermissionGranted(PluginPermission.L2_ADAPT_AND_HOOK)) return null
        val registrar = sandboxedHookRegistrar ?: return null
        return extensionSink.resolveField(pluginId, queryId, registrar)
    }

    override fun readSettingBool(key: String, defaultValue: Boolean): Boolean {
        if (!resolveSettingsReadAllowed(key)) {
            rejectedSettingsReads.incrementAndGet()
            return defaultValue
        }
        return settingsReader.getBoolean(key, defaultValue)
    }

    override fun readSettingInt(key: String, defaultValue: Int): Int {
        if (!resolveSettingsReadAllowed(key)) {
            rejectedSettingsReads.incrementAndGet()
            return defaultValue
        }
        return settingsReader.getInt(key, defaultValue)
    }

    override fun readSettingString(key: String, defaultValue: String): String {
        if (!resolveSettingsReadAllowed(key)) {
            rejectedSettingsReads.incrementAndGet()
            return defaultValue
        }
        return settingsReader.getString(key, defaultValue)
    }

    override fun registerContentFilter(extension: ContentFilterExtension): AutoCloseable {
        requirePermission(PluginPermission.L1_CONTENT_FILTER, "registerContentFilter")
        val pipelineHandle = filterPipeline?.registerContentFilter(pluginId, extension) { err ->
            onPluginFault(err)
        }
        val sinkHandle = try {
            extensionSink.registerContentFilter(pluginId, extension)
        } catch (throwable: Throwable) {
            runCatching { pipelineHandle?.close() }
            throw throwable
        }
        val combined = AutoCloseable {
            runCatching { sinkHandle.close() }
            runCatching { pipelineHandle?.close() }
        }
        return resourceScope.register("content_filter:$pluginId", combined)
    }

    override fun registerDanmakuBatchFilter(filter: DanmakuBatchFilter): AutoCloseable {
        requirePermission(PluginPermission.L1_CONTENT_FILTER, "registerDanmakuBatchFilter")
        val pipelineHandle = filterPipeline?.registerDanmakuBatchFilter(pluginId, filter) { err ->
            onPluginFault(err)
        }
        val sinkHandle = try {
            extensionSink.registerDanmakuBatchFilter(pluginId, filter)
        } catch (throwable: Throwable) {
            runCatching { pipelineHandle?.close() }
            throw throwable
        }
        val combined = AutoCloseable {
            runCatching { sinkHandle.close() }
            runCatching { pipelineHandle?.close() }
        }
        return resourceScope.register("danmaku_filter:$pluginId", combined)
    }

    override fun registerWireExtension(extension: WireExtension): AutoCloseable {
        requirePermission(PluginPermission.L2_ADAPT_AND_HOOK, "registerWireExtension")
        val handle = extensionSink.registerWireExtension(pluginId, extension)
        return resourceScope.register("wire_extension:$pluginId", handle)
    }

    override fun registerAgentToolExtension(extension: AgentToolExtension): AutoCloseable {
        requirePermission(PluginPermission.L1_CONTENT_FILTER, "registerAgentToolExtension")
        val registryHandle = agentTools.register(pluginId, extension)
        val sinkHandle = try {
            extensionSink.registerAgentToolExtension(pluginId, extension)
        } catch (throwable: Throwable) {
            runCatching { registryHandle.close() }
            throw throwable
        }
        val combined = AutoCloseable {
            runCatching { sinkHandle.close() }
            runCatching { registryHandle.close() }
        }
        return resourceScope.register("agent_tool:$pluginId", combined)
    }

    override fun logInfo(tag: String, message: String) {
        runCatching { logSink(false, pluginId, tag, message) }
    }

    override fun logError(tag: String, message: String) {
        runCatching { logSink(true, pluginId, tag, message) }
    }

    private fun requirePermission(required: PluginPermission, action: String) {
        if (!isPermissionGranted(required)) {
            throw SecurityException(
                "Plugin '$pluginId' lacks permission ${required.name} for $action"
            )
        }
    }
}

/**
 * Active handle for a loaded Tier-C signed-code plugin.
 *
 * Keeps only [WeakReference] links to ClassLoaders so unloading allows full GC reclamation
 * even if callers retain a reference to [LoadedTierCPlugin].
 */
internal class LoadedTierCPlugin internal constructor(
    val pluginId: String,
    val manifest: PluginManifest,
    val hostContext: PluginHostContextImpl,
    val hookRegistrar: SandboxedPluginHookRegistrar?,
    val resourceScope: TierCPluginResourceScope,
    pluginClassLoader: ClassLoader,
    bridgeClassLoader: PluginApiBridgeClassLoader,
    private val onClose: () -> Unit
) : AutoCloseable {
    private val classLoaderRef = WeakReference(pluginClassLoader)
    private val bridgeClassLoaderRef = WeakReference(bridgeClassLoader)
    private val active = AtomicBoolean(true)

    val classLoader: ClassLoader?
        get() = classLoaderRef.get()

    val bridgeClassLoader: PluginApiBridgeClassLoader?
        get() = bridgeClassLoaderRef.get()

    val isActive: Boolean
        get() = active.get() && !resourceScope.isClosed

    val activeHookCount: Int
        get() = hookRegistrar?.activeHookCount ?: 0

    val rejectedSettingReadCount: Int
        get() = hostContext.rejectedSettingReadCount

    internal fun markInactive() {
        active.set(false)
    }

    override fun close() {
        if (!active.compareAndSet(true, false)) return
        runCatching { onClose() }
    }
}

/**
 * Lifecycle manager for Tier-C [PluginTier.SIGNED_CODE] plugins.
 *
 * Coordinates [PluginBootLedger] crash-loop detection, [PluginApiBridgeClassLoader] isolation,
 * [SandboxedPluginHookRegistrar] identity/error isolation, [PluginFaultCircuitBreaker] tripping,
 * [SandboxedPluginAgentToolRegistry], and [HostResourceRegistry] lease cleanup.
 */
internal class TierCPluginRuntime @JvmOverloads constructor(
    val bootLedger: PluginBootLedger = PluginBootLedger(),
    val circuitBreaker: PluginFaultCircuitBreaker = PluginFaultCircuitBreaker(),
    private val filterPipeline: ContentFilterPipeline? = null,
    private val settingsStore: PluginSettingsStore? = null,
    val agentTools: SandboxedPluginAgentTools = SandboxedPluginAgentTools(),
    private val defaultExtensionSink: TierCExtensionSink = TierCExtensionSink.NO_OP,
    private val hostPackagePrefix: String? = null,
    private val classLoaderFactory: (
        manifest: PluginManifest,
        packageBytes: ByteArray,
        bridgeParent: PluginApiBridgeClassLoader
    ) -> ClassLoader = ::defaultTierCClassLoader,
    private val logSink: (isError: Boolean, pluginId: String, tag: String, message: String) -> Unit =
        ::defaultLogSink
) {
    private class ActiveRecord(
        val pluginId: String,
        val manifest: PluginManifest,
        var plugin: BilPlugin?,
        var session: PluginSession?,
        var pluginClassLoader: ClassLoader?,
        var bridgeClassLoader: PluginApiBridgeClassLoader?,
        var hookRegistrar: SandboxedPluginHookRegistrar?,
        val resourceScope: TierCPluginResourceScope,
        var diagnosticsSink: PluginDiagnosticSink?,
        var breakerListenerHandle: AutoCloseable?,
        val handle: LoadedTierCPlugin
    ) {
        val unloaded = AtomicBoolean(false)

        fun clearStrongRefs() {
            plugin = null
            session = null
            pluginClassLoader = null
            bridgeClassLoader = null
            hookRegistrar = null
            diagnosticsSink = null
            breakerListenerHandle = null
        }
    }

    private val lifecycleLock = Any()
    private val activeRecords = ConcurrentHashMap<String, ActiveRecord>()

    fun activate(
        bundle: VerifiedBilpBundle,
        packageBytes: ByteArray,
        hostAppInfo: HostAppInfo,
        themeSnapshot: HostThemeSnapshot,
        hostClassLoader: ClassLoader?,
        hookBridge: PluginHookBridge = PluginHookBridge.NO_OP,
        settingsReader: PluginSettingsReader? = null,
        diagnosticsSink: PluginDiagnosticSink? = null,
        extensionSink: TierCExtensionSink = defaultExtensionSink
    ): LoadedTierCPlugin? = activateManifest(
        manifest = bundle.manifest,
        packageBytes = packageBytes,
        hostAppInfo = hostAppInfo,
        themeSnapshot = themeSnapshot,
        hostClassLoader = hostClassLoader,
        hookBridge = hookBridge,
        settingsReader = settingsReader,
        diagnosticsSink = diagnosticsSink,
        extensionSink = extensionSink,
        hasNativeLibs = bundle.verifiedNativeLibs.isNotEmpty()
    )

    fun activateManifest(
        manifest: PluginManifest,
        packageBytes: ByteArray = ByteArray(0),
        hostAppInfo: HostAppInfo = DEFAULT_HOST_APP_INFO,
        themeSnapshot: HostThemeSnapshot = DEFAULT_THEME_SNAPSHOT,
        hostClassLoader: ClassLoader? = TierCPluginRuntime::class.java.classLoader,
        hookBridge: PluginHookBridge = PluginHookBridge.NO_OP,
        settingsReader: PluginSettingsReader? = null,
        diagnosticsSink: PluginDiagnosticSink? = null,
        extensionSink: TierCExtensionSink = defaultExtensionSink,
        hasNativeLibs: Boolean = false
    ): LoadedTierCPlugin? {
        val pluginId = manifest.id
        if (manifest.tier != PluginTier.SIGNED_CODE || manifest.validate().isNotEmpty()) {
            reportDiagnostic(
                diagnosticsSink,
                pluginId,
                manifest.tier,
                PluginRuntimeStatus.QUARANTINED,
                PluginQuarantineReason.INVALID_PACKAGE,
                faultCount = 0,
                hookCount = 0
            )
            return null
        }
        if (PluginApiVersion.CURRENT_API_LEVEL !in manifest.minApiLevel..manifest.maxApiLevel) {
            reportDiagnostic(
                diagnosticsSink,
                pluginId,
                manifest.tier,
                PluginRuntimeStatus.QUARANTINED,
                PluginQuarantineReason.API_LEVEL_INCOMPATIBLE,
                faultCount = 0,
                hookCount = 0
            )
            return null
        }
        if (!manifest.isCompatibleWith(hostAppInfo.versionCode, PluginApiVersion.CURRENT_API_LEVEL)) {
            reportDiagnostic(
                diagnosticsSink,
                pluginId,
                manifest.tier,
                PluginRuntimeStatus.QUARANTINED,
                PluginQuarantineReason.HOST_VERSION_INCOMPATIBLE,
                faultCount = 0,
                hookCount = 0
            )
            return null
        }
        if (hasNativeLibs && manifest.permissions.none { it.includes(PluginPermission.L3_NATIVE_OR_PRIVILEGED) }) {
            reportDiagnostic(
                diagnosticsSink,
                pluginId,
                manifest.tier,
                PluginRuntimeStatus.QUARANTINED,
                PluginQuarantineReason.PERMISSION_DENIED,
                faultCount = 0,
                hookCount = 0
            )
            return null
        }

        val entryClassName = manifest.entryClass?.trim().orEmpty()
        if (entryClassName.isEmpty()) {
            reportDiagnostic(
                diagnosticsSink,
                pluginId,
                manifest.tier,
                PluginRuntimeStatus.QUARANTINED,
                PluginQuarantineReason.INVALID_PACKAGE,
                faultCount = 0,
                hookCount = 0
            )
            return null
        }

        if (circuitBreaker.isTripped(pluginId)) {
            bootLedger.quarantine(pluginId, PluginQuarantineReason.CALLBACK_FAULT_STORM)
            reportDiagnostic(
                diagnosticsSink,
                pluginId,
                manifest.tier,
                PluginRuntimeStatus.QUARANTINED,
                PluginQuarantineReason.CALLBACK_FAULT_STORM,
                faultCount = circuitBreaker.faultCount(pluginId),
                hookCount = 0
            )
            return null
        }

        if (bootLedger.isQuarantined(pluginId)) {
            reportDiagnostic(
                diagnosticsSink,
                pluginId,
                manifest.tier,
                PluginRuntimeStatus.QUARANTINED,
                bootLedger.quarantineReason(pluginId),
                faultCount = circuitBreaker.faultCount(pluginId),
                hookCount = 0
            )
            return null
        }

        if (activeRecords.containsKey(pluginId)) {
            unload(pluginId)
        }

        if (!bootLedger.recordAttempt(pluginId, manifest.versionCode)) {
            reportDiagnostic(
                diagnosticsSink,
                pluginId,
                manifest.tier,
                PluginRuntimeStatus.QUARANTINED,
                bootLedger.quarantineReason(pluginId) ?: PluginQuarantineReason.BOOT_CRASH_LOOP,
                faultCount = circuitBreaker.faultCount(pluginId),
                hookCount = 0
            )
            return null
        }

        val apiParent = hostClassLoader ?: TierCPluginRuntime::class.java.classLoader
            ?: ClassLoader.getSystemClassLoader()
        val bridgeParent = PluginApiBridgeClassLoader(
            hostApiParent = apiParent,
            hostPackagePrefix = hostPackagePrefix
        )
        val resourceScope = TierCPluginResourceScope(pluginId)
        val hookAllowed = manifest.permissions.any { it.includes(PluginPermission.L2_ADAPT_AND_HOOK) }
        val hookRegistrar = if (hookAllowed) {
            SandboxedPluginHookRegistrar(
                pluginId = pluginId,
                hookBridge = hookBridge,
                circuitBreaker = circuitBreaker,
                onTrip = {
                    handleCircuitBreakerTrip(pluginId)
                }
            )
        } else {
            null
        }
        val effectiveSettingsReader = settingsReader ?: settingsStore?.let { store ->
            object : PluginSettingsReader {
                override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
                    store.getBoolean(pluginId, key, defaultValue)

                override fun getInt(key: String, defaultValue: Int): Int =
                    store.getInt(pluginId, key, defaultValue)

                override fun getString(key: String, defaultValue: String): String =
                    store.getString(pluginId, key, defaultValue)
            }
        } ?: PluginSettingsReader.EMPTY
        val hostContext = PluginHostContextImpl(
            manifest = manifest,
            appInfo = hostAppInfo,
            themeSnapshot = themeSnapshot,
            resourceScope = resourceScope,
            sandboxedHookRegistrar = hookRegistrar,
            settingsReader = effectiveSettingsReader,
            extensionSink = extensionSink,
            filterPipeline = filterPipeline,
            agentTools = agentTools,
            onPluginFault = {
                if (circuitBreaker.recordFault(pluginId)) {
                    handleCircuitBreakerTrip(pluginId)
                }
            },
            logSink = logSink
        )

        var pluginClassLoader: ClassLoader? = null
        var pluginInstance: BilPlugin? = null
        var session: PluginSession? = null
        var breakerListener: AutoCloseable? = null

        return try {
            val loader = classLoaderFactory(manifest, packageBytes, bridgeParent)
            require(
                loader !== hostClassLoader &&
                    loader !== TierCPluginRuntime::class.java.classLoader
            ) {
                "Tier-C plugin loader must be isolated from host ClassLoader"
            }
            pluginClassLoader = loader
            val entryCls = loader.loadClass(entryClassName)
            val ctor = entryCls.getDeclaredConstructor()
            ctor.isAccessible = true
            val instance = ctor.newInstance() as? BilPlugin
                ?: throw IllegalStateException("Entry class '$entryClassName' does not implement BilPlugin")
            require(instance.manifest.id == pluginId) {
                "Loaded plugin manifest id '${instance.manifest.id}' does not match '$pluginId'"
            }
            pluginInstance = instance
            val loadedSession = instance.onLoad(hostContext)
            require(loadedSession.pluginId == pluginId) {
                "PluginSession id '${loadedSession.pluginId}' does not match '$pluginId'"
            }
            session = loadedSession

            val loadedHandle = LoadedTierCPlugin(
                pluginId = pluginId,
                manifest = manifest,
                hostContext = hostContext,
                hookRegistrar = hookRegistrar,
                resourceScope = resourceScope,
                pluginClassLoader = loader,
                bridgeClassLoader = bridgeParent,
                onClose = { unload(pluginId) }
            )

            breakerListener = circuitBreaker.addOnTripListener { trippedPluginId ->
                if (trippedPluginId == pluginId) {
                    handleCircuitBreakerTrip(pluginId)
                }
            }

            val record = ActiveRecord(
                pluginId = pluginId,
                manifest = manifest,
                plugin = instance,
                session = loadedSession,
                pluginClassLoader = loader,
                bridgeClassLoader = bridgeParent,
                hookRegistrar = hookRegistrar,
                resourceScope = resourceScope,
                diagnosticsSink = diagnosticsSink,
                breakerListenerHandle = breakerListener,
                handle = loadedHandle
            )
            synchronized(lifecycleLock) {
                activeRecords[pluginId] = record
            }

            bootLedger.markSucceeded(pluginId, manifest.versionCode)

            if (circuitBreaker.isTripped(pluginId)) {
                handleCircuitBreakerTrip(pluginId)
                return null
            }

            reportDiagnostic(
                diagnosticsSink,
                pluginId,
                manifest.tier,
                PluginRuntimeStatus.ACTIVE,
                quarantineReason = null,
                faultCount = circuitBreaker.faultCount(pluginId),
                hookCount = hookRegistrar?.activeHookCount ?: 0
            )
            loadedHandle
        } catch (throwable: Throwable) {
            runCatching { breakerListener?.close() }
            if (session != null) {
                runCatching { pluginInstance?.onUnload(session) }
                runCatching { session.close() }
            }
            runCatching { hookRegistrar?.close() }
            runCatching { resourceScope.close() }
            runCatching { agentTools.unregisterPlugin(pluginId) }
            runCatching { filterPipeline?.unregisterPlugin(pluginId) }
            pluginClassLoader = null
            pluginInstance = null
            session = null

            bootLedger.markFailed(pluginId, manifest.versionCode)
            val quarantine = bootLedger.quarantineReason(pluginId)
            val status = if (quarantine != null) PluginRuntimeStatus.QUARANTINED else PluginRuntimeStatus.UNLOADED
            reportDiagnostic(
                diagnosticsSink,
                pluginId,
                manifest.tier,
                status,
                quarantineReason = quarantine,
                faultCount = circuitBreaker.faultCount(pluginId),
                hookCount = 0
            )
            runCatching {
                logSink(true, pluginId, "TierCPluginRuntime", "Activation failed: ${throwable::class.java.simpleName}")
            }
            null
        }
    }

    fun unload(pluginId: String, quarantineReason: PluginQuarantineReason? = null): Boolean {
        val record = synchronized(lifecycleLock) {
            activeRecords.remove(pluginId)
        } ?: return false
        if (!record.unloaded.compareAndSet(false, true)) return false

        val sink = record.diagnosticsSink
        val tier = record.manifest.tier
        val faults = circuitBreaker.faultCount(pluginId)

        record.handle.markInactive()
        runCatching { record.breakerListenerHandle?.close() }
        runCatching { record.hookRegistrar?.close() }
        val activeSession = record.session
        val activePlugin = record.plugin
        if (activeSession != null) {
            runCatching { activePlugin?.onUnload(activeSession) }
            runCatching { activeSession.close() }
        }
        runCatching { record.resourceScope.close() }
        runCatching { agentTools.unregisterPlugin(pluginId) }
        runCatching { filterPipeline?.unregisterPlugin(pluginId) }
        record.clearStrongRefs()

        val status = if (quarantineReason != null) PluginRuntimeStatus.QUARANTINED else PluginRuntimeStatus.UNLOADED
        reportDiagnostic(
            sink,
            pluginId,
            tier,
            status,
            quarantineReason = quarantineReason,
            faultCount = faults,
            hookCount = 0
        )
        return true
    }

    fun unloadAll() {
        val ids = synchronized(lifecycleLock) { activeRecords.keys.toList() }
        for (id in ids) {
            unload(id)
        }
    }

    fun activeSessions(): List<LoadedTierCPlugin> =
        Collections.unmodifiableList(activeRecords.values.map { it.handle }.filter { it.isActive })

    fun sessionFor(pluginId: String): LoadedTierCPlugin? =
        activeRecords[pluginId]?.handle?.takeIf { it.isActive }

    fun isActive(pluginId: String): Boolean =
        sessionFor(pluginId) != null

    fun activeHookCount(pluginId: String): Int =
        activeRecords[pluginId]?.hookRegistrar?.activeHookCount ?: 0

    private fun handleCircuitBreakerTrip(pluginId: String) {
        bootLedger.quarantine(pluginId, PluginQuarantineReason.CALLBACK_FAULT_STORM)
        unload(pluginId, quarantineReason = PluginQuarantineReason.CALLBACK_FAULT_STORM)
    }

    private fun reportDiagnostic(
        sink: PluginDiagnosticSink?,
        pluginId: String,
        tier: PluginTier,
        status: PluginRuntimeStatus,
        quarantineReason: PluginQuarantineReason?,
        faultCount: Int,
        hookCount: Int
    ) {
        if (sink == null) return
        runCatching {
            sink.record(pluginId, tier, status, quarantineReason, faultCount, hookCount)
        }
    }

    companion object {
        private const val MAX_DEX_BYTES = 16 * 1024 * 1024

        private val DEFAULT_HOST_APP_INFO = HostAppInfo(
            packageName = "tv.danmaku.bili",
            versionCode = 1L,
            versionName = "1.0.0",
            processName = "tv.danmaku.bili",
            abi = "arm64-v8a"
        )

        private val DEFAULT_THEME_SNAPSHOT = HostThemeSnapshot(
            isDark = false,
            surfaceColor = 0xFFFFFFFF.toInt(),
            accentColor = 0xFFFB7299.toInt(),
            textPrimaryColor = 0xFF18191C.toInt(),
            textSecondaryColor = 0xFF61666D.toInt(),
            density = 1.0f
        )

        private fun defaultLogSink(isError: Boolean, pluginId: String, tag: String, message: String) {
            val formatted = "[BIL:Plugin:$pluginId][$tag] $message"
            if (isError) ModernHookLog.error(formatted) else ModernHookLog.info(formatted)
        }

        internal fun defaultTierCClassLoader(
            manifest: PluginManifest,
            packageBytes: ByteArray,
            bridgeParent: PluginApiBridgeClassLoader
        ): ClassLoader {
            require(packageBytes.isNotEmpty()) {
                "Empty package bytes for Tier-C plugin '${manifest.id}'"
            }
            val dexBuffers = extractDexBuffers(packageBytes)
            require(dexBuffers.isNotEmpty()) {
                "No classes*.dex entries found in Tier-C plugin '${manifest.id}'"
            }
            return instantiateInMemoryDexClassLoader(dexBuffers, bridgeParent)
        }

        private fun extractDexBuffers(packageBytes: ByteArray): Array<ByteBuffer> {
            val buffers = ArrayList<ByteBuffer>()
            val scratch = ByteArray(16 * 1024)
            var totalBytes = 0
            ZipInputStream(ByteArrayInputStream(packageBytes)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name
                    if (!entry.isDirectory && isRootDexEntry(name)) {
                        val out = ByteArrayOutputStream()
                        while (true) {
                            val read = zis.read(scratch)
                            if (read < 0) break
                            if (read == 0) continue
                            totalBytes += read
                            require(totalBytes <= MAX_DEX_BYTES) {
                                "Tier-C dex payload exceeds $MAX_DEX_BYTES bytes"
                            }
                            out.write(scratch, 0, read)
                        }
                        buffers += ByteBuffer.wrap(out.toByteArray())
                    }
                    zis.closeEntry()
                }
            }
            return buffers.toTypedArray()
        }

        private fun isRootDexEntry(name: String): Boolean {
            if (name.contains('/') || name.contains('\\')) return false
            return name == "classes.dex" ||
                (name.startsWith("classes") && name.endsWith(".dex") && name.length > "classes.dex".length)
        }

        private fun instantiateInMemoryDexClassLoader(
            dexBuffers: Array<ByteBuffer>,
            parent: ClassLoader
        ): ClassLoader {
            val cls = Class.forName("dalvik.system.InMemoryDexClassLoader")
            if (dexBuffers.size == 1) {
                val singleCtor = runCatching {
                    cls.getConstructor(ByteBuffer::class.java, ClassLoader::class.java)
                }.getOrNull()
                if (singleCtor != null) {
                    return singleCtor.newInstance(dexBuffers[0], parent) as ClassLoader
                }
            }
            val arrayCtor = cls.getConstructor(Array<ByteBuffer>::class.java, ClassLoader::class.java)
            return arrayCtor.newInstance(dexBuffers, parent) as ClassLoader
        }
    }
}
