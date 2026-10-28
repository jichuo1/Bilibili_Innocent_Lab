package com.Bilibili_Innocent_Lab.xposedmodule.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.IntentCompat
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.provider.RoamingCompatProvider
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.AdmissionReplayBook
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostAdmissionEndpoint
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.RoamingSettingsLauncher
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootUpgradeRecoveryCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import io.github.jichuo1.meguviz.android.extensions.AndroidVersion
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf

/**
 * 代开哔哩漫游设置的接收器（B 站进程 → 本模块 App 的跨进程通道）。
 *
 * 背景：部分设备（如本机 MIUI + LSPosed DirectAccessService 分支）上，B 站
 * 进程对任何其他包都不可见（系统级包可见性隔离），既不能直接 startActivity
 * 打开 me.iacn.biliroaming，也不能经 ContentProvider 调用本模块（Unknown
 * authority）。广播投递不受包可见性过滤，是 B 站 → 模块 App 仅剩的可靠
 * 通道：B 站点击「我的」页注入的「哔哩漫游设置」入口后发送显式广播，
 * 本接收器返回模块创建的固定目标凭证，由仍可见的宿主发送，保留系统启动策略。
 * 同一显式组件还承载一次性 ordered broadcast 的只读授权回执，不接受状态写入。
 */
class RoamingOpenReceiver : BroadcastReceiver() {

    companion object {
        /** Provider 被宿主包可见性隔离时的一次性、只读 Hook 授权查询。 */
        const val ACTION_QUERY_HOOK_AUTHORIZATION =
            "com.Bilibili_Innocent_Lab.xposedmodule.QUERY_HOOK_AUTHORIZATION"
        const val EXTRA_HOOK_AUTHORIZATION_HANDLED = "hook_authorization_handled"
        const val EXTRA_HOOK_AUTHORIZED = "hook_authorized"
        const val ACTION_QUERY_HOOK_ADMISSION =
            "com.Bilibili_Innocent_Lab.xposedmodule.QUERY_HOOK_ADMISSION"
        const val EXTRA_ADMISSION_METHOD = "hook_admission_method"
        const val EXTRA_ADMISSION_REQUEST = "hook_admission_request"
        const val EXTRA_ADMISSION_NONCE = "hook_admission_nonce"
        const val EXTRA_ADMISSION_CALLER_PROOF = "hook_admission_caller_proof"
        const val EXTRA_ADMISSION_RESPONSE_ACTION = "hook_admission_response_action"
        const val EXTRA_ADMISSION_HANDLED = "hook_admission_handled"
        const val EXTRA_ADMISSION_RESPONSE = "hook_admission_response"
        const val EXTRA_BOOTSTRAP_CALLBACK = "no_root_bootstrap_callback"
        const val EXTRA_BOOTSTRAP_NONCE = "no_root_bootstrap_nonce"

        /** NPatch 宿主的启动/关闭回执；调用方身份由其自建 PendingIntent 证明。 */
        const val ACTION_REPORT_NO_ROOT_HEARTBEAT =
            "com.Bilibili_Innocent_Lab.xposedmodule.REPORT_NO_ROOT_HEARTBEAT"
        const val EXTRA_CALLER_PROOF = "no_root_caller_proof"
        const val EXTRA_NO_ROOT_ACTIVE = "no_root_active"

        /** B 站进程发送的广播 action（RoamingCompatHook 中点击入口时发送） */
        const val ACTION_OPEN_ROAMING_SETTINGS = "com.Bilibili_Innocent_Lab.xposedmodule.OPEN_ROAMING_SETTINGS"
        const val EXTRA_REQUEST_ELAPSED_REALTIME = "request_elapsed_realtime"
        private val admissionReplays = AdmissionReplayBook(
            clock = { SystemClock.elapsedRealtime() }
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_QUERY_HOOK_ADMISSION) {
            handleAdmissionQuery(context, intent)
            return
        }
        if (intent.action == ACTION_QUERY_HOOK_AUTHORIZATION) {
            // 必须是显式 ordered broadcast；宿主通过系统最终回调取回结果，
            // 模块不接受任何状态写入。Android 14+ 还校验框架报告的真实发送包。
            if (!isOrderedBroadcast ||
                (AndroidVersion.isAtLeast(AndroidVersion.U) &&
                    sentFromPackage != HookEntry.TARGET_PACKAGE)
            ) return
            val authorized = runCatching {
                UserTermsConsentStore.readOrInitialize(context).isAuthorized
            }.getOrDefault(false)
            getResultExtras(true).apply {
                putBoolean(EXTRA_HOOK_AUTHORIZATION_HANDLED, true)
                putBoolean(EXTRA_HOOK_AUTHORIZED, authorized)
            }
            sendSecureBootstrapReply(context, intent, authorized)
            if (authorized) NoRootUpgradeRecoveryCoordinator.pokeIfRetryable()
            return
        }
        if (intent.action == ACTION_REPORT_NO_ROOT_HEARTBEAT) {
            receiveNoRootHeartbeat(context, intent)
            return
        }
        if (intent.action != ACTION_OPEN_ROAMING_SETTINGS) return
        RoamingSettingsLauncher.reply(context, intent,
            if (AndroidVersion.isAtLeast(AndroidVersion.U)) sentFromPackage else null,
            if (AndroidVersion.isAtLeast(AndroidVersion.U)) sentFromUid else -1)
    }

