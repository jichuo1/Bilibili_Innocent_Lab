package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.diagnostics.PluginRuntimeDiagnostics
import io.github.jichuo1.meguviz.android.extensions.versionCodeCompat

/** 模块进程内的唯一宿主会话。磁盘内容从不用于建立会话或证明宿主在线。 */
internal object HostReceiptRegistry {
    private class PersistTask(
        val appContext: Context,
        val channel: String,
        val payload: String,
        val source: HostRuntimeDiagnosticsSource,
        val sequence: Long
    ) {
        override fun equals(other: Any?): Boolean =
            other is PersistTask &&
                channel == other.channel &&
                payload == other.payload &&
                source == other.source &&
                sequence == other.sequence

        override fun hashCode(): Int {
            var result = channel.hashCode()
            result = 31 * result + payload.hashCode()
            result = 31 * result + source.hashCode()
            result = 31 * result + sequence.hashCode()
            return result
        }
    }

    private val lock = Any()
    private val gate = ReceiptSessionGate<IBinder>()
    private var death: IBinder.DeathRecipient? = null
    private val sequences = hashMapOf<String, Long>()
    private val persistExecutor = HostReceiptWire.executor("bil-receipt-persist")
    private val persistPublisher = LatestValuePublisher<String, PersistTask>(
        schedule = { action -> persistExecutor.execute(action) },
        publish = { _, task -> persistSnapshot(task) }
    )
    @Volatile private var hostUid = -1

    fun current() = gate.current()
    // oneway Binder 不提供 callingPid；身份靠内核 UID、当前 endpoint 和仅发给它的随机 nonce。
    // 兼容跨用户双开/工作资料已登记的宿主 UID，绝不放行同用户内其他 UID。
    fun isCurrent(session: ReceiptSessionGate.Session<IBinder>, uid: Int): Boolean {
        val currentHostUid = hostUid
        if (currentHostUid <= 0 || !gate.matches(session)) return false
        if (uid == currentHostUid) return true
        return HostCallerIdentityVerifier.verifyHostUid(
            callerUid = uid,
            primaryHostUid = currentHostUid,
            auditTag = "HostReceiptRegistry.isCurrent"
        ).isCrossUser
    }

