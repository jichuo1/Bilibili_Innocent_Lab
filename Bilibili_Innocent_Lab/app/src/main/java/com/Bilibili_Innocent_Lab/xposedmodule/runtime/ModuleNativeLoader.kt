package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.Build
import android.os.Process
import java.io.File

/**
 * Loads module native libraries, falling back to loading directly from the canonical module APK
 * (`$canonicalApk!/lib/$abi/lib$name.so`) when the host classloader namespace lacks the module's
 * native library directory.
 */
internal object ModuleNativeLoader {
    @Volatile
    private var moduleApkPath: String? = null

    fun registerModuleApk(path: String?) {
        val raw = path?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val canonical = runCatching {
            val file = File(raw).canonicalFile
            if (file.isFile) file.path else null
        }.getOrNull() ?: return
        moduleApkPath = canonical
    }

    fun load(name: String) {
        loadWith(
            name = name,
            loadLibrary = System::loadLibrary,
            loadPath = System::load,
            abiProvider = ::runtimeAbi
        )
    }

    internal fun loadWith(
        name: String,
        loadLibrary: (String) -> Unit,
        loadPath: (String) -> Unit,
        abiProvider: () -> String?
    ) {
        require(name.isNotEmpty() && !name.contains('/') && !name.contains('\\') && !name.contains(".."))
        try {
            loadLibrary(name)
            return
        } catch (primary: UnsatisfiedLinkError) {
            val apk = canonicalRegisteredApk() ?: throw primary
            val abi = abiProvider() ?: throw primary
            val entryPath = "$apk!/lib/$abi/lib$name.so"
            try {
                loadPath(entryPath)
            } catch (fallback: UnsatisfiedLinkError) {
                fallback.addSuppressed(primary)
                throw fallback
            }
        }
    }

    internal fun canonicalRegisteredApk(): String? {
        val registered = moduleApkPath ?: return null
        return runCatching {
            val file = File(registered)
            val canonical = file.canonicalFile
            if (file.path == canonical.path && canonical.isFile) canonical.path else null
        }.getOrNull()
    }

    internal fun selectAbi(
        is64Bit: Boolean,
        supported64: Array<String>?,
        supported32: Array<String>?
    ): String? {
        val candidates = if (is64Bit) supported64 else supported32
        if (candidates == null || candidates.isEmpty()) return null
        val preferred = if (is64Bit) "arm64-v8a" else "armeabi-v7a"
        return candidates.firstOrNull { it == preferred }
            ?: candidates.firstOrNull { !it.isNullOrBlank() }
    }

    internal fun clearRegisteredApkForTest() {
        moduleApkPath = null
    }

    private fun runtimeAbi(): String? = runCatching {
        selectAbi(Process.is64Bit(), Build.SUPPORTED_64_BIT_ABIS, Build.SUPPORTED_32_BIT_ABIS)
    }.getOrNull()
}
