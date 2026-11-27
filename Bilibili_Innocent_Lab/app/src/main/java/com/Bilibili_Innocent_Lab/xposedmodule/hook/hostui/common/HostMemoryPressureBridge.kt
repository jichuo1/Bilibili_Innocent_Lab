package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
import com.Bilibili_Innocent_Lab.xposedmodule.hook.CommentEmojiAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.BrandSplashImageCache
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundController
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundRasterCache
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundScene
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostPageChrome
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostCardMaterialScope
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardStyle
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ModuleNativeLoader
import com.lumen.coacervation.engine.LumenEngine
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import io.github.jichuo1.meguviz.android.runtime.platform.MemoryBudgetObserver
import io.github.jichuo1.meguviz.memory.android.MemoryPressureAdapter
import java.lang.ref.WeakReference
import java.util.LinkedHashSet
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 宿主侧可回收资源统一裁剪强度分级。
 */
internal enum class HostTrimSeverity {
    HIDDEN,
    MODERATE,
    CRITICAL
}

/**
 * 宿主进程侧可回收资源与缓存租约注册表。
 *
 * 登记后返回幂等 [AutoCloseable] 租约句柄；当宿主触发 UI 隐藏、中度内存压力或紧急内存压力时，
 * 统一通过 [HostMemoryPressureBridge] 按分级裁剪全部活跃登记项。
 */
internal object HostResourceRegistry {
    private class LeaseEntry(
        val tag: String,
        val onTrim: (HostTrimSeverity) -> Unit
    ) {
        val closed = AtomicBoolean(false)
    }

    private val lock = Any()
    private val entries = LinkedHashSet<LeaseEntry>()

    /**
     * 登记分级 trim 回调并返回自动注销的 [AutoCloseable] 租约句柄。
     */
    fun register(
        tag: String = "host_resource",
        onTrim: (HostTrimSeverity) -> Unit
    ): AutoCloseable {
        val entry = LeaseEntry(tag, onTrim)
        synchronized(lock) {
            entries.add(entry)
        }
        return AutoCloseable {
            if (!entry.closed.compareAndSet(false, true)) return@AutoCloseable
            synchronized(lock) {
                entries.remove(entry)
            }
        }
    }

    /**
     * 按严重级别分别登记裁剪回调；未单独指定 [onCritical] 时回退至 [onModerate]。
     */
    fun register(
        tag: String = "host_resource",
        onHidden: (() -> Unit)? = null,
        onModerate: (() -> Unit)? = null,
        onCritical: (() -> Unit)? = null
    ): AutoCloseable = register(tag) { severity ->
        when (severity) {
            HostTrimSeverity.HIDDEN -> onHidden?.invoke()
            HostTrimSeverity.MODERATE -> onModerate?.invoke()
            HostTrimSeverity.CRITICAL -> (onCritical ?: onModerate)?.invoke()
        }
    }

    internal fun dispatch(severity: HostTrimSeverity): Int {
        val snapshot = synchronized(lock) {
            entries.filterNot { it.closed.get() }
        }
        var invoked = 0
        for (entry in snapshot) {
            if (entry.closed.get()) continue
            runCatching { entry.onTrim(severity) }
            invoked++
        }
        return invoked
    }

    internal fun activeCount(): Int = synchronized(lock) {
        entries.count { !it.closed.get() }
    }

    internal fun clearForTests() {
        val snapshot = synchronized(lock) {
            entries.toList().also { entries.clear() }
        }
        snapshot.forEach { it.closed.set(true) }
    }
}

/**
 * 宿主进程内的内存压力、API 37.2 预算超限与 API 29+ 热节流桥接器。
 *
 * 所有系统回调只读、低开销并经 [HostThreadGuard] 包裹，绝不向模块进程或磁盘发起 IPC/I/O。
 */
internal object HostMemoryPressureBridge {
    internal const val LEVEL_NONE = 0
    internal const val LEVEL_MODERATE = 1
    internal const val LEVEL_CRITICAL = 2

    private const val THERMAL_MODERATE = 2
    private const val THERMAL_SEVERE = 3
    private const val THERMAL_CRITICAL = 4
    private const val API_37_2 = 3_700_002
    private const val SKIP_NATIVE_LOAD_PROP = "io.github.jichuo1.meguviz.memory.skipNativeLoad"

