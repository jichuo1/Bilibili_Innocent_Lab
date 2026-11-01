package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import org.junit.Assert.*
import org.junit.Test

class IncognitoBroadcastConfigTest {
    private class KConfig {
        fun ff(key: String, fallback: Boolean): Boolean = key.isNotEmpty() && fallback
    }

    private interface ConfigContract { fun ff(key: String): Boolean? }
    private open class ConfigImpl : ConfigContract {
        override fun ff(key: String): Boolean? = key.isNotEmpty()
    }
    private class DerivedConfig : ConfigImpl()
    private class RuntimeHelper(val config: ConfigContract) { fun getCm(): ConfigContract = config }
    private class RpcEngine {
        fun createRpc(host: String, port: Int, proxy: String): Long = host.length + port.toLong() + proxy.length
    }

    private val names = IncognitoBroadcastConfig.Names(
        KConfig::class.java.name, RuntimeHelper::class.java.name, ConfigContract::class.java.name,
        RpcEngine::class.java.name, "fixture.Modern", "fixture.Legacy")

    private inner class Harness(
        stack: () -> Array<StackTraceElement> = { emptyArray() },
        failStatus: Boolean = false
    ) {
        val registrar = PlayerPortTestRegistrar()
        val statuses = mutableMapOf<String, String>()
        val stages = mutableListOf<FeatureRuntimeStage>()
        val loader = javaClass.classLoader!!
        val env = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { key, value ->
                if (failStatus) error("diagnostic unavailable")
                statuses[key] = value
            }, runtimeEvidence = { _, stage, _ -> stages += stage })
        init { assertEquals(3, requireNotNull(IncognitoBroadcastConfig(names, stack).prepare(env)).invoke()) }

        fun kmp(key: String, fallback: Boolean = true): Any? = registrar.invoke("incognito.broadcast.kmp",
            KConfig(), arrayOf(key, fallback)) { fallback }

        fun provide(target: ConfigContract): Any? = registrar.invoke("incognito.broadcast.get_cm", RuntimeHelper(target)) { target }
        fun java(key: String): Any? = registrar.invoke("incognito.broadcast.java.${ConfigImpl::class.java.name}",
            ConfigImpl(), arrayOf(key)) { true }
    }

    @Test fun onlyBroadcastSwitchesAreDisabledAndOtherConfigIsUntouched() {
        val h = Harness()
        assertEquals(false, h.kmp("grpc_broadcast_enable"))
        assertEquals(false, h.kmp("grpc_stream_biz_enabled"))
        assertEquals(false, h.kmp("grpc_stream_biz_room_enabled"))
        assertEquals(true, h.kmp("grpc_broadcast_enable_extra"))
        assertEquals(true, h.kmp("GRPC_BROADCAST_ENABLE"))
        assertEquals(false, h.kmp("unrelated", false))
        assertEquals("disabled", h.statuses["incognito_broadcast_kmp_global"])
        assertEquals("disabled", h.statuses["incognito_broadcast_kmp_business"])
        assertEquals("disabled", h.statuses["incognito_broadcast_kmp_room"])
    }

    @Test fun concreteJavaConfigIsHookedBeforeTheCallerUsesTheUnchangedObject() {
        val h = Harness()
        val config = DerivedConfig()
        assertSame(config, h.provide(config))
        assertSame(config, h.provide(config))
        val id = "incognito.broadcast.java.${ConfigImpl::class.java.name}"
        assertEquals(1, h.registrar.registrationAttempts.count { it.first == id })
        assertEquals(false, h.java("grpc_broadcast_enable"))
        assertEquals(false, h.java("grpc_stream_biz_enabled"))
        assertEquals(false, h.java("grpc_stream_biz_room_enabled"))
        assertEquals(true, h.java("other_feature"))
        assertEquals("installed", h.statuses["incognito_broadcast_java_hook"])
        assertEquals("disabled", h.statuses["incognito_broadcast_java_global"])
    }

    @Test fun failedDynamicRegistrationPreservesTheHostResultAndCanBeRetried() {
        val h = Harness()
        val config = DerivedConfig()
        val id = "incognito.broadcast.java.${ConfigImpl::class.java.name}"
        h.registrar.failId = id
        assertSame(config, h.provide(config))
        assertTrue(FeatureRuntimeStage.ERROR in h.stages)
        assertFalse(h.registrar.hooks.containsKey(id))
        h.registrar.failId = null
        assertSame(config, h.provide(config))
        assertEquals(false, h.java("grpc_broadcast_enable"))
        assertEquals(2, h.registrar.registrationAttempts.count { it.first == id })
    }

    @Test fun diagnosticsCannotRestoreTheOriginalConfigValue() {
        val h = Harness(failStatus = true)
        assertEquals(false, h.kmp("grpc_broadcast_enable"))
        h.provide(ConfigImpl())
        assertEquals(false, h.java("grpc_stream_biz_room_enabled"))
    }

    @Test fun observedBroadcastIsReportedWithoutChangingTheNativeCall() {
        val h = Harness(stack = { arrayOf(StackTraceElement("fixture.Modern", "invokeSuspend", "Host.kt", 1)) })
        var calls = 0
        val result = h.registrar.invoke("incognito.broadcast.observe", RpcEngine(), arrayOf("host", 443, "proxy")) {
            calls++
            17L
        }
        assertEquals(17L, result)
        assertEquals(1, calls)
        assertEquals("observed", h.statuses["incognito_broadcast_tunnel_attempt"])
        h.registrar.invoke("incognito.broadcast.observe", RpcEngine(), arrayOf("host", 443, "proxy")) { 18L }
        assertEquals(1, h.stages.count { it == FeatureRuntimeStage.ERROR })
    }

    @Test fun unrelatedNativeCallDoesNotRaiseABroadcastAlarm() {
        val h = Harness(stack = { arrayOf(StackTraceElement("other.Caller", "invokeSuspend", "Host.kt", 1)) })
        assertEquals(17L, h.registrar.invoke("incognito.broadcast.observe", RpcEngine(), arrayOf("host", 443, "proxy")) { 17L })
        assertFalse(h.statuses.containsKey("incognito_broadcast_tunnel_attempt"))
        assertFalse(FeatureRuntimeStage.ERROR in h.stages)
    }

    @Test fun unknownHostStructureRegistersNothing() {
        val h = Harness()
        val fresh = PlayerPortTestRegistrar()
        val env = h.env.copy(registrar = fresh)
        assertNull(IncognitoBroadcastConfig(names.copy(runtimeHelper = "missing.Host")).prepare(env))
        assertTrue(fresh.hooks.isEmpty())
    }
}
