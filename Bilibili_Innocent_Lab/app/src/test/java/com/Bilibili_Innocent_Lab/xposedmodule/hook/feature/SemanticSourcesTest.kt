package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.Admission
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.ItemKey
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.ManualChoice
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.Operation
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerQualitySessions.Outcome
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SemanticSourcesTest {

    /** 批量/补发类断言假设一题一文本；拆题后一题 = 一条 × 一条规则，取一条规则保持题数 = 条目数。 */
    private val rules = SemanticPresets.COMMENT.take(1)

    val temp = TemporaryFolder()

    fun reset() = SemanticJudge.resetLearnedVariants()

    fun clear() = SemanticJudge.resetLearnedVariants()

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

    fun `a single source never hedges or asks for a second opinion`() {
        val calls = mutableListOf<String>()
        val judge = SemanticJudge("unused", rules, background = { it.run(); true },
            hedgeExecutor = { it.run(); true },
            transport = { body, key, _ -> calls += key; 200 to answers(body, block = 0.6) },
            sourcePool = SemanticSourcePool(listOf(source(1))))
        judge.evaluate(listOf("一"), SemanticMode.WAIT)
        assertEquals(listOf("key1"), calls)
    }

    fun `long runs of one character share a cache key but still read as flooding`() {
        val thirty = TextNormalizer.forSemantic("啊".repeat(30))
        assertEquals(thirty, TextNormalizer.forSemantic("啊".repeat(31)))
        assertEquals(TextNormalizer.MAX_RUN, thirty.length)
        assertEquals("😂".repeat(TextNormalizer.MAX_RUN), TextNormalizer.forSemantic("😂".repeat(40))) // 按码点，表情不被劈开
        assertEquals("哈哈哈哈好笑", TextNormalizer.forSemantic("哈哈哈哈好笑")) // 普通文本原样
        assertEquals("a".repeat(12) + "b" + "a".repeat(12), TextNormalizer.capRuns("a".repeat(20) + "b" + "a".repeat(15)))
        assertEquals(SemanticJudge.clip("前方高能" + "!".repeat(50)), SemanticJudge.clip("前方高能" + "!".repeat(13)))
    }

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

    fun `a single skipped entry is not worth a refill`() {
        val sizes = mutableListOf<Int>()
        val verdicts = single { body, _, _ ->
            sizes += questions(body)
            200 to partial(body) { it != 7 }
        }.evaluate(List(8) { "条目$it" }, SemanticMode.WAIT)
        assertEquals(listOf(8), sizes)
        assertEquals(SemanticVerdict.UNKNOWN, verdicts[7]) // 缺的照常放行
    }

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

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (SemanticSourcesTest) -> Unit) {
            val s = SemanticSourcesTest()
            s.temp.create()
            try {
                s.reset()
                try {
                    block(s)
                } finally {
                    s.clear()
                }
            } finally {
                s.temp.delete()
            }
        }
        runIsolated { s -> s.`all eight configured sources survive settings and source eight can be fixed`() }
        runIsolated { s -> s.`invalid source indices and incomplete configurations never enter routing`() }
        runIsolated { s -> s.`sequential initial requests reach all eight sources despite fast first response`() }
        runIsolated { s -> s.`idle source gets a bounded reprobe without expanding concurrent work`() }
        runIsolated { s -> s.`eight source balancing always respects cooldown exclusions and fixed routes`() }
        runIsolated { s -> s.`settings keep source one from the legacy keys and add valid extra sources`() }
        runIsolated { s -> s.`a fixed route uses only that source and a missing one falls back to all`() }
        runIsolated { s -> s.`auto balance spreads chunks by estimated finish time`() }
        runIsolated { s -> s.`untried sources are all used when chunks run in parallel`() }
        runIsolated { s -> s.`a failing source hands only the unresolved entries to the next source`() }
        runIsolated { s -> s.`cooldown is shared across surfaces through the pool`() }
        runIsolated { s -> s.`learned limits and question types are kept per source`() }
        runIsolated { s -> s.`guidance is editable but the safety clause is always sent`() }
        runIsolated { s -> s.`cache identity changes with sources guidance and custom rules but not with keys`() }
        runIsolated { s -> s.`custom rules round trip with limits and bad input is tolerated`() }
        runIsolated { s -> s.`failing sources back off exponentially and recover on success`() }
        runIsolated { s -> s.`a slow source is hedged by another and the first success wins`() }
        runIsolated { s -> s.`no hedge is sent when the first source answers in time`() }
        runIsolated { s -> s.`borderline scores get a second opinion that is averaged into the cache`() }
        runIsolated { s -> s.`a single source never hedges or asks for a second opinion`() }
        runIsolated { s -> s.`long runs of one character share a cache key but still read as flooding`() }
        runIsolated { s -> s.`thread pools grow with the number of sources and stay capped`() }
        runIsolated { s -> s.`rate limit resets are read from openrouter bodies and retry headers`() }
        runIsolated { s -> s.`a rate limited source waits for its reset instead of retrying every few minutes`() }
        runIsolated { s -> s.`a source whose blocks keep being overturned stops deleting on its own`() }
        runIsolated { s -> s.`late trust restoration matches restoring before reviews and is applied only once`() }
        runIsolated { s -> s.`loading old trust after live reviews merges history without repeated attach inflation`() }
        runIsolated { s -> s.`source trust survives a restart`() }
        runIsolated { s -> s.`entries the model skipped are re-sent once and merged`() }
        runIsolated { s -> s.`a single skipped entry is not worth a refill`() }
        runIsolated { s -> s.`a refill is sent at most once and its failure keeps what was judged`() }
    
        ShareLinkPurifierCases().verifyAllCases()
        SmallCardGroupingCases().verifyAllCases()
        StoryPurifyFeatureInstallerCases().verifyAllCases()
        StoryQualityReparseGateCases().verifyAllCases()
        StoryQualityRuntimeCases().verifyAllCases()
        SubtitlePackagePatcherCases().verifyAllCases()
        SubtitleScriptAdapterCases().verifyAllCases()
    }
}

private class ShareLinkPurifierCases {

    fun `drops tracking parameters and keeps playback position`() {
        assertEquals(
            "https://www.bilibili.com/video/BV1xx411c7mD?p=2&t=90",
            ShareLinkPurifier.purifyUrl(
                "https://www.bilibili.com/video/BV1xx411c7mD" +
                    "?p=2&t=90&share_source=copy_web&vd_source=deadbeef&spm_id_from=333.999"
            )
        )
    }

    fun `converts millisecond progress into the seconds parameter`() {
        assertEquals(
            "https://b23.tv/abcdefg?t=95",
            ShareLinkPurifier.purifyUrl("https://b23.tv/abcdefg?start_progress=95400&bbid=xyz")
        )
    }

