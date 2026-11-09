package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 语义判定后端：把"若干条文本 + 一组屏蔽规则"变成请求，再把响应还原成每条的屏蔽概率（0–1，失败 NaN）。
 *
 * 判定器（[SemanticJudge]）只依赖这一层，缓存、截止、冷却、落盘都与具体模型无关。
 * 任何能做"这段文字是否命中这些规则"判断的服务都可以作为后端接入：
 * - [JevBackend]：TypeSafe Jev 的结构化 `/v1/systemone`（官方或兼容中转）；
 * - [OpenAiCompatibleBackend]：OpenAI 兼容 `/chat/completions`（DeepSeek、通义千问、智谱、Gemini、
 *   OpenRouter、本地 Ollama 等）；
 * - [CloudflareBackend]：Cloudflare Workers AI（OpenAI 兼容端点，地址可只填账户 ID）。
 */
internal interface SemanticBackend {
    /** 存储 id；进入缓存指纹，切换后端后旧判定整份作废。 */
    val id: String

    /** 请求用的模型名；同样进入缓存指纹。 */
    val model: String

    /** 单个请求最多几题；生成式模型输出随条数线性增长，要比结构化接口小。 */
    val maxBatch: Int

    /** 按实际请求地址取每请求题数上限（个别端点与默认限制不同，如 Jev 官方没有题数上限）。 */
    fun maxBatch(endpoint: String): Int = maxBatch

    /** 用户填的地址 → 实际请求地址；空串表示使用该后端默认地址（没有默认地址时返回 null）。 */
    fun resolveEndpoint(raw: String): String?

    /**
     * 请求变体数。兼容服务对请求写法的支持不一：变体 0 是首选写法，后面依次更保守。
     * 判定器遇到 [shouldFallback] 的状态码或认不出的响应结构时换下一个变体重试，探到可用的变体后
     * 同一服务 + 模型共享、不再探测（见 [SemanticJudge]）。
     */
    val variants: Int get() = 1

    /** 变体名，只用于观测日志。 */
    fun variantName(variant: Int): String = variant.toString()

    /**
     * [guidance] 是完整判定说明（防注入前缀 + 判定口径），见 [SemanticGuidance.of]；
     * [endpoint] 是实际请求地址，按站点决定要不要带厂商私有参数（如关闭深度思考）。
     */
    fun encode(
        texts: List<String>,
        rules: List<SemanticRule>,
        variant: Int = 0,
        guidance: String = SemanticGuidance.of(null),
        endpoint: String = ""
    ): ByteArray

    /** 整体结构不对、或外壳认得出却一条都没判出时返回 null；单条缺失或越界为 NaN。 */
    fun decode(payload: String, count: Int): FloatArray?

    /**
     * 这一级写法在该地址上是否值得发；false 时判定器直接跳到下一级（避免发出和别的级别一模一样的请求）。
     */
    fun applies(variant: Int, endpoint: String): Boolean = true

    /** 该状态码是否意味着"这种请求写法不被支持"，值得换下一个变体再试。 */
    fun shouldFallback(status: Int): Boolean = false

    /** 响应里的用量（token / 中转给出的费用）；没有时返回 null。只进观测日志。 */
    fun usageOf(payload: String): SemanticUsage? = null

