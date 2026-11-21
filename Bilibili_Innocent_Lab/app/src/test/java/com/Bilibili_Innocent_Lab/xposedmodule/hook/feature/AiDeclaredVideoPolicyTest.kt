package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import java.net.URLEncoder
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AiDeclaredVideoPolicyTest {

    private val policy = AiDeclaredVideoPolicy

    fun declarationTextMatchesTheCapturedAiStatementAndItsVariants() {
        // 2026-09-25 抓包里的原文。
        assertTrue(policy.isAiDeclaration("含AI生成内容"))
        assertTrue(policy.isAiDeclaration("含 AI 生成内容"))
        assertTrue(policy.isAiDeclaration("该视频使用人工智能合成技术"))
        assertTrue(policy.isAiDeclaration("作者声明：AI辅助生成"))
        assertTrue(policy.isAiDeclaration("AIGC 内容"))
    }

    fun otherStatementsAndBlankTitlesAreNeverTreatedAsAiDeclarations() {
        assertFalse(policy.isAiDeclaration(null))
        assertFalse(policy.isAiDeclaration(""))
        assertFalse(policy.isAiDeclaration("个人观点，仅供参考"))
        assertFalse(policy.isAiDeclaration("该视频含有危险行为，请勿模仿"))
        // 只是话题里有 AI，没有"生成/合成"这类动词。
        assertFalse(policy.isAiDeclaration("AI 话题讨论"))
    }

    fun creationTagsMatchOnlyAWholeAigcSegment() {
        assertTrue(policy.creationTagsDeclareAigc("人工智能-aigc,音乐-aigc"))
        assertTrue(policy.creationTagsDeclareAigc("人工智能-aigc"))
        assertTrue(policy.creationTagsDeclareAigc(" 音乐 - AIGC "))
        assertFalse(policy.creationTagsDeclareAigc("知识-解读评析,科技数码-杂谈"))
        assertFalse(policy.creationTagsDeclareAigc("人工智能-杂谈"))
        assertFalse(policy.creationTagsDeclareAigc("人工智能-aigc音乐"))
        assertFalse(policy.creationTagsDeclareAigc(""))
        assertFalse(policy.creationTagsDeclareAigc(null))
    }

    fun feedUriIsDecodedOnlyThroughPlayerPreloadAndQnFeatureCreationTags() {
        assertTrue(policy.feedUriDeclaresAigc(feedUri(creationTags = "人工智能-aigc,音乐-aigc")))
        assertFalse(policy.feedUriDeclaresAigc(feedUri(creationTags = "知识-教学/教程")))
        // 抓包里的反例：ai_tags 是分类器输出，AI 话题视频也有；creation_tags 缺失就不算。
        assertFalse(policy.feedUriDeclaresAigc(feedUri(creationTags = null, aiTags = "人工智能-aigc-其他ai生成内容")))
        // aigc 只出现在标题里，不在特征串的 creation_tags 上。
        assertFalse(policy.feedUriDeclaresAigc(feedUri(creationTags = "", title = "aigc 教程")))
    }

    fun feedUriFailsOpenOnAnythingMalformedOrMissing() {
        assertFalse(policy.feedUriDeclaresAigc(null))
        assertFalse(policy.feedUriDeclaresAigc(""))
        assertFalse(policy.feedUriDeclaresAigc("bilibili://video/1?cid=2"))
        assertFalse(policy.feedUriDeclaresAigc("bilibili://video/1?player_preload=%7Baigc"))
        assertFalse(policy.feedUriDeclaresAigc("bilibili://video/1?other=aigc"))
        val broken = "bilibili://video/1?player_preload=" +
            URLEncoder.encode("{\"qn_feature\":\"{not json aigc\"}", "UTF-8")
        assertFalse(policy.feedUriDeclaresAigc(broken))
    }

    fun preGateOnlyPassesWhenAigcFollowsTheCreationTagsKey() {
        // AI 话题卡：aigc 只在 ai_tags（排在 creation_tags 前面）→ 预判即挡，不做整段解码。
        assertFalse(policy.mayDeclareAigc(feedUri(creationTags = "知识-杂谈", aiTags = "人工智能-aigc-其他")))
        assertTrue(policy.mayDeclareAigc(feedUri(creationTags = "人工智能-aigc")))
        assertTrue(policy.mayDeclareAigc(feedUri(creationTags = "人工智能-AIGC")))
        assertTrue(policy.feedUriDeclaresAigc(feedUri(creationTags = "人工智能-AIGC")))
        assertFalse(policy.mayDeclareAigc("bilibili://video/1?x=aigc"))
        // 键后窗口之外的 aigc 不算。
        val far = "creation_tags" + "x".repeat(2000) + "aigc"
        assertFalse(policy.mayDeclareAigc(far))
    }

    fun repeatedJudgementsOfTheSameUriAreStable() {
        val uri = feedUri(creationTags = "人工智能-aigc")
        repeat(3) { assertTrue(policy.feedUriDeclaresAigc(uri)) }
        val miss = feedUri(creationTags = "音乐-aigc音乐")
        repeat(3) { assertFalse(policy.feedUriDeclaresAigc(miss)) }
    }

    fun onlyTheHistoryPageSpmidIsPassive() {
        assertTrue(policy.isPassiveRequest("main.my-history.recommend.0"))
        assertFalse(policy.isPassiveRequest("main.ugc-video-detail.0.0"))
        assertFalse(policy.isPassiveRequest(""))
        assertFalse(policy.isPassiveRequest(null))
    }

    fun queryParameterReadsTheExactNameWithoutDecoding() {
        val uri = "bilibili://video/1?cid=2&player_preload=%7B%7D&player_preload_x=9"
        assertEquals("%7B%7D", policy.queryParameter(uri, "player_preload"))
        assertEquals("2", policy.queryParameter(uri, "cid"))
        assertNull(policy.queryParameter(uri, "missing"))
        assertNull(policy.queryParameter("bilibili://video/1", "cid"))
    }

    fun aidFromParamAcceptsOnlyPositiveDecimals() {
        assertEquals(117184995006501L, policy.aidFromParam("117184995006501"))
        assertNull(policy.aidFromParam("0"))
        assertNull(policy.aidFromParam("-3"))
        assertNull(policy.aidFromParam("ep123"))
        assertNull(policy.aidFromParam(null))
    }

    fun replacementSkipsSelfKnownAiSameUploaderBlockedUploaderAndNonVideoRoutes() {
        fun card(aid: Long, uri: String? = "bilibili://video/$aid", mid: Long = 7, name: String = "up", video: Boolean = true) =
            AiDeclaredVideoPolicy.RelateCandidate(video, aid, uri, name, mid)
        fun ok(candidate: AiDeclaredVideoPolicy.RelateCandidate, known: Set<Long> = emptySet(), blocked: Set<String> = emptySet()) =
            policy.isReplacementCandidate(candidate, currentAid = 100, currentOwnerMid = 1,
                isKnownAi = { it in known }, isBlockedAuthor = { name, mid -> name in blocked || mid in blocked })

        assertTrue(ok(card(200)))
        assertFalse(ok(card(100)))
        assertFalse(ok(card(0)))
        assertFalse(ok(card(200, video = false)))
        assertFalse(ok(card(200, uri = "https://www.bilibili.com/bangumi/play/ep1")))
        assertFalse(ok(card(200, uri = null)))
        assertFalse(ok(card(200), known = setOf(200)))
        assertFalse(ok(card(200, mid = 1)))
        assertFalse(ok(card(200, name = "blocked"), blocked = setOf("blocked")))
        assertFalse(ok(card(200, mid = 9), blocked = setOf("9")))
    }

    /** 按抓包形状拼一条首页卡片 uri：`player_preload` 是 URL 编码的 JSON，`qn_feature` 是其中的 JSON 字符串。 */
    private fun feedUri(creationTags: String?, aiTags: String = "音乐-内容看点-演唱翻唱", title: String = "t"): String {
        // 手工拼串：org.json 不保证键序，而服务端下发的顺序是 title → ai_tags → creation_tags →
        // general_tags（2026-09-25 抓包），预判正是依赖 ai_tags 排在 creation_tags 之前。
        val feature = buildString {
            append("{\"title\":").append(JSONObject.quote(title))
            append(",\"ai_tags\":").append(JSONObject.quote(aiTags))
            creationTags?.let { append(",\"creation_tags\":").append(JSONObject.quote(it)) }
            append(",\"general_tags\":\"\",\"ip_tags\":\"\"}")
        }
        val preload = JSONObject().apply {
            put("expire_time", 1790346275)
            put("cid", 41597143842L)
            put("qn_feature", feature.toString())
        }
        return "bilibili://video/117184995006501?cid=41597143842&player_height=1080" +
            "&player_preload=" + URLEncoder.encode(preload.toString(), "UTF-8") + "&trackid=x"
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AiDeclaredVideoPolicyTest) -> Unit) {
            val s = AiDeclaredVideoPolicyTest()
            block(s)
        }
        runIsolated { s -> s.declarationTextMatchesTheCapturedAiStatementAndItsVariants() }
        runIsolated { s -> s.otherStatementsAndBlankTitlesAreNeverTreatedAsAiDeclarations() }
        runIsolated { s -> s.creationTagsMatchOnlyAWholeAigcSegment() }
        runIsolated { s -> s.feedUriIsDecodedOnlyThroughPlayerPreloadAndQnFeatureCreationTags() }
        runIsolated { s -> s.feedUriFailsOpenOnAnythingMalformedOrMissing() }
        runIsolated { s -> s.preGateOnlyPassesWhenAigcFollowsTheCreationTagsKey() }
        runIsolated { s -> s.repeatedJudgementsOfTheSameUriAreStable() }
        runIsolated { s -> s.onlyTheHistoryPageSpmidIsPassive() }
        runIsolated { s -> s.queryParameterReadsTheExactNameWithoutDecoding() }
        runIsolated { s -> s.aidFromParamAcceptsOnlyPositiveDecimals() }
        runIsolated { s -> s.replacementSkipsSelfKnownAiSameUploaderBlockedUploaderAndNonVideoRoutes() }
    
        AiDeclaredVideoRegistryCases().verifyAllCases()
        AiPlaylistSkipperCases().verifyAllCases()
        AiRelatePrecheckerCases().verifyAllCases()
        AuthorRuleSetCases().verifyAllCases()
        BlockComponentLibraryPolicyCases().verifyAllCases()
        BrandSelectionInitializationCases().verifyAllCases()
        BrandSplash917BehaviorCases().verifyAllCases()
    }
}