    /** Provider/Service 被宿主包可见性隔离时的完整授权握手。 */
    private fun handleAdmissionQuery(context: Context, intent: Intent) {
        Log.i("BilibiliInnocentLab", "[BIL] 收到启动授权广播")
        val ordered = isOrderedBroadcast
        if (AndroidVersion.isAtLeast(AndroidVersion.U) &&
            sentFromPackage != HookEntry.TARGET_PACKAGE
        ) return
        val proof = IntentCompat.getParcelableExtra(
            intent,
            EXTRA_ADMISSION_CALLER_PROOF,
            classOf<PendingIntent>()
        ) ?: return
        val hostUid = runCatching {
            context.packageManager.getApplicationInfo(HookEntry.TARGET_PACKAGE, 0).uid
        }.getOrNull() ?: return
        val creatorPackage = proof.creatorPackage ?: return
        if (creatorPackage != HookEntry.TARGET_PACKAGE || proof.creatorUid != hostUid) return
        val nonce = intent.getStringExtra(EXTRA_ADMISSION_NONCE).orEmpty()
        val request = intent.getBundleExtra(EXTRA_ADMISSION_REQUEST) ?: return
        if (!HostAdmissionQueryContract.isValidNoncePair(nonce, request)) return
        val method = intent.getStringExtra(EXTRA_ADMISSION_METHOD).orEmpty()
        if (method != "prepare_admission" && method != "confirm_admission") return
        val response = admissionReplays.getOrPut(nonce, method) {
            runCatching {
                HostAdmissionEndpoint.handleBroadcast(context, method, request, proof.creatorUid)
            }.getOrNull()
        } ?: return
        val responseAction = intent.getStringExtra(EXTRA_ADMISSION_RESPONSE_ACTION).orEmpty()
        if (responseAction.isEmpty() ||
            !HostAdmissionQueryContract.isValidResponseAction(creatorPackage, responseAction)
        ) return
        val reply = Intent(responseAction).setPackage(HookEntry.TARGET_PACKAGE).apply {
            putExtra(EXTRA_ADMISSION_RESPONSE, response)
        }
        runCatching { proof.send(context, 0, reply) }
        if (ordered) {
            getResultExtras(true).apply {
                putBoolean(EXTRA_ADMISSION_HANDLED, true)
                putBundle(EXTRA_ADMISSION_RESPONSE, response)
            }
            setResultCode(HostAdmissionQueryContract.RESULT_CODE_HANDLED)
        }
        Log.i("BilibiliInnocentLab", "[BIL] 启动授权广播已处理(status=${response.getString("status")}, ordered=$ordered)")
    }

