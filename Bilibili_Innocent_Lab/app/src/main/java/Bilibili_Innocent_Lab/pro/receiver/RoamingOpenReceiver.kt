package Bilibili_Innocent_Lab.pro.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import Bilibili_Innocent_Lab.pro.hook.HookEntry

/**
 * 代开哔哩漫游设置的接收器（B 站进程 → 本模块 App 的跨进程通道）。
 *
 * 背景：部分设备（如本机 MIUI + LSPosed DirectAccessService 分支）上，B 站
 * 进程对任何其他包都不可见（系统级包可见性隔离），既不能直接 startActivity
 * 打开 me.iacn.biliroaming，也不能经 ContentProvider 调用本模块（Unknown
 * authority）。广播投递不受包可见性过滤，是 B 站 → 模块 App 仅剩的可靠
 * 通道：B 站点击「我的」页注入的「哔哩漫游设置」入口后发送显式广播，
 * 本接收器以模块 App 身份启动漫游的 MainActivityAlias（已导出、带 LAUNCHER
 * 类别；其 MainActivity 本体未导出，显式启动会 ActivityNotFoundException）。
 */
class RoamingOpenReceiver : BroadcastReceiver() {

    companion object {
        /** B 站进程发送的广播 action（RoamingCompatHook 中点击入口时发送） */
        const val ACTION_OPEN_ROAMING_SETTINGS = "Bilibili.Innocent_Lab.pro.OPEN_ROAMING_SETTINGS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_OPEN_ROAMING_SETTINGS) return
        // Android 14+ 可取得真实发送方身份；只接受由注入代码所在的 B 站进程
        // 发起的请求。Android 13 及以下没有对应公开 API，仍依赖显式包路由，
        // 且该接收器只执行无参数的设置页跳转，不处理状态写入或外部数据。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            sentFromPackage != HookEntry.TARGET_PACKAGE
        ) return
        runCatching {
            val launch = Intent().apply {
                setClassName("me.iacn.biliroaming", "me.iacn.biliroaming.MainActivityAlias")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launch)
        }
    }
}
