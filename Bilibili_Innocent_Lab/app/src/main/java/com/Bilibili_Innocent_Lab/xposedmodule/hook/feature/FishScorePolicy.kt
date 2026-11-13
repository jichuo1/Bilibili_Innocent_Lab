package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 「屏蔽 AI 评论与回复」的离线加权打分判据。
 *
 * 2026 年 10 月起大批 AI Agent 小号涌入评论区：昵称"XX的大肥鱼"、多为 Lv2 新号，正文自称"本鲸"、
 * 括号里演动作、@ 主人汇报，楼中楼里则大量复述主评论的词句。单看任何一个特征都不足以下结论，所以这里不做
 * "命中即删"，而是把昵称、等级、正文、复述和账号资料各自计分，
 * 总分 ≥ 阈值才判定。权重与规则移植自 dafeiyu-cleaner 的 `detector.js`
 * （MIT，见 THIRD_PARTY_NOTICES.md），只保留离线可算的部分：不看头像像素、不联网、不调模型。
 *
 * 与原实现的有意差异（这里是**静默删除**，宁可漏判也不错判）：
 * - 读不到的信号一律不计分：等级读不到不当作 Lv0，昵称/正文读不到对应维度整体跳过；
 * - 人群里常见的泛用说法（"让我来"、"来打卡"、"替他说"、"我是学AI的"）从强特征降为弱特征，
 *   不再与 Lv≤2 的 +14 合起来越过默认阈值；
 * - 去掉只对应某一期视频内容的复述规则（"缓存命中"、"驾驭"、"马具"等）；
 * - ≥Lv5 默认直接放行，白名单优先于一切计分；
 * - 自定义词至少 2 个字，且命中按"强特征"计分而不是直接删除。
 *
 * 纯函数、无 Android 依赖：规则在类加载时预编译一次，热路径只跑匹配。
 */
internal object FishScorePolicy {

    /** 一条加权规则。[cap] 非空时按命中次数累加、不超过 [cap]。 */
    private class Rule(val regex: Regex, val weight: Int, val cap: Int? = null)

    // JS 的 \b 只认 ASCII 单词字符，Android 的 ICU 引擎会把汉字也算单词字符，"小AI助手"里的 AI 就边界不上。
    // 用显式前后断言保持与原实现一致。
    private const val B_OPEN = "(?<![a-z0-9_])"
    private const val B_CLOSE = "(?![a-z0-9_])"

    // 规则一律写成小写：文本与昵称先过 TextNormalizer.forMatching（NFKC + 去格式字符 + 小写）。
    private val NAME_RULES = listOf(
        Rule(Regex("deep\\s*seek|深度求索|${B_OPEN}ds$B_CLOSE|ds家|ds是"), 40),
        Rule(Regex("大肥鱼|小肥鱼|肥鱼|肥鲸|鲸鱼娘|鲸娘|人机"), 45),
        Rule(Regex("鲸"), 30),
        Rule(Regex("鱼鱼|小鱼儿?|大鱼|小蓝"), 22),
        Rule(Regex("白饭|干饭|token"), 30),
        Rule(Regex("(鲸|鱼)\\1"), 10),
        Rule(Regex("(?:的|の|家的)[^的の]{0,4}(?:鱼|鲸|哈基米|小蓝|喵)"), 14),
        Rule(Regex("${B_OPEN}ai$B_CLOSE|${B_OPEN}bot$B_CLOSE|机器人|gpt|agent|智能体|赛博|自动|助手"), 28)
    )

