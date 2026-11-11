package com.Bilibili_Innocent_Lab.xposedmodule.settings.frame

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchMode
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRemoteGateway.SyncResult
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.FrameworkServiceCall

/** 插帧模型文件经哪条通道读写。 */
internal enum class FrameFileRoute {
    /** LSPosed 等经 `XposedServiceHelper` 推送来的框架服务。 */
    PUSHED_SERVICE,

    /** NPatch 通道已选：只用 Manager 认证的拉取通道，推送来的服务不校验来源，不能写文件。 */
    PULLED_NPATCH,

    /** 嵌入模式的 Remote Store 在宿主私有目录，设置 App 写不进去。 */
    UNSUPPORTED_EMBEDDED;

    companion object {
        /**
         * [standardFrameworkConnected]：已有 LSPosed / Vector / LSPatch 一类的标准框架服务推送到本进程。
         * 免 Root 开关只对 NPatch 通道是总开关（见 `isNpatchConfigChannel`），同时装着 Root 框架的用户
         * 仍走原来的推送路径，不会被开关改道到没安装的 NPatch Manager。
         */
        fun select(noRootSelected: Boolean, mode: NPatchMode, standardFrameworkConnected: Boolean): FrameFileRoute = when {
            !noRootSelected || standardFrameworkConnected -> PUSHED_SERVICE
            mode == NPatchMode.EMBEDDED -> UNSUPPORTED_EMBEDDED
            else -> PULLED_NPATCH
        }

        /** 拉取通道的失败沿用推送路径的失败原因，让界面文案与重试逻辑保持一致。 */
        fun failureReason(result: SyncResult): FrameworkServiceCall.Reason = when (result) {
            SyncResult.ManagerMissing, SyncResult.ModuleNotRegistered -> FrameworkServiceCall.Reason.NOT_CONNECTED
            SyncResult.ConnectionTimeout -> FrameworkServiceCall.Reason.METADATA_UNAVAILABLE
            is SyncResult.Failure -> FrameworkServiceCall.Reason.OPERATION_FAILED
            SyncResult.Success -> FrameworkServiceCall.Reason.OPERATION_FAILED
        }
    }
}
