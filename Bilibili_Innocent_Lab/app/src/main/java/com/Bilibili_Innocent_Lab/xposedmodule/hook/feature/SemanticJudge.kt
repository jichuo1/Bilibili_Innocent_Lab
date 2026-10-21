package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 语义判定：把若干条文本交给 TypeSafe Jev（官方或兼容的第三方中转），按"一句话规则"**逐条规则**给出
 * keep/block——每条文本 × 每条规则一个原子问题，绝不把多种违规判定塞进一个问题（官方反例，见
 * [JevRequestCodec]），答案按条目取最大还原成屏蔽概率。配置来自设置页（实验性功能 → 兼容），
 * 见 `development_experience.md` 2026-09-29。
 *
 * 纪律：
 * - **fail-open**：冷却中、超时、HTTP 错误、解析失败一律 [SemanticVerdict.UNKNOWN]，调用方按原有规则
 *   处理；语义判定只会在规则之外"多删"，永远不会让规则删掉的东西复活。
 * - 只发文本，不发 uid、用户名、Cookie。
 * - 缓存存模型给出的**屏蔽概率**，灵敏度在读取时套用；内存 + 硬盘（只存摘要与分数，见 [SemanticDiskStore]）。
 *   宿主只在 attach 读一次配置；关闭开关 + 重启哔哩哔哩后由 [SemanticDiskCleanup] 删除该面的硬盘缓存。
 * - 线程：[SemanticMode.CACHE_ONLY] 任意线程；[SemanticMode.WAIT] 只能在非主线程（同步联网）；
 *   [SemanticMode.PREFETCH] 立即返回，联网在模块自有后台线程。
 */
internal enum class SemanticVerdict { KEEP, BLOCK, UNKNOWN }

internal enum class SemanticMode { CACHE_ONLY, WAIT, PREFETCH }

/** 用户可选的灵敏度；门槛作用在模型给出的屏蔽概率上。越灵敏删得越多，误删也越多。 */
internal enum class SemanticSensitivity(val id: String, val blockThreshold: Float) {
    LOW("low", 0.85f),
    MEDIUM("medium", 0.6f),
    HIGH("high", 0.4f);

    companion object {
        val DEFAULT = MEDIUM
        val IDS: Set<String> = entries.mapTo(linkedSetOf()) { it.id }
        fun fromId(raw: String?): SemanticSensitivity = entries.firstOrNull { it.id == raw?.trim() } ?: DEFAULT
    }
}

/**
 * 一条可勾选的屏蔽类型。[id] 是存储与缓存身份，**不可改名**；其余字段是发给模型的判定说明。
 *
 * 结构化写法（2026-09-30，减少误判）：[text] 为"类型：包括什么"，[notFor] 写明**不属于**本类的相近内容，
 * [examples] / [keepExamples] 是该屏蔽与该保留的典型例子。依据：OpenAI/ROOST 的审核策略写法指南要求
 * 同时写"应标记"与"不应标记"并给正反例；TypeSafe 建议把边界情况写进规则。只发送用户勾选的类型。
 */
internal data class SemanticRule(
    val id: String,
    val text: String,
    val defaultEnabled: Boolean = true,
    val notFor: String = "",
    val examples: List<String> = emptyList(),
    val keepExamples: List<String> = emptyList()
) {
    val type: String get() = text.substringBefore('：')
    val covers: String get() = text.substringAfter('：', text)

    /** 发给模型的规则对象；字段顺序固定（也用作缓存指纹）。 */
    fun toJson(): JSONObject = JSONObject().put("type", type).put("covers", covers).apply {
        if (notFor.isNotEmpty()) put("not_for", notFor)
        if (examples.isNotEmpty()) put("examples", JSONArray().also { array -> examples.forEach { array.put(it) } })
        if (keepExamples.isNotEmpty()) {
            put("keep_examples", JSONArray().also { array -> keepExamples.forEach { array.put(it) } })
        }
    }

    /** 缓存身份：任一字段变化，旧判定整份作废。 */
    val identity: String
        get() = buildString {
            append(id).append('=').append(text).append('\u0002').append(notFor)
            examples.forEach { append('\u0003').append(it) }
            keepExamples.forEach { append('\u0004').append(it) }
        }
}

/** 过滤面：各自一套预设、一个开关、一份勾选。 */
internal enum class SemanticSurface { DYNAMIC, DANMAKU, COMMENT, VIDEO }

/**
 * 各过滤面的预设屏蔽类型（设置页勾选面板的全部选项）。
 *
 * 勾选结果以逗号分隔的 id 存储，按本目录顺序规范化；未知 id 忽略、全部未勾选 ⇒ 不创建判定器。
 * 规则集合进入缓存键指纹，所以改勾选或改规则说明后旧判定自然失效，不会残留。
 */
internal object SemanticPresets {
    const val MAX_SELECTION_LENGTH = 512

    private val FLAME = SemanticRule(
        "flame", "引战拉踩：煽动对立、拉踩比较、挑起争吵",
        notFor = "有理由的批评、客观比较、表达不同意见",
        examples = listOf("就这也配和XX比？粉丝真是没见过世面", "XX玩家全是脑残"),
        keepExamples = listOf("我觉得A的剧情比B好，节奏更紧凑")
    )
    private val ABUSE = SemanticRule(
        "abuse", "人身攻击：辱骂、嘲讽、网暴他人",
        notFor = "针对内容本身的批评、熟人间的玩笑、引用台词",
        examples = listOf("你是不是脑子有病", "这种人就该被开盒"),
        keepExamples = listOf("这个观点我不认同，数据来源有问题")
    )
    private val SPOILER = SemanticRule(
        "spoiler", "剧透：提前透露剧情走向、结局、反转或人物命运",
        notFor = "对已播内容的回顾、没有答案的猜测、与剧情无关的玩梗",
        examples = listOf("他最后会死", "反派其实是女主她爸"),
        keepExamples = listOf("感觉这人后面要黑化，纯猜的")
    )

    val DYNAMIC: List<SemanticRule> = listOf(
        SemanticRule(
            "lottery", "抽奖：转发抽奖、评论抽奖、互动抽奖或开奖公告",
            notFor = "讨论别人的抽奖、晒中奖的日常、游戏抽卡",
            examples = listOf("关注+转发，抽3位送机械键盘", "开奖啦！恭喜以下粉丝"),
            keepExamples = listOf("这期卡池又歪了，抽了90发")
        ),
        SemanticRule(
            "goods", "带货：以成交为目的的商品推荐、购物链接、团购或优惠券推广",
            notFor = "与商品无利益相关的讨论、测评、吐槽",
            examples = listOf("链接放评论区啦", "点击小黄车，今天下单立减50"),
            keepExamples = listOf("这耳机用了半年，降噪一般，不推荐")
        ),
        SemanticRule(
            "sponsored", "商单恰饭：品牌合作、软广植入或推广口播",
            notFor = "自发分享喜欢的产品、吐槽别人的广告",
            examples = listOf("本期内容由XX品牌赞助", "感谢XX的支持，评论区抽送同款"),
            keepExamples = listOf("又恰饭了，不过这次广告挺有意思")
        ),
        SemanticRule(
            "traffic", "引流：导流到其他平台、加群、私信领取资料",
            notFor = "注明出处、提到其他平台的正常讨论、官方活动信息",
            examples = listOf("资料整理好了，私信回复666领取", "进群交流：123456789"),
            keepExamples = listOf("原视频在油管，转载注明了出处")
        ),
        FLAME,
        ABUSE,
        SemanticRule(
            "bait", "互动诱导：求三连、求关注、转发接好运之类", defaultEnabled = false,
            notFor = "正常的感谢观看、就内容发起的提问讨论",
            examples = listOf("不转不是中国人", "转发这条锦鲤，好运一整年"),
            keepExamples = listOf("你们觉得下期做什么好？")
        ),
        SemanticRule(
            "marketing", "营销号模板：拼凑搬运、震惊体资讯、洗稿", defaultEnabled = false,
            notFor = "正常的新闻转述、注明出处的整理",
            examples = listOf("震惊！90%的人都不知道", "小编也很惊讶呢"),
            keepExamples = listOf("据官方公告，服务器周五停机维护")
        )
    )

    /** 弹幕逐条独立判断，"刷屏"只能按单条内容本身判断。 */
    val DANMAKU: List<SemanticRule> = listOf(
        SPOILER,
        SemanticRule(
            "flood", "刷屏：大量重复字符、复读口号或占屏符号",
            notFor = "短促的正常感叹、名场面的一句应援",
            examples = listOf("啊啊啊啊啊啊啊啊啊啊啊啊啊啊啊啊", "■■■■■■■■■■■■"),
            keepExamples = listOf("哈哈哈哈哈", "好耶")
        ),
        FLAME,
        SemanticRule(
            "abuse", "人身攻击：辱骂、嘲讽 UP 主、角色演员或其他观众",
            notFor = "对角色在剧情中行为的吐槽、对内容的批评",
            examples = listOf("UP主是不是没长脑子", "这演员长得真恶心"),
            keepExamples = listOf("这个反派太坏了")
        ),
        SemanticRule(
            "promotion", "广告引流：推销、导流到其他平台、招募或刷单",
            notFor = "说明作品出处、BGM 名称、推荐相关作品",
            examples = listOf("加V了解兼职日结", "进群领福利：123456"),
            keepExamples = listOf("BGM是《起风了》")
        ),
        SemanticRule(
            "checkin", "签到打卡：「第一」「来了」「打卡」「前排」之类占屏文字", defaultEnabled = false,
            notFor = "带有观看感受的发言",
            examples = listOf("第一！", "打卡第30天"),
            keepExamples = listOf("二刷了，还是在这里哭了")
        ),
        SemanticRule(
            "offtopic", "无关闲聊：与视频内容无关的聊天或刷存在感", defaultEnabled = false,
            notFor = "由视频内容引出的联想和讨论",
            examples = listOf("有人一起打游戏吗", "今天作业好多"),
            keepExamples = listOf("这段让我想起小时候看的动画")
        ),
        SemanticRule(
            "warning", "高能预警：「前方高能」「注意看」之类提示", defaultEnabled = false,
            notFor = "字幕说明、翻译注释",
            examples = listOf("前方高能", "非战斗人员请撤离"),
            keepExamples = listOf("注：这里原文是双关")
        )
    )