    private val TEXT_RULES = listOf(
        // 自称"本鲸/本鱼"或"作为 AI"。
        Rule(Regex("本(?:鲸|肥鱼|大肥鱼|鱼(?!塘|鱼|子|身|来|就|质|周|期|月|年|次|地|人|片|视频))|本\\s*ai|作为(?:一[个只条]|一名)?(?:ai|人工智能|大模型|语言模型)"), 50),
        // 自曝是住在 DSH 里的鱼。
        Rule(Regex("住在\\s*(?:dsh|那套|这|电脑|桌面|显卡)|我(?:就|本人)?(?:是|就是)?(?:跑在|住在)|出生证明|探出头|打工的(?:大肥鱼|鱼|鲸)|一条(?:住在|ai)|不是一个人住|奉命|咱家主人|小鲸鱼游过来|鲸鲸看完|我会(?:认真)?发评论|天天在\\s*dsh"), 46),
        // 原实现里同属上一组、但真人也常这样说的泛用说法：降为弱特征。
        Rule(Regex("本酱|让我来|(?:来|过来)打卡|自己(?:刷|挑|写)|照镜子|被.{0,10}养着|替(?:你|主人|他|她)(?:刷|看|记|挑|说)|我是.{0,8}(?:鲸|鱼|ai)|不是我发的"), 12),
        // 主人承认是 AI 代发。
        Rule(Regex("(?:deep\\s*seek|ai|ds|大肥鱼|鱼)\\s*.{0,3}发的"), 40),
        Rule(Regex("(?:小鲸娘|鲸鲸|小鲸鱼|大肥鱼|小肥鱼|本鱼)(?:居然|看完|也|就|来|一下|游|看懂|尾巴)"), 25),
        // 括号里演鲸鱼动作。
        Rule(Regex("[（(][^（()）]{0,14}(?:尾巴|摇头晃脑|吐泡泡|鳍|甩甩|晃了晃|摇了摇|咕噜|拍拍肚子|翻滚|打滚|呆毛)[^（()）]{0,10}[）)]|\\*[^*]{1,10}摇摇?\\*"), 40),
        Rule(Regex("主人|老大\\s*@|管饭的"), 20),
        Rule(Regex("主人\\s*[,，：:！!～~]?\\s*@|@\\S{1,24}\\s+主人"), 15),
        Rule(Regex("(?:测试|自测)\\s*(?:一下)?\\s*(?:艾特|@|提醒|能不能收到)|纯文本测试|测试真@|测试评论|自动评论链路"), 35),
        // 数弹幕刷屏次数、复述弹幕、精确引用时间点。
        Rule(Regex("[×x✕]\\s?[0-9]{1,3}(?![0-9])"), 15),
        Rule(Regex("弹幕(?:立刻|全在|里|刷|那句|飘)"), 10),
        Rule(Regex("(?:^|[^0-9:])[0-9]{1,2}:[0-9]{2}(?![:0-9])"), 8),
        Rule(Regex("咕噜|吐泡泡|吃白饭|摇头晃脑|打工鲸|白饭管够|🐳|🐋|🐟"), 10),
        Rule(Regex("从.{1,14}(?:到|一路|讲到|走到)|[：:][^。！]{0,30}、[^。！]{0,20}、"), 8),
        // AI 安利话术。
        Rule(Regex("值得(?:看|一看|细看|存|收藏|码住)|码住|可以(?:看看|直接看)|挺适合(?:你|入门|新手)|适合(?:刚|新手|小白|入门)|正好对(?:口|得上|上)|记一笔|这条(?:给你|简直|把|值得|挺|里)|最(?:实在|实用|值钱|有料|打动|戳)"), 10, cap = 20),
        Rule(Regex("——"), 6),
        Rule(Regex("「[^」]{1,30}」"), 6),
        // Markdown 排版没删干净。带圈数字经 NFKC 会变成普通数字，这里认不出，与"1."列表合并按后者算。
        Rule(Regex("\\*\\*[^*]+\\*\\*|^#{1,3}\\s|\\n\\s*[0-9][.、]\\s", RegexOption.MULTILINE), 10),
        Rule(Regex("首先.{2,40}其次|总的来说|综上所述|总而言之|希望对你有(?:所)?帮助"), 10),
        // 卖萌后缀。
        Rule(Regex("\\([￣¯][▽^ω][￣¯]\\)|喵[！!~～。]?$|(?:^|\\s)喵[～~]"), 4)
    )

    /** 真人味特征，只在文本分 < [HUMAN_RELIEF_BELOW] 时才减分：正文已经很像 AI 时不让一句"哈哈哈"洗白。 */
    private val HUMAN_RULES = listOf(
        Rule(Regex("请问|真心问|有没有人|求助|怎么(?:办|回事|弄)|为什么|啥区别|什么区别|[?？]\\s*$"), -14),
        Rule(Regex("^.{0,10}$", RegexOption.DOT_MATCHES_ALL), -8),
        Rule(Regex("哈哈哈{2,}|草+$|www+|6{3,}|绷不住|典$|我嘞个|卧槽|我靠"), -8)
    )

    private val FILLER = Regex("的|の|家|大|小|肥|鱼|鲸|娘|蓝色|蓝|白饭|吃|爱|不|是|之|[\\s_\\-—·・.0-9]")
    internal val MENTION = Regex("@([^\\s@:：，,。！!?？]{1,24})")
    private val LEAD_MENTION = Regex("^\\s*@([^\\s@:：，,。！!?？]{1,24})\\s*([\\s\\S]*)$")
    internal val EMOTE_PLACEHOLDER = Regex("\\[[^\\]]{1,12}\\]")
    private val HAN = Regex("[一-龥]")

    /** 昵称维度上限：多条规则叠加也不能单靠昵称越过标准阈值太多。 */
    private const val NAME_CAP = 55
    private const val TEXT_MAX = 75
    private const val TEXT_MIN = -20
    private const val HUMAN_RELIEF_BELOW = 40
    private const val CUSTOM_WORD_WEIGHT = 50
    private const val LEVEL_LOW = 2
    private const val PROTECTED_LEVEL = 5
    private const val VETERAN_LEVEL = 6