    private val installLock = Any()
    private val callbackLock = Any()
    private val bindingsLock = Any()

    @Volatile
    private var installedApp: WeakReference<Application>? = null

    private var pressureAdapter: AutoCloseable? = null
    private var uiHiddenCallbacks: ComponentCallbacks2? = null
    private var fallbackCallbacks: ComponentCallbacks2? = null
    private var budgetObserver: AutoCloseable? = null
    private var thermalObserver: AutoCloseable? = null

    @Volatile
    private var currentThermalPressure: Float = 0f

    private val moderateCallbacks = ArrayList<() -> Unit>()
    private val criticalCallbacks = ArrayList<() -> Unit>()
    private val bindings = WeakHashMap<LumenSurfaceBinding, Unit>()

    /**
     * 按宿主 [Application] 幂等挂载内存、预算与温控监听。
     */
    fun install(application: Application) {
        synchronized(installLock) {
            if (installedApp?.get() === application) return
            teardownLocked()
            installedApp = WeakReference(application)
            preloadNativeLibraries()
            attachTrimCallbacksLocked(application)
            budgetObserver = attachBudgetObserver(application)
            val thermal = if (Build.VERSION.SDK_INT >= 29) {
                ThermalMonitorApi29.attach(application, this)
            } else {
                null
            }
            if (thermal != null) {
                thermalObserver = thermal.second
                val initialPressure = thermalQualityPressure(thermal.first)
                currentThermalPressure = initialPressure
                if (initialPressure > 0f) applyThermalPressureToBindings(initialPressure)
            } else {
                currentThermalPressure = 0f
            }
        }
    }

    /**
     * 弱跟踪宿主侧活跃的 [LumenSurfaceBinding]，并立即同步当前热压力系数。
     */
    fun registerBinding(binding: LumenSurfaceBinding) {
        synchronized(bindingsLock) {
            bindings[binding] = Unit
        }
        val pressure = currentThermalPressure
        if (pressure > 0f) {
            runOnMain { applyQualityPressure(binding, pressure) }
        }
    }

    /**
     * 供外层入口登记额外的中度/紧急内存回收回调（如音频/插帧/描述符缓存）。
     */
    fun registerTrimCallbacks(onModerate: () -> Unit, onCritical: () -> Unit) {
        synchronized(callbackLock) {
            moderateCallbacks.add(onModerate)
            criticalCallbacks.add(onCritical)
        }
    }

    /**
     * 通过 [HostResourceRegistry] 登记可回收资源回调，并返回自动注销的 [AutoCloseable] 租约句柄。
     */
    fun registerResource(
        tag: String = "host_resource",
        onTrim: (HostTrimSeverity) -> Unit
    ): AutoCloseable = HostResourceRegistry.register(tag, onTrim)

    fun dispatchModerate() {
        dispatchModerateCore()
        HostResourceRegistry.dispatch(HostTrimSeverity.MODERATE)
        trimHostUiMainCaches()
    }

    fun dispatchCritical() {
        dispatchModerateCore()
        val extra = synchronized(callbackLock) { criticalCallbacks.toList() }
        for (callback in extra) {
            runCatching { callback() }
        }
        HostResourceRegistry.dispatch(HostTrimSeverity.CRITICAL)
        runOnMain {
            trimHostUiMainCachesOnMain()
            runCatching { LumenEngine.releaseGraphics() }
            runCatching { HostCardMaterialScope.releaseAllOffscreenRenderNodes() }
        }
    }

    internal fun dispatchHidden() {
        HostResourceRegistry.dispatch(HostTrimSeverity.HIDDEN)
    }

    /**
     * 按与 `MemoryPressureAdapter.classify` 一致的语义归类系统 `onTrimMemory(level)`：
     * - `TRIM_MEMORY_BACKGROUND(40)` -> [LEVEL_MODERATE]
     * - `TRIM_MEMORY_UI_HIDDEN(20)` -> [LEVEL_NONE]（保留历史契约：仅切后台不触发旧 moderate/critical，
     *   但由 [handleTrimMemory] 向 [HostResourceRegistry] 派发 [HostTrimSeverity.HIDDEN]）
     * - `TRIM_MEMORY_RUNNING_CRITICAL(15)` 与 `>= TRIM_MEMORY_COMPLETE(80)` -> [LEVEL_CRITICAL]
     */
    internal fun classifyTrimLevel(level: Int): Int = when {
        level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> LEVEL_CRITICAL
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> LEVEL_MODERATE
        level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> LEVEL_NONE
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> LEVEL_CRITICAL
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> LEVEL_MODERATE
        else -> LEVEL_NONE
    }

