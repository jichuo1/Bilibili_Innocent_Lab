package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.comment.ReplyTopologyAnchorBinder
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.comment.ReplyTopologySeedBridge
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyNodeFlags
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyNodeSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyThreadKey
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReplyTopologySeedStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeCommentItem
    private class FakeHolder(val itemView: View, private val item: FakeCommentItem)
    private class FakeBinding(private val root: View) {
        @Suppress("unused")
        fun getRoot(): View = root
    }
    private class FakeFieldBinding(@JvmField val a: View)

    private fun flagSeed(rpid: Long) = ReplyTopologySeed(
        key = ReplyTopologyThreadKey(oid = 1L, type = 1, rootRpid = rpid),
        nodes = listOf(
            ReplyTopologyNodeSnapshot.fromRaw(
                rpid = rpid,
                rootRpid = 0L,
                parentRpid = 0L,
                dialogId = 0L,
                ctime = 100L,
                authorMid = 10L,
                authorName = "user",
                repliedAuthorName = null,
                message = "msg-$rpid",
                flags = ReplyTopologyNodeFlags.ROOT
            )
        ),
        expectedReplyCount = 1
    )

    fun seedBridgeEnforcesBoundedLruCapacityAndWireRetryState() {
        val bridge = ReplyTopologySeedBridge()
        for (i in 1L..200L) {
            bridge.cacheSeed(flagSeed(i))
            if (i == 1L) bridge.findSeed(1L)
        }
        assertNull(bridge.findSeed(1L))
        assertNull(bridge.findSeed(8L))
        assertNotNull(bridge.findSeed(9L))
        assertNotNull(bridge.findSeed(200L))
        assertFalse(bridge.allWireBound())
        assertTrue(bridge.shouldRetryWire(null))
        bridge.markAllWireBound()
        assertEquals("wire", bridge.channelKind)
        assertFalse(bridge.shouldRetryWire(null))
        assertTrue(bridge.shouldRetryWire(VersionAdapter.HookPoint("a", "b")))
    }

    fun anchorBinderExtractsCommentItemAndBindingRootAcrossShapes() {
        val binder = ReplyTopologyAnchorBinder()
        val view = object : ViewGroup(null) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit
        }
        val item = FakeCommentItem()
        val holder = FakeHolder(view, item)

        assertSame(item, binder.findCommentItem(FakeCommentItem::class.java, holder, emptyArray()))
        assertSame(item, binder.findCommentItem(FakeCommentItem::class.java, Any(), arrayOf(item)))
        assertNull(binder.findCommentItem(FakeCommentItem::class.java, Any(), emptyArray()))

        assertSame(view, binder.findBindingRoot(holder, emptyArray()))
        assertSame(view, binder.findBindingRoot(null, arrayOf(FakeBinding(view))))
        assertSame(view, binder.findBindingRoot(null, arrayOf(FakeFieldBinding(view))))
    }

    fun commentTopologyInstallerIntegratesCoordinatorRetryAdapted() {
        val posted = mutableListOf<() -> Unit>()
        val env = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = javaClass.classLoader,
            hookPoints = HookPointRegistry(javaClass.classLoader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> },
            postToMain = { posted += it }
        )
        val coordinator = FeatureInstallCoordinator(env)
        val installer = CommentTopologyFeatureInstaller(
            enabled = true,
            points = null,
            lowBindPoint = null,
            highBindPoint = null
        )

        val initial = coordinator.installAll(listOf(installer)).single().result
        assertEquals(FeatureInstallResult.Skipped("missing-adapter-point"), initial)
        assertTrue(installer.isAdaptationRetryPending)

        coordinator.retryAdapted(adapted = null, ok = true)
        assertFalse("After prepare receives null adapted points, retry loop stops", installer.isAdaptationRetryPending)
        assertTrue(posted.isEmpty())
    }

    fun identityMissFallsBackToMatchingCommentItemRpid() {
        val store = ReplyTopologySeedStore(maxRememberedRoots = 2)
        val mappedCommentItem = Any()
        val reboundCommentItem = Any()
        val seed = seed(rootRpid = 101L)
        store.put(mappedCommentItem, seed)

        assertEquals(seed, store.get(reboundCommentItem, commentItemId = 101L))
        assertNull(store.get(reboundCommentItem, commentItemId = 102L))
        assertNull(store.get(reboundCommentItem, commentItemId = null))
    }

    fun rpidFallbackUsesAccessOrderAndEvictsOnlyTheEldestFallback() {
        val store = ReplyTopologySeedStore(maxRememberedRoots = 2)
        val firstCommentItem = Any()
        val secondCommentItem = Any()
        val first = seed(rootRpid = 201L)
        val second = seed(rootRpid = 202L)
        val third = seed(rootRpid = 203L)
        store.put(firstCommentItem, first)
        store.put(secondCommentItem, second)

        assertEquals(first, store.get(Any(), commentItemId = 201L))
        store.put(Any(), third)

        assertEquals(first, store.get(Any(), commentItemId = 201L))
        assertNull(store.get(Any(), commentItemId = 202L))
        assertEquals(third, store.get(Any(), commentItemId = 203L))

        // rpid 回退淘汰不应破坏仍存活 CommentItem 的弱身份命中。
        assertEquals(second, store.get(secondCommentItem, commentItemId = null))
    }

    fun identityCacheIsBoundedAndUsesAccessOrder() {
        // rpid 容量为 1 时，弱身份容量固定为 2。
        val store = ReplyTopologySeedStore(maxRememberedRoots = 1)
        val firstCommentItem = Any()
        val secondCommentItem = Any()
        val thirdCommentItem = Any()
        val first = seed(rootRpid = 301L)
        val second = seed(rootRpid = 302L)
        val third = seed(rootRpid = 303L)
        store.put(firstCommentItem, first)
        store.put(secondCommentItem, second)

        // 读取 first 将它提升为最近使用项，随后新增 third 应淘汰 second。
        assertEquals(first, store.get(firstCommentItem, commentItemId = null))
        store.put(thirdCommentItem, third)

        assertEquals(first, store.get(firstCommentItem, commentItemId = null))
        assertNull(store.get(secondCommentItem, commentItemId = null))
        assertEquals(third, store.get(thirdCommentItem, commentItemId = null))
    }

    fun repeatedIdentityLookupsStayStableAndDoNotInsertEntries() {
        // 查询探针被复用；反复查询不得污染 map，也不得让"未命中"意外建立条目。
        val store = ReplyTopologySeedStore(maxRememberedRoots = 2)
        val known = Any()
        val unknown = Any()
        val stored = seed(rootRpid = 401L)
        store.put(known, stored)

        repeat(64) {
            assertEquals(stored, store.get(known, commentItemId = null))
            assertNull(store.get(unknown, commentItemId = null))
        }

        // 未命中查询若插入了条目，容量为 2 的身份表会把 known 挤掉。
        assertEquals(stored, store.get(known, commentItemId = null))
        assertEquals(stored, store.get(Any(), commentItemId = 401L))
    }

    fun identityLookupKeepsAccessOrderAfterProbeReuse() {
        // rpid 容量为 1 时身份表容量为 2；探针复用不能破坏 LRU 提升。
        val store = ReplyTopologySeedStore(maxRememberedRoots = 1)
        val first = Any()
        val second = Any()
        val third = Any()
        store.put(first, seed(rootRpid = 501L))
        store.put(second, seed(rootRpid = 502L))

        repeat(8) { assertEquals(seed(rootRpid = 501L), store.get(first, commentItemId = null)) }
        store.put(third, seed(rootRpid = 503L))

        assertEquals(seed(rootRpid = 501L), store.get(first, commentItemId = null))
        assertNull(store.get(second, commentItemId = null))
        assertEquals(seed(rootRpid = 503L), store.get(third, commentItemId = null))
    }

    private fun seed(rootRpid: Long): ReplyTopologySeed {
        val key = ReplyTopologyThreadKey(
            oid = 10_000L + rootRpid,
            type = 1L,
            rootRpid = rootRpid
        )
        return ReplyTopologySeed(
            key = key,
            expectedReplyCount = 1,
            nodes = listOf(
                ReplyTopologyNodeSnapshot.fromRaw(
                    rpid = rootRpid,
                    rootRpid = 0L,
                    parentRpid = 0L,
                    authorName = "author-$rootRpid",
                    message = "message-$rootRpid"
                )
            )
        )
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologySeedStoreTest) -> Unit) {
            val s = ReplyTopologySeedStoreTest()
            block(s)
        }
        runIsolated { s -> s.identityMissFallsBackToMatchingCommentItemRpid() }
        runIsolated { s -> s.rpidFallbackUsesAccessOrderAndEvictsOnlyTheEldestFallback() }
        runIsolated { s -> s.identityCacheIsBoundedAndUsesAccessOrder() }
        runIsolated { s -> s.repeatedIdentityLookupsStayStableAndDoNotInsertEntries() }
        runIsolated { s -> s.identityLookupKeepsAccessOrderAfterProbeReuse() }
        runIsolated { s -> s.seedBridgeEnforcesBoundedLruCapacityAndWireRetryState() }
        runIsolated { s -> s.anchorBinderExtractsCommentItemAndBindingRootAcrossShapes() }
        runIsolated { s -> s.commentTopologyInstallerIntegratesCoordinatorRetryAdapted() }

        RuleSetCodecCases().verifyAllCases()
        SectionPickPolicyCases().verifyAllCases()
        SemanticBackendCases(tempFolder.newFolder()).verifyAllCases { tempFolder.newFolder() }
        SemanticCacheCases(tempFolder.newFolder()).verifyAllCases { tempFolder.newFolder() }
        SemanticConnectivityCases().verifyAllCases()
        SemanticHttpResponseReaderCases().verifyAllCases()
        SemanticLearnedStoreCases(tempFolder.newFolder()).verifyAllCases { tempFolder.newFolder() }
    }
}

