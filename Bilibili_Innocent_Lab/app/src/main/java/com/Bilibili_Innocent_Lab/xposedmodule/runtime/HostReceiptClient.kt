package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** 一次 oneway 请求 + 一次带 nonce 的回调；超时后由调用方使用旧广播，永不拿缓存当在线响应。 */
internal object HostReceiptClient {
    private val worker = HostReceiptWire.executor("bil-receipt-query")

    fun query(channel: String, callback: (Bundle?, String) -> Unit) {
        val session = HostReceiptRegistry.current()
        val nonce = UUID.randomUUID().toString()
        if (session == null) { callback(null, nonce); return }
        val main = Handler(Looper.getMainLooper())
        val completed = AtomicBoolean(false)
        val deadline = SystemClock.elapsedRealtime() + HostReceiptWire.TIMEOUT_MS
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
        main.postDelayed(timeout, HostReceiptWire.TIMEOUT_MS)
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
