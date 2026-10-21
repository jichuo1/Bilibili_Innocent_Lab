package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import org.junit.Test

class SemanticSourcesTest {

    /** 批量/补发类断言假设一题一文本；拆题后一题 = 一条 × 一条规则，取一条规则保持题数 = 条目数。 */
    private val rules = SemanticPresets.COMMENT.take(1)

    @get:Rule val temp = TemporaryFolder()

    @Before fun reset() = SemanticJudge.resetLearnedVariants()

    @After fun clear() = SemanticJudge.resetLearnedVariants()

    private fun source(index: Int, endpoint: String = "https://s$index.example.com") =
        requireNotNull(SemanticSource.from(index, "key$index", endpoint, SemanticBackend.JEV, ""))

    private fun answers(body: ByteArray, block: Double = 0.1): String {
        val count = JSONObject(String(body)).getJSONObject("questions").length()
        val map = JSONObject()
        repeat(count) {
            map.put("item_$it", JSONObject().put("type", "choice")
                .put("probabilities", JSONObject().put("block", block).put("keep", 1 - block)))
        }
        return JSONObject().put("answers", map).toString()
    }

    private fun questions(body: ByteArray) = JSONObject(String(body)).getJSONObject("questions").length()

    private fun judge(
        pool: SemanticSourcePool,
        route: List<SemanticSource> = pool.sources,
        guidance: String = "",
        clock: () -> Long = System::currentTimeMillis,
        transport: (ByteArray, String, Int) -> Pair<Int, String>?
    ) = SemanticJudge("unused", rules, batchSize = 4, background = { it.run(); true }, clock = clock,
        transport = transport, sourcePool = pool, sourceRoute = route, guidance = guidance)

    @Test
    fun `all eight configured sources survive settings and source eight can be fixed`() {
        val extras = (2..8).map(::source)
        val settings = requireNotNull(SemanticSettings.from("key1", "", "", false, extraSources = extras))
        assertEquals((1..8).toList(), settings.sources.map { it.index })
        assertEquals(listOf(8), SemanticRoute.resolve("8", settings.sources).map { it.index })
        assertEquals(settings.sources, SemanticRoute.resolve("9", settings.sources))
        assertEquals(setOf("auto") + (1..8).map(Int::toString), SemanticRoute.IDS)
        val called = mutableListOf<String>()
        judge(settings.pool, route = SemanticRoute.resolve("8", settings.sources)) { body, key, _ ->
            called += key
            200 to answers(body)
        }.evaluate(listOf("八号来源"), SemanticMode.WAIT)
        assertEquals(listOf("key8"), called)
    }

    @Test
    fun `invalid source indices and incomplete configurations never enter routing`() {
        listOf(-1, 0, 9, Int.MAX_VALUE).forEach { index ->
            assertNull(SemanticSource.from(index, "key", "", SemanticBackend.JEV, ""))
        }
        assertNull(SemanticSource.from(8, "", "", SemanticBackend.JEV, ""))
        assertNull(SemanticSource.from(8, "key", "https://api.example.com", SemanticBackend.OPENAI, ""))
        assertNull(SemanticSource.from(8, "key", "not a URL", SemanticBackend.OPENAI, "model"))
        val valid = listOf(source(2), source(7))
        assertEquals(valid, SemanticRoute.resolve("8", valid))
    }

    @Test
    fun `sequential initial requests reach all eight sources despite fast first response`() {
        val pool = SemanticSourcePool((1..8).map(::source))
        val slots = pool.slotsFor(pool.sources)
        val used = (1..8).map {
            val slot = requireNotNull(pool.acquire(slots, 0L))
            slot.recordLatency(1)
            pool.release(slot)
            slot.source.index
        }
        assertEquals((1..8).toList(), used)
        assertTrue(slots.all { it.inFlight.get() == 0 })
    }

    @Test
    fun `idle source gets a bounded reprobe without expanding concurrent work`() {
        val pool = SemanticSourcePool((1..8).map(::source))
        val slots = pool.slotsFor(pool.sources)
        repeat(8) {
            val slot = requireNotNull(pool.acquire(slots, 0L))
            slot.latencyMs = if (slot.source.index == 1) 1.0 else 10_000.0
            pool.release(slot)
        }
        val used = (1..(SemanticSourcePool.REPROBE_AFTER.toInt() + 8)).map {
            val slot = requireNotNull(pool.acquire(slots, 0L))
            assertEquals(1, slots.sumOf { it.inFlight.get() })
            pool.release(slot)
            slot.source.index
        }
        assertEquals((1..8).toSet(), used.toSet())
        assertTrue(used.count { it == 1 } > used.size / 2)
    }