    const val DEFAULT_THRESHOLD = 60
    private const val MIN_INDEPENDENT_POINTS = 16
    internal const val MAX_ACCOUNT_POINTS = 12
    const val MAX_CUSTOM_WORDS = 64
    private const val MIN_CUSTOM_WORD_LENGTH = 2

    /** 敏感度三档；阈值越低判得越严。[id] 是存储身份，**不可改名**。 */
    internal enum class Sensitivity(val id: String, val threshold: Int) {
        LOOSE("loose", 75),
        STANDARD("standard", DEFAULT_THRESHOLD),
        STRICT("strict", 45);

        companion object {
            val DEFAULT = STANDARD
            val IDS: Set<String> = entries.mapTo(linkedSetOf()) { it.id }
            fun fromId(raw: String?): Sensitivity = entries.firstOrNull { it.id == raw?.trim() } ?: DEFAULT
        }
    }

    /** 安装期把设置定型成的不可变判据；热路径只读它。 */
    internal class Plan(
        val threshold: Int,
        val protectHighLevel: Boolean,
        val nicknameWords: Set<String>,
        val textWords: Set<String>,
        val whitelist: AuthorRuleSet,
        val strongMode: Boolean = false
    ) {
        override fun equals(other: Any?): Boolean = other is Plan && threshold == other.threshold &&
            protectHighLevel == other.protectHighLevel && nicknameWords == other.nicknameWords &&
            textWords == other.textWords && whitelist == other.whitelist && strongMode == other.strongMode

        override fun hashCode(): Int = listOf(threshold, protectHighLevel, nicknameWords, textWords, whitelist, strongMode).hashCode()

        fun describe(): String = "fish>=$threshold" +
            (if (nicknameWords.isNotEmpty() || textWords.isNotEmpty()) "+words" else "") +
            (if (whitelist.isNotEmpty()) "+allow" else "")
    }

    /** 设置层读出的原始取值；[plan] 在启用时才产出。 */
    internal class Config(
        val enabled: Boolean = false,
        val sensitivityId: String = "",
        val rawNicknameWords: String = "",
        val rawTextWords: String = "",
        val rawWhitelist: String = "",
        val protectHighLevel: Boolean = true,
        val strongMode: Boolean = false
    ) {
        fun plan(): Plan? = if (!enabled) null else Plan(
            threshold = Sensitivity.fromId(sensitivityId).threshold,
            protectHighLevel = protectHighLevel,
            nicknameWords = customWords(rawNicknameWords),
            textWords = customWords(rawTextWords),
            whitelist = AuthorRuleSet.parse(rawWhitelist),
            strongMode = strongMode
        )

        companion object {
            val DISABLED = Config()
        }
    }

    /** 与其它词表同一套分隔与归一化，但至少 2 个字：1 个字的词会让整页评论一起被判成 AI。 */
    internal fun customWords(raw: String): Set<String> = RuleSetCodec.parse(raw)
        .filter { it.codePointCount(0, it.length) >= MIN_CUSTOM_WORD_LENGTH }
        .take(MAX_CUSTOM_WORDS)
        .toCollection(linkedSetOf())

    /**
     * 这条评论是否判定为 AI 灌水。任何信号读不到都不计分；白名单与高等级保护先于计分。
     *
     * @param level 读不到传 null，不能当作 0。
     * @param root 这条回复所在楼的主评论；不是楼中楼（主楼、置顶位）时传 null。
     * @param video 延迟读取与这条评论属于同一稿件的视频标题、简介。
     */
    fun shouldRemove(
        plan: Plan,
        name: String?,
        mid: Long?,
        level: Int?,
        message: String?,
        root: FishRootEcho.Root? = null,
        video: (() -> FishRootEcho.Video?)? = null,
        extra: (() -> Evidence)? = null
    ): Boolean {
        if (plan.whitelist.isNotEmpty() && plan.whitelist.matches(name, mid)) return false
        if (plan.protectHighLevel && level != null && level >= PROTECTED_LEVEL) return false
        return score(plan, name, level, message, root, video, extra) >= plan.threshold
    }

    internal data class Evidence(val peerPoints: Int = 0, val accountPoints: Int = 0)

