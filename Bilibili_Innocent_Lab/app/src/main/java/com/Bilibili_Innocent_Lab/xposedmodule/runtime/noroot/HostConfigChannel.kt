package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.ModernFrameworkStatus
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.isLspatch
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.isNpatchConfigChannel
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.isRootFramework

internal enum class HostConfigChannel { STANDARD, NPATCH, LSPATCH }

/** 管理器的服务没有绑定宿主；补丁框架冲突时以已安装宿主档案为准，保留 Root 优先级。 */
internal object HostConfigChannels {
    fun read(context: Context, status: ModernFrameworkStatus = RemoteHookConfigStore.status()): HostConfigChannel =
        select(status, NPatchRuntimeProfiles.read(context).markerPresent, LspatchRuntimeProfiles.read(context).modern)

    fun select(status: ModernFrameworkStatus, nPatchMarker: Boolean, modernLspatch: Boolean): HostConfigChannel = when {
        status.capable && !status.isLspatch && isRootFramework(status.capable, status.name, status.properties) ->
            HostConfigChannel.STANDARD
        modernLspatch -> HostConfigChannel.LSPATCH
        nPatchMarker -> HostConfigChannel.NPATCH
        else -> serviceChannel(status)
    }

    private fun serviceChannel(status: ModernFrameworkStatus): HostConfigChannel = when {
        status.capable && status.isLspatch -> HostConfigChannel.LSPATCH
        isNpatchConfigChannel(status) -> HostConfigChannel.NPATCH
        else -> HostConfigChannel.STANDARD
    }
}
