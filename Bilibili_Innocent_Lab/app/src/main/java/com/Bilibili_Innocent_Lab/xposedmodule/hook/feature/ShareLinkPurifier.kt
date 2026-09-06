package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.util.Locale

/**
 * 分享链接净化的纯字符串层：不依赖 `android.net.Uri`，可直接在 JVM 单测里锁行为。
 *
 * 采用**白名单**而不是黑名单：只保留播放定位相关的少数查询参数，其余一律丢弃。追踪参数
 * 每隔几个版本就会新增一批（`share_source`/`bbid`/`spmid`/`vd_source`…），黑名单注定漏。
 *
 * 刻意不做的事：**不解析 b23.tv 短链的 302 跳转**。那需要在 `getLink()`/`getContent()` 回调里
 * 发一次同步网络请求，而这两个回调可以落在主线程上，等于把 ANR 风险塞进分享面板。短链本身
 * 的查询串仍会被清掉，隐私收益的主要部分已经拿到。
 */
internal object ShareLinkPurifier {

    /** 需要净化的站点；其它域名一律原样保留，不替用户改写第三方链接。 */
    private val PURIFIED_HOSTS = listOf(
        "bilibili.com",
        "b23.tv",
        "bili2233.cn",
        "bilibili.tv"
    )

    /** 播放定位参数：分 P 与时间点。丢了它们分享出去的链接会跳错位置。 */
    private const val PARAM_PAGE = "p"
    private const val PARAM_TIME = "t"

    /** 宿主用毫秒表达的进度参数；换算成秒后并入 [PARAM_TIME]。 */
    private const val PARAM_START_PROGRESS = "start_progress"

    private const val MILLIS_PER_SECOND = 1000L

    /** 分享文案里的 URL；到中英文标点或空白为止，避免把后面的句子吃进来。 */
    private val URL_PATTERN = Regex("""https?://[^\s，。！？、；：）】》"'<>]+""")

    /**
     * 净化单条链接。
     *
     * @return 净化后的链接；不需要改动（非目标站点、本来就没有多余参数）时返回 null。
     */
    fun purifyUrl(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_URL_LENGTH) return null
        val schemeEnd = trimmed.indexOf("://").takeIf { it > 0 } ?: return null
        val scheme = trimmed.substring(0, schemeEnd).lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null

        val authorityStart = schemeEnd + 3
        val pathStart = trimmed.indexOfFirst(authorityStart) { it == '/' || it == '?' || it == '#' }
            .takeIf { it >= 0 } ?: trimmed.length
        val authority = trimmed.substring(authorityStart, pathStart)
        if (!isPurifiedHost(authority)) return null

        val fragmentStart = trimmed.indexOf('#', pathStart).takeIf { it >= 0 } ?: trimmed.length
        val fragment = trimmed.substring(fragmentStart)
        val queryStart = trimmed.indexOf('?', pathStart).takeIf { it in pathStart until fragmentStart }
        val path = trimmed.substring(pathStart, queryStart ?: fragmentStart)
        val query = queryStart?.let { trimmed.substring(it + 1, fragmentStart) }.orEmpty()

        val retained = retainQuery(query)
        val rebuilt = buildString {
            append(scheme)
            append("://")
            append(authority)
            append(path)
            if (retained.isNotEmpty()) {
                append('?')
                append(retained)
            }
            append(fragment)
        }
        return rebuilt.takeIf { it != trimmed }
    }

    /**
     * 净化分享文案里出现的每一条链接。
     *
     * @return 净化后的文案；没有任何链接被改动时返回 null，调用方据此完全跳过写回。
     */
    fun purifyText(text: String): String? {
        if (text.isEmpty() || text.length > MAX_TEXT_LENGTH) return null
        var changed = false
        val purified = URL_PATTERN.replace(text) { match ->
            val replacement = purifyUrl(match.value)
            if (replacement == null) {
                match.value
            } else {
                changed = true
                replacement
            }
        }
        return purified.takeIf { changed }
    }

    /** 只保留白名单参数，并把毫秒进度折算成秒；重复出现时以第一次为准。 */
    private fun retainQuery(query: String): String {
        if (query.isEmpty()) return ""
        var page: String? = null
        var time: String? = null
        query.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val separator = pair.indexOf('=')
            if (separator <= 0 || separator == pair.length - 1) return@forEach
            val name = pair.substring(0, separator)
            val value = pair.substring(separator + 1)
            when (name) {
                PARAM_PAGE -> if (page == null && value.isNumeric()) page = value
                PARAM_TIME -> if (time == null && value.isNumeric()) time = value
                PARAM_START_PROGRESS -> if (time == null) {
                    value.toLongOrNull()
                        ?.takeIf { it >= 0L }
                        ?.let { time = (it / MILLIS_PER_SECOND).toString() }
                }
            }
        }
        return buildList {
            page?.let { add("$PARAM_PAGE=$it") }
            time?.let { add("$PARAM_TIME=$it") }
        }.joinToString("&")
    }

    /** 匹配站点及其任意子域，不做后缀包含判断，`notbilibili.com` 不会被误认。 */
    private fun isPurifiedHost(authority: String): Boolean {
        val host = authority.substringBefore(':').lowercase(Locale.ROOT).trimEnd('.')
        if (host.isEmpty()) return false
        return PURIFIED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun String.isNumeric(): Boolean = isNotEmpty() && all(Char::isDigit)

    private inline fun String.indexOfFirst(startIndex: Int, predicate: (Char) -> Boolean): Int {
        for (index in startIndex until length) {
            if (predicate(this[index])) return index
        }
        return -1
    }

    private const val MAX_URL_LENGTH = 4_096
    private const val MAX_TEXT_LENGTH = 16_384
}
