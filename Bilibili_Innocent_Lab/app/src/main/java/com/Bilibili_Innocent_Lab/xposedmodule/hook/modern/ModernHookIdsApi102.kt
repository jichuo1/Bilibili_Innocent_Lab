package com.Bilibili_Innocent_Lab.xposedmodule.hook.modern

import io.github.libxposed.api.XposedInterface

/** 独立类隔离 102 新接口；R8 规则禁止把调用内联/合并到 API 101 路径。 */
internal object ModernHookIdsApi102 {
    fun assign(
        module: XposedInterface,
        builder: XposedInterface.HookBuilder,
        id: String
    ): XposedInterface.HookBuilder {
        // 调用方已分流，桥内仍显式保护，防止后续调用点绕开版本边界。
        if (module.getApiVersion() >= XposedInterface.API_102) {
            return builder.setId(id)
        }
        error("Framework Hook IDs require API 102")
    }
}
