package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.bilibili.lib.moss.api.MossResponseHandler
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class PauseResponseInstallerTest {
    private val stages = mutableListOf<FeatureRuntimeStage>()
    private val statuses = linkedMapOf<String, String>()
    private fun environment(registrar: HookRegistrar) = HookEnvironment(
        "tv.danmaku.bili", javaClass.classLoader, HookPointRegistry(javaClass.classLoader), registrar,
        { _, _ -> }, { _, _ -> }, { key, value -> statuses[key] = value },
        runtimeEvidence = { _, stage, _ -> stages += stage }
    )
    private fun points(java: Class<*> = JavaPauseMoss::class.java, kotlin: Class<*>? = KotlinPauseMoss::class.java) =
        PauseResponseAdapter.resolve(PauseReply::class.java, PauseReq::class.java, java, kotlin)

    @Test fun `sync async and Kotlin responses each have their own registration`() {
        val registrar = PlayerPortTestRegistrar()
        val coverage = PauseResponseAdapter.install(environment(registrar), points())
        assertEquals(PauseResponseAdapter.Coverage(3, 3, kotlinInstalled = true), coverage)
        assertTrue(coverage.complete)
        assertEquals(setOf("paused.response.execute_play_pause", "paused.response.play_pause",
            "paused.response.kmoss.play_pause"), registrar.hooks.keys)
    }

    @Test fun `absent methods ambiguous callbacks and registration failures remain in the denominator`() {
        val missing = points(MissingAsyncPauseMoss::class.java, AmbiguousKotlinPauseMoss::class.java)
        assertNull(missing.async)
        assertNull(missing.kotlin)
        val partial = PauseResponseAdapter.install(environment(PlayerPortTestRegistrar()), missing)
        assertEquals(PauseResponseAdapter.Coverage(1, 3), partial)
        assertFalse(partial.complete)
        val failed = PauseResponseAdapter.install(environment(PlayerPortTestRegistrar("paused.response.play_pause")), points())
        assertEquals(PauseResponseAdapter.Coverage(2, 3, kotlinInstalled = true), failed)
        assertFalse(failed.complete)
        val kotlinFailed = PauseResponseAdapter.install(
            environment(PlayerPortTestRegistrar("paused.response.kmoss.play_pause")), points())
        assertEquals(PauseResponseAdapter.Coverage(2, 3), kotlinFailed)
    }

    @Test fun `request routes require the audited host ABI and the registered Kotlin boundary`() {
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                error("Unexpected host lookup without route evidence")
        }
        val families = setOf("ad-api", "theseus")
        assertTrue(PauseRequestCoverage.covered(loader, 9190400, families, kotlinInstalled = false).isEmpty())
        assertTrue(PauseRequestCoverage.covered(loader, 9190300, families, kotlinInstalled = true).isEmpty())
        assertTrue(PauseRequestCoverage.covered(javaClass.classLoader!!, 9190400, families, kotlinInstalled = true).isEmpty())
    }

    @Test fun `sync cleans only ads and copies bar extra fields and Java unknown fields`() {
        val registrar = PlayerPortTestRegistrar()
        PauseResponseAdapter.install(environment(registrar), points())
        val original = PauseReply("ad", "pause bar", 7, "unrelated", byteArrayOf(1, 4, 9))
        val updated = registrar.invoke("paused.response.execute_play_pause") { original } as PauseReply
        assertNotSame(original, updated)
        assertFalse(updated.hasAds())
        assertTrue(original.hasAds())
        assertEquals(original.bar, updated.bar)
        assertEquals(original.kind, updated.kind)
        assertEquals(original.other, updated.other)
        assertArrayEquals(original.unknownFields, updated.unknownFields)
        assertTrue(FeatureRuntimeStage.APPLIED in stages)
        stages.clear()
        val onlyBar = PauseReply(null, "pause bar")
        assertSame(onlyBar, registrar.invoke("paused.response.execute_play_pause") { onlyBar })
        assertFalse(FeatureRuntimeStage.APPLIED in stages)
        val default = PauseReply.getDefaultInstance()
        assertSame(default, registrar.invoke("paused.response.execute_play_pause") { default })
    }

    @Test fun `async keeps callback errors completion and delegate exceptions unchanged`() {
        val registrar = PlayerPortTestRegistrar()
        PauseResponseAdapter.install(environment(registrar), points())
        val replies = mutableListOf<Any?>()
        val errors = mutableListOf<Throwable>()
        var completed = 0
        val handler = object : MossResponseHandler {
            override fun onNext(reply: Any?) { replies += reply }
            override fun onError(error: Throwable) { errors += error }
            override fun onCompleted() { completed++ }
        }
        val original = PauseReply("ad", "bar")
        val error = IllegalStateException("host callback")
        val arguments = arrayOf<Any?>(PauseReq(), handler)
        registrar.invoke("paused.response.play_pause", args = arguments) { args ->
            (args[1] as MossResponseHandler).apply { onNext(original); onError(error); onCompleted() }
        }
        assertSame(handler, arguments[1])
        assertFalse((replies.single() as PauseReply).hasAds())
        assertEquals("bar", (replies.single() as PauseReply).bar)
        assertEquals(listOf(error), errors)
        assertEquals(1, completed)
        val throwing = object : MossResponseHandler by handler {
            override fun onNext(reply: Any?) { throw error }
        }
        val thrown = assertThrows(IllegalStateException::class.java) {
            registrar.invoke("paused.response.play_pause", args = arrayOf(PauseReq(), throwing)) { args ->
                (args[1] as MossResponseHandler).onNext(original)
            }
        }
        assertSame(error, thrown)
        assertSame(error, assertThrows(IllegalStateException::class.java) {
            registrar.invoke("paused.response.execute_play_pause") { throw error }
        })
    }

    @Test fun `Kotlin bridge rewrites only a successful copy and preserves a bar only reply identity`() {
        val registrar = PlayerPortTestRegistrar()
        PauseResponseAdapter.install(environment(registrar), points())
        val replies = mutableListOf<Any?>()
        val handler = collecting(replies)
        val proto = PauseProtoBuf()
        val original = KotlinPause(PauseReply("ad", "bar", 5, "keep", byteArrayOf(8, 2)))
        fun emit(reply: KotlinPause) = registrar.invoke("paused.response.kmoss.play_pause",
            args = arrayOf(PauseReq(), Strategy, Strategy, handler, proto)) { args ->
            (args[3] as MossResponseHandler).onNext(reply)
        }
        emit(original)
        val updated = replies.last() as KotlinPause
        assertNotSame(original, updated)
        assertFalse(updated.message.hasAds())
        assertTrue(original.message.hasAds())
        assertEquals("bar", updated.message.bar)
        assertEquals(5, updated.message.kind)
        assertEquals("keep", updated.message.other)
        // 夹具在线格式中保存该字段；不据此许诺任意 Kotlin 序列化器保留未知字段。
        assertArrayEquals(original.message.unknownFields, updated.message.unknownFields)
        assertTrue(FeatureRuntimeStage.APPLIED in stages)
        stages.clear()
        val onlyBar = KotlinPause(PauseReply(null, "bar"))
        emit(onlyBar)
        assertSame(onlyBar, replies.last())
        assertFalse(FeatureRuntimeStage.APPLIED in stages)
    }

    @Test fun `failed Kotlin write or changed bar readback passes the original and never claims applied`() {
        val registrar = PlayerPortTestRegistrar()
        val env = environment(registrar)
        val cleaner = requireNotNull(PausePayloadCleaner.resolve(PauseReply::class.java))
        val original = KotlinPause(PauseReply("ad", "bar"))
        val bridge = KotlinMossReplyBridge(
            encode = { (it as KotlinPause).message.toByteArray() },
            parseJava = PauseReply::parseFrom,
            toBytes = { (it as PauseReply).toByteArray() },
            decode = { error("decode failed") }
        )
        assertSame(original, PauseResponseAdapter.transformKotlin(bridge, cleaner, original, env))
        assertTrue(FeatureRuntimeStage.ERROR in stages)
        assertFalse(FeatureRuntimeStage.APPLIED in stages)
        stages.clear()
        val corrupt = KotlinMossReplyBridge(
            encode = { (it as KotlinPause).message.toByteArray() },
            parseJava = PauseReply::parseFrom,
            toBytes = { (it as PauseReply).toByteArray() },
            decode = { KotlinPause(PauseReply(null, "changed bar")) }
        )
        assertSame(original, PauseResponseAdapter.transformKotlin(corrupt, cleaner, original, env))
        assertTrue(FeatureRuntimeStage.ERROR in stages)
        assertFalse(FeatureRuntimeStage.APPLIED in stages)
    }

    @Test fun `identified requests remain untouched and an incomplete response layer cannot turn green`() {
        val registrar = PlayerPortTestRegistrar()
        val requests = VersionAdapter.PausePoints(listOf(VersionAdapter.HookPoint("shared.pause.Request", "invokeSuspend")), null, null, null)
        val result = PausedAdFeatureInstaller(true, requests).install(environment(registrar)) as FeatureInstallResult.Installed
        assertFalse(result.complete)
        assertTrue(statuses.getValue("adskip_status").startsWith("partial:"))
        assertEquals("partial:1/2", statuses["adskip_response_status"])
        assertTrue(registrar.hooks.keys.none { it.startsWith("paused.request") || it == "paused.legacy_callback" })
    }

    private fun collecting(replies: MutableList<Any?>) = object : MossResponseHandler {
        override fun onNext(reply: Any?) { replies += reply }
        override fun onError(error: Throwable) = Unit
        override fun onCompleted() = Unit
    }

    class PauseReq
    class PauseReply(val ads: String?, val bar: String?, val kind: Int = 0, val other: String = "",
        val unknownFields: ByteArray = byteArrayOf()) {
        fun hasAds() = ads != null
        fun hasBar() = bar != null
        fun toByteArray(): ByteArray = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use {
                it.writeBoolean(ads != null); if (ads != null) it.writeUTF(ads)
                it.writeBoolean(bar != null); if (bar != null) it.writeUTF(bar)
                it.writeInt(kind); it.writeUTF(other); it.writeInt(unknownFields.size); it.write(unknownFields)
            }
        }.toByteArray()
        class Builder(private val original: PauseReply) {
            private var ads = original.ads
            fun clearAds(): Builder { ads = null; return this }
            fun build() = PauseReply(ads, original.bar, original.kind, original.other, original.unknownFields.copyOf())
        }
        companion object {
            private val DEFAULT = PauseReply(null, null)
            @JvmStatic fun getDefaultInstance() = DEFAULT
            @JvmStatic fun newBuilder(original: PauseReply) = Builder(original)
            @JvmStatic fun parseFrom(bytes: ByteArray): PauseReply = DataInputStream(ByteArrayInputStream(bytes)).use {
                val ads = if (it.readBoolean()) it.readUTF() else null
                val bar = if (it.readBoolean()) it.readUTF() else null
                val kind = it.readInt(); val other = it.readUTF()
                val unknown = ByteArray(it.readInt()); it.readFully(unknown)
                PauseReply(ads, bar, kind, other, unknown)
            }
        }
    }
    class JavaPauseMoss {
        fun executePlayPause(request: PauseReq): PauseReply = PauseReply.getDefaultInstance()
        fun playPause(request: PauseReq, handler: MossResponseHandler) = Unit
    }
    class MissingAsyncPauseMoss {
        fun executePlayPause(request: PauseReq): PauseReply = PauseReply.getDefaultInstance()
    }
    class KotlinPauseMoss {
        fun playPause(request: Any, serializer: SerializationStrategy<*>, deserializer: DeserializationStrategy<*>,
            handler: MossResponseHandler, protoBuf: ProtoBuf) = Unit
    }
    interface OtherHandler { fun onNext(reply: Any?) }
    class AmbiguousKotlinPauseMoss {
        fun playPause(request: Any, serializer: SerializationStrategy<*>, deserializer: DeserializationStrategy<*>,
            handler: MossResponseHandler, protoBuf: ProtoBuf) = Unit
        fun playPause(request: Any, serializer: SerializationStrategy<*>, deserializer: DeserializationStrategy<*>,
            handler: OtherHandler, protoBuf: ProtoBuf) = Unit
    }
    class KotlinPause(val message: PauseReply)
    object Strategy : SerializationStrategy<Any>, DeserializationStrategy<Any>
    class PauseProtoBuf : ProtoBuf() {
        override fun encodeToByteArray(serializer: SerializationStrategy<*>, value: Any): ByteArray =
            (value as KotlinPause).message.toByteArray()
        override fun decodeFromByteArray(deserializer: DeserializationStrategy<*>, bytes: ByteArray): Any =
            KotlinPause(PauseReply.parseFrom(bytes))
    }
}
