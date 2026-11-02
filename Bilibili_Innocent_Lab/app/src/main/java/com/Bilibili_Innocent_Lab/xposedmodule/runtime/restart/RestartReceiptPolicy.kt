package com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostConfigState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostInstallChainState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationIdentity

/** 命令成功、历史回执、同配置的旧进程都不能代替本次新进程的在线回执。 */
internal object RestartReceiptPolicy {
    fun accepts(expected: PublicationIdentity?, snapshot: HostRuntimeDiagnosticsSnapshot?, queryReady: Boolean,
                newPid: Int, sessionPid: Int?, sessionStarted: Long?, operationStarted: Long,
                processInstalled: Boolean): Boolean =
        expected != null && snapshot != null && queryReady && processInstalled && newPid > 0 &&
            sessionPid == newPid && sessionStarted != null && sessionStarted >= operationStarted &&
            snapshot.bootstrap.admission == expected && snapshot.bootstrap.configState == HostConfigState.ACCEPTED &&
            snapshot.bootstrap.installChainState == HostInstallChainState.COMPLETED
}