private class AiDeclaredVideoRegistryCases {

    fun reset() = AiDeclaredVideoRegistry.resetForTest()

    fun addIsIdempotentAndIgnoresNonPositiveAids() {
        assertTrue(AiDeclaredVideoRegistry.add(10))
        assertFalse(AiDeclaredVideoRegistry.add(10))
        assertFalse(AiDeclaredVideoRegistry.add(0))
        assertFalse(AiDeclaredVideoRegistry.add(-1))
        assertFalse(AiDeclaredVideoRegistry.add(null))
        assertTrue(AiDeclaredVideoRegistry.contains(10))
        assertFalse(AiDeclaredVideoRegistry.contains(11))
        assertFalse(AiDeclaredVideoRegistry.contains(null))
    }

    fun boundedKeepsTheNewestEntries() {
        val values = (1L..(AiDeclaredVideoRegistry.MAX_AIDS + 5L)).toList()
        val bounded = AiDeclaredVideoRegistry.bounded(values)
        assertEquals(AiDeclaredVideoRegistry.MAX_AIDS, bounded.size)
        assertFalse(1L in bounded)
        assertTrue(values.last() in bounded)
    }

    fun decodeDropsOnlyTheBrokenLines() {
        assertEquals(listOf(1L, 3L), AiDeclaredVideoRegistry.decode("1\nabc\n-2\n0\n 3 \n"))
        assertEquals(emptyList<Long>(), AiDeclaredVideoRegistry.decode(null))
        val encoded = AiDeclaredVideoRegistry.encode(listOf(5L, 6L))
        assertEquals(listOf(5L, 6L), AiDeclaredVideoRegistry.decode(encoded))
    }