    @Suppress("DEPRECATION")
    fun receive(context: Context, extras: Bundle?): Bundle {
        var failure = ReceiptQueryFailure.SOURCE_MISMATCH
        val accepted = runCatching {
            val uid = Binder.getCallingUid()
            val pid = Binder.getCallingPid()
            // 写回执只接受真实的宿主 UID（或经过审计的跨用户同包名分身 UID），绝不复用 shell/root 读权限。
            val info = context.packageManager.getPackageInfo(HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE, 0)
            val callerIdentity = HostCallerIdentityVerifier.verifyHostUid(
                callerUid = uid,
                primaryHostUid = info.applicationInfo?.uid,
                callerPackages = HostCallerIdentityVerifier.resolvePackagesForUid(context, uid),
                expectedPackage = HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE,
                auditTag = "HostReceiptRegistry"
            )
            if (!callerIdentity.allowed) return@runCatching false
            if (!callerIdentity.isCrossUser && info.applicationInfo?.uid != uid) return@runCatching false
            if (extras == null) {
                failure = ReceiptQueryFailure.MALFORMED_RESPONSE
                return@runCatching false
            }
            if (extras.getInt("version") != HostReceiptWire.VERSION) {
                failure = ReceiptQueryFailure.UNSUPPORTED_PROTOCOL
                return@runCatching false
            }
            val source = HostRuntimeDiagnosticsSource(
                extras.getLong("target_version"), extras.getLong("target_update"), extras.getLong("module_version"))
            val currentSource = HostRuntimeDiagnosticsSource(
                info.versionCodeCompat,
                info.lastUpdateTime, BuildConfig.VERSION_CODE.toLong())
            if (!source.isComplete || source != currentSource) return@runCatching false
            failure = ReceiptQueryFailure.MALFORMED_RESPONSE
            val started = extras.getLong("started")
            if (started <= 0 || started > SystemClock.elapsedRealtime()) return@runCatching false
            val endpoint = extras.getBinder("endpoint") ?: return@runCatching false
            val channel = extras.getString("channel").orEmpty()
            val payload = HostReceiptWire.readPayloadFromBundle(extras) ?: return@runCatching false
            if (channel == HostReceiptWire.PLUGIN_DIAGNOSTICS) {
                val reason = PluginRuntimeDiagnostics.validateEnvelope(
                    payload, extras.getString("digest").orEmpty(), source, currentSource)
                if (reason != ReceiptQueryFailure.NONE) {
                    failure = reason
                    return@runCatching false
                }
            } else if (channel != HostReceiptWire.REGISTER) {
                val reason = HostReceiptPayload.validate(channel, payload, extras.getString("digest").orEmpty(), source, currentSource)
                if (reason != ReceiptQueryFailure.NONE) {
                    failure = reason
                    return@runCatching false
                }
            }
            val appContext = context.applicationContext ?: context
            synchronized(lock) {
                val old = gate.current()
                val candidate = ReceiptSessionGate.Session(started, pid, endpoint)
                if (candidate != old) {
                    val recipient = IBinder.DeathRecipient {
                        synchronized(lock) {
                            gate.remove(candidate)
                            if (gate.current() == null) death = null
                        }
                    }
                    failure = ReceiptQueryFailure.ENDPOINT_DEAD
                    endpoint.linkToDeath(recipient, 0)
                    if (!gate.accept(started, pid, endpoint)) {
                        failure = ReceiptQueryFailure.SESSION_MISMATCH
                        endpoint.unlinkToDeath(recipient, 0)
                        return@synchronized false
                    }
                    if (old != null) death?.let { runCatching { old.endpoint.unlinkToDeath(it, 0) } }
                    death = recipient
                    hostUid = uid
                    sequences.clear()
                }
                if (!endpoint.isBinderAlive) {
                    failure = ReceiptQueryFailure.ENDPOINT_DEAD
                    gate.remove(candidate)
                    return@synchronized false
                }
                // 与会话替换串行，旧进程迟到的发布不能覆盖新会话的缓存。
                failure = ReceiptQueryFailure.MALFORMED_RESPONSE
                com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentHostClient.register(
                    candidate, extras.getBinder(com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentWire.ENDPOINT_KEY))
                if (channel == HostReceiptWire.REGISTER) return@synchronized true
                val sequence = extras.getLong("sequence")
                when (ReceiptSessionGate.sequenceDecision(sequences[channel] ?: 0L, sequence)) {
                    ReceiptSessionGate.SequenceDecision.INVALID -> {
                        failure = ReceiptQueryFailure.INVALID_SEQUENCE
                        return@synchronized false
                    }
                    ReceiptSessionGate.SequenceDecision.SUPERSEDED -> {
                        // Provider 与备用 Binder 可能同时送达；已有较新值即可确认，避免重试同一副本。
                        ReceiptQueryLog.superseded()
                        return@synchronized true
                    }
                    ReceiptSessionGate.SequenceDecision.STORE -> Unit
                }
                if (channel == HostReceiptWire.PLUGIN_DIAGNOSTICS &&
                    !PluginRuntimeDiagnostics.receivePayload(payload, source)
                ) {
                    failure = ReceiptQueryFailure.STORE_FAILED
                    return@synchronized false
                }
                sequences[channel] = sequence
                val enqueued = persistPublisher.submit(
                    channel,
                    PersistTask(appContext, channel, payload, source, sequence)
                )
                if (!enqueued) {
                    ReceiptQueryLog.failure("persist_queue", ReceiptQueryFailure.STORE_FAILED)
                }
                true
            }
        }.getOrDefault(false)
        if (!accepted) ReceiptQueryLog.failure("push", failure)
        return Bundle().apply { putBoolean("accepted", accepted) }
    }

    private fun persistSnapshot(task: PersistTask): Boolean = runCatching {
        val context = task.appContext
        val payload = task.payload
        val source = task.source
        when (task.channel) {
            HostReceiptWire.DIAGNOSTICS -> {
                // 仅留作历史数据；实时诊断必须经过 endpoint 的 nonce 响应。
                val prefs = context.getSharedPreferences("host_receipt_history", Context.MODE_PRIVATE)
                if (prefs.getString("payload", null) == payload &&
                    prefs.getLong("target_version", 0) == source.targetVersionCode &&
                    prefs.getLong("target_update", 0) == source.targetUpdateTime &&
                    prefs.getLong("module_version", 0) == source.moduleVersionCode) true
                else prefs.edit()
                    .putString("payload", payload).putLong("target_version", source.targetVersionCode)
                    .putLong("target_update", source.targetUpdateTime).putLong("module_version", source.moduleVersionCode)
                    .commit()
            }
            HostReceiptWire.PLUGIN_DIAGNOSTICS -> {
                PluginRuntimeDiagnostics.receivePublished(context, payload, source)
            }
            else -> {
                MineComponentSnapshotStore.cache(context, payload, MineComponentSnapshotSource(
                    source.targetVersionCode, source.targetUpdateTime, source.moduleVersionCode))
            }
        }
    }.getOrElse {
        ReceiptQueryLog.failure("persist", ReceiptQueryFailure.STORE_FAILED)
        false
    }

    fun receiveQueryResponse(
        channel: String,
        extras: Bundle?,
        nonce: String,
        currentSource: HostRuntimeDiagnosticsSource
    ): Boolean {
        if (channel != HostReceiptWire.PLUGIN_DIAGNOSTICS || extras == null) return false
        return PluginRuntimeDiagnostics.receiveBundle(extras, nonce, currentSource) != null
    }
}
