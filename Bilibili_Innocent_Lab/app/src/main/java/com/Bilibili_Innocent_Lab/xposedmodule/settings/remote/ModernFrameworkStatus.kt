package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote

import io.github.libxposed.service.XposedService

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
        capable = (api.getOrNull() ?: 0) >= XposedService.API_102 &&
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