private class IsolatedCaseFolder(val root: File) {
    fun newFolder(name: String) = File(root, name).apply { mkdirs() }
}

private class RuleSetCodecCases {

    fun `matching preserves whole string Unicode lowercase and nullable value semantics`() {
        val cases = listOf<String?>(
            null, "", "直播Game", "İstanbul", "i\u0307stanbul", "ΟΣ", "ος", "οσ", "ı", "I", "ß", "ẞ", "😀A"
        )
        val rules = listOf("", "game", "İ", "i\u0307", "σ", "ς", "ı", "ß", "😀")
        for (rule in rules) {
            val tokens = RuleSetCodec.parse(rule)
            for (first in cases) {
                for (second in cases) {
                    val values = arrayOf(first, second)
                    val expected = tokens.isNotEmpty() && values.asSequence()
                        .filterNotNull().map(String::lowercase)
                        .any { value -> tokens.any(value::contains) }
                    assertEquals("rule=$rule values=${values.contentToString()}",
                        expected, RuleSetCodec.matches(tokens, *values))
                }
            }
        }
        // 调用者可直接传集合，空 token 的旧行为也必须保留。
        assertTrue(RuleSetCodec.matches(setOf(""), ""))
        assertFalse(RuleSetCodec.matches(setOf(""), null))
    }

    fun `parses supported separators and matches case insensitive substrings`() {
        val rules = RuleSetCodec.parse("直播, 番剧；Game\n 课堂 ")

        assertEquals(setOf("直播", "番剧", "game", "课堂"), rules)
        assertTrue(RuleSetCodec.matches(rules, "HomeGameFragment"))
        assertTrue(RuleSetCodec.matches(rules, "直播"))
        assertFalse(RuleSetCodec.matches(rules, "推荐"))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (RuleSetCodecCases) -> Unit) {
            val s = RuleSetCodecCases()
            block(s)
        }
        runIsolated { s -> s.`matching preserves whole string Unicode lowercase and nullable value semantics`() }
        runIsolated { s -> s.`parses supported separators and matches case insensitive substrings`() }
    }
}

private class SectionPickPolicyCases {

    fun tearDown() {
        SectionPickSession.resetForTest()
        SectionPickClickSlot.resetForTest()
    }

    fun `only the dislike group carries a tag`() {
        // FEEDBACK 组是"恐怖血腥/色情低俗"那一批，语义完全不同。
        assertEquals(163L, SectionPickPolicy.resolveSection("DISLIKE", 3L, null, 163L))
        assertNull(SectionPickPolicy.resolveSection("FEEDBACK", 3L, null, 163L))
        assertNull(SectionPickPolicy.resolveSection("NO_SELECTED", 3L, null, 163L))
        assertNull(SectionPickPolicy.resolveSection(null, 3L, null, 163L))
    }

    fun `an extend that equals the card tag is self proving`() {
        // 主判据：不依赖任何 id 约定。面板内容是服务端按当前卡下发的，id 不可预知。
        // 9.10.0 实测 extend 恒为 null，这条判据拿不到数据，但宿主一旦填上就该优先。
        assertEquals(163L, SectionPickPolicy.resolveSection("DISLIKE", 9999L, "163", 163L))
        assertEquals(163L, SectionPickPolicy.resolveSection("DISLIKE", null, " 163 ", 163L))
        // 对不上就不算——绝不从 extend 里"捞"数字。
        assertNull(SectionPickPolicy.resolveSection("DISLIKE", null, "164", 163L))
        assertNull(SectionPickPolicy.resolveSection("DISLIKE", null, "{\"tid\":163}", 163L))
        assertNull(SectionPickPolicy.parseExtend(null))
        assertNull(SectionPickPolicy.parseExtend(""))
        assertNull(SectionPickPolicy.parseExtend("0"))
    }

