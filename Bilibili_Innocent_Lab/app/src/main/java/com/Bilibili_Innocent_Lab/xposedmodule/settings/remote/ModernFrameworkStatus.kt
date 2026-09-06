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

/** 可选版本信息读取失败不否决已确认的 API/Remote 能力；任何失败都不逸出服务回调。 */
internal fun readModernFrameworkStatus(
    readApiVersion: () -> Int,
    readProperties: () -> Long,
    readName: () -> String,
    readVersion: () -> String,
    readVersionCode: () -> Long
): ModernFrameworkStatus {
    val api = runCatching(readApiVersion)
    val properties = runCatching(readProperties)
    val name = runCatching(readName)
    val version = runCatching(readVersion)
    val versionCode = runCatching(readVersionCode)
    return ModernFrameworkStatus(
        connected = true,
        capable = (api.getOrNull() ?: 0) >= ModernApiSupport.MIN_API &&
            properties.getOrNull()?.let { it and XposedService.PROP_CAP_REMOTE != 0L } == true,
        name = name.getOrNull().orEmpty().take(128),
        apiVersion = api.getOrNull()?.coerceAtLeast(0) ?: 0,
        version = version.getOrNull()?.take(128),
        versionCode = versionCode.getOrNull()?.takeIf { it >= 0L },
        properties = properties.getOrNull(),
        failureCode = if (listOf(api, properties, name, version, versionCode).any { it.isFailure }) {
            "framework_metadata_unavailable"
        } else null
    )
}
