package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import kmossfixture.MainListReply
import kmossfixture.ReplyInfo
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.protobuf.ProtoBuf
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinMossChannelTest {

    private fun rewriter(blocked: Set<String>, seen: MutableList<List<String>> = mutableListOf()) =
        ProtobufReplyTreeRewriter(ReplyInfo::class.java) { replies ->
            seen += replies.map { (it as ReplyInfo).text }
            replies.filter { (it as ReplyInfo).text in blocked }.toSet()
        }

    fun `raw scope is reentrant and thread local`() {
        assertFalse(KotlinMossChannel.isRaw())
        KotlinMossChannel.raw {
            assertTrue(KotlinMossChannel.isRaw())
            KotlinMossChannel.raw { assertTrue(KotlinMossChannel.isRaw()) }
            assertTrue(KotlinMossChannel.isRaw())
            var other = true
            Thread { other = KotlinMossChannel.isRaw() }.apply { start(); join() }
            assertFalse(other)
        }
        assertFalse(KotlinMossChannel.isRaw())
    }

    fun `nothing blocked keeps the very same message`() {
        val reply = MainListReply(listOf(ReplyInfo.of("a", ReplyInfo.of("a1")), ReplyInfo.of("b")), null, null)
        val result = rewriter(emptySet()).rewrite(reply)
        assertSame(reply, result.message)
        assertEquals(0, result.removed)
    }

    fun `blocked replies sub replies and the top slot are removed`() {
        val keep = ReplyInfo.of("keep", ReplyInfo.of("child-ok"), ReplyInfo.of("child-bad"))
        val reply = MainListReply(listOf(keep, ReplyInfo.of("bad")), ReplyInfo.of("bad-top"), null)
        val seen = mutableListOf<List<String>>()

        val result = rewriter(setOf("bad", "child-bad", "bad-top"), seen).rewrite(reply)
        val rebuilt = result.message as MainListReply

        assertEquals(3, result.removed)
        assertEquals(listOf("keep"), rebuilt.repliesList.map { it.text })
        assertEquals(listOf("child-ok"), rebuilt.repliesList.single().repliesList.map { it.text })
        assertFalse(rebuilt.hasUpTop())
        // 同一层整批判定：主楼一批、子回复一批、置顶位一批。
        assertTrue(listOf("keep", "bad") in seen)
        assertTrue(listOf("child-ok", "child-bad") in seen)
        assertTrue(listOf("bad-top") in seen)
        // 原响应不被改动。
        assertEquals(2, reply.repliesList.size)
        assertTrue(reply.hasUpTop())
    }

    fun `a non removable single only has its children filtered`() {
        // DetailListReply.root 这类单条字段：自己不删，只删它下面命中的子回复。
        val root = ReplyInfo.of("bad", ReplyInfo.of("bad"), ReplyInfo.of("ok"))
        val reply = MainListReply(emptyList(), null, root)

        val rebuilt = rewriter(setOf("bad")).rewrite(reply).message as MainListReply

        assertEquals("bad", rebuilt.root.text)
        assertEquals(listOf("ok"), rebuilt.root.repliesList.map { it.text })
    }

    fun `getter filters are bypassed while reading raw lists`() {
        // 模拟 Java getter 过滤：非 raw 时读到的是过滤后的列表。
        var filteredReads = 0
        val reply = MainListReply(listOf(ReplyInfo.of("bad"), ReplyInfo.of("ok")), null, null)
        val rewriter = ProtobufReplyTreeRewriter(ReplyInfo::class.java) { replies ->
            if (!KotlinMossChannel.isRaw()) filteredReads++
            replies.filter { (it as ReplyInfo).text == "bad" }.toSet()
        }
        val result = rewriter.rewrite(reply)
        // decide 本身在 raw 作用域外调用（判据读取不受影响），但列表是在 raw 里读的。
        assertEquals(1, result.removed)
        assertTrue(filteredReads > 0)
    }

    fun `owner aware decision sees the message that holds each batch`() {
        val child = ReplyInfo.of("c")
        val parent = ReplyInfo.of("p", child)
        val root = ReplyInfo.of("root", ReplyInfo.of("x"))
        val response = MainListReply(listOf(parent), null, root)
        val seen = mutableListOf<Pair<List<String>, Any>>()
        val rewriter = ProtobufReplyTreeRewriter(
            ReplyInfo::class.java,
            decideOwned = { replies, owner ->
                seen += replies.map { (it as ReplyInfo).text } to owner
                emptySet()
            }
        ) { error("the owner-less decision must not be used when an owner-aware one is given") }

        assertSame(response, rewriter.rewrite(response).message)

        // 顶层列表挂在响应上；子回复挂在各自的父评论上；详情页的 root 只递归处理它下面的子回复。
        assertEquals(listOf(listOf("p"), listOf("c"), listOf("x")), seen.map { it.first })
        assertSame(response, seen[0].second)
        assertSame(parent, seen[1].second)
        assertSame(root, seen[2].second)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (KotlinMossChannelTest) -> Unit) {
            val s = KotlinMossChannelTest()
            block(s)
        }
        runIsolated { s -> s.`raw scope is reentrant and thread local`() }
        runIsolated { s -> s.`nothing blocked keeps the very same message`() }
        runIsolated { s -> s.`blocked replies sub replies and the top slot are removed`() }
        runIsolated { s -> s.`a non removable single only has its children filtered`() }
        runIsolated { s -> s.`getter filters are bypassed while reading raw lists`() }
        runIsolated { s -> s.`owner aware decision sees the message that holds each batch`() }
    
        KotlinMossResponseHandlerProxyCases().verifyAllCases()
        LiveHomePromotionFeatureInstallerCases().verifyAllCases()
        MineComponentHiddenFlagWriterCases().verifyAllCases()
        MineScanEntryCases().verifyAllCases()
        MineSettingsEntryGuardCases().verifyAllCases()
        MossResponseHandlerProxyCases().verifyAllCases()
        NativeFeedbackPanelCases().verifyAllCases()
    }
}