    fun `the recorded value is always the card tag never the section`() {
        // args.tid 是标签（宿主按 tag_id 上报）、args.rid 才是分区，两者不是同一个 id
        // 空间。判定与落库只认 tid；rid 混进来会把分区 id 写进标签名单，进而误删
        // ——分区 id 都是两三位数，撞上一个小标签 id 是迟早的事。
        assertEquals(163L, SectionPickPolicy.resolveSection("DISLIKE", null, "163", 163L))
        assertNull("extend 等于 rid 不算命中", SectionPickPolicy.resolveSection("DISLIKE", null, "4", 163L))
        assertTrue(SectionPickPolicy.matchesExtend("163", 163L))
        assertFalse(SectionPickPolicy.matchesExtend("4", 163L))
    }

    fun `the ordinary card chain decides without any usable id`() {
        // 被点项的 id 由服务端下发，不可预知；判据必须在 id 全缺时照样成立。
        assertEquals(163L, SectionPickPolicy.resolveSection("DISLIKE", null, "163", 163L))
        assertNull(SectionPickPolicy.resolveSection("DISLIKE", null, "164", 163L))
    }

    fun `both the legacy card ids still count as a fallback`() {
        // 旧面板语义：直播卡 id=2，普通 av 卡 id=3。
        assertEquals(163L, SectionPickPolicy.resolveSection("DISLIKE", 2L, null, 163L))
        assertEquals(163L, SectionPickPolicy.resolveSection("DISLIKE", 3L, null, 163L))
    }

    fun `the other panel entries are never mistaken for a tag pick`() {
        // 实测面板项 id：第一层 0=稍后再看 / 1=当前视频；第二层 2,4,8,10,12,13,14。
        listOf(0L, 1L, 4L, 8L, 10L, 12L, 13L, 14L).forEach { id ->
            assertNull(
                "id=$id 不该被当成标签选择",
                SectionPickPolicy.resolveSection("DISLIKE", id, null, 163L)
            )
        }
        assertNull(SectionPickPolicy.resolveSection("DISLIKE", null, null, 163L))
    }

    fun `unmatched dislikes are diagnosable so the criterion can be fixed with evidence`() {
        // 判据来自抓包推断；没命中时要留下可核对的实测值，而不是静默放过。
        assertTrue(SectionPickPolicy.shouldDiagnose("DISLIKE", 163L, null))
        assertTrue(SectionPickPolicy.shouldDiagnose("DISLIKE", null, 4L))
        assertFalse(SectionPickPolicy.shouldDiagnose("FEEDBACK", 163L, 4L))
        assertFalse(SectionPickPolicy.shouldDiagnose("DISLIKE", null, null))
        assertFalse(SectionPickPolicy.shouldDiagnose("DISLIKE", 0L, 0L))
    }

    fun `an unreadable tag is never guessed`() {
        // 宁可不记，也不猜一个 id 写进用户名单。
        assertNull(SectionPickPolicy.resolveSection("DISLIKE", 3L, "163", null))
        assertNull(SectionPickPolicy.resolveSection("DISLIKE", 3L, null, 0L))
        assertNull(SectionPickPolicy.resolveSection("DISLIKE", 3L, null, -1L))
    }

    fun `the click slot carries the pick across the two hops`() {
        // extend 只活在 FeedbackItem 上，分区只活在占位卡回指的原卡上，中间那段挂不上。
        SectionPickClickSlot.remember(7L, "163", 1_000L)
        val pick = SectionPickClickSlot.consume(1_005L)
        assertEquals(7L, pick?.itemId)
        assertEquals("163", pick?.extend)
    }

    fun `the click slot is single use so one click is never counted twice`() {
        SectionPickClickSlot.remember(7L, "163", 1_000L)
        assertEquals("163", SectionPickClickSlot.consume(1_001L)?.extend)
        assertNull("取走就该失效，否则同一次点击会被两个落点各算一遍",
            SectionPickClickSlot.consume(1_002L))
    }

    fun `a stale pick is never claimed by a later card`() {
        // 旧版 CardClickProcessor 那条链会读 extend 却不产生占位卡，残值必须自己老死。
        SectionPickClickSlot.remember(7L, "163", 1_000L)
        assertNull(SectionPickClickSlot.consume(1_000L + SectionPickClickSlot.FRESH_WINDOW_MS + 1))
        SectionPickClickSlot.remember(7L, "163", 1_000L)
        assertNull("时钟倒退时也不能认领", SectionPickClickSlot.consume(999L))
    }

    fun `session picks apply immediately and stay deduplicated`() {
        assertTrue(SectionPickSession.add(163L))
        assertFalse("同一分区点第二次不该再增长", SectionPickSession.add(163L))
        assertTrue(SectionPickSession.contains(163L))
        assertFalse(SectionPickSession.contains(29413L))
        assertEquals(setOf(163L), SectionPickSession.current)
    }

    fun `session rejects unusable ids and stays bounded`() {
        assertFalse(SectionPickSession.add(0L))
        assertFalse(SectionPickSession.add(-5L))
        assertFalse(SectionPickSession.contains(null))
        repeat(80) { SectionPickSession.add((it + 1).toLong()) }
        assertTrue("会话集合必须有界，防止异常路径撑成泄漏", SectionPickSession.current.size <= 64)
    }

    fun `the section id is still diagnosable even though it never decides`() {
        // rid 不参与判定，但没命中时要记进日志——日后真要做"按分区过滤"得有实测数据。
        assertTrue(SectionPickPolicy.shouldDiagnose("DISLIKE", null, 4L))
        assertFalse(SectionPickPolicy.shouldDiagnose("DISLIKE", null, null))
    }

    fun `the snapshot key is stable so repeated picks collapse to one entry`() {
        assertEquals("tid:163", SectionPickPolicy.snapshotKey(163L))
        assertEquals(SectionPickPolicy.snapshotKey(163L), SectionPickPolicy.snapshotKey(163L))
    }

