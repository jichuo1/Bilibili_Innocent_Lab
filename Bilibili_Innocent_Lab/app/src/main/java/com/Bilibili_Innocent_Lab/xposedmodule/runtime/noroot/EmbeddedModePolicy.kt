package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.ModernFrameworkStatus
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.isLspatch
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.isNpatchConfigChannel

internal object EmbeddedModePolicy {
    // 发布构建恒为 false；仅 CI 的 embedded 变体通过 BuildConfig 编译成 true，供内测群验证。
    val SUPPORTED: Boolean = BuildConfig.EMBEDDED_MODE_SUPPORTED

    fun isBlocked(
        nPatchMode: NPatchMode,
        lspatchMode: LspatchMode,
        supported: Boolean = SUPPORTED
    ): Boolean = !supported && (nPatchMode == NPatchMode.EMBEDDED || lspatchMode == LspatchMode.EMBEDDED)

    /** 宿主以实际加载框架为准；Root 框架不受安装包内残留的补丁标记影响。 */
    fun isHostBlocked(context: Context, runtime: HookRuntimeMode): Boolean = when (runtime) {
        HookRuntimeMode.STANDARD_XPOSED -> false
        HookRuntimeMode.NPATCH_LEGACY -> isBlocked(
            NPatchRuntimeProfiles.read(context, context.packageName).mode, LspatchMode.UNKNOWN)
        HookRuntimeMode.LSPATCH -> isBlocked(
            NPatchMode.UNKNOWN, LspatchRuntimeProfiles.read(context, context.packageName).mode)
    }

    /** 设置及授权端点共用安装档案，服务未连接时仍能识别无 Manager 的集成模式。 */
    fun isSettingsBlocked(context: Context, status: ModernFrameworkStatus = RemoteHookConfigStore.status()): Boolean {
        if (status.capable && !isNpatchConfigChannel(status) && !status.isLspatch) return false
        return isBlocked(NPatchRuntimeProfiles.read(context).mode, LspatchRuntimeProfiles.read(context).mode)
    }
}