private class KotlinMossResponseHandlerProxyCases {
    data class JavaReply(val cards: List<String>, val attributes: Map<String, String>)
    data class KotlinReply(val message: JavaReply)
    interface Handler { fun onNext(reply: Any?) }
    class Endpoint {
        @Suppress("UNUSED_PARAMETER")
        fun mainList(request: Any, serializer: SerializationStrategy<Any>, decoder: DeserializationStrategy<Any>,
            handler: Handler, protoBuf: ProtoBuf) = Unit
    }
    private val endpoint = Endpoint::class.java.getDeclaredMethod("mainList", Any::class.java,
        SerializationStrategy::class.java, DeserializationStrategy::class.java, Handler::class.java, ProtoBuf::class.java)
    private fun key() = KotlinMossResponseHandlerProxy.Key(endpoint, JavaReply::class.java, this, endpoint)

    private class Codec(val failDecode: Boolean = false, val corrupt: Boolean = false) {
        var encodes = 0
        var decodes = 0
        private val messages = ArrayList<JavaReply>()
        private fun store(message: JavaReply): ByteArray { messages += message; return byteArrayOf((messages.size - 1).toByte()) }
        private fun read(bytes: ByteArray) = messages[bytes[0].toInt()]
        val bridge = KotlinMossReplyBridge(
            encode = { encodes++; store((it as KotlinReply).message) },
            parseJava = { read(it).copy(cards = read(it).cards.toList(), attributes = read(it).attributes.toMap()) },
            toBytes = { store(it as JavaReply) },
            decode = { bytes ->
                decodes++
                check(!failDecode) { "decode failed" }
                val value = read(bytes)
                KotlinReply(value.copy(cards = if (corrupt) listOf("restored") else value.cards.toList(),
                    attributes = value.attributes.entries.reversed().associate { it.key to it.value }))
            }
        )
    }

    fun `confirmed receipts run after the final new callback and map order is structural`() {
        val codec = Codec()
        val original = KotlinReply(JavaReply(listOf("drop", "keep"), linkedMapOf("a" to "1", "b" to "2")))
        val events = mutableListOf<String>()
        var delivered: Any? = null
        val delegate = object : Handler {
            override fun onNext(reply: Any?) { assertTrue(events.isEmpty()); delivered = reply }
        }
        val proxy = KotlinMossResponseHandlerProxy.wrap(Handler::class.java, delegate, key(), codec.bridge,
            failed = { throw AssertionError(it) }, transform = { it }, confirmedTransform = { source ->
                val value = source as JavaReply
                KotlinMossResponseHandlerProxy.ConfirmedReply(value.copy(cards = listOf("keep"))) { events += "applied:1" }
            }) as Handler
        proxy.onNext(original)
        assertNotSame(original, delivered)
        assertEquals(listOf("keep"), (delivered as KotlinReply).message.cards)
        assertEquals(original.message.attributes, (delivered as KotlinReply).message.attributes)
        assertEquals(listOf("applied:1"), events)
    }

    fun `failed or corrupt decode keeps the original callback without a confirmed receipt`() {
        for (codec in listOf(Codec(failDecode = true), Codec(corrupt = true))) {
            val original = KotlinReply(JavaReply(listOf("drop"), emptyMap()))
            var delivered: Any? = null
            var applied = 0
            var errors = 0
            val delegate = object : Handler { override fun onNext(reply: Any?) { delivered = reply } }
            val proxy = KotlinMossResponseHandlerProxy.wrap(Handler::class.java, delegate, key(), codec.bridge,
                failed = { errors++ }, transform = { it }, confirmedTransform = { source ->
                    KotlinMossResponseHandlerProxy.ConfirmedReply((source as JavaReply).copy(cards = emptyList())) { applied++ }
                }) as Handler
            proxy.onNext(original)
            assertSame(original, delivered)
            assertEquals(0, applied)
            assertEquals(1, errors)
        }
    }