    fun `returns null when there is nothing to clean`() {
        assertNull(ShareLinkPurifier.purifyUrl("https://www.bilibili.com/video/BV1xx411c7mD"))
        assertNull(ShareLinkPurifier.purifyUrl("https://www.bilibili.com/video/BV1xx411c7mD?p=1"))
    }

    fun `leaves third party and non http links untouched`() {
        assertNull(ShareLinkPurifier.purifyUrl("https://example.com/watch?utm_source=bili"))
        assertNull(ShareLinkPurifier.purifyUrl("bilibili://video/1?share_source=copy"))
        // 后缀相同但并非同一站点，不能误判成 B 站域名。
        assertNull(ShareLinkPurifier.purifyUrl("https://notbilibili.com/x?share_source=copy"))
    }

    fun `keeps subdomains and strips the port only for host matching`() {
        assertEquals(
            "https://m.bilibili.com/video/BV1xx411c7mD",
            ShareLinkPurifier.purifyUrl(
                "https://m.bilibili.com/video/BV1xx411c7mD?share_medium=android"
            )
        )
    }

    fun `preserves the fragment while rewriting the query`() {
        assertEquals(
            "https://www.bilibili.com/read/cv1?t=3#reply",
            ShareLinkPurifier.purifyUrl("https://www.bilibili.com/read/cv1?t=3&bbid=1#reply")
        )
    }

    fun `purifies every bilibili link inside share text`() {
        val text = "看看这个 https://b23.tv/abc?share_source=copy_web 还有 https://example.com/a?x=1"
        assertEquals(
            "看看这个 https://b23.tv/abc 还有 https://example.com/a?x=1",
            ShareLinkPurifier.purifyText(text)
        )
    }

    fun `share text without tracked links is left alone`() {
        assertNull(ShareLinkPurifier.purifyText("纯文本，没有链接"))
        assertNull(ShareLinkPurifier.purifyText("https://example.com/a?utm=1"))
    }

    fun `purifying twice is stable`() {
        val once = requireNotNull(
            ShareLinkPurifier.purifyUrl("https://b23.tv/abc?share_source=copy_web&t=5")
        )
        assertEquals("https://b23.tv/abc?t=5", once)
        assertNull(ShareLinkPurifier.purifyUrl(once))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ShareLinkPurifierCases) -> Unit) {
            val s = ShareLinkPurifierCases()
            block(s)
        }
        runIsolated { s -> s.`drops tracking parameters and keeps playback position`() }
        runIsolated { s -> s.`converts millisecond progress into the seconds parameter`() }
        runIsolated { s -> s.`returns null when there is nothing to clean`() }
        runIsolated { s -> s.`leaves third party and non http links untouched`() }
        runIsolated { s -> s.`keeps subdomains and strips the port only for host matching`() }
        runIsolated { s -> s.`preserves the fragment while rewriting the query`() }
        runIsolated { s -> s.`purifies every bilibili link inside share text`() }
        runIsolated { s -> s.`share text without tracked links is left alone`() }
        runIsolated { s -> s.`purifying twice is stable`() }
    }
}

private class SmallCardGroupingCases {
    private data class Card(val name: String, val small: Boolean?)

    private fun s(name: String) = Card(name, true)
    private fun l(name: String) = Card(name, false)
    private val style: (Any?) -> Boolean? = { (it as? Card)?.small }

    fun discardMirrorsTheHostWindowRuleForPairsAndWiderGroups() {
        // 宿主：小卡按顺序凑满一组；遇到大卡或列表结束时未凑满的整组丢弃。
        assertEquals(0, SmallCardGrouping.discarded(listOf(s("1"), s("2"), l("A"), s("3"), s("4")), 2, style))
        assertEquals(2, SmallCardGrouping.discarded(listOf(s("1"), l("A"), s("2"), s("3"), s("4")), 2, style))
        // 三列：大卡前待定的两张与末尾两张都凑不满三张，宿主整组丢弃。
        assertEquals(4, SmallCardGrouping.discarded(listOf(s("1"), s("2"), l("A"), s("3"), s("4")), 3, style))
        assertEquals(0, SmallCardGrouping.discarded(listOf(s("1"), s("2"), s("3"), l("A")), 3, style))
        assertEquals(0, SmallCardGrouping.discarded(listOf(l("A"), l("B")), 2, style))
        assertEquals(0, SmallCardGrouping.discarded(emptyList<Any>(), 2, style))
    }

    fun unknownStylesOrAnInvalidGroupSizeAreNotGuessed() {
        assertNull(SmallCardGrouping.discarded(listOf(s("1"), Card("?", null)), 2, style))
        assertNull(SmallCardGrouping.discarded(listOf(s("1"), Any()), 2, style))
        assertNull(SmallCardGrouping.discarded(listOf(s("1")), 0, style))
    }

    fun regroupLetsAnIncompleteGroupFloatPastLargeCardsAndKeepsBothOrders() {
        val items = listOf(s("1"), l("A"), s("2"), s("3"), l("B"), s("4"))
        val regrouped = SmallCardGrouping.regroup(items, 2, style)
        assertEquals(listOf(l("A"), s("1"), s("2"), l("B"), s("3"), s("4")), regrouped)
        assertEquals(0, SmallCardGrouping.discarded(regrouped, 2, style))
        assertEquals(items.toSet(), regrouped.toSet())
        assertEquals(items.filter { (it as Card).small == true }, regrouped.filter { (it as Card).small == true })
        assertEquals(items.filter { (it as Card).small == false }, regrouped.filter { (it as Card).small == false })
    }

    fun regroupUsesTheHostGroupSize() {
        val items = listOf(s("1"), s("2"), l("A"), s("3"))
        assertEquals(listOf(l("A"), s("1"), s("2"), s("3")), SmallCardGrouping.regroup(items, 3, style))
    }

    fun completeGroupsUnknownStylesAndSingleColumnsKeepTheOriginalInstance() {
        val complete = listOf(s("1"), s("2"), l("A"), s("3"), s("4"))
        assertSame(complete, SmallCardGrouping.regroup(complete, 2, style))
        val unknown = listOf(s("1"), l("A"), Card("?", null), s("2"))
        assertSame(unknown, SmallCardGrouping.regroup(unknown, 2, style))
        val single = listOf(s("1"), l("A"), s("2"))
        assertSame(single, SmallCardGrouping.regroup(single, 1, style))
    }

