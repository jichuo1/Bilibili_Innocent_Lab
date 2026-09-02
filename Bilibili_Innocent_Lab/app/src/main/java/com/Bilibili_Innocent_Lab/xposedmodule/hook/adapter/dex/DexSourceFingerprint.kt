package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import android.content.pm.ApplicationInfo
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 从宿主 APK 的 ZIP 中央目录生成稳定的 DEX 内容指纹。
 *
 * 指纹只读取 `classes*.dex` 的 CRC 与尺寸元数据，不解压、不加载 DEX；绝对安装路径和
 * 非代码 split 不参与摘要，因此同一构建重新安装后仍得到同一结果。
 */
internal object DexSourceFingerprint {

    private const val FORMAT_VERSION = 1
    private const val MAX_ARCHIVES = 32
    private const val MAX_DEX_ENTRIES = 512
    private val DEX_ENTRY_PATTERN = Regex("^classes(?:[2-9]|[1-9][0-9]+)?\\.dex$")

    data class Result(
        val value: String,
        /** 实际含 DEX 的 APK 路径；供后台 DexKit 查询使用，不写入缓存。 */
        val codePaths: List<String>,
        val dexEntryCount: Int
    )

    fun inspect(applicationInfo: ApplicationInfo?): Result? {
        if (applicationInfo == null) return null
        val paths = buildList {
            applicationInfo.sourceDir?.takeIf(String::isNotBlank)?.let(::add)
            applicationInfo.splitSourceDirs
                .orEmpty()
                .filter(String::isNotBlank)
                .sortedBy { File(it).name }
                .forEach(::add)
        }
        return inspectCodePaths(paths)
    }

    internal fun inspectCodePaths(paths: List<String>): Result? = runCatching {
        val archives = paths
            .map(::File)
            .distinctBy { it.canonicalPath }
        if (archives.isEmpty() || archives.size > MAX_ARCHIVES || archives.any { !it.isFile }) {
            return@runCatching null
        }

        val canonicalLines = mutableListOf<String>()
        val codePaths = mutableListOf<String>()
        var dexCount = 0
        archives.forEach { archive ->
            val entries = ZipFile(archive).use { zip ->
                zip.entries().asSequence()
                    .filter { entry -> !entry.isDirectory && DEX_ENTRY_PATTERN.matches(entry.name) }
                    .map { entry ->
                        listOf(
                            entry.name,
                            entry.crc.toString(),
                            entry.size.toString(),
                            entry.compressedSize.toString()
                        ).joinToString(":")
                    }
                    .sorted()
                    .toList()
            }
            if (entries.isEmpty()) return@forEach
            dexCount += entries.size
            if (dexCount > MAX_DEX_ENTRIES) return@runCatching null
            val codeArchiveIndex = codePaths.size
            codePaths += archive.absolutePath
            entries.forEach { canonicalLines += "$codeArchiveIndex:$it" }
        }
        if (dexCount == 0) return@runCatching null

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalLines.joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        Result(
            value = "dex-v$FORMAT_VERSION:$dexCount:$digest",
            codePaths = codePaths.toList(),
            dexEntryCount = dexCount
        )
    }.getOrNull()
}
