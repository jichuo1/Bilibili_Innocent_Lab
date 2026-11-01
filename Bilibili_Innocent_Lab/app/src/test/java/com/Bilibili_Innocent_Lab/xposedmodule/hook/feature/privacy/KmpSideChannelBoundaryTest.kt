package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.bilibili.lib.ighttp.IgHttpRequest
import kntr.base.neuron.KNeuron
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class KmpSideChannelBoundaryTest {
    /** Records adapted() hooks as well, keeping the requested exception policy. */
    private class Registrar(val port: PlayerPortTestRegistrar = PlayerPortTestRegistrar()) : HookRegistrar by port {
        val policies = linkedMapOf<String, HookExceptionPolicy>()
        val hooks get() = port.hooks
        override fun adapted(id: String, point: VersionAdapter.HookPoint, exceptionPolicy: HookExceptionPolicy,
                             block: ModernMemberHookCreator.() -> Unit) {
            policies[id] = exceptionPolicy
            val member = Class.forName(point.className).declaredMethods.single { it.name == point.methodName }
            port.hooks[id] = PlayerPortTestRegistrar.Entry(member, ModernMemberHookCreator(member).apply(block))
        }
        fun invoke(id: String, receiver: Any? = null, args: Array<Any?> = emptyArray(), original: (Array<Any?>) -> Any? = { null }) =
            port.invoke(id, receiver, args, original)
    }

    private class Harness {
        val session = IncognitoSession().apply { enter() }
        val registrar = Registrar()
        val statuses = mutableMapOf<String, String>()
        var ready = true
        val loader = javaClass.classLoader!!
        val env = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { key, value -> statuses[key] = value })
    }

    private fun Harness.request(url: String, method: String = "GET", vararg headers: Pair<String, String>): IgHttpRequest {
        val builder = IgHttpRequest.Builder().url(url).method(method, null)
        headers.forEach { (k, v) -> builder.addHeaders(k, v) }
        return registrar.invoke("incognito.ignet.http.build", builder) { builder.build() } as IgHttpRequest
    }

    private fun Harness.execute(request: IgHttpRequest): Result<Any?> =
        runCatching { registrar.invoke("incognito.ignet.http.execute", request) { request.execute() } }

    private fun ignet(): Harness = Harness().apply {
        assertEquals(2, requireNotNull(IgnetHttpBoundary(session) { ready }.prepare(env)).invoke())
    }

    @Test fun ignetRequestsAreSealedBeforeTheNativeRequestIsBuilt() {
        val h = ignet()
        val request = h.request("https://app.bilibili.com/x/resource/show/tab/v2?access_key=real&buvid=dev&a=1",
            "GET", "Authorization" to "identify_v1 real", "x-bili-mid" to "42", "Buvid" to "dev", "User-Agent" to "ua")
        val identity = h.session.identity()!!
        assertEquals("https://app.bilibili.com/x/resource/show/tab/v2?buvid=${identity.buvid}&a=1", request.url())
        assertEquals(mapOf("Buvid" to identity.buvid, "User-Agent" to "ua"), request.headers().toMap())
        assertTrue(h.execute(request).isSuccess)
        assertEquals(1, request.executed)
    }

    @Test fun reportsAndTelemetryAreWithheldWithAnIoExceptionTheHostReceives() {
        val h = ignet()
        assertEquals(HookExceptionPolicy.DELIVER_TO_HOST, h.registrar.policies["incognito.ignet.http.execute"])
        for ((url, method) in listOf("https://dataflow.biliapi.com/log/pbmobile/realtime" to "POST",
                "https://api.bilibili.com/x/report/click/now" to "POST")) {
            val request = h.request(url, method)
            val failure = h.execute(request).exceptionOrNull()
            assertTrue(url, failure is IOException)
            assertEquals(0, request.executed)
            assertTrue(request.closed)
        }
        assertEquals("withheld", h.statuses["incognito_transport_ignet_http"])
    }

    @Test fun gameTrackerConfigurationIsAllowedButReportsNeverReachTheNativeEngine() {
        val h = ignet()
        val config = h.request("https://line1-log.biligame.net/collector/admin/config", "POST")
        assertTrue(h.execute(config).isSuccess)
        assertEquals(1, config.executed)
        val report = h.request("https://line1-log.biligame.net/collector/api/report", "POST")
        assertTrue(h.execute(report).exceptionOrNull() is IOException)
        assertEquals(0, report.executed)
        assertTrue(report.closed)
        assertEquals("withheld-ignet", h.statuses["incognito_game_tracker_report"])
    }

    @Test fun unsealedOrEarlyRequestsNeverLeave() {
        val h = ignet()
        val unsealed = IgHttpRequest.Builder().url("https://api.bilibili.com/x/v2/view?aid=1").build()
        assertTrue(h.execute(unsealed).exceptionOrNull() is IOException)
        assertEquals(0, unsealed.executed)
        h.ready = false
        val early = h.request("https://api.bilibili.com/x/v2/view?aid=1")
        assertTrue(h.execute(early).exceptionOrNull() is IOException)
        assertEquals(0, early.executed)
    }

    @Test fun kmpNeuronProducersAreDiscardedWithoutRunningUploadConditions() {
        val h = Harness()
        requireNotNull(IncognitoTelemetryHooks(h.session).prepare(h.env)).invoke()
        val produced = h.registrar.hooks.filterKeys { it.startsWith("incognito.kneuron.") }.values.map { it.member.name }.toSet()
        assertEquals(setOf("reportClick", "trackT", "simpleTrackT"), produced)
        var original = 0
        var condition = 0
        val id = h.registrar.hooks.entries.single { it.key.startsWith("incognito.kneuron.trackT") }.key
        h.registrar.invoke(id, KNeuron(), arrayOf(false, "event", emptyMap<String, String>(), { condition++; true })) { original++; null }
        assertEquals(0, original)
        assertEquals(0, condition)
    }
}