    fun aTrailingIncompleteGroupStaysLastAndIsNotCarriedAcrossPages() {
        val items = listOf(s("1"), l("A"), s("2"), s("3"))
        val regrouped = SmallCardGrouping.regroup(items, 2, style)
        assertEquals(listOf(l("A"), s("1"), s("2"), s("3")), regrouped)
        assertEquals(1, SmallCardGrouping.discarded(regrouped, 2, style))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (SmallCardGroupingCases) -> Unit) {
            val s = SmallCardGroupingCases()
            block(s)
        }
        runIsolated { s -> s.discardMirrorsTheHostWindowRuleForPairsAndWiderGroups() }
        runIsolated { s -> s.unknownStylesOrAnInvalidGroupSizeAreNotGuessed() }
        runIsolated { s -> s.regroupLetsAnIncompleteGroupFloatPastLargeCardsAndKeepsBothOrders() }
        runIsolated { s -> s.regroupUsesTheHostGroupSize() }
        runIsolated { s -> s.completeGroupsUnknownStylesAndSingleColumnsKeepTheOriginalInstance() }
        runIsolated { s -> s.aTrailingIncompleteGroupStaysLastAndIsNotCarriedAcrossPages() }
    }
}

private class StoryPurifyFeatureInstallerCases {

    fun `filters only explicitly enabled exact story types`() {
        assertTrue(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(ad = true),
                removeAds = true,
                removeLive = false,
                removeGames = false
            )
        )
        assertFalse(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(game = true),
                removeAds = true,
                removeLive = false,
                removeGames = false
            )
        )
        assertTrue(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(live = true),
                removeAds = false,
                removeLive = true,
                removeGames = false
            )
        )
        assertTrue(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(bangumi = true),
                removeAds = false,
                removeLive = false,
                removeGames = false,
                removeBangumi = true
            )
        )
        assertTrue(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(course = true),
                removeAds = false,
                removeLive = false,
                removeGames = false,
                removeCourses = true
            )
        )
    }

    fun `filters exact short drama shopping and music signals independently`() {
        assertTrue(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(shortDrama = true),
                removeAds = false,
                removeLive = false,
                removeGames = false,
                removeShortDrama = true
            )
        )
        assertTrue(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(shopping = true),
                removeAds = false,
                removeLive = false,
                removeGames = false,
                removeShopping = true
            )
        )
        assertTrue(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(music = true),
                removeAds = false,
                removeLive = false,
                removeGames = false,
                removeMusic = true
            )
        )
        assertFalse(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(shortDrama = true, shopping = true, music = true),
                removeAds = false,
                removeLive = false,
                removeGames = false
            )
        )
    }

    fun `filters only the enabled exact season subtype`() {
        assertTrue(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(
                    seasonType = StoryPurifyFeatureInstaller.SEASON_TYPE_MOVIE
                ),
                removeAds = false,
                removeLive = false,
                removeGames = false,
                removeMovies = true
            )
        )
        assertFalse(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(
                    seasonType = StoryPurifyFeatureInstaller.SEASON_TYPE_DOCUMENTARY
                ),
                removeAds = false,
                removeLive = false,
                removeGames = false,
                removeMovies = true
            )
        )
        assertTrue(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(
                    seasonType = StoryPurifyFeatureInstaller.SEASON_TYPE_DOCUMENTARY
                ),
                removeAds = false,
                removeLive = false,
                removeGames = false,
                removeDocumentaries = true
            )
        )
        assertTrue(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(
                    seasonType = StoryPurifyFeatureInstaller.SEASON_TYPE_TV
                ),
                removeAds = false,
                removeLive = false,
                removeGames = false,
                removeTv = true
            )
        )
        assertTrue(
            StoryPurifyFeatureInstaller.shouldRemove(
                StoryPurifyFeatureInstaller.Signals(
                    seasonType = StoryPurifyFeatureInstaller.SEASON_TYPE_VARIETY
                ),
                removeAds = false,
                removeLive = false,
                removeGames = false,
                removeVariety = true
            )
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (StoryPurifyFeatureInstallerCases) -> Unit) {
            val s = StoryPurifyFeatureInstallerCases()
            block(s)
        }
        runIsolated { s -> s.`filters only explicitly enabled exact story types`() }
        runIsolated { s -> s.`filters exact short drama shopping and music signals independently`() }
        runIsolated { s -> s.`filters only the enabled exact season subtype`() }
    }
}

private class StoryQualityReparseGateCases {
    class Resource
    interface Listener {
        fun success(resource: Resource)
        fun onError()
    }
    interface UnknownListener {
        fun success(resource: Resource)
        fun onError()
        fun progress(percent: Int)
    }
    interface ThrowableListener {
        fun success(resource: Resource)
        fun onError(throwable: Throwable)
    }
    interface NoErrorListener { fun success(resource: Resource) }
    private val success = Listener::class.java.getMethod("success", Resource::class.java)
    private val item = PlayerQualitySessions.ItemKey(123L, 456L)
    private class Callback : Listener {
        var onSuccess: (Resource) -> Unit = {}
        var onFailure: () -> Unit = {}
        override fun success(resource: Resource) = onSuccess(resource)
        override fun onError() = onFailure()
    }
    private class Fixture {
        val owner = Any()
        val params = Any()
        val sessions = PlayerQualitySessions(80)
        val token: PlayerQualitySessions.Token
        val lease: StoryQualityReparseGate.Lease
        init {
            val item = PlayerQualitySessions.ItemKey(123L, 456L)
            sessions.start(owner, item)
            token = requireNotNull(sessions.reserve(owner, item, PlayerQualitySessions.Admission.ALLOW,
                PlayerQualitySessions.Operation.REPARSE))
            lease = StoryQualityReparseGate.Lease(owner, params, item, token)
        }
        fun permitted(lease: StoryQualityReparseGate.Lease): Boolean = lease.owner()?.let { owner ->
            lease.params() === params && sessions.valid(lease.token, owner, lease.item, PlayerQualitySessions.Admission.ALLOW)
        } == true
    }

    fun `success exposes weak lease only synchronously and duplicate terminal is ignored`() {
        val fixture = Fixture()
        var successes = 0
        var failures = 0
        lateinit var gate: StoryQualityReparseGate
        val callback = Callback().apply {
            onSuccess = {
                successes++
                assertSame(fixture.lease, gate.current())
                assertSame(it, gate.resource())
                assertSame(fixture.owner, gate.current()?.owner())
                assertSame(fixture.params, gate.current()?.params())
                assertSame(fixture.token, gate.current()?.token)
            }
        }
        gate = StoryQualityReparseGate(Listener::class.java, success, fixture::permitted, { failures++ })
        assertTrue(gate.available)
        val proxy = requireNotNull(gate.wrap(callback, fixture.lease)) as Listener
        assertNull(gate.current())
        proxy.success(Resource())
        assertNull(gate.current())
        assertNull(gate.resource())
        proxy.success(Resource())
        proxy.onError()
        assertEquals(1, successes)
        assertEquals(0, failures)
    }

