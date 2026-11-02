package com.Bilibili_Innocent_Lab.xposedmodule.runtime

internal object HostAdmissionSourcePolicy {
    /**
     * 启动授权的来源选择。
     *
     * `noRootSelected == (normalNoRootRevision > 0)` 是“用户意图与文档类型”的自洽校验：
     * 选了免 Root 就必须是免 Root 文档，没选就必须是标准文档。B1 暴露的漏洞是第二侧——
     * 标准发布器会在免 Root 关闭后把 `noRootRevision = 0` 的标准文档写回 Remote Store，
     * 让“关闭态”再次自洽并放行。因此补一条总开关门禁：**免 Root 开关对 NPatch 通道是
     * 总开关**，关闭后 NORMAL 与 DIRECT 都不再发放授权。
     *
     * [nPatchChannel] 由框架元数据判定；元数据未就绪时传 false，改由 `normalNoRootRevision > 0`
     * （文档确实来自免 Root 通道）兜底，两者都不成立才按标准通道放行，保证 LSPosed 不受影响。
     */
    fun select(
        noRootSelected: Boolean,
        normalNoRootRevision: Long,
        expectedFingerprint: String,
        normalFingerprint: String?,
        allowDirect: Boolean,
        directEnabled: Boolean,
        nPatchChannel: Boolean = false,
        lspatchChannel: Boolean = false
    ): String? = when {
        // LSPatch 集成模式的配置库属于宿主 UID；两种模式均使用当前模块文档和完整的新鲜握手。
        lspatchChannel -> if (allowDirect) HostAdmissionContract.DIRECT else null
        !noRootSelected && (nPatchChannel || normalNoRootRevision > 0L) -> null
        // 已验证 NPatch 身份的启动优先取当前本地文档；不依赖旧兼容模式或 Manager 镜像。
        nPatchChannel && noRootSelected && allowDirect -> HostAdmissionContract.DIRECT
        noRootSelected == (normalNoRootRevision > 0L) && expectedFingerprint == normalFingerprint ->
            HostAdmissionContract.NORMAL
        allowDirect && directEnabled -> HostAdmissionContract.DIRECT
        else -> null
    }
}
