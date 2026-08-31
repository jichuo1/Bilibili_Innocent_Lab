package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

internal sealed interface RemoteHookConfigPublishResult {
    val succeeded: Boolean

    data class Success(
        val generation: Long,
        val changed: Boolean
    ) : RemoteHookConfigPublishResult {
        override val succeeded: Boolean = true
    }

    data class Failure(
        val reason: String,
        val throwable: Throwable? = null
    ) : RemoteHookConfigPublishResult {
        override val succeeded: Boolean = false
    }
}

internal data class ModernFrameworkStatus(
    val connected: Boolean,
    val capable: Boolean,
    val name: String,
    val apiVersion: Int
)

internal fun interface ModernFrameworkStatusListener {
    fun onFrameworkStatusChanged(status: ModernFrameworkStatus)
}

/**
 * 模块进程中的 API 102 Remote Preferences 发布器和服务状态单点。
 *
 * 私有默认设置仍是权威源。服务绑定、设置变更和条款决定只会在单线程发布器上合并，宿主
 * 读取的是 LSPosed 数据库中的完整不可变快照，不再接触模块私有目录。
 */
internal object RemoteHookConfigStore {
    private const val TAG = "BilibiliInnocentLab"
    private val lock = Any()
    private val observedKeys = RemoteHookConfigContract.hookValueKeys
    private val publishScheduled = AtomicBoolean(false)
    private val publishDirty = AtomicBoolean(false)
    private val listenerRegistered = AtomicBoolean(false)
    private val statusListeners = CopyOnWriteArraySet<ModernFrameworkStatusListener>()
    private val publishExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bil-remote-config").apply { isDaemon = true }
    }

    private var applicationContext: Context? = null
    private var observedPreferences: SharedPreferences? = null
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var service: XposedService? = null
    @Volatile private var requestedDecision = UserTermsDecision.UNDECIDED
    @Volatile private var frameworkStatus = ModernFrameworkStatus(
        connected = false,
        capable = false,
        name = "",
        apiVersion = 0
    )

    fun initialize(context: Context, decision: UserTermsDecision): RemoteHookConfigPublishResult {
        val appContext = context.applicationContext ?: context
        applicationContext = appContext
        requestedDecision = decision
        registerServiceListener()
        synchronized(lock) {
            if (observedPreferences == null) {
                val source = appContext.modulePreferences()
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == null || key !in observedKeys) return@OnSharedPreferenceChangeListener
                    requestPublish(appContext)
                }
                source.registerOnSharedPreferenceChangeListener(listener)
                observedPreferences = source
                preferenceListener = listener
            }
        }
        return publish(appContext, decision)
    }

    fun publish(
        context: Context,
        decision: UserTermsDecision
    ): RemoteHookConfigPublishResult = synchronized(lock) {
        val appContext = context.applicationContext ?: context
        applicationContext = appContext
        requestedDecision = decision
        val activeService = service ?: return@synchronized RemoteHookConfigPublishResult.Failure(
            "Xposed service is not connected"
        )
        if (!frameworkStatus.capable) {
            return@synchronized RemoteHookConfigPublishResult.Failure(
                "Xposed framework does not provide API 102 remote preferences"
            )
        }
        runCatching {
            val values = RemoteHookConfigContract.resolveSourceValues(
                appContext.modulePreferences().all
            )
            val preferences = activeService.getRemotePreferences(RemoteHookConfigContract.GROUP)
            val current = RemoteHookConfigContract.decode(preferences.all)
            if (current is RemoteHookConfigDecodeResult.Ready &&
                current.snapshot.decision == decision &&
                current.snapshot.values == values
            ) {
                return@synchronized RemoteHookConfigPublishResult.Success(
                    generation = current.snapshot.generation,
                    changed = false
                )
            }
            val previousGeneration = (preferences.all[RemoteHookConfigContract.KEY_GENERATION]
                as? Long)?.coerceAtLeast(0L) ?: 0L
            val generation = max(
                System.currentTimeMillis().coerceAtLeast(1L),
                previousGeneration.nextGeneration()
            )
            val encoded = RemoteHookConfigContract.encode(generation, decision, values)
            val editor = preferences.edit().clear()
            encoded.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is String -> editor.putString(key, value)
                    else -> error("Unsupported remote preference value")
                }
            }
            check(editor.commit()) { "remote preferences commit returned false" }
            val readBack = RemoteHookConfigContract.decode(preferences.all)
            check(readBack is RemoteHookConfigDecodeResult.Ready) {
                "remote read-back failed: " +
                    (readBack as RemoteHookConfigDecodeResult.Invalid).reason
            }
            check(readBack.snapshot.generation == generation) {
                "remote generation read-back mismatch"
            }
            check(readBack.snapshot.decision == decision) {
                "remote decision read-back mismatch"
            }
            check(readBack.snapshot.values == values) { "remote value read-back mismatch" }
            RemoteHookConfigPublishResult.Success(generation, changed = true)
        }.getOrElse { throwable ->
            RemoteHookConfigPublishResult.Failure(
                throwable.message ?: throwable.javaClass.simpleName,
                throwable
            )
        }
    }

    fun status(): ModernFrameworkStatus = frameworkStatus

    /**
     * 框架服务由 LSPosed 异步投递；订阅时立即回送当前快照，消除 Activity 首次绘制与
     * Binder 到达之间的竞态。监听器必须由调用方按生命周期移除。
     */
    fun addStatusListener(listener: ModernFrameworkStatusListener) {
        statusListeners.add(listener)
        notifyStatusListener(listener, frameworkStatus)
    }

    fun removeStatusListener(listener: ModernFrameworkStatusListener) {
        statusListeners.remove(listener)
    }

    fun logFailure(result: RemoteHookConfigPublishResult) {
        if (result !is RemoteHookConfigPublishResult.Failure) return
        Log.w(TAG, "publish remote hook config failed: ${result.reason}", result.throwable)
    }

    private fun registerServiceListener() {
        if (!listenerRegistered.compareAndSet(false, true)) return
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(boundService: XposedService) {
                val capable = boundService.apiVersion >= XposedService.API_102 &&
                    boundService.frameworkProperties and XposedService.PROP_CAP_REMOTE != 0L
                val newStatus = ModernFrameworkStatus(
                    connected = true,
                    capable = capable,
                    name = boundService.frameworkName,
                    apiVersion = boundService.apiVersion
                )
                val changed = synchronized(lock) {
                    if (!capable && service != null) {
                        false
                    } else {
                        service = boundService
                        if (frameworkStatus == newStatus) {
                            false
                        } else {
                            frameworkStatus = newStatus
                            true
                        }
                    }
                }
                if (changed) notifyStatusListeners(newStatus)
                applicationContext?.let(::requestPublish)
            }

            override fun onServiceDied(deadService: XposedService) {
                val disconnected = ModernFrameworkStatus(
                    connected = false,
                    capable = false,
                    name = "",
                    apiVersion = 0
                )
                val changed = synchronized(lock) {
                    if (service !== deadService) {
                        false
                    } else {
                        service = null
                        if (frameworkStatus == disconnected) {
                            false
                        } else {
                            frameworkStatus = disconnected
                            true
                        }
                    }
                }
                if (changed) notifyStatusListeners(disconnected)
            }
        })
    }

    private fun notifyStatusListeners(status: ModernFrameworkStatus) {
        statusListeners.forEach { listener -> notifyStatusListener(listener, status) }
    }

    private fun notifyStatusListener(
        listener: ModernFrameworkStatusListener,
        status: ModernFrameworkStatus
    ) {
        runCatching { listener.onFrameworkStatusChanged(status) }
            .onFailure { throwable ->
                Log.w(TAG, "framework status listener failed", throwable)
            }
    }

    private fun requestPublish(context: Context) {
        publishDirty.set(true)
        if (!publishScheduled.compareAndSet(false, true)) return
        publishExecutor.execute {
            try {
                do {
                    publishDirty.set(false)
                    logFailure(publish(context, requestedDecision))
                } while (publishDirty.get())
            } finally {
                publishScheduled.set(false)
                if (publishDirty.get()) requestPublish(context)
            }
        }
    }

    private fun Long.nextGeneration(): Long = when {
        this < 1L -> 1L
        this == Long.MAX_VALUE -> Long.MAX_VALUE
        else -> this + 1L
    }
}
