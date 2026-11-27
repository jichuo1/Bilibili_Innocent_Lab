package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.getPackageInfoOrNull

internal enum class HookRuntimeMode {
    STANDARD_XPOSED,
    LSPATCH,
    NPATCH_LEGACY
}

/** 以实际框架/模块加载器为先；manifest 标记仅作为无法识别加载器时的降级证据。 */
internal object NoRootRuntimeDetector {
    private const val NPATCH_METADATA_KEY = "npatch"
    private const val LSPATCH_METADATA_KEY = "lspatch"
    private val modeCache = BoundedPackageProfileCache<HookRuntimeMode>()

    fun detect(
        context: Context,
        frameworkClassLoader: ClassLoader?,
        moduleClassLoader: ClassLoader?
    ): HookRuntimeMode {
        val frameworkFingerprint = classLoaderFingerprint(frameworkClassLoader)
        val moduleFingerprint = classLoaderFingerprint(moduleClassLoader)
        classifyKnownLoaders(frameworkFingerprint, moduleFingerprint)?.let { return it }
        val packageName = context.packageName
        return modeCache.getOrLoad(context, packageName) {
            val metadata = loadApplicationMetaData(context, packageName)
            when {
                hasNpatchMarker(metadata) -> HookRuntimeMode.NPATCH_LEGACY
                hasLspatchMarker(metadata) ||
                    LspatchRuntimeProfiles.readFromMetadata(metadata).isDetected -> HookRuntimeMode.LSPATCH
                else -> HookRuntimeMode.STANDARD_XPOSED
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun loadApplicationMetaData(context: Context, packageName: String): Bundle? =
        context.packageManager.getPackageInfoOrNull(packageName, PackageManager.GET_META_DATA)
            ?.applicationInfo?.metaData
            ?: runCatching {
                context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData
            }.getOrNull()

    internal fun hasNpatchMarker(metadata: Bundle?): Boolean =
        hasMetadataMarker(metadata, NPATCH_METADATA_KEY)

    internal fun hasLspatchMarker(metadata: Bundle?): Boolean =
        hasMetadataMarker(metadata, LSPATCH_METADATA_KEY)

    @Suppress("DEPRECATION")
    private fun hasMetadataMarker(metadata: Bundle?, key: String): Boolean =
        metadata?.containsKey(key) == true &&
            !metadata.get(key)?.toString().isNullOrBlank()

    internal fun classifyKnownLoaders(
        frameworkFingerprint: String,
        moduleFingerprint: String
    ): HookRuntimeMode? {
        val framework = frameworkFingerprint.lowercase()
        val module = moduleFingerprint.lowercase()
        return when {
            framework.contains("npatch") -> HookRuntimeMode.NPATCH_LEGACY
            framework.contains("org.lsposed.lspatch") -> HookRuntimeMode.LSPATCH
            module.contains("org.lsposed.lspd") ||
                module.contains("lspmoduleclassloader") ||
                framework.contains("org.lsposed.lspd") -> HookRuntimeMode.STANDARD_XPOSED
            else -> null
        }
    }

    internal fun classify(
        hasNpatchMarker: Boolean,
        frameworkFingerprint: String,
        moduleFingerprint: String,
        hasLspatchMarker: Boolean = false,
        lspatchProfile: LspatchRuntimeProfile? = null
    ): HookRuntimeMode = classifyKnownLoaders(frameworkFingerprint, moduleFingerprint) ?: when {
        hasNpatchMarker -> HookRuntimeMode.NPATCH_LEGACY
        hasLspatchMarker || lspatchProfile?.isDetected == true -> HookRuntimeMode.LSPATCH
        else -> HookRuntimeMode.STANDARD_XPOSED
    }

    internal fun cacheForTest(): BoundedPackageProfileCache<HookRuntimeMode> = modeCache

    private fun classLoaderFingerprint(loader: ClassLoader?): String {
        val parts = ArrayList<String>(8)
        var current = loader
        var depth = 0
        while (current != null && depth < 6) {
            parts += current.javaClass.name
            parts += runCatching { current.toString() }.getOrDefault("")
            current = current.parent
            depth++
        }
        return parts.joinToString("|").lowercase()
    }
}