    fun `the picks surface is a registered observation surface`() {
        // 没登记进 ALLOWED_SURFACES 的话 encode 会把它悄悄改写成 mine，快照就串面了。
        assertTrue(
            MineComponentSnapshotCodec.SURFACE_SECTION_PICKS in
                MineComponentSnapshotCodec.ALLOWED_SURFACES
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (SectionPickPolicyCases) -> Unit) {
            val s = SectionPickPolicyCases()
            try {
                block(s)
            } finally {
                s.tearDown()
            }
        }
        runIsolated { s -> s.`only the dislike group carries a tag`() }
        runIsolated { s -> s.`an extend that equals the card tag is self proving`() }
        runIsolated { s -> s.`the recorded value is always the card tag never the section`() }
        runIsolated { s -> s.`the ordinary card chain decides without any usable id`() }
        runIsolated { s -> s.`both the legacy card ids still count as a fallback`() }
        runIsolated { s -> s.`the other panel entries are never mistaken for a tag pick`() }
        runIsolated { s -> s.`unmatched dislikes are diagnosable so the criterion can be fixed with evidence`() }
        runIsolated { s -> s.`an unreadable tag is never guessed`() }
        runIsolated { s -> s.`the click slot carries the pick across the two hops`() }
        runIsolated { s -> s.`the click slot is single use so one click is never counted twice`() }
        runIsolated { s -> s.`a stale pick is never claimed by a later card`() }
        runIsolated { s -> s.`session picks apply immediately and stay deduplicated`() }
        runIsolated { s -> s.`session rejects unusable ids and stays bounded`() }
        runIsolated { s -> s.`the section id is still diagnosable even though it never decides`() }
        runIsolated { s -> s.`the snapshot key is stable so repeated picks collapse to one entry`() }
        runIsolated { s -> s.`the picks surface is a registered observation surface`() }
    }
}

private class SemanticBackendCases(rootDir: File) {

    val temp = IsolatedCaseFolder(rootDir)

    /** 批量上限类断言假设一题一文本；拆题后一题 = 一条 × 一条规则，取一条规则保持题数 = 条目数（多规则题面见 SemanticJudgeTest）。 */
    private val rules = SemanticPresets.COMMENT.take(1)

    fun resetVariants() = SemanticJudge.resetLearnedVariants()

    fun clearVariants() = SemanticJudge.resetLearnedVariants()

    private fun chat(content: String): String = JSONObject().put(
        "choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", content)))
    ).toString()

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

    fun `backend selection needs a model for openai compatible`() {
        assertNull(SemanticBackend.of("openai", "  "))
        assertEquals("deepseek-chat", SemanticBackend.of("openai", " deepseek-chat ")!!.model)
        assertEquals(JevBackend.DEFAULT_MODEL, SemanticBackend.of("jev", "")!!.model)
        assertEquals(SemanticBackend.JEV, SemanticBackend.of("unknown", "")!!.id)
        assertNull(SemanticSettings.from("k", "", "", false, provider = "openai", model = ""))
    }

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

    fun `switching backend or model changes the cache identity`() {
        val jev = SemanticJudge.fingerprintOf(rules, "e", JevBackend())
        assertNotEquals(jev, SemanticJudge.fingerprintOf(rules, "e", OpenAiCompatibleBackend("a")))
        assertNotEquals(SemanticJudge.fingerprintOf(rules, "e", OpenAiCompatibleBackend("a")),
            SemanticJudge.fingerprintOf(rules, "e", OpenAiCompatibleBackend("b")))
    }

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

    fun `changing a rule boundary invalidates old verdicts`() {
        val base = SemanticPresets.COMMENT.take(1)
        val edited = listOf(base[0].copy(notFor = base[0].notFor + "、新增边界"))
        assertNotEquals(SemanticJudge.fingerprintOf(base, "e"), SemanticJudge.fingerprintOf(edited, "e"))
    }

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

    fun `workers ai native response shape is readable too`() {
        val cf = CloudflareBackend("@cf/x")
        val asText = JSONObject().put("result", JSONObject().put("response", "{\"results\":[{\"i\":0,\"p\":0.8}]}"))
            .put("success", true).toString()
        assertEquals(0.8f, cf.decode(asText, 1)!![0], 1e-6f)
        val asObject = JSONObject().put("result", JSONObject().put("response",
            JSONObject().put("results", JSONArray().put(JSONObject().put("i", 0).put("p", 0.3))))).toString()
        assertEquals(0.3f, cf.decode(asObject, 1)!![0], 1e-6f)
    }

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

    fun `queue rejections are reported as rejected rather than deadline`() {
        val reports = mutableListOf<SemanticBatchReport>()
        val judge = SemanticJudge("k", rules, background = { false }, transport = { body, _, _ -> 200 to choiceAnswers(body) })
        assertEquals(listOf(SemanticVerdict.UNKNOWN), judge.evaluate(listOf("一"), SemanticMode.WAIT) { r, _, _ -> reports += r })
        assertEquals("rejected", reports.single().outcome)
    }

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

    fun `openrouter addresses on the jev type go to the decisions api`() {
        val jev = JevBackend("span-01-lite:free")
        assertEquals(JevBackend.OPENROUTER_DECISIONS, jev.resolveEndpoint("https://openrouter.ai/api/v1"))
        assertEquals(JevBackend.OPENROUTER_DECISIONS, jev.resolveEndpoint("https://openrouter.ai"))
        assertEquals(JevBackend.OPENROUTER_DECISIONS, jev.resolveEndpoint("https://openrouter.ai/api/alpha/decisions"))
        // 对话模型类型仍走 chat/completions。
        assertEquals("https://openrouter.ai/api/v1/chat/completions", OpenAiCompatibleBackend("x").resolveEndpoint("https://openrouter.ai/api/v1"))
        assertEquals("https://relay.example.com/v1/systemone", jev.resolveEndpoint("https://relay.example.com"))
    }

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
        repeat(500) { if (!file.isFile) Thread.sleep(2) }
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

    fun `a model without reasoning parameters keeps strict json mode`() {
        // 普通非推理模型：拒收任何 reasoning_effort，但支持 response_format → 落在 strict + 默认。
        val seen = ladderRun(OpenAiCompatibleBackend.DEFAULT_ENDPOINT) { json -> !json.has("reasoning_effort") }
        assertEquals(listOf("strict+none", "plain+none", "plain+minimal", "plain+low", "strict"), seen)
    }

    fun `switch only sites skip effort levels instead of repeating requests`() {
        // 智谱只有开关没有强度：关不掉时直接到默认，不发 minimal / low 两个与默认一样的请求。
        val seen = ladderRun("https://open.bigmodel.cn/api/paas/v4/chat/completions") { json -> !json.has("thinking") }
        assertEquals(listOf("strict+disabled", "plain+disabled", "strict"), seen)
        // DeepSeek 没有 minimal：关不掉时试 low。
        val deepseek = ladderRun("https://api.deepseek.com/chat/completions") { json -> json.optString("reasoning_effort") == "low" }
        assertEquals(listOf("strict+disabled", "plain+disabled", "plain+low"), deepseek)
    }

    fun `the chat compat layer is usable without the judge`() {
        // 以后别的 AI 功能直接用通用层：组请求、取正文、找 JSON。
        val body = AiChatCompat.buildRequest("m", "sys", "user", 0, "https://api.deepseek.com/chat/completions", 256)
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertEquals(256, body.getInt("max_tokens"))
        val content = AiChatCompat.contentOf(chatReply("<think>先想 {x</think>```json\n{\"keywords\":[\"a\"]}\n```"))!!
        assertEquals("a", AiChatCompat.findJsonObject(content) { it.has("keywords") }!!.getJSONArray("keywords").getString(0))
        assertTrue(AiChatCompat.truncated(chatReply("{", "length")))
    }

    fun verifyAllCases(newRoot: () -> File) {
        fun runIsolated(block: (SemanticBackendCases) -> Unit) {
            val s = SemanticBackendCases(newRoot())
            s.resetVariants()
            try {
                block(s)
            } finally {
                s.clearVariants()
            }
        }
        runIsolated { s -> s.`openai compatible base urls resolve to chat completions`() }
        runIsolated { s -> s.`a query or fragment survives when a path segment is appended`() }
        runIsolated { s -> s.`backend selection needs a model for openai compatible`() }
        runIsolated { s -> s.`openai request carries rules as system prompt and entries as data`() }
        runIsolated { s -> s.`openai decoding tolerates fences reasoning and malformed items`() }
        runIsolated { s -> s.`openai per rule results merge by max per entry`() }
        runIsolated { s -> s.`unsupported response format relaxes once and stays relaxed`() }
        runIsolated { s -> s.`switching backend or model changes the cache identity`() }
        runIsolated { s -> s.`new results never overwrite the disk copy before it has been merged`() }
        runIsolated { s -> s.`envelope parses but nothing scores counts as a structural failure`() }
        runIsolated { s -> s.`jev question formats follow the official criteria shapes`() }
        runIsolated { s -> s.`jev decoding reads every answer type regardless of what was asked`() }
        runIsolated { s -> s.`jev falls back to noul when choice is rejected and every surface reuses it`() }
        runIsolated { s -> s.`an unrelated rejection does not downgrade the question format`() }
        runIsolated { s -> s.`openai last variant folds the system prompt into the user message`() }
        runIsolated { s -> s.`every preset states its boundary and both kinds of examples`() }
        runIsolated { s -> s.`jev state carries structured rules and the boundary guidance`() }
        runIsolated { s -> s.`changing a rule boundary invalidates old verdicts`() }
        runIsolated { s -> s.`cloudflare accepts an account id or its known url shapes`() }
        runIsolated { s -> s.`workers ai native response shape is readable too`() }
        runIsolated { s -> s.`user wait limit overrides surface defaults and the network timeout stays generous`() }
        runIsolated { s -> s.`an oversized batch is split in halves and the working size is remembered`() }
        runIsolated { s -> s.`a question type rejection is not mistaken for a batch size limit`() }
        runIsolated { s -> s.`tiny prefetch calls are coalesced into one request`() }
        runIsolated { s -> s.`a full prefetch batch is sent at once without waiting for the timer`() }
        runIsolated { s -> s.`queue rejections are reported as rejected rather than deadline`() }
        runIsolated { s -> s.`a decisions model that only takes text state and noul is reached by fallback`() }
        runIsolated { s -> s.`text state lists rules guidance and numbered entries`() }
        runIsolated { s -> s.`openrouter addresses on the jev type go to the decisions api`() }
        runIsolated { s -> s.`learned request formats survive a restart`() }
        runIsolated { s -> s.`thinking is switched off on sites that turn it on by default`() }
        runIsolated { s -> s.`decision endpoints of jev compatible providers resolve correctly`() }
        runIsolated { s -> s.`an oversized request body is split like an oversized batch`() }
        runIsolated { s -> s.`a reasoning model truncated by the token cap falls back to the uncapped request`() }
        runIsolated { s -> s.`a source that rejects everything cannot burn the whole request budget`() }
        runIsolated { s -> s.`a refill that judges nothing does not cool down the source that just answered`() }
        runIsolated { s -> s.`a model that cannot turn thinking off runs at its lowest supported effort`() }
        runIsolated { s -> s.`a model without reasoning parameters keeps strict json mode`() }
        runIsolated { s -> s.`switch only sites skip effort levels instead of repeating requests`() }
        runIsolated { s -> s.`the chat compat layer is usable without the judge`() }
    }
}