    @Test
    fun `eight source balancing always respects cooldown exclusions and fixed routes`() {
        val pool = SemanticSourcePool((1..8).map(::source))
        val slots = pool.slotsFor(pool.sources)
        slots.take(7).forEach { it.holdUntil(100, 0) }
        val eighth = requireNotNull(pool.acquire(slots, 0L))
        assertEquals(8, eighth.source.index)
        pool.release(eighth)
        assertNull(pool.acquire(slots, 0L, exclude = setOf(eighth)))
        eighth.holdUntil(100, 0)
        assertNull(pool.acquire(slots, 0L))
        val fixed = pool.slotsFor(SemanticRoute.resolve("8", pool.sources))
        assertNull(pool.acquire(fixed, 50L))
        val recovered = requireNotNull(pool.acquire(fixed, 100L))
        assertEquals(8, recovered.source.index)
        pool.release(recovered)
    }

    @Test
    fun `settings keep source one from the legacy keys and add valid extra sources`() {
        val extra = listOfNotNull(
            SemanticSource.from(2, "k2", "https://api.deepseek.com", SemanticBackend.OPENAI, "deepseek-chat"),
            SemanticSource.from(3, "", "https://x.example.com", SemanticBackend.JEV, ""), // 没有 Key：不启用
            SemanticSource.from(4, "k4", "not a url", SemanticBackend.JEV, "") // 地址非法：不启用
        )
        val settings = SemanticSettings.from("k1", "", "", false, extraSources = extra)!!
        assertEquals(listOf(1, 2), settings.sources.map { it.index })
        assertEquals(JevBackend.DEFAULT_ENDPOINT, settings.endpoint)
        // 1 号没配、2 号配了：照样启用。
        assertEquals(listOf(2), SemanticSettings.from("", "", "", false, extraSources = extra)!!.sources.map { it.index })
        assertNull(SemanticSettings.from("", "", "", false))
        // copy() 沿用同一个来源池：各面共享冷却与负载。
        assertTrue(settings.copy(storeRoot = null).pool === settings.pool)
    }

    @Test
    fun `a fixed route uses only that source and a missing one falls back to all`() {
        val sources = listOf(source(1), source(2), source(3))
        assertEquals(listOf(2), SemanticRoute.resolve("2", sources).map { it.index })
        assertEquals(listOf(1, 2, 3), SemanticRoute.resolve("4", sources).map { it.index })
        assertEquals(listOf(1, 2, 3), SemanticRoute.resolve(SemanticRoute.AUTO, sources).map { it.index })
        assertEquals(listOf(1, 2, 3), SemanticRoute.resolve(null, sources).map { it.index })

        val used = mutableListOf<String>()
        val pool = SemanticSourcePool(sources)
        judge(pool, route = SemanticRoute.resolve("3", sources)) { body, key, _ -> used += key; 200 to answers(body) }
            .evaluate(List(12) { "条目$it" }, SemanticMode.WAIT)
        assertEquals(setOf("key3"), used.toSet())
    }

    @Test
    fun `auto balance spreads chunks by estimated finish time`() {
        val pool = SemanticSourcePool(listOf(source(1), source(2)))
        val slots = pool.slotsFor(pool.sources)
        // 1 号很慢、2 号很快：新请求交给 2 号。
        slots[0].recordLatency(10_000); repeat(5) { slots[0].recordLatency(10_000) }
        slots[1].recordLatency(200); repeat(5) { slots[1].recordLatency(200) }
        assertEquals(2, pool.pick(slots, 0L)!!.source.index)
        // 2 号手上已压了很多请求：估计完成时间超过 1 号，就交给 1 号。
        repeat(80) { slots[1].inFlight.incrementAndGet() }
        assertEquals(1, pool.pick(slots, 0L)!!.source.index)
        // 冷却中的来源不参与；都在冷却时没有可选。
        slots[0].cooldownUntil = 100L
        assertEquals(2, pool.pick(slots, 50L)!!.source.index)
        slots[1].cooldownUntil = 100L
        assertNull(pool.pick(slots, 50L))
    }

    @Test
    fun `untried sources are all used when chunks run in parallel`() {
        val pool = SemanticSourcePool(listOf(source(1), source(2), source(3)))
        val used = mutableListOf<String>()
        // 并发时选中和占用也必须原子完成，不能让多个分批挤到同一未探测来源。
        val gate = java.util.concurrent.CountDownLatch(1)
        val started = java.util.concurrent.CountDownLatch(3)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(3)
        val judge = SemanticJudge("unused", rules, batchSize = 4, timeoutMs = 5_000,
            background = { task -> executor.execute(task); true },
            transport = { body, key, _ ->
                synchronized(used) { used += key }
                started.countDown()
                gate.await()
                200 to answers(body)
            },
            sourcePool = pool)
        val worker = Thread { judge.evaluate(List(12) { "条目$it" }, SemanticMode.WAIT) }
        worker.start()
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        gate.countDown()
        worker.join(5_000)
        executor.shutdownNow()
        assertEquals(setOf("key1", "key2", "key3"), used.toSet())
    }

