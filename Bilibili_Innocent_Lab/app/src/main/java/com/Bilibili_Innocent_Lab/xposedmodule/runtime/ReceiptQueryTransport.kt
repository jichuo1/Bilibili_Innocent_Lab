package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CommunicationCompatibilityStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CommunicationCompatibilityPolicy
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 先尝试当前会话 Binder；无会话/超时/断线使用原广播。两条通道各自有界。
 *
 * 两条都判定"没人应答"时还有第三段**保底**：宿主进程可能压根没起来，
 * 此时它磁盘上其实存着上一次的完整快照。经 [HostProcessWaker] 拉起主进程后再试一次，
 * 判据与预算见 [HostProcessWakePolicy]。保底只跑一次，失败就按原来的结果收尾，
 * 不改变任何身份/校验语义。
 *
 * **保底必须由调用方显式打开（`allowWake`），默认关**：把宿主进程拉起来是用户看得见的
 * 副作用，只有用户**主动点开**某个需要宿主扫描结果的面板时才有正当性。
 * 遥测（`TelemetryCoordinator`，自动 24h 一次 + 15 分钟本地重试）与启动时的激活卡检查
 * 也走这条 transport，**它们绝不能顺手把哔哩哔哩启动起来**——那既违背"遥测不在 B 站
 * 进程里做任何事"的口径，也是用户没要求过的后台行为。
 */
internal object ReceiptQueryTransport {
    private val waker = HostReceiptWire.executor("bil-host-wake")

    data class Reply(val extras: Bundle? = null, val nonce: String = "",
                     val failure: ReceiptQueryFailure = ReceiptQueryFailure.NONE)
    private data class Contract(val action: String, val protocol: String, val nonce: String,
                                val handled: String, val handledCode: Int)
    private fun contract(diagnostics: Boolean) = if (diagnostics) Contract(
        HostRuntimeDiagnosticsQueryContract.ACTION_QUERY, HostRuntimeDiagnosticsQueryContract.EXTRA_PROTOCOL_VERSION,
        HostRuntimeDiagnosticsQueryContract.EXTRA_REQUEST_NONCE, HostRuntimeDiagnosticsQueryContract.EXTRA_HANDLED,
        HostRuntimeDiagnosticsQueryContract.RESULT_CODE_HANDLED) else Contract(
        MineComponentSnapshotQueryContract.ACTION_QUERY, MineComponentSnapshotQueryContract.EXTRA_PROTOCOL_VERSION,
        MineComponentSnapshotQueryContract.EXTRA_REQUEST_NONCE, MineComponentSnapshotQueryContract.EXTRA_HANDLED,
        MineComponentSnapshotQueryContract.RESULT_CODE_HANDLED)

    fun queryPluginDiagnostics(
        context: Context,
        allowWake: Boolean = false,
        callback: (Reply) -> Unit
    ) {
        query(context, HostReceiptWire.PLUGIN_DIAGNOSTICS, allowWake = allowWake, callback = callback)
    }

