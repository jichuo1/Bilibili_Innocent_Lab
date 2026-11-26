package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.*
import com.bilibili.lib.neuron.api.Neurons
import org.junit.Assert.*
import org.junit.Test

class IncognitoTelemetryHooksTest {
    private class Harness {
        val session = IncognitoSession().apply { enter() }
        val registrar = PlayerPortTestRegistrar()
        val statuses = mutableListOf<String>()
        init {
            val loader = javaClass.classLoader!!
            IncognitoTelemetryHooks(session).install(HookEnvironment("tv.danmaku.bili", loader,
                HookPointRegistry(loader), registrar, { _, _ -> }, { _, _ -> },
                { key, _ -> statuses += key }))
        }
        fun id(name: String) = registrar.hooks.entries.single {
            it.value.member.name == name && it.value.member.declaringClass == Neurons::class.java
        }.key
    }

    fun eventProducersCannotEnqueueOrEvaluateUploadConditions() {
        val h = Harness()
        var originalCalls = 0
        var conditionCalls = 0
        val condition = { conditionCalls++; true }
        for (name in listOf("reportClick", "trackT", "track")) {
            repeat(2) {
                val args: Array<Any?> = if (name == "trackT") arrayOf("event", condition) else arrayOf("event")
                assertNull(h.registrar.invoke(h.id(name), Neurons(), args) { originalCalls++; null })
            }
        }
        assertEquals(0, originalCalls)
        assertEquals(0, conditionCalls)
        assertEquals(1, h.statuses.count { it == "incognito_telemetry_producer" })
        assertFalse(h.registrar.hooks.values.any { it.member.name in listOf("initialize", "reportCountAfterLastFetch") })
    }

    fun uuidRefreshNeverReadsOrWritesPersistentIdentity() {
        val h = Harness()
        var originalCalls = 0
        fun read() = h.registrar.invoke(h.id("getUUID"), Neurons()) { originalCalls++; "persistent" }
        val first = read()
        assertEquals(h.session.identity()!!.sessionId, first)
        h.registrar.invoke(h.id("setUUID"), Neurons(), arrayOf("persistent")) { originalCalls++; null }
        h.session.refresh()
        assertNotEquals(first, read())
        assertEquals(h.session.identity()!!.sessionId, read())
        assertEquals(0, originalCalls)
    }

    fun kmpProducersIgnorePrivateMethodsAndMissingNeuronsSkipsAtPrepare() {
        class MixedVisibilityKNeuron {
            fun reportClick(event: String) = Unit
            fun trackT(event: String) = Unit
            private fun reportSecret(event: String) = Unit
            internal fun trackHidden(event: String) = Unit
        }
        val found = IncognitoTelemetryHooks.kmpProducers(MixedVisibilityKNeuron::class.java)
        assertNotNull(found)
        assertEquals(setOf("reportClick", "trackT"), found!!.map { it.name }.toSet())

        val emptyLoader = object : ClassLoader(null) {}
        val registrar = PlayerPortTestRegistrar()
        val env = HookEnvironment("tv.danmaku.bili", emptyLoader, HookPointRegistry(emptyLoader),
            registrar, { _, _ -> }, { _, _ -> }, { _, _ -> })
        assertNull(IncognitoTelemetryHooks(IncognitoSession().apply { enter() }).prepare(env))
        assertTrue(registrar.hooks.isEmpty())
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (IncognitoTelemetryHooksTest) -> Unit) {
            val s = IncognitoTelemetryHooksTest()
            block(s)
        }
        runIsolated { s -> s.eventProducersCannotEnqueueOrEvaluateUploadConditions() }
        runIsolated { s -> s.uuidRefreshNeverReadsOrWritesPersistentIdentity() }
        runIsolated { s -> s.kmpProducersIgnorePrivateMethodsAndMissingNeuronsSkipsAtPrepare() }
    }
}