    fun `unchanged confirmed callback retains identity with one encode and no decoder`() {
        val codec = Codec()
        val original = KotlinReply(JavaReply(listOf("keep"), emptyMap()))
        var delivered: Any? = null
        var applied = 0
        val delegate = object : Handler { override fun onNext(reply: Any?) { delivered = reply } }
        val proxy = KotlinMossResponseHandlerProxy.wrap(Handler::class.java, delegate, key(), codec.bridge,
            failed = { throw AssertionError(it) }, transform = { it }, confirmedTransform = {
                KotlinMossResponseHandlerProxy.ConfirmedReply(it) { applied++ }
            }) as Handler
        proxy.onNext(original)
        assertSame(original, delivered)
        assertEquals(1, codec.encodes)
        assertEquals(0, codec.decodes)
        assertEquals(0, applied)
    }

    fun `merged steps confirm only after one final delivery and host exceptions keep their type`() {
        val codec = Codec()
        val original = KotlinReply(JavaReply(listOf("first", "second", "keep"), emptyMap()))
        val events = mutableListOf<String>()
        val delegate = object : Handler {
            override fun onNext(reply: Any?) {
                assertTrue(events.isEmpty())
                assertEquals(listOf("keep"), (reply as KotlinReply).message.cards)
            }
        }
        fun wrap(target: Handler, name: String) = KotlinMossResponseHandlerProxy.wrap(Handler::class.java,
            target, key(), codec.bridge, failed = { throw AssertionError(it) }, transform = { it }, confirmedTransform = { source ->
                val value = source as JavaReply
                KotlinMossResponseHandlerProxy.ConfirmedReply(value.copy(cards = value.cards.filterNot { it == name })) { events += name }
            }) as Handler
        wrap(wrap(delegate, "first"), "second").onNext(original)
        assertEquals(listOf("second", "first"), events)
        events.clear()
        val failure = IllegalStateException("host")
        val throwing = object : Handler { override fun onNext(reply: Any?) { throw failure } }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { wrap(throwing, "first").onNext(original) })
        assertTrue(events.isEmpty())
    }

    fun verifyAllCases() {
        fun runIsolated(block: (KotlinMossResponseHandlerProxyCases) -> Unit) {
            val s = KotlinMossResponseHandlerProxyCases()
            block(s)
        }
        runIsolated { s -> s.`confirmed receipts run after the final new callback and map order is structural`() }
        runIsolated { s -> s.`failed or corrupt decode keeps the original callback without a confirmed receipt`() }
        runIsolated { s -> s.`unchanged confirmed callback retains identity with one encode and no decoder`() }
        runIsolated { s -> s.`merged steps confirm only after one final delivery and host exceptions keep their type`() }
    }
}

private class LiveHomePromotionFeatureInstallerCases {
    class Card(@JvmField var cardType: String?) {
        fun getCardData(): Any? = error("invoked through Modern callback fixture")
    }
    class Page {
        private fun feedPageData(): List<Any> = emptyList()
        fun getCardList(): List<Any> = emptyList()
    }
    class WrongType { @JvmField var cardType = 1; fun getCardData(): Any? = null }
    class WrongGetter { @JvmField var cardType = "banner_v2"; fun getCardData(): String? = null }
    class WrongPage { fun feedPageData(): Any = emptyList<Any>(); fun getCardList(): List<Any> = emptyList() }
    private class Fixture(data: Class<*>? = Card::class.java, page: Class<*>? = Page::class.java,
        process: String = "tv.danmaku.bili", failRegistration: Boolean = false) {
        var lookups = 0
        val loader = object : ClassLoader(Card::class.java.classLoader) {
            override fun loadClass(name: String): Class<*> = when (name) {
                LiveHomePromotionFeatureInstaller.DATA_CLASS -> { lookups++; data ?: throw ClassNotFoundException(name) }
                LiveHomePromotionFeatureInstaller.PAGE_CLASS -> { lookups++; page ?: throw ClassNotFoundException(name) }
                else -> super.loadClass(name)
            }
        }
        val registrar = PlayerPortTestRegistrar(if (failRegistration) HOOK else null)
        val stages = mutableListOf<FeatureRuntimeStage>()
        val errors = mutableListOf<String>()
        val environment = HookEnvironment(process, loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { key, _ -> errors += key }, { _, _ -> },
            runtimeEvidence = { id, stage, _ -> assertEquals(LiveHomePromotionFeatureInstaller.ID, id); stages += stage })
        fun install(enabled: Boolean = true) = LiveHomePromotionFeatureInstaller(enabled).install(environment)
        fun read(card: Any, original: () -> Any?): Any? = registrar.invoke(HOOK, card) { original() }
    }