    internal fun thermalQualityPressure(status: Int): Float = when {
        status >= THERMAL_CRITICAL -> 1f
        status == THERMAL_SEVERE -> 0.75f
        status == THERMAL_MODERATE -> 0.5f
        else -> 0f
    }

    internal fun handleTrimMemory(level: Int) {
        when (classifyTrimLevel(level)) {
            LEVEL_MODERATE -> dispatchModerate()
            LEVEL_CRITICAL -> dispatchCritical()
            else -> if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                dispatchHidden()
            }
        }
    }

    internal fun onThermalStatus(status: Int) {
        val next = thermalQualityPressure(status)
        if (next == currentThermalPressure) return
        currentThermalPressure = next
        applyThermalPressureToBindings(next)
    }

    internal fun currentThermalPressure(): Float = currentThermalPressure

    internal fun registeredBindingCount(): Int = synchronized(bindingsLock) { bindings.size }

    internal fun resetForTests() {
        synchronized(installLock) { teardownLocked() }
        synchronized(callbackLock) {
            moderateCallbacks.clear()
            criticalCallbacks.clear()
        }
        synchronized(bindingsLock) { bindings.clear() }
        HostResourceRegistry.clearForTests()
        currentThermalPressure = 0f
    }

    private fun preloadNativeLibraries() {
        runCatching {
            if (!java.lang.Boolean.getBoolean(SKIP_NATIVE_LOAD_PROP)) {
                ModuleNativeLoader.load("mgmem")
                ModuleNativeLoader.load("mgmem_pressure_jni")
                System.setProperty(SKIP_NATIVE_LOAD_PROP, "true")
            }
        }
    }

    private fun attachTrimCallbacksLocked(application: Application) {
        val adapter = createPressureAdapter(application)
        if (adapter != null) {
            pressureAdapter = adapter
            val hiddenCallbacks = object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) = HostThreadGuard.run("host.memory_pressure") {
                    if (classifyTrimLevel(level) == LEVEL_NONE &&
                        level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                    ) {
                        dispatchHidden()
                    }
                }

                override fun onLowMemory() = Unit

                override fun onConfigurationChanged(newConfig: Configuration) = Unit
            }
            runCatching { application.registerComponentCallbacks(hiddenCallbacks) }
                .onSuccess { uiHiddenCallbacks = hiddenCallbacks }
            return
        }
        val callbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) = HostThreadGuard.run("host.memory_pressure") {
                handleTrimMemory(level)
            }

            override fun onLowMemory() = HostThreadGuard.run("host.memory_pressure") {
                dispatchCritical()
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        }
        runCatching { application.registerComponentCallbacks(callbacks) }
            .onSuccess { fallbackCallbacks = callbacks }
    }

    private fun createPressureAdapter(application: Application): AutoCloseable? {
        return try {
            val backend = object : MemoryPressureAdapter.Backend {
                override fun notifyPressure(level: Int): Int {
                    HostThreadGuard.run("host.memory_pressure") {
                        when (level) {
                            MemoryPressureAdapter.MODERATE -> dispatchModerate()
                            MemoryPressureAdapter.CRITICAL -> dispatchCritical()
                        }
                    }
                    return 0
                }

                override fun beginClose() = Unit

                override fun awaitClosed(timeoutMs: Long): Boolean = true

                override fun snapshot(): LongArray = LongArray(0)
            }
            MemoryPressureAdapter(application, backend)
        } catch (_: LinkageError) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun attachBudgetObserver(application: Application): AutoCloseable? {
        if (Build.VERSION.SDK_INT < 37) return null
        val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull() ?: return null
        return MemoryBudgetApi37.observe(application, mainLooper) {
            dispatchCritical()
        }
    }

    private fun dispatchModerateCore() {
        val extra = synchronized(callbackLock) { moderateCallbacks.toList() }
        for (callback in extra) {
            runCatching { callback() }
        }
        runCatching { HostBackgroundRasterCache.clearForMemoryPressure() }
        runCatching { HostBackgroundScene.clearForMemoryPressure() }
        runCatching { BrandSplashImageCache.clearAll() }
        runCatching { CommentEmojiAdapter.clearCaches() }
        runCatching { HostVideoCardHostAccess.trimAllCaches() }
    }

    private fun trimHostUiMainCaches() {
        runOnMain { trimHostUiMainCachesOnMain() }
    }

    private fun trimHostUiMainCachesOnMain() {
        runCatching { HostVideoCardStyle.trimAllCaches() }
        runCatching { HostBackgroundController.trimAllCaches() }
        runCatching { HostCardMaterialScope.trimAllCaches() }
        runCatching { HostPageChrome.trimAllCaches() }
    }

    private fun applyThermalPressureToBindings(pressure: Float) {
        val snapshot = synchronized(bindingsLock) { bindings.keys.toList() }
        if (snapshot.isEmpty()) return
        runOnMain {
            for (binding in snapshot) {
                applyQualityPressure(binding, pressure)
            }
        }
    }

    private fun applyQualityPressure(binding: LumenSurfaceBinding, pressure: Float) {
        runCatching {
            if (binding.isBound) binding.setQualityPressure(pressure)
        }
    }

    private inline fun runOnMain(crossinline action: () -> Unit) {
        val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull()
        val currentLooper = runCatching { Looper.myLooper() }.getOrNull()
        if (mainLooper == null || currentLooper === mainLooper) {
            action()
        } else {
            Handler(mainLooper).post(HostThreadGuard.runnable("host.memory_pressure.main") {
                action()
            })
        }
    }

    private fun teardownLocked() {
        runCatching { pressureAdapter?.close() }
        pressureAdapter = null
        val app = installedApp?.get()
        uiHiddenCallbacks?.let { cb ->
            runCatching { app?.unregisterComponentCallbacks(cb) }
        }
        uiHiddenCallbacks = null
        fallbackCallbacks?.let { cb ->
            runCatching { app?.unregisterComponentCallbacks(cb) }
        }
        fallbackCallbacks = null
        val observer = budgetObserver
        budgetObserver = null
        if (observer != null) {
            runOnMain { runCatching { observer.close() } }
        }
        runCatching { thermalObserver?.close() }
        thermalObserver = null
        installedApp = null
    }

    @RequiresApi(37)
    private object MemoryBudgetApi37 {
        @DoNotInline
        fun observe(application: Application, looper: Looper, onOverBudget: () -> Unit): AutoCloseable? {
            if (Build.VERSION.SDK_INT_FULL < API_37_2) return null
            return runCatching {
                MemoryBudgetObserver.observeProcess(application, looper) {
                    HostThreadGuard.run("host.memory_pressure") { onOverBudget() }
                }
            }.getOrNull()
        }
    }

    @RequiresApi(29)
    private object ThermalMonitorApi29 {
        // 注销后 Binder 存根在系统端 GC 前仍可能暂时可达；断开 target 即可避免滞留桥接状态。
        private class Relay(@Volatile var target: HostMemoryPressureBridge?) :
            PowerManager.OnThermalStatusChangedListener {
            override fun onThermalStatusChanged(status: Int) {
                HostThreadGuard.run("host.thermal_pressure") {
                    target?.onThermalStatus(status)
                }
            }
        }

        @DoNotInline
        fun attach(application: Application, bridge: HostMemoryPressureBridge): Pair<Int, AutoCloseable>? {
            val powerManager = runCatching { application.getSystemService(PowerManager::class.java) }.getOrNull()
                ?: return null
            val initial = runCatching { powerManager.currentThermalStatus }
                .getOrDefault(PowerManager.THERMAL_STATUS_NONE)
            val relay = Relay(bridge)
            val registered = runCatching {
                powerManager.addThermalStatusListener(application.mainExecutor, relay)
            }.isSuccess
            if (!registered) {
                relay.target = null
                return null
            }
            val handle = AutoCloseable {
                relay.target = null
                runCatching { powerManager.removeThermalStatusListener(relay) }
            }
            return initial to handle
        }
    }
}