    fun redirectGuardAllowsABoundedChainPerWindow() {
        var now = 0L
        val guard = AiRedirectGuard(maxRedirects = 3, windowMillis = 30_000L, clock = { now })
        assertTrue(guard.tryAcquire())
        assertTrue(guard.tryAcquire())
        assertTrue(guard.tryAcquire())
        // 第四次落在窗口内：连锁保险生效，改走提示页。
        assertFalse(guard.tryAcquire())
        now = 30_001L
        assertTrue(guard.tryAcquire())
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AiDeclaredVideoRegistryCases) -> Unit) {
            val s = AiDeclaredVideoRegistryCases()
            s.reset()
            try {
                block(s)
            } finally {
                s.reset()
            }
        }
        runIsolated { s -> s.addIsIdempotentAndIgnoresNonPositiveAids() }
        runIsolated { s -> s.boundedKeepsTheNewestEntries() }
        runIsolated { s -> s.decodeDropsOnlyTheBrokenLines() }
        runIsolated { s -> s.redirectGuardAllowsABoundedChainPerWindow() }
    }
}

private class AiPlaylistSkipperCases {

    /** 8.89.0 的属性表顺序：attr 第 0 位、id 第 8 位（`MultiTypeMedia_JsonDescriptor.createProperties`）。 */
    private val keys = listOf("attr", "bv_id", "cnt_info", "coin", "copy_right", "cover", "duration",
        "fav_state", "id", "index")