    fun `disabled or non main process performs no host lookup or registration`() {
        for ((enabled, process) in listOf(false to "tv.danmaku.bili", true to "tv.danmaku.bili:live")) {
            val f = Fixture(process = process)
            assertTrue(f.install(enabled) is FeatureInstallResult.Skipped)
            assertEquals(0, f.lookups)
            assertTrue(f.registrar.registrationAttempts.isEmpty())
        }
    }

    fun `unknown host shapes leave original behavior untouched`() {
        for (f in listOf(Fixture(data = null), Fixture(page = null), Fixture(data = WrongType::class.java),
                Fixture(data = WrongGetter::class.java), Fixture(page = WrongPage::class.java))) {
            assertTrue(f.install() is FeatureInstallResult.Skipped)
            assertTrue(f.registrar.hooks.isEmpty())
        }
    }

    fun `both promotion types skip conversion even when a payload was cached`() {
        val f = Fixture(); assertEquals(FeatureInstallResult.Installed(), f.install())
        val cachedPayload = Any()
        for (type in listOf("banner_v2", "topview_v2")) {
            var called = false
            assertNull(f.read(Card(type)) { called = true; cachedPayload })
            assertFalse(called)
        }
        assertEquals(1, f.registrar.registrationAttempts.size)
    }

    fun `followed channels ordinary live cards and unknown types preserve payload identity`() {
        val f = Fixture(); f.install()
        for (type in listOf("my_idol_v1", "small_card_v1", "area_entrance_v3", "activity_card_v1",
                "sea_patrol_v2", "banner_v20", "prefix_banner_v2", "BANNER_V2", "", null)) {
            val payload = Any(); var calls = 0
            assertSame(payload, f.read(Card(type)) { calls++; payload })
            assertEquals(1, calls)
        }
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED), f.stages)
    }

    fun `host null skipping assembles a compact list with preserved neighbor order`() {
        val f = Fixture(); f.install()
        val follow = Any(); val ordinary = Any(); val area = Any()
        val cards = listOf(Card("banner_v2") to Any(), Card("my_idol_v1") to follow,
            Card("topview_v2") to Any(), Card("small_card_v1") to ordinary, Card("area_entrance_v3") to area)
        repeat(3) { // 首屏、重复读取、刷新都经过相同转换入口。
            val list = cards.mapNotNull { (card, payload) -> f.read(card) { payload } }
            assertEquals(3, list.size)
            assertSame(follow, list[0]); assertSame(ordinary, list[1]); assertSame(area, list[2])
        }
        assertTrue(cards.all { it.first.cardType != null })
    }

    fun `repeated target reads report evidence once and never run original conversion`() {
        val f = Fixture(); f.install(); val card = Card("banner_v2")
        repeat(2_000) { assertNull(f.read(card) { fail("promotion conversion ran"); Any() }) }
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), f.stages)
        assertTrue(f.errors.isEmpty())
    }

    fun `ordinary host exceptions still reach the caller`() {
        val f = Fixture(); f.install(); val failure = IllegalStateException("host failure")
        try { f.read(Card("small_card_v1")) { throw failure }; fail("exception lost") }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }
    }

    fun `unreadable receiver disables only this installer and logs once`() {
        val f = Fixture(); f.install(); val payload = Any()
        assertSame(payload, f.read(Any()) { payload })
        assertSame(payload, f.read(Card("banner_v2")) { payload })
        assertEquals(listOf("live_home_promotion_read_failed"), f.errors)
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED), f.stages)
    }

    fun `registration failure reports no applied evidence`() {
        val f = Fixture(failRegistration = true)
        assertEquals(FeatureInstallResult.Skipped("registration-failed"), f.install())
        assertTrue(f.stages.isEmpty())
    }

    companion object { private const val HOOK = LiveHomePromotionFeatureInstaller.HOOK_ID }

    fun verifyAllCases() {
        fun runIsolated(block: (LiveHomePromotionFeatureInstallerCases) -> Unit) {
            val s = LiveHomePromotionFeatureInstallerCases()
            block(s)
        }
        runIsolated { s -> s.`disabled or non main process performs no host lookup or registration`() }
        runIsolated { s -> s.`unknown host shapes leave original behavior untouched`() }
        runIsolated { s -> s.`both promotion types skip conversion even when a payload was cached`() }
        runIsolated { s -> s.`followed channels ordinary live cards and unknown types preserve payload identity`() }
        runIsolated { s -> s.`host null skipping assembles a compact list with preserved neighbor order`() }
        runIsolated { s -> s.`repeated target reads report evidence once and never run original conversion`() }
        runIsolated { s -> s.`ordinary host exceptions still reach the caller`() }
        runIsolated { s -> s.`unreadable receiver disables only this installer and logs once`() }
        runIsolated { s -> s.`registration failure reports no applied evidence`() }
    }
}

