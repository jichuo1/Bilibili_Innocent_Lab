package com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Parcel
import android.os.SystemClock
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostReceiptWire
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard

/** 与 Provider 独立调度的短时绑定。保留最新消息，前台/新发布事件触发恢复，不常驻轮询。 */
internal object CompatibilityReceiptPublisher {
    private val main: Handler = object : Handler(Looper.getMainLooper()) {
        override fun dispatchMessage(msg: Message) {
            HostThreadGuard.run("compat_receipt.main") { super.dispatchMessage(msg) }
        }
    }
    private val worker = HostReceiptWire.executor("bil-bound-receipt")
    private val pending = LatestReceiptOutbox<Bundle>()
    private val sending = hashMapOf<String, Long>()
    private var connection: ServiceConnection? = null
    @Volatile private var endpoint: IBinder? = null
    private var lastAttempt = -CommunicationCompatibilityPolicy.BIND_COOLDOWN_MS
    private var release: Runnable? = null
    private var retry: Runnable? = null

    fun publish(context: Context, extras: Bundle) {
        val app = context.applicationContext ?: context
        main.post(HostThreadGuard.runnable("compat_receipt.publish") {
            val channel = extras.getString("channel") ?: return@runnable
            val estimate = 4096L + (extras.getString("payload")?.length ?: 0).toLong() * 2L
            if (estimate > HostReceiptWire.MAX_PARCEL_BYTES) return@runnable
            if (!pending.put(channel, extras.getLong("sequence"), estimate.toInt(), Bundle(extras))) return@runnable
            connect(app)
        })
    }
    fun acknowledge(channel: String, sequence: Long) {
        main.post(HostThreadGuard.runnable("compat_receipt.ack") { pending.ack(channel, sequence) })
    }

    private fun connect(app: Context) {
        endpoint?.let { flush(it); return }
        if (connection != null || pending.snapshot().isEmpty()) return
        val delay = CommunicationCompatibilityPolicy.BIND_COOLDOWN_MS - (SystemClock.elapsedRealtime() - lastAttempt)
        if (delay > 0) {
            if (retry == null) {
                retry = HostThreadGuard.runnable("compat_receipt.retry") {
                    retry = null
                    connect(app)
                }.also { main.postDelayed(it, delay) }
            }
            return
        }
        lastAttempt = SystemClock.elapsedRealtime()
        val component = ComponentName(BuildConfig.APPLICATION_ID, CompatibilityReceiptService::class.java.name)
        val candidate = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                HostThreadGuard.run("compat_receipt.service_connected") {
                    if (connection !== this || name != component) return@run
                    endpoint = service
                    // 每条消息同时携带反向查询端点，不要求另一条通道先完成注册。
                    flush(service)
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                HostThreadGuard.run("compat_receipt.service_disconnected") {
                    if (connection === this) close(app)
                }
            }
            override fun onBindingDied(name: ComponentName) {
                HostThreadGuard.run("compat_receipt.binding_died") {
                    if (connection === this) close(app)
                }
            }
            override fun onNullBinding(name: ComponentName) {
                HostThreadGuard.run("compat_receipt.null_binding") {
                    if (connection === this) close(app)
                }
            }
        }
        connection = candidate
        release = HostThreadGuard.runnable("compat_receipt.release") {
            if (connection === candidate) close(app)
        }.also {
            main.postDelayed(it, CommunicationCompatibilityPolicy.BIND_WINDOW_MS)
        }
        val bound = runCatching { app.bindService(Intent().setComponent(component), candidate, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) close(app)
    }

    private fun flush(service: IBinder) {
        pending.snapshot().forEach { (channel, entry) ->
            if (channel in sending) return@forEach
            sending[channel] = entry.revision
            runCatching { worker.execute {
                HostThreadGuard.run("compat_receipt.flush") {
                    val data = Parcel.obtain(); val reply = Parcel.obtain()
                    var accepted = false
                    try {
                        if (endpoint !== service || !service.isBinderAlive || service.interfaceDescriptor != CompatibilityReceiptService.DESCRIPTOR) return@run
                        data.writeInterfaceToken(CompatibilityReceiptService.DESCRIPTOR)
                        data.writeBundle(entry.value)
                        if (data.dataSize() > HostReceiptWire.MAX_PARCEL_BYTES) return@run
                        if (service.transact(CompatibilityReceiptService.SUBMIT, data, reply, 0)) {
                            reply.readException(); accepted = reply.readInt() == 1
                        }
                    } catch (_: Exception) {
                        // 未知结果保留最新数据；不创建替代线程或无界重试。
                    } finally {
                        data.recycle(); reply.recycle()
                        main.post(HostThreadGuard.runnable("compat_receipt.flush_done") {
                            sending.remove(channel)
                            if (accepted) pending.ack(channel, entry.revision)
                            // 在途时该通道发生更新：发送更新值；失败的相同值等待下次外部事件。
                            if ((pending.snapshot()[channel]?.revision ?: 0L) > entry.revision) endpoint?.let(::flush)
                        })
                    }
                }
            } }.onFailure { sending.remove(channel) }
        }
    }

    private fun close(context: Context) {
        release?.let(main::removeCallbacks); release = null
        val previous = connection
        connection = null; endpoint = null
        // sending 由实际事务退出清除，超时不能假装撤销 Binder 或再开写线程。
        if (previous != null) runCatching { context.unbindService(previous) }
    }
}
