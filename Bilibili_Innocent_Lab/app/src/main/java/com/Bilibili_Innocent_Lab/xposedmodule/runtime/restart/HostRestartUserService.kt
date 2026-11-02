package com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.Process
import androidx.annotation.Keep
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.getPackageInfoOrNull
import kotlin.system.exitProcess

/** Shizuku v13 的包 Context 只用于取固定包身份；不注册组件、不读取宿主私有数据。 */
@Keep
class HostRestartUserService @Keep constructor(context: Context) : IHostRestartService.Stub() {
    private val ownerUid = context.applicationInfo.uid
    private val commands = HostRestartCommands(
        requireNotNull(context.packageManager.getPackageInfoOrNull(HostRestartCommands.TARGET)?.applicationInfo).uid)

    init { check(context.packageName == BuildConfig.APPLICATION_ID) }

    private fun enforceOwner() {
        check(Binder.getCallingUid() == ownerUid) { "caller_rejected" }
        check(Process.myUid() == 0 || Process.myUid() == 2000) { "unsupported_identity" }
    }

    override fun stopHost(): Bundle {
        enforceOwner()
        return result("stop_failed") {
            val stopped = commands.stop()
            putString("token", stopped.token)
            putIntArray("old_pids", stopped.oldPids.toIntArray())
        }
    }

    override fun startHost(token: String): Bundle {
        enforceOwner()
        return result("launch_failed") { putInt("pid", commands.start(token)) }
    }

    private fun result(failure: String, action: Bundle.() -> Unit): Bundle = Bundle().apply {
        putInt("uid", Process.myUid())
        try { action(); putString("result", "ok") }
        catch (_: Exception) { putString("result", failure) }
    }

    // 官方保留事务由 Shizuku server 调用；不能复用仅允许设置 App UID 的业务校验。
    override fun destroy() {
        val uid = Binder.getCallingUid()
        check(uid == ownerUid || uid == 0 || uid == 2000)
        exitProcess(0)
    }
}
