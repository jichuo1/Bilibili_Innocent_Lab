package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.bapis.bilibili.app.playerunite.v1.*
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.*
import org.junit.Test

class PlayerCodecKotlinRequestTest {
    // Opaque request with no semantic getters, exactly as after R8.
    data class Opaque(val a: Int, val b: Int, val c: String)
    interface Handler
    object Serializer : SerializationStrategy<Any>, DeserializationStrategy<Any>
    object EncodeOnly : SerializationStrategy<Any>
    class KMoss {
        fun playViewUnite(request: Any, encoder: SerializationStrategy<*>, decoder: DeserializationStrategy<*>,
            handler: Handler, format: ProtoBuf) = Unit
    }
    class LegacyKMoss {
        fun playViewUnite(request: Any, encoder: SerializationStrategy<*>, decoder: DeserializationStrategy<*>,
            handler: Handler) = Unit
    }
    abstract class ClassHandler { abstract fun onNext(value: Any?) }
    class ClassHandlerKMoss {
        fun playViewUnite(request: Any, encoder: SerializationStrategy<*>, decoder: DeserializationStrategy<*>,
            handler: ClassHandler, format: ProtoBuf) = Unit
    }
    class Wire(val broken: Boolean = false) : ProtoBuf() {
        override fun encodeToByteArray(serializer: SerializationStrategy<*>, value: Any): ByteArray {
            check(!broken)
            val request = value as Opaque
            return PlayViewUniteReq(VideoVod(CodeType.entries[request.a], request.b), request.c).toByteArray()
        }
        override fun decodeFromByteArray(serializer: DeserializationStrategy<*>, bytes: ByteArray): Any {
            check(!broken)
            val request = PlayViewUniteReq.parseFrom(bytes)
            return Opaque(request.getVod().getPreferCodecType().ordinal, request.getVod().getFnval(), request.unrelated)
        }
    }
    private fun harness(codec: PlayerCodecPreference = PlayerCodecPreference.AV1,
        mode: PlayerDecodeMode = PlayerDecodeMode.FOLLOW_HOST, legacy: Boolean = false): Pair<PlayerPortTestRegistrar, MutableList<FeatureRuntimeStage>> {
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                if (name == "com.bapis.bilibili.app.playerunite.v1.KPlayerMoss")
                    (if (legacy) LegacyKMoss::class.java else KMoss::class.java) else super.loadClass(name, resolve)
        }
        val registrar = PlayerPortTestRegistrar(); val events = mutableListOf<FeatureRuntimeStage>()
        val env = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { _, _ -> }, runtimeEvidence = { _, stage, _ -> events += stage })
        val result = PlayerCodecForceFeatureInstaller(codec.value, mode.value).install(env)
        assertTrue(result is FeatureInstallResult.Installed && result.complete)
        assertTrue(registrar.hooks.containsKey("player_codec_force.request.kotlin.playViewUnite"))
        return registrar to events
    }
    private fun call(registrar: PlayerPortTestRegistrar, original: Opaque,
        serializer: SerializationStrategy<*> = Serializer, wire: Wire = Wire(), legacy: Boolean = false): Any? {
        val handler = object : Handler {}
        val arguments = if (legacy) arrayOf<Any?>(original, serializer, Serializer, handler)
            else arrayOf<Any?>(original, serializer, Serializer, handler, wire)
        return registrar.invoke("player_codec_force.request.kotlin.playViewUnite", if (legacy) LegacyKMoss() else KMoss(), arguments) { args ->
            assertSame(serializer, args[1]); assertSame(Serializer, args[2]); assertSame(handler, args[3])
            if (!legacy) assertSame(wire, args[4])
            args[0]
        }
    }
    @Test fun opaqueRequestUsesItsSerializerAndPreservesOtherFieldsAndOriginal() {
        val (registrar, events) = harness()
        val original = Opaque(CodeType.CODE_UNKNOWN.ordinal, 16, "preserve routing and account fields")
        val updated = call(registrar, original) as Opaque
        assertNotSame(original, updated); assertEquals(CodeType.CODEAV1.ordinal, updated.a)
        assertEquals(2064, updated.b); assertEquals(original.c, updated.c)
        assertEquals(16, original.b); assertEquals(CodeType.CODE_UNKNOWN.ordinal, original.a)
        assertTrue(FeatureRuntimeStage.APPLIED in events)
    }
    @Test fun oldFourArgumentRequestUsesTheHostDefaultFormatAndPreservesPayload() {
        val old = ProtoBuf.Default
        try {
            ProtoBuf.Default = Wire()
            val (registrar, _) = harness(legacy = true)
            val original = Opaque(CodeType.CODE_UNKNOWN.ordinal, 16, "old routing")
            val updated = call(registrar, original, legacy = true) as Opaque
            assertEquals(CodeType.CODEAV1.ordinal, updated.a)
            assertEquals(2064, updated.b); assertEquals(original.c, updated.c); assertEquals(16, original.b)
        } finally { ProtoBuf.Default = old }
    }
    @Test fun callbackClassShapeDoesNotBlockRequestOnlyRewriting() {
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                if (name == "com.bapis.bilibili.app.playerunite.v1.KPlayerMoss") ClassHandlerKMoss::class.java else super.loadClass(name, resolve)
        }
        val registrar = PlayerPortTestRegistrar()
        val env = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { _, _ -> })
        assertTrue(PlayerCodecForceFeatureInstaller(PlayerCodecPreference.AV1.value,
            PlayerDecodeMode.FOLLOW_HOST.value).install(env) is FeatureInstallResult.Installed)
        val handler = object : ClassHandler() { override fun onNext(value: Any?) = Unit }
        val original = Opaque(CodeType.CODE_UNKNOWN.ordinal, 16, "same callback class")
        val updated = registrar.invoke("player_codec_force.request.kotlin.playViewUnite", ClassHandlerKMoss(),
            arrayOf(original, Serializer, Serializer, handler, Wire())) { args ->
            assertSame(handler, args[3]); args[0]
        } as Opaque
        assertEquals(CodeType.CODEAV1.ordinal, updated.a); assertEquals(original.c, updated.c)
    }

    @Test fun unchangedPreferenceKeepsTheSameKotlinRequest() {
        val (registrar, _) = harness()
        val original = Opaque(CodeType.CODEAV1.ordinal, 2064, "keep")
        assertSame(original, call(registrar, original))
    }
    @Test fun softwareDecodeOnlyClearsAv1AndPreservesCodecPreference() {
        val (registrar, _) = harness(PlayerCodecPreference.FOLLOW_HOST, PlayerDecodeMode.FORCE_SOFTWARE)
        val original = Opaque(CodeType.CODE265.ordinal, 2064, "keep")
        val updated = call(registrar, original) as Opaque
        assertEquals(original.a, updated.a); assertEquals(16, updated.b); assertEquals(original.c, updated.c)
    }
    @Test fun missingRoundtripOrBrokenCodecLeavesAllOriginalArgumentsInPlace() {
        val (registrar, events) = harness()
        val original = Opaque(CodeType.CODE_UNKNOWN.ordinal, 16, "keep")
        assertSame(original, call(registrar, original, EncodeOnly))
        assertSame(original, call(registrar, original, wire = Wire(broken = true)))
        assertTrue(FeatureRuntimeStage.ERROR in events); assertFalse(FeatureRuntimeStage.APPLIED in events)
    }
}
