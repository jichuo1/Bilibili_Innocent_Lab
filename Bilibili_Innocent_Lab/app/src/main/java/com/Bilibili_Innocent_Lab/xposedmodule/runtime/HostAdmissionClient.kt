package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CompatibilityReceiptService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Bundle
import android.os.SystemClock
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationIdentity
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigDecodeResult
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigSnapshot
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 启动窗口内完成新鲜许可；工作线程只读取，不在迟到回调中安装 Hook。 */
internal object HostAdmissionClient {
    data class Grant(val snapshot: RemoteHookConfigSnapshot, val source: String, val identity: PublicationIdentity)
    data class Result(val grant: Grant? = null, val reason: String = "admission_unavailable")
    private val boundWorker = HostReceiptWire.executor("bil-bound-admission")
    private val worker = HostReceiptWire.executor("bil-host-admission")

    fun admit(context: Context, normal: RemoteHookConfigSnapshot?, normalFailure: String?): Result {
        val app = context.applicationContext ?: context
        val deadline = SystemClock.elapsedRealtime() + HostAdmissionContract.BOOTSTRAP_TIMEOUT_MS
        val responses = LinkedBlockingQueue<Result>(2)
        fun launch(bound: Boolean) = runCatching {
            (if (bound) boundWorker else worker).submit {
                val result = try { exchange(app, normal, normalFailure, deadline, bound) }
                catch (_: SecurityException) { Result(reason = "admission_denied") }
                catch (_: TimeoutException) { Result(reason = "admission_timeout") }
                catch (_: Exception) { Result() }
                responses.offer(result)
            }
        }.getOrNull()
        val calls = listOfNotNull(launch(false), if (Build.VERSION.SDK_INT >= 29) launch(true) else null)
        if (calls.isEmpty()) return Result()
        var failure = Result()
        try {
            repeat(calls.size) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) return Result(reason = "admission_timeout")
                val response = responses.poll(remaining, TimeUnit.MILLISECONDS) ?: return Result(reason = "admission_timeout")
                if (response.grant != null && SystemClock.elapsedRealtime() < deadline) return response
                failure = response
            }
            return failure
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return Result()
        } finally {
            // 仅取消排队及结果所有权；Binder 事务可能仍在运行，但绝不安装迟到 Hook。
            calls.forEach { it.cancel(false) }
        }
    }

    private fun exchange(context: Context, normal: RemoteHookConfigSnapshot?, normalFailure: String?, deadline: Long, bound: Boolean): Result {
        val authority = "${BuildConfig.APPLICATION_ID}.admission"
        val module = context.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0)
        if (!bound) {
            val provider = context.packageManager.resolveContentProvider(authority, 0) ?: return Result()
            if (provider.packageName != BuildConfig.APPLICATION_ID || provider.applicationInfo?.uid != module.uid) return Result(reason = "admission_denied")
        }
        // 有损坏/身份拒绝证据时不切换到另一个更宽松的源。缺失和旧协议可以询问当前权威源。
        val directFallback = normalFailure == null || normalFailure in setOf(
            "remote_group_unavailable", "remote_read_exception", "remote_group_missing", "remote_stale_protocol")
        val nonce = UUID.randomUUID().toString()
        val request = Bundle().apply {
            putLong("deadline", deadline)
            putInt("version", HostAdmissionContract.VERSION)
            putString("nonce", nonce)
            putBoolean("allowDirect", directFallback)
            putLong("normalNoRootRevision", normal?.noRootRevision ?: 0L)
            normal?.takeIf { it.authorized }?.let { putString("normalFingerprint", RemoteHookConfigContract.contentFingerprint(it)) }
        }
        fun exchangeUsing(call: (String, Bundle) -> Bundle?): Result {
            if (SystemClock.elapsedRealtime() >= deadline) return Result(reason = "admission_timeout")
            val prepared = call(HostAdmissionContract.METHOD_PREPARE, request) ?: return Result()
            if (!valid(prepared, nonce) || prepared.getString("status") != "prepared") return Result(reason = "admission_denied")
            val identity = HostAdmissionContract.identity(prepared) ?: return Result(reason = "admission_denied")
            val source = prepared.getString("source")
            val snapshot = when (source) {
                HostAdmissionContract.NORMAL -> normal ?: return Result(reason = "admission_denied")
                HostAdmissionContract.DIRECT -> {
                    if (!directFallback) return Result(reason = "admission_denied")
                    val document = HostAdmissionContract.decode(prepared.getString("document").orEmpty()) ?: return Result(reason = "admission_denied")
                    (RemoteHookConfigContract.decode(document) as? RemoteHookConfigDecodeResult.Ready)?.snapshot
                        ?: return Result(reason = "admission_denied")
                }
                else -> return Result(reason = "admission_denied")
            }
            if (!snapshot.authorized || RemoteHookConfigContract.contentFingerprint(snapshot) != identity.fingerprint) return Result(reason = "admission_denied")
            if (SystemClock.elapsedRealtime() >= deadline) return Result(reason = "admission_timeout")
            val confirmation = Bundle().apply {
                putInt("version", HostAdmissionContract.VERSION)
                putString("nonce", nonce)
                putString("challenge", prepared.getString("challenge"))
                HostAdmissionContract.putIdentity(this, identity)
            }
            val granted = call(HostAdmissionContract.METHOD_CONFIRM, confirmation) ?: return Result()
            if (SystemClock.elapsedRealtime() >= deadline) return Result(reason = "admission_timeout")
            if (!valid(granted, nonce) || granted.getString("status") != "granted" ||
                granted.getString("source") != source || HostAdmissionContract.identity(granted) != identity) return Result(reason = "admission_denied")
            return Result(Grant(snapshot, source, identity), reason = "")
        }
        if (SystemClock.elapsedRealtime() >= deadline) return Result(reason = "admission_timeout")
        return if (bound && Build.VERSION.SDK_INT >= 29) boundExchange(context, deadline, ::exchangeUsing)
        else context.contentResolver.acquireUnstableContentProviderClient(authority)?.use { client ->
            exchangeUsing { method, extras -> client.call(method, null, extras) }
        } ?: Result()
    }
    // API 29 起可指定工作回调执行器，避免 Application.attach 主线程等待自己的绑定回调。
    @androidx.annotation.RequiresApi(29)
    private fun boundExchange(context: Context, deadline: Long, exchange: ((String, Bundle) -> Bundle?) -> Result): Result {
        val component = ComponentName(BuildConfig.APPLICATION_ID, CompatibilityReceiptService::class.java.name)
        val info = context.packageManager.getServiceInfo(component, 0)
        val module = context.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0)
        if (info.packageName != BuildConfig.APPLICATION_ID || info.applicationInfo.uid != module.uid || !info.exported || !info.enabled) return Result(reason = "admission_denied")
        val arrival = CompletableFuture<IBinder?>()
        val closed = AtomicBoolean(false)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (!closed.get()) arrival.complete(service.takeIf { name == component })
            }
            override fun onServiceDisconnected(name: ComponentName) { arrival.complete(null) }
            override fun onBindingDied(name: ComponentName) { arrival.complete(null) }
            override fun onNullBinding(name: ComponentName) { arrival.complete(null) }
        }
        try {
            if (!context.bindService(Intent().setComponent(component), Context.BIND_AUTO_CREATE,
                    java.util.concurrent.Executor { it.run() }, connection)) return Result()
            val binder = arrival.get((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L), TimeUnit.MILLISECONDS) ?: return Result()
            if (!binder.isBinderAlive || binder.interfaceDescriptor != CompatibilityReceiptService.DESCRIPTOR) return Result(reason = "admission_denied")
            return exchange { method, extras ->
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(CompatibilityReceiptService.DESCRIPTOR)
                    data.writeString(method); data.writeBundle(extras)
                    if (!binder.transact(CompatibilityReceiptService.ADMISSION, data, reply, 0)) null
                    else { reply.readException(); reply.readBundle(Bundle::class.java.classLoader) }
                } finally { data.recycle(); reply.recycle() }
            }
        } finally {
            closed.set(true)
            runCatching { context.unbindService(connection) }
        }
    }

    private fun valid(value: Bundle, nonce: String): Boolean = value.getInt("version") == HostAdmissionContract.VERSION &&
        value.getLong("moduleVersion") == BuildConfig.VERSION_CODE.toLong() && value.getString("nonce") == nonce
}
