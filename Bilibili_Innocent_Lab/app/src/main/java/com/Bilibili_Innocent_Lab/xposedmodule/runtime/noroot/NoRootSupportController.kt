package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.content.Context
import com.highcapable.betterandroid.system.extension.component.versionCodeCompat
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion
import com.highcapable.yukihookapi.hook.xposed.prefs.YukiHookPrefsBridge
import java.util.concurrent.atomic.AtomicLong

/** 模块设置进程的单飞协调器；无定时轮询，不持有 Activity 或 View。 */
internal object NoRootSupportController {
    private val requestGeneration = AtomicLong(0L)

    /**
     * 只提交用户意图并使旧同步回调失效；开启后的快照构造与 Binder 同步由调用方
     * 随后放到后台执行。这样快速开关时，最后一次 UI 操作始终拥有最终决定权。
     */
    fun setDesiredEnabled(
        context: Context,
        enabled: Boolean
    ): Boolean {
        val appContext = context.applicationContext
        if (enabled && AndroidVersion.isLessThan(AndroidVersion.P)) {
            return false
        }
        val intentGeneration = requestGeneration.incrementAndGet()
        if (!enabled) {
            val disabled = NoRootSupportStore.disable(appContext)
            if (!disabled) {
                NoRootSupportStore.updateSyncState(
                    appContext,
                    NoRootSupportStore.SyncState.ERROR,
                    detail = "disable_snapshot_write_failed",
                    stillCurrent = { requestGeneration.get() == intentGeneration }
                )
            } else {
                // 目标启动权威通道已经使用本地 tombstone；这里再尽力清理 NPatch
                // Remote Store，避免未来版本读取到历史 enabled=true。
                NoRootSupportStore.readSnapshot(appContext)
                    ?.takeUnless { it.enabled }
                    ?.let { tombstone ->
                        NPatchRemoteGateway.syncAsync(
                            context = appContext,
                            snapshot = tombstone,
                            stillCurrent = {
                                requestGeneration.get() == intentGeneration &&
                                    !NoRootSupportStore.isDesiredEnabled(appContext) &&
                                    NoRootSupportStore.readSnapshot(appContext)?.revision ==
                                    tombstone.revision
                            },
                            callback = {}
                        )
                    }
            }
            return disabled
        }
        if (!NoRootSupportStore.setDesiredEnabled(appContext, enabled = true)) return false
        NoRootSupportStore.updateSyncState(
            appContext,
            NoRootSupportStore.SyncState.CHECKING,
            stillCurrent = { requestGeneration.get() == intentGeneration }
        )
        return true
    }

    /** 在 UI 生命周期调用顺序中同步分配代次，防止旧 Activity 的迟启动线程反客为主。 */
    fun beginSynchronization(context: Context): Long? {
        val appContext = context.applicationContext
        if (!NoRootSupportStore.isDesiredEnabled(appContext) ||
            AndroidVersion.isLessThan(AndroidVersion.P)
        ) return null
        val generation = requestGeneration.incrementAndGet()
        NoRootSupportStore.updateSyncState(
            appContext,
            NoRootSupportStore.SyncState.CHECKING,
            stillCurrent = { requestGeneration.get() == generation }
        )
        return generation
    }

    fun synchronize(
        context: Context,
        bridge: YukiHookPrefsBridge,
        generation: Long? = null,
        onFinished: (() -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        if (!NoRootSupportStore.isDesiredEnabled(appContext) ||
            AndroidVersion.isLessThan(AndroidVersion.P)
        ) return
        val activeGeneration = generation ?: beginSynchronization(appContext) ?: return
        if (requestGeneration.get() != activeGeneration) return
        val snapshot = runCatching {
            NoRootSupportStore.upsertEnabledSnapshot(appContext, bridge)
        }.getOrNull()
        if (snapshot == null) {
            if (requestGeneration.get() != activeGeneration ||
                !NoRootSupportStore.isDesiredEnabled(appContext)
            ) return
            NoRootSupportStore.updateSyncState(
                appContext,
                NoRootSupportStore.SyncState.ERROR,
                detail = "snapshot_write_failed",
                stillCurrent = { requestGeneration.get() == activeGeneration }
            )
            onFinished?.invoke()
            return
        }
        if (requestGeneration.get() != activeGeneration ||
            !NoRootSupportStore.isDesiredEnabled(appContext)
        ) return
        NoRootSupportStore.updateSyncState(
            appContext,
            NoRootSupportStore.SyncState.SYNCING,
            revision = snapshot.revision,
            stillCurrent = { requestGeneration.get() == activeGeneration }
        )
        NPatchRemoteGateway.syncAsync(
            context = appContext,
            snapshot = snapshot,
            stillCurrent = {
                requestGeneration.get() == activeGeneration &&
                    NoRootSupportStore.isDesiredEnabled(appContext)
            }
        ) { result ->
            if (requestGeneration.get() != activeGeneration ||
                !NoRootSupportStore.isDesiredEnabled(appContext)
            ) return@syncAsync
            val state = when (result) {
                NPatchRemoteGateway.SyncResult.Success -> {
                        if (!NoRootSupportStore.markRemoteSynced(
                                appContext,
                                snapshot.revision,
                                stillCurrent = {
                                    requestGeneration.get() == activeGeneration
                                }
                            )
                    ) {
                        NoRootSupportStore.SyncState.ERROR
                    } else {
                        val status = NoRootSupportStore.readStatus(appContext)
                        val targetInfo = runCatching {
                            appContext.packageManager.getPackageInfo(
                                NoRootSupportState.TARGET_PACKAGE,
                                0
                            )
                        }.getOrNull()
                        val currentTargetVersionCode = targetInfo?.versionCodeCompat ?: 0L
                        val heartbeatMatches =
                            status.heartbeatRevision == snapshot.revision &&
                                status.heartbeatModuleVersion == snapshot.moduleVersionCode &&
                                status.heartbeatTargetPackage ==
                                NoRootSupportState.TARGET_PACKAGE &&
                                currentTargetVersionCode > 0L &&
                                status.heartbeatTargetVersion == currentTargetVersionCode &&
                                status.heartbeatTargetUpdateTime ==
                                (targetInfo?.lastUpdateTime ?: 0L)
                        if (heartbeatMatches) NoRootSupportStore.SyncState.ACTIVE
                        else NoRootSupportStore.SyncState.RESTART_REQUIRED
                    }
                }
                NPatchRemoteGateway.SyncResult.ManagerMissing ->
                    NoRootSupportStore.SyncState.MANAGER_MISSING
                NPatchRemoteGateway.SyncResult.ModuleNotRegistered ->
                    NoRootSupportStore.SyncState.MODULE_NOT_REGISTERED
                NPatchRemoteGateway.SyncResult.ConnectionTimeout ->
                    NoRootSupportStore.SyncState.CONNECTION_TIMEOUT
                is NPatchRemoteGateway.SyncResult.Failure ->
                    NoRootSupportStore.SyncState.ERROR
            }
            val detail = (result as? NPatchRemoteGateway.SyncResult.Failure)?.errorClass
            NoRootSupportStore.updateSyncState(
                appContext,
                state,
                revision = snapshot.revision,
                detail = detail,
                stillCurrent = { requestGeneration.get() == activeGeneration }
            )
            onFinished?.invoke()
        }
    }
}