    val COMMENT: List<SemanticRule> = listOf(
        FLAME,
        ABUSE,
        SemanticRule(
            "sarcasm", "反串黑：以夸张吹捧、反讽或伪装成支持者的方式抹黑对方",
            notFor = "真诚的夸赞、明显的玩梗调侃",
            examples = listOf("XX哥哥天下第一，谁不服谁就是黑子，全网都该给他道歉"),
            keepExamples = listOf("这期真的做得好，剪辑进步很大")
        ),
        SemanticRule(
            "fandom", "饭圈控评：无条件站队、口号复读、集体刷屏或压制正常讨论",
            notFor = "普通的喜爱表达、理性支持",
            examples = listOf("XX最棒！XX最棒！XX最棒！", "姐妹们快来控评，把黑子压下去"),
            keepExamples = listOf("喜欢XX好几年了，这首歌真的好听")
        ),
        SemanticRule(
            "polarize", "群体对立：针对地域、性别、职业等群体的攻击或刻板印象",
            notFor = "讨论社会现象、分享自身经历、有依据的数据讨论",
            examples = listOf("XX地方的人都是骗子", "女的就是开不好车"),
            keepExamples = listOf("我们那边方言也有类似的说法")
        ),
        SemanticRule(
            "promotion", "广告引流：推销、导流到其他平台或账号、招募、刷单",
            notFor = "注明出处、推荐相关作品或原作者",
            examples = listOf("私我领取全套教程", "兼职日结，有意加V"),
            keepExamples = listOf("原作者主页还有更多作品")
        ),
        SPOILER,
        SemanticRule(
            "checkin", "抢楼打卡：「第一」「前排」「打卡」之类", defaultEnabled = false,
            notFor = "带有观看感受的发言",
            examples = listOf("前排", "第一！"),
            keepExamples = listOf("来晚了，这期内容好扎实")
        ),
        SemanticRule(
            "fishing", "求关注钓鱼：互粉、求关注、「主页有惊喜」之类", defaultEnabled = false,
            notFor = "UP 主本人的回复、提到其他视频的正常讨论",
            examples = listOf("互粉必回", "主页有惊喜哦"),
            keepExamples = listOf("UP上期也讲过这个")
        )
    )

    /** 视频卡片按标题判断（首页推荐、相关推荐）。 */
    val VIDEO: List<SemanticRule> = listOf(
        SemanticRule(
            "clickbait", "标题党：夸大、悬念钓鱼或与内容不符的耸动标题",
            notFor = "正常吸引人的标题、有信息量的疑问句",
            examples = listOf("震惊！他竟然做了这件事……", "看到最后我哭了"),
            keepExamples = listOf("为什么天是蓝的？一个视频讲清楚")
        ),
        SemanticRule(
            "marketing", "营销号：模板化搬运、拼凑剪辑或以带货为目的的视频",
            notFor = "原创解说、正常的商品测评",
            examples = listOf("盘点十大离谱瞬间，第一个就绷不住了", "这件神器让你一秒变白"),
            keepExamples = listOf("实测5款降噪耳机：到底谁更强")
        ),
        SemanticRule(
            "borderline", "擦边低俗：以性暗示或低俗噱头吸引点击",
            notFor = "正常的舞蹈、健身、穿搭展示",
            examples = listOf("深夜福利，懂的都懂", "黑丝诱惑……"),
            keepExamples = listOf("健身第100天，体态变化对比")
        ),
        SemanticRule(
            "outrage", "情绪煽动：制造对立或煽动愤怒",
            notFor = "客观报道争议事件、理性评论",
            examples = listOf("看完气炸了！这些人太过分了", "全网都在骂，你还不知道？"),
            keepExamples = listOf("某事件完整时间线梳理")
        ),
        SemanticRule(
            "anxiety", "贩卖焦虑：以恐吓、焦虑或危机感吸引点击", defaultEnabled = false,
            notFor = "正常的健康、理财、职业科普",
            examples = listOf("30岁还没存款，你的人生就完了", "再不学这个就要被淘汰了"),
            keepExamples = listOf("新手理财：先弄清这三个概念")
        ),
        SemanticRule(
            "repost", "搬运盗转：未授权搬运、盗录或简单二改他人作品", defaultEnabled = false,
            notFor = "注明授权的转载、字幕组翻译作品",
            examples = listOf("【搬运】XX最新视频（侵删）", "抖音热门合集第8期"),
            keepExamples = listOf("【授权转载】原作者：XX")
        )
    )

    fun of(surface: SemanticSurface): List<SemanticRule> = when (surface) {
        SemanticSurface.DYNAMIC -> DYNAMIC
        SemanticSurface.DANMAKU -> DANMAKU
        SemanticSurface.COMMENT -> COMMENT
        SemanticSurface.VIDEO -> VIDEO
    }

    /** 默认勾选（存储格式）。 */
    fun defaultSelection(surface: SemanticSurface): String =
        of(surface).filter(SemanticRule::defaultEnabled).joinToString(",") { it.id }

    /** 存储值 → 已勾选规则（按目录顺序、去重、忽略未知 id）。 */
    fun selected(surface: SemanticSurface, raw: String): List<SemanticRule> {
        val ids = raw.take(MAX_SELECTION_LENGTH).split(',').mapTo(hashSetOf()) { it.trim() }
        return of(surface).filter { it.id in ids }
    }

    /** 勾选结果 → 规范化存储值。 */
    fun encode(surface: SemanticSurface, ids: Set<String>): String =
        of(surface).filter { it.id in ids }.joinToString(",") { it.id }
}

/**
 * 设置页的共享 AI 语义判定配置（实验性功能 → 兼容）。各过滤面用自己勾选的规则各建一个 [SemanticJudge]，
 * 缓存互不共享（规则不同，结论本就不能复用）；来源池 [pool] 全进程共享（冷却与负载按来源统计）。
 * 一个可用来源都没有时 [from] 返回 null。
 */
internal data class SemanticSettings(
    /** 已配置且有效的来源，按编号排序；至少一个。 */
    val sources: List<SemanticSource>,
    val blockThreshold: Float,
    val waitFirstScreen: Boolean,
    /** 判定结果保存时长（天，1–90）；同时是内存与硬盘条目的有效期。 */
    val cacheDays: Int = DEFAULT_CACHE_DAYS,
    /** 硬盘缓存根目录（宿主私有 files）；null 时只用内存。 */
    val storeRoot: java.io.File? = null,
    /** 用户设的等待上限（毫秒）；0 = 各面默认值。 */
    val waitTimeoutMs: Int = 0,
    /** 用户写的判定口径（空 = 默认）；防注入前缀与输出格式由模块固定。 */
    val guidance: String = "",
    /** 构造参数而非属性体：`copy()` 时沿用同一个池，各面共享冷却与负载。 */
    val pool: SemanticSourcePool = SemanticSourcePool(sources)
) {
    val cacheTtlMs: Long get() = cacheDays.coerceIn(MIN_CACHE_DAYS, MAX_CACHE_DAYS) * DAY_MS

    /** 1 号来源（兼容旧调用与单测）。 */
    val endpoint: String get() = sources.first().endpoint
    val backend: SemanticBackend get() = sources.first().backend

    /**
     * 没有勾选任何类型时返回 null：该过滤面不创建判定器。
     * [route] 为 [SemanticRoute.AUTO] 或来源编号；指定的来源不存在时退回自动分流。
     */
    fun judge(
        surface: SemanticSurface,
        rules: List<SemanticRule>,
        batchSize: Int = SemanticJudge.MAX_BATCH,
        timeoutMs: Int = SemanticJudge.DEFAULT_TIMEOUT_MS,
        route: String? = SemanticRoute.AUTO
    ): SemanticJudge? = rules.takeIf { it.isNotEmpty() }?.let {
        val routed = SemanticRoute.resolve(route, sources)
        val first = routed.first()
        SemanticJudge(
            apiKey = first.apiKey,
            rules = it,
            timeoutMs = if (waitTimeoutMs > 0) waitTimeoutMs else timeoutMs,
            blockThreshold = blockThreshold,
            endpoint = first.endpoint,
            waitFirstScreen = waitFirstScreen,
            batchSize = batchSize,
            backend = first.backend,
            cache = SemanticScoreCache(ttlMs = cacheTtlMs),
            store = storeRoot?.let { root ->
                SemanticDiskStore(SemanticDiskStore.fileFor(root, surface), SemanticJudge.fingerprintOf(it, routed, guidance))
            },
            cacheTtlMs = cacheTtlMs,
            sourcePool = pool,
            sourceRoute = routed,
            guidance = guidance
        )
    }

    companion object {
        const val DEFAULT_CACHE_DAYS = 7
        const val MIN_CACHE_DAYS = 1
        const val MAX_CACHE_DAYS = 90
        private const val DAY_MS = 24L * 60 * 60 * 1000
        const val MIN_TIMEOUT_MS = 500
        const val MAX_TIMEOUT_MS = 30_000

        /** 0 或负数 = 自动；其余夹到 [MIN_TIMEOUT_MS]–[MAX_TIMEOUT_MS]。 */
        fun normalizeTimeout(raw: Int): Int = if (raw <= 0) 0 else raw.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

        /**
         * 1 号来源用前几个参数（沿用最早的单来源设置键）；[extraSources] 是 2–4 号里有效的。
         * 一个有效来源都没有时返回 null：任何过滤面都不启用。
         */
        fun from(
            apiKey: String,
            endpoint: String,
            sensitivity: String,
            waitFirstScreen: Boolean,
            cacheDays: Int = DEFAULT_CACHE_DAYS,
            storeRoot: java.io.File? = null,
            provider: String = SemanticBackend.JEV,
            model: String = "",
            timeoutMs: Int = 0,
            extraSources: List<SemanticSource> = emptyList(),
            guidance: String = ""
        ): SemanticSettings? {
            val sources = (listOfNotNull(SemanticSource.from(1, apiKey, endpoint, provider, model)) +
                extraSources.filter { it.index in 2..SemanticSource.MAX_SOURCES })
                .distinctBy { it.index }.sortedBy { it.index }
            if (sources.isEmpty()) return null
            return SemanticSettings(
                sources = sources,
                blockThreshold = SemanticSensitivity.fromId(sensitivity).blockThreshold,
                waitFirstScreen = waitFirstScreen,
                cacheDays = cacheDays.coerceIn(MIN_CACHE_DAYS, MAX_CACHE_DAYS),
                storeRoot = storeRoot,
                waitTimeoutMs = normalizeTimeout(timeoutMs),
                guidance = SemanticGuidance.normalize(guidance)
            )
        }
    }
}

/**
 * 撤销保证：宿主启动时（后台线程）删除不再启用的过滤面的硬盘缓存；JEV 未配置时全部删除。
 * 开关与勾选都要重启哔哩哔哩才生效，所以"关闭 → 重启"之后磁盘上不会留下任何判定。
 */
internal object SemanticDiskCleanup {
    fun run(root: java.io.File, active: Set<SemanticSurface>) {
        if (active.isEmpty()) runCatching { java.io.File(java.io.File(root, "bil_semantic"), SemanticJudge.LEARNED_FILE).delete() }
        SemanticSurface.entries.filter { it !in active }.forEach { surface ->
            runCatching { SemanticDiskStore.fileFor(root, surface).delete() }
            runCatching { java.io.File(SemanticDiskStore.fileFor(root, surface).path + ".tmp").delete() }
        }
    }
}

