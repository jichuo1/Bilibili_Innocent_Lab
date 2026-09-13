package com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostReceiptRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostReceiptWire
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsQueryContract

/** 两种模式共用的鉴权回执入口。每次同步 Binder 事务独立验真，不把 onBind 当身份校验。 */
class CompatibilityReceiptService : Service() {
    private val endpoint = object : Binder() {
        init { attachInterface(null, DESCRIPTOR) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != SUBMIT && code != ADMISSION) return super.onTransact(code, data, reply, flags)
            if (flags and IBinder.FLAG_ONEWAY != 0 || reply == null ||
                data.dataSize() > HostReceiptWire.MAX_PARCEL_BYTES ||
                !trustedCaller() ||
                !CommunicationCompatibilityStore.hasConsent(this@CompatibilityReceiptService)
            ) return false
            if (code == ADMISSION) {
                val result = runCatching {
                    data.enforceInterface(DESCRIPTOR)
                    val method = data.readString().orEmpty()
                    com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostAdmissionEndpoint.handle(
                        this@CompatibilityReceiptService, method, data.readBundle(Bundle::class.java.classLoader))
                }.getOrNull()
                reply.writeNoException(); reply.writeBundle(result)
                return true
            }
            val accepted = runCatching {
                data.enforceInterface(DESCRIPTOR)
                val extras = data.readBundle(Bundle::class.java.classLoader)
                // 不 clearCallingIdentity：Registry 用内核 UID/PID、版本、会话、摘要共同验真。
                HostReceiptRegistry.receive(this@CompatibilityReceiptService, extras).getBoolean("accepted")
            }.getOrDefault(false)
            reply.writeNoException()
            reply.writeInt(if (accepted) 1 else 0)
            return true
        }
    }

    private fun trustedCaller(): Boolean = runCatching {
        packageManager.getApplicationInfo(HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE, 0).uid == Binder.getCallingUid()
    }.getOrDefault(false)

    override fun onBind(intent: Intent?): IBinder? =
        endpoint.takeIf { CommunicationCompatibilityStore.hasConsent(this) }

    companion object {
        const val DESCRIPTOR = "bilab.compat.receipt.v1"
        const val SUBMIT = IBinder.FIRST_CALL_TRANSACTION
        const val ADMISSION = IBinder.FIRST_CALL_TRANSACTION + 1
    }
}
