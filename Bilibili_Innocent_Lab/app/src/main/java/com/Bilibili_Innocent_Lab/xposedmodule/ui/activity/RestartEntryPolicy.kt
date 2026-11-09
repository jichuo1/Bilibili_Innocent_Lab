package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RestartRoute
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.ShizukuAvailability

/** 打开状态/恢复面板与取得重启能力是两件事；服务未启动时仍可引导用户打开管理器。 */
internal fun canOpenRestartControls(
    checking: Boolean,
    route: RestartRoute?,
    shizuku: ShizukuAvailability.Snapshot?
): Boolean = !checking && when (route) {
    RestartRoute.ROOT -> true
    RestartRoute.SHIZUKU -> shizuku?.let {
        it.recommended || it.status == ShizukuAvailability.Status.STOPPED
    } == true
    else -> false
}