    fun slotsAreFoundByJsonKeyNameNotByPosition() {
        assertArrayEquals(intArrayOf(0, 8), AiPlaylistSkipper.slotsOf(keys))
        assertArrayEquals(intArrayOf(2, 0), AiPlaylistSkipper.slotsOf(listOf("id", null, "attr")))
        assertArrayEquals(intArrayOf(-1, -1), AiPlaylistSkipper.slotsOf(listOf("title")))
    }

    fun knownAiItemIsMarkedInvalidInPlace() {
        val values = arrayOfNulls<Any>(keys.size).also { it[0] = 0; it[8] = 117184995006501L }
        val result = AiPlaylistSkipper.markKnownAi(values, 0, 8) { it == 117184995006501L }
        assertEquals(AiPlaylistSkipper.MarkResult(117184995006501L, true), result)
        assertEquals(1, values[0])
    }

    fun otherAttrBitsAreKeptAndMissingAttrCountsAsZero() {
        val values = arrayOfNulls<Any>(keys.size).also { it[0] = 4; it[8] = 7L }
        assertTrue(AiPlaylistSkipper.markKnownAi(values, 0, 8) { true }.marked)
        assertEquals(5, values[0])
        val missing = arrayOfNulls<Any>(keys.size).also { it[8] = 7L }
        assertTrue(AiPlaylistSkipper.markKnownAi(missing, 0, 8) { true }.marked)
        assertEquals(1, missing[0])
    }

    fun unknownOrAlreadyInvalidItemsAreOnlyRecorded() {
        val unknown = arrayOfNulls<Any>(keys.size).also { it[0] = 0; it[8] = 7L }
        assertEquals(AiPlaylistSkipper.MarkResult(7L, false), AiPlaylistSkipper.markKnownAi(unknown, 0, 8) { false })
        assertEquals(0, unknown[0])
        val invalid = arrayOfNulls<Any>(keys.size).also { it[0] = 1; it[8] = 7L }
        assertFalse(AiPlaylistSkipper.markKnownAi(invalid, 0, 8) { true }.marked)
        assertEquals(1, invalid[0])
    }

    fun unreadableShapesFailOpen() {
        val values = arrayOfNulls<Any>(3)
        assertNull(AiPlaylistSkipper.markKnownAi(values, 0, 8) { true }.aid)
        assertNull(AiPlaylistSkipper.markKnownAi(arrayOf(0, "x"), 0, 1) { true }.aid)
        assertNull(AiPlaylistSkipper.markKnownAi(arrayOf(0, 0L), 0, 1) { true }.aid)
        // 读得到 aid 但缺 attr 格：只记归属，不标。
        assertEquals(AiPlaylistSkipper.MarkResult(9L, false), AiPlaylistSkipper.markKnownAi(arrayOf<Any?>(9L), -1, 0) { true })
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AiPlaylistSkipperCases) -> Unit) {
            val s = AiPlaylistSkipperCases()
            block(s)
        }
        runIsolated { s -> s.slotsAreFoundByJsonKeyNameNotByPosition() }
        runIsolated { s -> s.knownAiItemIsMarkedInvalidInPlace() }
        runIsolated { s -> s.otherAttrBitsAreKeptAndMissingAttrCountsAsZero() }
        runIsolated { s -> s.unknownOrAlreadyInvalidItemsAreOnlyRecorded() }
        runIsolated { s -> s.unreadableShapesFailOpen() }
    }
}

private class AiRelatePrecheckerCases {

    /** 手动推进的调度器：记下延迟，由测试逐个执行。 */
    private class ManualScheduler : AbstractExecutorService(), ScheduledExecutorService {
        val pending = ArrayDeque<Pair<Long, Runnable>>()
        fun runNext(): Long? = pending.removeFirstOrNull()?.let { (delay, task) -> task.run(); delay }
        fun drain(): List<Long> = generateSequence { runNext() }.toList()

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            pending.addLast(unit.toMillis(delay) to command)
            return NoopFuture
        }
        override fun <V : Any?> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> =
            throw UnsupportedOperationException()
        override fun scheduleAtFixedRate(c: Runnable, i: Long, p: Long, u: TimeUnit): ScheduledFuture<*> =
            throw UnsupportedOperationException()
        override fun scheduleWithFixedDelay(c: Runnable, i: Long, d: Long, u: TimeUnit): ScheduledFuture<*> =
            throw UnsupportedOperationException()
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true

