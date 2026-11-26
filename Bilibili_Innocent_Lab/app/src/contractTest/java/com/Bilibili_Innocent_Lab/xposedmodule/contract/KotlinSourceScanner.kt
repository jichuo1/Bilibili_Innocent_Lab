package com.Bilibili_Innocent_Lab.xposedmodule.contract

import java.util.concurrent.ConcurrentHashMap

/**
 * 源码契约共用的 Kotlin 词法扫描与函数/属性边界切片器。
 *
 * 与 [SettingsUiSource] 使用同一套单趟词法状态机与跨行括号深度追踪：
 * 1. 注释与字符串在同一趟扫描中识别，避免 URL 字符串里的 `//` 被误判成行注释；
 * 2. 所有结果与原文逐字符下标对齐，可在剥除副本上算结构边界、回原文切出含字面量的片段；
 * 3. `function` / `property` 命中必须唯一，缺失或歧义直接失败，禁止退化成整份文件子串。
 */
internal object KotlinSourceScanner {

    private val CONTINUATIONS = listOf("=", "->", "+", ",", ".", ":", "&&", "||", "?:", "?")
    private val MODIFIERS = "(?:private |internal |public |protected |override |open |inline |suspend |final |operator |infix |tailrec |external )*"

    private val codeCache = ConcurrentHashMap<String, String>()
    private val maskCache = ConcurrentHashMap<String, String>()

    fun code(source: String): String {
        val normalized = SourceContract.normalize(source)
        return codeCache.computeIfAbsent(normalized) { scan(it, blankStrings = false) }
    }

    fun masked(source: String): String {
        val normalized = SourceContract.normalize(source)
        return maskCache.computeIfAbsent(normalized) { scan(it, blankStrings = true) }
    }

    fun functions(source: String, name: String): List<String> {
        val normalized = SourceContract.normalize(source)
        val masked = masked(normalized)
        val pattern = Regex(
            """\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.<>?,\s]*\.)?""" +
                Regex.escape(name) + """\s*\("""
        )
        return pattern.findAll(masked).mapNotNull { match ->
            declarationEnd(masked, match.range.first, requireBinding = false)?.let { end ->
                normalized.substring(match.range.first, end)
            }
        }.toList()
    }

    fun properties(source: String, name: String): List<String> {
        val normalized = SourceContract.normalize(source)
        val masked = masked(normalized)
        val pattern = Regex(
            """\b(?:val|var)\s+""" + Regex.escape(name) + """(?=\s*(?:[:=]|by\b))"""
        )
        return pattern.findAll(masked).mapNotNull { match ->
            declarationEnd(masked, match.range.first, requireBinding = true)?.let { end ->
                normalized.substring(match.range.first, end)
            }
        }.toList()
    }

    fun declaredFunctions(source: String, indent: Int): List<Pair<String, String>> {
        val normalized = SourceContract.normalize(source)
        val masked = masked(normalized)
        val pattern = Regex(
            "(?m)^ {" + indent + "}" + MODIFIERS +
                """fun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.<>?,\s]*\.)?(\w+)\s*\("""
        )
        return pattern.findAll(masked).mapNotNull { match ->
            declarationEnd(masked, match.range.first, requireBinding = false)?.let { end ->
                match.groupValues[1] to normalized.substring(match.range.first, end)
            }
        }.toList()
    }

    /**
     * 跨任意缩进扫描声明函数，自动跳过嵌套在已有函数体内部的局部 `fun`。
     * 适用于同时含类成员（缩进 4）、companion 成员（缩进 8）与顶层函数（缩进 0）的源码集合。
     */
    fun nonNestedFunctions(source: String): List<Pair<String, String>> {
        val normalized = SourceContract.normalize(source)
        val masked = masked(normalized)
        val pattern = Regex(
            "(?m)^[ \\t]*" + MODIFIERS +
                """fun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.<>?,\s]*\.)?(\w+)\s*\("""
        )
        val result = mutableListOf<Pair<String, String>>()
        var enclosedEnd = -1
        for (match in pattern.findAll(masked)) {
            if (match.range.first < enclosedEnd) continue
            val end = declarationEnd(masked, match.range.first, requireBinding = false) ?: continue
            result += match.groupValues[1] to normalized.substring(match.range.first, end)
            enclosedEnd = end
        }
        return result
    }

    fun function(
        files: List<Pair<String, String>>,
        name: String,
        scopeDescription: String,
        predicate: ((String) -> Boolean)? = null
    ): String {
        val allHits = files.flatMap { (file, text) ->
            functions(text, name).map { file to it }
        }
        val hits = if (predicate == null) allHits else allHits.filter { predicate(it.second) }
        check(hits.isNotEmpty()) {
            "function `$name` not found in ${files.map { it.first }} ($scopeDescription)"
        }
        check(hits.size == 1) {
            "function `$name` matched ${hits.size} times (${hits.map { it.first }}); " +
                "provide a predicate or use functions()"
        }
        return hits.single().second
    }

