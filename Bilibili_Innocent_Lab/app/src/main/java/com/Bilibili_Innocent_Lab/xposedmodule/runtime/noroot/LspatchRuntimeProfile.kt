package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Base64
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.getPackageInfoOrNull
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.ModernFrameworkStatus
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore
import org.json.JSONObject

internal enum class LspatchMode { MANAGER, EMBEDDED, UNKNOWN }

internal data class LspatchRuntimeProfile(
    val mode: LspatchMode = LspatchMode.UNKNOWN,
    val loaderApi: Int = 0,
    val loaderVersion: Long = 0L,
    val markerPresent: Boolean = false
) {
    val modern: Boolean get() = mode != LspatchMode.UNKNOWN && loaderApi >= 102 && loaderVersion > 0L
    val isDetected: Boolean get() = markerPresent || mode != LspatchMode.UNKNOWN || loaderApi > 0
    val legacy: Boolean get() = isDetected && !modern
}

/** JingMatrix 1.x 的已安装补丁档案；管理器存在、请求自报及旧版 Legacy 标记均不能授予配置通道。 */
internal object LspatchRuntimeProfiles {
    private const val MAX_METADATA_CHARS = 64 * 1024
    private val cache = BoundedPackageProfileCache<LspatchRuntimeProfile>()

    fun read(context: Context, packageName: String = NoRootSupportState.TARGET_PACKAGE): LspatchRuntimeProfile =
        cache.getOrLoad(context, packageName) {
            val metaData = runCatching {
                context.packageManager.getPackageInfoOrNull(packageName, PackageManager.GET_META_DATA)
                    ?.applicationInfo?.metaData
            }.getOrNull()
            readFromMetadata(metaData)
        }

    internal fun readFromMetadata(metadata: Bundle?): LspatchRuntimeProfile = runCatching {
        val marker = metadata?.getString("lspatch") ?: return@runCatching LspatchRuntimeProfile()
        if (marker.isBlank()) return@runCatching LspatchRuntimeProfile()
        if (marker.length > MAX_METADATA_CHARS) return@runCatching LspatchRuntimeProfile(markerPresent = true)
        val parsed = parseJson(String(Base64.decode(marker, Base64.DEFAULT), Charsets.UTF_8))
        if (parsed.markerPresent) parsed else LspatchRuntimeProfile(markerPresent = true)
    }.getOrDefault(LspatchRuntimeProfile())

    fun usesDirectConfig(context: Context, status: ModernFrameworkStatus = RemoteHookConfigStore.status()): Boolean =
        HostConfigChannels.read(context, status) == HostConfigChannel.LSPATCH

    internal fun cacheForTest(): BoundedPackageProfileCache<LspatchRuntimeProfile> = cache

    internal fun parseJson(raw: String): LspatchRuntimeProfile = runCatching {
        require(raw.length <= MAX_METADATA_CHARS)
        val json = JSONObject(raw)
        val manager = json.opt("useManager") as? Boolean
        val loader = json.optJSONObject("lspConfig")
        fun number(key: String): Long = when (val value = loader?.opt(key)) {
            is Int -> value.toLong()
            is Long -> value
            else -> 0L
        }
        LspatchRuntimeProfile(
            mode = when (manager) {
                true -> LspatchMode.MANAGER
                false -> LspatchMode.EMBEDDED
                else -> LspatchMode.UNKNOWN
            },
            loaderApi = number("API_CODE").takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt() ?: 0,
            loaderVersion = number("VERSION_CODE").coerceAtLeast(0L),
            markerPresent = true
        )
    }.getOrDefault(LspatchRuntimeProfile())
}
