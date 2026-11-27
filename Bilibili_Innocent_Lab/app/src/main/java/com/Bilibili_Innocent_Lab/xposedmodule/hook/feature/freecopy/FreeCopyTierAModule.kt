package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.freecopy

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlan
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostResourceRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostTrimSeverity
import com.bilab.plugin.api.model.PluginApiVersion
import com.bilab.plugin.api.model.PluginCapabilityDecl
import com.bilab.plugin.api.model.PluginManifest
import com.bilab.plugin.api.model.PluginPermission
import com.bilab.plugin.api.model.PluginTier
import com.bilab.plugin.api.spi.PluginResourceScope
import com.bilab.plugin.api.spi.PluginSession
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tier-A 编译期内部模块化 FreeCopy 所需的宿主依赖与开关配置快照。
 */
internal data class TierAFreeCopyConfig(
    val initialCommentEnabled: Boolean = true,
    val initialDescriptionEnabled: Boolean = true,
    val initialTitleEnabled: Boolean = false,
    val initialLightMode: Boolean = false,
    val initialAutoLight: Boolean = false,
    val commentTopologyEnabled: Boolean = false,
    val adaptPlan: AdaptPlan = AdaptPlan(0L, emptySet(), emptySet()),
    val hostVersionCode: () -> Int,
    val initialAdaptResult: () -> VersionAdapter.AdaptResult? = { null },
    val onComposeMore: (View, Long, () -> Boolean) -> Unit = { _, _, _ -> },
    val onComposeResetMore: () -> Unit = {},
    val lowBindPointProvider: () -> VersionAdapter.HookPoint? = { initialAdaptResult()?.commentLow },
    val highBindPointProvider: () -> VersionAdapter.HookPoint? = { initialAdaptResult()?.commentHigh }
)

/**
 * Tier-A 编译期内部模块统一桥接契约：用稳定 `:plugin-api` 模型声明清单并绑定宿主资源作用域。
 */
internal interface TierAFeatureModule {
    val manifest: PluginManifest

    fun createInstaller(
        deps: TierAFreeCopyConfig,
        resourceScope: PluginResourceScope
    ): FeatureInstaller
}

/**
 * 基于 [HostResourceRegistry] 实现的插件级资源作用域，统一托管租约释放与内存裁剪分级转发。
 */
