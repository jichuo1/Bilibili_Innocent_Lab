package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FollowUpRecommendFeatureInstaller.Companion.matchesRecommendPath
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.Continuation

class FollowUpRecommendFeatureInstallerTest {
    // 两个页面家族各自的仓库与请求协程体：协程体实现 Function2，构造参数为（仓库, Continuation）。
    class Repo2
    class Body2(@JvmField val owner: Repo2, @JvmField val completion: Continuation<*>) : Function2<Any?, Any?, Any?> {
        override fun invoke(p1: Any?, p2: Any?): Any? = null
        fun invokeSuspend(result: Any): Any = error("invoked through Modern callback fixture")
    }
    class Repo1
    class Body1(@JvmField val owner: Repo1, @JvmField val completion: Continuation<*>) : Function2<Any?, Any?, Any?> {
        override fun invoke(p1: Any?, p2: Any?): Any? = null
        fun invokeSuspend(result: Any): Any = error("invoked through Modern callback fixture")
    }
    /** 构造参数属于别的仓库：结构漂移，不能当成本家族的请求体。 */
    class ForeignBody(@JvmField val owner: Repo1, @JvmField val completion: Continuation<*>) : Function2<Any?, Any?, Any?> {
        override fun invoke(p1: Any?, p2: Any?): Any? = null
        fun invokeSuspend(result: Any): Any = Unit
    }
    /** 不是挂起 lambda（未实现 Function2）。 */
    class PlainBody(@JvmField val owner: Repo2, @JvmField val completion: Continuation<*>) {
        fun invokeSuspend(result: Any): Any = Unit
    }

    // Ktor：语句持有唯一的请求构建器字段（混淆名 a），构建器 getUrl() 返回 URLBuilder。
    class UrlBuilder(private val segments: List<String>) { fun getEncodedPathSegments(): List<String> = segments }
    class RequestBuilder(private val url: UrlBuilder) { fun getUrl(): UrlBuilder = url }
    class Statement(@JvmField val a: RequestBuilder?) { fun execute(completion: Continuation<*>): Any? = error("fixture") }
    class StatementWithoutBuilder { fun execute(completion: Continuation<*>): Any? = error("fixture") }

    private class Fixture(
        repo2: Class<*>? = Repo2::class.java,
        body2: Class<*>? = Body2::class.java,
        repo1: Class<*>? = Repo1::class.java,
        body1: Class<*>? = Body1::class.java,
        statement: Class<*>? = Statement::class.java,
        process: String = "tv.danmaku.bili",
        failId: String? = null
    ) {
        var lookups = 0
        private val mapping = mapOf(
            FollowUpRecommendFeatureInstaller.Family.THESEUS2.repository to repo2,
            FollowUpRecommendFeatureInstaller.Family.THESEUS2.requestBody to body2,
            FollowUpRecommendFeatureInstaller.Family.THESEUS.repository to repo1,
            FollowUpRecommendFeatureInstaller.Family.THESEUS.requestBody to body1,
            FollowUpRecommendFeatureInstaller.KTOR_STATEMENT to statement,
            FollowUpRecommendFeatureInstaller.KTOR_REQUEST_BUILDER to RequestBuilder::class.java,
            FollowUpRecommendFeatureInstaller.KTOR_URL_BUILDER to UrlBuilder::class.java
        )
        val loader = object : ClassLoader(Repo2::class.java.classLoader) {
            override fun loadClass(name: String): Class<*> = if (name in mapping) {
                lookups++
                mapping[name] ?: throw ClassNotFoundException(name)
            } else super.loadClass(name)
        }
        val port = PlayerPortTestRegistrar(failId)
        val policies = linkedMapOf<String, HookExceptionPolicy>()
        val points = linkedMapOf<String, VersionAdapter.HookPoint>()
        val registrar = object : HookRegistrar by port {
            override fun adapted(id: String, point: VersionAdapter.HookPoint, exceptionPolicy: HookExceptionPolicy,
                                 block: ModernMemberHookCreator.() -> Unit) {
                policies[id] = exceptionPolicy
                points[id] = point
                val owner = loader.loadClass(point.className)
                val params = point.paramClassNames.orEmpty().map { loader.loadClass(it) }.toTypedArray()
                port.exact(id, owner, point.methodName, *params, block = block)
            }
        }
        val stages = mutableListOf<FeatureRuntimeStage>()
        val errors = mutableListOf<String>()
        val statuses = mutableListOf<Pair<String, String>>()
        val capabilities = linkedMapOf<String, FeatureInstallResult>()
        val environment = HookEnvironment(process, loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { key, _ -> errors += key }, { key, value -> statuses += key to value },
            runtimeEvidence = { id, stage, _ -> assertEquals(FollowUpRecommendFeatureInstaller.ID, id); stages += stage },
            capabilityEvidence = { id, result -> capabilities[id] = result })

        fun install(enabled: Boolean = true) = FollowUpRecommendFeatureInstaller(enabled).install(environment)
        fun request(family: FollowUpRecommendFeatureInstaller.Family, body: Any, original: () -> Any?): Any? =
            port.invoke("${FollowUpRecommendFeatureInstaller.HOOK_REQUEST}.${family.key}", body, arrayOf<Any?>(Any())) { original() }
        fun execute(statement: Any, original: () -> Any?): Any? =
            port.invoke(FollowUpRecommendFeatureInstaller.HOOK_NETWORK_KTOR, statement, arrayOf<Any?>(null)) { original() }
    }