    fun `manual fixed or auto discards only module reparse result`() {
        for (choice in PlayerQualitySessions.ManualChoice.entries) {
            val fixture = Fixture()
            var successes = 0
            var failures = 0
            val callback = Callback().apply { onSuccess = { successes++ } }
            val gate = StoryQualityReparseGate(Listener::class.java, success, fixture::permitted, { failures++ })
            val proxy = requireNotNull(gate.wrap(callback, fixture.lease)) as Listener
            fixture.sessions.manual(fixture.owner, item, choice)
            proxy.success(Resource())
            assertEquals(0, successes)
            assertEquals(1, failures)
            assertNull(gate.current())
            callback.success(Resource()) // 宿主原 listener 没有被全局改写。
            assertEquals(1, successes)
        }
    }

    fun `new generation and denied revision reject old listener result`() {
        for (invalidate in listOf<(Fixture) -> Unit>(
            { it.sessions.start(it.owner, item) },
            { it.sessions.target(it.owner, item, PlayerQualitySessions.Admission.DENY) },
            { it.sessions.close(it.owner) })) {
            val fixture = Fixture()
            var successes = 0
            val gate = StoryQualityReparseGate(Listener::class.java, success, fixture::permitted, {})
            val proxy = requireNotNull(gate.wrap(Callback().apply { onSuccess = { successes++ } }, fixture.lease)) as Listener
            invalidate(fixture)
            proxy.success(Resource())
            assertEquals(0, successes)
            assertNull(gate.current())
        }
    }

    fun `guard exception discards result and protects cleanup exception`() {
        val fixture = Fixture()
        var successes = 0
        var failures = 0
        val callback = Callback().apply { onSuccess = { successes++ } }
        val gate = StoryQualityReparseGate(Listener::class.java, success, { throw IllegalStateException("guard") }, {
            failures++
            throw IllegalStateException("cleanup")
        })
        val proxy = requireNotNull(gate.wrap(callback, fixture.lease)) as Listener
        proxy.success(Resource())
        proxy.success(Resource())
        assertEquals(0, successes)
        assertEquals(1, failures)
        assertNull(gate.current())
    }

    fun `current error preserves delegate logging and invokes protected cleanup once`() {
        val fixture = Fixture()
        var errors = 0
        var failures = 0
        var guards = 0
        lateinit var gate: StoryQualityReparseGate
        val callback = Callback().apply {
            onFailure = {
                errors++
                assertNull(gate.current())
            }
        }
        gate = StoryQualityReparseGate(Listener::class.java, success, { guards++; true }, {
            failures++
            throw IllegalStateException("cleanup")
        })
        val proxy = requireNotNull(gate.wrap(callback, fixture.lease)) as Listener
        proxy.onError()
        proxy.onError()
        proxy.success(Resource())
        assertEquals(1, errors)
        assertEquals(1, failures)
        assertEquals(1, guards)
    }

    fun `stale error cannot delegate into new state and cleanup remains protected once`() {
        val fixture = Fixture()
        var errors = 0
        var failures = 0
        val callback = Callback().apply { onFailure = { errors++ } }
        val gate = StoryQualityReparseGate(Listener::class.java, success, fixture::permitted, {
            failures++
            throw IllegalStateException("cleanup")
        })
        val proxy = requireNotNull(gate.wrap(callback, fixture.lease)) as Listener
        fixture.sessions.start(fixture.owner, item)
        proxy.onError()
        proxy.onError()
        proxy.success(Resource())
        assertEquals(0, errors)
        assertEquals(1, failures)
        assertNull(gate.current())
        assertNull(gate.resource())
    }

    fun `host delegate exception unwraps and restores current lease`() {
        val fixture = Fixture()
        val original = IllegalArgumentException("delegate")
        var failures = 0
        val callback = Callback().apply { onSuccess = { throw original } }
        val gate = StoryQualityReparseGate(Listener::class.java, success, { true }, { failures++ })
        val proxy = requireNotNull(gate.wrap(callback, fixture.lease)) as Listener
        try {
            proxy.success(Resource())
            fail("Original delegate exception must propagate")
        } catch (actual: IllegalArgumentException) {
            assertSame(original, actual)
        }
        assertNull(gate.current())
        assertEquals(1, failures)
        assertNull(gate.resource())
        proxy.success(Resource())
        assertEquals(1, failures)
    }