internal class HostPluginResourceScope(
    val pluginId: String
) : PluginResourceScope {
    private class RegisteredHandle(
        val tag: String,
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
    private val resources = LinkedHashSet<RegisteredHandle>()
    private val trimCallbacks = LinkedHashMap<RegisteredHandle, (Int) -> Unit>()

    private val registryLease: AutoCloseable = HostResourceRegistry.register("plugin_scope:$pluginId") { severity ->
        val level = when (severity) {
            HostTrimSeverity.HIDDEN -> PluginResourceScope.TRIM_HIDDEN
            HostTrimSeverity.MODERATE -> PluginResourceScope.TRIM_MODERATE
            HostTrimSeverity.CRITICAL -> PluginResourceScope.TRIM_CRITICAL
        }
        dispatchTrim(level)
    }

    val isClosed: Boolean
        get() = closed.get()

    val registeredResourceCount: Int
        get() = synchronized(lock) { resources.size }

    val registeredTrimCallbackCount: Int
        get() = synchronized(lock) { trimCallbacks.size }

    override fun register(tag: String, resource: AutoCloseable): AutoCloseable {
        if (closed.get()) {
            runCatching { resource.close() }
            return AutoCloseable { }
        }
        lateinit var handle: RegisteredHandle
        handle = RegisteredHandle(tag) {
            synchronized(lock) {
                resources.remove(handle)
            }
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
        if (closed.get()) {
            return AutoCloseable { }
        }
        lateinit var handle: RegisteredHandle
        handle = RegisteredHandle(tag) {
            synchronized(lock) {
                trimCallbacks.remove(handle)
            }
        }
        val accepted = synchronized(lock) {
            if (closed.get()) {
                false
            } else {
                trimCallbacks[handle] = onTrim
                true
            }
        }
        if (!accepted) {
            return AutoCloseable { }
        }
        return handle
    }

    fun dispatchTrim(severityLevel: Int): Int {
        if (closed.get()) return 0
        val snapshot = synchronized(lock) { trimCallbacks.values.toList() }
        var invoked = 0
        for (callback in snapshot) {
            if (closed.get()) break
            runCatching { callback(severityLevel) }
            invoked++
        }
        return invoked
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
 * Tier-A 内部试跑会话句柄：关闭时幂等释放绑定的 [PluginResourceScope] 与所有宿主租约。
 */
internal class TierAPluginSession(
    override val pluginId: String,
    val installer: FeatureInstaller,
    val resourceScope: PluginResourceScope
) : PluginSession {
    private val active = AtomicBoolean(true)

    override val isActive: Boolean
        get() = active.get()

    override fun close() {
        if (!active.compareAndSet(true, false)) return
        runCatching { resourceScope.close() }
    }
}

/**
 * FreeCopy 功能的 Tier-A 编译期模块化试跑桥接入口。
 *
 * 使用 `DECLARATIVE_BUNDLE` + `L2_ADAPT_AND_HOOK` 且 `entryClass = null` 通过清单校验，
 * 不改动 `HookEntry` 原安装链，只把真实的 [FreeCopyFeatureInstaller] 与 [HostResourceRegistry] 生命周期对齐。
 */
internal object FreeCopyTierAModule : TierAFeatureModule {
    const val PLUGIN_ID = "com.bilab.internal.freecopy"

    override val manifest: PluginManifest = PluginManifest(
        id = PLUGIN_ID,
        versionCode = 1,
        versionName = "1.0.0",
        displayName = "FreeCopy",
        tier = PluginTier.DECLARATIVE_BUNDLE,
        minApiLevel = PluginApiVersion.CURRENT_API_LEVEL,
        maxApiLevel = PluginApiVersion.CURRENT_API_LEVEL,
        minHostVersionCode = 0L,
        maxHostVersionCode = Long.MAX_VALUE,
        permissions = setOf(PluginPermission.L2_ADAPT_AND_HOOK),
        capabilities = listOf(
            PluginCapabilityDecl(
                localId = FreeCopyFeatureInstaller.CAPABILITY_COMMENT,
                displayName = "Comment FreeCopy",
                permission = PluginPermission.L2_ADAPT_AND_HOOK
            ),
            PluginCapabilityDecl(
                localId = FreeCopyFeatureInstaller.CAPABILITY_DESCRIPTION,
                displayName = "Description FreeCopy",
                permission = PluginPermission.L2_ADAPT_AND_HOOK
            ),
            PluginCapabilityDecl(
                localId = FreeCopyFeatureInstaller.CAPABILITY_TITLE,
                displayName = "Title FreeCopy",
                permission = PluginPermission.L2_ADAPT_AND_HOOK
            )
        ),
        entryClass = null
    )

    override fun createInstaller(
        deps: TierAFreeCopyConfig,
        resourceScope: PluginResourceScope
    ): FeatureInstaller {
        val validationErrors = manifest.validate()
        check(validationErrors.isEmpty()) {
            "Invalid FreeCopyTierAModule manifest: ${validationErrors.joinToString("; ")}"
        }
        val installer = FreeCopyFeatureInstaller(
            initialCommentEnabled = deps.initialCommentEnabled,
            initialDescriptionEnabled = deps.initialDescriptionEnabled,
            initialTitleEnabled = deps.initialTitleEnabled,
            initialLightMode = deps.initialLightMode,
            initialAutoLight = deps.initialAutoLight,
            commentTopologyEnabled = deps.commentTopologyEnabled,
            adaptPlan = deps.adaptPlan,
            hostVersionCode = deps.hostVersionCode,
            initialAdaptResult = deps.initialAdaptResult,
            onComposeMore = deps.onComposeMore,
            onComposeResetMore = deps.onComposeResetMore,
            lowBindPointProvider = deps.lowBindPointProvider,
            highBindPointProvider = deps.highBindPointProvider
        )
        val trimHandle = resourceScope.registerTrimCallback("${manifest.id}:caches") { severityLevel ->
            trimCaches(severityLevel)
        }
        val hostLease = HostResourceRegistry.register("tier_a:${manifest.id}") { severity ->
            val level = when (severity) {
                HostTrimSeverity.HIDDEN -> PluginResourceScope.TRIM_HIDDEN
                HostTrimSeverity.MODERATE -> PluginResourceScope.TRIM_MODERATE
                HostTrimSeverity.CRITICAL -> PluginResourceScope.TRIM_CRITICAL
            }
            trimCaches(level)
        }
        resourceScope.register("${manifest.id}:trim_callback", trimHandle)
        resourceScope.register("${manifest.id}:host_registry_lease", hostLease)
        return installer
    }

    fun openSession(
        deps: TierAFreeCopyConfig,
        resourceScope: PluginResourceScope = HostPluginResourceScope(manifest.id)
    ): TierAPluginSession {
        val installer = createInstaller(deps, resourceScope)
        return TierAPluginSession(
            pluginId = manifest.id,
            installer = installer,
            resourceScope = resourceScope
        )
    }

    private fun trimCaches(severityLevel: Int) {
        if (severityLevel >= PluginResourceScope.TRIM_MODERATE) {
            FreeCopyBindScheduler.cHandlerViewFieldByClass.clear()
        }
        if (severityLevel >= PluginResourceScope.TRIM_CRITICAL) {
            FreeCopyRuntimeConfig.freeCopyLightCache = null
        }
    }
}
