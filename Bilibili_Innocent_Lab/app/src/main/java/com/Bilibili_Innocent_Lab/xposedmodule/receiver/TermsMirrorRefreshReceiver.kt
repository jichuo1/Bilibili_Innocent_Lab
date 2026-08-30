package com.Bilibili_Innocent_Lab.xposedmodule.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootUpgradeRecoveryCoordinator
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 覆盖安装后唤起模块进程。条款迁移、授权镜像以及免 Root 快照自愈统一由
 * DefaultApplication.onCreate 启动；接收器只保活等待该后台任务，不写状态也不启动界面。
 */
class TermsMirrorRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pendingResult = goAsync()
        val finished = AtomicBoolean(false)
        NoRootUpgradeRecoveryCoordinator.startPendingAttempt()
        NoRootUpgradeRecoveryCoordinator.awaitCurrentAttempt {
            if (finished.compareAndSet(false, true)) {
                pendingResult.finish()
            }
        }
    }
}
