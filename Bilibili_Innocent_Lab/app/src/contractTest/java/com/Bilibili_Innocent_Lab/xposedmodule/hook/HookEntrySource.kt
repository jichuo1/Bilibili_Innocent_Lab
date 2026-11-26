package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.contract.KotlinSourceScanner
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import java.io.File

/**
 * Hook 入口与功能外移分卷源码扫描器。
 *
 * 覆盖 `hook/HookEntry.kt`、`hook/feature/FeatureInstaller.kt`、`hook/feature/freecopy` 下分卷、
 * `hook/feature/CommentTopologyFeatureInstaller.kt` 与 `hook/feature/comment` 下分卷
 * （以及可选的 `hook/feature/MossRpcChannel.kt`）。
 *
 * 所有函数/属性切片与 [SettingsUiSource] 共用 [KotlinSourceScanner] 的花括号深度配对与单趟词法扫描，
 * 找不到或重名时直接失败，禁止退化成全文件子串断言。
 */
internal object HookEntrySource {

    private val hookDir: File by lazy {
        SourceContract.file("hook/HookEntry.kt").parentFile
            ?: error("cannot locate hook directory")
    }

    private val featureDir: File by lazy {
        File(hookDir, "feature").also {
            require(it.isDirectory) { "missing hook/feature directory: ${it.absolutePath}" }
        }
    }

    private fun collectRelativeKt(subDirName: String): List<String> {
        val dir = File(featureDir, subDirName)
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .map { file ->
                val rel = file.relativeTo(hookDir).invariantSeparatorsPath
                "hook/$rel"
            }
            .sorted()
            .toList()
    }

    val freeCopyExtractedPaths: List<String> by lazy { collectRelativeKt("freecopy") }
    val commentExtractedPaths: List<String> by lazy { collectRelativeKt("comment") }

    val scannedPaths: List<String> by lazy {
        buildList {
            add("hook/HookEntry.kt")
            add("hook/feature/FeatureInstaller.kt")
            if (File(featureDir, "MossRpcChannel.kt").isFile) {
                add("hook/feature/MossRpcChannel.kt")
            }
            addAll(freeCopyExtractedPaths)
            add("hook/feature/CommentTopologyFeatureInstaller.kt")
            addAll(commentExtractedPaths)
        }.distinct()
    }

    private val scannedFilesCache: List<Pair<String, String>> by lazy {
        scannedPaths.map { it to SourceContract.read(it) }
    }

    private val freeCopyScopeFilesCache: List<Pair<String, String>> by lazy {
        (listOf("hook/HookEntry.kt") + freeCopyExtractedPaths).distinct().map { it to SourceContract.read(it) }
    }

    private val commentTopologyFilesCache: List<Pair<String, String>> by lazy {
        (listOf("hook/feature/CommentTopologyFeatureInstaller.kt") + commentExtractedPaths)
            .distinct()
            .map { it to SourceContract.read(it) }
    }

    fun hookEntry(): String = SourceContract.read("hook/HookEntry.kt")

    fun featureInstaller(): String = SourceContract.read("hook/feature/FeatureInstaller.kt")

    fun file(relativePath: String): String {
        val normalized = relativePath.trimStart('/')
        val resolved = if (normalized.startsWith("hook/") || normalized.startsWith("src/")) normalized else "hook/$normalized"
        return SourceContract.read(resolved)
    }

    fun hookEntryFiles(): List<Pair<String, String>> = scannedFilesCache

    fun freeCopyScopeFiles(): List<Pair<String, String>> = freeCopyScopeFilesCache

    fun commentTopologyFiles(): List<Pair<String, String>> = commentTopologyFilesCache

    fun all(): String = hookEntryFiles().joinToString("\n") { it.second }

    fun freeCopyAll(): String = freeCopyScopeFiles().joinToString("\n") { it.second }

    fun commentTopologyAll(): String = commentTopologyFiles().joinToString("\n") { it.second }

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
        files: List<Pair<String, String>> = hookEntryFiles(),
        predicate: ((String) -> Boolean)? = null
    ): String = KotlinSourceScanner.function(files, name, "hook entry & extracted feature volumes", predicate)

    fun property(
        name: String,
        files: List<Pair<String, String>> = hookEntryFiles(),
        predicate: ((String) -> Boolean)? = null
    ): String = KotlinSourceScanner.property(files, name, "hook entry & extracted feature volumes", predicate)

    /**
     * 防止拆出的 FreeCopy 或 ReplyTopology 逻辑逃出扫描集：
     * - 检查 `hook/feature/` 下未纳管子目录或独立文件中是否包含 FreeCopy 触摸/绑定核心实现符号或 Topology 分卷；
     * - 检查 `hook/` 顶层是否出现未纳管的 `*FreeCopy*.kt` / `*CommentTopology*.kt` 新文件。
     */
    fun unscannedFeatureExtractions(): List<String> {
        val allowedTopFeatureDirs = setOf(
            "audio", "comment", "danmakuwire", "freecopy", "insight", "interpolation", "privacy", "sponsor"
        )
        val freeCopyImplMarkers = listOf(
            "private val freeCopyLongPressRunnable",
            "internal val freeCopyLongPressRunnable",
            "private val sharedFreeCopyListener",
            "internal val sharedFreeCopyListener",
            "fun finishDescLikeTouch(",
            "fun publishFreeCopyCapabilities(",
            "fun drainCommentBinds("
        )
        val topologyMarkers = listOf(
            "class ReplyTopologySeedBridge",
            "object ReplyTopologySeedBridge",
            "class ReplyTopologyAnchorBinder",
            "class ReplyTopologyPager",
            "object ReplyTopologyPager",
            "class ReplyTopologyCoordinator",
            "object MossRpcChannel"
        )
        val offenders = mutableListOf<String>()
        featureDir.listFiles().orEmpty().forEach { entry ->
            if (entry.isDirectory && entry.name !in allowedTopFeatureDirs) {
                entry.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { kt ->
                    offenders += "hook/feature/${kt.relativeTo(featureDir).invariantSeparatorsPath}"
                }
            } else if (entry.isFile && entry.name.endsWith(".kt")) {
                val rel = "hook/feature/${entry.name}"
                if (rel !in scannedPaths) {
                    val text = code(SourceContract.read(rel))
                    if (freeCopyImplMarkers.any(text::contains) || topologyMarkers.any(text::contains)) {
                        offenders += rel
                    }
                }
            }
        }
        val knownTopHookFiles = setOf(
            "CommentEmojiAdapter.kt",
            "CommentTextIdentity.kt",
            "FreeCopySelectionMapper.kt",
            "HookEntry.kt",
            "HookPointRegistry.kt",
            "RoamingCompatHook.kt",
            "StartupAdapterPlan.kt",
            "VersionAdapter.kt",
            "VersionAdapterContract.kt"
        )
        hookDir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".kt") && it.name !in knownTopHookFiles }
            .forEach { offenders += "hook/${it.name}" }
        return offenders.sorted()
    }
}
