package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.HostConfigChannel

/** 配置入口跟随实际宿主通道，不跟随最后连接的管理器。 */
internal data class NoRootSetupPolicy(val lspatch: Boolean) {
    val needsOptIn: Boolean get() = !lspatch
    val managerPackage: String get() = if (lspatch) "org.lsposed.lspatch" else "top.nkbe.npatch"

    companion object {
        fun forChannel(channel: HostConfigChannel) = NoRootSetupPolicy(channel == HostConfigChannel.LSPATCH)
    }
}