    fun query(
        context: Context,
        channel: String,
        allowWake: Boolean = false,
        callback: (Reply) -> Unit
    ) {
        val enabled = CommunicationCompatibilityStore.isEnabled(context)
        val completed = AtomicBoolean(false)
        // 保底只允许跑一次：唤起之后的那一轮再失败就是真的取不到，不能循环。
        val woke = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())
        fun finish(reply: Reply) { if (completed.compareAndSet(false, true)) callback(reply) }
        fun attempt(index: Int) {
            val compatibility = enabled && CommunicationCompatibilityStore.isEnabled(context)
            fun handleFallback(reply: Reply) {
                when {
                    CommunicationCompatibilityPolicy.retryReceipt(compatibility, index, reply.failure) ->
                        main.postDelayed({ if (!completed.get()) attempt(index + 1) }, CommunicationCompatibilityPolicy.RETRY_DELAY_MS)
                    allowWake && HostProcessWakePolicy.shouldWake(reply.failure) &&
                        woke.compareAndSet(false, true) ->
                        wakeThenRetry(context, main, completed, reply, { finish(it) }) { attempt(index + 1) }
                    else -> finish(reply)
                }
            }
            HostReceiptClient.query(channel, CommunicationCompatibilityPolicy.binderTimeout(compatibility)) { extras, nonce ->
                when {
                    extras != null -> finish(Reply(extras, nonce))
                    // 插件诊断只走在线 Binder 通道，避免误打到广播查询契约。
                    channel == HostReceiptWire.PLUGIN_DIAGNOSTICS -> handleFallback(
                        Reply(
                            failure = if (HostReceiptRegistry.current() == null) {
                                ReceiptQueryFailure.SEND_FAILED
                            } else {
                                ReceiptQueryFailure.TIMEOUT
                            }
                        )
                    )
                    else -> broadcast(context, channel, compatibility, ::handleFallback)
                }
            }
        }
        attempt(0)
    }

    /**
     * 拉起宿主主进程后再试一轮。
     *
     * 唤起会阻塞到 provider 发布（真机 1–3 秒），必须离开主线程；回到主线程后还要等
     * [HostProcessWakePolicy.SETTLE_MS]，让宿主把磁盘快照读回内存，否则会撞上
     * "在线但这个面还没有内容"。唤起没成功、或线程池排满时按原结果收尾。
     */
    private fun wakeThenRetry(
        context: Context,
        main: Handler,
        completed: AtomicBoolean,
        fallback: Reply,
        finish: (Reply) -> Unit,
        retry: () -> Unit
    ) {
        runCatching {
            waker.execute {
                val started = runCatching { HostProcessWaker.wake(context) }.getOrDefault(false)
                main.postDelayed({
                    if (completed.get()) return@postDelayed
                    if (started) retry() else finish(fallback)
                }, HostProcessWakePolicy.SETTLE_MS)
            }
        }.onFailure { finish(fallback) }
    }

    private fun broadcast(context: Context, channel: String, compatibility: Boolean, callback: (Reply) -> Unit) {
        val spec = contract(channel == HostReceiptWire.DIAGNOSTICS)
        val main = Handler(Looper.getMainLooper())
        val completed = AtomicBoolean(false)
        val nonce = UUID.randomUUID().toString()
        fun finish(reply: Reply) {
            if (completed.compareAndSet(false, true)) callback(reply)
        }
        val timeout = Runnable { finish(Reply(failure = ReceiptQueryFailure.TIMEOUT)) }
        main.postDelayed(timeout, CommunicationCompatibilityPolicy.broadcastTimeout(compatibility))
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (completed.get()) return
                main.removeCallbacks(timeout)
                val reply = runCatching {
                    val extras = getResultExtras(false)
                    val reason = ReceiptQueryPolicy.headerFailure(
                        resultCode == spec.handledCode, extras != null,
                        extras?.getString(spec.nonce) == nonce)
                    when {
                        reason != ReceiptQueryFailure.NONE -> Reply(failure = reason)
                        extras?.getBoolean(spec.handled, false) != true -> Reply(failure = ReceiptQueryFailure.MALFORMED_RESPONSE)
                        else -> Reply(extras, nonce)
                    }
                }.getOrElse { Reply(failure = ReceiptQueryFailure.MALFORMED_RESPONSE) }
                finish(reply)
            }
        }
        val intent = Intent(spec.action).setPackage(HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE)
            .putExtra(spec.protocol, if (channel == HostReceiptWire.DIAGNOSTICS)
                HostRuntimeDiagnosticsQueryContract.PROTOCOL_VERSION else MineComponentSnapshotQueryContract.PROTOCOL_VERSION)
            .putExtra(spec.nonce, nonce)
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        if (channel != HostReceiptWire.DIAGNOSTICS) intent.putExtra(MineComponentSnapshotQueryContract.EXTRA_SURFACE, channel)
        runCatching {
            CrossAppBroadcastCompat.sendOrderedBroadcast(context, intent, receiver, main)
        }.onFailure {
            main.removeCallbacks(timeout)
            finish(Reply(failure = ReceiptQueryFailure.SEND_FAILED))
        }
    }
}
