package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CompatibilityReceiptPublisher
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.diagnostics.PluginRuntimeDiagnostics
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.diagnostics.PluginRuntimeDiagnosticsCodec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Provider 主动发布与反向 Binder 查询；所有 IPC 都在独立有界线程，不占用扫描/持久化线程。 */
internal object HostReceiptHost {
    private const val RESUME_COOLDOWN_MS = 30_000L

    private data class EnvelopePayload(val payload: String, val source: HostRuntimeDiagnosticsSource)

    private class Publication(
        val payload: String,
        val source: HostRuntimeDiagnosticsSource,
        val packet: SharedReceiptPacket<Bundle>
    )

    private class RefreshPass(
        val register: Publication,
        val snapshots: Map<String, Publication>
    )

    private val started = SystemClock.elapsedRealtime().coerceAtLeast(1L)
    private val receiptSequence = ReceiptEnvelopeSequencer()
    private val initialized = AtomicBoolean(false)
    private val refreshThrottle = RefreshThrottle()
    private val diagnosticsScheduled = AtomicBoolean(false)
    private val latest = ConcurrentHashMap<String, Publication>()
    private val worker = HostReceiptWire.executor("bil-receipt-publish")
    private val prepareBound = HostReceiptWire.executor("bil-bound-prepare")
    private val boundDirty = ConcurrentHashMap<String, Publication>()
    private val boundPreparing = AtomicBoolean(false)
    private val queries = HostReceiptWire.executor("bil-receipt-response")
    private val publications = LatestValuePublisher<String, Publication>(
        { worker.execute(it) }, { channel, value -> send(channel, value) })
    @Volatile private var context: Context? = null
    @Volatile private var moduleUid = -1
    @Volatile private var source: HostRuntimeDiagnosticsSource? = null

    fun setModuleUid(uid: Int) {
        if (uid >= android.os.Process.FIRST_APPLICATION_UID && uid != android.os.Process.myUid()) {
            moduleUid = uid
        }
    }