        private object NoopFuture : ScheduledFuture<Any?> {
            override fun compareTo(other: Delayed?) = 0
            override fun getDelay(unit: TimeUnit) = 0L
            override fun cancel(mayInterruptIfRunning: Boolean) = false
            override fun isCancelled() = false
            override fun isDone() = true
            override fun get(): Any? = null
            override fun get(timeout: Long, unit: TimeUnit): Any? = null
        }
    }

    private val threadFlags = mutableListOf<Boolean>()

    private fun prechecker(
        scheduler: ManualScheduler,
        answers: Map<Long, Boolean?>,
        queried: MutableList<Long>,
        declared: MutableList<Long>,
        ready: () -> Boolean = { true },
        budget: PrecheckBudget = PrecheckBudget()
    ) = AiRelatePrechecker(
        query = { aid ->
            // 查询期间线程标记必须为真，View 拦截器据此按被动请求处理。
            // （断言不能写在回调里：会被 HostThreadGuard 吞掉，先记下来）
            threadFlags += AiRelatePrechecker.isQueryThread()
            queried += aid
            answers[aid]
        },
        ready = ready,
        onDeclared = { declared += it },
        budget = budget,
        clock = { 0L },
        executorFactory = { scheduler }
    )

    fun `selection keeps order, drops duplicates, known and checked aids and caps the page`() {
        val picked = AiRelatePrechecker.select(
            listOf(5, 1, 0, -3, 2, 1, 3, 4) .map(Int::toLong),
            known = { it == 2L },
            checked = { it == 3L },
            limit = 3
        )
        assertEquals(listOf(5L, 1L, 4L), picked)
    }

    fun `queries run one by one with spacing and record declared videos`() {
        val scheduler = ManualScheduler()
        val queried = mutableListOf<Long>(); val declared = mutableListOf<Long>()
        prechecker(scheduler, mapOf(900001L to true, 900002L to false), queried, declared)
            .submit(listOf(900001L, 900002L))
        val delays = scheduler.drain()
        assertEquals(listOf(900001L, 900002L), queried)
        assertEquals(listOf(900001L), declared)
        assertEquals(AiRelatePrechecker.INITIAL_DELAY_MS, delays.first())
        assertTrue(delays.drop(1).all { it == AiRelatePrechecker.SPACING_MS })
        assertEquals(listOf(true, true), threadFlags)
        assertFalse(AiRelatePrechecker.isQueryThread())
    }

    fun `a newer page supersedes the old queue`() {
        val scheduler = ManualScheduler()
        val queried = mutableListOf<Long>()
        val checker = prechecker(scheduler, emptyMap(), queried, mutableListOf())
        checker.submit(listOf(910001L, 910002L))
        checker.submit(listOf(920001L))
        scheduler.drain()
        assertEquals(listOf(920001L), queried)
    }

    fun `nothing is queried without a ready account`() {
        val scheduler = ManualScheduler()
        val queried = mutableListOf<Long>()
        prechecker(scheduler, emptyMap(), queried, mutableListOf(), ready = { false }).submit(listOf(930001L))
        scheduler.drain()
        assertTrue(queried.isEmpty())
    }

    fun `videos confirmed clean are not queried again while failures are retried`() {
        val scheduler = ManualScheduler()
        val queried = mutableListOf<Long>()
        val checker = prechecker(scheduler, mapOf(940001L to false, 940002L to null), queried, mutableListOf())
        checker.submit(listOf(940001L, 940002L)); scheduler.drain()
        checker.submit(listOf(940001L, 940002L)); scheduler.drain()
        assertEquals(listOf(940001L, 940002L, 940002L), queried)
    }

    fun `budget caps queries inside the rolling window`() {
        val budget = PrecheckBudget(max = 2, windowMs = 1_000L)
        assertTrue(budget.tryAcquire(0)); assertTrue(budget.tryAcquire(10))
        assertFalse(budget.tryAcquire(500))
        assertTrue(budget.tryAcquire(1_001))
    }

    fun `bounded aid set evicts the least recently used entry`() {
        val set = BoundedAidSet(2)
        set.add(1); set.add(2); set.contains(1); set.add(3)
        assertTrue(set.contains(1)); assertFalse(set.contains(2)); assertTrue(set.contains(3))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AiRelatePrecheckerCases) -> Unit) {
            val s = AiRelatePrecheckerCases()
            block(s)
        }
        runIsolated { s -> s.`selection keeps order, drops duplicates, known and checked aids and caps the page`() }
        runIsolated { s -> s.`queries run one by one with spacing and record declared videos`() }
        runIsolated { s -> s.`a newer page supersedes the old queue`() }
        runIsolated { s -> s.`nothing is queried without a ready account`() }
        runIsolated { s -> s.`videos confirmed clean are not queried again while failures are retried`() }
        runIsolated { s -> s.`budget caps queries inside the rolling window`() }
        runIsolated { s -> s.`bounded aid set evicts the least recently used entry`() }
    }
}