    fun property(
        files: List<Pair<String, String>>,
        name: String,
        scopeDescription: String,
        predicate: ((String) -> Boolean)? = null
    ): String {
        val allHits = files.flatMap { (file, text) ->
            properties(text, name).map { file to it }
        }
        val hits = if (predicate == null) allHits else allHits.filter { predicate(it.second) }
        check(hits.isNotEmpty()) {
            "property `$name` not found in ${files.map { it.first }} ($scopeDescription)"
        }
        check(hits.size == 1) {
            "property `$name` matched ${hits.size} times (${hits.map { it.first }}); " +
                "provide a predicate or use properties()"
        }
        return hits.single().second
    }

    private fun declarationEnd(masked: String, declStart: Int, requireBinding: Boolean): Int? {
        var lineStart = declStart
        var depth = 0
        var firstLine = true
        var sawBinding = !requireBinding
        while (lineStart < masked.length) {
            val newline = masked.indexOf('\n', lineStart)
            val lineEnd = if (newline < 0) masked.length else newline
            val line = masked.substring(lineStart, lineEnd)
            depth += line.count { it == '(' || it == '[' || it == '{' }
            depth -= line.count { it == ')' || it == ']' || it == '}' }
            if (depth < 0) return null
            if (!sawBinding && (line.contains('=') || line.contains('{') || Regex("""\bby\b""").containsMatchIn(line))) {
                sawBinding = true
            }
            val continues = CONTINUATIONS.any { line.trimEnd().endsWith(it) }
            if (!sawBinding && depth == 0 && !continues) {
                val nextIndex = skipWhitespace(masked, lineEnd)
                val nextStartsBinding = nextIndex < masked.length && (
                    masked[nextIndex] == '=' ||
                        masked[nextIndex] == '{' ||
                        masked.startsWith("by", nextIndex) ||
                        masked.startsWith("get", nextIndex)
                    )
                if (!nextStartsBinding) return lineEnd
            }
            val ended = depth == 0 && !continues && sawBinding && (!firstLine || line.contains('='))
            if (ended) return lineEnd
            firstLine = false
            lineStart = lineEnd + 1
        }
        return null
    }

    private fun skipWhitespace(text: String, startIndex: Int): Int {
        var idx = startIndex
        while (idx < text.length && text[idx].isWhitespace()) idx++
        return idx
    }

    private fun scan(source: String, blankStrings: Boolean): String {
        val out = StringBuilder(source.length)
        var i = 0
        fun blankThrough(end: Int) {
            while (i < end && i < source.length) {
                out.append(if (source[i] == '\n') '\n' else ' ')
                i++
            }
        }
        fun copyThrough(end: Int) {
            while (i < end && i < source.length) {
                out.append(source[i])
                i++
            }
        }
        fun skipQuoted(startIndex: Int, quote: Char): Int {
            var j = startIndex + 1
            while (j < source.length && source[j] != quote) {
                if (source[j] == '\\') {
                    j += 2
                } else if (quote == '"' && source.startsWith("\${", j)) {
                    var exprDepth = 1
                    j += 2
                    while (j < source.length && exprDepth > 0) {
                        when {
                            source[j] == '\\' -> j += 2
                            source.startsWith("\"\"\"", j) -> {
                                val rawEnd = source.indexOf("\"\"\"", j + 3)
                                j = if (rawEnd < 0) source.length else rawEnd + 3
                            }
                            source[j] == '"' || source[j] == '\'' -> j = skipQuoted(j, source[j])
                            source[j] == '{' -> { exprDepth++; j++ }
                            source[j] == '}' -> { exprDepth--; j++ }
                            else -> j++
                        }
                    }
                } else {
                    j++
                }
            }
            return minOf(j + 1, source.length)
        }
        while (i < source.length) {
            val c = source[i]
            when {
                c == '/' && source.startsWith("//", i) -> {
                    val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                    blankThrough(end)
                }
                c == '/' && source.startsWith("/*", i) -> {
                    var depth = 0
                    var j = i
                    while (j < source.length) {
                        if (source.startsWith("/*", j)) {
                            depth++
                            j += 2
                        } else if (source.startsWith("*/", j)) {
                            depth--
                            j += 2
                            if (depth == 0) break
                        } else {
                            j++
                        }
                    }
                    blankThrough(if (depth == 0) j else source.length)
                }
                source.startsWith("\"\"\"", i) -> {
                    val end = source.indexOf("\"\"\"", i + 3).let { if (it < 0) source.length else it + 3 }
                    if (blankStrings) blankThrough(end) else copyThrough(end)
                }
                c == '"' || c == '\'' -> {
                    val end = skipQuoted(i, c)
                    if (blankStrings) blankThrough(end) else copyThrough(end)
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