/** 一次批量判定的观测记录（debug 日志用）；不含原文全文。 */
internal data class SemanticBatchReport(
    val total: Int,
    val cacheHits: Int,
    val requested: Int,
    val blocked: Int,
    val elapsedMs: Long,
    val outcome: String,
    /** 本次实际请求的用量合计；没有请求或服务不报用量时为 null。 */
    val usage: SemanticUsage? = null,
    /** 本次使用的请求变体（JEV 题型等）；没有请求时为空。 */
    val variant: String = "",
    /** 本次实际用到的来源编号（逗号分隔）；多来源分流与故障转移的观测。 */
    val sources: String = "",
    /** 发了对冲请求的分批数。 */
    val hedged: Int = 0,
    /** 低可信来源判"屏蔽"、本次先不执行（等复核）的条数。 */
    val withheld: Int = 0,
    /** 模型漏答、补发后补回分数的条数。 */
    val refilled: Int = 0
) {
    /** 追加在各面观测日志行尾的来源、用量与变体字段。 */
    fun extras(): String = buildString {
        if (sources.isNotEmpty()) append(" src=").append(sources)
        if (hedged > 0) append(" hedge=").append(hedged)
        if (withheld > 0) append(" withheld=").append(withheld)
        if (refilled > 0) append(" refill=").append(refilled)
        if (variant.isNotEmpty()) append(" variant=").append(variant)
        usage?.let { append(' ').append(it.describe()) }
    }
}

