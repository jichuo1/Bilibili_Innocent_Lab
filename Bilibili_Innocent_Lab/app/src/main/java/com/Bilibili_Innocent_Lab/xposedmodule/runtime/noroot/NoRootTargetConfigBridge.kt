package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.provider.RoamingCompatProvider
import com.Bilibili_Innocent_Lab.xposedmodule.receiver.RoamingOpenReceiver
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.CrossAppBroadcastCompat

/** NPatch Legacy 目标进程的纯配置解析与一次性运行状态回执。 */
internal object NoRootTargetConfigBridge {
    private const val TARGET_STATE_PREFS = "innocent_lab_no_root_target_state"
    private const val KEY_APPLIED_ADAPTER_RESET = "applied_adapter_reset"
    private const val HEARTBEAT_PROOF_REQUEST_CODE = 0x42494C

    sealed interface Resolution {
        data class Ready(
            val snapshot: NoRootConfigSnapshot,
            val source: String
        ) : Resolution

        data class Disabled(val revision: Long) : Resolution
        data object Invalid : Resolution
        data object Unavailable : Resolution
    }

    /** 只解析当前启动握手的权威 envelope；失败时不回退宿主旧文件。 */
    fun resolve(
        valid: Boolean,
        enabled: Boolean,
        revision: Long,
        payload: String?,
        source: String
    ): Resolution {
        if (!valid) return Resolution.Invalid
        if (!enabled) return Resolution.Disabled(revision.coerceAtLeast(0L))
        if (revision <= 0L || payload.isNullOrBlank()) return Resolution.Invalid
        val snapshot = NoRootConfigSnapshotCodec.decode(
            payload,
            expectedModulePackage = BuildConfig.APPLICATION_ID,
            expectedModuleVersionCode = BuildConfig.VERSION_CODE.toLong()
        ) ?: return Resolution.Invalid
        return if (snapshot.enabled && snapshot.revision == revision) {
            Resolution.Ready(snapshot, source)
        } else {
            Resolution.Invalid
        }
    }

    /** adapterResetRevision 只应由调用方在宿主主进程冷启动安装前应用一次。 */
    fun applyAdapterResetIfNeeded(
        context: Context,
        revision: Long,
        clearAction: () -> Unit
    ) {
        if (revision <= 0L) return
        val prefs = context.getSharedPreferences(TARGET_STATE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_APPLIED_ADAPTER_RESET, 0L) >= revision) return
        clearAction()
        prefs.edit { putLong(KEY_APPLIED_ADAPTER_RESET, revision) }
    }

    /**
     * 模块接收端用 B 站 uid 创建的 immutable PendingIntent creatorPackage 验证身份，
     * 并再次校验 revision、版本和开关状态；本方法只报告广播是否成功投递。
     */
    fun reportRuntimeState(
        context: Context,
        revision: Long,
        moduleVersionCode: Long,
        targetVersionCode: Long,
        targetUpdateTime: Long,
        processName: String,
        active: Boolean
    ): Boolean = runCatching {
        val appContext = context.applicationContext ?: context
        val proof = PendingIntent.getBroadcast(
            appContext,
            HEARTBEAT_PROOF_REQUEST_CODE,
            Intent("${appContext.packageName}.INNOCENT_LAB_NO_ROOT_PROOF")
                .setPackage(appContext.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val report = Intent(RoamingOpenReceiver.ACTION_REPORT_NO_ROOT_HEARTBEAT)
            .setComponent(
                ComponentName(
                    BuildConfig.APPLICATION_ID,
                    "${BuildConfig.APPLICATION_ID}.receiver.RoamingOpenReceiver"
                )
            )
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra(RoamingOpenReceiver.EXTRA_CALLER_PROOF, proof)
            .putExtra(RoamingOpenReceiver.EXTRA_NO_ROOT_ACTIVE, active)
            .putExtra(RoamingCompatProvider.EXTRA_NO_ROOT_REVISION, revision)
            .putExtra(
                RoamingCompatProvider.EXTRA_NO_ROOT_MODULE_VERSION,
                moduleVersionCode
            )
            .putExtra(RoamingCompatProvider.EXTRA_NO_ROOT_TARGET_VERSION, targetVersionCode)
            .putExtra(
                RoamingCompatProvider.EXTRA_NO_ROOT_TARGET_UPDATE_TIME,
                targetUpdateTime
            )
            .putExtra(
                RoamingCompatProvider.EXTRA_NO_ROOT_TARGET_PACKAGE,
                appContext.packageName
            )
            .putExtra(RoamingCompatProvider.EXTRA_NO_ROOT_PROCESS, processName)
        CrossAppBroadcastCompat.sendBroadcast(appContext, report)
        true
    }.getOrDefault(false)
}