    companion object {
        const val JEV = "jev"
        const val OPENAI = "openai"
        const val CLOUDFLARE = "cloudflare"
        val IDS: Set<String> = linkedSetOf(JEV, OPENAI, CLOUDFLARE)
        const val MAX_MODEL_LENGTH = 128

        /** 设置值 → 后端；未知 id 回落 JEV；OpenAI 兼容与 Cloudflare 必须有模型名，否则返回 null（不启用）。 */
        fun of(id: String?, model: String): SemanticBackend? {
            val name = model.trim().take(MAX_MODEL_LENGTH)
            return when (id?.trim()) {
                OPENAI -> name.takeIf(String::isNotEmpty)?.let(::OpenAiCompatibleBackend)
                CLOUDFLARE -> name.takeIf(String::isNotEmpty)?.let(::CloudflareBackend)
                else -> JevBackend(name.ifEmpty { JevBackend.DEFAULT_MODEL })
            }
        }

        /** 无协议时允许补 https 的主机形式：域名 / IP / localhost，可带端口。 */
        private val BARE_HOST = Regex("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?(?::[0-9]{1,5})?")

        /** 接受完整 http(s) 地址、或只填域名 / 主机（含路径）；无协议时按 https 补全，去掉末尾斜杠。 */
        internal fun parseUrl(raw: String): Pair<String, URL>? {
            var value = raw.trim().trimEnd('/')
            if (value.isEmpty() || value.length > SemanticJudge.MAX_ENDPOINT_LENGTH) return null
            if (!value.contains("://")) {
                // 设置说明里写了「可以只填域名」；但任意文本（含空格、缺域名形状）不能当地址。
                if (value.any { it.isWhitespace() } || !BARE_HOST.matches(value.substringBefore('/'))) return null
                value = "https://$value"
            }
            val url = runCatching { URL(value) }.getOrNull() ?: return null
            if (url.protocol !in setOf("https", "http") || url.host.isNullOrBlank()) return null
            return value to url
        }

        /**
         * 往地址尾部追加一段路径，**插到 query / fragment 之前**并原样保留它们。
         *
         * 直接把片段拼到整串末尾会把 `?api-version=…` 变成路径的一部分，请求打到一个不存在的路径上。
         * 已经写全判定端点的地址（Azure OpenAI 那种带 `?api-version=` 的）根本不会走到这里，
         * 所以 query 必须接受、不能一棍子拒掉。
         */
        internal fun withPathSegment(base: String, segment: String): String {
            val cut = base.indexOfFirst { it == '?' || it == '#' }
            if (cut < 0) return "$base/$segment"
            return base.substring(0, cut).trimEnd('/') + "/" + segment + base.substring(cut)
        }
    }
}

/** 一次请求的用量；[cost] 只有部分中转会给（单位由中转定），没有时为 NaN。 */
internal data class SemanticUsage(val inputTokens: Int, val outputTokens: Int, val cost: Double = Double.NaN) {
    operator fun plus(other: SemanticUsage) = SemanticUsage(
        inputTokens + other.inputTokens,
        outputTokens + other.outputTokens,
        when {
            cost.isNaN() -> other.cost
            other.cost.isNaN() -> cost
            else -> cost + other.cost
        }
    )

    fun describe(): String = buildString {
        append("in=").append(inputTokens).append(" out=").append(outputTokens)
        if (!cost.isNaN()) append(" cost=").append(String.format(java.util.Locale.ROOT, "%.6f", cost))
    }
}

/**
 * TypeSafe Jev 及同类"判定接口"（OpenRouter Decisions API 等）：规则与说明放共享 `state`，
 * 每条文本 × 每条规则一题（一题只判一种违规，官方反例见 [JevRequestCodec] 头注）。
 *
 * 请求写法按 [JevRequestVariant] 依次回退（题型 × state 形状）：
 * `choice`（首选，2026-09-29 真机 A/B 14/14）→ `choice`+文本 state → `noul` → `noul`+文本 → `score` → `score`+文本。
 * 2026-09-30 真机：OpenRouter 上的 Respan Span-01 系列只接受**字符串 state + noul 题**，停在第 4 种。
 * 各题型都还原成"屏蔽概率"，解析只看每条答案自己的 `type`。
 */
internal class JevBackend(override val model: String = DEFAULT_MODEL) : SemanticBackend {
    override val id: String = SemanticBackend.JEV
    /**
     * 官方文档没写题数上限（只限上下文 64k）；2026-09-30 实测第三方中转每请求最多 32 题，33 题起 400。
     * 默认按 32 切；官方端点没有这个限制，按 [SemanticJudge.MAX_BATCH_LIMIT] 的 200 题切——拆题后
     * 一题 = 一条 × 一条规则，批越小每条摊到的规则正文重付越多（见 [SemanticJudge.chunkLimit]）。
     * 更小的中转由判定器被拒后对半拆分自动探明。
     */
    override val maxBatch: Int = MAX_QUESTIONS
    override fun maxBatch(endpoint: String): Int = if (isOfficial(endpoint)) MAX_QUESTIONS_OFFICIAL else MAX_QUESTIONS
    override val variants: Int get() = JevRequestVariant.COUNT
    override fun variantName(variant: Int): String = JevRequestVariant.of(variant).label

