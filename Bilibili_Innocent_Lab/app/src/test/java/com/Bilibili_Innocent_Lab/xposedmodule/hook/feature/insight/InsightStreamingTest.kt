package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.*
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import kmpfixture.modern.Descriptor
import kmpfixture.modern.GrpcEngine
import kmpfixture.modern.Handler
import kmpfixture.modern.KMossException
import kmpfixture.modern.Service
import kotlinx.serialization.SerializationStrategy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class InsightStreamingTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val streamContext = InsightContext(
        InsightVideo("BV1xx411c7mD", 1),
        300,
        "title",
        "desc",
        listOf(
            InsightCue(0.0, 20.0, "开始"),
            InsightCue(45.0, 60.0, "原理"),
            InsightCue(90.0, 110.0, "结论")
        )
    )

    private val source = AgentModelSource(1, "https://example.test/v1", "secret", "model")
    private val video = InsightVideo("BV1xx411c7mD", 1)
    private val repoContext = InsightContext(
        video,
        900,
        "title",
        "description",
        listOf(
            InsightCue(0.0, 300.0, "开始"),
            InsightCue(300.0, 600.0, "原理"),
            InsightCue(600.0, 900.0, "结论")
        )
    )

    private fun payload(text: String) = JSONObject().put(
        "choices",
        JSONArray().put(
            JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("role", "assistant").put("content", text))
        )
    ).toString()

    private fun client(emit: ((String) -> Unit) -> String) = AgentModelClient(object : AgentHttpTransport {
        override fun post(source: AgentModelSource, body: ByteArray, timeoutMs: Int, cancelled: () -> Boolean): String =
            error("must request streaming")

        override fun postStreaming(
            source: AgentModelSource,
            body: ByteArray,
            timeoutMs: Int,
            cancelled: () -> Boolean,
            onDelta: (String) -> Unit
        ): String {
            assertTrue(JSONObject(body.toString(Charsets.UTF_8)).getBoolean("stream"))
            return payload(emit(onDelta))
        }
    })

    private fun thumbnailData() = JSONObject().put("img_x_len", 2).put("img_y_len", 2)
        .put("image", JSONArray().put("//bimp.hdslb.com/a.jpg").put("//bimp.hdslb.com/b.jpg"))

    internal object DummyStrategy : SerializationStrategy<Any>

    internal class StrippedDescriptor(
        pkg: String,
        svc: String,
        mtd: String
    ) : Descriptor(pkg, svc, mtd, Any::class, DummyStrategy) {
        override fun toString(): String = "kmp.Descriptor@${Integer.toHexString(System.identityHashCode(this))}"
    }

    internal class CodecReq(val id: Long, val tag: String = "orig") {
        fun toByteArray(): ByteArray = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { s -> s.writeLong(id); s.writeUTF(tag) }
        }.toByteArray()

        companion object {
            @JvmStatic
            fun parseFrom(bytes: ByteArray): CodecReq =
                DataInputStream(ByteArrayInputStream(bytes)).use { CodecReq(it.readLong(), it.readUTF()) }
        }
    }

    internal class CodecReply(val marker: String) {
        fun toByteArray(): ByteArray = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { s -> s.writeUTF(marker) }
        }.toByteArray()

        companion object {
            @JvmStatic
            fun parseFrom(bytes: ByteArray): CodecReply =
                DataInputStream(ByteArrayInputStream(bytes)).use { CodecReply(it.readUTF()) }
        }
    }

    private fun wireEnginePoint(): VersionAdapter.HookPoint {
        val method = GrpcEngine::class.java.getDeclaredMethod(
            "a",
            Service::class.java,
            Descriptor::class.java,
            ByteArray::class.java,
            Handler::class.java,
            Any::class.java
        )
        return KmpGrpcWireAdapter.point(method)
    }

    private fun invokeWire(
        registrar: PlayerPortTestRegistrar,
        pkg: String,
        svc: String,
        mtd: String,
        reqBytes: ByteArray,
        replyBytes: ByteArray
    ): Pair<ByteArray, ByteArray?> {
        var sentReq = reqBytes
        var received: ByteArray? = null
        val handler = object : Handler {
            override fun onNext(bytes: ByteArray?) { received = bytes }
            override fun onCompleted() = Unit
            override fun onError(error: KMossException) = Unit
        }
        val descriptor = StrippedDescriptor(pkg, svc, mtd)
        val args = arrayOf<Any?>(Service("host"), descriptor, reqBytes, handler, null)
        registrar.invoke("kmp.grpc.wire.channel", GrpcEngine(emptyList()), args) { passed ->
            sentReq = passed[2] as ByteArray
            (passed[3] as Handler).onNext(replyBytes)
            null
        }
        return sentReq to received
    }

    @Test
    fun verifyStreamTextFormattingRevealAndThumbnailIndex() {
        // answer appears before JSON closes and never shows partial tags or escapes
        run {
            val stream = InsightStreamText(InsightMode.ASK, streamContext)
            assertEquals("原理", stream.append("{\"answer\":\"原理 [[t:"))
            assertEquals("原理 0:45", stream.append("45]]"))
            assertEquals("原理 0:45 在这里", stream.append(" 在这里\"}"))
        }

        // every character boundary handles unicode escapes quoted text and surrogate pairs
        run {
            val raw = """{"answer":"你好\n\"引用\" \uD83C\uDF19 [[t:45]]。"}"""
            val expected = "你好\n\"引用\" 🌙 0:45。"
            for (split in 0..raw.length) {
                val stream = InsightStreamText(InsightMode.ASK, streamContext)
                val first = stream.append(raw.take(split))
                assertFalse(first.lastOrNull()?.isHighSurrogate() == true)
                assertFalse(first.contains("[["))
                assertFalse(first.contains("\\u"))
                assertEquals(expected, stream.append(raw.drop(split)))
            }
        }

        // timed summary uses cue boundaries and never exposes structural punctuation
        run {
            val stream = InsightStreamText(InsightMode.SUMMARY, streamContext)
            assertEquals(
                "概述\n\n• 0:00 开始：原",
                stream.append("""{"overview":"概述","sections":[{"start_cue":0,"title":"开始","summary":"原""")
            )
            assertEquals("概述\n\n• 0:00 开始：原理", stream.append("""理"}]}"""))
        }

        // topic field order may vary and complete serialization remnants are hidden
        run {
            val stream = InsightStreamText(InsightMode.COMMENTS, streamContext)
            assertEquals("方向\n观点", stream.append("""{"topics":[{"summary":"观点},{","title":"方向"}]}"""))
        }

        // untimed summary and invalid time references never acquire a timestamp
        run {
            val untimed = InsightStreamText(InsightMode.SUMMARY, streamContext.copy(cues = emptyList()))
            assertEquals("概述\n\n• 具体结论", untimed.append("""{"overview":"概述","points":["具体结论"]}"""))
            val ask = InsightStreamText(InsightMode.ASK, streamContext)
            assertEquals("超范围  未覆盖", ask.append("""{"answer":"超范围 [[t:999]] 未覆盖 [[t:30]]"}"""))
        }

        // stream preview has the same bounded output budget as the final contract
        run {
            val stream = InsightStreamText(InsightMode.ASK, streamContext)
            assertTrue(runCatching { stream.append("x".repeat(InsightProtocol.MAX_OUTPUT + 1)) }.isFailure)
        }

        // InsightSummaryText: legacy adjacent tail citations become one leading representative time
        run {
            val raw = "主题概述\n\n• 提出关卡设计的核心是微型叙事。 [[t:168]] [[t:498]]\n\n• 第二个论点 [[t:600]]"
            val formatted = InsightSummaryText.leadingTimes(raw)
            assertEquals("主题概述\n\n• [[t:168]] 提出关卡设计的核心是微型叙事。\n\n• [[t:600]] 第二个论点", formatted)
            assertEquals(listOf(168, 600), InsightTimestamp.links(formatted, 1200, true).map { it.second })
            assertEquals(formatted, InsightSummaryText.leadingTimes(formatted))
        }

        // InsightSummaryText: timestamps stay with their own points and untimed summaries remain readable
        run {
            assertEquals(
                "概述\n\n• [[t:12]] 第一条\n\n• [[t:90]] 第二条",
                InsightSummaryText.leadingTimes("概述\n\n• 第一条 [[t:12]]\n\n• [[t:90]] 第二条")
            )
            val plain = "元信息\n\n• 简介中的事实"
            assertEquals(plain, InsightSummaryText.leadingTimes(plain))
        }

        // InsightTextReveal: new glyphs fade individually while settled prefix stays visible
        run {
            val reveal = InsightTextReveal()
            val first = reveal.update("你好", 100)
            assertEquals(2, first.size)
            assertEquals(0f, first.first().alpha(100), .001f)
            assertTrue(first.first().alpha(190) > first.last().alpha(190))
            assertFalse(reveal.active(500))
            val next = reveal.update("你好世界", 500)
            assertEquals(listOf(2, 3), next.map { it.start })
        }

        // InsightTextReveal: surrogate pairs and combining marks share glyph ranges
        run {
            val ranges = InsightTextReveal().update("🌙e\u0301中", 0)
            assertEquals(listOf(0 to 2, 2 to 4, 4 to 5), ranges.map { it.start to it.end })
        }

        // InsightTextReveal: combining mark arriving in another chunk replaces prior glyph span
        run {
            val reveal = InsightTextReveal()
            reveal.update("e", 0)
            assertEquals(listOf(0 to 2), reveal.update("e\u0301", 10).map { it.start to it.end })
        }

        // InsightTextReveal: large bursts have a bounded tail and settle without long typing delays
        run {
            val reveal = InsightTextReveal()
            val spans = reveal.update("字".repeat(12_000), 1000)
            assertEquals(96, spans.size)
            assertTrue(reveal.active(1000))
            assertFalse(reveal.active(1400))
            assertTrue(spans.all { it.start >= 11_904 })
        }

        // InsightTextReveal: repair replaces the old suffix and reset clears all animation state
        run {
            val reveal = InsightTextReveal()
            reveal.update("旧内容", 100)
            val changed = reveal.update("新", 110)
            assertTrue(changed.all { it.end <= 1 })
            reveal.clear()
            assertFalse(reveal.active(110))
        }

        // InsightThumbnailIndex: binary header removal and sprite/cell selection
        run {
            val seconds = listOf(0, 5, 10, 15, 20, 25)
            val bytes = (listOf(0) + seconds).flatMap { listOf((it shr 8).toByte(), it.toByte()) }.toByteArray()
            val index = InsightThumbnailIndex.parse(thumbnailData(), bytes)
            assertEquals(seconds, index.times)
            assertEquals(InsightThumbnailIndex.Cell(0, 0, 0), index.cell(0))
            assertEquals(InsightThumbnailIndex.Cell(0, 1, 0), index.cell(9))
            assertEquals(InsightThumbnailIndex.Cell(0, 1, 1), index.cell(15))
            assertEquals(InsightThumbnailIndex.Cell(1, 0, 0), index.cell(22))
            assertEquals(InsightThumbnailIndex.Cell(1, 1, 0), index.cell(100))
        }

        // InsightThumbnailIndex: inline preview indexes and malformed/unsafe sources are bounded
        run {
            val data = thumbnailData().put("index", JSONArray(listOf(0, 12, 40)))
            assertEquals(InsightThumbnailIndex.Cell(0, 0, 1), InsightThumbnailIndex.parse(data, byteArrayOf()).cell(40))
            for (invalid in listOf(
                thumbnailData().put("img_x_len", 0),
                thumbnailData().put("image", JSONArray().put("https://evil.test/image")),
                thumbnailData().put("index", JSONArray(listOf(10, 0)))
            )) {
                assertTrue(runCatching { InsightThumbnailIndex.parse(invalid, byteArrayOf(0, 0, 0, 0)) }.isFailure)
            }
            assertTrue(runCatching { InsightThumbnailIndex.parse(thumbnailData(), byteArrayOf(0, 0, 1)) }.isFailure)
        }
    }

    @Test
    fun verifyStreamingRepositoryAndInsightRpcWireFirst() {
        // preview arrives before completion and only validated result becomes a reusable cache
        run {
            val root = temporary.newFolder()
            val updates = mutableListOf<String>()
            var networkFinished = false
            val json = """{"topics":[{"title":"方向","summary":"具体讨论"}]}"""
            val repo = InsightRepository(source, InsightCache(root), { repoContext.copy(comments = listOf("评论")) }, client { emit ->
                json.forEach { emit(it.toString()); assertTrue(root.listFiles().orEmpty().isEmpty()) }
                networkFinished = true
                json
            })
            val result = repo.generate(video, InsightMode.COMMENTS, onProgress = {
                assertFalse(networkFinished)
                updates += it
            })
            assertTrue(updates.any { it.contains("具体") })
            assertEquals("方向\n具体讨论", result.output.text)
            assertEquals(result, repo.cached(video, InsightMode.COMMENTS))
            assertEquals(1, root.listFiles()!!.size)
        }

        // interrupted partial answer cannot become a cache entry
        run {
            val root = temporary.newFolder()
            val updates = mutableListOf<String>()
            val repo = InsightRepository(source, InsightCache(root), { repoContext }, client { emit ->
                emit("""{"overview":"已收到一部分""" )
                throw AgentModelException(AgentModelException.Reason.NETWORK)
            })
            assertTrue(runCatching { repo.generate(video, InsightMode.SUMMARY, onProgress = updates::add) }.isFailure)
            assertEquals(listOf("已收到一部分"), updates)
            assertTrue(root.listFiles().orEmpty().isEmpty())
            assertNull(repo.cached(video, InsightMode.SUMMARY))
        }

        // format repair keeps the first preview and stores only the corrected result
        run {
            val root = temporary.newFolder()
            val updates = mutableListOf<String>()
            var calls = 0
            val repo = InsightRepository(source, InsightCache(root), { repoContext.copy(comments = listOf("评论")) }, client { emit ->
                calls++
                val json = """{"topics":[{"title":"方向","summary":"${if (calls == 1) "坏片段},{" else "正确讨论"}"}]}"""
                emit(json)
                json
            })
            assertEquals("方向\n正确讨论", repo.generate(video, InsightMode.COMMENTS, onProgress = updates::add).output.text)
            assertTrue(updates.isNotEmpty())
            assertFalse(updates.contains(""))
            assertEquals(2, calls)
            assertFalse(root.listFiles()!!.single().readText().contains("坏片段"))
        }

        // cancellation discards late deltas without caching or completing the answer
        run {
            val root = temporary.newFolder()
            val updates = mutableListOf<String>()
            var cancelled = false
            val repo = InsightRepository(source, InsightCache(root), { repoContext }, client { emit ->
                emit("""{"answer":"早期正文""" )
                cancelled = true
                emit("迟到内容")
                "{}"
            })
            assertTrue(runCatching { repo.generate(video, InsightMode.ASK, "问题", onProgress = updates::add) { cancelled } }.isFailure)
            assertTrue(updates.none { it.contains("迟到") })
            assertTrue(root.listFiles().orEmpty().isEmpty())
        }

        // cached summaries bypass context network and preview animation callbacks
        run {
            val cache = InsightCache(temporary.newFolder())
            val entry = InsightCache.Entry(InsightOutput("已缓存"), 900, true)
            cache.write(InsightCache.key(video, source, InsightMode.SUMMARY), entry)
            val repo = InsightRepository(source, cache, { error("no context") }, client { error("no network") })
            assertEquals(entry, repo.generate(video, InsightMode.SUMMARY, onProgress = { error("no preview") }))
        }

        // InsightRpc install and observe run through Wire-First
        run {
            val loader = requireNotNull(javaClass.classLoader)
            val registrar = PlayerPortTestRegistrar()
            val environment = HookEnvironment(
                processName = "tv.danmaku.bili",
                classLoader = loader,
                hookPoints = HookPointRegistry(loader),
                registrar = registrar,
                logInfo = { _, _ -> },
                logError = { _, _ -> },
                reportStatus = { _, _ -> }
            )
            val wire = wireEnginePoint()
            val observed = mutableListOf<String>()

            val installCount = InsightRpc.install(
                env = environment,
                mossName = "com.bapis.test.v1.InsightMoss",
                rpc = "analyze",
                requestClass = CodecReq::class.java,
                replyClass = CodecReply::class.java,
                transformRequest = { req -> CodecReq((req as CodecReq).id + 1, "insight") },
                kmpGrpcWireEngine = wire
            ) { req, reply ->
                CodecReply("${(reply as CodecReply).marker}:bound=${(req as CodecReq).id}")
            }
            assertEquals(1, installCount)

            val (sentReq, gotReply) = invokeWire(
                registrar,
                "test.v1",
                "Insight",
                "Analyze",
                CodecReq(10L, "orig").toByteArray(),
                CodecReply("base").toByteArray()
            )
            assertEquals(11L, CodecReq.parseFrom(sentReq).id)
            assertEquals("base:bound=11", CodecReply.parseFrom(requireNotNull(gotReply)).marker)

            val observeCount = InsightRpc.observe(
                env = environment,
                mossName = "com.bapis.test.v1.WatchMoss",
                rpc = "ping",
                requestClass = CodecReq::class.java,
                replyClass = CodecReply::class.java,
                hookIdPrefix = "wave2.observe",
                what = "obs",
                kmpGrpcWireEngine = wire
            ) { reply ->
                observed += (reply as CodecReply).marker
            }
            assertEquals(1, observeCount)
            invokeWire(
                registrar,
                "test.v1",
                "Watch",
                "Ping",
                ByteArray(0),
                CodecReply("seen").toByteArray()
            )
            assertEquals(listOf("seen"), observed)
        }
    }
}
