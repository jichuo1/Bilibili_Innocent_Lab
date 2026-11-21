package com.Bilibili_Innocent_Lab.xposedmodule.contract

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 源码契约（"护栏"）测试的共享底座。
 *
 * 契约测试读取生产源码文本，断言某个真机教训对应的写法仍在：注册顺序、门控条件、
 * 不许复活的旧实现等。它们与行为测试分开放在 `src/contractTest/java`，由 Gradle 属性
 * `-PinnocentLab.testLayer=contract` 单独运行（见 `app/build.gradle.kts`）。
 *
 * ### 为什么统一走这里
 *
 * 2026-09-24 审计：60 个契约文件里有 42 份各自实现的读源码函数，以及 314 处
 * 不带兜底参数的 `substringAfter/substringBefore`。后者在锚点找不到时**返回原串**，
 * 窗口悄悄扩大成整份文件，`contains` 照样通过——代码一搬家，护栏就静默失效，测试还是绿的。
 * 这里的 [after]/[before] 在锚点缺失时直接失败，并把锚点写进失败信息。
 *
 * 读入的源码统一把 CRLF 换成 LF：本地 Windows 工作副本与 Linux CI 的换行不同，
 * 带 `\n` 的多行锚点在两边必须同样成立。
 */
object SourceContract {
    const val JAVA_ROOT = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule"
    private val readCache = ConcurrentHashMap<String, String>()

    /**
     * [path] 可以是以 `src/` 开头的模块内路径，也可以是相对 [JAVA_ROOT] 的路径
     * （如 `ui/skin/liquid/LiquidActivityRenderer.kt`）。Gradle 与 IDE 的工作目录分别是
     * `app/` 与工程根，两种都能找到。
     */
    fun file(path: String): File {
        val candidates = if (path.startsWith("src/")) listOf(path, "app/$path")
        else listOf("$JAVA_ROOT/$path", "app/$JAVA_ROOT/$path")
        return candidates.map(::File).firstOrNull(File::isFile)
            ?: throw AssertionError("contract source not found: $path (cwd ${File(".").absolutePath})")
    }

    fun read(path: String): String = readCache.computeIfAbsent(path) { normalize(file(it).readText()) }

    fun normalize(text: String): String = text.replace("\r\n", "\n")
}

/** 同 [String.substringAfter]，但锚点缺失时失败而不是返回原串。 */
fun String.after(anchor: String): String {
    val index = indexOf(anchor)
    if (index < 0) throw AssertionError("contract anchor not found (after): \"$anchor\"")
    return substring(index + anchor.length)
}

/** 同 [String.substringBefore]，但锚点缺失时失败而不是返回原串。 */
fun String.before(anchor: String): String {
    val index = indexOf(anchor)
    if (index < 0) throw AssertionError("contract anchor not found (before): \"$anchor\"")
    return substring(0, index)
}

/**
 * **有意**截到末尾：锚点存在时同 [before]，不存在时返回全部。只用于"最后一段后面本来就没有
 * 下一个分隔符"的场景，调用处要写明理由；其余一律用会失败的 [before]。
 */
fun String.beforeOrRest(anchor: String): String {
    val index = indexOf(anchor)
    return if (index < 0) this else substring(0, index)
}

/** 同 [String.substringAfterLast]，但锚点缺失时失败。 */
fun String.afterLast(anchor: String): String {
    val index = lastIndexOf(anchor)
    if (index < 0) throw AssertionError("contract anchor not found (afterLast): \"$anchor\"")
    return substring(index + anchor.length)
}

/** 同 [String.substringBeforeLast]，但锚点缺失时失败。 */
fun String.beforeLast(anchor: String): String {
    val index = lastIndexOf(anchor)
    if (index < 0) throw AssertionError("contract anchor not found (beforeLast): \"$anchor\"")
    return substring(0, index)
}
