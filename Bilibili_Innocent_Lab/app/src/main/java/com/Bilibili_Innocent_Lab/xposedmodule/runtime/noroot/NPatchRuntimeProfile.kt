package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.content.Context
import android.os.Build
import android.util.Base64
import io.github.jichuo1.meguviz.android.extensions.versionCodeCompat
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.getPackageInfoOrNull
import android.content.pm.PackageManager
import org.json.JSONObject

internal enum class NPatchMode { MANAGER, EMBEDDED, UNKNOWN }

/** 只保留有界的非敏感补丁字段，不保存签名、路径、Context 或原始 JSON。 */
internal data class NPatchRuntimeProfile(
    val markerPresent: Boolean = false,
    val mode: NPatchMode = NPatchMode.UNKNOWN,
    val signatureLevel: Int? = null,
    val debuggable: Boolean? = null,
    val loaderApi: Int? = null,
    val loaderVersionCode: Long? = null,
    val metadataValid: Boolean = false
)

internal data class NPatchEnvironment(
    val patch: NPatchRuntimeProfile,
    val managerVersionCode: Long?,
    val managerVersionName: String?,
    val hostVersionCode: Long? = null,
    val hostVersionName: String? = null,
    val androidSdk: Int? = null,
    val deviceAbis: List<String> = emptyList()
) {
    val versionMismatch: Boolean
        get() = patch.mode == NPatchMode.MANAGER && patch.loaderVersionCode != null &&
            managerVersionCode != null && patch.loaderVersionCode != managerVersionCode
}

/** manifest metadata 是 NPatch 实际覆盖签名等级等参数的来源，不用较旧的 asset 副本猜测。 */
internal object NPatchRuntimeProfiles {
    private const val MAX_METADATA_CHARS = 64 * 1024
    private const val MANAGER_PACKAGE = "top.nkbe.npatch"

    fun read(context: Context, packageName: String = NoRootSupportState.TARGET_PACKAGE): NPatchRuntimeProfile {
        val marker = runCatching {
            context.packageManager.getPackageInfoOrNull(packageName, PackageManager.GET_META_DATA)
                ?.applicationInfo?.metaData?.getString("npatch")
        }.getOrNull() ?: return NPatchRuntimeProfile()
        if (marker.isBlank()) return NPatchRuntimeProfile()
        if (marker.length > MAX_METADATA_CHARS) return NPatchRuntimeProfile(markerPresent = true)
        return runCatching {
            parseJson(String(Base64.decode(marker, Base64.DEFAULT), Charsets.UTF_8))
        }.getOrElse { NPatchRuntimeProfile(markerPresent = true) }
    }

    fun environment(context: Context): NPatchEnvironment {
        val manager = context.packageManager.getPackageInfoOrNull(MANAGER_PACKAGE)
        val host = context.packageManager.getPackageInfoOrNull(NoRootSupportState.TARGET_PACKAGE)
        return NPatchEnvironment(read(context), manager?.versionCodeCompat,
            manager?.versionName?.take(64), host?.versionCodeCompat, host?.versionName?.take(64),
            Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList())
    }

    /** 不让 JSON 的字符串/数字强制转换把损坏参数解释成真实能力。未知字段不参与决策。 */
    internal fun parseJson(raw: String): NPatchRuntimeProfile {
        val unknown = NPatchRuntimeProfile(markerPresent = true)
        if (raw.length > MAX_METADATA_CHARS) return unknown
        return runCatching {
            val json = JSONObject(raw)
            val manager = json.opt("useManager") as? Boolean
            val lsp = json.optJSONObject("lspConfig")
            NPatchRuntimeProfile(
                markerPresent = true,
                mode = when (manager) { true -> NPatchMode.MANAGER; false -> NPatchMode.EMBEDDED; null -> NPatchMode.UNKNOWN },
                signatureLevel = json.integer("sigBypassLevel", 0L..5L)?.toInt(),
                debuggable = json.opt("debuggable") as? Boolean,
                loaderApi = lsp?.integer("API_CODE", 1L..Int.MAX_VALUE.toLong())?.toInt(),
                loaderVersionCode = lsp?.integer("VERSION_CODE", 1L..Int.MAX_VALUE.toLong()),
                metadataValid = manager != null
            )
        }.getOrDefault(unknown)
    }

    private fun JSONObject.integer(key: String, range: LongRange): Long? {
        val value = opt(key)
        val number = if (value is Int) value.toLong() else if (value is Long) value else return null
        return number.takeIf { it in range }
    }
}
