package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import java.util.Locale

internal data class SettingsSearchItem(
    val key: String,
    val title: String,
    val detail: String,
    val section: String
)

/** 纯文本匹配层；索引来自当前已渲染、已本地化的设置控件，不复制业务配置目录。 */
internal object SettingsSearchMatcher {
    fun search(
        query: String,
        items: List<SettingsSearchItem>,
        limit: Int = 40
    ): List<SettingsSearchItem> {
        val tokens = normalize(query).split(Regex("\\s+")).filter(String::isNotBlank)
        if (tokens.isEmpty() || limit <= 0) return emptyList()

        return items.asSequence()
            .distinctBy { normalize(it.title) }
            .mapNotNull { item ->
                val title = normalize(item.title)
                val haystack = normalize("${item.title} ${item.detail} ${item.section}")
                if (tokens.all(haystack::contains)) {
                    val score = when {
                        title == tokens.joinToString(" ") -> 0
                        tokens.all(title::contains) -> 1
                        else -> 2
                    }
                    score to item
                } else {
                    null
                }
            }
            .sortedWith(compareBy<Pair<Int, SettingsSearchItem>> { it.first }
                .thenBy { it.second.title.length })
            .map(Pair<Int, SettingsSearchItem>::second)
            .take(limit)
            .toList()
    }

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