internal class SemanticJudge(
    private val apiKey: String,
    val rules: List<SemanticRule>,
    /** 单次判定（含分批）的总时间预算。 */
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    /** 屏蔽门槛（屏蔽概率 ≥ 此值才删），由 [SemanticSensitivity] 给出。 */
    val blockThreshold: Float = SemanticSensitivity.DEFAULT.blockThreshold,
    /** 官方或第三方中转的 systemone 端点，已规范化。 */
    val endpoint: String = ENDPOINT,
    /** true：首屏阻塞等判定；false：首屏放行，判定在后台进缓存，下次加载生效。 */
    val waitFirstScreen: Boolean = false,
    /** 每个请求最多放几题；一题 = 一条文本 × 一条规则（见 [JevRequestCodec]），Jev 的并行题目同享 state，弹幕这种短文本可以放大。 */
    private val batchSize: Int = MAX_BATCH,
    /** 判定后端；默认 JEV。 */
    private val backend: SemanticBackend = JevBackend(),
    transport: ((ByteArray, String, Int) -> Pair<Int, String>?)? = null,
    /** 后台投递；返回 false 表示被拒（队列满），这批只走规则。 */
    background: ((Runnable) -> Boolean)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cache: SemanticScoreCache = SemanticScoreCache(),
    /** 硬盘缓存；null 时只用内存。 */
    private val store: SemanticDiskStore? = null,
    private val cacheTtlMs: Long = SemanticScoreCache.DEFAULT_TTL_MS,
    /** 延迟执行（预取合并的定时发送）；返回 false 表示不可用。注入了 [background] 而没注入它时立即执行（单测）。 */
    scheduler: ((Long, Runnable) -> Boolean)? = null,
    /** 进程共享的来源池；null 时用 [apiKey]/[endpoint]/[backend] 组成单来源池（单测与旧调用）。 */
    sourcePool: SemanticSourcePool? = null,
    /** 本面可用的来源（自动分流 = 全部；指定 = 一个）；null = 池里全部。 */
    sourceRoute: List<SemanticSource>? = null,
    /** 用户判定口径（空 = 默认）。 */
    guidance: String = "",
    /**
     * 对冲 / 并行尝试用的执行器；返回 false 表示不可用。注入了 [background] 而没注入它时关闭对冲（单测保持顺序）。
     */
    hedgeExecutor: ((Runnable) -> Boolean)? = null
) {
    private val hedgeSubmit: ((Runnable) -> Boolean)? = hedgeExecutor ?: if (background != null) null else ::submitToHedgeExecutor

    /** 已做过灰区复核的键：每个条目最多复核一次；过大时清空（只是去重，不影响正确性）。 */
    private val reviewed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val scheduler: (Long, Runnable) -> Boolean = scheduler
        ?: if (background != null) { _, task -> task.run(); true } else ::scheduleShared
    private val pendingLock = Any()
    private val pendingItems = ArrayList<PendingItem>()
    private var pendingReport: ((SemanticBatchReport, List<String>, List<SemanticVerdict>) -> Unit)? = null
    private var flushArmed = false

    /** 注入的传输（单测，第二个参数是来源的 Key，可据此区分来源）；null 时按来源地址真发 HTTP。 */
    private val transport = transport
    private val pool = sourcePool ?: SemanticSourcePool(listOf(SemanticSource(1, apiKey, endpoint, backend)))
    private val slots: List<SemanticSourcePool.Slot> = pool.slotsFor(sourceRoute ?: pool.sources)
    private val guidanceText = SemanticGuidance.of(guidance)

    /**
     * 单个请求的网络超时，与"等多久"分开：等待上限由 `Future.get(剩余时间)` 保证，过了截止的请求继续在后台
     * 跑完并写缓存（下次命中）。网络超时若等于等待上限，慢服务（对话模型常见 2–5 s）会在后台也永远超时、
     * 一条都进不了缓存。
     */
    private val requestTimeoutMs = maxOf(timeoutMs, MIN_REQUEST_TIMEOUT_MS)
    private val background = background ?: ::submitToSharedExecutor
    /**
     * WAIT（用户正在等的首屏 / 弹幕分段）走独立池，不排在后台预取后面、也不被预取占满队列拒掉。
     * 注入了 [background]（单测）时两条路径共用注入的执行器。
     */
    private val waitBackground = background ?: ::submitToWaitExecutor
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /**
     * 规则、来源组合与判定口径先压成 32 字符摘要：缓存键只对"摘要 + 原文"求哈希，
     * 不必每条都重算规则正文。任一变化，旧判定自然作废。
     */
    private val ruleFingerprint = fingerprintOf(rules, slots.map { it.source }, guidance)

    val enabled: Boolean get() = rules.isNotEmpty() && slots.any { it.source.apiKey.isNotBlank() }

    private val storeLoadScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    /**
     * 硬盘内容已合并进内存。**合并完成前禁止写盘**：否则第一批新结果会用"只有新结果"的快照覆盖掉
     * 磁盘上全部旧判定（2026-09-30 复查发现：`lastFlushAt` 初值 0 让首批结果立即触发写盘）。
     */
    @Volatile private var storeLoaded = false
    private val flushScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastFlushAt = 0L

    /** 首次使用时在后台加载硬盘缓存；加载完成前只用内存，调用方不等待。 */
    private fun ensureStoreLoaded() {
        val store = store ?: return
        if (!storeLoadScheduled.compareAndSet(false, true)) return
        val accepted = runCatching {
            background(Runnable {
                HostThreadGuard.run("semantic_store_load") {
                    try {
                        val now = clock()
                        cache.restore(store.load(now, cacheTtlMs), now)
                        lastFlushAt = now
                    } finally {
                        storeLoaded = true
                    }
                }
            })
        }.getOrDefault(false)
        // 投递被拒（队列满）：放开标记，下次使用时再试；在此之前写盘也保持禁止。
        if (!accepted) storeLoadScheduled.set(false)
    }

    /** 新结果攒够 [FLUSH_THRESHOLD] 条或距上次落盘超过 [FLUSH_INTERVAL_MS] 时，在后台整份写盘。 */
    private fun maybeFlush() {
        val store = store ?: return
        if (!storeLoaded) return
        val now = clock()
        val dirty = cache.dirtyCount()
        if (dirty == 0 || (dirty < FLUSH_THRESHOLD && now - lastFlushAt < FLUSH_INTERVAL_MS)) return
        if (!flushScheduled.compareAndSet(false, true)) return
        val accepted = runCatching {
            background(Runnable {
                HostThreadGuard.run("semantic_store_flush") {
                    try {
                        store.save(cache.snapshot(clock()))
                        lastFlushAt = clock()
                    } finally {
                        flushScheduled.set(false)
                    }
                }
            })
        }.getOrDefault(false)
        if (!accepted) flushScheduled.set(false)
    }

    /** 一个待发分批：文本与它们在调用方列表里的位置一一对应（同一 key 只出现一次）。 */
    private class Chunk(val texts: List<String>, val keys: List<String>)

    /** 一个分批对某一个来源（含它的拆分与补发）能发出的请求上界；见 [fetchFrom] 的 budget 参数。 */
    private class RequestBudget(val limit: Int = MAX_REQUESTS_PER_CHUNK) {
        var spent = 0
    }

    private class PendingItem(val text: String, val key: String)

    /**
     * 当前分批上限（**条目数**）：调用方设定与后端上限都按"题数"（一题 = 一条文本 × 一条规则），
     * 除以规则数换算成条目，再和探明的条目上限（被拒后对半拆分学到的）取小。
     */
    private fun chunkLimit(): Int {
        val questions = minOf(batchSize, MAX_BATCH_LIMIT, slots.minOf { it.source.backend.maxBatch(it.source.endpoint) })
        val learned = slots.minOf { learnedBatchLimits[it.source.learnKey] ?: Int.MAX_VALUE }
        return minOf(questions / rules.size.coerceAtLeast(1), learned).coerceAtLeast(1)
    }

    /**
     * 每条文本只算一次摘要键。[onReport] 只在真的发出请求时回调（WAIT 在当前线程，PREFETCH 在后台线程）。
     *
     * WAIT 的网络一律在模块自有线程池上并发执行，调用方只用 `Future.get(剩余时间)` 等到**硬截止**：
     * `HttpURLConnection` 的超时不覆盖 DNS 解析，直接在宿主回调线程上发请求可能远超预算。截止后未完成的
     * 请求继续在后台跑完并写缓存（下次加载命中），本次这些条目为 UNKNOWN、按规则放行。
     *
     * PREFETCH 不立即发：先进合并队列，攒满一批或 [COALESCE_MS] 后一起发（见 [enqueuePrefetch]）。
     */
    fun evaluate(
        texts: List<String>,
        mode: SemanticMode,
        onReport: ((SemanticBatchReport, List<String>, List<SemanticVerdict>) -> Unit)? = null
    ): List<SemanticVerdict> {
        ensureStoreLoaded()
        val now = clock()
        val keys = texts.map { if (it.isBlank()) null else keyOf(it) }
        val scores = FloatArray(texts.size) { Float.NaN }
        val missing = ArrayList<Int>()
        keys.forEachIndexed { index, key ->
            key ?: return@forEachIndexed
            val cached = cache.get(key, now)
            if (cached.isNaN()) missing += index else scores[index] = cached
        }
        if (missing.isEmpty() || !enabled || slots.none { it.available(now) } || mode == SemanticMode.CACHE_ONLY) {
            return scores.map(::verdictOf)
        }
        // 同一文本已有请求在途（另一条路径正在判）时不重复计费；本次对它按 UNKNOWN 处理。
        // 按 key 分组而不是逐条 claim：同一列表里出现两段相同文本（同一条广告文案、两条一样的评论）时，
        // 逐条 claim 会让第二条被自己的第一条挡掉、留在 UNKNOWN，于是同一条内容第一张删第二张留。
        val positionsByKey = LinkedHashMap<String, MutableList<Int>>()
        missing.forEach { index ->
            positionsByKey.getOrPut(keys[index]!!) { ArrayList(1) } += index
        }
        val claimedKeys = positionsByKey.keys.filter { inFlight.add(it) }
        if (claimedKeys.isEmpty()) return scores.map(::verdictOf)
        if (mode == SemanticMode.PREFETCH) {
            enqueuePrefetch(claimedKeys.map { key -> PendingItem(texts[positionsByKey.getValue(key)[0]], key) }, onReport)
            return scores.map(::verdictOf)
        }
        val chunks = claimedKeys.chunked(chunkLimit()).map { chunk ->
            // 每个 key 只占一个位置（代表它的那一条），其余同 key 的位置在下面按 key 回填。
            Chunk(chunk.map { texts[positionsByKey.getValue(it)[0]] }, chunk)
        }
        val tasks = dispatch(chunks, mode, null, now)
        val deadline = now + timeoutMs
        val results = tasks.mapIndexed { index, task ->
            if (task.isCancelled) return@mapIndexed ChunkResult.failed("rejected", chunks[index].texts.size)
            val remaining = deadline - clock()
            if (remaining <= 0 && !task.isDone) return@mapIndexed null
            runCatching { task.get(remaining.coerceAtLeast(0L), TimeUnit.MILLISECONDS) }.getOrNull()
        }
        chunks.forEachIndexed { chunkIndex, chunk ->
            val result = results[chunkIndex] ?: return@forEachIndexed
            chunk.keys.forEachIndexed { position, key ->
                val score = result.scores.getOrElse(position) { Float.NaN }
                positionsByKey.getValue(key).forEach { scores[it] = score }
            }
        }
        onReport?.let { report(chunks, results, keys.count { it != null } - missing.size, now, it) }
        return scores.map(::verdictOf)
    }

    /**
     * 预取合并：宿主的列表 getter 会为每条回复的楼中楼预览（1–2 条）各调一次，2026-09-30 压测里 113 个请求有
     * 106 个只带 1–2 条，每个都要重付约 1300 token 的规则正文，还把后台队列挤满。这里把各次调用的待判文本
     * 攒起来：满一批立即发，不满的最多等 [COALESCE_MS] 再发。预取本来就不等结果，合并只让结论晚到这么一点。
     * 合并批次的观测日志记在最近一次提供的 [onReport] 名下。
     */
    private fun enqueuePrefetch(items: List<PendingItem>, onReport: ((SemanticBatchReport, List<String>, List<SemanticVerdict>) -> Unit)?) {
        val ready = ArrayList<List<PendingItem>>()
        var arm = false
        val reporter: ((SemanticBatchReport, List<String>, List<SemanticVerdict>) -> Unit)?
        synchronized(pendingLock) {
            pendingItems += items
            if (onReport != null) pendingReport = onReport
            val limit = chunkLimit()
            while (pendingItems.size >= limit) {
                ready += ArrayList(pendingItems.subList(0, limit))
                pendingItems.subList(0, limit).clear()
            }
            if (pendingItems.isNotEmpty() && !flushArmed) {
                flushArmed = true
                arm = true
            }
            reporter = pendingReport
        }
        if (ready.isNotEmpty()) {
            dispatch(ready.map { chunk -> Chunk(chunk.map { it.text }, chunk.map { it.key }) },
                SemanticMode.PREFETCH, reporter, clock())
        }
        if (arm && !runCatching { scheduler(COALESCE_MS, Runnable { flushPending() }) }.getOrDefault(false)) {
            // 定时器不可用：直接发，宁可小批也不能把条目永远卡在队列里。
            flushPending()
        }
    }

    private fun flushPending() {
        val items: List<PendingItem>
        val reporter: ((SemanticBatchReport, List<String>, List<SemanticVerdict>) -> Unit)?
        synchronized(pendingLock) {
            items = ArrayList(pendingItems)
            pendingItems.clear()
            flushArmed = false
            reporter = pendingReport
        }
        if (items.isEmpty()) return
        HostThreadGuard.run("semantic_prefetch_flush") {
            dispatch(items.chunked(chunkLimit()).map { chunk -> Chunk(chunk.map { it.text }, chunk.map { it.key }) },
                SemanticMode.PREFETCH, reporter, clock())
        }
    }

    /**
     * 把分批投到线程池。被拒（队列满）的分批立即释放在途标记、记为 `rejected`（下次见到会重判）。
     * PREFETCH 的观测日志由"最后一个完成的分批"顺手写出：不另占线程、不阻塞等待。
     */
    private fun dispatch(
        chunks: List<Chunk>,
        mode: SemanticMode,
        onReport: ((SemanticBatchReport, List<String>, List<SemanticVerdict>) -> Unit)?,
        started: Long
    ): List<java.util.concurrent.FutureTask<ChunkResult>> {
        val pending = java.util.concurrent.atomic.AtomicInteger(chunks.size)
        val tasks = ArrayList<java.util.concurrent.FutureTask<ChunkResult>>(chunks.size)
        chunks.forEach { chunk ->
            val task = object : java.util.concurrent.FutureTask<ChunkResult>({
                try {
                    HostThreadGuard.call("semantic_fetch", ChunkResult.failed("guard", chunk.texts.size)) {
                        fetchChunk(chunk.texts, chunk.keys, hedge = mode == SemanticMode.WAIT)
                    }
                } finally {
                    inFlight.removeAll(chunk.keys.toSet())
                }
            }) {
                override fun done() {
                    if (mode != SemanticMode.PREFETCH || onReport == null || pending.decrementAndGet() != 0) return
                    HostThreadGuard.run("semantic_prefetch_report") {
                        // 报告不另占线程也不阻塞等待，所以兄弟分批多半还在跑：这些记成 "pending"，
                        // 不能当成 null——那会被汇总成 "deadline"，把一次正常的预取报成超时。
                        report(chunks, tasks.mapIndexed { index, t ->
                            val size = chunks[index].texts.size
                            when {
                                t.isCancelled -> ChunkResult.failed("rejected", size)
                                !t.isDone -> ChunkResult.failed("pending", size)
                                else -> runCatching { t.get() }.getOrNull() ?: ChunkResult.failed("pending", size)
                            }
                        }, 0, started, onReport)
                    }
                }
            }
            tasks += task
        }
        val submit = if (mode == SemanticMode.WAIT) waitBackground else background
        tasks.forEachIndexed { index, task ->
            if (!runCatching { submit(task) }.getOrDefault(false)) {
                inFlight.removeAll(chunks[index].keys.toSet())
                task.cancel(false)
            }
        }
        return tasks
    }

    private fun report(
        chunks: List<Chunk>,
        results: List<ChunkResult?>,
        hits: Int,
        started: Long,
        onReport: (SemanticBatchReport, List<String>, List<SemanticVerdict>) -> Unit
    ) {
        val batch = chunks.flatMap { it.texts }
        val verdicts = chunks.flatMapIndexed { index, chunk ->
            val result = results[index]
            chunk.texts.indices.map { position -> verdictOf(result?.scores?.getOrNull(position) ?: Float.NaN) }
        }
        val outcome = results.firstOrNull { it != null && it.outcome != "ok" }?.outcome
            ?: if (results.any { it == null }) "deadline" else "ok"
        val usage = results.mapNotNull { it?.usage }.reduceOrNull(SemanticUsage::plus)
        val variant = results.flatMap { it?.variants.orEmpty() }.toSortedSet().joinToString(",")
        val sources = results.flatMap { it?.sources.orEmpty() }.toSortedSet().joinToString(",")
        onReport(
            SemanticBatchReport(batch.size + hits, hits, batch.size, verdicts.count { it == SemanticVerdict.BLOCK },
                clock() - started, outcome, usage, variant, sources, results.count { it?.hedged == true },
                results.sumOf { it?.withheld ?: 0 }, results.sumOf { it?.refilled ?: 0 }),
            batch,
            verdicts
        )
    }

    /**
     * 一个分批：挑来源 → 请求 → 解析 → 写缓存。
     *
     * 1. **对冲**（[hedge] 且本面有 ≥2 个来源）：用户正在等的分批先发给估计最快的来源；超过它平常耗时
     *    （[hedgeDelayMs]）还没回来，就把同一批再发给另一个来源，谁先成功用谁。只影响尾延迟，额外花费只发生在
     *    慢的那一小部分分批上；落后的那份照常跑完写缓存。
     * 2. **故障转移**：一个来源失败（网络、HTTP 错误、解析失败、冷却中）后，**只把仍未判出的条目**交给下一个
     *    可用来源；已判出的不重复计费。来源"成功但个别条目缺项"不换来源（换来源会重复计费整批）。
     * 3. **灰区复核**（≥2 个来源）：成功后，分数紧挨屏蔽门槛（±[GRAY_BAND]）的条目在后台交给另一个来源再判一次，
     *    两个分数取平均写回缓存；本次仍按第一个分数，下次加载用平均值。每个条目只复核一次。
     */
    private fun fetchChunk(texts: List<String>, keys: List<String>, hedge: Boolean = false): ChunkResult {
        val scores = FloatArray(texts.size) { Float.NaN }
        val tried = HashSet<SemanticSourcePool.Slot>()
        var pending: List<Int> = texts.indices.toList()
        var usage: SemanticUsage? = null
        val variants = sortedSetOf<String>()
        val sources = sortedSetOf<Int>()
        var outcome = "cooldown"
        var hedged = false
        var refilled = 0
        val origins = arrayOfNulls<SemanticSourcePool.Slot>(texts.size)
        fun absorb(result: ChunkResult, positions: List<Int>) {
            val origin = result.sources.singleOrNull()?.let { index -> slots.firstOrNull { it.source.index == index } }
            positions.forEachIndexed { position, index ->
                result.scores.getOrNull(position)?.takeIf { !it.isNaN() }?.let {
                    scores[index] = it
                    if (origins[index] == null) origins[index] = origin
                }
            }
            result.usage?.let { usage = usage?.plus(it) ?: it }
            variants += result.variants
            sources += result.sources
            outcome = result.outcome
            refilled += result.refilled
        }
        if (hedge && slots.size > 1) {
            race(texts, keys, tried)?.let { (result, raced) ->
                hedged = raced
                absorb(result, pending)
                pending = if (result.outcome == "ok") emptyList() else pending.filter { scores[it].isNaN() }
            }
        }
        while (pending.isNotEmpty()) {
            val slot = pool.acquire(slots, clock(), tried) ?: break
            tried += slot
            val result = try {
                fetchFrom(slot, pending.map { texts[it] }, pending.map { keys[it] })
            } finally {
                pool.release(slot)
            }
            absorb(result, pending)
            if (result.outcome == "ok") break
            pending = pending.filter { scores[it].isNaN() }
        }
        var withheld = 0
        if (outcome == "ok") withheld = secondOpinion(texts, keys, scores, origins, tried)
        return ChunkResult(scores, outcome, usage, variants, sources, hedged, withheld, refilled)
    }

    /** 对冲前等多久：该来源平滑耗时的 [HEDGE_FACTOR] 倍，夹在 [HEDGE_MIN_MS]–[HEDGE_MAX_MS]。 */
    private fun hedgeDelayMs(slot: SemanticSourcePool.Slot): Long =
        (slot.latencyMs * HEDGE_FACTOR).toLong().coerceIn(HEDGE_MIN_MS, HEDGE_MAX_MS)

    /**
     * 对冲赛跑：返回（结果, 是否真的发了第二份）；执行器不可用时返回 null，调用方走普通流程。
     * 两份都失败时把两份结果合并（各自判出的条目都保留），由调用方继续故障转移剩下的条目。
     */
    private fun race(
        texts: List<String>,
        keys: List<String>,
        tried: MutableSet<SemanticSourcePool.Slot>
    ): Pair<ChunkResult, Boolean>? {
        val submit = hedgeSubmit ?: return null
        val first = pool.acquire(slots, clock(), tried) ?: return null
        val results = java.util.concurrent.LinkedBlockingQueue<ChunkResult>()
        fun launch(slot: SemanticSourcePool.Slot): Boolean {
            val accepted = runCatching {
                submit(Runnable {
                    val result = try {
                        HostThreadGuard.call("semantic_hedge", ChunkResult.failed("guard", texts.size)) {
                            fetchFrom(slot, texts, keys)
                        }
                    } finally {
                        pool.release(slot)
                    }
                    results.offer(result)
                })
            }.getOrDefault(false)
            if (!accepted) pool.release(slot)
            return accepted
        }
        if (!launch(first)) return null
        tried += first
        results.poll(hedgeDelayMs(first), TimeUnit.MILLISECONDS)?.let { return it to false }
        var launched = 1
        pool.acquire(slots, clock(), tried)?.let { second ->
            if (launch(second)) {
                tried += second
                launched++
            }
        }
        var merged: ChunkResult? = null
        repeat(launched) {
            val next = results.poll(requestTimeoutMs * 2L, TimeUnit.MILLISECONDS) ?: return@repeat
            if (next.outcome == "ok") return next to (launched > 1)
            merged = merged?.let { ChunkResult.union(it, next) } ?: next
        }
        return (merged ?: ChunkResult.failed("deadline", texts.size)) to (launched > 1)
    }

    /**
     * 复核（本面有 ≥2 个来源时，后台进行，被拒就跳过），返回本次"先不执行"的条目数：
     * - **灰区**：分数在门槛 ±[GRAY_BAND] 内的条目；
     * - **屏蔽复核**：来源判"屏蔽"的条目。新来源、可信度不高的来源全部复核，已可靠的抽 [BLOCK_AUDIT_RATE]。
     *   复核结果更新该来源的可信度（复核也判屏蔽 = 一致；明确低于门槛 [DISPUTE_MARGIN] = 被推翻）。
     * - **低可信来源的屏蔽先不执行**：可信度低于 [SemanticSourcePool.LOW_TRUST] 的来源判的"屏蔽"本次按未判出处理
     *   （内容照常显示）、不进缓存，等复核后按两个来源的可信度加权写入。依据 2026-09-30 真机：OpenRouter 上的
     *   Respan Span-01 Lite 一批 24 条删了 8 条正常评论，同批 JEV 0 条。
     * 复核结果按两个来源的可信度加权合并写回缓存；每个条目只复核一次。
     */
    private fun secondOpinion(
        texts: List<String>,
        keys: List<String>,
        scores: FloatArray,
        origins: Array<SemanticSourcePool.Slot?>,
        used: Set<SemanticSourcePool.Slot>
    ): Int {
        if (slots.size < 2) return 0
        val audit = java.util.concurrent.ThreadLocalRandom.current()
        val picked = texts.indices.filter { index ->
            val score = scores[index]
            if (score.isNaN()) return@filter false
            val origin = origins[index]
            val gray = kotlin.math.abs(score - blockThreshold) <= GRAY_BAND
            val block = score >= blockThreshold && origin != null &&
                (origin.checks < TRUSTED_CHECKS || origin.trust < TRUSTED_LEVEL || audit.nextDouble() < BLOCK_AUDIT_RATE)
            (gray || block) && reviewed.add(keys[index])
        }
        if (picked.isEmpty()) return 0
        if (reviewed.size > MAX_REVIEWED) reviewed.clear()
        val firstScores = picked.map { scores[it] }
        // 低可信来源的"屏蔽"：本次不执行、不留在缓存里，等复核。
        var withheld = 0
        picked.forEach { index ->
            if (scores[index] >= blockThreshold && origins[index]?.untrusted == true) {
                cache.invalidate(keys[index])
                scores[index] = Float.NaN
                withheld++
            }
        }
        val accepted = runCatching {
            background(Runnable {
                HostThreadGuard.run("semantic_second_opinion") review@{
                    val exclude = used + picked.mapNotNull { origins[it] }
                    val slot = pool.acquire(slots, clock(), exclude) ?: return@review
                    val second = try {
                        fetchFrom(slot, picked.map { texts[it] }, picked.map { keys[it] })
                    } finally {
                        pool.release(slot)
                    }
                    val now = clock()
                    picked.forEachIndexed { position, index ->
                        val other = second.scores.getOrNull(position) ?: Float.NaN
                        val first = firstScores[position]
                        if (other.isNaN() || first.isNaN()) return@forEachIndexed
                        val origin = origins[index]
                        if (origin != null && first >= blockThreshold) {
                            when {
                                other >= blockThreshold -> origin.recordReview(agreed = true)
                                other < blockThreshold - DISPUTE_MARGIN -> origin.recordReview(agreed = false)
                            }
                            persistLearned()
                        }
                        val w1 = origin?.trust ?: 0.5
                        val w2 = slot.trust
                        cache.put(keys[index], ((first * w1 + other * w2) / (w1 + w2)).toFloat(), now)
                    }
                    maybeFlush()
                }
            })
        }.getOrDefault(false)
        if (!accepted) picked.forEach { reviewed.remove(keys[it]) }
        return withheld
    }

    /**
     * 向一个来源发一个分批。被拒（[SemanticBackend.shouldFallback] 的状态码，或 2xx 但响应结构认不出）时按顺序处理：
     * 1. 分批大于 [MIN_SPLIT] 条：**先对半拆分**分别重试（仍用这个来源）。2026-09-30 压测：第三方中转每请求最多
     *    32 题，33 题起一律 400（"bounded typed questions"），与题型无关。拆小后用被拒时同一种写法成功，
     *    就把成功的大小记为该来源的分批上限，之后直接按它切。
     * 2. 已经很小：换下一个请求变体（题型 / 参数写法）立即重试；可用变体按来源写进进程级共享表。
     * 都失败才让这个来源冷却。变体不进缓存指纹：各变体给出的都是"屏蔽概率"，换变体不作废旧判定。
     */
    private fun fetchFrom(
        slot: SemanticSourcePool.Slot,
        texts: List<String>,
        keys: List<String>,
        rejectedVariant: Int = -1,
        /** 一次请求成功后，是否允许把漏答的条目补发一次（补发本身不再补发）。 */
        refill: Boolean = true,
        /**
         * 这个分批的请求预算。被拒的批会**先对半拆分再换写法**：拆分树的内部节点各发一次，每个叶子又要把
         * 变体阶梯整条爬一遍。一个完全不被接受的服务上，一个 20 条分批会展开成几十次请求，而
         * `slot.penalize` 要到叶子走完才触发，冷凝来不及。共享一份预算把上界钉死。
         */
        budget: RequestBudget = RequestBudget()
    ): ChunkResult {
        if (!slot.available(clock())) return ChunkResult.failed("cooldown", texts.size)
        val source = slot.source
        val backend = source.backend
        val identity = source.learnKey
        val clipped = texts.map(::clip)
        var usage: SemanticUsage? = null
        val start = learnedVariants[identity] ?: 0
        var variant = start
        fun done(outcome: String, scores: FloatArray = FloatArray(texts.size) { Float.NaN }) =
            ChunkResult(scores, outcome, usage, sortedSetOf(backend.variantName(variant)), sortedSetOf(source.index))
        while (true) {
            // 预算用尽：不再发，剩下的条目按未判出放行（fail-open）。
            // 这里**不能**冷却来源：一次请求都没发出去，来源没做错任何事。冷却只会把刚刚成功
            // 应答、刚 recover() 过的来源按 15→30→…→300s 一路禁掉，害得所有过滤面一起变 UNKNOWN。
            if (budget.spent >= budget.limit) return done("budget")
            budget.spent += 1
            val sentAt = clock()
            val body = backend.encode(clipped, rules, variant, guidanceText, source.endpoint)
            val response = runCatching {
                if (transport != null) transport.invoke(body, source.apiKey, requestTimeoutMs)
                else httpPost(source.endpoint, body, source.apiKey, requestTimeoutMs)
            }.getOrNull()
            if (response == null) {
                slot.penalize(FAILURE_COOLDOWN_MS, clock())
                return done("network")
            }
            slot.recordLatency(clock() - sentAt)
            val (status, payload) = response
            backend.usageOf(payload)?.let { current -> usage = usage?.plus(current) ?: current }
            val decoded = if (status in 200..299) backend.decode(payload, texts.size) else null
            // 2xx 但一条分数都没有（截断、题型不认、答非所问）同样算"这种写法不行"，换写法；
            // 以前会当成成功，整批白判（2026-09-30 基元律动 GLM 截断案例）。
            val empty = decoded != null && decoded.isNotEmpty() && decoded.all { it.isNaN() }
            val unsupported = backend.shouldFallback(status) || (status in 200..299 && (decoded == null || empty))
            if (unsupported && texts.size > MIN_SPLIT) {
                val mid = texts.size / 2
                val first = fetchFrom(slot, texts.subList(0, mid), keys.subList(0, mid),
                    rejectedVariant = variant, refill = refill, budget = budget)
                val second = fetchFrom(slot, texts.subList(mid, texts.size), keys.subList(mid, keys.size),
                    rejectedVariant = variant, refill = refill, budget = budget)
                return ChunkResult.merge(first, second, usage)
            }
            if (unsupported) {
                var next = variant + 1
                while (next < backend.variants && !backend.applies(next, source.endpoint)) next++
                if (next < backend.variants) {
                    variant = next
                    continue
                }
            }
            if (status !in 200..299) {
                val now = clock()
                slot.penalize(if (status in AUTH_FAILURES) AUTH_COOLDOWN_MS else FAILURE_COOLDOWN_MS, now)
                // 限流：服务告诉了重置时刻（如 OpenRouter 免费档每日 50 次）就冷却到那时，不再每几分钟白撞一次。
                if (status == 429) SemanticSourcePool.rateLimitResetAt(payload, now)?.let { slot.holdUntil(it, now) }
                return done("http-$status")
            }
            if (decoded == null) {
                // 补发里一条都没判出不是来源的错，主请求刚成功，冷却会让所有过滤面在冷却期内失效。
                if (!refill && status in 200..299) return done("parse")
                slot.penalize(FAILURE_COOLDOWN_MS, clock())
                return done("parse")
            }
            // 换过变体且成功才记住：422 也可能是与写法无关的错误（如内容超长），失败时不能把进程永久降级。
            // 并发的分批可能同时探到：只前进不后退。
            if (variant != start) {
                learnedVariants.merge(identity, variant) { old, new -> maxOf(old, new) }
                persistLearned()
            }
            // 拆小后用**被拒时同一种写法**成功：这个大小就是该来源可用的分批上限（只缩不放）。
            // 换了写法才成功（或另一半直接用了新学到的写法）说明被拒是写法问题，不记。
            if (rejectedVariant >= 0 && variant == rejectedVariant) {
                learnedBatchLimits.merge(identity, texts.size) { old, new -> minOf(old, new) }
                persistLearned()
            }
            slot.recover()
            val now = clock()
            decoded.forEachIndexed { index, score -> if (!score.isNaN()) cache.put(keys[index], score, now) }
            maybeFlush()
            if (!refill) return done("ok", decoded)
            return refillMissing(slot, texts, keys, decoded, done("ok", decoded), budget)
        }
    }

    /**
     * 补发漏答：来源成功返回但**部分**条目没有分数（模型跳过了几条）时，只把缺的那几条原样补发一次
     * （同一来源、同一种已探明写法），合并分数。缺得少（不到 [REFILL_MIN_MISSING] 条或不到 [REFILL_MIN_FRACTION]）
     * 不补：补一次的固定开销（规则正文等）不划算。补发失败不影响已判出的条目，缺的仍按未判出放行。
     * 一条分数都没有的情况在上游已按"写法不行"处理，不会走到这里。
     */
    private fun refillMissing(
        slot: SemanticSourcePool.Slot,
        texts: List<String>,
        keys: List<String>,
        decoded: FloatArray,
        result: ChunkResult,
        budget: RequestBudget
    ): ChunkResult {
        val missing = decoded.indices.filter { decoded[it].isNaN() }
        if (missing.size < REFILL_MIN_MISSING || missing.size < texts.size * REFILL_MIN_FRACTION) return result
        val again = fetchFrom(slot, missing.map { texts[it] }, missing.map { keys[it] }, refill = false, budget = budget)
        val scores = decoded.copyOf()
        var filled = 0
        missing.forEachIndexed { position, index ->
            val score = again.scores.getOrNull(position) ?: Float.NaN
            if (!score.isNaN()) {
                scores[index] = score
                filled++
            }
        }
        val usage = listOfNotNull(result.usage, again.usage).reduceOrNull(SemanticUsage::plus)
        return ChunkResult(scores, result.outcome, usage, result.variants + again.variants, result.sources + again.sources,
            refilled = filled)
    }

    private class ChunkResult(
        val scores: FloatArray,
        val outcome: String,
        val usage: SemanticUsage? = null,
        /** 实际用到的请求变体名（观测日志）。 */
        val variants: Set<String> = emptySet(),
        /** 实际用到的来源编号（观测日志）。 */
        val sources: Set<Int> = emptySet(),
        /** 这一批是否发了对冲请求（观测日志）。 */
        val hedged: Boolean = false,
        /** 低可信来源判"屏蔽"、本次先不执行的条数（观测日志）。 */
        val withheld: Int = 0,
        /** 漏答后补发、补回分数的条数（观测日志）。 */
        val refilled: Int = 0
    ) {
        companion object {
            fun failed(outcome: String, size: Int = 0) = ChunkResult(FloatArray(size) { Float.NaN }, outcome)

            /** 同一批的两份结果（对冲的两个来源都失败时）：逐条取判出的那个，用量与来源合并。 */
            fun union(first: ChunkResult, second: ChunkResult): ChunkResult {
                val scores = FloatArray(first.scores.size) { index ->
                    first.scores[index].takeIf { !it.isNaN() } ?: second.scores.getOrElse(index) { Float.NaN }
                }
                val usage = listOfNotNull(first.usage, second.usage).reduceOrNull(SemanticUsage::plus)
                return ChunkResult(scores, second.outcome, usage, first.variants + second.variants, first.sources + second.sources,
                    refilled = first.refilled + second.refilled)
            }

            /** 拆分后的两半拼回一个结果；[extra] 是被拒那次请求自己的用量。 */
            fun merge(first: ChunkResult, second: ChunkResult, extra: SemanticUsage?): ChunkResult {
                val usage = listOfNotNull(extra, first.usage, second.usage).reduceOrNull(SemanticUsage::plus)
                val outcome = if (first.outcome != "ok") first.outcome else second.outcome
                return ChunkResult(first.scores + second.scores, outcome, usage,
                    first.variants + second.variants, first.sources + second.sources,
                    refilled = first.refilled + second.refilled)
            }
        }
    }

    private fun verdictOf(score: Float): SemanticVerdict = when {
        score.isNaN() -> SemanticVerdict.UNKNOWN
        score >= blockThreshold -> SemanticVerdict.BLOCK
        else -> SemanticVerdict.KEEP
    }

    private fun keyOf(text: String): String = digest128("$ruleFingerprint\u0000${clip(text)}")

    companion object {
        const val ENDPOINT = "https://api.typesafe.ai/v1/systemone"
        const val MODEL = "jev-latest"
        const val DEFAULT_TIMEOUT_MS = 2_500
        /** 单个请求的最短网络超时（连接、读取各一半）。 */
        const val MIN_REQUEST_TIMEOUT_MS = 12_000
        const val MAX_BATCH = 24
        /** 官方文档单请求上下文 64k（state + 最长一题 ≤ 32k）；200 题（一题 = 一条 × 一条规则）短文本约 12k token，留足余量。 */
        const val MAX_BATCH_LIMIT = 200
        const val MAX_TEXT_CHARS = 600
        const val MAX_API_KEY_LENGTH = 512
        const val MAX_ENDPOINT_LENGTH = 512
        const val FAILURE_COOLDOWN_MS = 15_000L
        const val AUTH_COOLDOWN_MS = 60_000L
        /** 模块网络池：并发上限与排队上限；满了直接拒绝（这批只走规则），绝不无界堆积。 */
        /**
         * 预取池（后台判定、落盘、启动清理）与等待池（WAIT）分开，各自有界。
         * 队列按"整段突发"设容量：拆题后一题 = 一条 × 一条规则，弹幕一段 300 条 ≈ 50 个分批任务
         * （JEV 中转 32 题、默认 5 条规则 ≈ 每批 6 条），旧容量 16 会当场拒掉约 2/3 的分批、那一轮只走规则。
         * 等待池 8 线程按 3 s 首屏预算取（约 50 个请求 × 每请求 0.4 s）；超出预算的分批按规则放行、后台补判。
         */
        private const val NETWORK_THREADS = 4
        private const val BACKGROUND_QUEUE = 48
        private const val WAIT_THREADS = 8
        private const val WAIT_QUEUE = 48
        /** 多来源时线程数随来源数增加（每多一个来源，后台 +2、等待 +2），封顶如下。 */
        private const val MAX_NETWORK_THREADS = 8
        private const val MAX_WAIT_THREADS = 10
        /** 对冲 / 并行尝试的线程上限（只在 ≥2 个来源且用户在等时用）。 */
        private const val MAX_HEDGE_THREADS = 8

        /** 对冲：等到该来源平常耗时的这么多倍还没回来才发第二份。 */
        const val HEDGE_FACTOR = 1.3
        const val HEDGE_MIN_MS = 300L
        const val HEDGE_MAX_MS = 1_500L

        /** 灰区：分数离屏蔽门槛这么近就请第二个来源复核。 */
        const val GRAY_BAND = 0.15f
        /** 复核分数比门槛低这么多，算"推翻"了原来源的屏蔽。 */
        const val DISPUTE_MARGIN = 0.2f
        /** 复核记录达到这么多、可信度达到 [TRUSTED_LEVEL] 后，屏蔽只抽样复核。 */
        const val TRUSTED_CHECKS = 20.0
        const val TRUSTED_LEVEL = 0.8
        const val BLOCK_AUDIT_RATE = 0.1
        private const val MAX_REVIEWED = 4_096

        /** 预取合并的最长等待。 */
        const val COALESCE_MS = 250L

        /** 漏答补发的门槛：缺至少这么多条，且缺的比例超过 [REFILL_MIN_FRACTION]。 */
        const val REFILL_MIN_MISSING = 2
        const val REFILL_MIN_FRACTION = 0.25

        /** 被拒时对半拆分的下限：不大于它就不再拆，改试下一个请求变体。 */
        const val MIN_SPLIT = 4
        /**
         * 一个分批**对某一个来源**（含它的拆分与补发）能发出的请求上界。正常情况一次到位；只有
         * "大小被拒"才会用到拆分，那种情况下二十来次足够走完整棵拆分树。真正的用处是兜住
         * "完全不被接受的服务"：那时每个叶子都要把变体阶梯整条爬一遍，没有上界就会在来源冷却
         * 生效之前把配额烧掉。
         *
         * 故障转移是每个来源一份预算：否则一个坏来源会把预算吃光，健康的那个再也轮不上。
         */
        private const val MAX_REQUESTS_PER_CHUNK = 24
        private val AUTH_FAILURES = setOf(401, 402, 403)
        private const val HEX = "0123456789abcdef"

        private const val FLUSH_THRESHOLD = 50
        private const val FLUSH_INTERVAL_MS = 30_000L

        /** 送判文本与缓存键共用：归一化（全角、零宽、连续空白）后截断。 */
        internal fun clip(text: String): String = TextNormalizer.forSemantic(text).take(MAX_TEXT_CHARS)

        /**
         * 硬盘文件头 / 缓存键指纹：来源组合（后端 + 模型 + 地址，按身份排序）+ 规则（含自定义类型的全部字段）
         * + 判定口径 + 题面结构版本；任一变化旧判定整份作废。Key 不参与（换 Key 不改变判定）。
         */
        fun fingerprintOf(rules: List<SemanticRule>, sources: List<SemanticSource>, guidance: String = ""): String =
            fingerprintOfIdentities(rules, sources.map { it.identity }, guidance)

        /** 单来源写法（单测与旧调用）。 */
        fun fingerprintOf(
            rules: List<SemanticRule>,
            endpoint: String,
            backend: SemanticBackend = JevBackend(),
            guidance: String = ""
        ): String = fingerprintOfIdentities(rules, listOf("${backend.id}\u0000${backend.model}\u0000$endpoint"), guidance)

        /**
         * 题面结构版本：换了"问题怎么出"（如 2026-10-13 从"一题问遍所有规则"拆成每规则一题，同日题面等义缩写）后，
         * 旧写法的判定分数不可比，进指纹让旧判定整份作废。
         */
        private const val QUESTION_SCHEMA = "per-rule-v3"

        private fun fingerprintOfIdentities(rules: List<SemanticRule>, identities: List<String>, guidance: String): String =
            digest128(
                QUESTION_SCHEMA + "\u0000" +
                    identities.sorted().joinToString("\u0005") + "\u0000" +
                    rules.joinToString("\u0001") { it.identity } + "\u0000" + SemanticGuidance.normalize(guidance)
            )

        /** 来源身份（后端 + 模型 + 地址）→ 已探明可用的请求变体；进程内共享，宿主重启后重新探测。 */
        private val learnedVariants = ConcurrentHashMap<String, Int>()

        /** 来源身份 → 被拒后拆分探明的分批上限；进程内共享，只缩不放。 */
        private val learnedBatchLimits = ConcurrentHashMap<String, Int>()

        /** 单测用：清空已探明的变体、分批上限与可信度。 */
        internal fun resetLearnedVariants() {
            learnedVariants.clear()
            learnedBatchLimits.clear()
            learnedTrust.clear()
            livePools.clear()
            learnedStore = createLearnedStore()
        }

        /**
         * 来源可信度的落盘副本：键 → (一致, 推翻)。来源池创建时套用（[registerPool]），
         * 写盘时从活着的来源池取最新值（[trustSnapshot]）。
         */
        private val learnedTrust = ConcurrentHashMap<String, Pair<Double, Double>>()
        private val livePools: MutableSet<SemanticSourcePool> =
            java.util.Collections.newSetFromMap(java.util.WeakHashMap())

        /** 来源池创建时登记；已读到的可信度立即套用（读盘晚于建池时，由 [attachLearnedStore] 补套）。 */
        internal fun registerPool(pool: SemanticSourcePool) {
            synchronized(livePools) { livePools += pool }
            applyTrust(pool)
        }

        private fun applyTrust(pool: SemanticSourcePool) {
            pool.slotsFor(pool.sources).forEach { slot ->
                learnedTrust[slot.source.learnKey]?.let { (agree, dispute) ->
                    slot.restoreTrust(agree, dispute)
                }
            }
        }

        private fun trustSnapshot(): Map<String, Pair<Double, Double>> {
            val out = HashMap(learnedTrust)
            synchronized(livePools) {
                livePools.forEach { pool ->
                    pool.slotsFor(pool.sources).forEach { slot ->
                        val trust = slot.trustSnapshot()
                        if (trust.first + trust.second > 0) out[slot.source.learnKey] = trust
                    }
                }
            }
            return out
        }

        /** 来源身份 → 共享表的键（128 位摘要；不含 Key，落盘也不暴露地址原文）。 */
        internal fun learnKeyOf(identity: String): String = digest128("learn\u0000$identity")

        /**
         * 已探明的写法与分批上限落盘：`files/bil_semantic/learned.txt`，每行 `v|b <键> <值>`。
         * 以前每次重启都要重新试错（Respan 那种只收 noul+文本的来源，每次要先被拒 3 次）；现在试出来一次就一直用。
         * 只存摘要与整数，不存 Key、地址与任何文本。读盘在后台，读到之前照常探测，结果按"只进不退 / 只缩不放"合并。
         */
        fun attachLearnedStore(root: File) {
            val file = File(File(root, "bil_semantic"), LEARNED_FILE)
            learnedStore.attach(file) { target ->
                if (!loadLearned(target)) false else {
                    // Complete the merge, including pools created before this background load,
                    // before initialization releases any queued saves.
                    synchronized(livePools) { livePools.toList() }.forEach(::applyTrust)
                    true
                }
            }
        }

        private fun loadLearned(file: File): Boolean = runCatching {
            if (!file.exists()) return@runCatching true
            if (!file.isFile) return@runCatching false
            // Oversized caches were already ignored; permit fresh learning to rebuild them.
            if (file.length() > MAX_LEARNED_BYTES) return@runCatching true
            // One immutable disk snapshot for variants, batch limits and trust. A concurrent
            // learner may update memory, but cannot replace the file until this merge completes.
            val lines = file.readLines(StandardCharsets.UTF_8)
            lines.forEach { line ->
                val parts = line.trim().split(' ')
                if (parts.size !in 3..4 || parts[1].length != 32) return@forEach
                val value = parts[2].toIntOrNull() ?: return@forEach
                when (parts[0]) {
                    "v" -> if (parts.size == 3 && value in 0..16) {
                        learnedVariants.merge(parts[1], value) { old, new -> maxOf(old, new) }
                    }
                    "b" -> if (parts.size == 3 && value in 1..MAX_BATCH_LIMIT) {
                        learnedBatchLimits.merge(parts[1], value) { old, new -> minOf(old, new) }
                    }
                    "t" -> if (parts.size == 4) {
                        val dispute = parts[3].toIntOrNull() ?: return@forEach
                        if (value in 0..100_000 && dispute in 0..100_000) {
                            learnedTrust[parts[1]] = value / 100.0 to dispute / 100.0
                        }
                    }
                }
            }
            true
        }.getOrDefault(false)

        @Volatile private var learnedStore = createLearnedStore()

        private fun createLearnedStore() = SemanticLearnedStore(
            schedule = ::submitBackground,
            snapshot = {
                buildString {
                    learnedVariants.forEach { (key, value) -> append("v ").append(key).append(' ').append(value).append('\n') }
                    learnedBatchLimits.forEach { (key, value) -> append("b ").append(key).append(' ').append(value).append('\n') }
                    trustSnapshot().forEach { (key, value) ->
                        append("t ").append(key).append(' ').append((value.first * 100).toInt()).append(' ')
                            .append((value.second * 100).toInt()).append('\n')
                    }
                }
            }
        )

        private fun persistLearned() {
            learnedStore.save()
        }

        const val LEARNED_FILE = "learned.txt"
        private const val MAX_LEARNED_BYTES = 16 * 1024L

        /** 预取合并的定时器：单个守护线程，空闲回收。 */
        private val timer: java.util.concurrent.ScheduledThreadPoolExecutor by lazy {
            java.util.concurrent.ScheduledThreadPoolExecutor(1) { runnable ->
                Thread(runnable, "BIL-SemanticTimer").apply { isDaemon = true }
            }.apply {
                setKeepAliveTime(30, TimeUnit.SECONDS)
                allowCoreThreadTimeOut(true)
                removeOnCancelPolicy = true
            }
        }

        private fun scheduleShared(delayMs: Long, task: Runnable): Boolean = try {
            timer.schedule(task, delayMs, TimeUnit.MILLISECONDS); true
        } catch (_: RejectedExecutionException) {
            false
        }

        /** 模块网络池（启动清理等一次性后台任务也用它）；满了返回 false。 */
        fun submitBackground(task: Runnable): Boolean = submitToSharedExecutor(task)

        /** 128 位摘要的十六进制；查表转换，不走 String.format。 */
        private val sha256 = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

        private fun digest128(value: String): String {
            val bytes = sha256.get()!!.digest(value.toByteArray(StandardCharsets.UTF_8))
            val out = CharArray(32)
            for (i in 0 until 16) {
                val v = bytes[i].toInt() and 0xFF
                out[i * 2] = HEX[v ushr 4]
                out[i * 2 + 1] = HEX[v and 0x0F]
            }
            return String(out)
        }

        /** 已配置的来源数（宿主安装期设一次）；决定线程池大小。 */
        @Volatile private var sourceCount = 1

        internal fun networkThreads(sources: Int = sourceCount) =
            (NETWORK_THREADS * sources.coerceAtLeast(1)).coerceAtMost(MAX_NETWORK_THREADS)

        internal fun waitThreads(sources: Int = sourceCount) =
            (WAIT_THREADS + 2 * (sources.coerceAtLeast(1) - 1)).coerceAtMost(MAX_WAIT_THREADS)

        /**
         * 按来源数调整线程池：单来源 4 / 8 线程；多来源时每个来源都能同时接活，
         * 否则第二个来源只是"备胎"，吞吐不涨。池已建好时就地调整。
         */
        fun configureConcurrency(sources: Int) {
            sourceCount = sources.coerceIn(1, SemanticSource.MAX_SOURCES)
            if (sharedExecutorLazy.isInitialized()) resize(sharedExecutorLazy.value, networkThreads())
            if (waitExecutorLazy.isInitialized()) resize(waitExecutorLazy.value, waitThreads())
        }

        private fun resize(executor: ThreadPoolExecutor, threads: Int) {
            if (threads > executor.maximumPoolSize) {
                executor.maximumPoolSize = threads
                executor.corePoolSize = threads
            } else {
                executor.corePoolSize = threads
                executor.maximumPoolSize = threads
            }
        }

        /** 模块自有网络池（各过滤面共用）；空闲 30 s 回收线程，队列有界，满了直接拒绝。 */
        private val sharedExecutorLazy = lazy {
            val counter = java.util.concurrent.atomic.AtomicInteger()
            val threads = networkThreads()
            ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, ArrayBlockingQueue(BACKGROUND_QUEUE)) { runnable ->
                Thread(runnable, "BIL-Semantic-${counter.incrementAndGet()}").apply { isDaemon = true }
            }.apply { allowCoreThreadTimeOut(true) }
        }
        private val sharedExecutor: ThreadPoolExecutor by sharedExecutorLazy

        private val waitExecutorLazy = lazy {
            val counter = java.util.concurrent.atomic.AtomicInteger()
            val threads = waitThreads()
            ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, ArrayBlockingQueue(WAIT_QUEUE)) { runnable ->
                Thread(runnable, "BIL-SemanticWait-${counter.incrementAndGet()}").apply { isDaemon = true }
            }.apply { allowCoreThreadTimeOut(true) }
        }
        private val waitExecutor: ThreadPoolExecutor by waitExecutorLazy

        /** 对冲线程：不排队（SynchronousQueue），满了直接拒绝，调用方退回普通流程。 */
        private val hedgeExecutor: ThreadPoolExecutor by lazy {
            val counter = java.util.concurrent.atomic.AtomicInteger()
            ThreadPoolExecutor(0, MAX_HEDGE_THREADS, 30, TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue()) { runnable ->
                Thread(runnable, "BIL-SemanticHedge-${counter.incrementAndGet()}").apply { isDaemon = true }
            }
        }

        private fun submitToHedgeExecutor(task: Runnable): Boolean = try {
            hedgeExecutor.execute(task); true
        } catch (_: RejectedExecutionException) {
            false
        }

        private fun submitToWaitExecutor(task: Runnable): Boolean = try {
            waitExecutor.execute(task); true
        } catch (_: RejectedExecutionException) {
            false
        }

        private fun submitToSharedExecutor(task: Runnable): Boolean = try {
            sharedExecutor.execute(task); true
        } catch (_: RejectedExecutionException) {
            false
        }

        /**
         * 规范化用户填的端点：只接受 http(s)；只给主机（或以 `/v1` 结尾）时补全 `/v1/systemone`，
         * 其余路径原样使用（第三方中转可能换了路径）。非法时返回 null。
         */
        internal fun normalizeEndpoint(raw: String): String? =
            if (raw.isBlank()) null else JevBackend().resolveEndpoint(raw)

        /**
         * 连接与读取各用一半预算。成功时读完响应体但**不 disconnect**，让连接回到 keep-alive 池，
         * 后续批次省掉 TLS 握手；只有异常路径才断开。
         */
        private fun withRateLimitHeaders(text: String, connection: HttpURLConnection): String {
            val headers = JSONObject()
            listOf("retry-after", "x-ratelimit-reset").forEach { name ->
                connection.getHeaderField(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { headers.put(name, it) }
            }
            if (headers.length() == 0) return text
            val root = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("raw", text.take(512)) }
            return root.put("_headers", headers).toString()
        }

        private fun httpPost(endpoint: String, body: ByteArray, apiKey: String, timeoutMs: Int): Pair<Int, String>? {
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            return try {
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.doOutput = true
                connection.connectTimeout = (timeoutMs / 2).coerceAtLeast(1)
                connection.readTimeout = (timeoutMs / 2).coerceAtLeast(1)
                connection.setFixedLengthStreamingMode(body.size)
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
                val text = SemanticHttpResponseReader.read(connection, status)
                // 限流：把重置相关的响应头并进返回体（`_headers`），冷却逻辑据此等到重置时刻。
                if (status == 429) status to withRateLimitHeaders(text, connection) else status to text
            } catch (_: Exception) {
                connection.disconnect()
                null
            }
        }
    }
}

