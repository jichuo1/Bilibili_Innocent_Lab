package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.diagnostics.PluginRuntimeDiagnostics
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.diagnostics.PluginRuntimeDiagnosticsSnapshot
import io.github.jichuo1.meguviz.android.extensions.versionCodeCompat
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** 一次 oneway 请求 + 一次带 nonce 的回调；超时后由调用方使用旧广播，永不拿缓存当在线响应。 */
internal object HostReceiptClient {
    private val worker = HostReceiptWire.executor("bil-receipt-query")
    private val pluginValidationWorker = HostReceiptWire.executor("bil-plugin-diag-validate")

    fun queryPluginDiagnostics(
        context: Context,
        allowWake: Boolean = false,
        callback: (PluginRuntimeDiagnosticsSnapshot?) -> Unit
    ) {
        val app = context.applicationContext ?: context
        val main = Handler(Looper.getMainLooper())
        fun deliver(snapshot: PluginRuntimeDiagnosticsSnapshot?, failure: ReceiptQueryFailure) {
            if (failure != ReceiptQueryFailure.NONE) {
                ReceiptQueryLog.failure("plugin_diagnostics", failure)
            }
            main.post { callback(snapshot) }
        }
        ReceiptQueryTransport.queryPluginDiagnostics(app, allowWake = allowWake) { reply ->
            val extras = reply.extras
            if (reply.failure != ReceiptQueryFailure.NONE || extras == null) {
                val failure = if (reply.failure != ReceiptQueryFailure.NONE) {
                    reply.failure
                } else {
                    ReceiptQueryFailure.MALFORMED_RESPONSE
                }
                deliver(null, failure)
                return@queryPluginDiagnostics
            }
            runCatching {
                pluginValidationWorker.execute {
                    val expectedSource = currentTargetSource(app)
                    val snapshot = if (expectedSource == null) {
                        null
                    } else {
                        runCatching {
                            PluginRuntimeDiagnostics.receiveBundle(extras, reply.nonce, expectedSource)
                        }.getOrNull()
                    }
                    val failure = when {
                        expectedSource == null -> ReceiptQueryFailure.SOURCE_MISMATCH
                        snapshot == null -> ReceiptQueryFailure.MALFORMED_RESPONSE
                        else -> ReceiptQueryFailure.NONE
                    }
                    deliver(snapshot, failure)
                }
            }.onFailure {
                deliver(null, ReceiptQueryFailure.SEND_FAILED)
            }
        }
    }

    private fun currentTargetSource(context: Context): HostRuntimeDiagnosticsSource? = runCatching {
        val info = context.packageManager.getPackageInfo(
            HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE, 0
        )
        HostRuntimeDiagnosticsSource(
            targetVersionCode = info.versionCodeCompat,
            targetUpdateTime = info.lastUpdateTime,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong()
        ).takeIf { it.isComplete }
    }.getOrNull()

    fun query(channel: String, timeoutMs: Long = HostReceiptWire.TIMEOUT_MS, callback: (Bundle?, String) -> Unit) {
        val session = HostReceiptRegistry.current()
        val nonce = UUID.randomUUID().toString()
        if (session == null) { callback(null, nonce); return }
        val main = Handler(Looper.getMainLooper())
        val completed = AtomicBoolean(false)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        fun finish(value: Bundle?) {
            if (!completed.compareAndSet(false, true)) return
            main.post { callback(value?.takeIf { HostReceiptRegistry.current() == session }, nonce) }
        }
        val timeout = Runnable {
            if (!completed.get()) {
                ReceiptQueryLog.failure("binder", ReceiptQueryFailure.TIMEOUT)
                finish(null)
            }
        }
        main.postDelayed(timeout, timeoutMs)
        val response = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != HostReceiptWire.RESPONSE) return super.onTransact(code, data, reply, flags)
                if (completed.get() || SystemClock.elapsedRealtime() > deadline ||
                    !HostReceiptRegistry.isCurrent(session, getCallingUid())) return false
                return runCatching {
                    if (data.dataSize() > HostReceiptWire.MAX_PARCEL_BYTES) return@runCatching false
                    data.enforceInterface(HostReceiptWire.DESCRIPTOR)
                    if (data.readString() != nonce) return@runCatching false
                    val value = data.readBundle(Bundle::class.java.classLoader) ?: return@runCatching false
                    main.removeCallbacks(timeout)
                    finish(value)
                    true
                }.getOrDefault(false)
            }
        }
        runCatching {
            worker.execute {
                val data = Parcel.obtain()
                try {
                    if (completed.get()) return@execute
                    data.writeInterfaceToken(HostReceiptWire.DESCRIPTOR)
                    data.writeString(channel)
                    data.writeString(nonce)
                    data.writeStrongBinder(response)
                    if (!session.endpoint.transact(HostReceiptWire.QUERY, data, null, IBinder.FLAG_ONEWAY)) {
                        main.removeCallbacks(timeout)
                        finish(null)
                    }
                } catch (_: Exception) {
                    ReceiptQueryLog.failure("binder", ReceiptQueryFailure.SEND_FAILED)
                    main.removeCallbacks(timeout)
                    finish(null)
                } finally { data.recycle() }
            }
        }.onFailure {
            main.removeCallbacks(timeout)
            finish(null)
        }
    }
}
