package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig

/** 模块进程内的唯一宿主会话。磁盘内容从不用于建立会话或证明宿主在线。 */
internal object HostReceiptRegistry {
    private val lock = Any()
    private val gate = ReceiptSessionGate<IBinder>()
    private var death: IBinder.DeathRecipient? = null
    private val sequences = hashMapOf<String, Long>()
    @Volatile private var hostUid = -1

    fun current() = gate.current()
    // oneway Binder 不提供 callingPid；身份靠内核 UID、当前 endpoint 和仅发给它的随机 nonce。
    fun isCurrent(session: ReceiptSessionGate.Session<IBinder>, uid: Int): Boolean =
        uid == hostUid && gate.matches(session)

    @Suppress("DEPRECATION")
    fun receive(context: Context, extras: Bundle?): Bundle {
        var failure = ReceiptQueryFailure.SOURCE_MISMATCH
        val accepted = runCatching {
            val uid = Binder.getCallingUid()
            val pid = Binder.getCallingPid()
            // 写回执只接受该 Android 用户中真实的宿主 uid，不复用 Provider 的 shell/root 读权限。
            val info = context.packageManager.getPackageInfo(HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE, 0)
            if (info.applicationInfo?.uid != uid || extras == null) return@runCatching false
            if (extras.getInt("version") != HostReceiptWire.VERSION) {
                failure = ReceiptQueryFailure.UNSUPPORTED_PROTOCOL
                return@runCatching false
            }
            val source = HostRuntimeDiagnosticsSource(
                extras.getLong("target_version"), extras.getLong("target_update"), extras.getLong("module_version"))
            val currentSource = HostRuntimeDiagnosticsSource(
                if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
                info.lastUpdateTime, BuildConfig.VERSION_CODE.toLong())
            if (!source.isComplete || source != currentSource) return@runCatching false
            failure = ReceiptQueryFailure.MALFORMED_RESPONSE
            val started = extras.getLong("started")
            if (started <= 0 || started > SystemClock.elapsedRealtime()) return@runCatching false
            val endpoint = extras.getBinder("endpoint") ?: return@runCatching false
            val channel = extras.getString("channel").orEmpty()
            val payload = extras.getString("payload").orEmpty()
            if (channel != HostReceiptWire.REGISTER) {
                val reason = HostReceiptPayload.validate(channel, payload, extras.getString("digest").orEmpty(), source, currentSource)
                if (reason != ReceiptQueryFailure.NONE) {
                    failure = reason
                    return@runCatching false
                }
            }
            synchronized(lock) {
                failure = ReceiptQueryFailure.SESSION_MISMATCH
                val old = gate.current()
                val candidate = ReceiptSessionGate.Session(started, pid, endpoint)
                if (candidate != old) {
                    val recipient = IBinder.DeathRecipient {
                        synchronized(lock) {
                            gate.remove(candidate)
                            if (gate.current() == null) death = null
                        }
                    }
                    endpoint.linkToDeath(recipient, 0)
                    if (!gate.accept(started, pid, endpoint)) {
                        endpoint.unlinkToDeath(recipient, 0)
                        return@synchronized false
                    }
                    if (old != null) death?.let { runCatching { old.endpoint.unlinkToDeath(it, 0) } }
                    death = recipient
                    hostUid = uid
                    sequences.clear()
                    if (!endpoint.isBinderAlive) {
                        gate.remove(candidate)
                        return@synchronized false
                    }
                }
                // 与会话替换串行，旧进程迟到的发布不能覆盖新会话的缓存。
                if (channel == HostReceiptWire.REGISTER) return@synchronized true
                val sequence = extras.getLong("sequence")
                if (!ReceiptSessionGate.isNewerSequence(sequences[channel] ?: 0L, sequence)) return@synchronized false
                failure = ReceiptQueryFailure.STORE_FAILED
                val stored = if (channel == HostReceiptWire.DIAGNOSTICS) {
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
                } else {
                    MineComponentSnapshotStore.cache(context, payload, MineComponentSnapshotSource(
                        source.targetVersionCode, source.targetUpdateTime, source.moduleVersionCode))
                }
                if (stored) sequences[channel] = sequence
                stored
            }
        }.getOrDefault(false)
        if (!accepted) ReceiptQueryLog.failure("push", failure)
        return Bundle().apply { putBoolean("accepted", accepted) }
    }
}