/**
 * Jev `/v1/systemone` 的请求编码与响应解析；纯函数，便于单测。
 *
 * **每条文本 × 每条规则各出一题**（键 `item_{条目}_{规则}`），一题只判一种违规：官方把"一题问遍所有违规"
 * 明确列为反例——docs.typesafe.ai《How to build with TypeSafe》"Decompose the questions" 的
 * "One broad question (bad)"（`is_spam: "Is `message` spam?"`）对应 "Decomposed questions (good)"
 * 的每规则一题，原文 "Broad questions hide several judgments behind one answer. Atomic questions
 * expose those judgments so you can inspect, tune, and combine them in code."；《Models》亦要求
 * "Decompose broad judgments into atomic questions and combine the outputs in code."。
 * 同一请求里各题并行作答、互不影响（官方 "Ask multiple questions together"），拆题几乎不增加时延。
 * 结论由 [decodeBlockScores] 按条目取各规则的最大值，还原成"命中任一规则"的屏蔽概率再过门槛；
 * 不用 1-Π(1-p)：同一条文本同时命中多条规则高度正相关，连乘会把正常内容的分数抬过门槛。
 *
 * 规则与说明放在共享的 `state` 里只写一次，每题只用反引号引用：2026-09-29 真机 A/B（14 条标注样本 × 3 轮）
 * 输入 token 4044 → 2201，三档门槛均 14/14；逐题重复规则的写法会把正常讨论的屏蔽概率抬到 0.54–0.63。
 */