    override fun resolveEndpoint(raw: String): String? {
        if (raw.isBlank()) return DEFAULT_ENDPOINT
        val (value, url) = SemanticBackend.parseUrl(raw) ?: return null
        val path = url.path.orEmpty()
        val host = url.host.lowercase()
        return when {
            // 已经是判定端点：原样用（TypeSafe / 阿里百炼 / 基元律动 / 兔子API 的 systemone，
            // 基元律动的 /v1/decision，OpenRouter / Eden AI / AIHubMix 的 decisions）。
            path.endsWith("/systemone") || path.endsWith("/decision") || path.endsWith("/decisions") -> value
            // OpenRouter 的判定模型走 Decisions API；用户常填 /api/v1（聊天接口的地址），会 404。
            host == "openrouter.ai" -> OPENROUTER_DECISIONS
            host == "api.edenai.run" -> EDENAI_DECISIONS
            path.isEmpty() -> SemanticBackend.withPathSegment(value, "v1/systemone")
            // 阿里百炼给的 base_url 是 …/compatible-mode/v1；其余中转也常只给到 /v1。
            path.endsWith("/v1") -> SemanticBackend.withPathSegment(value, "systemone")
            path.endsWith("/compatible-mode") -> SemanticBackend.withPathSegment(value, "v1/systemone")
            else -> value
        }
    }

    override fun encode(
        texts: List<String>,
        rules: List<SemanticRule>,
        variant: Int,
        guidance: String,
        endpoint: String
    ): ByteArray {
        val request = JevRequestVariant.of(variant)
        return JevRequestCodec.encode(texts, rules, model, request.format, guidance, textState = request.textState)
    }

    override fun decode(payload: String, count: Int): FloatArray? = JevRequestCodec.decodeBlockScores(payload, count)

    /** 官方校验失败是 422；部分中转用 400；请求体过大 413（兔子API 限 32 KiB）→ 先对半拆分。 */
    override fun shouldFallback(status: Int): Boolean = status == 400 || status == 413 || status == 422

    override fun usageOf(payload: String): SemanticUsage? = runCatching {
        val usage = JSONObject(payload).optJSONObject("usage") ?: return null
        SemanticUsage(usage.optInt("input_tokens"), usage.optInt("output_tokens"), usage.optDouble("cost", Double.NaN))
    }.getOrNull()

    companion object {
        const val DEFAULT_MODEL = "jev-latest"
        const val DEFAULT_ENDPOINT = "https://api.typesafe.ai/v1/systemone"
        const val OPENROUTER_DECISIONS = "https://openrouter.ai/api/alpha/decisions"
        const val EDENAI_DECISIONS = "https://api.edenai.run/v3/alpha/decisions"
        /** 第三方中转实测的每请求题数上限（33 题起 400）；默认按它切。 */
        const val MAX_QUESTIONS = 32
        /** 官方端点没有题数上限（只限上下文 64k），取模块的 200 题绝对上限。 */
        const val MAX_QUESTIONS_OFFICIAL = 200
        private val OFFICIAL_HOSTS = setOf("api.typesafe.ai")

        private fun isOfficial(endpoint: String): Boolean =
            runCatching { URL(endpoint).host?.lowercase() in OFFICIAL_HOSTS }.getOrDefault(false)
    }
}

/**
 * JEV 类接口的一种请求写法：题型 × state 形状（对象 / 文本）。编号即回退顺序：
 * 0 choice、1 choice+文本、2 noul、3 noul+文本、4 score、5 score+文本。
 */
internal data class JevRequestVariant(val format: JevQuestionFormat, val textState: Boolean) {
    val label: String get() = if (textState) "${format.wire}+text" else format.wire

    companion object {
        val COUNT = JevQuestionFormat.entries.size * 2

        fun of(variant: Int): JevRequestVariant {
            val index = variant.coerceIn(0, COUNT - 1)
            return JevRequestVariant(JevQuestionFormat.entries[index / 2], index % 2 == 1)
        }
    }
}

/** Jev 题型，按回退顺序排列；[wire] 是请求里的 `type`。 */
internal enum class JevQuestionFormat(val wire: String) {
    CHOICE("choice"),
    NOUL("noul"),
    SCORE("score")
}

