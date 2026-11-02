package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote

import io.github.libxposed.service.XposedService
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ModernApiSupport

internal data class ModernFrameworkStatus(
    val connected: Boolean,
    val capable: Boolean,
    val name: String,
    val apiVersion: Int,
    val version: String? = null,
    val versionCode: Long? = null,
    val properties: Long? = null,
    val connectionId: Long = 0L,
    val failureCode: String? = null
) {
    companion object {
        val allowedFailureCodes = setOf("framework_metadata_unavailable", "service_died")
    }
}

/**
 * 框架名称只用于管理器引导和展示语义，不能替代 API/Remote 能力校验或宿主运行回执。
 * LSPatch 当前公开服务名固定为 "LSPatch"；保持精确匹配，避免把其他名字中恰好包含
 * lspatch 的实现误归类。
 */
internal fun isLspatchFrameworkName(frameworkName: String): Boolean =
    frameworkName.trim().equals("LSPatch", ignoreCase = true)

internal val ModernFrameworkStatus.isLspatch: Boolean
    get() = isLspatchFrameworkName(name)

/**
 * NPatch 的公开服务名是 "NPatch"（v1.0.8 诊断页实测）。与 [frameworkManagerTargets] 保持同一
 * 口径：按包含匹配，因为 NPatch 各版本可能带后缀。
 */
internal fun isNpatchFrameworkName(frameworkName: String): Boolean =
    frameworkName.contains("npatch", ignoreCase = true)

/**
 * 框架能否 Hook 系统进程（`PROP_CAP_SYSTEM`）。这是 libxposed 服务契约里"框架自己声明"的能力位，
 * 只有真 Root 框架具备，无 Root 的 NPatch / LSPatch 不具备——不依赖框架名字，NPatch 改名后仍成立。
 *
 * @return null 表示属性读取失败、结论未知；调用方应按"未知不否决"处理，保持原行为。
 */
internal fun frameworkHasSystemCapability(properties: Long?): Boolean? =
    properties?.let { it and XposedService.PROP_CAP_SYSTEM != 0L }

/**
 * 是不是"真 Root 框架"：Modern 服务可写（[capable]）**且**不是 NPatch **且**没有被自己的能力位否决。
 *
 * `capable` 只说明服务能写偏好，NPatch v1.0.8 起同样为真，所以不能等同于"有 Root"。名字只是第一道
 * （已实测的 "NPatch"），能力位是第二道（改名也拦得住）；两道都按"否决 Root"方向叠加，
 * 属性未知时不额外否决，因此只会让判定更接近事实，不会把已确认的 Root 框架误判成无 Root。
 */
internal fun isRootFramework(capable: Boolean, frameworkName: String, properties: Long?): Boolean =
    capable && !isNpatchFrameworkName(frameworkName) &&
        frameworkHasSystemCapability(properties) != false

/**
 * 当前 Modern 服务是不是 NPatch 的「免 Root 通道」。免 Root 开关只对这条通道是总开关，
 * LSPosed / LSPatch 的标准发布不受它影响。
 *
 * 名字是第一道（已实测公开服务名 "NPatch"），`PROP_CAP_SYSTEM` 能力位是第二道（改名后
 * 仍成立）；LSPatch 用精确名字单独排除。**元数据未知（[ModernFrameworkStatus.capable] 为
 * false）时不判定**，宁可退回文档判据，也不能把 LSPosed 误判成 NPatch 后关掉它的发布。
 */
internal fun isNpatchConfigChannel(status: ModernFrameworkStatus): Boolean {
    if (!status.capable) return false
    return isNpatchFrameworkName(status.name) ||
        (!isLspatchFrameworkName(status.name) &&
            !isRootFramework(status.capable, status.name, status.properties))
}

/** 可选版本信息读取失败不否决已确认的 API/Remote 能力；任何失败都不逸出服务回调。 */
internal fun readModernFrameworkStatus(
    readApiVersion: () -> Int,
    readProperties: () -> Long,
    readName: () -> String,
    readVersion: () -> String,
    readVersionCode: () -> Long
): ModernFrameworkStatus = readModernFrameworkCapabilities(readApiVersion, readProperties)
    .withModernFrameworkMetadata(readName, readVersion, readVersionCode)

internal fun readModernFrameworkCapabilities(
    readApiVersion: () -> Int,
    readProperties: () -> Long
): ModernFrameworkStatus {
    val api = runCatching(readApiVersion)
    val properties = runCatching(readProperties)
    return ModernFrameworkStatus(
        connected = true,
        capable = (api.getOrNull() ?: 0) >= ModernApiSupport.MIN_API &&
            properties.getOrNull()?.let { it and XposedService.PROP_CAP_REMOTE != 0L } == true,
        name = "",
        apiVersion = api.getOrNull()?.coerceAtLeast(0) ?: 0,
        properties = properties.getOrNull(),
        failureCode = if (api.isFailure || properties.isFailure) "framework_metadata_unavailable" else null
    )
}

internal fun ModernFrameworkStatus.withModernFrameworkMetadata(
    readName: () -> String,
    readVersion: () -> String,
    readVersionCode: () -> Long
): ModernFrameworkStatus = withModernFrameworkIdentity(readName)
    .withModernFrameworkVersions(readVersion, readVersionCode)

/** The name selects LSPatch receipt routing; never expose an identity-less service as root activation. */
internal fun ModernFrameworkStatus.withModernFrameworkIdentity(readName: () -> String): ModernFrameworkStatus {
    val name = runCatching(readName)
    val identity = name.getOrNull()?.take(128).orEmpty()
    return copy(name = identity, capable = capable && identity.isNotBlank(),
        failureCode = if (identity.isBlank()) "framework_metadata_unavailable" else failureCode)
}

internal fun ModernFrameworkStatus.withModernFrameworkVersions(
    readVersion: () -> String,
    readVersionCode: () -> Long
): ModernFrameworkStatus {
    val version = runCatching(readVersion)
    val versionCode = runCatching(readVersionCode)
    return copy(
        version = version.getOrNull()?.take(128),
        versionCode = versionCode.getOrNull()?.takeIf { it >= 0L },
        failureCode = if (failureCode != null || listOf(version, versionCode).any { it.isFailure }) {
            "framework_metadata_unavailable"
        } else null
    )
}