    /** 完整配置只回送到由 B 站 uid 创建的一次性 PendingIntent，不进入 ordered extras。 */
    private fun sendSecureBootstrapReply(
        context: Context,
        request: Intent,
        authorized: Boolean
    ) {
        val callback = IntentCompat.getParcelableExtra(
            request,
            EXTRA_BOOTSTRAP_CALLBACK,
            classOf<PendingIntent>()
        ) ?: return
        if (callback.creatorPackage != HookEntry.TARGET_PACKAGE) return
        val nonce = request.getStringExtra(EXTRA_BOOTSTRAP_NONCE).orEmpty()
        if (nonce.isBlank()) return
        val exported = NoRootSupportStore.exportState(context, authorized)
        val reply = Intent().apply {
            putExtra(EXTRA_BOOTSTRAP_NONCE, nonce)
            putExtra(EXTRA_HOOK_AUTHORIZED, authorized)
            putExtra(RoamingCompatProvider.COLUMN_NO_ROOT_VALID, exported.valid)
            putExtra(RoamingCompatProvider.COLUMN_NO_ROOT_ENABLED, exported.enabled)
            putExtra(RoamingCompatProvider.COLUMN_NO_ROOT_REVISION, exported.revision)
            putExtra(RoamingCompatProvider.COLUMN_NO_ROOT_PAYLOAD, exported.payload)
        }
        runCatching { callback.send(context, 0, reply) }
    }

    private fun receiveNoRootHeartbeat(context: Context, intent: Intent) {
        if (AndroidVersion.isAtLeast(AndroidVersion.U) &&
            sentFromPackage != HookEntry.TARGET_PACKAGE
        ) return
        val callerProof = IntentCompat.getParcelableExtra(
            intent,
            EXTRA_CALLER_PROOF,
            classOf<PendingIntent>()
        ) ?: return
        if (callerProof.creatorPackage != HookEntry.TARGET_PACKAGE) return
        val targetPackage = intent.getStringExtra(
            RoamingCompatProvider.EXTRA_NO_ROOT_TARGET_PACKAGE
        ).orEmpty()
        val processName = intent.getStringExtra(
            RoamingCompatProvider.EXTRA_NO_ROOT_PROCESS
        ).orEmpty()
        if (targetPackage != HookEntry.TARGET_PACKAGE ||
            (processName != targetPackage && !processName.startsWith("$targetPackage:"))
        ) return
        val revision = intent.getLongExtra(
            RoamingCompatProvider.EXTRA_NO_ROOT_REVISION,
            0L
        )
        val moduleVersion = intent.getLongExtra(
            RoamingCompatProvider.EXTRA_NO_ROOT_MODULE_VERSION,
            0L
        )
        val targetVersion = intent.getLongExtra(
            RoamingCompatProvider.EXTRA_NO_ROOT_TARGET_VERSION,
            0L
        )
        val targetUpdateTime = intent.getLongExtra(
            RoamingCompatProvider.EXTRA_NO_ROOT_TARGET_UPDATE_TIME,
            0L
        )
        if (intent.getBooleanExtra(EXTRA_NO_ROOT_ACTIVE, true)) {
            val authorized = runCatching {
                UserTermsConsentStore.readOrInitialize(context).isAuthorized
            }.getOrDefault(false)
            if (!authorized) return
            NoRootSupportStore.recordHeartbeat(
                context = context,
                revision = revision,
                moduleVersionCode = moduleVersion,
                targetVersionCode = targetVersion,
                targetUpdateTime = targetUpdateTime,
                targetPackage = targetPackage
            )
        } else {
            NoRootSupportStore.recordDisabledAck(
                context = context,
                revision = revision,
                moduleVersionCode = moduleVersion,
                targetVersionCode = targetVersion,
                targetUpdateTime = targetUpdateTime,
                targetPackage = targetPackage
            )
        }
    }

}

private object HostAdmissionQueryContract {
    const val RESULT_CODE_HANDLED = 0x4841

    fun isValidNoncePair(nonce: String, request: android.os.Bundle): Boolean =
        com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsQueryContract.isValidNonce(nonce) &&
            request.getString("nonce") == nonce

    fun isValidResponseAction(packageName: String, action: String): Boolean =
        action.startsWith("$packageName.HOST_ADMISSION_RESPONSE.") && action.length <= 256
}