private class SemanticCacheCases(rootDir: File = File(".")) {

    val temp = IsolatedCaseFolder(rootDir)

    private fun key(i: Int) = "%032x".format(i)

    /**
     * 真实场景：刷一条弹幕很多的视频（大量一次性条目）期间，首页标题、热门评论这类热点
     * 每隔一段仍会被再次访问。间隔 150 条 > 容量 100，所以同一序列下纯 LRU 必然把热点挤掉。
     */
    private fun <T> replay(hot: List<String>, get: (String) -> T, put: (String) -> Unit) {
        hot.forEach(put)
        repeat(3) { hot.forEach { get(it) } }
        (1_000 until 4_000).forEachIndexed { index, i ->
            val k = key(i)
            get(k)
            put(k)
            if (index % 150 == 149) hot.forEach { get(it) }
        }
    }

    fun `hot entries survive a scan of one-off entries`() {
        val cache = SemanticScoreCache(maxEntries = 100)
        val hot = (0 until 20).map(::key)
        replay(hot, get = { cache.get(it, 0L) }, put = { cache.put(it, 0.5f, 0L) })
        assertTrue(cache.size() <= 100)
        assertTrue("W-TinyLFU should keep the frequently used entries", hot.all(cache::contains))
    }

    fun `plain recency would have evicted the same hot set`() {
        // 对照：同一访问序列下，纯 LRU（LinkedHashMap accessOrder + 容量 100）保不住热点。
        val lru = object : LinkedHashMap<String, Float>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?) = size > 100
        }
        val hot = (0 until 20).map(::key)
        var hotHits = 0
        var hotLookups = 0
        replay(hot, get = { k -> if (k in hot) { hotLookups++; if (lru[k] != null) hotHits++ } else lru[k] },
            put = { lru[it] = 0.5f })
        // 首轮预热之后，扫描期间每次回访热点都已被挤出。
        assertTrue(hotHits <= hot.size * 3)
        assertTrue(hotLookups > hot.size * 3)
    }

    fun `capacity is bounded and expired entries are dropped`() {
        val cache = SemanticScoreCache(maxEntries = 50, ttlMs = 1_000)
        (0 until 500).forEach { cache.put(key(it), 0.5f, 0L) }
        assertTrue(cache.size() <= 50)

        cache.put(key(9_999), 0.7f, 0L)
        assertEquals(0.7f, cache.get(key(9_999), 1_000L), 0f)
        assertTrue(cache.get(key(9_999), 1_001L).isNaN())
        assertFalse(cache.contains(key(9_999)))
    }

    fun `updating an existing key keeps one entry`() {
        val cache = SemanticScoreCache(maxEntries = 50)
        cache.put(key(1), 0.2f, 0L)
        cache.put(key(1), 0.8f, 5L)
        assertEquals(1, cache.size())
        assertEquals(0.8f, cache.get(key(1), 5L), 0f)
    }

    fun `restore bypasses admission but respects ttl and capacity`() {
        val cache = SemanticScoreCache(maxEntries = 10, ttlMs = 100)
        val entries = (0 until 30).map { SemanticScoreCache.Entry(key(it), 0.4f, if (it < 5) -1_000L else 50L) }
        cache.restore(entries, now = 100L)
        assertTrue(cache.size() <= 10)
        assertFalse(cache.contains(key(0))) // 过期的不恢复
        assertTrue(cache.contains(key(5)))
    }

    fun `disk store round trips and rejects foreign configuration`() {
        val file = File(temp.root, "bil_semantic/comment.bin")
        val store = SemanticDiskStore(file, fingerprint = "A")
        store.save(listOf(SemanticScoreCache.Entry(key(1), 0.9f, 100L), SemanticScoreCache.Entry(key(2), 0.1f, 50L)))
        assertTrue(file.isFile)
        assertTrue(file.length() < 200)

        val loaded = store.load(now = 150L, ttlMs = 1_000L)
        assertEquals(listOf(key(1), key(2)), loaded.map { it.key })
        assertEquals(0.9f, loaded[0].score, 0f)
        // 超过保存时长的条目不加载。
        assertEquals(listOf(key(1)), store.load(now = 1_080L, ttlMs = 1_000L).map { it.key })
        // 模型 / 勾选 / 端点变了：整份作废。
        assertTrue(SemanticDiskStore(file, fingerprint = "B").load(150L, 1_000L).isEmpty())
    }

    fun `corrupt or missing files load as empty`() {
        val file = File(temp.root, "bil_semantic/video.bin")
        assertTrue(SemanticDiskStore(file, "A").load(0L, 1_000L).isEmpty())
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertTrue(SemanticDiskStore(file, "A").load(0L, 1_000L).isEmpty())
    }

    fun `startup cleanup deletes only inactive surfaces`() {
        SemanticSurface.entries.forEach { surface ->
            SemanticDiskStore(SemanticDiskStore.fileFor(temp.root, surface), "A")
                .save(listOf(SemanticScoreCache.Entry(key(1), 0.5f, 0L)))
        }
        SemanticDiskCleanup.run(temp.root, setOf(SemanticSurface.COMMENT))
        SemanticSurface.entries.forEach { surface ->
            assertEquals(surface == SemanticSurface.COMMENT, SemanticDiskStore.fileFor(temp.root, surface).isFile)
        }
        // JEV 未配置：全部删除。
        SemanticDiskCleanup.run(temp.root, emptySet())
        assertTrue(SemanticSurface.entries.none { SemanticDiskStore.fileFor(temp.root, it).isFile })
    }

    fun `judgements persist across a restart and are not billed again`() {
        var calls = 0
        val transport: (ByteArray, String, Int) -> Pair<Int, String>? = { body, _, _ ->
            calls += 1
            val count = JSONObject(String(body)).getJSONObject("questions").length()
            val answers = JSONObject()
            repeat(count) { i ->
                answers.put("item_$i", JSONObject().put("type", "choice").put("choice", "block")
                    .put("probabilities", JSONObject().put("block", 0.95).put("keep", 0.05)))
            }
            200 to JSONObject().put("answers", answers).toString()
        }
        val rules = SemanticPresets.COMMENT
        val settings = SemanticSettings.from("k", "", "medium", false, cacheDays = 7, storeRoot = temp.root)!!
        fun judge() = SemanticJudge(
            "k", rules, transport = transport, background = { it.run(); true },
            cache = SemanticScoreCache(ttlMs = settings.cacheTtlMs),
            store = SemanticDiskStore(SemanticDiskStore.fileFor(temp.root, SemanticSurface.COMMENT),
                SemanticJudge.fingerprintOf(rules, settings.endpoint)),
            cacheTtlMs = settings.cacheTtlMs
        )

        // 恰好攒到落盘阈值（50 条）：最后一次写盘把整份写全，不受分批大小（拆题后一题 = 一条 × 一条规则）影响。
        val texts = List(50) { "评论$it" }
        val perRequest = SemanticJudge.MAX_BATCH / rules.size
        val requests = texts.size / perRequest + if (texts.size % perRequest == 0) 0 else 1
        assertTrue(judge().evaluate(texts, SemanticMode.WAIT).all { it == SemanticVerdict.BLOCK })
        assertEquals(requests, calls)
        assertTrue(SemanticDiskStore.fileFor(temp.root, SemanticSurface.COMMENT).isFile)

        // "重启"：新实例、空内存，从硬盘恢复后直接命中。
        val restarted = judge()
        restarted.evaluate(listOf("预热"), SemanticMode.CACHE_ONLY)
        assertTrue(restarted.evaluate(texts, SemanticMode.CACHE_ONLY).all { it == SemanticVerdict.BLOCK })
        assertEquals(requests, calls)
    }

    fun `retention setting is clamped to one to ninety days`() {
        val day = 24L * 60 * 60 * 1000
        assertEquals(90 * day, SemanticSettings.from("k", "", "", false, cacheDays = 365)!!.cacheTtlMs)
        assertEquals(1 * day, SemanticSettings.from("k", "", "", false, cacheDays = 0)!!.cacheTtlMs)
        assertEquals(7 * day, SemanticSettings.from("k", "", "", false)!!.cacheTtlMs)
    }

    fun verifyAllCases(newRoot: () -> File) {
        fun runIsolated(block: (SemanticCacheCases) -> Unit) {
            val s = SemanticCacheCases(newRoot())
            block(s)
        }
        runIsolated { s -> s.`hot entries survive a scan of one-off entries`() }
        runIsolated { s -> s.`plain recency would have evicted the same hot set`() }
        runIsolated { s -> s.`capacity is bounded and expired entries are dropped`() }
        runIsolated { s -> s.`updating an existing key keeps one entry`() }
        runIsolated { s -> s.`restore bypasses admission but respects ttl and capacity`() }
        runIsolated { s -> s.`disk store round trips and rejects foreign configuration`() }
        runIsolated { s -> s.`corrupt or missing files load as empty`() }
        runIsolated { s -> s.`startup cleanup deletes only inactive surfaces`() }
        runIsolated { s -> s.`judgements persist across a restart and are not billed again`() }
        runIsolated { s -> s.`retention setting is clamped to one to ninety days`() }
    }
}

