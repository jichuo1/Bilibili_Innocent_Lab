package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.ModernFrameworkStatus
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.isLspatch

/** 宿主回执确认了加载框架；只有同框架的在线服务才能补充 API 版本。 */
internal data class ActivationFrameworkSource(val name: String, val apiVersion: Int?) {
    companion object {
        fun lspatch(service: ModernFrameworkStatus) = ActivationFrameworkSource(
            name = "LSPatch",
            apiVersion = service.apiVersion.takeIf {
                service.connected && service.capable && service.isLspatch && it > 0
            }
        )
    }
}