/**
 * OpenAI 兼容 `/chat/completions`。让模型对**每条 × 每条规则**输出 `{"results":[{"i":条目序号,"r":规则序号,"p":0–1}]}`，
 * p 为命中该条规则的把握；同一条目各规则取最大即"命中任一规则"的把握（一题只判一种违规，官方反例见 [JevRequestCodec] 头注）。
 *
 * 鲁棒性：
 * - 请求写法与思考控制交给通用兼容层 [AiChatCompat]（以后别的 AI 功能也用它）：按"关闭思考 → 最低强度 → 低强度 →
 *   默认"与"严格参数 → 精简参数 → 不带 system 角色"组成的阶梯依次回退，服务商回 400/413/422、截断或读不出结果时
 *   换下一级，探到后按来源记住并落盘（见 [SemanticJudge]）。对**所有**对话类来源生效：已知站点用各家自己的参数，
 *   其余站点（含以后出现的中转）按 OpenAI 标准 `reasoning_effort` 试。
 * - 推理模型可能在正文前输出思考过程或用 ``` 包住 JSON：只取第一个 `{` 到最后一个 `}` 之间解析。
 * - 兼容 `p` 为数字或字符串、以及 `block: true/false` 的写法；缺 `r` 的整条式结果与各规则结果一起取最大；
 *   缺项、越界、重复 (i, r) 按 NaN/先到为准。
 * - 模型给出的"概率"未经校准，灵敏度门槛照常套用，判定质量取决于所选模型。
 */
internal open class OpenAiCompatibleBackend(override val model: String) : SemanticBackend {
    override val id: String = SemanticBackend.OPENAI
    override val maxBatch: Int = MAX_BATCH

    override fun resolveEndpoint(raw: String): String? {
        if (raw.isBlank()) return DEFAULT_ENDPOINT
        val (value, url) = SemanticBackend.parseUrl(raw) ?: return null
        return when {
            url.path.isNullOrEmpty() -> SemanticBackend.withPathSegment(value, "v1/chat/completions")
            url.path.endsWith("/chat/completions") -> value
            // 智谱 /api/paas/v4、百炼 /compatible-mode/v1、DeepSeek /v1 等 base_url 形式：补上 /chat/completions。
            else -> SemanticBackend.withPathSegment(value, "chat/completions")
        }
    }

    override val variants: Int get() = AiChatCompat.LADDER.size
    override fun variantName(variant: Int): String = AiChatCompat.variant(variant).label
    override fun applies(variant: Int, endpoint: String): Boolean = AiChatCompat.applies(variant, endpoint)

    override fun shouldFallback(status: Int): Boolean = status == 400 || status == 413 || status == 422

    /** OpenAI 写法 `prompt/completion_tokens`；OpenRouter 等另给 `cost`。 */
    override fun usageOf(payload: String): SemanticUsage? = runCatching {
        val usage = JSONObject(payload).optJSONObject("usage") ?: return null
        SemanticUsage(usage.optInt("prompt_tokens"), usage.optInt("completion_tokens"), usage.optDouble("cost", Double.NaN))
    }.getOrNull()

    override fun encode(
        texts: List<String>,
        rules: List<SemanticRule>,
        variant: Int,
        guidance: String,
        endpoint: String
    ): ByteArray {
        val system = buildString {
            append("You are a content filter for a video platform. Blocking rules (JSON):\n")
            append(JevRequestCodec.rulesJson(rules).toString()).append('\n')
            append(guidance).append('\n')
            append(
                "For each entry and each blocking rule, estimate p = probability (0 to 1) that the entry clearly " +
                    "matches that one rule. Judge every entry against every rule independently, one judgment per " +
                    "entry and rule. Reply with JSON only, exactly in the form " +
                    "{\"results\":[{\"i\":<entry index>,\"r\":<rule index>,\"p\":<number>}]}, one item per entry and " +
                    "rule, no other text."
            )
        }
        val entries = JSONArray()
        texts.forEachIndexed { index, text -> entries.put(JSONObject().put("i", index).put("text", text)) }
        val data = JSONObject().put("entries", entries).toString()
        // strict 级的 max_tokens 给推理模型留出思考空间：关不掉思考的模型（如经基元律动转发的 GLM-5.3-FlashX，
        // 2026-09-30 实测 2 条就思考 69–90 token）在 48 + 16×条数 的旧上限下正文被截断成没有分数的 JSON。
        // 不思考的模型写完 JSON 就停，上限高并不多花钱。结果按（条目 × 规则）逐对输出，条数乘规则数。
        return AiChatCompat.buildRequest(
            model = model,
            system = system,
            user = data,
            variantIndex = variant,
            endpoint = endpoint,
            maxTokens = MAX_TOKENS_BASE + texts.size * rules.size * MAX_TOKENS_PER_RESULT
        ).toString().toByteArray(StandardCharsets.UTF_8)
    }

