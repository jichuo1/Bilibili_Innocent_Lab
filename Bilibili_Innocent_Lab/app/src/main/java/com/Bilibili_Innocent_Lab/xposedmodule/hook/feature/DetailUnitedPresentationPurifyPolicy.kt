package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * United 详情页在进入 View 层前可安全剔除的展示模型。
 *
 * 这里不能按「热搜」「AI IN ALL」等展示文字识别：标题角标和特殊话题单元都由宿主用
 * 富文本/自定义布局渲染，文字会随语言和内容变化。唯一可靠的判据是它们各自携带的跳转 URI。
 */
internal object DetailUnitedPresentationPurifyPolicy {

    const val ID = "detail_united_presentation_purify"
    const val HOT_BADGE_CAPABILITY_ID = "detail_hot_badge_hidden"
    const val SPECIAL_TOPIC_CAPABILITY_ID = "detail_united_special_topic_hidden"

    const val HEADLINE_SERVICE_CLASS =
        "com.bilibili.ship.theseus.ugc.intro.ugcheadline.UgcHeadlineService"
    const val RUNNING_UI_COMPONENT_CLASS = "com.bilibili.app.gemini.ui.RunningUIComponent"
    /**
     * 已逐版核对过的 SpecialTag → TagsData 映射候选；具体方法仍须结构唯一。
     *
     * ### ⚠️ 这是**次级**保底，不是主路径
     *
     * 这两个类名是 R8 重命名过的（`.f` / `.j`）。硬编码混淆名违反"运行期动态定位"
     * 那条纪律，这里是有意的例外，因为 2026-09-11 把 24 个存档宿主扫完之后确认
     * **没有更稳的锚点可用**：
     *
     * - 整个 `...intro.module.tags` 包里，除这两个类之外**没有**任何类带
     *   `static X(SpecialTag)` 且返回 TagsData 形状的方法（24 版全部为 0），
     *   所以硬编码列表目前是**完整**的，不是碰巧撞上；
     * - 但 Java 没有"列举包内类"的 API，运行期做包扫描要读 APK 的 dex——
     *   这条回调路径明确不做 DEX 扫描，所以按形状扫包不可行；
     * - 未混淆的 `UgcTagsService` 在 24 版里**都没有**参数含 `SpecialTag`
     *   或返回 TagsData 形状的方法，不能拿它当锚点。
     *
     * 因此代价被限制在"向前脆弱"：将来宿主只把 `j` 改名成别的字母，
     * 这一层就静默降级。可以接受的原因是**主路径已经不依赖它**——
     * [DetailUnitedModulePurifyPolicy.TOPIC_TAGS] 按 `ModuleType.SPECIALTAG`
     * 在协议层整模块删除，24 版全部可用且不含任何混淆名。
     * 这一层只是在协议层被绕过时多兜一道（有测试钉住这个主次关系）。
     */
    val specialTagMapperClasses = listOf(
        "com.bilibili.ship.theseus.united.page.intro.module.tags.f",
        "com.bilibili.ship.theseus.united.page.intro.module.tags.j"
    )
    const val SPECIAL_TAG_CLASS = "com.bapis.bilibili.app.viewunite.common.SpecialTag"

    private const val SEARCH_SCHEME = "bilibili"
    private const val SEARCH_HOST = "search"
    private const val HOT_SEARCH_SOURCE_QUERY = "from"
    private const val HOT_SEARCH_SOURCE_VALUE = "apphotword_search_huangtiao"