internal object JevRequestCodec {
    /** 默认判定说明（防注入前缀 + 默认口径）；用户可改口径，见 [SemanticGuidance]。 */
    internal val GUIDANCE: String get() = SemanticGuidance.of(null)

    /**
     * 判定口径两档含义（2026-10-13 等义缩写，见 QUESTION_SCHEMA）：保留"只判这一条规则"与
     * "只命中 `not_for` 排除项 = 保留"两层护栏语义，只去掉冗长句式；含义不可再删。
     */
    private const val BLOCK_MEANING = "Matches this one rule."
    private const val KEEP_MEANING = "No clear match, or fits only the rule's `not_for`."

    fun encode(
        texts: List<String>,
        rules: List<SemanticRule>,
        model: String = SemanticJudge.MODEL,
        format: JevQuestionFormat = JevQuestionFormat.CHOICE,
        guidance: String = SemanticGuidance.of(null),
        /**
         * true：state 写成一段文本（规则 JSON、说明、编号条目逐行），题目用"entry [i]"指代；
         * 给只收字符串 state 的同类接口（OpenRouter 上的 Respan）。false：JEV 原生的对象 state。
         */
        textState: Boolean = false
    ): ByteArray {
        val state: Any = if (textState) {
            buildString {
                append("Blocking rules (JSON): ").append(rulesJson(rules).toString()).append('\n')
                append("Guidance: ").append(guidance).append('\n')
                append("Entries:")
                texts.forEachIndexed { index, text -> append("\n[").append(index).append("] ").append(text) }
            }
        } else {
            val entries = JSONArray()
            texts.forEach { text -> entries.put(JSONObject().put("text", text)) }
            JSONObject()
                .put("rules", rulesJson(rules))
                .put("guidance", guidance)
                .put("entries", entries)
        }
        val questions = JSONObject()
        texts.indices.forEach { index ->
            rules.forEachIndexed { ruleIndex, rule ->
                // 一题只判一种违规（官方反例见本文件头注）：只指到这一条规则，不再问"是否命中任一规则"。
                // 指令等义缩写（2026-10-13）：去掉规则名复述（`rules[r]` 自带 type/covers），保留
                // "只判这一条目、只判这一条规则"的防串扰护栏与"Follow `guidance`"的用户口径生效点。
                val instructions = if (textState) {
                    "Does entry [$index] match rule [$ruleIndex]? Only that entry, only that rule. Follow the guidance."
                } else {
                    "Does `entries[$index]` match `rules[$ruleIndex]`? Only that entry, only that rule. Follow `guidance`."
                }
                // 官方 schema：三种题型都是 type + instructions + criteria，criteria 形状各不相同。
                val criteria: Any = when (format) {
                    JevQuestionFormat.CHOICE -> JSONObject().put("block", BLOCK_MEANING).put("keep", KEEP_MEANING)
                    JevQuestionFormat.NOUL -> JSONObject().put("true", BLOCK_MEANING).put("false", KEEP_MEANING)
                    // 两级、按"保留 → 屏蔽"排序：加权值 0–1 即屏蔽概率。
                    JevQuestionFormat.SCORE -> JSONArray().put(KEEP_MEANING).put(BLOCK_MEANING)
                }
                questions.put(
                    "item_${index}_$ruleIndex",
                    JSONObject().put("type", format.wire).put("instructions", instructions).put("criteria", criteria)
                )
            }
        }
        return JSONObject()
            .put("model", model)
            .put("state", state)
            .put("questions", questions)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
    }