private class MineComponentHiddenFlagWriterCases {

    @Suppress("unused")
    private class Item {
        @JvmField var primitiveVisible: Boolean = true
        @JvmField var boxedVisible: java.lang.Boolean? = null
        @JvmField var primitiveCount: Int = 1
        @JvmField var boxedCount: java.lang.Integer? = null
        @JvmField var text: String? = "keep"
    }

    private fun fieldOf(name: String) = Item::class.java.getDeclaredField(name)

    /** 前置条件：固件确实同时提供了原始与装箱字段，否则后面的断言毫无意义。 */
    fun `fixture really declares both primitive and boxed fields`() {
        assertTrue(fieldOf("primitiveVisible").type.isPrimitive)
        assertTrue(fieldOf("primitiveCount").type.isPrimitive)
        assertEquals(java.lang.Boolean::class.java, fieldOf("boxedVisible").type)
        assertEquals(java.lang.Integer::class.java, fieldOf("boxedCount").type)
    }

    fun `writes the hidden value to primitive and boxed fields alike`() {
        val item = Item()

        assertTrue(MineComponentHiddenFlagWriter.apply(item, fieldOf("primitiveVisible")))
        assertEquals(false, fieldOf("primitiveVisible").get(item))

        assertTrue(MineComponentHiddenFlagWriter.apply(item, fieldOf("boxedVisible")))
        assertEquals(false, fieldOf("boxedVisible").get(item))

        assertTrue(MineComponentHiddenFlagWriter.apply(item, fieldOf("primitiveCount")))
        assertEquals(0, fieldOf("primitiveCount").get(item))

        assertTrue(MineComponentHiddenFlagWriter.apply(item, fieldOf("boxedCount")))
        assertEquals(0, fieldOf("boxedCount").get(item))
    }

    /** 无法表达「隐藏」语义的类型必须原样保留，不猜测写入。 */
    fun `leaves unsupported field types untouched`() {
        val item = Item()

        assertFalse(MineComponentHiddenFlagWriter.apply(item, fieldOf("text")))
        assertEquals("keep", fieldOf("text").get(item))

        assertFalse(MineComponentHiddenFlagWriter.apply(item, null))
        assertNull(MineComponentHiddenFlagWriter.hiddenValueFor(String::class.java))
    }

    fun `resolves a hidden value for both representations of each supported type`() {
        listOf("primitiveVisible", "boxedVisible").forEach { name ->
            assertEquals(false, MineComponentHiddenFlagWriter.hiddenValueFor(fieldOf(name).type))
        }
        listOf("primitiveCount", "boxedCount").forEach { name ->
            assertEquals(0, MineComponentHiddenFlagWriter.hiddenValueFor(fieldOf(name).type))
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (MineComponentHiddenFlagWriterCases) -> Unit) {
            val s = MineComponentHiddenFlagWriterCases()
            block(s)
        }
        runIsolated { s -> s.`fixture really declares both primitive and boxed fields`() }
        runIsolated { s -> s.`writes the hidden value to primitive and boxed fields alike`() }
        runIsolated { s -> s.`leaves unsupported field types untouched`() }
        runIsolated { s -> s.`resolves a hidden value for both representations of each supported type`() }
    }
}

private class MineScanEntryCases {

    fun `current snapshot round trip preserves stable selector and metadata`() {
        val entry = requireNotNull(
            MineComponentScanEntry.create(
                kind = "item",
                title = "历史记录",
                id = "123",
                uri = "bilibili://user_center/history",
                showing = false
            )
        )
        val payload = MineComponentSnapshotCodec.encode(
            processName = MineComponentSnapshotCodec.TARGET_PACKAGE,
            capabilities = setOf("item_filter"),
            entries = listOf(entry),
            generatedAt = 1234L
        )

        val restored = MineComponentSnapshotCodec.decodeOrNull(payload)
        assertNotNull(restored)
        assertEquals(1234L, restored?.generatedAt)
        assertEquals(setOf("item_filter"), restored?.capabilities)
        assertEquals("item:id:123", restored?.entries?.single()?.key)
        assertFalse(requireNotNull(restored).entries.single().showing)
    }

    fun `legacy v1 snapshot is readable and derives a stable key`() {
        val payload = JSONObject().apply {
            put("v", 1)
            put("items", JSONArray().put(JSONObject().apply {
                put("kind", "group")
                put("title", "更多服务")
                put("showing", true)
            }))
        }.toString()

        val restored = MineComponentSnapshotCodec.decodeOrNull(payload)
        assertEquals("group:title:更多服务", restored?.entries?.single()?.key)
        assertTrue(requireNotNull(restored).entries.single().showing)
        assertEquals(0L, restored.generatedAt)
    }