    private val endpoint = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != HostReceiptWire.QUERY) return super.onTransact(code, data, reply, flags)
            if (moduleUid < 0 || getCallingUid() != moduleUid || data.dataSize() > 4096) return false
            return runCatching {
                data.enforceInterface(HostReceiptWire.DESCRIPTOR)
                val channel = data.readString().orEmpty()
                val nonce = data.readString().orEmpty()
                val response = data.readStrongBinder() ?: return@runCatching false
                val nonCore = channel != HostReceiptWire.DIAGNOSTICS && channel !in MineComponentSnapshotCodec.ALLOWED_SURFACES
                if (nonCore && channel != HostReceiptWire.PLUGIN_DIAGNOSTICS)
                    return@runCatching false
                if (!HostRuntimeDiagnosticsQueryContract.isValidNonce(nonce)) return@runCatching false
                queries.execute {
                    HostThreadGuard.run("receipt.response") {
                        val value = when (channel) {
                            HostReceiptWire.DIAGNOSTICS -> HostRuntimeDiagnosticsBridge.response(nonce)
                            HostReceiptWire.PLUGIN_DIAGNOSTICS -> PluginRuntimeDiagnostics.response(nonce)
                            else -> MineComponentSnapshotHostBridge.response(channel, nonce)
                        }
                        if (value == null) return@run
                        val outgoing = Parcel.obtain()
                        try {
                            outgoing.writeInterfaceToken(HostReceiptWire.DESCRIPTOR)
                            outgoing.writeString(nonce)
                            outgoing.writeBundle(value)
                            if (outgoing.dataSize() > HostReceiptWire.MAX_PARCEL_BYTES) return@run
                            response.transact(HostReceiptWire.RESPONSE, outgoing, null, IBinder.FLAG_ONEWAY)
                        } finally { outgoing.recycle() }
                    }
                }
                true
            }.getOrDefault(false)
        }
    }

    fun initialize(application: Context, processSource: HostRuntimeDiagnosticsSource) {
        context = application.applicationContext ?: application
        source = processSource
        PluginRuntimeDiagnostics.updateHostSource(processSource)
        // attach.before 的 applicationContext 可能尚未建立，不能提前消耗监听的一次性标记。
        val app = context as? Application
        if (app != null && initialized.compareAndSet(false, true)) {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) { refresh(force = false) }
                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            })
        }
        refresh(force = true)
    }

    fun publish(channel: String, payload: String, processSource: HostRuntimeDiagnosticsSource) {
        val nonCore = channel != HostReceiptWire.DIAGNOSTICS && channel !in MineComponentSnapshotCodec.ALLOWED_SURFACES
        if (nonCore && channel != HostReceiptWire.PLUGIN_DIAGNOSTICS) return
        if (channel == HostReceiptWire.PLUGIN_DIAGNOSTICS &&
            (payload.length > PluginRuntimeDiagnosticsCodec.MAX_PAYLOAD_CHARS ||
                payload.toByteArray(Charsets.UTF_8).size + 4096 > HostReceiptWire.MAX_PARCEL_BYTES)
        ) return
        if (channel == HostReceiptWire.PLUGIN_DIAGNOSTICS) {
            PluginRuntimeDiagnostics.updateHostSource(processSource)
        }
        val value = createPublication(channel, payload, processSource)
        latest[channel] = value
        scheduleBound(channel, value)
        if (!publications.submit(channel, value)) {
            refreshThrottle.markDirty()
            ReceiptQueryLog.failure("publish_queue", ReceiptQueryFailure.SEND_FAILED)
        }
    }

    fun publishPluginDiagnostics() {
        if (PluginRuntimeDiagnostics.isEmpty()) return
        val currentSource = source ?: return
        val payload = runCatching { PluginRuntimeDiagnostics.encodeSnapshot() }.getOrNull() ?: return
        publish(HostReceiptWire.PLUGIN_DIAGNOSTICS, payload, currentSource)
    }

    /** 安装链完成时立即发首份诊断，不等待磁盘的 30 秒合并窗口。 */
    fun diagnosticsReady() {
        val currentSource = source ?: return
        val value = createPublication(HostReceiptWire.DIAGNOSTICS, "", currentSource)
        scheduleBound(HostReceiptWire.DIAGNOSTICS, value)
        if (!diagnosticsScheduled.compareAndSet(false, true)) return
        runCatching { worker.execute {
            try {
                HostThreadGuard.run("receipt.ready") {
                    if (!send(HostReceiptWire.DIAGNOSTICS, value)) send(HostReceiptWire.DIAGNOSTICS, value)
                }
            } finally { diagnosticsScheduled.set(false) }
        } }.onFailure { diagnosticsScheduled.set(false) }
    }

    /** 模块进程被回收后，在宿主下次前台事件重建会话；带脏标记与最小冷却节流，无轮询、无常驻服务。 */
    private fun refresh(force: Boolean = false) {
        val initialSource = source ?: return
        if (!refreshThrottle.tryAcquire(force)) return
        val initialPass = prepareRefreshPass(source ?: initialSource)
        runCatching { worker.execute {
            var pendingPass: RefreshPass? = initialPass
            while (pendingPass != null) {
                val pass = pendingPass
                var succeeded = false
                try {
                    HostThreadGuard.run("receipt.refresh") {
                        succeeded = executeRefreshPass(pass)
                    }
                } finally {
                    pendingPass = if (refreshThrottle.complete(succeeded)) {
                        val activeSource = source
                        if (activeSource != null) {
                            prepareRefreshPass(activeSource)
                        } else {
                            refreshThrottle.cancelLaunch()
                            null
                        }
                    } else {
                        null
                    }
                }
            }
        } }.onFailure { refreshThrottle.cancelLaunch() }
    }

    private fun prepareRefreshPass(currentSource: HostRuntimeDiagnosticsSource): RefreshPass {
        val registerPub = createPublication(HostReceiptWire.REGISTER, "", currentSource)
        val diagPub = createPublication(HostReceiptWire.DIAGNOSTICS, "", currentSource)
        latest[HostReceiptWire.DIAGNOSTICS] = diagPub
        val snapshots = linkedMapOf<String, Publication>().apply {
            putAll(latest)
            this[HostReceiptWire.DIAGNOSTICS] = diagPub
        }
        scheduleBound(HostReceiptWire.REGISTER, registerPub)
        snapshots.forEach(::scheduleBound)
        return RefreshPass(registerPub, snapshots)
    }

    private fun executeRefreshPass(pass: RefreshPass): Boolean {
        if (!send(HostReceiptWire.REGISTER, pass.register)) return false
        var allAccepted = true
        pass.snapshots.forEach { (channel, snapshot) ->
            val current = latest[channel] ?: snapshot
            if (!send(channel, current)) allAccepted = false
        }
        return allAccepted
    }

    private fun createPublication(
        channel: String,
        payload: String,
        processSource: HostRuntimeDiagnosticsSource
    ): Publication = Publication(
        payload = payload,
        source = processSource,
        packet = SharedReceiptPacket(
            builder = { envelope(channel, EnvelopePayload(payload, processSource)) },
            copier = ::Bundle
        )
    )

    private fun envelope(channel: String, value: EnvelopePayload): Bundle? = runCatching {
        val app = context ?: return@runCatching null
        // PackageManager 可能发生 IPC，必须位于本地排序点之外；受 HMA/包可见性限制失败时只保留 moduleUid = -1。
        if (moduleUid < 0) {
            val resolvedUid = runCatching {
                app.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid
            }.getOrNull()
            if (resolvedUid != null) setModuleUid(resolvedUid)
        }
        val stamped = receiptSequence.capture {
            // 快照与编号一同固定，旧载荷不能在另一通道发送新值后领取较大的编号。
            when (channel) {
                HostReceiptWire.DIAGNOSTICS ->
                    value.copy(payload = HostRuntimeDiagnosticsCodec.encode(HostRuntimeDiagnosticsBridge.snapshot()))
                HostReceiptWire.PLUGIN_DIAGNOSTICS ->
                    value.copy(payload = value.payload.ifEmpty { PluginRuntimeDiagnostics.encodeSnapshot() })
                HostReceiptWire.REGISTER -> value
                else -> latest[channel]?.let { EnvelopePayload(it.payload, it.source) } ?: value
            }
        }
        val outgoing = stamped.value
        if (channel == HostReceiptWire.PLUGIN_DIAGNOSTICS &&
            (outgoing.payload.length > PluginRuntimeDiagnosticsCodec.MAX_PAYLOAD_CHARS ||
                outgoing.payload.toByteArray(Charsets.UTF_8).size + 4096 > HostReceiptWire.MAX_PARCEL_BYTES)
        ) {
            return@runCatching null
        }
        Bundle().apply {
            putInt("version", HostReceiptWire.VERSION)
            putLong("started", started)
            putLong("sequence", stamped.sequence)
            putBinder("endpoint", endpoint)
            com.Bilibili_Innocent_Lab.xposedmodule.agent.host.HostAgentRuntime.endpoint()?.let {
                putBinder(com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentWire.ENDPOINT_KEY, it)
            }
            putString("channel", channel)
            putString("payload", outgoing.payload)
            putString("digest", HostRuntimeDiagnosticsQueryContract.sha256(outgoing.payload))
            putLong("target_version", outgoing.source.targetVersionCode)
            putLong("target_update", outgoing.source.targetUpdateTime)
            putLong("module_version", outgoing.source.moduleVersionCode)
        }
    }.getOrNull()

    private fun scheduleBound(channel: String, value: Publication) {
        boundDirty[channel] = value
        drainBound()
    }

    private fun drainBound() {
        if (!boundPreparing.compareAndSet(false, true)) return
        runCatching { prepareBound.execute {
            try {
                while (true) {
                    val entry = boundDirty.entries.firstOrNull() ?: break
                    if (!boundDirty.remove(entry.key, entry.value)) continue
                    if (entry.value.packet.isPrimaryDelivered()) continue
                    val app = context ?: continue
                    entry.value.packet.getOrBuild()?.let { CompatibilityReceiptPublisher.publish(app, it) }
                }
            } finally {
                boundPreparing.set(false)
                if (boundDirty.isNotEmpty()) drainBound()
            }
        } }.onFailure { boundPreparing.set(false) }
    }

    @Suppress("DEPRECATION")
    private fun send(channel: String, value: Publication): Boolean = runCatching {
        val app = context ?: return@runCatching false
        val extras = value.packet.getOrBuild() ?: return@runCatching false
        if (channel == HostReceiptWire.PLUGIN_DIAGNOSTICS) {
            val payloadBytes = extras.getString("payload")?.toByteArray(Charsets.UTF_8)?.size ?: 0
            if (payloadBytes + 4096 > HostReceiptWire.MAX_PARCEL_BYTES) return@runCatching false
        }
        val accepted = runCatching {
            app.contentResolver.acquireUnstableContentProviderClient("${BuildConfig.APPLICATION_ID}.roaming")?.use {
                it.call(HostReceiptWire.METHOD, null, extras)?.getBoolean("accepted") == true
            } == true
        }.getOrDefault(false)
        if (accepted) {
            value.packet.markPrimaryDelivered()
            CompatibilityReceiptPublisher.acknowledge(channel, extras.getLong("sequence"))
        }
        accepted
    }.getOrDefault(false).also {
        if (!it) {
            refreshThrottle.markDirty()
            ReceiptQueryLog.failure("provider", ReceiptQueryFailure.SEND_FAILED)
        }
    }
}

