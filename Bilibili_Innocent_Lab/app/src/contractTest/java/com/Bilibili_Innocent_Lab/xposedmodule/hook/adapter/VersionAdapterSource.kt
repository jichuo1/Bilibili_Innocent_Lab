package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.contract.KotlinSourceScanner
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import java.io.File

/**
 * VersionAdapter 与 `hook/adapter` 跨文件源码扫描器。
 *
 * 扫描范围固定为：
 * 1. `hook/VersionAdapter.kt`
 * 2. `hook/adapter` 根目录下的 `.kt`（不含 `hook/adapter/dex` 后端引擎）
 * 3. `hook/adapter/locators` 子树下的 `.kt`
 *
 * 与 [SettingsUiSource] / [HookEntrySource] 共用 [KotlinSourceScanner]，禁止退化成全文件 substring 兜底。
 */
internal object VersionAdapterSource {

    private val hookDir: File by lazy {
        SourceContract.file("hook/VersionAdapter.kt").parentFile
            ?: error("cannot locate hook directory")
    }

    private val adapterDir: File by lazy {
        File(hookDir, "adapter").also {
            require(it.isDirectory) { "missing hook/adapter directory: ${it.absolutePath}" }
        }
    }

    val directAdapterPaths: List<String> by lazy {
        adapterDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .map { "hook/adapter/${it.name}" }
            .sorted()
    }

    val locatorPaths: List<String> by lazy {
        val dir = File(adapterDir, "locators")
        if (!dir.isDirectory) emptyList()
        else dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .map { "hook/adapter/locators/${it.relativeTo(dir).invariantSeparatorsPath}" }
            .sorted()
            .toList()
    }

    val scannedPaths: List<String> by lazy {
        (listOf("hook/VersionAdapter.kt") + directAdapterPaths + locatorPaths).distinct()
    }

    private val adapterFilesCache: List<Pair<String, String>> by lazy {
        scannedPaths.map { it to SourceContract.read(it) }
    }

    private val locatorFilesCache: List<Pair<String, String>> by lazy {
        locatorPaths.map { it to SourceContract.read(it) }
    }

    fun versionAdapter(): String = SourceContract.read("hook/VersionAdapter.kt")

    fun file(relativePath: String): String {
        val normalized = relativePath.trimStart('/')
        val resolved = when {
            normalized.startsWith("hook/") || normalized.startsWith("src/") -> normalized
            normalized.endsWith(".kt") -> "hook/adapter/$normalized"
            else -> "hook/adapter/$normalized.kt"
        }
        return SourceContract.read(resolved)
    }

    fun adapterFiles(): List<Pair<String, String>> = adapterFilesCache

    fun locatorFiles(): List<Pair<String, String>> = locatorFilesCache

    fun all(): String = adapterFiles().joinToString("\n") { it.second }

    fun code(source: String): String = KotlinSourceScanner.code(source)

    fun masked(source: String): String = KotlinSourceScanner.masked(source)

    fun functions(source: String, name: String): List<String> = KotlinSourceScanner.functions(source, name)

    fun properties(source: String, name: String): List<String> = KotlinSourceScanner.properties(source, name)

    fun declaredFunctions(source: String, indent: Int): List<Pair<String, String>> =
        KotlinSourceScanner.declaredFunctions(source, indent)

    fun nonNestedFunctions(source: String): List<Pair<String, String>> =
        KotlinSourceScanner.nonNestedFunctions(source)

    fun function(
        name: String,
        files: List<Pair<String, String>> = adapterFiles(),
        predicate: ((String) -> Boolean)? = null
    ): String = KotlinSourceScanner.function(files, name, "VersionAdapter & hook/adapter", predicate)

    fun property(
        name: String,
        files: List<Pair<String, String>> = adapterFiles(),
        predicate: ((String) -> Boolean)? = null
    ): String = KotlinSourceScanner.property(files, name, "VersionAdapter & hook/adapter", predicate)

    /**
     * 防止拆出的定位器、编解码、校验、合并或缓存模块逃出 `hook/adapter` 与 `hook/adapter/locators/`。
     */
    fun unscannedAdapterFiles(): List<String> {
        val allowedSubDirs = setOf("dex", "locators")
        val offenders = mutableListOf<String>()
        adapterDir.listFiles().orEmpty().forEach { entry ->
            if (entry.isDirectory && entry.name !in allowedSubDirs) {
                entry.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { kt ->
                    offenders += "hook/adapter/${kt.relativeTo(adapterDir).invariantSeparatorsPath}"
                }
            }
        }
        val adapterSymbolPattern = Regex(
            """\b(?:AdaptCacheStore|AdaptResultCodec|AdaptResultValidator|AdaptResultMerger|ProtocolFingerprintBuilder|DexFeatureAdapter)\b"""
        )
        val versionAdapterExtension = Regex("""(?m)^(?:internal |private |public )*fun VersionAdapter\.""")
        hookDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".kt") && it.name != "VersionAdapter.kt" }
            .forEach { file ->
                val text = code(SourceContract.read("hook/${file.name}"))
                if (versionAdapterExtension.containsMatchIn(text) ||
                    (file.name != "HookEntry.kt" && adapterSymbolPattern.containsMatchIn(text))
                ) {
                    offenders += "hook/${file.name}"
                }
            }
        return offenders.sorted()
    }
}