private class AuthorRuleSetCases {

    fun `splits numeric uids from names`() {
        val rules = AuthorRuleSet.parse("12345\n某位UP主；6789, 0")

        assertEquals(setOf(12345L, 6789L), rules.mids)
        assertEquals(setOf("某位up主", "0"), rules.names)
        assertTrue(rules.isNotEmpty())
    }

    fun `names match exactly and ignore case and padding`() {
        val rules = AuthorRuleSet.parse("SomeUp")

        assertTrue(rules.matches("someup", null))
        assertTrue(rules.matches("  SomeUp  ", null))
        // 全等匹配：一条规则不能把带前缀/后缀的其它作者一起删掉。
        assertFalse(rules.matches("SomeUploader", null))
        assertFalse(rules.matches("aSomeUp", null))
    }

    fun `unreadable signals never match`() {
        val rules = AuthorRuleSet.parse("42, name")

        assertFalse(rules.matches(null, null))
        assertFalse(AuthorRuleSet.EMPTY.matches("name", 42L))
        assertTrue(AuthorRuleSet.EMPTY.isEmpty())
    }

    fun `zero and negative uids fall back to name matching`() {
        val rules = AuthorRuleSet.parse("0, -5")

        assertFalse(rules.matches(null, 0L))
        assertFalse(rules.matches(null, -5L))
        assertTrue(rules.matches("0", null))
        assertTrue(rules.matches("-5", null))
    }

    fun `rule count is bounded`() {
        val raw = (1..AuthorRuleSet.MAX_RULES + 50).joinToString(",")
        val rules = AuthorRuleSet.parse(raw)

        assertEquals(AuthorRuleSet.MAX_RULES, rules.mids.size + rules.names.size)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (AuthorRuleSetCases) -> Unit) {
            val s = AuthorRuleSetCases()
            block(s)
        }
        runIsolated { s -> s.`splits numeric uids from names`() }
        runIsolated { s -> s.`names match exactly and ignore case and padding`() }
        runIsolated { s -> s.`unreadable signals never match`() }
        runIsolated { s -> s.`zero and negative uids fall back to name matching`() }
        runIsolated { s -> s.`rule count is bounded`() }
    }
}

private class BlockComponentLibraryPolicyCases {
    fun `pool keyword match is exact and empty names fail open`() {
        assertTrue(ComponentLibraryPoolMatcher.matches("Base Component Library", emptyList(), setOf("base component library")))
        assertTrue(ComponentLibraryPoolMatcher.matches(null, listOf("基础组件库"), setOf("基础组件库")))
        assertFalse(ComponentLibraryPoolMatcher.matches(null, emptyList()))
        assertFalse(ComponentLibraryPoolMatcher.matches("ordinary resource", listOf("video")))
        assertFalse(ComponentLibraryPoolMatcher.matches("Base Component Library Extra", emptyList(), setOf("base component library")))
    }

    fun `custom keyword set does not silently use the default set`() {
        assertTrue(ComponentLibraryPoolMatcher.matches("safe-pool", emptyList(), setOf("safe-pool")))
        assertFalse(ComponentLibraryPoolMatcher.matches("基础组件库", emptyList(), setOf("other")))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (BlockComponentLibraryPolicyCases) -> Unit) {
            val s = BlockComponentLibraryPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`pool keyword match is exact and empty names fail open`() }
        runIsolated { s -> s.`custom keyword set does not silently use the default set`() }
    }
}

private class BrandSelectionInitializationCases {
    fun `early selection write waits for mode and late storage fills only missing mode`() {
        val state = BrandSplashSelectionState<String>()
        val write = state.observeWrite(listOf("new selection"))
        assertFalse(state.isInitialized())
        val ready = state.initialize(listOf("stale selection"), true)
        assertTrue(state.isInitialized())
        assertEquals(listOf("new selection"), ready.selected)
        assertTrue(ready.customMode)
        assertFalse(state.isCurrent(write.revision))
        assertEquals(ready, state.initialize(listOf("another stale selection"), false))
    }

    fun `explicit mode and newer selection both take precedence over late initialization`() {
        for (custom in listOf(false, true)) {
            val state = BrandSplashSelectionState<String>()
            state.observeMode(custom)
            assertFalse(state.isInitialized())
            val write = state.observeWrite(listOf("latest"))
            assertTrue(state.isInitialized())
            assertEquals(write, state.initialize(listOf("old"), !custom))
            assertEquals(custom, state.snapshot().customMode)
        }
    }