    @Test
    fun `a failing source hands only the unresolved entries to the next source`() {
        val pool = SemanticSourcePool(listOf(source(1), source(2)))
        val calls = mutableListOf<Pair<String, Int>>()
        val reports = mutableListOf<SemanticBatchReport>()
        val judge = judge(pool) { body, key, _ ->
            calls += key to questions(body)
            if (key == "key1") 503 to "{}" else 200 to answers(body, block = 0.9)
        }
        val verdicts = judge.evaluate(listOf("一", "二", "三"), SemanticMode.WAIT) { r, _, _ -> reports += r }
        assertEquals(List(3) { SemanticVerdict.BLOCK }, verdicts)
        assertEquals(listOf("key1" to 3, "key2" to 3), calls)
        assertEquals("ok", reports.single().outcome)
        assertTrue(reports.single().extras().contains("src=1,2"))
        // 1 号冷却中：下一批直接给 2 号，不再先撞一次 1 号。
        calls.clear()
        judge.evaluate(listOf("四"), SemanticMode.WAIT)
        assertEquals(listOf("key2" to 1), calls)
    }

    @Test
    fun `cooldown is shared across surfaces through the pool`() {
        var now = 1_000L
        val pool = SemanticSourcePool(listOf(source(1)))
        var calls = 0
        val transport: (ByteArray, String, Int) -> Pair<Int, String>? = { _, _, _ -> calls++; 401 to "{}" }
        judge(pool, clock = { now }, transport = transport).evaluate(listOf("一"), SemanticMode.WAIT)
        assertEquals(1, calls)
        // 另一个过滤面（另一个判定器，同一个池）：来源在冷却，不发请求。
        val other = SemanticJudge("unused", SemanticPresets.DANMAKU.take(1), background = { it.run(); true },
            clock = { now }, transport = transport, sourcePool = pool)
        assertEquals(listOf(SemanticVerdict.UNKNOWN), other.evaluate(listOf("弹幕"), SemanticMode.WAIT))
        assertEquals(1, calls)
        now += SemanticJudge.AUTH_COOLDOWN_MS
        other.evaluate(listOf("弹幕"), SemanticMode.WAIT)
        assertEquals(2, calls)
    }

    @Test
    fun `learned limits and question types are kept per source`() {
        val pool = SemanticSourcePool(listOf(source(1), source(2)))
        val sizes = mutableListOf<Pair<String, Int>>()
        // 1 号最多收 4 题；2 号不限。固定走 1 号探明上限后，2 号的分批不受影响。
        val transport: (ByteArray, String, Int) -> Pair<Int, String>? = { body, key, _ ->
            sizes += key to questions(body)
            if (key == "key1" && questions(body) > 4) 400 to "{}" else 200 to answers(body)
        }
        fun routed(source: SemanticSource) = SemanticJudge("unused", rules, batchSize = 8, background = { it.run(); true },
            transport = transport, sourcePool = pool, sourceRoute = listOf(source))
        routed(pool.sources[0]).evaluate(List(8) { "a$it" }, SemanticMode.WAIT)
        assertEquals(listOf("key1" to 8, "key1" to 4, "key1" to 4), sizes)
        sizes.clear()
        routed(pool.sources[0]).evaluate(List(8) { "c$it" }, SemanticMode.WAIT)
        assertEquals(listOf("key1" to 4, "key1" to 4), sizes) // 1 号记住了上限
        sizes.clear()
        routed(pool.sources[1]).evaluate(List(8) { "b$it" }, SemanticMode.WAIT)
        assertEquals(listOf("key2" to 8), sizes) // 2 号不受 1 号上限影响
    }