private class SemanticConnectivityCases {

    fun reset() = SemanticJudge.resetLearnedVariants()

    fun clear() = SemanticJudge.resetLearnedVariants()

    private val jev = requireNotNull(SemanticSource.from(1, "k", "https://relay.example.com", SemanticBackend.JEV, ""))

    private fun answer(body: ByteArray): String {
        val json = JSONObject(String(body))
        val questions = json.getJSONObject("questions")
        val answers = JSONObject()
        questions.keys().forEach { id ->
            val type = questions.getJSONObject(id).getString("type")
            answers.put(id, if (type == "noul") JSONObject().put("type", "noul").put("noul", 0.1)
            else JSONObject().put("type", "choice").put("probabilities", JSONObject().put("block", 0.1).put("keep", 0.9)))
        }
        return JSONObject().put("answers", answers).toString()
    }

    fun `a working source reports success with the request format used`() {
        var sent = 0
        val result = SemanticConnectivity.probe(jev, transport = { body, key, _ ->
            sent++
            assertEquals("k", key)
            assertTrue(String(body).contains(SemanticConnectivity.SAMPLE))
            200 to answer(body)
        })
        assertTrue(result.ok)
        assertEquals("ok", result.outcome)
        assertEquals("choice", result.variant)
        assertEquals(1, sent) // 只发一条请求
    }

