package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import android.content.pm.ApplicationInfo
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * 从宿主 APK 的 ZIP 中央目录生成稳定的 DEX 内容指纹。
 *
 * 指纹只读取 `classes*.dex` 的 CRC 与尺寸元数据，不加载 DEX；绝对安装路径和
 * 非代码 split 不参与摘要，因此同一构建重新安装后仍得到同一结果。
 * NPatch / LSPatch 的外层只有 loader：后台先有界提取并验证原版 APK，指纹和 DexKit 必须共用该来源。
 */
internal object DexSourceFingerprint {

    private const val FORMAT_VERSION = 1
    private const val MAX_ARCHIVES = 32
    private const val MAX_DEX_ENTRIES = 512
    private val PATCH_ROOTS = listOf("assets/npatch", "assets/lspatch")
    private val DEX_ENTRY_PATTERN = Regex("^classes(?:[2-9]|[1-9][0-9]+)?\\.dex$")

    data class Result(
        val value: String,
        /** 实际含 DEX 的 APK 路径；供后台 DexKit 查询使用，不写入缓存。 */
        val codePaths: List<String>,
        val dexEntryCount: Int
    )

    fun inspect(applicationInfo: ApplicationInfo?, originalCacheDirectory: File? = null,
                materializeArchiveView: Boolean = false): Result? {
        if (applicationInfo == null) return null
        val paths = buildList {
            applicationInfo.sourceDir?.takeIf(String::isNotBlank)?.let(::add)
            applicationInfo.splitSourceDirs
                .orEmpty()
                .filter(String::isNotBlank)
                .sortedBy { File(it).name }
                .forEach(::add)
        }
        return inspectCodePaths(paths, originalCacheDirectory, materializeArchiveView = materializeArchiveView)
    }

