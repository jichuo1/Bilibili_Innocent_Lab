package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

/** 兼容已有结果缓存：每条概括只在开头保留一个代表性定位，不再暴露相邻尾注。 */
internal object InsightSummaryText {
    fun leadingTimes(text: String): String = text.split("\n\n").joinToString("\n\n") { paragraph ->
        val trimmed = paragraph.trim()
        val references = InsightTimestamp.pattern.findAll(trimmed).toList()
        val prose = InsightTimestamp.pattern.replace(trimmed, "").replace(Regex("[ \t]{2,}"), " ").trim()
        if (trimmed.startsWith("•") && references.isNotEmpty()) {
            val second = references.minOf { it.groupValues[1].toInt() }
            "• [[t:$second]] ${prose.removePrefix("•").trim()}"
        } else prose
    }
}