    fun `error delegate exception survives failing cleanup without invocation wrapper`() {
        val fixture = Fixture()
        val original = IllegalStateException("original")
        val callback = Callback().apply { onFailure = { throw original } }
        val gate = StoryQualityReparseGate(Listener::class.java, success, { true }, { throw AssertionError("cleanup") })
        val proxy = requireNotNull(gate.wrap(callback, fixture.lease)) as Listener
        try {
            proxy.onError()
            fail("Original delegate exception must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(original, actual)
        }
        assertNull(gate.current())
    }

    fun `nested successes restore parent lease even when child delegate throws`() {
        val first = Fixture()
        val second = Fixture()
        lateinit var gate: StoryQualityReparseGate
        val childError = IllegalStateException("child")
        val child = Callback().apply { onSuccess = {
            assertSame(second.lease, gate.current())
            assertSame(it, gate.resource())
            throw childError
        } }
        gate = StoryQualityReparseGate(Listener::class.java, success, { true }, {})
        val childProxy = requireNotNull(gate.wrap(child, second.lease)) as Listener
        val outer = Callback().apply {
            onSuccess = {
                assertSame(first.lease, gate.current())
                assertSame(it, gate.resource())
                try { childProxy.success(Resource()) } catch (actual: IllegalStateException) { assertSame(childError, actual) }
                assertSame(first.lease, gate.current())
                assertSame(it, gate.resource())
            }
        }
        (requireNotNull(gate.wrap(outer, first.lease)) as Listener).success(Resource())
        assertNull(gate.current())
        assertNull(gate.resource())
    }

    fun `object methods use proxy identity without touching delegate or guards`() {
        val fixture = Fixture()
        val callback = object : Listener {
            override fun success(resource: Resource) = Unit
            override fun onError() = Unit
            override fun equals(other: Any?): Boolean = throw AssertionError("delegate equals")
            override fun hashCode(): Int = throw AssertionError("delegate hashCode")
            override fun toString(): String = throw AssertionError("delegate toString")
        }
        val gate = StoryQualityReparseGate(Listener::class.java, success,
            { throw AssertionError("guard") }, { throw AssertionError("cleanup") })
        val proxy = requireNotNull(gate.wrap(callback, fixture.lease))
        val another = requireNotNull(gate.wrap(callback, fixture.lease))
        assertTrue(proxy == proxy)
        assertFalse(proxy == another)
        assertFalse(proxy == callback)
        assertEquals(System.identityHashCode(proxy), proxy.hashCode())
        assertEquals("StoryQualityReparseListener", proxy.toString())
        assertNull(gate.current())
    }

    fun `unsupported listener ABI and unrelated delegate are not wrapped`() {
        val fixture = Fixture()
        val unknown = object : UnknownListener {
            override fun success(resource: Resource) = Unit
            override fun onError() = Unit
            override fun progress(percent: Int) = Unit
        }
        val unknownGate = StoryQualityReparseGate(UnknownListener::class.java,
            UnknownListener::class.java.getMethod("success", Resource::class.java), { true }, {})
        assertFalse(unknownGate.available)
        assertNull(unknownGate.wrap(unknown, fixture.lease))
        val noError = object : NoErrorListener { override fun success(resource: Resource) = Unit }
        val noErrorGate = StoryQualityReparseGate(NoErrorListener::class.java,
            NoErrorListener::class.java.getMethod("success", Resource::class.java), { true }, {})
        assertNull(noErrorGate.wrap(noError, fixture.lease))
        assertNull(StoryQualityReparseGate(Listener::class.java, success, { true }, {}).wrap(Any(), fixture.lease))
        assertNull(StoryQualityReparseGate(Callback::class.java,
            Callback::class.java.getMethod("success", Resource::class.java), { true }, {}).wrap(Callback(), fixture.lease))
        val throwableGate = StoryQualityReparseGate(ThrowableListener::class.java,
            ThrowableListener::class.java.getMethod("success", Resource::class.java), { true }, {})
        assertFalse(throwableGate.available)
    }

    fun `concurrent success and error accept only one terminal callback`() {
        val fixture = Fixture()
        val callbacks = AtomicInteger()
        val failures = AtomicInteger()
        val callback = Callback().apply {
            onSuccess = { callbacks.incrementAndGet() }
            onFailure = { callbacks.incrementAndGet() }
        }
        val gate = StoryQualityReparseGate(Listener::class.java, success, { true }, { failures.incrementAndGet() })
        val proxy = requireNotNull(gate.wrap(callback, fixture.lease)) as Listener
        val start = CountDownLatch(1)
        val threads = List(8) { index -> Thread {
            start.await()
            if (index % 2 == 0) proxy.success(Resource()) else proxy.onError()
        }.also(Thread::start) }
        start.countDown()
        threads.forEach(Thread::join)
        assertEquals(1, callbacks.get())
        assertTrue(failures.get() in 0..1)
        assertNull(gate.current())
    }

    fun `checked original exception is unwrapped inside invocation handler`() {
        val fixture = Fixture()
        val original = Exception("checked")
        val callback = Callback().apply { onSuccess = { throw original } }
        val gate = StoryQualityReparseGate(Listener::class.java, success, { true }, {})
        val proxy = requireNotNull(gate.wrap(callback, fixture.lease))
        try {
            Proxy.getInvocationHandler(proxy).invoke(proxy, success, arrayOf(Resource()))
            fail("Original checked exception must propagate from handler")
        } catch (actual: Exception) {
            assertSame(original, actual)
        }
        assertNull(gate.current())
    }

    fun `reflection argument failure drops module callback and cleans up only once`() {
        val fixture = Fixture()
        var errors = 0
        var failures = 0
        val callback = Callback().apply { onFailure = { errors++ } }
        val gate = StoryQualityReparseGate(Listener::class.java, success, { true }, { failures++ })
        val proxy = requireNotNull(gate.wrap(callback, fixture.lease))
        Proxy.getInvocationHandler(proxy).invoke(proxy,
            Listener::class.java.getMethod("onError"), arrayOf("invalid ABI"))
        assertEquals(0, errors)
        assertEquals(1, failures)
        assertNull(gate.current())
    }

    fun `consumer reads latest params through lease instead of a captured seek position`() {
        class Params(var position: Long)
        val fixture = Fixture()
        val params = Params(10L)
        val lease = StoryQualityReparseGate.Lease(fixture.owner, params, item, fixture.token)
        lateinit var gate: StoryQualityReparseGate
        var consumed = -1L
        val callback = Callback().apply { onSuccess = { consumed = (gate.current()?.params() as Params).position } }
        gate = StoryQualityReparseGate(Listener::class.java, success, { true }, {})
        val proxy = requireNotNull(gate.wrap(callback, lease)) as Listener
        params.position = 99L
        proxy.success(Resource())
        assertEquals(99L, consumed)
        assertNull(gate.current())
    }

    fun verifyAllCases() {
        fun runIsolated(block: (StoryQualityReparseGateCases) -> Unit) {
            val s = StoryQualityReparseGateCases()
            block(s)
        }
        runIsolated { s -> s.`success exposes weak lease only synchronously and duplicate terminal is ignored`() }
        runIsolated { s -> s.`manual fixed or auto discards only module reparse result`() }
        runIsolated { s -> s.`new generation and denied revision reject old listener result`() }
        runIsolated { s -> s.`guard exception discards result and protects cleanup exception`() }
        runIsolated { s -> s.`current error preserves delegate logging and invokes protected cleanup once`() }
        runIsolated { s -> s.`stale error cannot delegate into new state and cleanup remains protected once`() }
        runIsolated { s -> s.`host delegate exception unwraps and restores current lease`() }
        runIsolated { s -> s.`error delegate exception survives failing cleanup without invocation wrapper`() }
        runIsolated { s -> s.`nested successes restore parent lease even when child delegate throws`() }
        runIsolated { s -> s.`object methods use proxy identity without touching delegate or guards`() }
        runIsolated { s -> s.`unsupported listener ABI and unrelated delegate are not wrapped`() }
        runIsolated { s -> s.`concurrent success and error accept only one terminal callback`() }
        runIsolated { s -> s.`checked original exception is unwrapped inside invocation handler`() }
        runIsolated { s -> s.`reflection argument failure drops module callback and cleans up only once`() }
        runIsolated { s -> s.`consumer reads latest params through lease instead of a captured seek position`() }
    }
}

private class StoryQualityRuntimeCases {
    private val item = ItemKey(123L, 456L)

    fun `binding alone and unknown owner never create playback target`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        val handler = Any()
        runtime.bind(owner, handler, null)
        assertSame(owner, runtime.ownerFor(handler))
        runtime.scoped(owner, item, Admission.ALLOW) {
            assertTrue(runtime.inStory())
            assertNull(runtime.target())
        }
        runtime.scoped(Any(), item, Admission.ALLOW) {
            assertTrue(runtime.inStory())
            assertNull(runtime.target())
        }
        assertFalse(runtime.inStory())
    }

