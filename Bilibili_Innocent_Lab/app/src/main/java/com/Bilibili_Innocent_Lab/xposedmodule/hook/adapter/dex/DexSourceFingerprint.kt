package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import android.content.pm.ApplicationInfo
import java.io.File
import java.security.MessageDigest
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * 从宿主 APK 的 ZIP 中央目录生成稳定的 DEX 内容指纹。
 *
 * 指纹只读取 `classes*.dex` 的 CRC 与尺寸元数据，不加载 DEX；绝对安装路径和
 * 非代码 split 不参与摘要，因此同一构建重新安装后仍得到同一结果。
 * NPatch / LSPatch 的外层只有 loader：后台先有界提取并验证原版 APK，指纹和 DexMeguviz 必须共用该来源。
 */
internal object DexSourceFingerprint {

    private const val FORMAT_VERSION = 1
    /**
     * 输入归档上限（涵盖含原生库/资源/语言在内的全部 APK 与 split）；
     * 通过过滤后实际交给 DexMeguviz 的含 DEX 归档数由 [MAX_CODE_ARCHIVES]（与 `ApkMetadataPlan` 对齐，base + 7 split = 8）约束。
     */
    private const val MAX_ARCHIVES = 32
    internal const val MAX_CODE_ARCHIVES = 8
    private const val MAX_DEX_ENTRIES = 512
    private const val MAX_SPLIT_CACHE_ENTRIES = 8
    private val PATCH_ROOTS = listOf("assets/npatch", "assets/lspatch")
    private val DEX_ENTRY_PATTERN = Regex("^classes(?:[2-9]|[1-9][0-9]+)?\\.dex$")

