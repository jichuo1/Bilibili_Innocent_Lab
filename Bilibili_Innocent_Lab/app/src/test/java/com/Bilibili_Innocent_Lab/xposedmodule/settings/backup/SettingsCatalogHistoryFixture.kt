package com.Bilibili_Innocent_Lab.xposedmodule.settings.backup

/**
 * Shared reader for the deduplicated monotonic snapshot table `settings-backup/catalog-history.tsv`.
 */
internal object SettingsCatalogHistoryFixture {
    private val parsed: Pair<List<Int>, List<Pair<String, Int>>> by lazy {
        val stream = requireNotNull(
            SettingsCatalogHistoryFixture::class.java.classLoader
                ?.getResourceAsStream("settings-backup/catalog-history.tsv")
        ) { "Missing settings-backup/catalog-history.tsv resource" }
        val versions = mutableListOf<Int>()
        val rows = mutableListOf<Pair<String, Int>>()
        stream.bufferedReader().useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEach
                if (line.startsWith("# versions=")) {
                    versions.addAll(
                        line.removePrefix("# versions=").split(',')
                            .filter(String::isNotBlank)
                            .map(String::toInt)
                    )
                } else if (!line.startsWith("#")) {
                    val tab = line.indexOf('\t')
                    require(tab > 0) { "Malformed catalog-history row: $line" }
                    rows += line.substring(0, tab) to line.substring(tab + 1).toInt()
                }
            }
        }
        versions.toList() to rows.toList()
    }

    fun versions(): List<Int> = parsed.first

    fun idsAtVersion(version: Int): List<String> =
        parsed.second.asSequence().filter { it.second <= version }.map { it.first }.sorted().toList()
}