    @Test
    fun `guidance is editable but the safety clause is always sent`() {
        assertEquals("${SemanticGuidance.SAFETY} ${SemanticGuidance.DEFAULT_CRITERIA}", SemanticGuidance.of(""))
        val custom = SemanticGuidance.of("  只要提到转发抽奖就屏蔽。  ")
        assertTrue(custom.startsWith(SemanticGuidance.SAFETY))
        assertTrue(custom.endsWith("只要提到转发抽奖就屏蔽。"))
        assertEquals(SemanticGuidance.MAX_LENGTH, SemanticGuidance.normalize("x".repeat(5_000)).length)

        var sent = ""
        judge(SemanticSourcePool(listOf(source(1))), guidance = "拿不准就保留") { body, _, _ ->
            sent = JSONObject(String(body)).getJSONObject("state").getString("guidance")
            200 to answers(body)
        }.evaluate(listOf("一"), SemanticMode.WAIT)
        assertTrue(sent.startsWith(SemanticGuidance.SAFETY) && sent.endsWith("拿不准就保留"))
        val chat = JSONObject(String(OpenAiCompatibleBackend("m").encode(listOf("a"), rules, 0, SemanticGuidance.of("自定义口径"))))
            .getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(chat.contains(SemanticGuidance.SAFETY) && chat.contains("自定义口径") && chat.contains("\"results\""))
    }

    @Test
    fun `cache identity changes with sources guidance and custom rules but not with keys`() {
        val one = listOf(source(1))
        val base = SemanticJudge.fingerprintOf(rules, one, "")
        assertNotEquals(base, SemanticJudge.fingerprintOf(rules, one + source(2), ""))
        assertNotEquals(base, SemanticJudge.fingerprintOf(rules, one, "拿不准就保留"))
        val custom = SemanticCustomRule("c1", true, "账号交易", "出售或收购游戏账号").toRule()
        assertNotEquals(base, SemanticJudge.fingerprintOf(rules + custom, one, ""))
        val otherKey = listOf(requireNotNull(SemanticSource.from(1, "different", "https://s1.example.com", SemanticBackend.JEV, "")))
        assertEquals(base, SemanticJudge.fingerprintOf(rules, otherKey, ""))
        // 来源顺序不影响身份。
        assertEquals(SemanticJudge.fingerprintOf(rules, listOf(source(1), source(2)), ""),
            SemanticJudge.fingerprintOf(rules, listOf(source(2), source(1)), ""))
    }

    @Test
    fun `custom rules round trip with limits and bad input is tolerated`() {
        val rule = SemanticCustomRule.sanitize(
            id = "c1", enabled = true,
            type = "  账号：交易  ",
            covers = "出售、收购或\n代练游戏账号",
            notFor = "讨论账号安全",
            examples = listOf("出号，私聊", "", "收号 高价", "第四条", "第五条"),
            keepExamples = listOf("账号被盗了怎么办")
        )!!
        assertEquals("账号：交易", rule.type)
        assertEquals("出售、收购或 代练游戏账号", rule.covers)
        assertEquals(3, rule.examples.size)
        val encoded = SemanticCustomRule.encode(listOf(rule, rule.copy(id = "c2", enabled = false)))
        val decoded = SemanticCustomRule.parse(encoded)
        assertEquals(listOf(rule, rule.copy(id = "c2", enabled = false)), decoded)
        // 发给模型的类型名不含全角冒号（否则会被当作"类型：包括"分隔符）。
        assertEquals("账号:交易", decoded[0].toRule().type)
        assertEquals("出售、收购或 代练游戏账号", decoded[0].toRule().covers)
        assertEquals("讨论账号安全", decoded[0].toRule().notFor)

        assertNull(SemanticCustomRule.sanitize("c1", true, "", "有内容", "", emptyList(), emptyList()))
        assertNull(SemanticCustomRule.sanitize("c1", true, "有名字", "  ", "", emptyList(), emptyList()))
        assertEquals(emptyList<SemanticCustomRule>(), SemanticCustomRule.parse("不是 JSON"))
        assertEquals(emptyList<SemanticCustomRule>(), SemanticCustomRule.parse(""))
        // 非法 id、重复 id 跳过；超过 8 条截断。
        val many = (1..12).joinToString(",", "[", "]") { """{"id":"c${(it - 1) % 9 + 1}","type":"t$it","covers":"c"}""" }
        val parsed = SemanticCustomRule.parse(many)
        assertTrue(parsed.size <= SemanticCustomRule.MAX_RULES)
        assertEquals(parsed.map { it.id }.distinct(), parsed.map { it.id })
        assertTrue(parsed.all { Regex("c[1-8]").matches(it.id) })
        assertEquals("c3", SemanticCustomRule.nextId(listOf(rule, rule.copy(id = "c2"))))
        assertNull(SemanticCustomRule.nextId((1..8).map { rule.copy(id = "c$it") }))
        assertEquals("", SemanticCustomRule.encode(emptyList()))
        assertFalse(SemanticPresets.of(SemanticSurface.COMMENT).any { Regex("c[1-8]").matches(it.id) })
    }

