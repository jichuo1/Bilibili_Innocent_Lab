package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle

internal enum class HookRuntimeMode {
    STANDARD_XPOSED,
    LSPATCH,
    NPATCH_LEGACY
}

/** 以实际框架/模块加载器为先；manifest 标记仅作为无法识别加载器时的降级证据。 */
internal object NoRootRuntimeDetector {
    private const val NPATCH_METADATA_KEY = "npatch"

    @Suppress("DEPRECATION")
    fun detect(
        context: Context,
        frameworkClassLoader: ClassLoader?,
        moduleClassLoader: ClassLoader?
    ): HookRuntimeMode {
        val frameworkFingerprint = classLoaderFingerprint(frameworkClassLoader)
        val moduleFingerprint = classLoaderFingerprint(moduleClassLoader)
        classifyKnownLoaders(frameworkFingerprint, moduleFingerprint)?.let { return it }
        val metadata = runCatching {
            context.packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData
        }.getOrNull()
        if (hasNpatchMarker(metadata)) return HookRuntimeMode.NPATCH_LEGACY
        return if (LspatchRuntimeProfiles.read(context, context.packageName).modern) {
            HookRuntimeMode.LSPATCH
        } else {
            HookRuntimeMode.STANDARD_XPOSED
        }
    }

    internal fun hasNpatchMarker(metadata: Bundle?): Boolean =
        metadata?.containsKey(NPATCH_METADATA_KEY) == true &&
            !metadata.get(NPATCH_METADATA_KEY)?.toString().isNullOrBlank()

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
        hasLspatchMarker: Boolean = false
    ): HookRuntimeMode = classifyKnownLoaders(frameworkFingerprint, moduleFingerprint) ?: when {
        hasNpatchMarker -> HookRuntimeMode.NPATCH_LEGACY
        hasLspatchMarker -> HookRuntimeMode.LSPATCH
        else -> HookRuntimeMode.STANDARD_XPOSED
    }

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
