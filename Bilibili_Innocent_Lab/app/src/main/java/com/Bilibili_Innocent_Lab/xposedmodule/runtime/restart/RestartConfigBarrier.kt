package com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostReceiptWire
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportController
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.AuthorityPublication
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigPublishResult

/** 保存通道由当前配置环境决定，与 Root / Shizuku / 手动执行方式独立。 */
internal object RestartConfigBarrier {
    private val worker = HostReceiptWire.executor("bil-restart-save")
    private val main = Handler(Looper.getMainLooper())
    fun flush(context: Context, preferences: SharedPreferences, noRoot: Boolean, done: (Boolean) -> Unit) {
        val app = context.applicationContext
        if (noRoot) {
            NoRootSupportController.flushBeforeRestart(app, preferences) { result ->
                main.post { done(result == NoRootSupportController.FlushResult.SUCCESS) }
            }
        } else {
            val queued = runCatching { worker.execute {
                @Suppress("UseKtx") // 同步 commit 的布尔结果是保存屏障，不能丢弃。
                val persisted = runCatching {
                    persistStandardRestartConfig(
                        persistLocal = { preferences.edit().commit() },
                        capture = { PublicationAuthorityStore.current(app) },
                        lspatchDirect = { com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.LspatchRuntimeProfiles.usesDirectConfig(app) },
                        publishRemote = { RemoteHookConfigStore.publish(app, it.decision) is RemoteHookConfigPublishResult.Success },
                        stillCurrent = { PublicationAuthorityStore.matches(app, it) }
                    )
                }.getOrDefault(false)
                main.post { done(persisted) }
            } }.isSuccess
            if (!queued) main.post { done(false) }
        }
    }
}

/** 标准通道仍优先发布镜像；通信兼容模式的当前直达文档也能满足保存屏障。 */
internal fun persistStandardRestartConfig(
    persistLocal: () -> Boolean,
    capture: () -> AuthorityPublication?,
    lspatchDirect: () -> Boolean,
    publishRemote: (AuthorityPublication) -> Boolean,
    stillCurrent: (AuthorityPublication) -> Boolean
): Boolean {
    if (!persistLocal()) return false
    val publication = capture()?.takeIf { it.decision.isAuthorized } ?: return false
    val available = lspatchDirect() || runCatching { publishRemote(publication) }.getOrDefault(false) ||
        publication.directAllowed
    return available && stillCurrent(publication)
}
