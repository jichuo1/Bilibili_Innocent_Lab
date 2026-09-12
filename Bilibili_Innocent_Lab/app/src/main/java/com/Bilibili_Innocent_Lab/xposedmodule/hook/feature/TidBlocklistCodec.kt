package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 标签黑名单的编解码：同一条串里既可以写标签 id（`args.tid`），也可以写标签名（`args.tname`）。
 *
 * **tid 是标签不是分区**：2026-09-12 实测，宿主把 `args.tid` 写进不感兴趣请求时用的键
 * 是 `tag_id`，`args.rid` 才是分区。类名沿用历史的 `Tid` 字样只是不改设置键的代价，
 * 语义以本注释为准。
 *
 * **为什么同时收名字**：App 里没有任何地方能告诉用户某个标签的 id 是多少
 * （原本指望三点面板点选，但 9.10.0 的面板项不带这个数据），只认 id 等于这个功能
 * 没人填得出名单。名字是用户唯一看得见的东西。
 *
 * **为什么是字符串而不是 `Set<String>`**：授权链只搬 `SettingValue` 的
 * Bool / IntValue / Text 三种形态（见 `RemoteHookConfigContract.resolveSourceValues`），
 * `getStringSet` 的偏好根本过不了 Remote Preferences 那一层。所以和既有的
 * `*_HIDDEN_RULES` / `title_filter.keywords` 一样，存成一条分隔符拼起来的 Text。
 *
 * **为什么不复用 [RuleSetCodec]**：它的 `matches` 做的是子串 contains。用在 id 上
 * `163` 会命中 `1631`；用在名字上"科技"会命中"科技美学"。两种都必须整串相等，
 * 所以 id 走本类、名字走 [ExactRuleSetCodec]（与详情页那档同一套语义）。
 *
 * [parse] / [parseNames] / [normalize] 三者都由同一个 [entries] 推导，
 * 保证"UI 存下去的"和"过滤链读出来的"永远是同一套切分——否则用户填的名字会在
 * 保存那一步被静默吃掉。
 */
internal object TidBlocklistCodec {
    /**
     * 条目分隔符：逗号、分号、换行。**不含空格**——标签名里可以有空格
     * （"东方 Project"），按空白切会把一个标签劈成两半。
     */
    private val separators = Regex("[,，;；\\r\\n]+")

    /** 纯数字片段内部再按空白切：历史上"163 29413"这种写法是能用的，不能让它失效。 */
    private val numericChunk = Regex("^[0-9\\s]+$")
    private val whitespace = Regex("\\s+")

    /**
     * 把原始串切成条目；数字项返回 [Long]，名字项返回归一后的 [String]。
     *
     * **非法项一律丢弃、不抛异常**：备份文件是可以被手改的，一个错字不能让整条
     * 过滤链失效。非正数也丢——宿主用 `tid = 0` 表示"没有标签"（番剧卡就是），
     * 把 0 收进黑名单会把所有无标签卡一起删掉。
     */
    private fun entries(raw: String?): List<Any> {
        if (raw.isNullOrBlank()) return emptyList()
        val result = ArrayList<Any>()
        for (chunk in raw.split(separators)) {
            val token = chunk.trim()
            if (token.isEmpty()) continue
            if (numericChunk.matches(token)) {
                // 全是数字和空白：按空白再切，每段一个 id。
                token.split(whitespace)
                    .mapNotNull(String::toLongOrNull)
                    .filterTo(result) { it > 0L }
            } else {
                // 带符号的数字（"-163"）不是名字，是个非法 id——丢掉，
                // 不能让它变成一个永远命中不了的"标签名"留在名单里。
                if (token.toLongOrNull() == null) result += token.lowercase()
            }
        }
        return result
    }

    /** 解析成标签 id 集合。 */
    fun parse(raw: String?): Set<Long> =
        entries(raw).filterIsInstance<Long>().toCollection(LinkedHashSet())

    /**
     * 解析成标签**名**集合，归一方式与 [ExactRuleSetCodec.parse] 一致（整串小写）。
     *
     * 代价：一个名字**恰好全是数字**的标签会被当成 id。这种标签基本不存在，
     * 真遇上了也只是它按 id 比，不会误删别的卡。
     */
    fun parseNames(raw: String?): Set<String> =
        entries(raw).filterIsInstance<String>().toCollection(LinkedHashSet())

    /**
     * 是否命中黑名单（按 id）。
     *
     * `tid == null` 或非正数一律**放行**：番剧卡、广告卡、部分直播卡本来就没有标签，
     * 把"读不到"当成"未知标签"删掉会误伤一大片正常内容。
     */
    fun matches(blocked: Set<Long>, tid: Long?): Boolean {
        if (blocked.isEmpty() || tid == null || tid <= 0L) return false
        return tid in blocked
    }

    /** 回写用的规范形式：去重、保序、逗号分隔。 */
    fun encode(tids: Collection<Long>): String =
        tids.asSequence().filter { it > 0L }.distinct().joinToString(",")

    /**
     * UI 保存前的规范化：id 与名字都保留，去重保序，逗号分隔。
     *
     * **不能用 `encode(parse(raw))`**——那条路会把所有名字静默丢掉，用户填了
     * "鬼畜"按确定就没了，还看不出为什么。
     */
    fun normalize(raw: String?): String =
        entries(raw).asSequence().distinct().joinToString(",")

    /** 面板点选时往已有名单里追加一个标签 id，返回新的存储字符串。 */
    fun add(raw: String?, tid: Long): String =
        (entries(raw) + tid).asSequence().distinct().joinToString(",")
}
