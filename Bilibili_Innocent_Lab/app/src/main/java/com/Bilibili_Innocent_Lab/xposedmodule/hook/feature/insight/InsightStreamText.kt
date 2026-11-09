package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

/** 将未完成的结构化正文投影为只读预览；不补齐 JSON，也不授予时间跳转或章节写入能力。 */
internal class InsightStreamText(private val mode: InsightMode, private val context: InsightContext) {
    private val raw = StringBuilder()
    private val cues = InsightProtocol.validCues(context)
    fun append(delta: String): String {
        require(raw.length + delta.length <= InsightProtocol.MAX_OUTPUT) { "insight-stream-limit" }
        raw.append(delta)
        val root = Reader(raw.toString()).value()?.value as? Map<*, *> ?: return ""
        fun prose(value: Any?): String = (value as? String)?.let(::safeText).orEmpty()
        return when (mode) {
            InsightMode.ASK -> prose(root["answer"])
            InsightMode.COMMENTS -> (root["topics"] as? List<*>)?.mapNotNull { topic ->
                val fields = topic as? Map<*, *> ?: return@mapNotNull null
                listOf(prose(fields["title"]), prose(fields["summary"])).filter(String::isNotEmpty).joinToString("\n")
            }?.filter(String::isNotEmpty)?.joinToString("\n\n").orEmpty()
            InsightMode.SUMMARY -> {
                val points = if (context.timed) (root["sections"] as? List<*>)?.mapNotNull { section ->
                    val fields = section as? Map<*, *> ?: return@mapNotNull null
                    val index = fields["start_cue"] as? Int ?: return@mapNotNull null
                    val cue = cues.getOrNull(index) ?: return@mapNotNull null
                    val title = prose(fields["title"]); val summary = prose(fields["summary"])
                    if (title.isEmpty() && summary.isEmpty()) return@mapNotNull null
                    val second = if (index == 0 && InsightProtocol.sectioned(context)) 0 else cue.start.toInt()
                    "• ${InsightTimestamp.label(second)} " + listOf(title, summary).filter(String::isNotEmpty).joinToString("：")
                } else (root["points"] as? List<*>)?.map { "• " + prose(it) }
                (listOf(prose(root["overview"])) + points.orEmpty()).filter(String::isNotEmpty).joinToString("\n\n")
            }
            InsightMode.CHAPTERS -> ""
        }
    }
    private fun safeText(input: String): String {
        // 未闭合标记和 Unicode 转义不向用户泄漏；完整标记此时也只是文本，最终验证后才可点击。
        val end = input.indexOf("[[").let { first ->
            var start = first
            while (start >= 0) {
                val close = input.indexOf("]]", start + 2)
                if (close < 0) return@let start
                start = input.indexOf("[[", close + 2)
            }
            input.length
        }
        val text = input.substring(0, end).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        return InsightCommentText.cleanLegacy(InsightTimestamp.pattern.replace(text) { match ->
            val second = match.groupValues[1].toIntOrNull()
            if (context.timed && second != null && second in 0 until context.duration && cues.any {
                second.toDouble() in kotlin.math.floor(it.start)..kotlin.math.ceil(it.end)
            }) InsightTimestamp.label(second) else ""
        })
    }
    private data class Part(val value: Any, val complete: Boolean)
    /** 有界深度的预览读取器；只接受完整字段名，字符串按转义边界解码，完整契约仍由 decode 执行。 */
    private class Reader(private val text: String) {
        private var at = 0
        private fun space() { while (at < text.length && text[at].isWhitespace()) at++ }
        fun value(depth: Int = 0): Part? {
            space()
            if (at >= text.length || depth > 8) return null
            return when (text[at]) {
                '"' -> string()
                '{' -> {
                    at++; space(); val fields = linkedMapOf<String, Any>()
                    if (at < text.length && text[at] == '}') { at++; return Part(fields, true) }
                    while (at < text.length) {
                        space(); if (at >= text.length || text[at] != '"') break
                        val key = string(); if (!key.complete) break
                        space(); if (at >= text.length || text[at++] != ':') break
                        val item = value(depth + 1) ?: break
                        fields[key.value as String] = item.value
                        if (!item.complete) break
                        space(); if (at >= text.length) break
                        when (text[at++]) { '}' -> return Part(fields, true); ',' -> Unit; else -> break }
                    }
                    Part(fields, false)
                }
                '[' -> {
                    at++; space(); val items = mutableListOf<Any>()
                    if (at < text.length && text[at] == ']') { at++; return Part(items, true) }
                    while (at < text.length) {
                        val item = value(depth + 1) ?: break
                        items += item.value
                        if (!item.complete) break
                        space(); if (at >= text.length) break
                        when (text[at++]) { ']' -> return Part(items, true); ',' -> Unit; else -> break }
                    }
                    Part(items, false)
                }
                else -> {
                    val start = at
                    while (at < text.length && text[at] !in ",}] \r\n\t") at++
                    if (at == text.length || start == at) null else text.substring(start, at).toIntOrNull()?.let { Part(it, true) }
                }
            }
        }
        private fun string(): Part {
            at++; val out = StringBuilder()
            while (at < text.length) {
                val char = text[at++]
                if (char == '"') return Part(out.toString(), true)
                if (char != '\\') { if (char < ' ') break; out.append(char); continue }
                if (at == text.length) break
                when (val escaped = text[at++]) {
                    '"', '\\', '/' -> out.append(escaped)
                    'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                    'b' -> out.append('\b'); 'f' -> out.append('\u000c')
                    'u' -> {
                        if (at + 4 > text.length) break
                        val code = text.substring(at, at + 4).toIntOrNull(16) ?: break
                        out.append(code.toChar()); at += 4
                    }
                    else -> break
                }
            }
            return Part(out.toString(), false)
        }
    }
}