    internal fun inspectCodePaths(
        paths: List<String>,
        originalCacheDirectory: File? = null,
        maximumOriginalBytes: Long = 512L * 1024 * 1024,
        materializeArchiveView: Boolean = false
    ): Result? = runCatching {
        val archives = paths
            .map(::File)
            .distinctBy { it.canonicalPath }
        if (archives.isEmpty() || archives.size > MAX_ARCHIVES || archives.any { !it.isFile }) {
            return@runCatching null
        }

        val canonicalLines = mutableListOf<String>()
        val codePaths = mutableListOf<String>()
        var dexCount = 0
        archives.forEach { outer ->
            val archive = originalArchive(outer, originalCacheDirectory, maximumOriginalBytes, materializeArchiveView)
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
        originalCacheDirectory?.let { retainRecentSources(it, codePaths.toSet()) }

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalLines.joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        Result(
            value = "dex-v$FORMAT_VERSION:$dexCount:$digest",
            codePaths = codePaths.toList(),
            dexEntryCount = dexCount
        )
    }.getOrNull()

    /** 只在既有适配/审计工作线程调用；不借用 loader 私有缓存路径或反射其内部实现。 */
    private fun originalArchive(outer: File, directory: File?, maximumBytes: Long, materializeView: Boolean): File =
        ZipFile(outer).use { zip ->
            val patchEntries = zip.entries().asSequence().filter { entry ->
                PATCH_ROOTS.any { root -> entry.name == "$root/origin.apk" || entry.name == "$root/config.json" }
            }.take(5).toList()
            if (patchEntries.isEmpty()) {
                // 资源 split 没有供 native 消费的代码，保留后续空 DEX 跳过语义。
                if (!materializeView || zip.entries().asSequence().none {
                        !it.isDirectory && DEX_ENTRY_PATTERN.matches(it.name)
                    }) return@use outer
                return@use snapshotReadableView(outer, zip, requireNotNull(directory), maximumBytes)
            }
            // 两种载体同时出现或缺少一半时，不能任取原包，也不能回退到 loader DEX。
            val root = PATCH_ROOTS.singleOrNull { root -> patchEntries.any { it.name.startsWith("$root/") } }
            requireNotNull(root) { "Ambiguous patched source" }
            val origins = patchEntries.filter { it.name == "$root/origin.apk" }
            val configs = patchEntries.filter { it.name == "$root/config.json" }
            require(origins.size == 1 && configs.size == 1 && !configs.single().isDirectory) { "Incomplete patched source" }
            val origin = origins.single()
            require(!origin.isDirectory && origin.size in 1..maximumBytes && origin.crc >= 0)
            val cache = requireNotNull(directory) { "Patched original source requires a private cache" }
            require(cache.isDirectory || cache.mkdirs())
            val target = File(cache, "origin-${origin.crc}-${origin.size}.apk")
            fun matches(file: File): Boolean {
                if (!file.isFile || file.length() != origin.size) return false
                val crc = CRC32()
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > origin.size) return false
                        crc.update(buffer, 0, read)
                    }
                }
                return crc.value == origin.crc
            }
            if (matches(target)) {
                return@use target
            }
            val temporary = File.createTempFile("origin-", ".tmp", cache)
            try {
                val crc = CRC32()
                var total = 0L
                zip.getInputStream(origin).buffered().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            check(total <= origin.size && total <= maximumBytes)
                            crc.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                check(total == origin.size && crc.value == origin.crc)
                check(temporary.setReadOnly())
                // 同一私有缓存被多个宿主进程使用；只发布完整文件，绝不原地重写已返回的来源。
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
                target
            } finally {
                temporary.delete()
            }
        }

    /** Java I/O 已被 loader 重定向、native 模块 I/O 未重定向时，冻结 Java 实际看到的来源。 */
    private fun snapshotReadableView(outer: File, zip: ZipFile, cache: File, maximumBytes: Long): File {
        fun entries(archive: ZipFile): List<String> = archive.entries().asSequence()
            .filter { !it.isDirectory && DEX_ENTRY_PATTERN.matches(it.name) }
            .map { "${it.name}:${it.crc}:${it.size}:${it.compressedSize}" }.sorted().toList()
        val expected = entries(zip)
        require(expected.isNotEmpty() && expected.size <= MAX_DEX_ENTRIES)
        val key = MessageDigest.getInstance("SHA-256").digest(expected.joinToString("\n").toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        require(cache.isDirectory || cache.mkdirs())
        val target = File(cache, "view-$key.apk")
        fun verifiedView(file: File): Boolean = runCatching {
            ZipFile(file).use { candidate ->
                if (entries(candidate) != expected) return@use false
                var total = 0L
                val buffer = ByteArray(128 * 1024)
                candidate.entries().asSequence().filter { DEX_ENTRY_PATTERN.matches(it.name) }.all { entry ->
                    val crc = CRC32()
                    var size = 0L
                    candidate.getInputStream(entry).use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            size += read
                            total += read
                            check(total <= maximumBytes && size <= entry.size)
                            crc.update(buffer, 0, read)
                        }
                    }
                    size == entry.size && crc.value == entry.crc
                }
            }
        }.getOrDefault(false)
        if (target.isFile && target.length() in 1..maximumBytes &&
            verifiedView(target)) {
            return target
        }
        val temporary = File.createTempFile("view-", ".tmp", cache)
        try {
            var total = 0L
            outer.inputStream().buffered().use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        check(total <= maximumBytes)
                        output.write(buffer, 0, read)
                    }
                }
            }
            check(total > 0 && verifiedView(temporary))
            check(temporary.setReadOnly())
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally { temporary.delete() }
    }

    private fun retainRecentSources(cache: File, activePaths: Set<String>) {
        activePaths.forEach { File(it).takeIf { file -> file.parentFile == cache }?.setLastModified(System.currentTimeMillis()) }
        // 先收集本次全部 base/split；保留当前来源及一份历史来源，不删除尚未交给 DexKit 的 split。
        cache.listFiles()?.filter { it.absolutePath !in activePaths && it.name.matches(Regex("(?:origin-[0-9]+-[0-9]+|view-[a-f0-9]{64})\\.apk")) }
            ?.sortedByDescending(File::lastModified)?.drop(1)?.forEach { it.delete() }
    }
}