    @Test
    fun `failing sources back off exponentially and recover on success`() {
        val slot = SemanticSourcePool(listOf(source(1))).slotsFor(listOf(source(1))).single()
        val base = SemanticJudge.FAILURE_COOLDOWN_MS
        slot.penalize(base, 0L); assertEquals(base, slot.cooldownUntil)
        slot.penalize(base, 0L); assertEquals(base * 2, slot.cooldownUntil)
        slot.penalize(base, 0L); assertEquals(base * 4, slot.cooldownUntil)
        repeat(10) { slot.penalize(base, 0L) }
        assertEquals(SemanticSourcePool.MAX_BACKOFF_MS, slot.cooldownUntil) // 封顶 5 分钟
        slot.recover()
        slot.penalize(base, 1_000L); assertEquals(1_000L + base, slot.cooldownUntil)
    }

    @Test
    fun `a slow source is hedged by another and the first success wins`() {
        val pool = SemanticSourcePool(listOf(source(1), source(2)))
        pool.slotsFor(pool.sources)[0].latencyMs = 100.0 // 平常很快 → 300 ms 没回来就对冲
        val executor = java.util.concurrent.Executors.newCachedThreadPool()
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val reports = mutableListOf<SemanticBatchReport>()
        val judge = SemanticJudge("unused", rules, timeoutMs = 5_000, background = { it.run(); true },
            hedgeExecutor = { task -> executor.execute(task); true },
            transport = { body, key, _ ->
                calls += key
                if (key == "key1") Thread.sleep(2_000)
                200 to answers(body, block = if (key == "key1") 0.1 else 0.9)
            },
            sourcePool = pool)
        val started = System.currentTimeMillis()
        val verdicts = judge.evaluate(listOf("一", "二"), SemanticMode.WAIT) { r, _, _ -> reports += r }
        val elapsed = System.currentTimeMillis() - started
        executor.shutdown()
        assertEquals(List(2) { SemanticVerdict.BLOCK }, verdicts) // 用的是 2 号的结果
        assertTrue("elapsed=$elapsed", elapsed < 1_500)
        assertEquals(listOf("key1", "key2"), calls.toList())
        assertTrue(reports.single().extras().contains("hedge=1"))
    }

    @Test
    fun `no hedge is sent when the first source answers in time`() {
        val pool = SemanticSourcePool(listOf(source(1), source(2)))
        val executor = java.util.concurrent.Executors.newCachedThreadPool()
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val judge = SemanticJudge("unused", rules, background = { it.run(); true },
            hedgeExecutor = { task -> executor.execute(task); true },
            transport = { body, key, _ -> calls += key; 200 to answers(body) },
            sourcePool = pool)
        judge.evaluate(listOf("一"), SemanticMode.WAIT)
        executor.shutdown()
        assertEquals(listOf("key1"), calls.toList())
    }