    fun `scope target and parameters are available only during current call`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        val params = Any()
        runtime.start(owner, item)
        val scope = runtime.enter(owner, item, Admission.ALLOW, params)
        assertEquals(80, runtime.target())
        assertSame(params, runtime.params())
        assertTrue(runtime.leave(scope))
        assertNull(scope.owner)
        assertNull(scope.parameters)
        assertNull(scope.stackIdentity)
        assertFalse(runtime.inStory())
        assertNull(runtime.target())
        assertNull(runtime.params())
        assertFalse(runtime.leave(scope))
    }

    fun `unknown nested scope suppresses global fallback until its own return`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        runtime.start(owner, item)
        runtime.scoped(owner, item, Admission.ALLOW) {
            assertEquals(80, runtime.target())
            runtime.scoped(null, null, Admission.UNKNOWN) {
                assertTrue(runtime.inStory())
                assertNull(runtime.target())
            }
            assertEquals(80, runtime.target())
        }
        assertFalse(runtime.inStory())
    }

    fun `original exception and suspended return both remove synchronous scope`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        runtime.start(owner, item)
        val failure = IllegalStateException("fixture")
        try {
            runtime.scoped<Unit>(owner, item, Admission.ALLOW) { throw failure }
            fail("Original exception must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertFalse(runtime.inStory())
        assertSame(COROUTINE_SUSPENDED, runtime.scoped(owner, item, Admission.ALLOW) { COROUTINE_SUSPENDED })
        assertFalse(runtime.inStory())
    }

    fun `nested owner scopes restore previous frame without sharing manual state`() {
        val runtime = StoryQualityRuntime(80)
        val first = Any()
        val second = Any()
        runtime.start(first, item)
        runtime.start(second, item)
        runtime.scoped(first, item, Admission.ALLOW) {
            runtime.scoped(second, item, Admission.ALLOW) {
                assertTrue(runtime.manual(second, item, ManualChoice.AUTO))
                assertNull(runtime.target())
                assertTrue(runtime.inStory())
            }
            assertEquals(80, runtime.target())
        }
    }

    fun `manual auto and fixed revoke pending result before asynchronous original starts`() {
        for (choice in ManualChoice.entries) {
            val runtime = StoryQualityRuntime(80)
            val owner = Any()
            runtime.start(owner, item)
            val pending = requireNotNull(runtime.sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
            runtime.scoped(owner, item, Admission.ALLOW) {
                assertEquals(80, runtime.target())
                assertTrue(runtime.manual(owner, item, choice))
                val originalStarted = {
                    assertFalse(runtime.sessions.valid(pending, owner, item, Admission.ALLOW))
                    assertNull(runtime.target())
                    assertTrue(runtime.inStory())
                }
                originalStarted()
            }
            assertFalse(runtime.sessions.finish(pending, owner, item, Admission.ALLOW, Outcome.COMPLETED))
        }
    }

    fun `same item new generation cannot borrow old synchronous frame`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        runtime.start(owner, item)
        val old = runtime.enter(owner, item, Admission.ALLOW)
        val pending = requireNotNull(runtime.sessions.reserve(owner, item, Admission.ALLOW, Operation.REQUEST_TARGET))
        runtime.start(owner, item)
        assertNull(runtime.target())
        assertTrue(runtime.inStory())
        runtime.scoped(owner, item, Admission.ALLOW) { assertEquals(80, runtime.target()) }
        assertNull(runtime.target())
        assertFalse(runtime.sessions.valid(pending, owner, item, Admission.ALLOW))
        assertTrue(runtime.leave(old))
        assertFalse(runtime.inStory())
    }

    fun `nested denied admission does not revive older allowed frame`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        runtime.start(owner, item)
        runtime.scoped(owner, item, Admission.ALLOW) {
            assertEquals(80, runtime.target())
            runtime.scoped(owner, item, Admission.DENY) { assertNull(runtime.target()) }
            assertNull(runtime.target())
            runtime.scoped(owner, item, Admission.ALLOW) { assertEquals(80, runtime.target()) }
            assertNull(runtime.target())
        }
    }

    fun `entering blocked branch revokes pending work even without a target read`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        runtime.start(owner, item)
        val pending = requireNotNull(runtime.sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
        val scope = runtime.enter(owner, item, Admission.DENY)
        assertFalse(runtime.sessions.valid(pending, owner, item, Admission.ALLOW))
        assertTrue(runtime.leave(scope))
    }

    fun `closed owner loses lookup and frame stays Story without target`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        val handler = Any()
        val provider = Any()
        runtime.bind(owner, handler, provider)
        runtime.start(owner, item)
        runtime.scoped(owner, item, Admission.ALLOW) {
            runtime.close(owner)
            assertNull(runtime.ownerFor(owner))
            assertNull(runtime.ownerFor(handler))
            assertNull(runtime.ownerFor(provider))
            assertTrue(runtime.inStory())
            assertNull(runtime.target())
        }
    }

    fun `component identity collision returns missing without conflating owners`() {
        class EqualOwner {
            override fun equals(other: Any?) = other is EqualOwner
            override fun hashCode() = 1
        }
        val runtime = StoryQualityRuntime(80)
        val first = EqualOwner()
        val second = EqualOwner()
        val shared = Any()
        val firstProvider = Any()
        val secondProvider = Any()
        runtime.bind(first, shared, firstProvider)
        runtime.bind(second, shared, secondProvider)
        assertNull(runtime.ownerFor(shared))
        assertSame(first, runtime.ownerFor(firstProvider))
        assertSame(second, runtime.ownerFor(secondProvider))
        runtime.close(first)
        assertSame(second, runtime.ownerFor(shared))
    }

    fun `binding capacity invalidates evicted owner and pending session token`() {
        val runtime = StoryQualityRuntime(80)
        val owners = List(17) { Any() }
        runtime.start(owners.first(), item)
        val pending = requireNotNull(runtime.sessions.reserve(owners.first(), item, Admission.ALLOW, Operation.RESOURCE_SELECT))
        val oldScope = runtime.enter(owners.first(), item, Admission.ALLOW)
        owners.drop(1).forEach { runtime.start(it, item) }
        assertNull(runtime.ownerFor(owners.first()))
        assertNull(runtime.target())
        assertTrue(runtime.inStory())
        assertFalse(runtime.sessions.valid(pending, owners.first(), item, Admission.ALLOW))
        assertSame(owners.last(), runtime.ownerFor(owners.last()))
        assertTrue(runtime.leave(oldScope))
    }

    fun `overflow keeps Story suppression and duplicate leave cannot consume another frame`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        runtime.start(owner, item)
        val scopes = List(16) { runtime.enter(owner, item, Admission.ALLOW, Any()) }
        assertEquals(80, runtime.target())
        val firstOverflow = runtime.enter(owner, item, Admission.ALLOW, Any())
        val secondOverflow = runtime.enter(owner, item, Admission.ALLOW, Any())
        assertNull(firstOverflow.owner)
        assertNull(firstOverflow.parameters)
        assertTrue(runtime.inStory())
        assertNull(runtime.target())
        assertNull(runtime.params())
        assertTrue(runtime.leave(firstOverflow))
        assertFalse(runtime.leave(firstOverflow))
        assertNull(runtime.target())
        assertTrue(runtime.leave(secondOverflow))
        assertEquals(80, runtime.target())
        scopes.asReversed().forEach { assertTrue(runtime.leave(it)) }
        assertFalse(runtime.inStory())
    }

    fun `out of order and old leaves never clear a newer current frame`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        runtime.start(owner, item)
        val outer = runtime.enter(owner, item, Admission.ALLOW)
        val params = Any()
        val inner = runtime.enter(owner, item, Admission.ALLOW, params)
        assertTrue(runtime.leave(outer))
        assertFalse(runtime.leave(outer))
        assertSame(params, runtime.params())
        assertEquals(80, runtime.target())
        assertTrue(runtime.leave(inner))
        val fresh = runtime.enter(owner, item, Admission.ALLOW)
        assertFalse(runtime.leave(outer))
        assertTrue(runtime.inStory())
        assertTrue(runtime.leave(fresh))
    }

    fun `another thread cannot close original frame or inherit Story context`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        runtime.start(owner, item)
        val scope = runtime.enter(owner, item, Admission.ALLOW)
        val untouched = AtomicBoolean(false)
        Thread {
            untouched.set(!runtime.inStory() && runtime.target() == null && !runtime.leave(scope))
        }.also { it.start(); it.join() }
        assertTrue(untouched.get())
        assertEquals(80, runtime.target())
        assertTrue(runtime.leave(scope))
    }

    fun `request completion keeps target but repeated reparse remains bounded`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        runtime.start(owner, item)
        runtime.scoped(owner, item, Admission.ALLOW) {
            val request = requireNotNull(runtime.sessions.reserve(owner, item, Admission.ALLOW, Operation.REQUEST_TARGET))
            assertTrue(runtime.sessions.finish(request, owner, item, Admission.ALLOW, Outcome.COMPLETED))
            assertEquals(80, runtime.target())
            val reparse = requireNotNull(runtime.sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
            assertTrue(runtime.sessions.finish(reparse, owner, item, Admission.ALLOW, Outcome.FAILED))
            assertNull(runtime.sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
            assertEquals(80, runtime.target())
        }
    }

    fun `follow host setting never produces a Story override`() {
        val runtime = StoryQualityRuntime(0)
        val owner = Any()
        assertNull(runtime.start(owner, item))
        runtime.scoped(owner, item, Admission.ALLOW) { assertNull(runtime.target()) }
        assertFalse(runtime.inStory())
    }

    fun `duplicate handler selection preserves manual choice and settled playback`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        val first = runtime.select(owner, item, 0)
        assertTrue(runtime.manual(owner, item, ManualChoice.AUTO))
        assertEquals(first, runtime.select(owner, item, 0))
        runtime.scoped(owner, item, Admission.ALLOW) { assertNull(runtime.target()) }
        val second = runtime.select(owner, item, 1)
        assertTrue(first != second)
        val render = requireNotNull(runtime.sessions.reserve(owner, item, Admission.ALLOW, Operation.PLAYBACK_SETTLE))
        assertTrue(runtime.sessions.settle(render, owner, item, Admission.ALLOW))
        assertEquals(second, runtime.select(owner, item, 1))
        runtime.scoped(owner, item, Admission.ALLOW) { assertNull(runtime.target()) }
    }

    fun `same ID different index and different item same index start new generations`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        val first = runtime.select(owner, item, 0)
        val pending = requireNotNull(runtime.sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
        val second = runtime.select(owner, item, 1)
        assertTrue(first != second)
        assertFalse(runtime.sessions.valid(pending, owner, item, Admission.ALLOW))
        runtime.scoped(owner, item, Admission.ALLOW) { assertEquals(80, runtime.target()) }
        val another = ItemKey(123L, 789L)
        assertTrue(second != runtime.select(owner, another, 1))
        runtime.scoped(owner, another, Admission.ALLOW) { assertEquals(80, runtime.target()) }
    }

    fun `invalid selection closes mapping and pending work`() {
        val runtime = StoryQualityRuntime(80)
        val owner = Any()
        val handler = Any()
        runtime.bind(owner, handler, null)
        runtime.select(owner, item, 0)
        val pending = requireNotNull(runtime.sessions.reserve(owner, item, Admission.ALLOW, Operation.REPARSE))
        assertNull(runtime.select(owner, item, -1))
        assertNull(runtime.ownerFor(handler))
        assertFalse(runtime.sessions.valid(pending, owner, item, Admission.ALLOW))
        assertNull(runtime.select(owner, null, 0))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (StoryQualityRuntimeCases) -> Unit) {
            val s = StoryQualityRuntimeCases()
            block(s)
        }
        runIsolated { s -> s.`binding alone and unknown owner never create playback target`() }
        runIsolated { s -> s.`scope target and parameters are available only during current call`() }
        runIsolated { s -> s.`unknown nested scope suppresses global fallback until its own return`() }
        runIsolated { s -> s.`original exception and suspended return both remove synchronous scope`() }
        runIsolated { s -> s.`nested owner scopes restore previous frame without sharing manual state`() }
        runIsolated { s -> s.`manual auto and fixed revoke pending result before asynchronous original starts`() }
        runIsolated { s -> s.`same item new generation cannot borrow old synchronous frame`() }
        runIsolated { s -> s.`nested denied admission does not revive older allowed frame`() }
        runIsolated { s -> s.`entering blocked branch revokes pending work even without a target read`() }
        runIsolated { s -> s.`closed owner loses lookup and frame stays Story without target`() }
        runIsolated { s -> s.`component identity collision returns missing without conflating owners`() }
        runIsolated { s -> s.`binding capacity invalidates evicted owner and pending session token`() }
        runIsolated { s -> s.`overflow keeps Story suppression and duplicate leave cannot consume another frame`() }
        runIsolated { s -> s.`out of order and old leaves never clear a newer current frame`() }
        runIsolated { s -> s.`another thread cannot close original frame or inherit Story context`() }
        runIsolated { s -> s.`request completion keeps target but repeated reparse remains bounded`() }
        runIsolated { s -> s.`follow host setting never produces a Story override`() }
        runIsolated { s -> s.`duplicate handler selection preserves manual choice and settled playback`() }
        runIsolated { s -> s.`same ID different index and different item same index start new generations`() }
        runIsolated { s -> s.`invalid selection closes mapping and pending work`() }
    }
}

