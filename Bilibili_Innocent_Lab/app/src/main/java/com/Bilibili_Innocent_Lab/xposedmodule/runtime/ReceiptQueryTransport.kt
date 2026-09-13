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

/** 先尝试当前会话 Binder；无会话/超时/断线使用原广播。两条通道各自有界。 */
internal object ReceiptQueryTransport {
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

    fun query(context: Context, channel: String, callback: (Reply) -> Unit) {
        val enabled = CommunicationCompatibilityStore.isEnabled(context)
        val completed = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())
        fun finish(reply: Reply) { if (completed.compareAndSet(false, true)) callback(reply) }
        fun attempt(index: Int) {
            val compatibility = enabled && CommunicationCompatibilityStore.isEnabled(context)
            HostReceiptClient.query(channel, CommunicationCompatibilityPolicy.binderTimeout(compatibility)) { extras, nonce ->
                if (extras != null) finish(Reply(extras, nonce))
                else broadcast(context, channel, compatibility) { reply ->
                    if (CommunicationCompatibilityPolicy.retryReceipt(compatibility, index, reply.failure)) {
                        main.postDelayed({ if (!completed.get()) attempt(index + 1) }, CommunicationCompatibilityPolicy.RETRY_DELAY_MS)
                    } else finish(reply)
                }
            }
        }
        attempt(0)
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