    fun `provider mode rejects legacy mismatched keys and oversized payloads`() {
        val legacy = JSONObject().apply {
            put("v", 1)
            put("items", JSONArray())
        }.toString()
        assertNull(MineComponentSnapshotCodec.decodeOrNull(legacy, allowLegacy = false))

        val mismatchedKey = JSONObject().apply {
            put("schema", MineComponentSnapshotCodec.CURRENT_SCHEMA_VERSION)
            put("targetPackage", MineComponentSnapshotCodec.TARGET_PACKAGE)
            put("process", MineComponentSnapshotCodec.TARGET_PACKAGE)
            put("generatedAt", 1L)
            put("items", JSONArray().put(JSONObject().apply {
                put("key", "item:id:wrong")
                put("kind", "item")
                put("id", "right")
                put("showing", true)
            }))
        }.toString()
        assertNull(MineComponentSnapshotCodec.decodeOrNull(mismatchedKey, allowLegacy = false))

        val oversized = "x".repeat(MineComponentSnapshotCodec.MAX_PAYLOAD_BYTES + 1)
        assertNull(MineComponentSnapshotCodec.decodeOrNull(oversized, allowLegacy = false))
    }

    fun `selection codec preserves commas and removes duplicates`() {
        val values = setOf(
            "item:uri:bilibili://mine?a=1,b=2",
            "group:title:更多服务"
        )
        val encoded = MineComponentSelectionCodec.encode(values)
        assertEquals(values, MineComponentSelectionCodec.decode(encoded))
        assertEquals(setOf("item:id:1"), MineComponentSelectionCodec.decode("item:id:1\nitem:id:1"))
    }

    fun `entry factory rejects entries without identity or overlong fields`() {
        assertNull(MineComponentScanEntry.create("group", null, null, null, true))
        assertNull(MineComponentScanEntry.create("unknown", "title", null, null, true))
        val overlong = MineComponentScanEntry.create(
            "item",
            "t".repeat(300),
            null,
            null,
            true
        )
        assertNull(overlong)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (MineScanEntryCases) -> Unit) {
            val s = MineScanEntryCases()
            block(s)
        }
        runIsolated { s -> s.`current snapshot round trip preserves stable selector and metadata`() }
        runIsolated { s -> s.`legacy v1 snapshot is readable and derives a stable key`() }
        runIsolated { s -> s.`provider mode rejects legacy mismatched keys and oversized payloads`() }
        runIsolated { s -> s.`selection codec preserves commas and removes duplicates`() }
        runIsolated { s -> s.`entry factory rejects entries without identity or overlong fields`() }
    }
}

private class MineSettingsEntryGuardCases {

    fun onlyTheHostSettingsRouteIsProtected() {
        assertTrue(MineSettingsEntryGuard.isSettingsEntry("activity://main/preference"))
        assertTrue(MineSettingsEntryGuard.isSettingsEntry(" activity://main/preference?from=mine "))
        assertTrue(MineSettingsEntryGuard.isSettingsEntry("activity://main/preference#x"))
        assertFalse(MineSettingsEntryGuard.isSettingsEntry("activity://main/preference-other"))
        assertFalse(MineSettingsEntryGuard.isSettingsEntry("bilibili://main/scan"))
        assertFalse(MineSettingsEntryGuard.isSettingsEntry(""))
        assertFalse(MineSettingsEntryGuard.isSettingsEntry(null))
    }

    /** 整组隐藏但组里有「设置」：只留「设置」，而不是把整组连同设置一起删掉。 */
    fun hiddenGroupKeepsOnlyTheSettingsEntry() {
        val items = listOf("bilibili://main/scan", "activity://main/preference", "bilibili://history")
        assertEquals(listOf("activity://main/preference"), MineSettingsEntryGuard.settingsOnly(items) { it as String })
    }

    fun groupWithoutSettingsIsHiddenAsBefore() {
        assertNull(MineSettingsEntryGuard.settingsOnly(listOf("bilibili://history", null)) { it as String? })
        assertNull(MineSettingsEntryGuard.settingsOnly(emptyList<Any>()) { it as String? })
    }

    fun verifyAllCases() {
        fun runIsolated(block: (MineSettingsEntryGuardCases) -> Unit) {
            val s = MineSettingsEntryGuardCases()
            block(s)
        }
        runIsolated { s -> s.onlyTheHostSettingsRouteIsProtected() }
        runIsolated { s -> s.hiddenGroupKeepsOnlyTheSettingsEntry() }
        runIsolated { s -> s.groupWithoutSettingsIsHiddenAsBefore() }
    }
}

