package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.Activity
import androidx.fragment.app.Fragment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PrivacyChannelAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.GuestLibraryHostHooks
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.GuestLibraryRuntime
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.GuestNativeFolderEditorHooks
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.GuestNativeHistoryHooks
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.GuestNativeListHttpHooks
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.GuestNativeWatchLaterAddHooks
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.IgnetHttpBoundary
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.IncognitoNetworkHooks
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.IncognitoSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.KmpMossBoundary
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.PrivacyHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.PrivacyStructuralResolver
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.bapis.bilibili.app.playerunite.v1.CodeType
import com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReq
import com.bapis.bilibili.app.playerunite.v1.VideoVod
import com.bapis.bilibili.metadata.device.Device
import com.bilibili.lib.ighttp.IgHttpRequest
import kmpfixture.modern.Descriptor
import kmpfixture.modern.GrpcEngine
import kmpfixture.modern.Handler
import kmpfixture.modern.KMossException
import kmpfixture.modern.Service
import kmossfixture.KReplyMoss
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.reflect.KClass

class Phase6WireAndPrivacyTest {
    private val loader: ClassLoader = javaClass.classLoader!!

    private fun env(
        customLoader: ClassLoader = loader,
        registrar: HookRegistrar = PlayerPortTestRegistrar(),
        statuses: MutableMap<String, String> = mutableMapOf(),
        evidence: MutableList<Pair<String, FeatureRuntimeStage>> = mutableListOf()
    ) = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = customLoader,
        hookPoints = HookPointRegistry(customLoader),
        registrar = registrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { k, v -> statuses[k] = v },
        runtimeEvidence = { id, stage, _ -> evidence += id to stage }
    )

    private class AdaptedForwardingRegistrar(
        private val target: PlayerPortTestRegistrar,
        private val targetLoader: ClassLoader
    ) : HookRegistrar by target {
        override fun adapted(
            id: String,
            point: VersionAdapter.HookPoint,
            exceptionPolicy: HookExceptionPolicy,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            val owner = Class.forName(point.className, false, targetLoader)
            val params = point.paramClassNames.orEmpty().map { Class.forName(it, false, targetLoader) }.toTypedArray()
            target.exact(id, owner, point.methodName, *params, block = block)
        }
    }

    private fun wireSurface(): KmpGrpcWireAdapter.WireSurface = requireNotNull(
        KmpGrpcWireAdapter.inspectEntry(GrpcEngine::class.java.declaredMethods.single { it.name == "a" })
    )

    // --- [6A] KmpMethodDescriptorResolver & KmpGrpcWireAdapter ---

    private object DummyDeserStrategy : DeserializationStrategy<String>

    private class DeserDescriptor(
        val k: KClass<*>,
        val d: DeserializationStrategy<*>
    ) {
        fun getPackageName(): String = "bilibili.app.interface.v1"
        fun getServiceName(): String = "Search"
        fun getMethodName(): String = "DefaultWords"
        override fun toString(): String = "obf.D@abc1"
    }

    private class DeserEngine {
        @Suppress("UNUSED_PARAMETER")
        fun dispatch(service: Any, descriptor: DeserDescriptor, req: ByteArray, handler: Handler) = Unit
    }

    private class LeadingDotPkg {
        private val a = ".bilibili.v1"
        private val b = "Svc"
        private val c = "Mtd"
        private val k: KClass<*> = String::class
        private val s: DeserializationStrategy<*> = DummyDeserStrategy
        override fun toString(): String = "obf.L@12"
    }

    private class WhitespacePkg {
        private val a = "bilibili.v1 bad"
        private val b = "Svc"
        private val c = "Mtd"
        private val k: KClass<*> = String::class
        private val s: DeserializationStrategy<*> = DummyDeserStrategy
        override fun toString(): String = "obf.W@13"
    }

    private class SlashInSvc {
        private val a = "bilibili.v1"
        private val b = "Bad/Svc"
        private val c = "Mtd"
        private val k: KClass<*> = String::class
        private val s: DeserializationStrategy<*> = DummyDeserStrategy
        override fun toString(): String = "obf.S@14"
    }

    @Test
    fun `descriptor resolver and wire adapter support DeserializationStrategy and reject invalid shapes`() {
        val inspected = KmpGrpcWireAdapter.inspectEntry(DeserEngine::class.java.declaredMethods.single { it.name == "dispatch" })
        assertNotNull(inspected)
        assertTrue(KmpMethodDescriptorResolver.isDescriptorType(DeserDescriptor::class.java))
        val desc = DeserDescriptor(String::class, DummyDeserStrategy)
        assertEquals("bilibili.app.interface.v1.Search/DefaultWords", KmpMethodDescriptorResolver.resolve(desc))
        assertEquals("bilibili.app.interface.v1.Search/DefaultWords", KmpMethodDescriptorResolver.resolve(desc))
        KmpMethodDescriptorResolver.clearCaches()
        assertEquals("bilibili.app.interface.v1.Search/DefaultWords", KmpMethodDescriptorResolver.resolve(desc))
        assertNull(KmpMethodDescriptorResolver.resolve(LeadingDotPkg()))
        assertNull(KmpMethodDescriptorResolver.resolve(WhitespacePkg()))
        assertNull(KmpMethodDescriptorResolver.resolve(SlashInSvc()))
    }

    // --- [6A] Pure-byte step mirror reset + KotlinMossChannel supersededBy guard ---

    data class CodecEchoReply(val body: String) {
        fun toByteArray(): ByteArray = body.toByteArray(Charsets.UTF_8)

        companion object {
            var parseCalls = 0

            @JvmStatic
            fun parseFrom(bytes: ByteArray): CodecEchoReply {
                parseCalls++
                return CodecEchoReply(String(bytes, Charsets.UTF_8))
            }
        }
    }

    private class FakeKotlinMossEndpoint {
        @Suppress("UNUSED_PARAMETER")
        fun mainList(
            request: Any,
            serializer: SerializationStrategy<Any>,
            decoder: DeserializationStrategy<Any>,
            handler: KReplyMoss.Handler,
            protoBuf: ProtoBuf
        ) = Unit
    }

    private class DummyCodecStrategy : SerializationStrategy<Any>, DeserializationStrategy<Any>

    @Test
    fun `raw byte step clears stale mirror and deferred bind sets supersededBy for KotlinMoss`() {
        val registrar = PlayerPortTestRegistrar()
        val environment = env(registrar = registrar)
        val wireActive = AtomicBoolean(false)
        CodecEchoReply.parseCalls = 0

        // 1. Install KotlinMoss fallback with supersededBy = wireActive.
        val members = requireNotNull(KotlinMossBridgeMembers.resolve(loader))
        val entry = requireNotNull(KotlinMossBridgeMembers.callbackEntry(FakeKotlinMossEndpoint::class.java, "mainList"))
        assertTrue(
            KotlinMossChannel.install(
                environment = environment,
                loader = loader,
                members = members,
                javaMossClassName = "kmossfixture.ReplyMoss",
                rpc = "mainList",
                javaReplyClass = CodecEchoReply::class.java,
                hookId = "phase6.kmoss.mainList",
                what = "phase6",
                logKey = "phase6",
                resolvedEntry = entry,
                supersededBy = wireActive
            ) { it }
        )

        val sink = object : KReplyMoss.Handler {
            override fun onNext(reply: Any?) = Unit
        }
        val strategy = DummyCodecStrategy()
        // Before wire activation: KotlinMoss hook wraps args[3] in forwarded callArgs.
        val activeArgs = arrayOf<Any?>("req", strategy, strategy, sink, ProtoBuf())
        val wrappedSink = registrar.invoke("phase6.kmoss.mainList", FakeKotlinMossEndpoint(), activeArgs) { callArgs -> callArgs[3] }
        assertNotSame(sink, wrappedSink)

        // 2. Queue deferred Java step followed by raw-byte step, followed by downstream Java step.
        assertFalse(
            KmpGrpcWireChannel.installOrDefer(
                environment = environment,
                surface = null,
                javaMossClassName = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss",
                rpc = "mainList",
                javaReplyClass = CodecEchoReply::class.java,
                what = "step1-java",
                logKey = "p6_java1",
                wireActive = wireActive
            ) { msg -> CodecEchoReply((msg as CodecEchoReply).body + "|j1") }
        )
        KmpGrpcWireChannel.deferRawBytes(
            environment = environment,
            rpcFullName = "bilibili.main.community.reply.v1.Reply/MainList",
            what = "step2-raw",
            logKey = "p6_raw",
            transformRawRequest = { bytes -> (String(bytes, Charsets.UTF_8) + "|rawReq").toByteArray(Charsets.UTF_8) }
        ) { bytes -> (String(bytes, Charsets.UTF_8) + "|rawResp").toByteArray(Charsets.UTF_8) }
        assertFalse(
            KmpGrpcWireChannel.installOrDefer(
                environment = environment,
                surface = null,
                javaMossClassName = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss",
                rpc = "mainList",
                javaReplyClass = CodecEchoReply::class.java,
                what = "step3-java",
                logKey = "p6_java2"
            ) { msg -> CodecEchoReply((msg as CodecEchoReply).body + "|j2") }
        )

        val bound = KmpGrpcWireChannel.bindDeferred(environment, wireSurface())
        assertEquals(3, bound)
        assertTrue(wireActive.get())

        // After wire activation: KotlinMoss hook skips immediately and leaves sink untouched!
        val skippedArgs = arrayOf<Any?>("req", strategy, strategy, sink, ProtoBuf())
        val unhookedSink = registrar.invoke("phase6.kmoss.mainList", FakeKotlinMossEndpoint(), skippedArgs) { callArgs -> callArgs[3] }
        assertSame(sink, unhookedSink)

        val descriptor = Descriptor("bilibili.main.community.reply.v1", "Reply", "MainList", String::class, strategy)
        var sentRequest: ByteArray? = null
        var receivedReply: ByteArray? = null
        val wireSink = object : Handler {
            override fun onNext(bytes: ByteArray?) { receivedReply = bytes }
            override fun onCompleted() = Unit
            override fun onError(error: KMossException) = Unit
        }
        registrar.invoke(
            "kmp.grpc.wire.channel",
            GrpcEngine(emptyList()),
            arrayOf(Service("grpc.biliapi.net"), descriptor, "req0".toByteArray(), wireSink, null)
        ) { callArgs ->
            sentRequest = callArgs[2] as ByteArray
            (callArgs[3] as Handler).onNext("in".toByteArray())
            null
        }
        assertEquals("req0|rawReq", String(requireNotNull(sentRequest), Charsets.UTF_8))
        assertEquals("in|j1|rawResp|j2", String(requireNotNull(receivedReply), Charsets.UTF_8))
        // Step1 parsed once + Step3 had to re-parse after rawResp replaced currentBytes (2 total).
        assertEquals(2, CodecEchoReply.parseCalls)
    }

    // --- [6A] ProtobufBuilderPlan toBuilder() + ReplyTree indexed getter fallback ---

    open class ErasedBuilderBase(var label: String, var preserved: String) {
        open fun build(): Any = error("overridden")
    }

    class ConcreteErasedBuilder(label: String, preserved: String) : ErasedBuilderBase(label, preserved) {
        fun setLabel(value: String): ConcreteErasedBuilder {
            label = value
            return this
        }

        override fun build(): ErasedToBuilderMessage = ErasedToBuilderMessage(label, preserved)
    }

    class ErasedToBuilderMessage(val label: String, val preserved: String) {
        fun toBuilder(): ErasedBuilderBase = ConcreteErasedBuilder(label, preserved)

        companion object {
            private val DEFAULT = ErasedToBuilderMessage("", "")
            @JvmStatic fun getDefaultInstance(): ErasedToBuilderMessage = DEFAULT
        }
    }

    class RawReplyInfoList(initial: List<IndexedReplyItem> = emptyList()) : ArrayList<IndexedReplyItem>(initial)

    class IndexedReplyItem(val id: String, val children: List<IndexedReplyItem> = emptyList()) {
        fun getRepliesList(): RawReplyInfoList = RawReplyInfoList(children)
        fun getReplies(index: Int): IndexedReplyItem = children[index]

        class Builder(source: IndexedReplyItem) {
            private var children = source.children.toList()
            fun clearReplies(): Builder { children = emptyList(); return this }
            fun addAllReplies(items: Iterable<*>): Builder {
                children = items.filterIsInstance<IndexedReplyItem>()
                return this
            }
            fun build() = IndexedReplyItem(sourceId, children)
            private val sourceId = source.id
        }

        companion object {
            @JvmStatic fun newBuilder(source: IndexedReplyItem) = Builder(source)
        }
    }

    @Test
    fun `protobufBuilderPlan supports erased toBuilder and replyTree resolves stripped generic list via indexed getter`() {
        val plan = requireNotNull(ProtobufBuilderPlan.resolve(ErasedToBuilderMessage::class.java))
        val setLabel = requireNotNull(plan.method("setLabel", String::class.java))
        val src = ErasedToBuilderMessage("old", "keep-me")
        val updated = plan.edit(src) { b -> setLabel.invoke(b, "new") } as ErasedToBuilderMessage
        assertNotSame(src, updated)
        assertEquals("new", updated.label)
        assertEquals("keep-me", updated.preserved)

        val dropChild = IndexedReplyItem("drop")
        val keepChild = IndexedReplyItem("keep")
        val root = IndexedReplyItem("root", listOf(dropChild, keepChild))

        val visited = mutableListOf<String>()
        ProtobufReplyTreeVisitor(IndexedReplyItem::class.java) { item ->
            visited += (item as IndexedReplyItem).id
        }.visit(root)
        assertEquals(listOf("drop", "keep"), visited)

        val rewriter = ProtobufReplyTreeRewriter(IndexedReplyItem::class.java) { list ->
            list.filter { (it as IndexedReplyItem).id == "drop" }.toSet()
        }
        val result = rewriter.rewrite(root)
        assertEquals(1, result.removed)
        val rewritten = result.message as IndexedReplyItem
        assertNotSame(root, rewritten)
        assertEquals(listOf("keep"), rewritten.getRepliesList().map { it.id })
    }

    // --- [6C] KmpMossBoundary setFts & fail-closed ---

    class DeviceWithoutSetFts : com.bapis.bilibili.metadata.FakeMessageBase<DeviceWithoutSetFts, DeviceWithoutSetFts.Builder>(arrayOf("", "")) {
        override fun newBuilderInstance() = Builder()

        class Builder : com.bapis.bilibili.metadata.FakeBuilderBase<DeviceWithoutSetFts, Builder>(2) {
            fun setBuvid(v: String) = apply { fields[0] = v }
            fun setFp(v: String) = apply { fields[1] = v }
            fun setFpLocal(v: String) = this
            fun setFpRemote(v: String) = this
            fun setGuestId(v: String) = this
            override fun create(fields: Array<String>) = DeviceWithoutSetFts()
        }

        companion object {
            @JvmStatic fun parseFrom(bytes: ByteArray) = DeviceWithoutSetFts()
            @JvmStatic fun newBuilder() = Builder()
        }
    }

    @Test
    fun `identitySanitizer fails closed when Device setFts is missing and IgnetHttpBoundary fails when engine present without request`() {
        val noFtsLoader = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                if (name == "com.bapis.bilibili.metadata.device.Device") DeviceWithoutSetFts::class.java
                else super.loadClass(name, resolve)
        }
        assertNull(KmpMossBoundary.identitySanitizer(noFtsLoader))

        val brokenIgnetLoader = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
                PrivacyChannelAdapter.IGNET_ENGINE -> Any::class.java
                PrivacyChannelAdapter.IGNET_REQUEST -> throw ClassNotFoundException(name)
                PrivacyChannelAdapter.IGNET_STREAM_PROVIDER -> IgHttpRequest.StreamDataProvider::class.java
                else -> super.loadClass(name, resolve)
            }
        }
        assertNull(
            IgnetHttpBoundary(IncognitoSession().apply { enter() }) { true }
                .prepare(env(customLoader = brokenIgnetLoader))
        )
    }

    // --- [6C] IncognitoNetworkHooks raw body sanitization ---

    @Test
    fun `nonFormBody raw sanitizer cleans form and json and rejects binary payloads`() {
        val session = IncognitoSession().apply { enter() }
        val identity = requireNotNull(session.identity())
        val hooks = IncognitoNetworkHooks(session)

        val cleanedForm = hooks.sanitizeRawRequestBody(
            "access_key=secret&buvid=real&aid=100".toByteArray(Charsets.UTF_8),
            "application/x-www-form-urlencoded",
            identity
        ) { "signed" }
        assertEquals("buvid=${identity.buvid}&aid=100", String(cleanedForm, Charsets.UTF_8))

        val cleanedJson = hooks.sanitizeRawRequestBody(
            """{"buvid":999,"session_id":"real-s","aid":42}""".toByteArray(Charsets.UTF_8),
            "application/json; charset=utf-8",
            identity
        ) { error("unused") }
        assertFalse(String(cleanedJson, Charsets.UTF_8).contains("real-s"))
        assertTrue(String(cleanedJson, Charsets.UTF_8).contains(identity.buvid))

        assertThrows(IOException::class.java) {
            hooks.sanitizeRawRequestBody(byteArrayOf(0x01, 0x00, 0x02), "application/octet-stream", identity) { "" }
        }
        assertThrows(IOException::class.java) {
            hooks.sanitizeRawRequestBody(byteArrayOf(0xff.toByte(), 0xfe.toByte()), null, identity) { "" }
        }
        val cleanedProblemJson = hooks.sanitizeRawRequestBody(
            """{"access_key":"tok","buvid":"real","aid":7}""".toByteArray(Charsets.UTF_8),
            "application/problem+json",
            identity
        ) { error("unused") }
        assertFalse(String(cleanedProblemJson, Charsets.UTF_8).contains("tok"))
        assertTrue(String(cleanedProblemJson, Charsets.UTF_8).contains(identity.buvid))

        val cleanedTextForm = hooks.sanitizeRawRequestBody(
            "access_key=tok&buvid=real&aid=8".toByteArray(Charsets.UTF_8),
            "text/plain; charset=utf-8",
            identity
        ) { error("unused") }
        assertEquals("buvid=${identity.buvid}&aid=8", String(cleanedTextForm, Charsets.UTF_8))

        assertThrows(IOException::class.java) {
            hooks.sanitizeRawRequestBody(
                "raw-secret-token-without-delimiters".toByteArray(Charsets.UTF_8),
                "text/plain",
                identity
            ) { "" }
        }
    }

    // --- [6C] PlayerCodecForce Wire-First & null-engine count compatibility ---

    @Test
    fun `playerCodecForce supports wire-first on PlayViewUnite and preserves counts when wireEngine is null`() {
        val baselineReg = PlayerPortTestRegistrar()
        val baselineRes = PlayerCodecForceFeatureInstaller(
            PlayerCodecPreference.AV1.value,
            PlayerDecodeMode.FOLLOW_HOST.value,
            kmpGrpcWireEngine = null
        ).install(env(registrar = baselineReg))
        assertEquals(FeatureInstallResult.Installed(4), baselineRes)

        val wirePoint = KmpGrpcWireAdapter.point(GrpcEngine::class.java.declaredMethods.single { it.name == "a" })
        val wireReg = PlayerPortTestRegistrar()
        val statuses = mutableMapOf<String, String>()
        val wireRes = PlayerCodecForceFeatureInstaller(
            PlayerCodecPreference.AV1.value,
            PlayerDecodeMode.FOLLOW_HOST.value,
            kmpGrpcWireEngine = wirePoint
        ).install(env(registrar = wireReg, statuses = statuses))
        assertEquals(FeatureInstallResult.Installed(5), wireRes)
        assertTrue(wireReg.hooks.containsKey("kmp.grpc.wire.channel"))

        val descriptor = Descriptor(
            "bilibili.app.playerunite.v1",
            "Player",
            "PlayViewUnite",
            String::class,
            DummyCodecStrategy()
        )
        val srcReq = PlayViewUniteReq(VideoVod(CodeType.CODE_UNKNOWN, 16), "keep-field")
        var forwardedReq: PlayViewUniteReq? = null
        val sink = object : Handler {
            override fun onNext(bytes: ByteArray?) = Unit
            override fun onCompleted() = Unit
            override fun onError(error: KMossException) = Unit
        }
        wireReg.invoke(
            "kmp.grpc.wire.channel",
            GrpcEngine(emptyList()),
            arrayOf(Service("grpc.biliapi.net"), descriptor, srcReq.toByteArray(), sink, null)
        ) { callArgs ->
            forwardedReq = PlayViewUniteReq.parseFrom(callArgs[2] as ByteArray)
            (callArgs[3] as Handler).onNext(ByteArray(0))
            null
        }
        assertNotNull(forwardedReq)
        assertEquals(CodeType.CODEAV1, forwardedReq!!.getVod().getPreferCodecType())
        assertEquals(2064, forwardedReq!!.getVod().getFnval())
        assertEquals("keep-field", forwardedReq!!.unrelated)
    }

    // --- [6C] VideoRelate response getter guards KotlinMossChannel.raw{} & hasThrowable ---

    class FixtureRelateCard(private val cardGoto: String) {
        fun getGoto(): String = cardGoto
    }

    class FixtureRelateResponse(@JvmField var items: List<FixtureRelateCard>) {
        fun getItems(): List<FixtureRelateCard> = items
    }

    @Test
    fun `videoRelate response getter skips filtering under KotlinMossChannel raw and when hasThrowable`() {
        val basePoints = requireNotNull(VersionAdapter.locateVideoRelate(loader))
        val customPoints = basePoints.copy(
            responseItemGetters = listOf(VersionAdapter.HookPoint(FixtureRelateResponse::class.java.name, "getItems", emptyList())),
            cardCaseGetters = emptyList(),
            gotoGetters = listOf(VersionAdapter.HookPoint(FixtureRelateCard::class.java.name, "getGoto", emptyList())),
            cardTypeGetters = emptyList(),
            relateCardTypeGetters = emptyList(),
            fromSourceTypeGetters = emptyList(),
            fromSourceTypeChains = emptyList(),
            publishTextChains = emptyList()
        )
        val rawReg = PlayerPortTestRegistrar()
        val recording = AdaptedForwardingRegistrar(rawReg, loader)
        val res = VideoRelateFilterFeatureInstaller(
            hiddenTypes = setOf("game"),
            minDurationSeconds = 0,
            maxDurationSeconds = 0,
            points = customPoints
        ).install(env(registrar = recording))
        assertTrue(res is FeatureInstallResult.Installed)

        val cards = listOf(FixtureRelateCard("game"), FixtureRelateCard("av"))
        val resp = FixtureRelateResponse(cards)
        val rawResult = KotlinMossChannel.raw {
            rawReg.invoke("video.relate.response.0", resp) { resp.getItems() }
        } as List<*>
        assertEquals(2, rawResult.size)

        val normalResult = rawReg.invoke("video.relate.response.0", resp) { resp.getItems() } as List<*>
        assertEquals(1, normalResult.size)

        assertThrows(IllegalStateException::class.java) {
            rawReg.invoke("video.relate.response.0", resp) { throw IllegalStateException("host_boom") }
        }
    }

    // --- [8C Privacy] Tier-B structural fallbacks & WatchLater te6.b isolation ---

    private class FakePlaySet
    private open class FakeEventBase
    private class FakeEventInit(@JvmField var g: Boolean = false) : FakeEventBase()
    private class FakeEventModify : FakeEventBase()
    private class FakeEditorViewModel { @Suppress("UNUSED_PARAMETER") fun a2(e: FakeEventBase) = Unit }
    private class FakeEditorFragment { fun Wo() = Unit }
    private class FakeToast

    private class RenamedStateMachine {
        @Suppress("UNUSED_PARAMETER")
        fun renamedWhitelist(a: String, c: Continuation<*>): Any = 1

        @Suppress("UNUSED_PARAMETER")
        fun renamedCreate(a: String, b: String, c: String, d: String, e: Int, cont: Continuation<*>): Any = Unit

        @Suppress("UNUSED_PARAMETER")
        fun renamedEdit(a: String, b: String, c: String, d: String, e: Int, id: Long, cont: Continuation<*>): Any = Unit
    }

    private class RenamedWatchLaterManager {
        @Suppress("UNUSED_PARAMETER") fun e1(a: Activity, b: String, c: String, d: String, e: Int, f: Boolean, g: Int) = Unit
        @Suppress("UNUSED_PARAMETER") fun e2(a: Fragment, b: String, c: String, d: String, e: Int, f: Int) = Unit
        @Suppress("UNUSED_PARAMETER") fun e3(a: Activity, b: String, c: String, d: Int, e: Int) = Unit
        @Suppress("UNUSED_PARAMETER") fun r1(a: String, b: String, c: String, cont: Continuation<*>): Any = Unit
        @Suppress("UNUSED_PARAMETER") fun r2(a: String, b: String, cont: Continuation<*>): Any = Unit

        companion object {
            @JvmField val singletonInst = RenamedWatchLaterManager()
            @JvmStatic @Suppress("UNUSED_PARAMETER") fun c1(a: String, b: Activity, c: String, d: String, e: Int, f: Boolean, g: Int) = Unit
            @JvmStatic @Suppress("UNUSED_PARAMETER") fun c2(a: String, b: Fragment, c: String, d: String, e: Int, f: Int) = Unit
            @JvmStatic @Suppress("UNUSED_PARAMETER") fun c3(a: String, b: Activity, c: String, d: Int, e: Int) = Unit
        }
    }

    private fun allocateDummyGuestLibraryRuntime(): GuestLibraryRuntime {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val instanceField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = instanceField.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, GuestLibraryRuntime::class.java) as GuestLibraryRuntime
    }

    @Test
    fun `tier b guest watch later and folder editor install with structural fallbacks and missing te6`() {
        val library = allocateDummyGuestLibraryRuntime()
        val watchLaterLoader = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
                "tv.danmaku.bili.ui.watchlater.widget.WatchLaterManagerV2" -> RenamedWatchLaterManager::class.java
                "tv.danmaku.bili.ui.watchlater.data.WatchLaterAddToast" -> FakeToast::class.java
                "te6.b" -> throw ClassNotFoundException(name)
                else -> super.loadClass(name, resolve)
            }
        }
        val regWatch = PlayerPortTestRegistrar()
        val statuses = mutableMapOf<String, String>()
        GuestNativeWatchLaterAddHooks(library) { true }.install(
            env(customLoader = watchLaterLoader, registrar = regWatch, statuses = statuses)
        )
        assertEquals("adapted", statuses["guest_native_watch_later_add"])
        assertTrue(regWatch.hooks.containsKey("guest.native.later.entry.j"))
        assertTrue(regWatch.hooks.containsKey("guest.native.later.entry.k"))
        assertTrue(regWatch.hooks.containsKey("guest.native.later.entry.p"))
        assertTrue(regWatch.hooks.containsKey("guest.native.later.add.i"))
        assertTrue(regWatch.hooks.containsKey("guest.native.later.add.u"))
        assertFalse(regWatch.hooks.containsKey("guest.native.later.entry.legacy"))

        val editorLoader = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
                "com.bilibili.playset.editor.PlaySetEditorStateMachine" -> RenamedStateMachine::class.java
                "com.bilibili.playset.editor.PlaySetEditorViewModel" -> FakeEditorViewModel::class.java
                "com.bilibili.playset.editor.PlaySetEditorFragment" -> FakeEditorFragment::class.java
                "com.bilibili.playset.api.PlaySet" -> FakePlaySet::class.java
                "ss3.c" -> FakeEventBase::class.java
                "ss3.c\$c" -> FakeEventInit::class.java
                "ss3.c\$f" -> FakeEventModify::class.java
                else -> super.loadClass(name, resolve)
            }
        }
        val regEditor = PlayerPortTestRegistrar()
        GuestNativeFolderEditorHooks(library) { true }.install(
            env(customLoader = editorLoader, registrar = regEditor, statuses = statuses)
        ) {}
        assertEquals("adapted-v2", statuses["guest_native_folder_editor"])
        assertTrue(regEditor.hooks.containsKey("guest.favorite.native.editor_whitelist"))
        assertTrue(regEditor.hooks.containsKey("guest.favorite.native.editor_create"))
        assertTrue(regEditor.hooks.containsKey("guest.favorite.native.editor_edit"))
    }

    private class RenamedCallback {
        fun onFollowSuccess(): Boolean = true
        fun onUnFollowSuccess(): Boolean = true
    }

    private class RenamedFollowFlowHelper {
        private val renamedMid: Long = 42L
        private val renamedCb: RenamedCallback? = null
    }

    private class RenamedMenuService {
        private val renamedActivity: Activity? = null
    }

    @Test
    fun `structural resolver finds unique renamed follow menu and history helpers`() {
        assertNotNull(
            PrivacyStructuralResolver.uniqueField(RenamedFollowFlowHelper::class.java) {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == Long::class.javaPrimitiveType
            }
        )
        assertNotNull(
            PrivacyStructuralResolver.uniqueField(RenamedFollowFlowHelper::class.java) {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    KavaMemberLookup.methodOrNull(it.type, "onFollowSuccess") != null &&
                    KavaMemberLookup.methodOrNull(it.type, "onUnFollowSuccess") != null
            }
        )
        assertNotNull(
            PrivacyStructuralResolver.uniqueField(RenamedMenuService::class.java) {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) && Activity::class.java.isAssignableFrom(it.type)
            }
        )
    }
}
