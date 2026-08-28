package com.Bilibili_Innocent_Lab.xposedmodule.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion
import java.util.concurrent.atomic.AtomicLong

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
 * 同一显式组件还承载一次性 ordered broadcast 的只读授权回执，不接受状态写入。
 */
class RoamingOpenReceiver : BroadcastReceiver() {

    companion object {
        /** Provider 被宿主包可见性隔离时的一次性、只读 Hook 授权查询。 */
        const val ACTION_QUERY_HOOK_AUTHORIZATION =
            "com.Bilibili_Innocent_Lab.xposedmodule.QUERY_HOOK_AUTHORIZATION"
        const val EXTRA_HOOK_AUTHORIZATION_HANDLED = "hook_authorization_handled"
        const val EXTRA_HOOK_AUTHORIZED = "hook_authorized"

        /** B 站进程发送的广播 action（RoamingCompatHook 中点击入口时发送） */
        const val ACTION_OPEN_ROAMING_SETTINGS = "com.Bilibili_Innocent_Lab.xposedmodule.OPEN_ROAMING_SETTINGS"
        const val EXTRA_REQUEST_ELAPSED_REALTIME = "request_elapsed_realtime"
        private const val MAX_REQUEST_AGE_MS = 5_000L
        private const val MIN_REQUEST_INTERVAL_MS = 1_000L
        private val lastAcceptedRequestMs = AtomicLong(0L)
    }

    override fun onReceive(context: Context, intent: Intent) {
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
            return
        }
        if (intent.action != ACTION_OPEN_ROAMING_SETTINGS) return
        val authorized = runCatching {
            UserTermsConsentStore.readOrInitialize(context).isAuthorized
        }.getOrDefault(false)
        if (!authorized) return
        val now = SystemClock.elapsedRealtime()
        val requestedAt = intent.getLongExtra(EXTRA_REQUEST_ELAPSED_REALTIME, -1L)
        if (requestedAt <= 0L || now - requestedAt !in 0L..MAX_REQUEST_AGE_MS) return
        // Android 14+ 可取得真实发送方身份；只接受由注入代码所在的 B 站进程
        // 发起的请求。Android 13 及以下没有对应公开 API，因此 Manifest 不再声明
        // Intent Filter，发送方必须知道并显式指定组件，同时还需通过短时效与节流检查。
        // 接收器只执行无参数的设置页跳转，不处理状态写入或外部数据。
        if (AndroidVersion.isAtLeast(AndroidVersion.U) &&
            sentFromPackage != HookEntry.TARGET_PACKAGE
        ) return
        val previous = lastAcceptedRequestMs.get()
        if (previous > 0L && now - previous < MIN_REQUEST_INTERVAL_MS) return
        lastAcceptedRequestMs.set(now)
        runCatching {
            val launch = Intent().apply {
                setClassName("me.iacn.biliroaming", "me.iacn.biliroaming.MainActivityAlias")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launch)
        }
    }
}