private class MossResponseHandlerProxyCases {
    fun `copy transformer forwards replacement and failures keep original`() {
        val original = Any()
        val replacement = Any()
        val delegate = RecordingHandler()
        val proxy = MossResponseHandlerProxy.wrapTransform(FakeHandler::class.java, delegate) { replacement } as FakeHandler
        proxy.onNext(original)
        assertTrue(delegate.delivered.single() === replacement)
        val failing = MossResponseHandlerProxy.wrapTransform(FakeHandler::class.java, delegate) { error("copy failed") } as FakeHandler
        failing.onNext(original)
        assertTrue(delegate.delivered.last() === original)
        assertEquals(42L, proxy.onNextForAck(original))
    }

    /** 结构与宿主 `MossResponseHandler` 一致的最小替身：一个 onNext + 一个带返回值的方法。 */
    interface FakeHandler {
        fun onNext(reply: Any?)
        fun onError(reason: String)
        fun onNextForAck(reply: Any?): Long
    }

    private class RecordingHandler : FakeHandler {
        val delivered = mutableListOf<Any?>()
        val errors = mutableListOf<String>()
        var ackCalls = 0

        override fun onNext(reply: Any?) {
            delivered += reply
        }

        override fun onError(reason: String) {
            errors += reason
        }

        override fun onNextForAck(reply: Any?): Long {
            ackCalls += 1
            return 42L
        }
    }

    fun `observes onNext then forwards the original reply`() {
        val delegate = RecordingHandler()
        val observed = mutableListOf<Any>()
        val proxy = MossResponseHandlerProxy.wrap(
            FakeHandler::class.java,
            delegate
        ) { observed += it } as FakeHandler

        proxy.onNext("reply")

        assertEquals(listOf<Any>("reply"), observed)
        assertEquals(listOf<Any?>("reply"), delegate.delivered)
    }

    fun `observer failure never breaks host delivery`() {
        val delegate = RecordingHandler()
        val proxy = MossResponseHandlerProxy.wrap(
            FakeHandler::class.java,
            delegate
        ) { error("observer blew up") } as FakeHandler

        proxy.onNext("reply")

        assertEquals(listOf<Any?>("reply"), delegate.delivered)
    }

    fun `other callbacks are forwarded untouched including return values`() {
        val delegate = RecordingHandler()
        val observed = mutableListOf<Any>()
        val proxy = MossResponseHandlerProxy.wrap(
            FakeHandler::class.java,
            delegate
        ) { observed += it } as FakeHandler

        proxy.onError("boom")
        val ack = proxy.onNextForAck("reply")

        assertEquals(listOf("boom"), delegate.errors)
        assertEquals(42L, ack)
        assertEquals(1, delegate.ackCalls)
        assertTrue(observed.isEmpty())
    }

    fun `host thrown exceptions keep their original type`() {
        val delegate = object : FakeHandler {
            override fun onNext(reply: Any?) = throw IllegalStateException("host failure")
            override fun onError(reason: String) = Unit
            override fun onNextForAck(reply: Any?): Long = 0L
        }
        val proxy = MossResponseHandlerProxy.wrap(FakeHandler::class.java, delegate) {} as FakeHandler

        val failure = assertThrows(IllegalStateException::class.java) { proxy.onNext("reply") }
        assertEquals("host failure", failure.message)
    }

    fun `refuses to wrap a mismatched delegate or a non interface`() {
        assertNull(MossResponseHandlerProxy.wrap(FakeHandler::class.java, "not a handler") {})
        assertNull(MossResponseHandlerProxy.wrap(String::class.java, "text") {})
        assertNotNull(
            MossResponseHandlerProxy.wrap(FakeHandler::class.java, RecordingHandler()) {}
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (MossResponseHandlerProxyCases) -> Unit) {
            val s = MossResponseHandlerProxyCases()
            block(s)
        }
        runIsolated { s -> s.`copy transformer forwards replacement and failures keep original`() }
        runIsolated { s -> s.`observes onNext then forwards the original reply`() }
        runIsolated { s -> s.`observer failure never breaks host delivery`() }
        runIsolated { s -> s.`other callbacks are forwarded untouched including return values`() }
        runIsolated { s -> s.`host thrown exceptions keep their original type`() }
        runIsolated { s -> s.`refuses to wrap a mismatched delegate or a non interface`() }
    }
}

private class NativeFeedbackPanelCases {
    enum class Type { DEFAULT, DISLIKE }
    enum class Style { LIST }
    data class Item(val title: String, val type: Type, val onClick: (Any?) -> Any?)
    data class Group(val style: Style, val title: String, val items: List<Item>)
    class Card(val tag: String)
    class Click(val card: Card, val body: () -> Unit) : (Any?) -> Any? {
        override fun invoke(value: Any?): Any? { body(); return null }
    }
    class Wrapper(val child: Any)
    class Ambiguous(val first: Card, val second: Card)
    class StaticHolder { companion object { @JvmField var card: Card? = null } }

