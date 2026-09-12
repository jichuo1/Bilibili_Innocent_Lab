package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 分区 id（`args.tid`）黑名单的编解码。
 *
 * **为什么是字符串而不是 `Set<String>`**：授权链只搬 `SettingValue` 的
 * Bool / IntValue / Text 三种形态（见 `RemoteHookConfigContract.resolveSourceValues`），
 * `getStringSet` 的偏好根本过不了 Remote Preferences 那一层。所以和既有的
 * `*_HIDDEN_RULES` / `title_filter.keywords` 一样，存成一条分隔符拼起来的 Text。
 *
 * **为什么不复用 [RuleSetCodec]**：它的 `matches` 做的是子串 contains，而分区必须
 * **精确数值相等**——`tid=163` 不能命中 `1631`，`"29413"` 也不能因为含 "941" 就命中。
 * contains 语义用在数字上一定误伤。
 */
internal object TidBlocklistCodec {
    /** 与 [RuleSetCodec] 对齐的分隔符集合，另外容忍空白，方便手填与从备份里粘贴。 */
    private val separators = Regex("[,，;；\\s]+")

    /**
     * 解析成分区 id 集合；**非法项一律丢弃、不抛异常**。
     *
     * 备份文件是可以被手改的，一个错字不能让整条过滤链失效。
     * 非正数也丢：宿主用 `tid = 0` 表示"没有分区"（番剧卡就是），
     * 把 0 收进黑名单会把所有无分区卡一起删掉。
     */
    fun parse(raw: String?): Set<Long> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(separators)
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull(String::toLongOrNull)
            .filter { it > 0L }
            .toCollection(LinkedHashSet())
    }

    /**
     * 是否命中黑名单。
     *
     * `tid == null` 或非正数一律**放行**：番剧卡、广告卡、部分直播卡本来就没有分区，
     * 把"读不到"当成"未知分区"删掉会误伤一大片正常内容。
     */
    fun matches(blocked: Set<Long>, tid: Long?): Boolean {
        if (blocked.isEmpty() || tid == null || tid <= 0L) return false
        return tid in blocked
    }

    /** 回写用的规范形式：去重、保序、逗号分隔。 */
    fun encode(tids: Collection<Long>): String =
        tids.asSequence().filter { it > 0L }.distinct().joinToString(",")

    /** 面板点选时往已有名单里追加一个分区，返回新的存储字符串。 */
    fun add(raw: String?, tid: Long): String = encode(parse(raw) + tid)
}
