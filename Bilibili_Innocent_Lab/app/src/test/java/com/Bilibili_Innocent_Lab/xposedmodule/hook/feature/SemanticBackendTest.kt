package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets

class SemanticBackendTest {

    @get:Rule val temp = TemporaryFolder()

    /** 批量上限类断言假设一题一文本；拆题后一题 = 一条 × 一条规则，取一条规则保持题数 = 条目数（多规则题面见 SemanticJudgeTest）。 */
    private val rules = SemanticPresets.COMMENT.take(1)

    @Before fun resetVariants() = SemanticJudge.resetLearnedVariants()

    @After fun clearVariants() = SemanticJudge.resetLearnedVariants()

    private fun chat(content: String): String = JSONObject().put(
        "choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", content)))
    ).toString()

    @Test
    fun `openai compatible base urls resolve to chat completions`() {
        val backend = OpenAiCompatibleBackend("m")
        assertEquals(OpenAiCompatibleBackend.DEFAULT_ENDPOINT, backend.resolveEndpoint(""))
        assertEquals("https://api.deepseek.com/v1/chat/completions", backend.resolveEndpoint("https://api.deepseek.com"))
        assertEquals("https://open.bigmodel.cn/api/paas/v4/chat/completions",
            backend.resolveEndpoint("https://open.bigmodel.cn/api/paas/v4/"))
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            backend.resolveEndpoint("https://dashscope.aliyuncs.com/compatible-mode/v1"))
        assertEquals("http://127.0.0.1:11434/v1/chat/completions", backend.resolveEndpoint("http://127.0.0.1:11434/v1/chat/completions"))
        // 说明里承诺「可以只填域名」：无协议时按 https 补全，不能再判无效。
        assertEquals("https://api.deepseek.com/v1/chat/completions", backend.resolveEndpoint("api.deepseek.com"))
        assertEquals("https://openrouter.ai/api/v1/chat/completions", backend.resolveEndpoint("openrouter.ai/api/v1/"))
        assertEquals(JevBackend.OPENROUTER_DECISIONS, JevBackend().resolveEndpoint("openrouter.ai"))
        assertEquals(JevBackend.DEFAULT_ENDPOINT, JevBackend().resolveEndpoint(""))
    }

    @Test
    fun `a query or fragment survives when a path segment is appended`() {
        // 直接把片段拼到整串末尾会把 ?api-version=… 变成路径的一部分，请求打到一个不存在的路径上。
        // 片段必须插到 query 之前并原样保留它。
        assertEquals(
            "https://relay.example.com/v1/chat/completions?api-version=preview",
            OpenAiCompatibleBackend("m").resolveEndpoint("https://relay.example.com/v1?api-version=preview")
        )
        assertEquals(
            "https://relay.example.com/v1/chat/completions?frag",
            OpenAiCompatibleBackend("m").resolveEndpoint("https://relay.example.com?frag")
        )
        assertEquals(
            "https://relay.example.com/v1/chat/completions#frag",
            OpenAiCompatibleBackend("m").resolveEndpoint("https://relay.example.com/v1#frag")
        )
        assertEquals(
            "https://relay.example.com/v1/systemone?key=abc",
            JevBackend().resolveEndpoint("https://relay.example.com/v1?key=abc")
        )
        // 已经写全判定端点的地址原样使用（Azure OpenAI 那种带 api-version 的），不能被拒。
        val complete = "https://my-resource.openai.azure.com/openai/v1/chat/completions?api-version=preview"
        assertEquals(complete, OpenAiCompatibleBackend("m").resolveEndpoint(complete))
        assertEquals(
            "https://relay.example.com/v1/systemone?key=abc",
            JevBackend().resolveEndpoint("https://relay.example.com/v1/systemone?key=abc")
        )
        assertEquals("https://relay.example.com/v1/chat/completions",
            OpenAiCompatibleBackend("m").resolveEndpoint("https://relay.example.com/v1"))
    }

    @Test
    fun `backend selection needs a model for openai compatible`() {
        assertNull(SemanticBackend.of("openai", "  "))
        assertEquals("deepseek-chat", SemanticBackend.of("openai", " deepseek-chat ")!!.model)
        assertEquals(JevBackend.DEFAULT_MODEL, SemanticBackend.of("jev", "")!!.model)
        assertEquals(SemanticBackend.JEV, SemanticBackend.of("unknown", "")!!.id)
        assertNull(SemanticSettings.from("k", "", "", false, provider = "openai", model = ""))
    }

    @Test
    fun `openai request carries rules as system prompt and entries as data`() {
        val body = JSONObject(String(OpenAiCompatibleBackend("m").encode(listOf("a", "b"), rules), StandardCharsets.UTF_8))
        assertEquals("m", body.getString("model"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        val messages = body.getJSONArray("messages")
        assertTrue(messages.getJSONObject(0).getString("content").contains(rules.first().covers))
        assertTrue(messages.getJSONObject(0).getString("content").contains("JSON"))
        assertEquals(2, JSONObject(messages.getJSONObject(1).getString("content")).getJSONArray("entries").length())
        val relaxed = JSONObject(String(OpenAiCompatibleBackend("m").encode(listOf("a"), rules, variant = 1)))
        assertFalse(relaxed.has("response_format") || relaxed.has("temperature") || relaxed.has("max_tokens"))
    }

    @Test
    fun `openai decoding tolerates fences reasoning and malformed items`() {
        val backend = OpenAiCompatibleBackend("m")
        val messy = "<think>先想一想 {不是 JSON</think>\n```json\n" +
            "{\"results\":[{\"i\":0,\"p\":0.9},{\"i\":1,\"p\":\"0.2\"},{\"i\":2,\"block\":true}," +
            "{\"i\":3,\"p\":1.7},{\"i\":0,\"p\":0.1},{\"i\":9,\"p\":0.5}]}\n```"
        val scores = backend.decode(chat(messy), 5)!!
        assertEquals(0.9f, scores[0], 1e-6f) // 重复序号以先到为准
        assertEquals(0.2f, scores[1], 1e-6f)
        assertEquals(1f, scores[2], 0f)
        assertTrue(scores[3].isNaN()) // 越界
        assertTrue(scores[4].isNaN()) // 缺项
        assertNull(backend.decode(chat("抱歉，我不能回答"), 1))
        assertNull(backend.decode("{\"error\":{}}", 1))
    }

    @Test
    fun `openai per rule results merge by max per entry`() {
        val backend = OpenAiCompatibleBackend("m")
        val scores = backend.decode(
            chat(
                "{\"results\":[{\"i\":0,\"r\":0,\"p\":0.2},{\"i\":0,\"r\":1,\"p\":0.75}," +
                    "{\"i\":1,\"r\":0,\"p\":0.4},{\"i\":1,\"r\":0,\"p\":0.9}]}"
            ), 2
        )!!
        assertEquals(0.75f, scores[0], 1e-6f) // 同一条目各规则取最大
        assertEquals(0.4f, scores[1], 1e-6f) // 同一 (i, r) 重复先到为准
    }

    @Test
    fun `unsupported response format relaxes once and stays relaxed`() {
        val formats = mutableListOf<Boolean>()
        val judge = SemanticJudge("k", rules, backend = OpenAiCompatibleBackend("m"), background = { it.run(); true },
            transport = { body, _, _ ->
                val strict = JSONObject(String(body)).has("response_format")
                formats += strict
                if (strict) 400 to "{}" else 200 to chat("{\"results\":[{\"i\":0,\"p\":0.95}]}")
            })
        assertEquals(listOf(SemanticVerdict.BLOCK), judge.evaluate(listOf("一"), SemanticMode.WAIT))
        assertEquals(listOf(SemanticVerdict.BLOCK), judge.evaluate(listOf("二"), SemanticMode.WAIT))
        assertEquals(listOf(true, false, false), formats)
    }

    @Test
    fun `switching backend or model changes the cache identity`() {
        val jev = SemanticJudge.fingerprintOf(rules, "e", JevBackend())
        assertNotEquals(jev, SemanticJudge.fingerprintOf(rules, "e", OpenAiCompatibleBackend("a")))
        assertNotEquals(SemanticJudge.fingerprintOf(rules, "e", OpenAiCompatibleBackend("a")),
            SemanticJudge.fingerprintOf(rules, "e", OpenAiCompatibleBackend("b")))
    }

    @Test
    fun `new results never overwrite the disk copy before it has been merged`() {
        val file = File(temp.root, "bil_semantic/comment.bin")
        val fingerprint = SemanticJudge.fingerprintOf(rules, JevBackend.DEFAULT_ENDPOINT)
        val stamp = System.currentTimeMillis()
        val old = List(10) { SemanticScoreCache.Entry("%032x".format(it), 0.3f, stamp) }
        SemanticDiskStore(file, fingerprint).save(old)

        val queued = mutableListOf<Runnable>()
        val judge = SemanticJudge("k", rules, background = { queued += it; true },
            store = SemanticDiskStore(file, fingerprint),
            transport = { body, _, _ ->
                val count = JSONObject(String(body)).getJSONObject("questions").length()
                val answers = JSONObject()
                repeat(count) { i ->
                    answers.put("item_$i", JSONObject().put("type", "choice").put("choice", "keep")
                        .put("probabilities", JSONObject().put("block", 0.1).put("keep", 0.9)))
                }
                200 to JSONObject().put("answers", answers).toString()
            })
        // 首次使用：加载任务排队但尚未执行；此时新结果（超过落盘阈值）不得触发写盘。
        judge.evaluate(List(60) { "新$it" }, SemanticMode.PREFETCH)
        val load = queued.removeAt(0)
        queued.toList().forEach(Runnable::run)
        queued.clear()
        assertEquals(10, SemanticDiskStore(file, fingerprint).load(System.currentTimeMillis(), Long.MAX_VALUE / 2).size)

        // 加载完成后再写：旧条目与新条目都在。
        load.run()
        judge.evaluate(List(60) { "再$it" }, SemanticMode.PREFETCH)
        queued.toList().forEach(Runnable::run)
        queued.toList().forEach(Runnable::run)
        val saved = SemanticDiskStore(file, fingerprint).load(System.currentTimeMillis(), Long.MAX_VALUE / 2)
        assertTrue(saved.size >= 10 + 60)
        assertTrue(old.all { entry -> saved.any { it.key == entry.key } })
    }

    private fun jevAnswers(vararg answers: JSONObject): String {
        val map = JSONObject()
        answers.forEachIndexed { index, answer -> map.put("item_$index", answer) }
        return JSONObject().put("answers", map)
            .put("usage", JSONObject().put("input_tokens", 120).put("output_tokens", 9).put("cost", 0.000005))
            .toString()
    }

    private fun typeOf(body: ByteArray): String =
        JSONObject(String(body)).getJSONObject("questions").getJSONObject("item_0_0").getString("type")

    @Test
    fun `envelope parses but nothing scores counts as a structural failure`() {
        // 外壳认得出、却一条都没判出，与"结构不对"同义：必须返回 null，否则阶梯走完后会被当成成功，
        // 判定静默失效而连通性测试仍显示通过。
        assertNull(OpenAiCompatibleBackend("m").decode(chat("{\"results\":[{\"i\":0,\"p\":85}]}"), 2))
        assertNull(OpenAiCompatibleBackend("m").decode(chat("{\"results\":[{\"p\":0.9}]}"), 2))
        assertNull(OpenAiCompatibleBackend("m").decode(chat("{\"results\":[{\"i\":0,\"p\":null}]}"), 2))
        assertNull(
            JevRequestCodec.decodeBlockScores(
                jevAnswers(JSONObject().put("type", "mystery").put("value", 0.5)), 1
            )
        )
        // 只判出一半仍按"部分缺失"处理，不算结构失败。
        val partial = OpenAiCompatibleBackend("m")
            .decode(chat("{\"results\":[{\"i\":0,\"p\":0.9}]}"), 2)!!
        assertEquals(0.9f, partial[0], 1e-6f)
        assertTrue(partial[1].isNaN())
    }

    @Test
    fun `jev question formats follow the official criteria shapes`() {
        fun question(format: JevQuestionFormat) = JSONObject(String(JevRequestCodec.encode(listOf("a"), rules, format = format)))
            .getJSONObject("questions").getJSONObject("item_0_0")
        assertEquals(setOf("block", "keep"), question(JevQuestionFormat.CHOICE).getJSONObject("criteria").keys().asSequence().toSet())
        assertEquals(setOf("true", "false"), question(JevQuestionFormat.NOUL).getJSONObject("criteria").keys().asSequence().toSet())
        val levels = question(JevQuestionFormat.SCORE).getJSONArray("criteria")
        assertEquals(2, levels.length())
        assertTrue(levels.getString(1).contains("Matches this one rule")) // 最高级 = 屏蔽（一题只判一条规则）
        assertEquals("noul", question(JevQuestionFormat.NOUL).getString("type"))
    }

    @Test
    fun `jev decoding reads every answer type regardless of what was asked`() {
        val payload = jevAnswers(
            JSONObject().put("type", "noul").put("noul", 0.83),
            JSONObject().put("type", "score").put("score", 0.9).put("legend", JSONObject().put("0", "k").put("1", "b"))
                .put("probabilities", JSONObject().put("0", 0.1).put("1", 0.9)),
            JSONObject().put("type", "score").put("score", 0.4).put("legend", JSONObject().put("0", "k").put("1", "b")),
            JSONObject().put("type", "choice").put("choice", "block")
                .put("probabilities", JSONObject().put("block", 0.7).put("keep", 0.3)),
            JSONObject().put("type", "noul").put("noul", 1.4),
            JSONObject().put("type", "mystery").put("value", 0.5)
        )
        val scores = JevRequestCodec.decodeBlockScores(payload, 6)!!
        assertEquals(0.83f, scores[0], 1e-6f)
        assertEquals(0.9f, scores[1], 1e-6f)
        assertEquals(0.4f, scores[2], 1e-6f)
        assertEquals(0.7f, scores[3], 1e-6f)
        assertTrue(scores[4].isNaN()) // 越界
        assertTrue(scores[5].isNaN()) // 不认识的题型
    }

    @Test
    fun `jev falls back to noul when choice is rejected and every surface reuses it`() {
        val types = mutableListOf<String>()
        val transport: (ByteArray, String, Int) -> Pair<Int, String>? = { body, _, _ ->
            val type = typeOf(body)
            types += type
            if (type == "choice") 422 to "{\"detail\":\"unsupported question type\"}"
            else 200 to jevAnswers(JSONObject().put("type", "noul").put("noul", 0.92))
        }
        val reports = mutableListOf<SemanticBatchReport>()
        val comment = SemanticJudge("k", rules, background = { it.run(); true }, transport = transport)
        assertEquals(listOf(SemanticVerdict.BLOCK), comment.evaluate(listOf("一"), SemanticMode.WAIT) { r, _, _ -> reports += r })
        assertEquals(listOf("choice", "choice", "noul"), types) // choice、choice+文本 都被拒，停在 noul
        assertTrue(reports.single().extras().contains("variant=noul"))
        assertTrue(reports.single().extras().contains("in=120 out=9"))

        // 另一个过滤面（同服务、同模型、同端点）直接用 noul，不再为探测多发请求。
        val danmaku = SemanticJudge("k", SemanticPresets.DANMAKU, background = { it.run(); true }, transport = transport)
        danmaku.evaluate(listOf("二"), SemanticMode.WAIT)
        assertEquals(listOf("choice", "choice", "noul", "noul"), types)
    }

    @Test
    fun `an unrelated rejection does not downgrade the question format`() {
        var now = 1_000L
        val types = mutableListOf<String>()
        var reject = true
        val judge = SemanticJudge("k", rules, background = { it.run(); true }, clock = { now }, transport = { body, _, _ ->
            types += typeOf(body)
            if (reject) 422 to "{\"detail\":\"state too long\"}"
            else 200 to jevAnswers(JSONObject().put("type", "choice")
                .put("probabilities", JSONObject().put("block", 0.1).put("keep", 0.9)))
        })
        assertEquals(listOf(SemanticVerdict.UNKNOWN), judge.evaluate(listOf("一"), SemanticMode.WAIT))
        assertEquals(listOf("choice", "choice", "noul", "noul", "score", "score"), types) // 全部被拒：只试一轮，然后冷却
        judge.evaluate(listOf("二"), SemanticMode.WAIT)
        assertEquals(6, types.size) // 冷却中不发请求
        now += SemanticJudge.FAILURE_COOLDOWN_MS
        reject = false
        assertEquals(listOf(SemanticVerdict.KEEP), judge.evaluate(listOf("三"), SemanticMode.WAIT))
        assertEquals("choice", types.last()) // 没有被永久降级
    }

    @Test
    fun `openai last variant folds the system prompt into the user message`() {
        val body = JSONObject(String(OpenAiCompatibleBackend("m").encode(listOf("a"), rules, variant = AiChatCompat.LADDER.lastIndex)))
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertTrue(messages.getJSONObject(0).getString("content").contains(rules.first().covers))
        assertTrue(messages.getJSONObject(0).getString("content").contains("\"entries\""))
        assertFalse(body.has("response_format"))
        val usage = OpenAiCompatibleBackend("m").usageOf(
            "{\"usage\":{\"prompt_tokens\":300,\"completion_tokens\":40}}"
        )!!
        assertEquals(300, usage.inputTokens)
        assertEquals(40, usage.outputTokens)
        assertTrue(usage.cost.isNaN())
    }

    @Test
    fun `every preset states its boundary and both kinds of examples`() {
        SemanticSurface.entries.forEach { surface ->
            SemanticPresets.of(surface).forEach { rule ->
                assertTrue("${surface}/${rule.id}", rule.notFor.isNotBlank() && rule.examples.isNotEmpty() && rule.keepExamples.isNotEmpty())
                assertFalse("${surface}/${rule.id}", rule.type.isBlank() || rule.covers.isBlank() || rule.covers == rule.text)
            }
            // 全选时规则部分仍保持短小（审核策略写法建议约 400–600 token；中文约 1.5 字/token 粗估）。
            val chars = JevRequestCodec.rulesJson(SemanticPresets.of(surface)).toString().length
            assertTrue("$surface rules $chars chars", chars < 1800)
        }
    }

    @Test
    fun `jev state carries structured rules and the boundary guidance`() {
        val state = JSONObject(String(JevRequestCodec.encode(listOf("a"), SemanticPresets.DYNAMIC.take(2)))).getJSONObject("state")
        val goods = state.getJSONArray("rules").getJSONObject(1)
        assertEquals("带货", goods.getString("type"))
        assertEquals("与商品无利益相关的讨论、测评、吐槽", goods.getString("not_for"))
        assertEquals("链接放评论区啦", goods.getJSONArray("examples").getString(0))
        assertTrue(goods.getJSONArray("keep_examples").length() > 0)
        assertTrue(state.getString("guidance").contains("not_for"))
        val system = JSONObject(String(OpenAiCompatibleBackend("m").encode(listOf("a"), SemanticPresets.DYNAMIC.take(2))))
            .getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.contains("\"not_for\"") && system.contains("keep_examples"))
    }

    @Test
    fun `changing a rule boundary invalidates old verdicts`() {
        val base = SemanticPresets.COMMENT.take(1)
        val edited = listOf(base[0].copy(notFor = base[0].notFor + "、新增边界"))
        assertNotEquals(SemanticJudge.fingerprintOf(base, "e"), SemanticJudge.fingerprintOf(edited, "e"))
    }

    @Test
    fun `cloudflare accepts an account id or its known url shapes`() {
        val id = "0123456789abcdef0123456789abcdef"
        val cf = CloudflareBackend("@cf/meta/llama-3.3-70b-instruct-fp8-fast")
        val compat = "https://api.cloudflare.com/client/v4/accounts/$id/ai/v1/chat/completions"
        assertEquals(compat, cf.resolveEndpoint(id))
        assertEquals(compat, cf.resolveEndpoint("https://api.cloudflare.com/client/v4/accounts/$id"))
        assertEquals(compat, cf.resolveEndpoint("https://api.cloudflare.com/client/v4/accounts/$id/ai/v1/"))
        assertEquals(compat, cf.resolveEndpoint("https://api.cloudflare.com/client/v4/accounts/$id/ai/run/@cf/meta/llama-3.1-8b-instruct"))
        assertEquals("https://gateway.ai.cloudflare.com/v1/$id/my-gw/workers-ai/v1/chat/completions",
            cf.resolveEndpoint("https://gateway.ai.cloudflare.com/v1/$id/my-gw/workers-ai"))
        assertEquals("https://gateway.ai.cloudflare.com/v1/$id/my-gw/compat/chat/completions",
            cf.resolveEndpoint("https://gateway.ai.cloudflare.com/v1/$id/my-gw/compat"))
        assertEquals("https://my-proxy.example.workers.dev/v1/chat/completions", cf.resolveEndpoint("https://my-proxy.example.workers.dev/v1"))
        assertNull(cf.resolveEndpoint("")) // 没有默认地址
        assertNull(cf.resolveEndpoint("https://api.cloudflare.com/client/v4/user"))
        assertNull(SemanticBackend.of("cloudflare", ""))
        assertEquals(SemanticBackend.CLOUDFLARE, SemanticBackend.of("cloudflare", "@cf/x")!!.id)
        assertNull(SemanticSettings.from("k", "", "", false, provider = "cloudflare", model = "@cf/x"))
    }

    @Test
    fun `workers ai native response shape is readable too`() {
        val cf = CloudflareBackend("@cf/x")
        val asText = JSONObject().put("result", JSONObject().put("response", "{\"results\":[{\"i\":0,\"p\":0.8}]}"))
            .put("success", true).toString()
        assertEquals(0.8f, cf.decode(asText, 1)!![0], 1e-6f)
        val asObject = JSONObject().put("result", JSONObject().put("response",
            JSONObject().put("results", JSONArray().put(JSONObject().put("i", 0).put("p", 0.3))))).toString()
        assertEquals(0.3f, cf.decode(asObject, 1)!![0], 1e-6f)
    }

    @Test
    fun `user wait limit overrides surface defaults and the network timeout stays generous`() {
        assertEquals(0, SemanticSettings.normalizeTimeout(0))
        assertEquals(500, SemanticSettings.normalizeTimeout(100))
        assertEquals(30_000, SemanticSettings.normalizeTimeout(99_999))
        val timeouts = mutableListOf<Int>()
        val transport: (ByteArray, String, Int) -> Pair<Int, String>? = { body, _, timeout ->
            timeouts += timeout
            val count = JSONObject(String(body)).getJSONObject("questions").length()
            val answers = JSONObject()
            repeat(count) { answers.put("item_$it", JSONObject().put("type", "noul").put("noul", 0.1)) }
            200 to JSONObject().put("answers", answers).toString()
        }
        val custom = SemanticSettings.from("k", "", "", true, timeoutMs = 8_000)!!
        val judge = custom.judge(SemanticSurface.DANMAKU, SemanticPresets.DANMAKU.take(1), timeoutMs = 3_000)!!
        val auto = SemanticSettings.from("k", "", "", true)!!.judge(SemanticSurface.COMMENT, rules)!!
        // 等待上限：用户设了就用用户的；否则用各面默认。网络超时至少 12 s，慢服务在后台也能跑完进缓存。
        val waitField = SemanticJudge::class.java.getDeclaredField("timeoutMs").apply { isAccessible = true }
        assertEquals(8_000, waitField.getInt(judge))
        assertEquals(SemanticJudge.DEFAULT_TIMEOUT_MS, waitField.getInt(auto))
        SemanticJudge("k", rules, timeoutMs = 2_500, background = { it.run(); true }, transport = transport)
            .evaluate(listOf("一"), SemanticMode.WAIT)
        assertEquals(listOf(SemanticJudge.MIN_REQUEST_TIMEOUT_MS), timeouts)
    }

    private fun choiceAnswers(body: ByteArray, block: Double = 0.1): String {
        val count = JSONObject(String(body)).getJSONObject("questions").length()
        val answers = JSONObject()
        repeat(count) {
            answers.put("item_$it", JSONObject().put("type", "choice")
                .put("probabilities", JSONObject().put("block", block).put("keep", 1 - block)))
        }
        return JSONObject().put("answers", answers).toString()
    }

    @Test
    fun `an oversized batch is split in halves and the working size is remembered`() {
        val sizes = mutableListOf<Int>()
        // 模拟中转：超过 20 题一律 400（与题型无关）。
        val transport: (ByteArray, String, Int) -> Pair<Int, String>? = { body, _, _ ->
            val count = JSONObject(String(body)).getJSONObject("questions").length()
            sizes += count
            if (count > 20) 400 to "{\"error\":{\"message\":\"bounded typed questions\"}}" else 200 to choiceAnswers(body)
        }
        val judge = SemanticJudge("k", rules, batchSize = 32, background = { it.run(); true }, transport = transport)
        val verdicts = judge.evaluate(List(32) { "弹幕$it" }, SemanticMode.WAIT)
        assertTrue(verdicts.all { it == SemanticVerdict.KEEP }) // 拆开后全部判出
        assertEquals(listOf(32, 16, 16), sizes) // 32 被拒 → 两个 16，题型没变
        sizes.clear()
        // 之后直接按探明的 16 切，不再先撞一次 400；其他过滤面同一服务也一样。
        SemanticJudge("k", SemanticPresets.DANMAKU.take(1), batchSize = 32, background = { it.run(); true }, transport = transport)
            .evaluate(List(32) { "新$it" }, SemanticMode.WAIT)
        assertEquals(listOf(16, 16), sizes)
    }

    @Test
    fun `a question type rejection is not mistaken for a batch size limit`() {
        val sizes = mutableListOf<Pair<Int, String>>()
        val transport: (ByteArray, String, Int) -> Pair<Int, String>? = { body, _, _ ->
            val count = JSONObject(String(body)).getJSONObject("questions").length()
            val type = typeOf(body)
            sizes += count to type
            if (type == "choice") 422 to "{}"
            else 200 to jevAnswers(*Array(count) { JSONObject().put("type", "noul").put("noul", 0.1) })
        }
        val judge = SemanticJudge("k", rules, batchSize = 8, background = { it.run(); true }, transport = transport)
        judge.evaluate(List(8) { "评论$it" }, SemanticMode.WAIT)
        sizes.clear()
        judge.evaluate(List(8) { "新评论$it" }, SemanticMode.WAIT)
        // 已学到 noul；分批仍是 8，没有被误记成"只能 4 条"。
        assertEquals(listOf(8 to "noul"), sizes)
    }

    @Test
    fun `tiny prefetch calls are coalesced into one request`() {
        val sizes = mutableListOf<Int>()
        val timers = mutableListOf<Runnable>()
        val reports = mutableListOf<SemanticBatchReport>()
        val judge = SemanticJudge("k", rules, background = { it.run(); true },
            scheduler = { delay, task -> assertEquals(SemanticJudge.COALESCE_MS, delay); timers += task; true },
            transport = { body, _, _ ->
                sizes += JSONObject(String(body)).getJSONObject("questions").length()
                200 to choiceAnswers(body)
            })
        // 楼中楼预览：每条回复 1–2 条，逐个调用 getter。
        repeat(7) { i ->
            judge.evaluate(listOf("回复$i-a", "回复$i-b"), SemanticMode.PREFETCH) { report, _, _ -> reports += report }
        }
        assertTrue(sizes.isEmpty()) // 还在攒
        assertEquals(1, timers.size) // 只挂一个定时器
        timers.single().run()
        assertEquals(listOf(14), sizes) // 一个请求带全部 14 条
        assertEquals(14, reports.single().requested)
        // 结果已进缓存。
        assertEquals(List(2) { SemanticVerdict.KEEP }, judge.evaluate(listOf("回复0-a", "回复6-b"), SemanticMode.CACHE_ONLY))
    }

    @Test
    fun `a full prefetch batch is sent at once without waiting for the timer`() {
        val sizes = mutableListOf<Int>()
        val timers = mutableListOf<Runnable>()
        val judge = SemanticJudge("k", rules, batchSize = 10, background = { it.run(); true },
            scheduler = { _, task -> timers += task; true },
            transport = { body, _, _ ->
                sizes += JSONObject(String(body)).getJSONObject("questions").length()
                200 to choiceAnswers(body)
            })
        judge.evaluate(List(23) { "条目$it" }, SemanticMode.PREFETCH)
        assertEquals(listOf(10, 10), sizes) // 满批立即发
        timers.single().run()
        assertEquals(listOf(10, 10, 3), sizes) // 余下 3 条由定时器发
    }

    @Test
    fun `queue rejections are reported as rejected rather than deadline`() {
        val reports = mutableListOf<SemanticBatchReport>()
        val judge = SemanticJudge("k", rules, background = { false }, transport = { body, _, _ -> 200 to choiceAnswers(body) })
        assertEquals(listOf(SemanticVerdict.UNKNOWN), judge.evaluate(listOf("一"), SemanticMode.WAIT) { r, _, _ -> reports += r })
        assertEquals("rejected", reports.single().outcome)
    }

    @Test
    fun `a decisions model that only takes text state and noul is reached by fallback`() {
        val seen = mutableListOf<String>()
        // 模拟 OpenRouter 上的 Respan：state 必须是字符串，且只收 noul。
        val transport: (ByteArray, String, Int) -> Pair<Int, String>? = { body, _, _ ->
            val json = JSONObject(String(body))
            val type = json.getJSONObject("questions").getJSONObject("item_0_0").getString("type")
            val text = json.opt("state") is String
            seen += if (text) "$type+text" else type
            when {
                !text -> 400 to "{\"error\":{\"message\":\"Respan state must be a string\"}}"
                type != "noul" -> 400 to "{\"error\":{\"message\":\"Respan only accepts noul questions\"}}"
                else -> 200 to jevAnswers(*Array(json.getJSONObject("questions").length()) {
                    JSONObject().put("type", "noul").put("noul", 0.9)
                })
            }
        }
        val judge = SemanticJudge("k", rules, background = { it.run(); true }, transport = transport)
        assertEquals(listOf(SemanticVerdict.BLOCK), judge.evaluate(listOf("一"), SemanticMode.WAIT))
        assertEquals(listOf("choice", "choice+text", "noul", "noul+text"), seen)
        seen.clear()
        judge.evaluate(listOf("二"), SemanticMode.WAIT)
        assertEquals(listOf("noul+text"), seen) // 之后直接用探明的写法
    }

    @Test
    fun `text state lists rules guidance and numbered entries`() {
        val body = JSONObject(String(JevRequestCodec.encode(listOf("第一条", "第二条"), rules,
            format = JevQuestionFormat.NOUL, guidance = SemanticGuidance.of("拿不准就保留"), textState = true)))
        val state = body.getString("state")
        assertTrue(state.contains("Blocking rules (JSON): ") && state.contains(rules.first().covers))
        assertTrue(state.contains("拿不准就保留") && state.contains(SemanticGuidance.SAFETY))
        assertTrue(state.contains("[0] 第一条\n[1] 第二条"))
        val question = body.getJSONObject("questions").getJSONObject("item_1_0")
        assertEquals("noul", question.getString("type"))
        assertTrue(question.getString("instructions").contains("entry [1]"))
        assertTrue(question.get("instructions") is String) // Respan 只收纯字符串的 instructions / criteria
        assertTrue(question.getJSONObject("criteria").get("true") is String)
        assertEquals(6, JevBackend().variants)
        assertEquals("noul+text", JevBackend().variantName(3))
    }

    @Test
    fun `openrouter addresses on the jev type go to the decisions api`() {
        val jev = JevBackend("span-01-lite:free")
        assertEquals(JevBackend.OPENROUTER_DECISIONS, jev.resolveEndpoint("https://openrouter.ai/api/v1"))
        assertEquals(JevBackend.OPENROUTER_DECISIONS, jev.resolveEndpoint("https://openrouter.ai"))
        assertEquals(JevBackend.OPENROUTER_DECISIONS, jev.resolveEndpoint("https://openrouter.ai/api/alpha/decisions"))
        // 对话模型类型仍走 chat/completions。
        assertEquals("https://openrouter.ai/api/v1/chat/completions", OpenAiCompatibleBackend("x").resolveEndpoint("https://openrouter.ai/api/v1"))
        assertEquals("https://relay.example.com/v1/systemone", jev.resolveEndpoint("https://relay.example.com"))
    }

    @Test
    fun `learned request formats survive a restart`() {
        val root = temp.newFolder("host-files")
        SemanticJudge.attachLearnedStore(root)
        val queued = mutableListOf<Runnable>()
        val transport: (ByteArray, String, Int) -> Pair<Int, String>? = { body, _, _ ->
            val json = JSONObject(String(body))
            if (json.opt("state") !is String) 400 to "{}"
            else 200 to jevAnswers(*Array(json.getJSONObject("questions").length()) {
                JSONObject().put("type", "choice").put("probabilities", JSONObject().put("block", 0.1).put("keep", 0.9))
            })
        }
        SemanticJudge("k", rules, background = { it.run(); true }, transport = transport).evaluate(listOf("一"), SemanticMode.WAIT)
        val file = File(File(root, "bil_semantic"), SemanticJudge.LEARNED_FILE)
        // 真机上写盘走后台池；单测里等它落盘。
        repeat(50) { if (!file.isFile) Thread.sleep(20) }
        assertTrue(file.isFile)
        val saved = file.readText()
        assertTrue(saved.startsWith("v ") && saved.trim().endsWith(" 1")) // choice+文本 = 变体 1
        assertFalse(saved.contains("typesafe") || saved.contains("http")) // 只存摘要，不存地址

        // "重启"：清掉内存里的表，再从文件读回。
        SemanticJudge.resetLearnedVariants()
        SemanticJudge.attachLearnedStore(root)
        val seen = mutableListOf<Boolean>()
        SemanticJudge("k", rules, background = { it.run(); true }, transport = { body, key, timeout ->
            seen += JSONObject(String(body)).opt("state") is String
            transport(body, key, timeout)
        }).evaluate(listOf("二"), SemanticMode.WAIT)
        assertEquals(listOf(true), seen) // 直接用文本 state，不再先被拒一次
        queued.clear()
    }

    @Test
    fun `thinking is switched off on sites that turn it on by default`() {
        val chat = OpenAiCompatibleBackend("m")
        fun body(endpoint: String, variant: Int) = JSONObject(String(chat.encode(listOf("a"), rules, variant, SemanticGuidance.of(null), endpoint)))
        // DeepSeek：thinking 默认开启，max_tokens 会被思考耗尽 → 默认关闭。
        val deepseek = body("https://api.deepseek.com/chat/completions", 0)
        assertEquals("disabled", deepseek.getJSONObject("thinking").getString("type"))
        assertTrue(deepseek.has("max_tokens") && deepseek.has("response_format"))
        // 变体 1：去掉通用严格字段但保留关闭思考；变体 2：什么都不带（给关不掉思考的模型）。
        val plain = body("https://api.deepseek.com/chat/completions", 1)
        assertTrue(plain.has("thinking") && !plain.has("max_tokens") && !plain.has("response_format"))
        assertFalse(body("https://api.deepseek.com/chat/completions", 4).has("thinking")) // strict + 默认思考
        assertEquals(false, body("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", 0).getBoolean("enable_thinking"))
        assertEquals(false, body("https://abc.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions", 0).getBoolean("enable_thinking"))
        assertEquals("disabled", body("https://open.bigmodel.cn/api/paas/v4/chat/completions", 0).getJSONObject("thinking").getString("type"))
        assertEquals("disabled", body("https://ark.cn-beijing.volces.com/api/v3/chat/completions", 0).getJSONObject("thinking").getString("type"))
        assertEquals(false, body("https://api.siliconflow.cn/v1/chat/completions", 0).getBoolean("enable_thinking"))
        assertEquals("none", body("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", 0).getString("reasoning_effort"))
        assertEquals("none", body("https://openrouter.ai/api/v1/chat/completions", 0).getJSONObject("reasoning").getString("effort"))
        // 未知站点（含 OpenAI 官方与各类中转）只带 OpenAI 标准的 reasoning_effort，不带任何厂商私有参数；
        // 默认思考那几级什么都不带（不认推理参数的普通模型最终落在 strict + 默认）。
        val openai = body(OpenAiCompatibleBackend.DEFAULT_ENDPOINT, 0)
        assertEquals("none", openai.getString("reasoning_effort"))
        assertFalse(openai.has("thinking") || openai.has("enable_thinking") || openai.has("reasoning"))
        val plainDefault = body(OpenAiCompatibleBackend.DEFAULT_ENDPOINT, 4)
        assertFalse(plainDefault.has("reasoning_effort"))
        assertTrue(plainDefault.has("response_format"))
        assertEquals(AiChatCompat.LADDER.size, chat.variants)
        assertEquals("plain+min-think", chat.variantName(2))
    }

    @Test
    fun `decision endpoints of jev compatible providers resolve correctly`() {
        val jev = JevBackend("decision-model-preview")
        // 阿里百炼决策模型：base_url 给到 …/compatible-mode/v1 或 …/compatible-mode。
        assertEquals("https://ws1.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/systemone",
            jev.resolveEndpoint("https://ws1.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"))
        assertEquals("https://ws1.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/systemone",
            jev.resolveEndpoint("https://ws1.cn-beijing.maas.aliyuncs.com/compatible-mode"))
        assertEquals("https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/systemone",
            jev.resolveEndpoint("https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/systemone"))
        // 基元律动：只填域名 / /v1 → systemone；/v1/decision 原样。
        assertEquals("https://tokenrhythm.studio/v1/systemone", jev.resolveEndpoint("https://tokenrhythm.studio"))
        assertEquals("https://tokenrhythm.studio/v1/systemone", jev.resolveEndpoint("https://tokenrhythm.studio/v1"))
        assertEquals("https://tokenrhythm.studio/v1/decision", jev.resolveEndpoint("https://tokenrhythm.studio/v1/decision"))
        // 兔子API、Eden AI、AIHubMix 风格。
        assertEquals("https://api.tu-zi.com/v1/systemone", jev.resolveEndpoint("https://api.tu-zi.com"))
        assertEquals(JevBackend.EDENAI_DECISIONS, jev.resolveEndpoint("https://api.edenai.run/v3/llm"))
        assertEquals("https://aihubmix.com/api/v1/decisions", jev.resolveEndpoint("https://aihubmix.com/api/v1/decisions"))
        assertEquals(JevBackend.DEFAULT_ENDPOINT, jev.resolveEndpoint(""))
    }

    @Test
    fun `an oversized request body is split like an oversized batch`() {
        val sizes = mutableListOf<Int>()
        // 模拟兔子API：请求体超过 32 KiB 回 413。
        val judge = SemanticJudge("k", rules, batchSize = 32, background = { it.run(); true }, transport = { body, _, _ ->
            val count = JSONObject(String(body)).getJSONObject("questions").length()
            sizes += count
            if (body.size > 32 * 1024) 413 to "{}" else 200 to choiceAnswers(body)
        })
        val long = "很长的评论".repeat(100) // 每条约 1.5 KB
        val verdicts = judge.evaluate(List(32) { "$long$it" }, SemanticMode.WAIT)
        assertTrue(verdicts.all { it == SemanticVerdict.KEEP })
        assertTrue(sizes.first() == 32 && sizes.drop(1).all { it < 32 })
    }

    @Test
    fun `a reasoning model truncated by the token cap falls back to the uncapped request`() {
        // 2026-09-30 真机：基元律动转发的 GLM-5.3-flashx 关不掉思考，思考吃掉 max_tokens，正文只剩 {"results":[{"i":0}]}。
        fun reply(content: String, finish: String) = JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("finish_reason", finish)
            .put("message", JSONObject().put("role", "assistant").put("content", content)))).toString()
        val variants = mutableListOf<String>()
        val judge = SemanticJudge("k", rules, backend = OpenAiCompatibleBackend("glm-5.3-flashx"),
            endpoint = "https://tokenrhythm.studio/v1/chat/completions", background = { it.run(); true },
            transport = { body, _, _ ->
                val json = JSONObject(String(body))
                if (json.has("max_tokens")) {
                    variants += "strict"
                    assertTrue(json.getInt("max_tokens") >= OpenAiCompatibleBackend.MAX_TOKENS_BASE)
                    200 to reply("{\"results\": [{\"i\": 0}]}", "length")
                } else {
                    variants += "plain"
                    200 to reply("{\"results\":[{\"i\":0,\"p\":0},{\"i\":1,\"p\":1}]}", "stop")
                }
            })
        val verdicts = judge.evaluate(listOf("这期视频做得真用心", "加V领取兼职日结"), SemanticMode.WAIT)
        assertEquals(listOf(SemanticVerdict.KEEP, SemanticVerdict.BLOCK), verdicts)
        assertEquals(listOf("strict", "plain"), variants)
        // 没有分数的 2xx 同样换写法，而不是当成功白判。
        assertNull(OpenAiCompatibleBackend("m").decode(reply("{\"results\": [{\"i\": 0}]}", "length"), 2))
    }

    private fun chatReply(content: String, finish: String = "stop") = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("finish_reason", finish).put("message", JSONObject().put("role", "assistant").put("content", content)))).toString()

    private fun ladderRun(endpoint: String, accept: (JSONObject) -> Boolean): List<String> {
        val seen = mutableListOf<String>()
        val judge = SemanticJudge("k", rules, backend = OpenAiCompatibleBackend("m"), endpoint = endpoint,
            background = { it.run(); true }, transport = { body, _, _ ->
                val json = JSONObject(String(body))
                val tag = listOfNotNull(
                    if (json.has("response_format")) "strict" else "plain",
                    json.optString("reasoning_effort").takeIf { it.isNotEmpty() },
                    json.optJSONObject("reasoning")?.optString("effort"),
                    json.optJSONObject("thinking")?.optString("type"),
                    json.opt("enable_thinking")?.toString()
                ).joinToString("+")
                seen += tag
                if (accept(json)) 200 to chatReply("{\"results\":[{\"i\":0,\"p\":0.1}]}") else 400 to "{}"
            })
        assertEquals(listOf(SemanticVerdict.KEEP), judge.evaluate(listOf("一"), SemanticMode.WAIT))
        return seen
    }

    @Test
    fun `a source that rejects everything cannot burn the whole request budget`() {
        var requests = 0
        // 时钟必须一路往前走：否则第一个叶子耗尽变体阶梯后的冷却会把后续 fetchFrom 全部挡在
        // 门外（返回 "cooldown"、一个请求都不发），预算这条线根本轮不到绑定，测试就成了摆设。
        var now = 0L
        val judge = SemanticJudge("k", rules, backend = OpenAiCompatibleBackend("m"), batchSize = 20,
            background = { it.run(); true }, clock = { now += 60_000; now },
            transport = { _, _, _ ->
                requests += 1
                400 to "{\"error\":{\"message\":\"nope\"}}"
            })

        // 全部按未判出放行（fail-open），一次都不删。
        assertEquals(
            List(20) { SemanticVerdict.UNKNOWN },
            judge.evaluate(List(20) { "评论$it" }, SemanticMode.WAIT)
        )
        // 拆分树的每个叶子都要把变体阶梯爬一遍，没有上界就是几十次请求。
        assertTrue("requests=$requests", requests <= 24)
        // 预算确实被用满（MAX_REQUESTS_PER_CHUNK = 24），否则说明这条线压根没生效，
        // 上面的断言只是碰巧成立。
        assertEquals(24, requests)
    }

    @Test
    fun `a refill that judges nothing does not cool down the source that just answered`() {
        var requests = 0
        val judge = SemanticJudge("k", rules, backend = OpenAiCompatibleBackend("m"), batchSize = 20,
            background = { it.run(); true }, clock = { 1_000L },
            transport = { _, _, _ ->
                requests += 1
                if (requests == 1) 200 to chatReply("{\"results\":[{\"i\":0,\"p\":0.99}]}")
                else 200 to chatReply("{\"results\":[]}")
            })

        val verdicts = judge.evaluate(listOf("加V领取兼职日结", "文本一", "文本二", "文本三"), SemanticMode.WAIT)
        assertEquals(SemanticVerdict.BLOCK, verdicts[0])
        assertTrue("refill must have been attempted, requests=$requests", requests > 1)

        val before = requests
        judge.evaluate(listOf("另一条全新的文本"), SemanticMode.WAIT)
        assertTrue("source must not be in cooldown", requests > before)
    }

    @Test
    fun `a model that cannot turn thinking off runs at its lowest supported effort`() {
        // 2026-09-30 真机：基元律动转发的 GLM-5.3-FlashX 只认 reasoning_effort = low / high / max。
        val seen = ladderRun("https://tokenrhythm.studio/v1/chat/completions") { json ->
            json.optString("reasoning_effort") in setOf("low", "high", "max")
        }
        assertEquals(listOf("strict+none", "plain+none", "plain+minimal", "plain+low"), seen)
        // 之后直接用"低强度"，不再先被拒三次。
        val again = mutableListOf<String>()
        SemanticJudge("k", rules, backend = OpenAiCompatibleBackend("m"), endpoint = "https://tokenrhythm.studio/v1/chat/completions",
            background = { it.run(); true }, transport = { body, _, _ ->
                again += JSONObject(String(body)).optString("reasoning_effort")
                200 to chatReply("{\"results\":[{\"i\":0,\"p\":0.1}]}")
            }).evaluate(listOf("二"), SemanticMode.WAIT)
        assertEquals(listOf("low"), again)
    }

    @Test
    fun `a model without reasoning parameters keeps strict json mode`() {
        // 普通非推理模型：拒收任何 reasoning_effort，但支持 response_format → 落在 strict + 默认。
        val seen = ladderRun(OpenAiCompatibleBackend.DEFAULT_ENDPOINT) { json -> !json.has("reasoning_effort") }
        assertEquals(listOf("strict+none", "plain+none", "plain+minimal", "plain+low", "strict"), seen)
    }

    @Test
    fun `switch only sites skip effort levels instead of repeating requests`() {
        // 智谱只有开关没有强度：关不掉时直接到默认，不发 minimal / low 两个与默认一样的请求。
        val seen = ladderRun("https://open.bigmodel.cn/api/paas/v4/chat/completions") { json -> !json.has("thinking") }
        assertEquals(listOf("strict+disabled", "plain+disabled", "strict"), seen)
        // DeepSeek 没有 minimal：关不掉时试 low。
        val deepseek = ladderRun("https://api.deepseek.com/chat/completions") { json -> json.optString("reasoning_effort") == "low" }
        assertEquals(listOf("strict+disabled", "plain+disabled", "plain+low"), deepseek)
    }

    @Test
    fun `the chat compat layer is usable without the judge`() {
        // 以后别的 AI 功能直接用通用层：组请求、取正文、找 JSON。
        val body = AiChatCompat.buildRequest("m", "sys", "user", 0, "https://api.deepseek.com/chat/completions", 256)
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertEquals(256, body.getInt("max_tokens"))
        val content = AiChatCompat.contentOf(chatReply("<think>先想 {x</think>```json\n{\"keywords\":[\"a\"]}\n```"))!!
        assertEquals("a", AiChatCompat.findJsonObject(content) { it.has("keywords") }!!.getJSONArray("keywords").getString(0))
        assertTrue(AiChatCompat.truncated(chatReply("{", "length")))
    }
}
