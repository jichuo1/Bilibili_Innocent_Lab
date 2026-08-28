package com.Bilibili_Innocent_Lab.xposedmodule.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 覆盖安装后唤起模块进程。真正的条款迁移和 Hook 授权镜像同步统一由
 * DefaultApplication.onCreate 完成；接收器不写状态，也不启动任何界面。
 */
class TermsMirrorRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
    }
}