    private val splitNameCacheLock = Any()
    private val splitNameCache = object : LinkedHashMap<List<String>, List<String?>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<List<String>, List<String?>>): Boolean =
            size > MAX_SPLIT_CACHE_ENTRIES
    }

    data class Result(
        val value: String,
        /** 实际含 DEX 的 APK 路径；供后台 DexMeguviz 查询使用，不写入缓存。 */
        val codePaths: List<String>,
        val dexEntryCount: Int,
        /** 与 [codePaths] 一一对应的 Android split 名称（base 为 null）。 */
        val splitNames: List<String?> = emptyList()
    )

    internal fun splitNamesFor(codePaths: List<String>): List<String?> {
        if (codePaths.isEmpty()) return emptyList()
        synchronized(splitNameCacheLock) {
            return splitNameCache[codePaths]
                ?: codePaths.map { path -> runCatching { File(path).canonicalPath }.getOrDefault(path) }
                    .let { canonical -> splitNameCache[canonical] }
                ?: emptyList()
        }
    }

    fun inspect(
        applicationInfo: ApplicationInfo?,
        originalCacheDirectory: File? = null,
        materializeArchiveView: Boolean = false
    ): Result? {
        if (applicationInfo == null) return null
        val basePath = applicationInfo.sourceDir?.takeIf(String::isNotBlank)
            ?: applicationInfo.publicSourceDir?.takeIf(String::isNotBlank)
        val rawSplits = applicationInfo.splitSourceDirs?.takeIf { it.isNotEmpty() }
            ?: applicationInfo.splitPublicSourceDirs.orEmpty()
        val rawSplitNames = applicationInfo.splitNames.orEmpty()
        val splitEntries = rawSplits.mapIndexedNotNull { index, path ->
            path?.takeIf(String::isNotBlank)?.let {
                it to rawSplitNames.getOrNull(index)?.takeIf(String::isNotBlank)
            }
        }.sortedBy { (path, _) -> File(path).name }

        val paired = buildList {
            basePath?.let { add(it to null) }
            addAll(splitEntries)
        }
        val hasNamedSplits = splitEntries.any { it.second != null }
        return inspectCodePathsWithSplitNames(
            paths = paired.map { it.first },
            splitNames = if (hasNamedSplits) paired.map { it.second } else emptyList(),
            originalCacheDirectory = originalCacheDirectory,
            materializeArchiveView = materializeArchiveView
        )
    }

    internal fun inspectCodePaths(
        paths: List<String>,
        originalCacheDirectory: File? = null,
        maximumOriginalBytes: Long = 512L * 1024 * 1024,
        materializeArchiveView: Boolean = false
    ): Result? = inspectCodePathsWithSplitNames(
        paths = paths,
        splitNames = emptyList(),
        originalCacheDirectory = originalCacheDirectory,
        maximumOriginalBytes = maximumOriginalBytes,
        materializeArchiveView = materializeArchiveView
    )

    internal fun inspectCodePathsWithSplitNames(
        paths: List<String>,
        splitNames: List<String?> = emptyList(),
        originalCacheDirectory: File? = null,
        maximumOriginalBytes: Long = 512L * 1024 * 1024,
        materializeArchiveView: Boolean = false
    ): Result? = runCatching {
        val archives = paths.mapIndexed { index, path ->
            File(path) to splitNames.getOrNull(index)?.takeIf(String::isNotBlank)
        }.distinctBy { (file, _) -> file.canonicalPath }
        if (archives.isEmpty() || archives.size > MAX_ARCHIVES || archives.any { (file, _) -> !file.isFile }) {
            return@runCatching null
        }

        val canonicalLines = mutableListOf<String>()
        val codePaths = mutableListOf<String>()
        val resolvedSplitNames = mutableListOf<String?>()
        val recordSplits = splitNames.isNotEmpty()
        var dexCount = 0
        archives.forEach { (outer, splitName) ->
            val resolved = resolveArchiveAndDexEntries(
                outer = outer,
                directory = originalCacheDirectory,
                maximumBytes = maximumOriginalBytes,
                materializeView = materializeArchiveView
            )
            if (resolved.dexEntries.isEmpty()) return@forEach
            dexCount += resolved.dexEntries.size
            if (dexCount > MAX_DEX_ENTRIES) return@runCatching null
            val codeArchiveIndex = codePaths.size
            codePaths += resolved.archive.canonicalPath
            if (recordSplits) resolvedSplitNames += splitName
            resolved.dexEntries.forEach { canonicalLines += "$codeArchiveIndex:$it" }
        }
        if (dexCount == 0) return@runCatching null
        originalCacheDirectory?.let { retainRecentSources(it, codePaths.toSet()) }

        val immutableCodePaths = codePaths.toList()
        val immutableSplitNames = if (recordSplits) resolvedSplitNames.toList() else emptyList()
        if (immutableSplitNames.isNotEmpty()) {
            synchronized(splitNameCacheLock) {
                splitNameCache[immutableCodePaths] = immutableSplitNames
            }
        }

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalLines.joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        Result(
            value = "dex-v$FORMAT_VERSION:$dexCount:$digest",
            codePaths = immutableCodePaths,
            dexEntryCount = dexCount,
            splitNames = immutableSplitNames
        )
    }.getOrNull()

    private class ResolvedArchive(val archive: File, val dexEntries: List<String>)

    private fun readDexEntries(zip: ZipFile): List<String> =
        zip.entries().asSequence()
            .filter { entry -> !entry.isDirectory && DEX_ENTRY_PATTERN.matches(entry.name) }
            .map { entry -> "${entry.name}:${entry.crc}:${entry.size}:${entry.compressedSize}" }
            .sorted()
            .toList()

    /**
     * 只在既有适配/审计工作线程调用；单次打开外层 ZIP 同时收集补丁载体信息与 `classes*.dex` 元数据，
     * 仅在 NPatch / LSPatch 提取内层 `origin.apk` 后才打开内层 ZIP。
     */
    private fun resolveArchiveAndDexEntries(
        outer: File,
        directory: File?,
        maximumBytes: Long,
        materializeView: Boolean
    ): ResolvedArchive = ZipFile(outer).use { zip ->
        val patchEntries = ArrayList<java.util.zip.ZipEntry>(5)
        val outerDexEntries = ArrayList<String>()
        val enumEntries = zip.entries()
        while (enumEntries.hasMoreElements()) {
            val entry = enumEntries.nextElement()
            if (patchEntries.size < 5 && PATCH_ROOTS.any { root ->
                    entry.name == "$root/origin.apk" || entry.name == "$root/config.json"
                }
            ) {
                patchEntries.add(entry)
            }
            if (!entry.isDirectory && DEX_ENTRY_PATTERN.matches(entry.name)) {
                outerDexEntries.add("${entry.name}:${entry.crc}:${entry.size}:${entry.compressedSize}")
            }
        }
        outerDexEntries.sort()
        if (patchEntries.isEmpty()) {
            // 资源 split 没有供 native 消费的代码，保留后续空 DEX 跳过语义。
            if (!materializeView || outerDexEntries.isEmpty()) {
                return@use ResolvedArchive(outer, outerDexEntries)
            }
            val view = snapshotReadableView(outer, outerDexEntries, requireNotNull(directory), maximumBytes)
            return@use ResolvedArchive(view, outerDexEntries)
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
        val innerArchive = when {
            // 整文件校验只在发布前做一次；之后每次启动只比对长度、修改时间和只读属性。
            target.length() == origin.size && isVerified(target) -> target
            matches(target) -> {
                // 旧版本发布的、尚无标记的缓存：校验这一次，之后走快路径。
                markVerified(target)
                target
            }
            else -> {
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
                    moveReplacing(temporary, target)
                    markVerified(target)
                    target
                } finally {
                    temporary.delete()
                }
            }
        }
        val innerEntries = ZipFile(innerArchive).use(::readDexEntries)
        ResolvedArchive(innerArchive, innerEntries)
    }

    /** Java I/O 已被 loader 重定向、native 模块 I/O 未重定向时，冻结 Java 实际看到的来源。 */
    private fun snapshotReadableView(outer: File, expected: List<String>, cache: File, maximumBytes: Long): File {
        require(expected.isNotEmpty() && expected.size <= MAX_DEX_ENTRIES)
        val key = MessageDigest.getInstance("SHA-256").digest(expected.joinToString("\n").toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        require(cache.isDirectory || cache.mkdirs())
        val target = File(cache, "view-$key.apk")
        fun verifiedView(file: File): Boolean = runCatching {
            ZipFile(file).use { candidate ->
                if (readDexEntries(candidate) != expected) return@use false
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
            (isVerified(target) || verifiedView(target).also { if (it) markVerified(target) })) {
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
            moveReplacing(temporary, target)
            markVerified(target)
            return target
        } finally { temporary.delete() }
    }

    /**
     * 缓存来源的"已校验"标记。来源在发布前已做过整文件校验，发布是原子的，文件名又带内容摘要，
     * 所以此后只要长度、修改时间都没变、文件仍是只读，就仍是当初校验过的那一份。
     * 原地改写需要先放开只读并推进修改时间，补丁重装后缺失标记，都会退回整文件校验并重建标记。
     * 标记缺失或写入失败不是错误，只是下次启动再校验一次。
     */
    private fun marker(source: File) = File(source.parentFile, source.name + VERIFIED_SUFFIX)

    private fun stamp(source: File) = "${source.length()}:${source.lastModified()}"

    internal fun isVerified(source: File): Boolean = runCatching {
        val mark = marker(source)
        source.isFile && !source.canWrite() && mark.isFile && mark.length() <= MAX_STAMP_BYTES &&
            mark.readText() == stamp(source)
    }.getOrDefault(false)

    private fun markVerified(source: File) {
        runCatching {
            val temporary = File.createTempFile("verified-", ".tmp", source.parentFile)
            try {
                temporary.writeText(stamp(source))
                moveReplacing(temporary, marker(source))
            } finally { temporary.delete() }
        }
    }

    private fun moveReplacing(temporary: File, target: File) {
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun retainRecentSources(cache: File, activePaths: Set<String>) {
        // 最近使用时间记在标记上：触碰来源本身会推进它的修改时间，使已校验标记在下次启动失效。
        val now = System.currentTimeMillis()
        val canonicalCache = cache.canonicalFile
        activePaths.forEach { path ->
            File(path).takeIf { file -> file.parentFile?.canonicalFile == canonicalCache }?.let { file ->
                marker(file).takeIf(File::isFile)?.setLastModified(now) ?: file.setLastModified(now)
            }
        }
        fun recency(source: File) = maxOf(source.lastModified(), marker(source).lastModified())
        // 先收集本次全部 base/split；保留当前来源及一份历史来源，不删除尚未交给 DexMeguviz 的 split。
        cache.listFiles()?.filter { it.canonicalPath !in activePaths && it.name.matches(SOURCE_NAME) }
            ?.sortedByDescending(::recency)?.drop(1)?.forEach { it.delete(); marker(it).delete() }
        cache.listFiles()?.filter { it.name.endsWith(VERIFIED_SUFFIX) &&
            !File(cache, it.name.removeSuffix(VERIFIED_SUFFIX)).exists() }?.forEach { it.delete() }
    }

    private const val VERIFIED_SUFFIX = ".ok"
    private const val MAX_STAMP_BYTES = 64L
    private val SOURCE_NAME = Regex("(?:origin-[0-9]+-[0-9]+|view-[a-f0-9]{64})\\.apk")
}
