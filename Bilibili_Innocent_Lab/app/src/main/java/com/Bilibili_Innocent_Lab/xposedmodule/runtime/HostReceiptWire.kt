package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.IBinder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 仅使用系统 Parcel 类型，跨宿主/模块 ClassLoader 不传应用对象。 */
internal object HostReceiptWire {
    const val METHOD = "publish_host_receipt"
    const val VERSION = 1
    const val DESCRIPTOR = "bilab.host.receipt.v1"
    const val QUERY = IBinder.FIRST_CALL_TRANSACTION
    const val RESPONSE = IBinder.FIRST_CALL_TRANSACTION
    const val DIAGNOSTICS = "diagnostics"
    const val REGISTER = "register"
    const val TIMEOUT_MS = 750L
    const val MAX_PARCEL_BYTES = 768 * 1024

    fun executor(name: String) = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(8),
        { runnable -> Thread(runnable, name).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
}