    fun `failures carry the outcome for a readable message`() {
        assertEquals("http-401", SemanticConnectivity.probe(jev, transport = { _, _, _ -> 401 to "{}" }).outcome)
        assertEquals("network", SemanticConnectivity.probe(jev, transport = { _, _, _ -> null }).outcome)
        assertEquals("parse", SemanticConnectivity.probe(jev, transport = { _, _, _ -> 200 to "<html>" }).outcome.let {
            // 2xx 但读不出：先按写法回退，全部失败后是 parse。
            it
        })
        assertFalse(SemanticConnectivity.probe(jev, transport = { _, _, _ -> 429 to "{}" }).ok)
    }

    fun `a decisions model that needs another request format still tests green`() {
        val respan = requireNotNull(SemanticSource.from(1, "k", "https://openrouter.ai/api/v1", SemanticBackend.JEV, "span-01-lite:free"))
        assertEquals(JevBackend.OPENROUTER_DECISIONS, respan.endpoint)
        val result = SemanticConnectivity.probe(respan, transport = { body, _, _ ->
            val json = JSONObject(String(body))
            val type = json.getJSONObject("questions").getJSONObject("item_0_0").getString("type")
            if (json.opt("state") !is String || type != "noul") 400 to "{}" else 200 to answer(body)
        })
        assertTrue(result.ok)
        assertEquals("noul+text", result.variant)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (SemanticConnectivityCases) -> Unit) {
            val s = SemanticConnectivityCases()
            s.reset()
            try {
                block(s)
            } finally {
                s.clear()
            }
        }
        runIsolated { s -> s.`a working source reports success with the request format used`() }
        runIsolated { s -> s.`failures carry the outcome for a readable message`() }
        runIsolated { s -> s.`a decisions model that needs another request format still tests green`() }
    }
}

private class SemanticHttpResponseReaderCases {
    fun `successful UTF8 body reads through EOF and closes its stream`() {
        val stream = Body("正常响应".toByteArray(Charsets.UTF_8))
        assertEquals("正常响应", SemanticHttpResponseReader.read(Connection(stream, null), 200))
        assertTrue(stream.closed)
    }

    fun `a body exactly at the byte limit is accepted with or without a length header`() {
        val limit = SemanticHttpResponseReader.MAX_BODY_BYTES
        for (declared in listOf(-1L, limit.toLong())) {
            val stream = Body(ByteArray(limit) { 'a'.code.toByte() })
            val text = SemanticHttpResponseReader.read(Connection(stream, null, declared), 200)
            assertEquals(limit, text.length)
            assertEquals(limit, stream.consumed)
            assertTrue(stream.closed)
        }
    }

    fun `chunked overflow is rejected despite a missing or understated length header`() {
        val limit = SemanticHttpResponseReader.MAX_BODY_BYTES
        for (declared in listOf(-1L, 1L)) {
            val stream = Body(ByteArray(limit + 200), chunk = 4093)
            expectTooLarge { SemanticHttpResponseReader.read(Connection(stream, null, declared), 200) }
            assertEquals(limit + 1, stream.consumed)
            assertTrue(stream.closed)
        }
    }

    fun `oversized length preflight rejects before consuming the response body`() {
        val stream = Body("small body".toByteArray())
        expectTooLarge {
            SemanticHttpResponseReader.read(Connection(stream, null,
                SemanticHttpResponseReader.MAX_BODY_BYTES.toLong() + 1L), 200)
        }
        assertEquals(0, stream.consumed)
        assertTrue(stream.closed)
    }

    fun `error streams use the same limit and are never returned as truncated JSON`() {
        val stream = Body(ByteArray(SemanticHttpResponseReader.MAX_BODY_BYTES + 1), chunk = 4093)
        expectTooLarge { SemanticHttpResponseReader.read(Connection(null, stream, 5L), 429) }
        assertTrue(stream.closed)
        assertEquals("", SemanticHttpResponseReader.read(Connection(null, null), 500))
    }

    private fun expectTooLarge(action: () -> Unit) {
        try {
            action()
            fail("oversized bodies must fail before parsing")
        } catch (expected: IOException) {
            assertEquals("semantic response body too large", expected.message)
        }
    }

    private class Body(bytes: ByteArray, private val chunk: Int = Int.MAX_VALUE) : ByteArrayInputStream(bytes) {
        var closed = false
        val consumed: Int get() = pos
        override fun read(target: ByteArray, offset: Int, length: Int): Int =
            super.read(target, offset, minOf(chunk, length))
        override fun close() { closed = true; super.close() }
    }

    private class Connection(
        private val success: InputStream?,
        private val error: InputStream?,
        private val declaredLength: Long = -1L
    ) : HttpURLConnection(URL("https://example.invalid/")) {
        override fun getInputStream(): InputStream = checkNotNull(success) { "wrong response stream" }
        override fun getErrorStream(): InputStream? = error
        override fun getContentLengthLong(): Long = declaredLength
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
    }

    fun verifyAllCases() {
        fun runIsolated(block: (SemanticHttpResponseReaderCases) -> Unit) {
            val s = SemanticHttpResponseReaderCases()
            block(s)
        }
        runIsolated { s -> s.`successful UTF8 body reads through EOF and closes its stream`() }
        runIsolated { s -> s.`a body exactly at the byte limit is accepted with or without a length header`() }
        runIsolated { s -> s.`chunked overflow is rejected despite a missing or understated length header`() }
        runIsolated { s -> s.`oversized length preflight rejects before consuming the response body`() }
        runIsolated { s -> s.`error streams use the same limit and are never returned as truncated JSON`() }
    }
}

private class SemanticLearnedStoreCases(rootDir: File = File(".")) {
    val temporary = IsolatedCaseFolder(rootDir)