/** 宿主前台切屏刷新节流：带脏标记、并发在途合并与最小冷却窗口，避免频繁转场重复建包/IPC。 */
internal class RefreshThrottle(
    private val cooldownMs: Long = 30_000L,
    private val clock: () -> Long = SystemClock::elapsedRealtime
) {
    private var hasAttempted = false
    private var lastAttemptAtMs = 0L
    private var dirty = false
    private var pendingForce = false
    private var inFlight = false

    @Synchronized
    fun markDirty() {
        dirty = true
    }

    @Synchronized
    fun tryAcquire(force: Boolean): Boolean {
        dirty = true
        if (force) pendingForce = true
        if (inFlight) return false
        val now = clock()
        if (!pendingForce && hasAttempted && (now - lastAttemptAtMs) < cooldownMs) {
            return false
        }
        dirty = false
        pendingForce = false
        inFlight = true
        hasAttempted = true
        lastAttemptAtMs = now
        return true
    }

    @Synchronized
    fun flushPendingIfReady(): Boolean {
        if (inFlight || (!dirty && !pendingForce)) return false
        val now = clock()
        if (!pendingForce && hasAttempted && (now - lastAttemptAtMs) < cooldownMs) {
            return false
        }
        dirty = false
        pendingForce = false
        inFlight = true
        hasAttempted = true
        lastAttemptAtMs = now
        return true
    }

    @Synchronized
    fun complete(succeeded: Boolean = true): Boolean {
        inFlight = false
        if (!succeeded) dirty = true
        if (!dirty && !pendingForce) return false
        val now = clock()
        if (!pendingForce && hasAttempted && (now - lastAttemptAtMs) < cooldownMs) {
            return false
        }
        dirty = false
        pendingForce = false
        inFlight = true
        hasAttempted = true
        lastAttemptAtMs = now
        return true
    }

    @Synchronized
    fun cancelLaunch() {
        inFlight = false
        dirty = true
    }

    @Synchronized
    fun isDirty(): Boolean = dirty
}

/** 双通道同次发送共享单次序列化与编号结果，并为独立消费者提供隔离副本。 */
internal class SharedReceiptPacket<T : Any>(
    private val builder: () -> T?,
    private val copier: (T) -> T = { it }
) {
    private val lock = Any()
    @Volatile private var cached: T? = null
    @Volatile private var completedOnPrimary = false

    fun getOrBuild(): T? {
        cached?.let { return copier(it) }
        synchronized(lock) {
            cached?.let { return copier(it) }
            val built = builder() ?: return null
            cached = built
            return copier(built)
        }
    }

    fun markPrimaryDelivered() {
        completedOnPrimary = true
    }

    fun isPrimaryDelivered(): Boolean = completedOnPrimary
}