    fun `mode observed before list initializes retains its value while accepting initial list`() {
        val state = BrandSplashSelectionState<String>()
        state.observeMode(true)
        val ready = state.initialize(listOf("initial"), false)
        assertTrue(state.isInitialized())
        assertTrue(ready.customMode)
        assertEquals(listOf("initial"), ready.selected)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (BrandSelectionInitializationCases) -> Unit) {
            val s = BrandSelectionInitializationCases()
            block(s)
        }
        runIsolated { s -> s.`early selection write waits for mode and late storage fills only missing mode`() }
        runIsolated { s -> s.`explicit mode and newer selection both take precedence over late initialization`() }
        runIsolated { s -> s.`mode observed before list initializes retains its value while accepting initial list`() }
    }
}

private class BrandSplash917BehaviorCases {
    interface State
    interface MutableState : State
    class Exit(@JvmField val `$onExit`: () -> Unit) {
        @JvmField var label = 0
        @JvmField var `$customBitmapReady$delegate`: MutableState? = null
        @JvmField var `$customLogoReady$delegate`: MutableState? = null
        @JvmField var `$customBitmap$delegate`: MutableState? = null
        @JvmField var `$currentOnShow`: State? = null
        fun invokeSuspend(value: Any?): Any? = value
    }
    private class Host(private val exitName: String) : ClassLoader(BrandSplash917BehaviorCases::class.java.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
            BrandSplashSkipFeatureInstaller.KNTR_EXIT -> throw ClassNotFoundException(name)
            exitName -> Exit::class.java
            "androidx.compose.runtime.MutableState" -> MutableState::class.java
            "androidx.compose.runtime.State" -> State::class.java
            else -> super.loadClass(name, resolve)
        }
    }
    private fun installed(mainThread: Boolean = true, exitName: String = BrandSplashKntrAccess.CURRENT_EXIT):
        Pair<PlayerPortTestRegistrar, BrandSplashKntrExit> {
        val loader = Host(exitName)
        val point = requireNotNull(BrandSplashKntrAccess.exit(loader))
        assertTrue(point.waitsForImages)
        val registrar = PlayerPortTestRegistrar()
        val environment = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { _, _ -> })
        assertTrue(BrandSplashSkipFeatureInstaller(true) { mainThread }.install(environment) is FeatureInstallResult.Installed)
        return registrar to point
    }

    fun `waiting exit preserves native show persistence and the suspended result`() {
        val (registrar, _) = installed()
        val events = arrayListOf<String>()
        val frame = Exit { events += "exit" }
        val result = registrar.invoke("brand.skip.kntr.exit", frame, arrayOf(Unit)) {
            events += "show"; events += "persist"; frame.label = 1; COROUTINE_SUSPENDED
        }
        assertSame(COROUTINE_SUSPENDED, result)
        assertEquals(listOf("show", "persist", "exit"), events)
        assertEquals(1, frame.label)
        var resumed = 0
        assertSame(Unit, registrar.invoke("brand.skip.kntr.exit", frame, arrayOf(Unit)) { resumed++; Unit })
        assertEquals(0, resumed)
        assertEquals(3, events.size)
    }

    fun `readiness return does not exit or advance photo rotation`() {
        val (registrar, point) = installed()
        var exits = 0
        val frame = Exit { exits++ }
        assertSame(Unit, registrar.invoke("brand.skip.kntr.exit", frame, arrayOf(Unit)) { Unit })
        assertEquals(0, exits)
        assertFalse(point.exited(frame, Unit))
        frame.label = 1
        assertTrue(point.exited(frame, Unit))
        assertFalse(point.exited(frame, COROUTINE_SUSPENDED))
    }

    fun `bitmap exit keeps readiness and the native persistence before the one exit`() {
        val (registrar, point) = installed(exitName = BrandSplashKntrAccess.BITMAP_EXIT)
        val events = arrayListOf<String>()
        val frame = Exit { events += "exit" }
        assertSame(Unit, registrar.invoke("brand.skip.kntr.exit", frame, arrayOf(Unit)) { Unit })
        assertFalse(point.exited(frame, Unit))
        assertTrue(events.isEmpty())
        assertSame(COROUTINE_SUSPENDED, registrar.invoke("brand.skip.kntr.exit", frame, arrayOf(Unit)) {
            events += "show"
            events += "persist"
            frame.label = 1
            COROUTINE_SUSPENDED
        })
        assertEquals(listOf("show", "persist", "exit"), events)
        assertSame(Unit, registrar.invoke("brand.skip.kntr.exit", frame, arrayOf(Unit)) { error("duplicate exit") })
    }

    fun `cancelled pending completion reaches the native failure path`() {
        val (registrar, _) = installed()
        var exits = 0
        val frame = Exit { exits++ }
        registrar.invoke("brand.skip.kntr.exit", frame, arrayOf(Unit)) { frame.label = 1; COROUTINE_SUSPENDED }
        val cancelled = CancellationException("fixture cancellation")
        val failure = Class.forName("kotlin.Result\$Failure").getConstructor(Throwable::class.java).newInstance(cancelled)
        val actual = runCatching {
            registrar.invoke("brand.skip.kntr.exit", frame, arrayOf(failure)) {
                assertEquals(1, frame.label)
                throw cancelled
            }
        }.exceptionOrNull()
        assertSame(cancelled, actual)
        assertEquals(1, exits)
    }

    fun `failed early callback keeps native timer and waiting label`() {
        val (registrar, _) = installed()
        val frame = Exit { error("fixture callback unavailable") }
        assertSame(COROUTINE_SUSPENDED, registrar.invoke("brand.skip.kntr.exit", frame, arrayOf(Unit)) {
            frame.label = 1; COROUTINE_SUSPENDED
        })
        assertEquals(1, frame.label)
    }

    fun `off main completion retains the original wait`() {
        val (registrar, _) = installed(mainThread = false)
        var exits = 0
        val frame = Exit { exits++ }
        registrar.invoke("brand.skip.kntr.exit", frame, arrayOf(Unit)) { frame.label = 1; COROUTINE_SUSPENDED }
        assertEquals(1, frame.label)
        assertEquals(0, exits)
    }

    fun `page ABI preserves the additional native onShow argument`() {
        val prefix = listOf("brand.Model", "kotlin.jvm.functions.Function0", "androidx.compose.ui.Modifier", "java.lang.String")
        assertEquals(BrandSplashPageAbi(4, 5), brandSplashPageAbi(prefix + listOf("androidx.compose.runtime.Composer", "int")))
        assertEquals(BrandSplashPageAbi(4, 5), brandSplashPageAbi(prefix + listOf("androidx.compose.runtime.Composer", "int", "int")))
        assertEquals(BrandSplashPageAbi(5, 6), brandSplashPageAbi(prefix + listOf("kotlin.jvm.functions.Function0", "androidx.compose.runtime.Composer", "int")))
        assertNull(brandSplashPageAbi(prefix + listOf("java.lang.Object", "androidx.compose.runtime.Composer", "int")))
        assertNull(brandSplashPageAbi(prefix + listOf("kotlin.jvm.functions.Function0", "int", "androidx.compose.runtime.Composer")))
    }

    fun `compact brand page keeps native callbacks and bitmap draw admits the transition capture only in its slot`() {
        val page = listOf("bgk", "kotlin.jvm.functions.Function0", "androidx.compose.ui.Modifier",
            "kotlin.jvm.functions.Function0", "androidx.compose.runtime.Composer", "int")
        assertEquals(BrandSplashPageAbi(4, 5), brandSplashPageAbi(page))
        assertNull(brandSplashPageAbi(listOf("other.Model") + page.drop(1)))
        val image = "androidx.compose.ui.graphics.ImageBitmap"
        val resource = "org.jetbrains.compose.resources.DrawableResource"
        val state = "androidx.compose.runtime.MutableState"
        val transition = "androidx.compose.runtime.MutableIntState"
        val draw = listOf(image, "bgk", "androidx.compose.ui.unit.Density", resource, state)
        assertTrue(brandSplashDrawConstructorAbi(draw + listOf(state, state), image, "bgk", resource))
        assertTrue(brandSplashDrawConstructorAbi(draw + listOf(transition, state, state), image, "bgk", resource))
        assertFalse(brandSplashDrawConstructorAbi(draw + listOf(state, transition, state), image, "bgk", resource))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (BrandSplash917BehaviorCases) -> Unit) {
            val s = BrandSplash917BehaviorCases()
            block(s)
        }
        runIsolated { s -> s.`waiting exit preserves native show persistence and the suspended result`() }
        runIsolated { s -> s.`readiness return does not exit or advance photo rotation`() }
        runIsolated { s -> s.`bitmap exit keeps readiness and the native persistence before the one exit`() }
        runIsolated { s -> s.`cancelled pending completion reaches the native failure path`() }
        runIsolated { s -> s.`failed early callback keeps native timer and waiting label`() }
        runIsolated { s -> s.`off main completion retains the original wait`() }
        runIsolated { s -> s.`page ABI preserves the additional native onShow argument`() }
        runIsolated { s -> s.`compact brand page keeps native callbacks and bitmap draw admits the transition capture only in its slot`() }
    }
}