    override fun decode(payload: String, count: Int): FloatArray? {
        val content = AiChatCompat.contentOf(payload) ?: return null
        val results = AiChatCompat.findJsonObject(content) { it.optJSONArray("results") != null }
            ?.optJSONArray("results") ?: return null
        val scores = FloatArray(count) { Float.NaN }
        val seen = HashSet<Pair<Int, Int>>()
        for (n in 0 until results.length()) {
            val item = results.optJSONObject(n) ?: continue
            val index = item.optInt("i", -1).takeIf { it in 0 until count } ?: continue
            val rule = item.optInt("r", -1)
            // 同一 (i, r) 重复先到为准；缺 r 的整条式结果记 (i, -1)，与各规则结果一起取最大。
            if (!seen.add(index to rule)) continue
            val score = when {
                item.has("p") -> item.optDouble("p", Double.NaN)
                item.has("block") -> if (item.optBoolean("block")) 1.0 else 0.0
                else -> Double.NaN
            }
            if (!score.isNaN() && score in 0.0..1.0 && (scores[index].isNaN() || score > scores[index])) {
                scores[index] = score.toFloat()
            }
        }
        // 回答因长度上限被截断且有条目缺分：当成"这种写法不行"，由判定器换下一级（带思考的级别不设上限）。
        if (AiChatCompat.truncated(payload) && scores.any { it.isNaN() }) return null
        // 外壳认得出、却一条都没判出（模型回百分比、缺 i、p 为 null……）与"结构不对"同义：返回 null，
        // 让判定器换写法并冷却这个来源。不返回的话，阶梯走完后会被当成成功，判定静默失效。
        return if (count > 0 && scores.all { it.isNaN() }) null else scores
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions"
        const val MAX_TOKENS_BASE = 1024
        /** 每对结果（条目 × 规则）的输出预算；实际约 8–16 token，留足富余。 */
        const val MAX_TOKENS_PER_RESULT = 32
        /** 生成式输出随（条目 × 规则）对数线性增长；20 题一批把单次延迟控制在常见模型 1–3 s 量级。 */
        const val MAX_BATCH = 20
    }
}

/**
 * Cloudflare Workers AI：走它的 OpenAI 兼容端点（`/ai/v1/chat/completions`，Bearer = API 令牌）。
 *
 * 地址可以填：32 位账户 ID；`https://api.cloudflare.com/client/v4/accounts/<ID>[/ai[/v1[/chat/completions]]]`；
 * 原生 `…/ai/run/@cf/…`（改写到兼容端点，模型以"模型名"一栏为准）；AI Gateway 的
 * `https://gateway.ai.cloudflare.com/v1/<ID>/<网关>/workers-ai[/v1]` 或 `…/compat`。没有默认地址。
 * JSON 模式只有部分模型支持，不支持时回 400，由请求变体回退接住。
 */
internal class CloudflareBackend(model: String) : OpenAiCompatibleBackend(model) {
    override val id: String = SemanticBackend.CLOUDFLARE

    override fun resolveEndpoint(raw: String): String? {
        val value = raw.trim().trimEnd('/')
        if (ACCOUNT_ID.matches(value)) return "$API_BASE/accounts/$value/ai/v1/chat/completions"
        val (normalized, url) = SemanticBackend.parseUrl(value) ?: return null
        val path = url.path.orEmpty()
        return when (url.host.lowercase()) {
            "api.cloudflare.com" -> ACCOUNT_PATH.find(path)?.groupValues?.get(1)
                ?.let { "https://api.cloudflare.com/client/v4/accounts/$it/ai/v1/chat/completions" }
            "gateway.ai.cloudflare.com" -> {
                val prefix = GATEWAY_PATH.find(path)?.value ?: return null
                val rest = path.removePrefix(prefix).trimEnd('/')
                when {
                    rest.startsWith("/compat") -> "https://gateway.ai.cloudflare.com$prefix/compat/chat/completions"
                    rest.startsWith("/workers-ai") -> "https://gateway.ai.cloudflare.com$prefix/workers-ai/v1/chat/completions"
                    else -> null
                }
            }
            // 自建代理（Worker 反代等）：按普通 OpenAI 兼容地址处理。
            else -> super.resolveEndpoint(normalized)
        }
    }

    companion object {
        private const val API_BASE = "https://api.cloudflare.com/client/v4"
        private val ACCOUNT_ID = Regex("[0-9a-fA-F]{32}")
        private val ACCOUNT_PATH = Regex("^/client/v4/accounts/([0-9a-zA-Z]+)(?:/|$)")
        private val GATEWAY_PATH = Regex("^/v1/[0-9a-zA-Z]+/[^/]+")
    }
}