    /** 结构化规则数组（type / covers / not_for / examples / keep_examples）。 */
    internal fun rulesJson(rules: List<SemanticRule>): JSONArray =
        JSONArray().also { array -> rules.forEach { array.put(it.toJson()) } }

    /** `item_{条目}_{规则}`：拆题后的原子问题；`item_{条目}`：整条式答案（个别模型/中转简化键名），按整条计。 */
    private val PAIR_KEY = Regex("item_(\\d+)_(\\d+)")
    private val ENTRY_KEY = Regex("item_(\\d+)")

    /**
     * 每条的屏蔽概率，按答案自己的 `type` 解析（不假设与请求题型一致）：
     * - `choice`：优先 `probabilities.block`；没有分布时按 `choice` 退化为 1/0；
     * - `noul`：`noul` 本身就是"是"的概率；
     * - `score`：优先最高级（屏蔽）的概率，否则 `score / (级数 - 1)`。
     *
     * 键 `item_{条目}_{规则}` 是按规则拆开的原子问题，同一条目各规则取**最大**（命中任一规则即屏蔽）；
     * 只带条目号的 `item_{条目}` 与各题一起取最大。某条目一个答案都没有才是 NaN（UNKNOWN）——
     * 部分规则漏答不算缺失（漏答补发按整条计）。缺项、类型不认识、数值越界不计分；
     * 整体结构不对、或一条都没判出时返回 null。
     */
    fun decodeBlockScores(payload: String, count: Int): FloatArray? {
        val answers = runCatching { JSONObject(payload).getJSONObject("answers") }.getOrNull() ?: return null
        val scores = FloatArray(count) { Float.NaN }
        answers.keys().forEach { key ->
            val entry = PAIR_KEY.matchEntire(key)?.groupValues?.get(1)?.toIntOrNull()
                ?: ENTRY_KEY.matchEntire(key)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@forEach
            if (entry !in 0 until count) return@forEach
            val answer = answers.optJSONObject(key) ?: return@forEach
            val value = when (answer.optString("type")) {
                "choice" -> {
                    val probability = answer.optJSONObject("probabilities")?.optDouble("block", Double.NaN) ?: Double.NaN
                    when {
                        !probability.isNaN() -> probability
                        answer.optString("choice") == "block" -> 1.0
                        answer.optString("choice") == "keep" -> 0.0
                        else -> Double.NaN
                    }
                }
                "noul" -> answer.optDouble("noul", Double.NaN)
                "score" -> scoreProbability(answer)
                else -> Double.NaN
            }
            if (!value.isNaN() && value in 0.0..1.0 && (scores[entry].isNaN() || value > scores[entry])) {
                scores[entry] = value.toFloat()
            }
        }
        // 外壳认得出、却一条都没判出（题型全不认识、概率全是百分比……）与"结构不对"同义：返回 null
        // 让判定器换写法并冷却这个来源。不返回的话，阶梯走完后会被当成成功，判定静默失效。
        return if (count > 0 && scores.all { it.isNaN() }) null else scores
    }

    /** 两级 score：最高级的概率就是屏蔽概率；没有分布时用加权值按级数归一。 */
    private fun scoreProbability(answer: JSONObject): Double {
        val probabilities = answer.optJSONObject("probabilities")
        val levels = answer.optJSONObject("legend")?.length() ?: probabilities?.length() ?: 0
        if (levels >= 2) {
            val top = probabilities?.optDouble((levels - 1).toString(), Double.NaN) ?: Double.NaN
            if (!top.isNaN()) return top
            val score = answer.optDouble("score", Double.NaN)
            if (!score.isNaN()) return score / (levels - 1)
        }
        return Double.NaN
    }
}

/** debug 构建的观测日志：宿主私有目录下 `bil_semantic.log`，超过上限截断重来。release 不写。 */
internal object SemanticDebugLog {
    const val LOG_NAME = "bil_semantic.log"
    private const val MAX_LOG_BYTES = 512 * 1024L

    fun append(directory: File, line: String) {
        runCatching {
            val file = File(directory, LOG_NAME)
            if (file.length() > MAX_LOG_BYTES) file.writeText("")
            file.appendText(line + "\n", StandardCharsets.UTF_8)
        }
    }
}