    fun legacyItemsResolveTheirOwnCallbackCardWithoutRunningOfficialActions() {
        var official = 0
        val picked = mutableListOf<Card>()
        val card = Card("legacy")
        val result = panel(picked::add).legacyEntries(listOf(Wrapper(Click(card) { official++ })))
        assertEquals(listOf("legacy"), result.map { it.title })
        result.single().click()
        assertEquals(listOf(card), picked)
        assertEquals(0, official)
    }

    private fun injector(): FeedbackPanelInjector {
        val owner = Item::class.java
        return FeedbackPanelInjector(owner.declaredConstructors.single(), owner.getMethod("getTitle"),
            owner.getMethod("getType"), owner.getMethod("getOnClick"), owner,
            kotlin.jvm.functions.Function1::class.java, Type.DEFAULT)
    }

    private fun panel(onPicked: (Card) -> Unit): NativeFeedbackPanel {
        val group = Group::class.java
        val inject = injector()
        assertTrue(inject.isUsable)
        return NativeFeedbackPanel(inject, group.getMethod("getItems"), group.getMethod("getStyle"),
            group.getMethod("getTitle"), group.getMethod("copy", Style::class.java, String::class.java, List::class.java),
            Item::class.java.getMethod("getOnClick"), { it is Card }, { card ->
                listOf(checkNotNull(inject.newEntry((card as Card).tag) { onPicked(card) }))
            })
    }

    fun itemCallbackBindsTheVideoAndOfficialCallbackIsUntouched() {
        var official = 0
        val selected = mutableListOf<Card>()
        val card = Card("tag")
        val originalItem = Item("official", Type.DISLIKE, Click(card) { official++ })
        val original = listOf(Group(Style.LIST, "native", listOf(originalItem)))
        val result = panel(selected::add).merge(original)!!
        val changed = result.single() as Group
        assertEquals(1, original.single().items.size)
        assertSame(originalItem, changed.items.first())
        assertEquals("native", changed.title)
        assertEquals(Type.DEFAULT, changed.items.last().type)
        changed.items.last().onClick(null)
        assertEquals(listOf(card), selected)
        assertEquals(0, official)
        originalItem.onClick(null)
        assertEquals(1, official)
        assertEquals(1, selected.size)
    }

    fun recompositionDoesNotDuplicateEntriesAndANewPanelBindsItsOwnCard() {
        val selected = mutableListOf<Card>()
        val first = Card("one")
        val second = Card("two")
        fun groups(card: Card) = listOf(Group(Style.LIST, "", listOf(Item("official", Type.DISLIKE, Click(card) {}))))
        val panel = panel(selected::add)
        val original = groups(first)
        val result = panel.merge(original)!!
        assertSame(result, panel.merge(original))
        assertNull(panel.merge(result))
        val other = panel.merge(groups(second))!!
        ((result.single() as Group).items.last()).onClick(null)
        ((other.single() as Group).items.last()).onClick(null)
        assertEquals(listOf(first, second), selected)
    }

    fun ambiguousCardsStaticFieldsCyclesAndExcessiveDepthFailClosed() {
        val first = Card("one")
        val second = Card("two")
        assertNull(FeedbackCardGraph.findUnique(listOf(Ambiguous(first, second))) { it is Card })
        StaticHolder.card = first
        assertNull(FeedbackCardGraph.findUnique(listOf(StaticHolder())) { it is Card })
        assertNull(FeedbackCardGraph.findUnique(listOf(Wrapper(Wrapper(Wrapper(Wrapper(Wrapper(first))))))) { it is Card })
        assertSame(first, FeedbackCardGraph.findUnique(listOf(Click(first) {}, Click(first) {})) { it is Card })
        StaticHolder.card = null
    }

    fun mixedListsAndMissingCardPreserveNativePanel() {
        assertFalse(injector().accepts(listOf(Item("", Type.DEFAULT) { null }, "foreign")))
        assertNull(panel {}.merge(listOf(Group(Style.LIST, "", listOf(Item("", Type.DEFAULT) { null })))))
        assertNull(panel {}.merge(emptyList<Any>()))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (NativeFeedbackPanelCases) -> Unit) {
            val s = NativeFeedbackPanelCases()
            block(s)
        }
        runIsolated { s -> s.legacyItemsResolveTheirOwnCallbackCardWithoutRunningOfficialActions() }
        runIsolated { s -> s.itemCallbackBindsTheVideoAndOfficialCallbackIsUntouched() }
        runIsolated { s -> s.recompositionDoesNotDuplicateEntriesAndANewPanelBindsItsOwnCard() }
        runIsolated { s -> s.ambiguousCardsStaticFieldsCyclesAndExcessiveDepthFailClosed() }
        runIsolated { s -> s.mixedListsAndMissingCardPreserveNativePanel() }
    }
}
