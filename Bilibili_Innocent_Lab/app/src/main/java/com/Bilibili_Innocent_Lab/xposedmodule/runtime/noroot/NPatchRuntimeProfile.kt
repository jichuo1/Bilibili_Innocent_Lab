package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.getPackageInfoOrNull
import io.github.jichuo1.meguviz.android.extensions.versionCodeCompat
import java.util.concurrent.ConcurrentHashMap
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

internal data class PackageProfileCacheKey(
    val packageName: String,
    val lastUpdateTime: Long,
    val versionCode: Long
) {
    val cacheable: Boolean get() = lastUpdateTime > 0L || versionCode > 0L
}

internal class BoundedPackageProfileCache<V : Any>(
    private val maxEntries: Int = 16
) {
    private val entries = ConcurrentHashMap<PackageProfileCacheKey, V>()

    fun getOrLoad(
        context: Context,
        packageName: String,
        baseInfo: PackageInfo? = null,
        loader: (PackageInfo?) -> V
    ): V {
        val resolvedBase = baseInfo ?: context.packageManager.getPackageInfoOrNull(packageName)
            ?: return loader(null)
        val key = keyOf(packageName, resolvedBase)
        return getOrLoadForKey(key) { loader(resolvedBase) }
    }

    internal fun getOrLoadForKey(key: PackageProfileCacheKey, loader: () -> V): V {
        if (!key.cacheable) return loader()
        entries[key]?.let { return it }
        val loaded = loader()
        if (entries.size >= maxEntries && !entries.containsKey(key)) {
            entries.clear()
        }
        entries.putIfAbsent(key, loaded)
        return entries[key] ?: loaded
    }

    internal fun clear() {
        entries.clear()
    }

    companion object {
        fun keyOf(packageName: String, info: PackageInfo): PackageProfileCacheKey =
            PackageProfileCacheKey(
                packageName = packageName,
                lastUpdateTime = info.lastUpdateTime,
                versionCode = info.versionCodeCompat
            )
    }
}

/** manifest metadata 是 NPatch 实际覆盖签名等级等参数的来源，不用较旧的 asset 副本猜测。 */
internal object NPatchRuntimeProfiles {
    private const val MAX_METADATA_CHARS = 64 * 1024
    private const val MANAGER_PACKAGE = "top.nkbe.npatch"
    private val cache = BoundedPackageProfileCache<NPatchRuntimeProfile>()

    fun read(context: Context, packageName: String = NoRootSupportState.TARGET_PACKAGE): NPatchRuntimeProfile =
        readWithBaseInfo(context, packageName, null)

    internal fun readWithBaseInfo(
        context: Context,
        packageName: String,
        baseInfo: PackageInfo?
    ): NPatchRuntimeProfile = cache.getOrLoad(context, packageName, baseInfo) {
        val marker = runCatching {
            context.packageManager.getPackageInfoOrNull(packageName, PackageManager.GET_META_DATA)
                ?.applicationInfo?.metaData?.getString("npatch")
        }.getOrNull() ?: return@getOrLoad NPatchRuntimeProfile()
        if (marker.isBlank()) return@getOrLoad NPatchRuntimeProfile()
        if (marker.length > MAX_METADATA_CHARS) return@getOrLoad NPatchRuntimeProfile(markerPresent = true)
        runCatching {
            parseJson(String(Base64.decode(marker, Base64.DEFAULT), Charsets.UTF_8))
        }.getOrElse { NPatchRuntimeProfile(markerPresent = true) }
    }

    internal fun cacheForTest(): BoundedPackageProfileCache<NPatchRuntimeProfile> = cache

    fun environment(context: Context): NPatchEnvironment {
        val manager = context.packageManager.getPackageInfoOrNull(MANAGER_PACKAGE)
        val host = context.packageManager.getPackageInfoOrNull(NoRootSupportState.TARGET_PACKAGE)
        return NPatchEnvironment(readWithBaseInfo(context, NoRootSupportState.TARGET_PACKAGE, host), manager?.versionCodeCompat,
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