    private fun statement(vararg segments: String) = Statement(RequestBuilder(UrlBuilder(segments.toList())))

    @Test fun `disabled or non main process performs no host lookup or registration`() {
        for ((enabled, process) in listOf(false to "tv.danmaku.bili", true to "tv.danmaku.bili:web")) {
            val f = Fixture(process = process)
            assertTrue(f.install(enabled) is FeatureInstallResult.Skipped)
            assertEquals(0, f.lookups)
            assertTrue(f.port.registrationAttempts.isEmpty())
            assertTrue(f.capabilities.isEmpty())
        }
        assertTrue(FollowUpRecommendFeatureInstaller(false).capabilityIds.isEmpty())
    }

    @Test fun `both page families and the ktor transport install as separate counted units`() {
        val f = Fixture()
        assertEquals(FeatureInstallResult.Installed(3, complete = true), f.install())
        assertEquals(FeatureInstallResult.Installed(2, complete = true), f.capabilities[FollowUpRecommendFeatureInstaller.CAP_REQUEST])
        assertEquals(FeatureInstallResult.Installed(1, complete = true), f.capabilities[FollowUpRecommendFeatureInstaller.CAP_NETWORK])
        assertEquals(listOf("theseus2", "theseus").map { "${FollowUpRecommendFeatureInstaller.HOOK_REQUEST}.$it" } +
            FollowUpRecommendFeatureInstaller.HOOK_NETWORK_KTOR, f.port.registrationAttempts.map { it.first })
        assertEquals(HookExceptionPolicy.DELIVER_TO_HOST, f.policies[FollowUpRecommendFeatureInstaller.HOOK_NETWORK_KTOR])
        assertEquals(listOf(FollowUpRecommendFeatureInstaller.CONTINUATION),
            f.points[FollowUpRecommendFeatureInstaller.HOOK_NETWORK_KTOR]?.paramClassNames)
        assertEquals(FollowUpRecommendFeatureInstaller.STATUS to "theseus2:installed,theseus:installed,ktor:installed",
            f.statuses.single())
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED), f.stages)
    }

    @Test fun `request bodies finish with the host unit and never run the original request`() {
        val f = Fixture(); f.install()
        val completion = object : Continuation<Any?> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(result: Result<Any?>) = Unit
        }
        for ((family, body) in listOf(FollowUpRecommendFeatureInstaller.Family.THESEUS2 to Body2(Repo2(), completion),
                FollowUpRecommendFeatureInstaller.Family.THESEUS to Body1(Repo1(), completion))) {
            repeat(3) { assertSame(Unit, f.request(family, body) { throw AssertionError("recommendation request ran") }) }
        }
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), f.stages)
        assertTrue(f.errors.isEmpty())
    }

    @Test fun `the network guard fails only the exact recommendation path before any network work`() {
        val f = Fixture(); f.install()
        var network = 0
        for (target in listOf(statement("", "x", "v2", "view", "upper", "recmd"), statement("x", "v2", "view", "upper", "recmd", ""))) {
            val error = assertThrows(IOException::class.java) { f.execute(target) { network++; "response" } }
            assertEquals(FollowUpRecommendFeatureInstaller.BLOCKED_MESSAGE, error.message)
        }
        assertEquals(0, network)
        // 个人空间同名推荐、详情页其他接口与更深的路径都原样放行，返回值保持同一对象。
        for (target in listOf(statement("", "x", "v2", "space", "upper", "recmd"), statement("", "x", "v2", "view"),
                statement("", "x", "v2", "view", "upper", "recmd", "more"), statement(""), Statement(null))) {
            val response = Any()
            assertSame(response, f.execute(target) { network++; response })
        }
        assertEquals(5, network)
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), f.stages)
    }

    @Test fun `path matching is exact on non empty segments`() {
        assertTrue(matchesRecommendPath(listOf("", "x", "v2", "view", "upper", "recmd")))
        assertTrue(matchesRecommendPath(listOf("x", "v2", "view", "upper", "recmd")))
        assertFalse(matchesRecommendPath(listOf("", "x", "v2", "space", "upper", "recmd")))
        assertFalse(matchesRecommendPath(listOf("", "x", "v2", "view", "upper")))
        assertFalse(matchesRecommendPath(listOf("", "x", "v2", "view", "upper", "recmd", "1")))
        assertFalse(matchesRecommendPath(listOf("", "X", "v2", "view", "upper", "recmd")))
        assertFalse(matchesRecommendPath(emptyList<String>()))
        assertFalse(matchesRecommendPath(listOf(null, "x", "v2", "view", "upper", "recmd")))
    }

    @Test fun `a drifted family stays in the denominator while the other units keep working`() {
        for (drifted in listOf(null, PlainBody::class.java, ForeignBody::class.java)) {
            val f = Fixture(body2 = drifted)
            assertEquals(FeatureInstallResult.Installed(2, complete = false), f.install())
            assertEquals(FeatureInstallResult.Installed(1, complete = false), f.capabilities[FollowUpRecommendFeatureInstaller.CAP_REQUEST])
            assertTrue(f.statuses.single().second.startsWith("theseus2:${FollowUpRecommendFeatureInstaller.STATE_MISSING_BODY},"))
        }
    }

    @Test fun `older hosts without the compose family are complete with the view family alone`() {
        val f = Fixture(repo2 = null, body2 = null)
        assertEquals(FeatureInstallResult.Installed(2, complete = true), f.install())
        assertEquals(FeatureInstallResult.Installed(1, complete = true), f.capabilities[FollowUpRecommendFeatureInstaller.CAP_REQUEST])
        assertEquals("theseus:installed,ktor:installed", f.statuses.single().second)
    }

    @Test fun `a ktor statement without a unique request builder is a missing transport not a crash`() {
        val f = Fixture(statement = StatementWithoutBuilder::class.java)
        assertEquals(FeatureInstallResult.Installed(2, complete = false), f.install())
        val network = f.capabilities[FollowUpRecommendFeatureInstaller.CAP_NETWORK] as FeatureInstallResult.Skipped
        assertEquals(FeatureSkipReason.MISSING_HOST_STRUCTURE, network.reasonCode)
        assertNull(f.port.hooks[FollowUpRecommendFeatureInstaller.HOOK_NETWORK_KTOR])
    }

    @Test fun `hosts without any recommendation family or ktor are not applicable`() {
        val f = Fixture(repo2 = null, body2 = null, repo1 = null, body1 = null, statement = null)
        val result = f.install() as FeatureInstallResult.Skipped
        assertEquals(FeatureSkipReason.NOT_APPLICABLE_HOST, result.reasonCode)
        assertTrue(f.port.registrationAttempts.isEmpty())
        assertTrue(f.stages.isEmpty())
    }

    @Test fun `registration failure of the network layer leaves the request layer installed`() {
        val f = Fixture(failId = FollowUpRecommendFeatureInstaller.HOOK_NETWORK_KTOR)
        assertEquals(FeatureInstallResult.Installed(2, complete = false), f.install())
        assertEquals(FeatureInstallResult.Skipped("registration-failed"), f.capabilities[FollowUpRecommendFeatureInstaller.CAP_NETWORK])
        assertEquals(listOf("follow_up_recommend_network_ktor"), f.errors)
        assertNotNull(f.port.hooks["${FollowUpRecommendFeatureInstaller.HOOK_REQUEST}.theseus2"])
    }
}