    @Test
    fun `borderline scores get a second opinion that is averaged into the cache`() {
        val pool = SemanticSourcePool(listOf(source(1), source(2)))
        val calls = mutableListOf<Pair<String, Int>>()
        val judge = SemanticJudge("unused", rules, background = { it.run(); true }, transport = { body, key, _ ->
            calls += key to questions(body)
            // 1 号：第一条紧挨门槛（中灵敏度 0.6），第二条明确；2 号认为第一条不该删。
            val count = questions(body)
            val map = JSONObject()
            repeat(count) { i ->
                val block = if (key == "key1") (if (count == 2 && i == 1) 0.95 else 0.62) else 0.3
                map.put("item_$i", JSONObject().put("type", "choice")
                    .put("probabilities", JSONObject().put("block", block).put("keep", 1 - block)))
            }
            200 to JSONObject().put("answers", map).toString()
        }, sourcePool = pool)
        val first = judge.evaluate(listOf("边界", "明确"), SemanticMode.WAIT)
        assertEquals(listOf(SemanticVerdict.BLOCK, SemanticVerdict.BLOCK), first) // 本次按第一个分数
        // 紧挨门槛的那条（灰区）和新来源判的"屏蔽"都去复核；2 号两条都不同意。
        assertEquals(listOf("key1" to 2, "key2" to 2), calls)
        // 下次：缓存里是按可信度加权的合并分（1 号被推翻两次、权重下降）→ 两条都保留。
        assertEquals(listOf(SemanticVerdict.KEEP, SemanticVerdict.KEEP),
            judge.evaluate(listOf("边界", "明确"), SemanticMode.CACHE_ONLY))
        // 同一条不会反复复核。
        calls.clear()
        judge.evaluate(listOf("边界"), SemanticMode.WAIT)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a single source never hedges or asks for a second opinion`() {
        val calls = mutableListOf<String>()
        val judge = SemanticJudge("unused", rules, background = { it.run(); true },
            hedgeExecutor = { it.run(); true },
            transport = { body, key, _ -> calls += key; 200 to answers(body, block = 0.6) },
            sourcePool = SemanticSourcePool(listOf(source(1))))
        judge.evaluate(listOf("一"), SemanticMode.WAIT)
        assertEquals(listOf("key1"), calls)
    }

    @Test
    fun `long runs of one character share a cache key but still read as flooding`() {
        val thirty = TextNormalizer.forSemantic("啊".repeat(30))
        assertEquals(thirty, TextNormalizer.forSemantic("啊".repeat(31)))
        assertEquals(TextNormalizer.MAX_RUN, thirty.length)
        assertEquals("😂".repeat(TextNormalizer.MAX_RUN), TextNormalizer.forSemantic("😂".repeat(40))) // 按码点，表情不被劈开
        assertEquals("哈哈哈哈好笑", TextNormalizer.forSemantic("哈哈哈哈好笑")) // 普通文本原样
        assertEquals("a".repeat(12) + "b" + "a".repeat(12), TextNormalizer.capRuns("a".repeat(20) + "b" + "a".repeat(15)))
        assertEquals(SemanticJudge.clip("前方高能" + "!".repeat(50)), SemanticJudge.clip("前方高能" + "!".repeat(13)))
    }

    @Test
    fun `thread pools grow with the number of sources and stay capped`() {
        assertEquals(4, SemanticJudge.networkThreads(1))
        assertEquals(8, SemanticJudge.networkThreads(2))
        assertEquals(8, SemanticJudge.networkThreads(4))
        assertEquals(8, SemanticJudge.networkThreads(8))
        assertEquals(8, SemanticJudge.waitThreads(1))
        assertEquals(10, SemanticJudge.waitThreads(2))
        assertEquals(10, SemanticJudge.waitThreads(4))
        assertEquals(10, SemanticJudge.waitThreads(8))
    }

    @Test
    fun `rate limit resets are read from openrouter bodies and retry headers`() {
        val now = 1_790_750_000_000L
        // 2026-09-30 真机拿到的 OpenRouter 免费档限流响应（节选）。
        val openRouter = """{"error":{"message":"Rate limit exceeded: free-models-per-day","code":429,"metadata":{"headers":{"X-RateLimit-Limit":"50","X-RateLimit-Remaining":"0","X-RateLimit-Reset":"1790812800000"}}}}"""
        assertEquals(1_790_812_800_000L, SemanticSourcePool.rateLimitResetAt(openRouter, now))
        assertEquals(now + 30_000, SemanticSourcePool.rateLimitResetAt("""{"_headers":{"retry-after":"30"}}""", now))
        assertEquals(1_790_760_000_000L, SemanticSourcePool.rateLimitResetAt("""{"_headers":{"x-ratelimit-reset":"1790760000"}}""", now))
        assertNull(SemanticSourcePool.rateLimitResetAt("""{"error":{"message":"busy"}}""", now))
        assertNull(SemanticSourcePool.rateLimitResetAt("not json", now))
    }

    @Test
    fun `a rate limited source waits for its reset instead of retrying every few minutes`() {
        var now = 1_790_750_000_000L
        val reset = now + 5 * 60 * 60 * 1000L
        val pool = SemanticSourcePool(listOf(source(1), source(2)))
        val calls = mutableListOf<String>()
        val judge = SemanticJudge("unused", rules, background = { it.run(); true }, clock = { now }, transport = { body, key, _ ->
            calls += key
            if (key == "key1") 429 to """{"error":{"metadata":{"headers":{"X-RateLimit-Reset":"$reset"}}}}"""
            else 200 to answers(body)
        }, sourcePool = pool)
        judge.evaluate(listOf("一"), SemanticMode.WAIT)
        assertEquals(listOf("key1", "key2"), calls) // 1 号限流 → 转给 2 号
        assertEquals(reset, pool.slotsFor(pool.sources)[0].cooldownUntil)
        now += SemanticSourcePool.MAX_BACKOFF_MS + 1 // 普通退避早就过了，但还没到重置时刻
        calls.clear()
        judge.evaluate(listOf("二"), SemanticMode.WAIT)
        assertEquals(listOf("key2"), calls)
        // 说"明天再来"也最多等 24 小时。
        val slot = pool.slotsFor(pool.sources)[1]
        slot.holdUntil(now + 10 * SemanticSourcePool.MAX_RATE_LIMIT_WAIT_MS, now)
        assertEquals(now + SemanticSourcePool.MAX_RATE_LIMIT_WAIT_MS, slot.cooldownUntil)
    }

    @Test
    fun `a source whose blocks keep being overturned stops deleting on its own`() {
        val pool = SemanticSourcePool(listOf(source(1), source(2)))
        val (sloppy, careful) = pool.slotsFor(pool.sources)
        careful.latencyMs = 1e9 // 让新请求总先给 1 号（模拟"乱删"的来源先判）
        val reports = mutableListOf<SemanticBatchReport>()
        val judge = SemanticJudge("unused", rules, background = { it.run(); true }, transport = { body, key, _ ->
            200 to answers(body, block = if (key == "key1") 0.95 else 0.1)
        }, sourcePool = pool)
        // 前几轮：1 号说删、2 号复核说留 → 1 号每次都被推翻；这期间 1 号的屏蔽仍照常执行（记录还不够）。
        repeat(SemanticSourcePool.MIN_TRUST_CHECKS.toInt() + 1) { round ->
            assertEquals(listOf(SemanticVerdict.BLOCK), judge.evaluate(listOf("正常评论$round"), SemanticMode.WAIT))
        }
        assertTrue(sloppy.untrusted)
        assertTrue(sloppy.trust < SemanticSourcePool.LOW_TRUST)
        // 之后：1 号判的"屏蔽"先不执行（内容照常显示），复核后缓存里是按可信度加权的结果（保留）。
        val verdict = judge.evaluate(listOf("另一条正常评论"), SemanticMode.WAIT) { r, _, _ -> reports += r }
        assertEquals(listOf(SemanticVerdict.UNKNOWN), verdict)
        assertTrue(reports.single().extras().contains("withheld=1"))
        assertEquals(listOf(SemanticVerdict.KEEP), judge.evaluate(listOf("另一条正常评论"), SemanticMode.CACHE_ONLY))
        // 分流：可信度低的来源估计完成时间被放大，同样耗时下新请求交给另一个来源。
        careful.latencyMs = sloppy.latencyMs
        assertEquals(2, pool.pick(pool.slotsFor(pool.sources), 0L)!!.source.index)
        // 2 号从没被推翻：不受影响。
        assertFalse(careful.untrusted)
    }

    @Test
    fun `late trust restoration matches restoring before reviews and is applied only once`() {
        val early = SemanticSourcePool.Slot(source(1))
        val late = SemanticSourcePool.Slot(source(1))
        early.restoreTrust(20.0, 8.0)
        listOf(true, false).forEach { agreed ->
            early.recordReview(agreed)
            late.recordReview(agreed)
        }
        late.restoreTrust(20.0, 8.0)
        assertEquals(early.agreements, late.agreements, 1e-12)
        assertEquals(early.disputes, late.disputes, 1e-12)
        val restored = late.trustSnapshot()
        late.restoreTrust(20.0, 8.0)
        assertEquals(restored, late.trustSnapshot())
        early.recordReview(agreed = true)
        late.recordReview(agreed = true)
        assertEquals(early.agreements, late.agreements, 1e-12)
        assertEquals(early.disputes, late.disputes, 1e-12)
    }

    @Test
    fun `loading old trust after live reviews merges history without repeated attach inflation`() {
        val root = temp.newFolder("late-host-files")
        val configured = source(1)
        val file = File(File(root, "bil_semantic"), SemanticJudge.LEARNED_FILE)
        file.parentFile!!.mkdirs()
        val original = "v ${configured.learnKey} 3\nb ${configured.learnKey} 7\nt ${configured.learnKey} 2000 800\n"
        file.writeText(original)
        val pool = SemanticSourcePool(listOf(configured))
        val slot = pool.slotsFor(pool.sources).single()
        slot.recordReview(agreed = true)
        slot.recordReview(agreed = false)
        SemanticJudge.attachLearnedStore(root)
        val decay = SemanticSourcePool.TRUST_DECAY
        assertEquals(20.0 * decay * decay + decay, slot.agreements, 1e-12)
        assertEquals(8.0 * decay * decay + 1.0, slot.disputes, 1e-12)
        val restored = slot.trustSnapshot()
        SemanticJudge.attachLearnedStore(root)
        SemanticJudge.registerPool(pool)
        assertEquals(restored, slot.trustSnapshot())
        assertEquals(original, file.readText())
    }

    @Test
    fun `source trust survives a restart`() {
        val root = temp.newFolder("host-files")
        SemanticJudge.attachLearnedStore(root)
        val pool = SemanticSourcePool(listOf(source(1), source(2)))
        val slot = pool.slotsFor(pool.sources)[0]
        repeat(6) { slot.recordReview(agreed = false) }
        // 触发一次写盘（真机由复核结果触发）：借一次"探明写法"。
        val transport: (ByteArray, String, Int) -> Pair<Int, String>? = { body, _, _ ->
            if (JSONObject(String(body)).opt("state") !is String) 400 to "{}" else 200 to answers(body)
        }
        SemanticJudge("unused", rules, background = { it.run(); true }, transport = transport,
            sourcePool = pool, sourceRoute = listOf(pool.sources[1])).evaluate(listOf("一"), SemanticMode.WAIT)
        val file = File(File(root, "bil_semantic"), SemanticJudge.LEARNED_FILE)
        repeat(100) { if (!file.isFile || !file.readText().contains("t ")) Thread.sleep(20) }
        assertTrue(file.readText().lines().any { it.startsWith("t ${pool.sources[0].learnKey} ") })

        // "重启"：新的来源池（同一来源身份）读回可信度。
        SemanticJudge.resetLearnedVariants()
        val restarted = SemanticSourcePool(listOf(source(1), source(2)))
        SemanticJudge.attachLearnedStore(root)
        assertTrue(restarted.slotsFor(restarted.sources)[0].untrusted)
        assertFalse(restarted.slotsFor(restarted.sources)[1].untrusted)
    }

    /** 只给 [answered] 里的题目作答（模拟模型漏答），分数 0.1。 */
    private fun partial(body: ByteArray, answered: (Int) -> Boolean): String {
        val count = questions(body)
        val map = JSONObject()
        (0 until count).filter(answered).forEach {
            map.put("item_$it", JSONObject().put("type", "choice")
                .put("probabilities", JSONObject().put("block", 0.1).put("keep", 0.9)))
        }
        return JSONObject().put("answers", map).toString()
    }

    private fun single(transport: (ByteArray, String, Int) -> Pair<Int, String>?) =
        SemanticJudge("unused", rules, batchSize = 8, background = { it.run(); true }, transport = transport,
            sourcePool = SemanticSourcePool(listOf(source(1))))

    @Test
    fun `entries the model skipped are re-sent once and merged`() {
        val sizes = mutableListOf<Int>()
        val reports = mutableListOf<SemanticBatchReport>()
        val judge = single { body, _, _ ->
            val count = questions(body)
            sizes += count
            // 第一次漏答 5、6、7 三条；补发时全答。
            200 to if (count == 8) partial(body) { it < 5 } else answers(body)
        }
        val verdicts = judge.evaluate(List(8) { "条目$it" }, SemanticMode.WAIT) { r, _, _ -> reports += r }
        assertTrue(verdicts.all { it == SemanticVerdict.KEEP })
        assertEquals(listOf(8, 3), sizes) // 只补发缺的 3 条
        assertTrue(reports.single().extras().contains("refill=3"))
        // 补回的分数也进了缓存。
        assertEquals(List(3) { SemanticVerdict.KEEP }, judge.evaluate(List(3) { "条目${it + 5}" }, SemanticMode.CACHE_ONLY))
    }

    @Test
    fun `a single skipped entry is not worth a refill`() {
        val sizes = mutableListOf<Int>()
        val verdicts = single { body, _, _ ->
            sizes += questions(body)
            200 to partial(body) { it != 7 }
        }.evaluate(List(8) { "条目$it" }, SemanticMode.WAIT)
        assertEquals(listOf(8), sizes)
        assertEquals(SemanticVerdict.UNKNOWN, verdicts[7]) // 缺的照常放行
    }

    @Test
    fun `a refill is sent at most once and its failure keeps what was judged`() {
        val sizes = mutableListOf<Int>()
        val stillSkipping = single { body, _, _ ->
            sizes += questions(body)
            200 to partial(body) { it == 0 } // 每次都只答第一题
        }.evaluate(List(8) { "条目$it" }, SemanticMode.WAIT)
        assertEquals(listOf(8, 7), sizes) // 补发一次，补发里又漏的不再补
        assertEquals(2, stillSkipping.count { it == SemanticVerdict.KEEP })

        sizes.clear()
        val refillFails = single { body, _, _ ->
            val count = questions(body)
            sizes += count
            if (count == 8) 200 to partial(body) { it < 4 } else 503 to "{}"
        }.evaluate(List(8) { "条目$it" }, SemanticMode.WAIT)
        assertEquals(listOf(8, 4), sizes)
        assertEquals(4, refillFails.count { it == SemanticVerdict.KEEP }) // 已判出的不受补发失败影响
        assertEquals(4, refillFails.count { it == SemanticVerdict.UNKNOWN })
    }
}
