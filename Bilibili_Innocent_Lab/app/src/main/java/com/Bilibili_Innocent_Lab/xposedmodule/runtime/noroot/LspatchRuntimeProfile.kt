package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.content.Context
import android.util.Base64
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.ModernFrameworkStatus
import android.content.pm.PackageManager
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.getPackageInfoOrNull
import org.json.JSONObject

internal enum class LspatchMode { MANAGER, EMBEDDED, UNKNOWN }

internal data class LspatchRuntimeProfile(
    val mode: LspatchMode = LspatchMode.UNKNOWN,
    val loaderApi: Int = 0,
    val loaderVersion: Long = 0L
) {
    val modern: Boolean get() = mode != LspatchMode.UNKNOWN && loaderApi >= 102 && loaderVersion > 0L
}

/** JingMatrix 1.x 的已安装补丁档案；管理器存在、请求自报及旧版 Legacy 标记均不能授予配置通道。 */
internal object LspatchRuntimeProfiles {
    private const val MAX_METADATA_CHARS = 64 * 1024

    fun read(context: Context, packageName: String = NoRootSupportState.TARGET_PACKAGE): LspatchRuntimeProfile =
        runCatching {
            val marker = context.packageManager.getPackageInfoOrNull(packageName, PackageManager.GET_META_DATA)
                ?.applicationInfo?.metaData?.getString("lspatch") ?: return@runCatching LspatchRuntimeProfile()
            if (marker.isBlank() || marker.length > MAX_METADATA_CHARS) return@runCatching LspatchRuntimeProfile()
            parseJson(String(Base64.decode(marker, Base64.DEFAULT), Charsets.UTF_8))
        }.getOrDefault(LspatchRuntimeProfile())

    fun usesDirectConfig(context: Context, status: ModernFrameworkStatus = RemoteHookConfigStore.status()): Boolean =
        HostConfigChannels.read(context, status) == HostConfigChannel.LSPATCH

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
            loaderVersion = number("VERSION_CODE").coerceAtLeast(0L)
        )
    }.getOrDefault(LspatchRuntimeProfile())
}