private class SubtitlePackagePatcherCases {
    private val runtime = "/* Module-owned style adapter */ globalThis.__bilSubtitleStyle={path:__BIL_STYLE_FILE__,apply:function(){},color:function(r,k,v){return v}};"
    private val worker = "/* ${"bounded-renderer-fixture ".repeat(60)} */" +
        "var S=class{rasterize(){this._canvas.strokeColor=S.STROKE_COLOR;this._canvas.fillColor=S.BG_COLOR}};" +
        "var P=class{init(){this._mainRenderer={};this._viceRenderer={};}" +
        "applyRendererConfig(a,b,c){a.factor=this.rendererFactor(c)}handleOnVideoFrameChanged(){}" +
        "handleOnSubtitleConfigChanged(){this.applyRendererConfig(null,false,1)}" +
        "log(){return 'SubtitleFont: <SubtitleRenderer>'}};"

    private fun archive(file: File, version: String = "3.12.0") {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, body) in listOf("info.json" to "{\"name\":\"danmaku-flame-master\",\"version\":\"$version\",\"commit\":\"official\"}",
                SubtitleScriptAdapter.WORKER to worker, "res/other.txt" to "untouched")) {
                zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry()
            }
        }
    }

    fun `private copy is reusable while the official bytes and unrelated resources are preserved`() {
        val root = Files.createTempDirectory("subtitle-patch-test").toFile()
        try {
            val source = File(root, "official.zip"); archive(source)
            val original = source.readBytes()
            val patcher = SubtitlePackagePatcher(runtime)
            val patched = requireNotNull(patcher.prepare(source, File(root, "style.json"), File(root, "private")))
            assertNotEquals(source.canonicalPath, patched.canonicalPath)
            assertArrayEquals(original, source.readBytes())
            assertEquals(patched, patcher.cached(source, File(root, "style.json")))
            assertEquals(patched, SubtitlePackagePatcher(runtime).prepare(source, File(root, "style.json"), File(root, "private")))
            ZipFile(patched).use { zip ->
                assertEquals("untouched", zip.getInputStream(zip.getEntry("res/other.txt")).bufferedReader().readText())
                val info = zip.getInputStream(zip.getEntry("info.json")).bufferedReader().readText()
                assertTrue(info.contains("innocent_subtitle_adapter")); assertFalse(info.contains("\"commit\":\"official\""))
                assertTrue(zip.getInputStream(zip.getEntry(SubtitleScriptAdapter.WORKER)).bufferedReader().readText().contains("globalThis.__bilSubtitleStyle.apply(a)"))
            }
            assertEquals(1, File(root, "private").listFiles()!!.count { it.extension == "zip" })
            val style = File(root, "style.json").apply { writeText(PlayerSubtitleStyle(sizePercent = 170).encode()) }
            assertNull(patcher.cached(source, style))
            val updated = requireNotNull(patcher.prepare(source, style, File(root, "private")))
            assertNotEquals(patched, updated)
            ZipFile(updated).use { zip ->
                val json = zip.getInputStream(zip.getEntry("res/${SubtitleStylePayload.CONFIG}")).reader().readText()
                assertEquals(170, org.json.JSONObject(json).getInt("size"))
            }
        } finally { root.deleteRecursively() }
    }

    fun `unknown versions or damaged archives do not publish a replacement`() {
        val root = Files.createTempDirectory("subtitle-patch-test").toFile()
        try {
            val source = File(root, "official.zip"); archive(source, "future-version")
            val output = File(root, "private")
            assertNull(SubtitlePackagePatcher(runtime).prepare(source, File(root, "style.json"), output))
            source.writeText("corrupt")
            assertNull(SubtitlePackagePatcher(runtime).prepare(source, File(root, "style.json"), output))
            assertFalse(output.exists())
        } finally { root.deleteRecursively() }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (SubtitlePackagePatcherCases) -> Unit) {
            val s = SubtitlePackagePatcherCases()
            block(s)
        }
        runIsolated { s -> s.`private copy is reusable while the official bytes and unrelated resources are preserved`() }
        runIsolated { s -> s.`unknown versions or damaged archives do not publish a replacement`() }
    }
}