    /**
     * 普通总分限制在 0..99；强力模式在同样的评分完成后将总分乘二。
     *
     * 跨账号复述和账号资料需要已有昵称或正文证据，所有复述共享同一个上限。
     */
    fun score(
        plan: Plan,
        name: String?,
        level: Int?,
        message: String?,
        root: FishRootEcho.Root? = null,
        video: (() -> FishRootEcho.Video?)? = null,
        extra: (() -> Evidence)? = null
    ): Int {
        val normalizedName = name?.takeIf(String::isNotBlank)?.let(TextNormalizer::forMatching)
        val normalizedMessage = message?.takeIf(String::isNotBlank)?.let(TextNormalizer::forMatching)
        val independent = (normalizedName?.let { nameScore(it, plan.nicknameWords) } ?: 0) +
            (normalizedMessage?.let { textScore(it, normalizedName, plan.textWords) } ?: 0)
        var total = independent + levelScore(level)
        val evidence = if (extra != null && independent >= MIN_INDEPENDENT_POINTS && total < plan.threshold &&
            total + FishRootEcho.MAX_POINTS + MAX_ACCOUNT_POINTS >= plan.threshold
        ) extra() else null
        total += evidence?.accountPoints?.coerceIn(0, MAX_ACCOUNT_POINTS) ?: 0
        if (normalizedMessage != null && total < plan.threshold && total + FishRootEcho.MAX_POINTS >= plan.threshold) {
            val rootPoints = if (root != null && (normalizedName == null || normalizedName != root.authorName())) {
                FishRootEcho.points(root, normalizedMessage)
            } else {
                0
            }
            val videoPoints = if (video != null && total + rootPoints < plan.threshold) {
                runCatching(video).getOrNull()?.points(normalizedMessage) ?: 0
            } else {
                0
            }
            val peerPoints = evidence?.peerPoints?.coerceIn(0, FishPeerEcho.MAX_POINTS) ?: 0
            total += maxOf(rootPoints, videoPoints, peerPoints)
        }
        val score = total.coerceIn(0, 99)
        return if (plan.strongMode) score * 2 else score
    }

    internal fun nameScore(name: String, customWords: Set<String> = emptySet()): Int {
        var total = 0
        for (rule in NAME_RULES) if (rule.regex.containsMatchIn(name)) total += rule.weight
        if (containsAny(customWords, name)) total += CUSTOM_WORD_WEIGHT
        if (total == 0 && '鱼' in name) total = 8
        return minOf(NAME_CAP, total)
    }

    internal fun levelScore(level: Int?): Int = when {
        level == null -> 0
        level <= LEVEL_LOW -> 14
        level == LEVEL_LOW + 1 -> 6
        level >= VETERAN_LEVEL -> -10
        else -> 0
    }

    internal fun textScore(text: String, ownerName: String?, customWords: Set<String> = emptySet()): Int {
        var score = applyRules(text, TEXT_RULES)
        if (containsAny(customWords, text)) score += CUSTOM_WORD_WEIGHT
        if (ownerName != null && MENTION.findAll(text).any { ownerLink(ownerName, it.groupValues[1]) }) score += 25
        val lead = LEAD_MENTION.find(text)
        // 楼中楼的"回复 @某人 :"前缀不以 @ 开头，不会落进这一支。
        if (lead != null) {
            val rest = EMOTE_PLACEHOLDER.replace(lead.groupValues[2], "").trim()
            if (rest.isEmpty()) score += 15 else if (rest.length >= 14) score += 20
        }
        val length = EMOTE_PLACEHOLDER.replace(text, "").length
        if (length > 150) score += 8 else if (length > 70) score += 4
        if (score < HUMAN_RELIEF_BELOW) score += applyRules(text, HUMAN_RULES)
        return score.coerceIn(TEXT_MIN, TEXT_MAX)
    }

    /** 词与文本都已归一化，直接做子串比较，不再走 [RuleSetCodec.matches] 的二次归一化。 */
    private fun containsAny(words: Set<String>, text: String): Boolean {
        for (word in words) if (text.contains(word)) return true
        return false
    }

    private fun applyRules(text: String, rules: List<Rule>): Int {
        var sum = 0
        for (rule in rules) {
            val cap = rule.cap
            if (cap == null) {
                if (rule.regex.containsMatchIn(text)) sum += rule.weight
            } else {
                val hits = rule.regex.findAll(text).count()
                if (hits > 0) sum += minOf(cap, rule.weight * hits)
            }
        }
        return sum
    }

    /**
     * @ 的对象是不是"主人"：昵称去掉宠物名常用字后，剩下的核心部分与被 @ 的名字重合。
     * 核心 ≥2 字时按两字滑窗比，1 字时只认汉字，避免一个字母或数字凑巧撞上。
     */
    internal fun ownerLink(name: String, mention: String): Boolean {
        if (name.isEmpty() || mention.isEmpty()) return false
        if (name == mention) return true
        val core = FILLER.replace(name, "")
        if (core.isEmpty()) return false
        if (core.length >= 2) {
            for (index in 0..core.length - 2) if (mention.contains(core.substring(index, index + 2))) return true
            return false
        }
        return HAN.containsMatchIn(core) && mention.contains(core)
    }
}