    fun `updates during an active save keep one consumer and persist the newest snapshot`() {
        val tasks = ArrayDeque<Runnable>()
        val file = File(temporary.root, "learned.txt")
        var snapshot = "old"
        val writes = mutableListOf<String>()
        lateinit var store: SemanticLearnedStore
        store = SemanticLearnedStore({ tasks.addLast(it); true }, { snapshot }) { target, text ->
            writes += text
            if (text == "old") {
                snapshot = "new"
                assertTrue(store.save())
                // Force the precise interleaving that formerly scheduled a second file writer.
                assertTrue(tasks.isEmpty())
            }
            SemanticLearnedStore.replaceAtomically(target, text)
        }
        assertTrue(store.attach(file) { true })
        assertTrue(store.save())
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertEquals(listOf("old", "new"), writes)
        assertEquals("new", file.readText())
        assertTrue(tasks.isEmpty())
    }

    fun `failed writes retry within a bound and remain retryable on a later update`() {
        val tasks = ArrayDeque<Runnable>()
        val file = File(temporary.root, "learned.txt").apply { writeText("previous") }
        var healthy = false
        var attempts = 0
        val store = SemanticLearnedStore({ tasks.addLast(it); true }, { "latest" }) { target, text ->
            attempts++
            healthy && SemanticLearnedStore.replaceAtomically(target, text)
        }
        assertTrue(store.attach(file) { true })
        store.save()
        tasks.removeFirst().run()
        assertEquals(2, attempts)
        assertEquals("previous", file.readText())
        assertTrue(tasks.isEmpty())
        healthy = true
        store.save()
        tasks.removeFirst().run()
        assertEquals(3, attempts)
        assertEquals("latest", file.readText())
    }

    fun `updates before and during loading wait for all disk sections to merge before saving`() {
        val tasks = ArrayDeque<Runnable>()
        val file = File(temporary.root, "learned.txt").apply { writeText("v old\nb old\nt old\n") }
        val memory = linkedSetOf("v new")
        val store = SemanticLearnedStore({ tasks.addLast(it); true }, { memory.joinToString("\n") })
        assertTrue(store.save())
        assertTrue(tasks.isEmpty())
        var reads = 0
        assertTrue(store.attach(file) { target ->
            reads++
            val disk = target.readLines()
            memory += disk[0]
            memory += "b new"
            assertTrue(store.save())
            assertTrue(tasks.isEmpty())
            // A repeated attach during the load must not start a second disk reader.
            assertFalse(store.attach(target) { reads++; true })
            assertEquals(1, reads)
            memory += disk.drop(1)
            true
        })
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertEquals(setOf("v new", "b new", "v old", "b old", "t old"), file.readLines().toSet())
        assertTrue(store.attach(file) { reads++; true })
        assertEquals(1, reads)
        assertTrue(tasks.isEmpty())
        val other = File(temporary.root, "other.txt")
        assertFalse(store.attach(other) { reads++; true })
        assertEquals(1, reads)
        assertFalse(other.exists())
    }

    fun `attaching without new learning does not rewrite an existing file`() {
        val tasks = ArrayDeque<Runnable>()
        val file = File(temporary.root, "learned.txt").apply { writeText("old") }
        val store = SemanticLearnedStore({ tasks.addLast(it); true }, { "replacement" })
        var reads = 0
        assertTrue(store.attach(file) { reads++; true })
        assertTrue(store.attach(file) { reads++; true })
        assertEquals(1, reads)
        assertTrue(tasks.isEmpty())
        assertEquals("old", file.readText())
    }

    fun `failed initialization preserves disk and pending updates until a successful retry`() {
        val tasks = ArrayDeque<Runnable>()
        val file = File(temporary.root, "learned.txt").apply { writeText("old") }
        var memory = "new"
        val store = SemanticLearnedStore({ tasks.addLast(it); true }, { memory })
        store.save()
        assertFalse(store.attach(file) { throw IOException("read failure") })
        assertTrue(tasks.isEmpty())
        assertEquals("old", file.readText())
        assertTrue(store.save())
        assertTrue(tasks.isEmpty())
        var wrongRootReads = 0
        assertFalse(store.attach(File(temporary.root, "other.txt")) { wrongRootReads++; true })
        assertEquals(0, wrongRootReads)
        assertTrue(tasks.isEmpty())
        assertTrue(store.attach(file) { memory += ":" + it.readText(); true })
        tasks.removeFirst().run()
        assertEquals("new:old", file.readText())
    }

    fun `rejected save after loading is retried by repeated attach without rereading disk`() {
        val tasks = ArrayDeque<Runnable>()
        var reject = true
        val file = File(temporary.root, "learned.txt").apply { writeText("old") }
        val store = SemanticLearnedStore({ if (reject) false else { tasks.addLast(it); true } }, { "merged" })
        store.save()
        var reads = 0
        assertTrue(store.attach(file) { reads++; true })
        assertTrue(tasks.isEmpty())
        assertEquals("old", file.readText())
        reject = false
        assertTrue(store.attach(file) { reads++; true })
        assertEquals(1, reads)
        tasks.removeFirst().run()
        assertEquals("merged", file.readText())
    }

    fun `atomic replacement updates an existing file and cleans temporary files`() {
        val file = File(temporary.root, "learned.txt").apply { writeText("old") }
        assertTrue(SemanticLearnedStore.replaceAtomically(file, "new"))
        assertEquals("new", file.readText())
        assertEquals(listOf("learned.txt"), temporary.root.list()?.toList())
    }

    fun `failed final move preserves the previous file and cleans the incomplete replacement`() {
        val file = File(temporary.root, "learned.txt").apply { writeText("old") }
        assertFalse(SemanticLearnedStore.replaceAtomically(file, "new") { _, _ -> throw IOException("move failed") })
        assertEquals("old", file.readText())
        assertEquals(listOf("learned.txt"), temporary.root.list()?.toList())
    }

    fun verifyAllCases(newRoot: () -> File) {
        fun runIsolated(block: (SemanticLearnedStoreCases) -> Unit) {
            val s = SemanticLearnedStoreCases(newRoot())
            block(s)
        }
        runIsolated { s -> s.`updates during an active save keep one consumer and persist the newest snapshot`() }
        runIsolated { s -> s.`failed writes retry within a bound and remain retryable on a later update`() }
        runIsolated { s -> s.`updates before and during loading wait for all disk sections to merge before saving`() }
        runIsolated { s -> s.`attaching without new learning does not rewrite an existing file`() }
        runIsolated { s -> s.`failed initialization preserves disk and pending updates until a successful retry`() }
        runIsolated { s -> s.`rejected save after loading is retried by repeated attach without rereading disk`() }
        runIsolated { s -> s.`atomic replacement updates an existing file and cleans temporary files`() }
        runIsolated { s -> s.`failed final move preserves the previous file and cleans the incomplete replacement`() }
    }
}