private class SubtitleScriptAdapterCases {
    private val runtime = "/* Module adapter test */ globalThis.__bilSubtitleStyle={file:__BIL_STYLE_FILE__,apply:function(){},color:function(r,k,f){return f}};"
    private val worker = """
        /* ${"verified-scoped-fixture ".repeat(55)} */
        var unrelated={fillColor:1};
        var Raster=class{render(){this._canvas.strokeColor=Raster.STROKE_COLOR;this._canvas.fillColor=Raster.BG_COLOR}};
        var Provider=class{
          init(){this._mainRenderer={};this._viceRenderer={};}
          applyRendererConfig(renderer,vice,factor){renderer.factor=this.rendererFactor(factor)}handleOnVideoFrameChanged(){}
          handleOnSubtitleConfigChanged(){this.applyRendererConfig(null,false,1)}
          log(){return 'SubtitleFont: <SubtitleRenderer>';}
        };
    """.trimIndent()

    fun `only subtitle renderer style assignments change and minified names may differ`() {
        val patched = requireNotNull(SubtitleScriptAdapter.patch(worker, runtime, "/private/a\"b/style.json"))
        assertTrue(patched.contains("var unrelated={fillColor:1}"))
        assertTrue(patched.contains("globalThis.__bilSubtitleStyle.apply(renderer)"))
        assertTrue(patched.contains("color(this,'background',Raster.BG_COLOR)"))
        assertTrue(patched.contains("color(this,'outline',Raster.STROKE_COLOR)"))
        assertTrue(patched.contains("a\\\"b"))
        assertNotNull(SubtitleScriptAdapter.patch(Regex("\\brenderer\\b").replace(worker, "a").replace("Raster", "z"), runtime, "/style.json"))
    }

    fun `missing duplicate or mismatched anchors leave the official script untouched`() {
        assertNull(SubtitleScriptAdapter.patch(worker.replace("SubtitleFont: <SubtitleRenderer>", "unknown"), runtime, "/style.json"))
        assertNull(SubtitleScriptAdapter.patch(worker + worker, runtime, "/style.json"))
        assertNull(SubtitleScriptAdapter.patch(worker.replace("Raster.BG_COLOR", "Other.BG_COLOR"), runtime, "/style.json"))
        assertNull(SubtitleScriptAdapter.patch(worker, runtime + "__BIL_STYLE_FILE__", "/style.json"))
        val patched = requireNotNull(SubtitleScriptAdapter.patch(worker, runtime, "/style.json"))
        assertNull(SubtitleScriptAdapter.patch(patched, runtime, "/style.json"))
    }

    fun `archive paths must remain inside the private extraction directory`() {
        for (path in listOf("../index.js", "/index.js", "res/../../outside", "C:/index.js", "res\\index.js", "res//index.js", "res/./index.js", "res/\u0000index.js")) {
            assertFalse(path, SubtitlePackagePatcher.safeEntry(path))
        }
        assertTrue(SubtitlePackagePatcher.safeEntry(SubtitleScriptAdapter.WORKER))
        assertTrue(SubtitlePackagePatcher.safeEntry("res/subtitle/"))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (SubtitleScriptAdapterCases) -> Unit) {
            val s = SubtitleScriptAdapterCases()
            block(s)
        }
        runIsolated { s -> s.`only subtitle renderer style assignments change and minified names may differ`() }
        runIsolated { s -> s.`missing duplicate or mismatched anchors leave the official script untouched`() }
        runIsolated { s -> s.`archive paths must remain inside the private extraction directory`() }
    }
}