    /**
     * 标题角标：**按类别判别**——label 的跳转是不是"跳到搜索"。
     *
     * ### 为什么是类别而不是某个 from 值
     *
     * 2026-09-11 实测「热搜」与「活动」两种角标是**完全相同的协议结构**
     * （icon + `icon_name` + 空 `icon_id` + 标题 + search 跳转），
     * 只有 `icon_name` 的文案和 `from` 的来源标记不同：
     *
     * | 角标 | from |
     * | --- | --- |
     * | 热搜 | `apphotword_search_huangtiao` |
     * | 活动 | `app_comment_topic_search` |
     *
     * `icon_name` 是可变运营文案（今天「热搜」「活动」，明天任何词），
     * 按文案或按某个 `from` 值判都必然漏网。所以判据收在**结构**上：
     * **scheme 是 bilibili、host 是 search** ⇒ 这是个"点了跳去搜索"的推广角标。
     * 一个变体一个开关是不可维护的，这正是"按类别"的工程含义。
     *
     * ### 为什么不会误伤正常标题标识
     *
     * 「互动视频」这类正常徽标跳的是互动视频本身，不是搜索页；
     * 真正的标题图标通常带 `icon_id` 或根本没有跳转。
     * 判据只认"跳搜索"，所以正常标识不在范围内。
     *
     * ⚠️ 仍然只接受能解析出 scheme/host 的 URI；`keyword` 里恰好出现
     * "search" 之类的字样不会命中（判的是 host，不是子串）。
     */
    fun isSearchJumpLabelUri(uri: String?): Boolean {
        val parts = parse(uri) ?: return false
        return parts.scheme.equals(SEARCH_SCHEME, ignoreCase = true) &&
            parts.host.equals(SEARCH_HOST, ignoreCase = true)
    }

    /**
     * 「热搜」那一种的精确来源。
     *
     * **不再作为屏蔽判据**（已被 [isSearchJumpLabelUri] 的类别判据取代），
     * 保留是为了把"当初是按哪条抓包证据落地的"钉在代码里，并供测试对比
     * "精确值命中" 与 "同类别其它 from 也命中" 两种情形。
     */
    fun isHotSearchLabelUri(uri: String?): Boolean {
        if (!isSearchJumpLabelUri(uri)) return false
        val parts = parse(uri) ?: return false
        return parts.queryValues(HOT_SEARCH_SOURCE_QUERY)
            .any { it == HOT_SEARCH_SOURCE_VALUE }
    }

    /**
     * United `SpecialTag` 的话题单元。
     *
     * 当前实证值是 `https://m.bilibili.com/topic-detail?...`；限定 Bilibili 域名和完整路径，
     * 不把普通文本、搜索链接或 query 参数里偶然出现的 `topic-detail` 当成话题组件。
     */
    fun isSpecialTopicUri(uri: String?): Boolean {
        val parts = parse(uri) ?: return false
        if (parts.scheme.lowercase() !in setOf("http", "https")) return false
        val host = parts.host.lowercase()
        if (host != "bilibili.com" && !host.endsWith(".bilibili.com")) return false
        return parts.path == "/topic-detail"
    }

    private data class UriParts(
        val scheme: String,
        val host: String,
        val path: String,
        val rawQuery: String?
    ) {
        fun queryValues(name: String): Sequence<String> = rawQuery
            ?.split('&')
            ?.asSequence()
            ?.mapNotNull { part ->
                val separator = part.indexOf('=')
                val rawName = if (separator >= 0) part.substring(0, separator) else part
                val rawValue = if (separator >= 0) part.substring(separator + 1) else ""
                val decodedName = decode(rawName) ?: return@mapNotNull null
                if (decodedName != name) return@mapNotNull null
                decode(rawValue)
            }
            ?: emptySequence()
    }

    /**
     * 不使用 [java.net.URI]：宿主的 bilibili URI 可能含未转义的展示关键词，严格 URI 解析会
     * 因空格直接失败。这里只解析本功能必须验证的 scheme / host / path / query 四段。
     */
    private fun parse(source: String?): UriParts? {
        val value = source?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val schemeEnd = value.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = value.substring(0, schemeEnd)
        val authorityStart = schemeEnd + 3
        val fragmentStart = value.indexOf('#', authorityStart).let { index ->
            if (index >= 0) index else value.length
        }
        val queryStart = value.indexOf('?', authorityStart).takeIf { it in authorityStart until fragmentStart }
        val authorityAndPathEnd = queryStart ?: fragmentStart
        val authorityAndPath = value.substring(authorityStart, authorityAndPathEnd)
        val slash = authorityAndPath.indexOf('/')
        val rawAuthority = if (slash >= 0) authorityAndPath.substring(0, slash) else authorityAndPath
        if (rawAuthority.isBlank() || '@' in rawAuthority) return null
        val host = rawAuthority.substringBefore(':')
        if (host.isBlank()) return null
        val path = if (slash >= 0) authorityAndPath.substring(slash) else ""
        val rawQuery = queryStart?.let { start ->
            value.substring(start + 1, fragmentStart)
        }
        return UriParts(scheme, host, path, rawQuery)
    }

    private fun decode(value: String): String? = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrNull()
}
