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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Provider 主动发布与反向 Binder 查询；所有 IPC 都在独立有界线程，不占用扫描/持久化线程。 */
internal object HostReceiptHost {
    private data class Publication(val payload: String, val source: HostRuntimeDiagnosticsSource)
    private val started = SystemClock.elapsedRealtime().coerceAtLeast(1L)
    private val receiptSequence = ReceiptEnvelopeSequencer()
    private val initialized = AtomicBoolean(false)
    private val refreshing = AtomicBoolean(false)
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

    private val endpoint = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != HostReceiptWire.QUERY) return super.onTransact(code, data, reply, flags)
            if (moduleUid < 0 || getCallingUid() != moduleUid || data.dataSize() > 4096) return false
            return runCatching {
                data.enforceInterface(HostReceiptWire.DESCRIPTOR)
                val channel = data.readString().orEmpty()
                val nonce = data.readString().orEmpty()
                val response = data.readStrongBinder() ?: return@runCatching false
                if (channel != HostReceiptWire.DIAGNOSTICS && channel !in MineComponentSnapshotCodec.ALLOWED_SURFACES)
                    return@runCatching false
                if (!HostRuntimeDiagnosticsQueryContract.isValidNonce(nonce)) return@runCatching false
                queries.execute {
                    HostThreadGuard.run("receipt.response") {
                        val value = if (channel == HostReceiptWire.DIAGNOSTICS)
                            HostRuntimeDiagnosticsBridge.response(nonce)
                        else MineComponentSnapshotHostBridge.response(channel, nonce)
                        if (value == null) return@run
                        val outgoing = Parcel.obtain()
                        try {
                            outgoing.writeInterfaceToken(HostReceiptWire.DESCRIPTOR)
                            outgoing.writeString(nonce)
                            outgoing.writeBundle(value)
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
        // attach.before 的 applicationContext 可能尚未建立，不能提前消耗监听的一次性标记。
        val app = context as? Application
        if (app != null && initialized.compareAndSet(false, true)) {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) { refresh() }
                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            })
        }
        refresh()
    }

    fun publish(channel: String, payload: String, processSource: HostRuntimeDiagnosticsSource) {
        if (channel != HostReceiptWire.DIAGNOSTICS && channel !in MineComponentSnapshotCodec.ALLOWED_SURFACES) return
        val value = Publication(payload, processSource)
        latest[channel] = value
        scheduleBound(channel, value)
        if (!publications.submit(channel, value)) ReceiptQueryLog.failure("publish_queue", ReceiptQueryFailure.SEND_FAILED)
    }

    /** 安装链完成时立即发首份诊断，不等待磁盘的 30 秒合并窗口。 */
    fun diagnosticsReady() {
        source?.let { scheduleBound(HostReceiptWire.DIAGNOSTICS, Publication("", it)) }
        if (!diagnosticsScheduled.compareAndSet(false, true)) return
        runCatching { worker.execute {
            try {
                HostThreadGuard.run("receipt.ready") {
                    val currentSource = source ?: return@run
                    val value = Publication("", currentSource)
                    if (!send(HostReceiptWire.DIAGNOSTICS, value)) send(HostReceiptWire.DIAGNOSTICS, value)
                }
            } finally { diagnosticsScheduled.set(false) }
        } }.onFailure { diagnosticsScheduled.set(false) }
    }

    /** 模块进程被回收后，在宿主下次前台事件重建会话；无轮询、无常驻服务。 */
    private fun refresh() {
        source?.let {
            latest[HostReceiptWire.DIAGNOSTICS] = Publication("", it)
            scheduleBound(HostReceiptWire.REGISTER, Publication("", it))
            latest.forEach(::scheduleBound)
        }
        if (!refreshing.compareAndSet(false, true)) return
        runCatching { worker.execute {
            try {
                HostThreadGuard.run("receipt.refresh") {
                    val currentSource = source ?: return@run
                    latest[HostReceiptWire.DIAGNOSTICS] = Publication("", currentSource)
                    if (!send(HostReceiptWire.REGISTER, Publication("", currentSource))) return@run
                    latest.forEach { (channel, snapshot) -> send(channel, snapshot) }
                }
            } finally { refreshing.set(false) }
        } }.onFailure { refreshing.set(false) }
    }

    private fun envelope(channel: String, value: Publication): Bundle? = runCatching {
        val app = context ?: return@runCatching null
        // PackageManager 可能发生 IPC，必须位于本地排序点之外。
        if (moduleUid < 0) moduleUid = app.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid
        val stamped = receiptSequence.capture {
            // 快照与编号一同固定，旧载荷不能在另一通道发送新值后领取较大的编号。
            if (channel == HostReceiptWire.DIAGNOSTICS)
                value.copy(payload = HostRuntimeDiagnosticsCodec.encode(HostRuntimeDiagnosticsBridge.snapshot()))
            else latest[channel] ?: value
        }
        val outgoing = stamped.value
        Bundle().apply {
            putInt("version", HostReceiptWire.VERSION)
            putLong("started", started)
            putLong("sequence", stamped.sequence)
            putBinder("endpoint", endpoint)
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
                    val app = context ?: continue
                    envelope(entry.key, entry.value)?.let { CompatibilityReceiptPublisher.publish(app, it) }
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
        val extras = envelope(channel, value) ?: return@runCatching false
        val accepted = runCatching {
            app.contentResolver.acquireUnstableContentProviderClient("${BuildConfig.APPLICATION_ID}.roaming")?.use {
                it.call(HostReceiptWire.METHOD, null, extras)?.getBoolean("accepted") == true
            } == true
        }.getOrDefault(false)
        if (accepted) CompatibilityReceiptPublisher.acknowledge(channel, extras.getLong("sequence"))
        accepted
    }.getOrDefault(false).also { if (!it) ReceiptQueryLog.failure("provider", ReceiptQueryFailure.SEND_FAILED) }
}
