package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.invoke
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.WeChatShareClasses
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.WeChatShareCompatAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.WeChatShareVersionProfile
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import com.bilibili.socialize.share.core.BiliShareConfiguration
import com.tencent.mm.opensdk.openapi.BaseWXApiImplV10
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import com.tencent.mm.opensdk.openapi.WeChatFixtureRuntime as Runtime
import io.github.libxposed.api.XposedInterface
import kntr.common.share.common.handler.wx.WeChatShareType
import kntr.common.share.common.handler.wx.k
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Executable
import java.util.concurrent.Executors

class WeChatShareCompatFeatureInstallerTest {
    private val loader = javaClass.classLoader!!
    private val stages = mutableListOf<FeatureRuntimeStage>()
    private val registrar = object : HookRegistrar by TestHookRegistrar {
        override fun exact(id: String, owner: Class<*>, methodName: String,
            vararg parameterTypes: Class<*>, block: ModernMemberHookCreator.() -> Unit) {
            val method = owner.getDeclaredMethod(methodName, *parameterTypes)
            val creator = ModernMemberHookCreator(method).apply(block)
            Runtime.hooks[id.substringAfterLast('.')] = { receiver, args, original ->
                creator.invoke(Chain(method, receiver, args, original))
            }
        }
    }
    private val environment = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader),
        registrar, { _, _ -> }, { _, _ -> }, { _, _ -> },
        runtimeEvidence = { _, stage, _ -> stages += stage })

    fun before() = Runtime.reset()
    fun after() = Runtime.reset()
    private fun install(enabled: Boolean = true, version: Int = 8902100,
        env: HookEnvironment = environment) = WeChatShareCompatFeatureInstaller(enabled) { version }.install(env)

    fun `both share wrappers relax install register and send without changing unrelated instances`() {
        assertEquals(FeatureInstallResult.Installed(3), install())
        val legacy = kz3.a(null, BiliShareConfiguration())
        legacy.t()
        val modern = k(null, WeChatShareType.TIMELINE)
        assertTrue(modern.j())
        listOf(legacy.api!!, modern.api!!).forEach {
            assertTrue(it.isWXAppInstalled()); assertTrue(it.registerApp("share")); assertTrue(it.sendReq())
            assertFalse((it as BaseWXApiImplV10).checkSignature)
        }
        // 相同 appId 的登录/支付/回调新实例仍按原证书检查，不按 appId 全局放行。
        repeat(3) {
            val unrelated = WXAPIFactory.createWXAPI(null, "share")
            assertTrue((unrelated as BaseWXApiImplV10).checkSignature)
            assertFalse(unrelated.isWXAppInstalled()); assertFalse(unrelated.registerApp("share")); assertFalse(unrelated.sendReq())
        }
        assertTrue(FeatureRuntimeStage.APPLIED in stages)
    }

    fun `existing share instances are covered without changing another already created instance`() {
        val existing = BaseWXApiImplV10(true)
        val unrelated = BaseWXApiImplV10(true)
        val legacy = kz3.a(null, BiliShareConfiguration()).apply { api = existing }
        install(); legacy.t()
        assertSame(existing, legacy.api)
        assertTrue(existing.sendReq()); assertFalse(unrelated.sendReq())
        val modern = k(null, WeChatShareType.SESSION).apply { api = BaseWXApiImplV10(true) }
        assertTrue(modern.j()); assertTrue(modern.api!!.sendReq())
    }

    fun `missing package and rejected SDK request still fail`() {
        install()
        Runtime.installed = false
        assertThrows(IllegalStateException::class.java) { kz3.a(null, BiliShareConfiguration()).t() }
        val modern = k(null, WeChatShareType.SESSION)
        assertFalse(modern.j()); assertFalse(modern.api!!.sendReq())
        Runtime.installed = true
        Runtime.acceptRequest = false
        val legacy = kz3.a(null, BiliShareConfiguration()); legacy.t()
        assertFalse(legacy.api!!.sendReq())
    }

    fun `exception leaves no scope and another thread never inherits share scope`() {
        install()
        val failure = IllegalStateException("original initialization")
        Runtime.initializeFailure = failure
        assertSame(failure, assertThrows(IllegalStateException::class.java) { kz3.a(null, BiliShareConfiguration()).t() })
        assertTrue((WXAPIFactory.createWXAPI(null, "share") as BaseWXApiImplV10).checkSignature)
        Runtime.initializeFailure = null
        val wrapped = Runtime.hooks.getValue("legacy")
        Runtime.hooks["legacy"] = { receiver, args, original ->
            wrapped(receiver, args) { modified ->
                val executor = Executors.newSingleThreadExecutor()
                try {
                    assertTrue(executor.submit<Boolean> {
                        (WXAPIFactory.createWXAPI(null, "share") as BaseWXApiImplV10).checkSignature
                    }.get())
                } finally { executor.shutdownNow() }
                original(modified)
            }
        }
        kz3.a(null, BiliShareConfiguration()).t()
    }

    fun `disabled other process and unknown host install nothing and do not read version when disabled`() {
        assertEquals(FeatureInstallResult.Skipped("disabled"), WeChatShareCompatFeatureInstaller(false) {
            error("disabled must be inert")
        }.install(environment))
        assertTrue(install(env = environment.copy(processName = "tv.danmaku.bili:web")) is FeatureInstallResult.Skipped)
        assertTrue(install(version = 9110000) is FeatureInstallResult.Skipped)
        assertTrue(install(env = environment.copy(classLoader = null)) is FeatureInstallResult.Skipped)
        assertTrue(Runtime.hooks.isEmpty())
    }

    fun `factory failure and wrapper failures do not claim full installation`() {
        val failing = object : HookRegistrar by TestHookRegistrar {
            override fun exact(id: String, owner: Class<*>, methodName: String,
                vararg parameterTypes: Class<*>, block: ModernMemberHookCreator.() -> Unit) {
                if (id.endsWith("factory")) throw IllegalStateException()
            }
        }
        assertEquals(FeatureInstallResult.Skipped("registration-failed"), install(env = environment.copy(registrar = failing)))
        assertTrue(stages.isEmpty())
        val partial = object : HookRegistrar by registrar {
            override fun exact(id: String, owner: Class<*>, methodName: String,
                vararg parameterTypes: Class<*>, block: ModernMemberHookCreator.() -> Unit) {
                if (id.endsWith("kntr")) throw IllegalStateException()
                registrar.exact(id, owner, methodName, *parameterTypes, block = block)
            }
        }
        assertEquals(FeatureInstallResult.Installed(2, false), install(env = environment.copy(registrar = partial)))
    }

    fun `original signed receiver succeeds when option is off and no instance changes`() {
        Runtime.trustedCertificate = true
        install(false)
        val legacy = kz3.a(null, BiliShareConfiguration()); legacy.t()
        assertTrue(legacy.api!!.sendReq()); assertTrue((legacy.api as BaseWXApiImplV10).checkSignature)
        assertTrue(stages.isEmpty())
    }

    fun `missing one audited wrapper is partial and missing SDK preserves all behavior`() {
        fun without(name: String) = object : ClassLoader(loader) {
            override fun loadClass(className: String, resolve: Boolean): Class<*> {
                if (className == name) throw ClassNotFoundException(className)
                return super.loadClass(className, resolve)
            }
        }
        val partialLoader = without("kntr.common.share.common.handler.wx.k")
        assertEquals(FeatureInstallResult.Installed(2, false), install(env = environment.copy(
            classLoader = partialLoader, hookPoints = HookPointRegistry(partialLoader))))
        Runtime.hooks.clear()
        val missingSdk = without("com.tencent.mm.opensdk.openapi.WXAPIFactory")
        assertTrue(install(env = environment.copy(classLoader = missingSdk,
            hookPoints = HookPointRegistry(missingSdk))) is FeatureInstallResult.Skipped)
        assertTrue(Runtime.hooks.isEmpty())
    }

    fun `older host with one share wrapper installs completely`() {
        assertEquals(FeatureInstallResult.Installed(2), install(version = 8840200))
        val legacy = ft3.a(null, BiliShareConfiguration())
        legacy.t()
        assertTrue(legacy.api!!.sendReq())
        assertFalse((legacy.api as BaseWXApiImplV10).checkSignature)
        assertTrue((WXAPIFactory.createWXAPI(null, "login") as BaseWXApiImplV10).checkSignature)
    }

    fun `newer host uses its verified wrappers and leaves unrelated instances alone`() {
        assertEquals(FeatureInstallResult.Installed(3), install(version = 8970300))
        val legacy = Ov0.a()
        legacy.l()
        val modern = kntr.common.share.common.handler.wx.i(null, WeChatShareType.TIMELINE)
        assertTrue(modern.b())
        assertTrue(legacy.api!!.sendReq())
        assertTrue(modern.api!!.sendReq())
        assertTrue((WXAPIFactory.createWXAPI(null, "payment") as BaseWXApiImplV10).checkSignature)
    }

    fun `newer host reports partial when the legacy share signature cannot be verified`() {
        val noPage = object : ClassLoader(loader) {
            override fun loadClass(className: String, resolve: Boolean): Class<*> {
                if (className == "com.bilibili.socialize.share.core.shareparam.ShareParamWebPage") {
                    throw ClassNotFoundException(className)
                }
                return super.loadClass(className, resolve)
            }
        }
        assertEquals(FeatureInstallResult.Installed(2, false), install(version = 8970300,
            env = environment.copy(classLoader = noPage, hookPoints = HookPointRegistry(noPage))))
        assertFalse(Runtime.hooks.containsKey("legacy"))
        assertTrue(Runtime.hooks.containsKey("kntr"))
    }

    fun `audited profile includes the locally verified build and distinguishes nearby variants`() {
        assertEquals("gz0.a", WeChatShareVersionProfile.forVersion(9130500)?.legacyOwner)
        assertEquals("kntr.common.share.common.handler.wx.l", WeChatShareVersionProfile.forVersion(9170300)?.modernOwner)
        assertNull(WeChatShareVersionProfile.forVersion(9130400))
    }

    class FakeNonZeroArgModernWx(
        @Suppress("UNUSED_PARAMETER") context: android.content.Context,
        @Suppress("UNUSED_PARAMETER") type: WeChatShareType
    ) {
        @JvmField val api: com.tencent.mm.opensdk.openapi.IWXAPI? = null
        @Suppress("UNUSED_PARAMETER")
        fun initialize(mode: Int): Boolean = true
    }

    fun `drifted profile falls back to cached classes and retries idempotently`() {
        val baseLoader = requireNotNull(javaClass.classLoader)
        val driftedLoader = TestRenamingClassLoader(parent = baseLoader, hiddenNames = setOf("gz0.a"))
        val cachedClasses = WeChatShareClasses(
            legacyOwner = "kz3.a",
            legacyInitialize = "t",
            modernOwner = "kntr.common.share.common.handler.wx.k",
            modernInitialize = "j",
            usesLegacyConstructors = true
        )
        assertNull(WeChatShareCompatAccess.locate(driftedLoader, 9130500, cached = null))
        assertNotNull(WeChatShareCompatAccess.locate(driftedLoader, 9130500, cached = cachedClasses))

        val portRegistrar = PlayerPortTestRegistrar()
        val statuses = linkedMapOf<String, String>()
        val retryStages = mutableListOf<FeatureRuntimeStage>()
        val env = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = driftedLoader,
            hookPoints = HookPointRegistry(driftedLoader),
            registrar = portRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { k, v -> statuses[k] = v },
            runtimeEvidence = { _, stage, _ -> retryStages += stage }
        )

        val installer = WeChatShareCompatFeatureInstaller(
            enabled = true,
            cachedClasses = null,
            hostVersionCode = { 9130500 }
        )
        assertEquals(FeatureInstallResult.Skipped("missing-verified-share-structure"), installer.install(env))
        assertTrue(installer.requiresAdaptationRetry)
        assertTrue(portRegistrar.registrationAttempts.isEmpty())

        installer.updateAdaptedClasses(cachedClasses)
        val run2 = installer.install(env) as FeatureInstallResult.Installed
        assertEquals(3, run2.hookCount)
        assertTrue(run2.complete)
        assertFalse(installer.requiresAdaptationRetry)
        assertEquals("success", statuses["wechat_share_compat_status"])
        assertEquals(3, portRegistrar.registrationAttempts.size)
        assertTrue(FeatureRuntimeStage.ADAPTED in retryStages)

        val run3 = installer.install(env) as FeatureInstallResult.Installed
        assertEquals(3, run3.hookCount)
        assertTrue(run3.complete)
        assertEquals(3, portRegistrar.registrationAttempts.size)
    }

    fun `partial profile drift falls back to complete cache and discover rejects parameterized initialize`() {
        val baseLoader = requireNotNull(javaClass.classLoader)
        val partialLoader = TestRenamingClassLoader(
            parent = baseLoader,
            hiddenNames = setOf("kntr.common.share.common.handler.wx.k")
        )
        val directPartial = requireNotNull(WeChatShareCompatAccess.locate(partialLoader, 8890400, cached = null))
        assertEquals(1, directPartial.wrappers.size)
        assertEquals(2, directPartial.expectedWrappers)

        val completeCache = WeChatShareClasses(
            legacyOwner = "kz3.a",
            legacyInitialize = "t",
            modernOwner = "FlatWave1WeChatWrapper",
            modernInitialize = "m",
            usesLegacyConstructors = true
        )
        val preferredCache = requireNotNull(WeChatShareCompatAccess.locate(partialLoader, 8890400, cached = completeCache))
        assertEquals(2, preferredCache.wrappers.size)

        val portRegistrar = PlayerPortTestRegistrar()
        val env = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = partialLoader,
            hookPoints = HookPointRegistry(partialLoader),
            registrar = portRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> }
        )
        val installer = WeChatShareCompatFeatureInstaller(
            enabled = true,
            cachedClasses = null,
            hostVersionCode = { 8890400 }
        )
        val run1 = installer.install(env) as FeatureInstallResult.Installed
        assertEquals(2, run1.hookCount)
        assertFalse(run1.complete)
        assertTrue(installer.requiresAdaptationRetry)
        assertEquals(2, portRegistrar.registrationAttempts.size)

        installer.updateAdaptedClasses(completeCache)
        val run2 = installer.install(env) as FeatureInstallResult.Installed
        assertEquals(3, run2.hookCount)
        assertTrue(run2.complete)
        assertFalse(installer.requiresAdaptationRetry)
        assertEquals(3, portRegistrar.registrationAttempts.size)
        assertEquals(
            listOf("wechat.share.factory", "wechat.share.legacy", "wechat.share.kntr"),
            portRegistrar.registrationAttempts.map { it.first }
        )

        val wxBadLoader = TestRenamingClassLoader(
            parent = baseLoader,
            templates = mapOf("kntr.common.share.common.handler.wx.BadModern" to FakeNonZeroArgModernWx::class.java)
        )
        val legacyInit = wxBadLoader.loadClass("kz3.a").getMethod("t")
        val badModernInit = wxBadLoader.loadClass("kntr.common.share.common.handler.wx.BadModern")
            .getMethod("initialize", Int::class.javaPrimitiveType)
        assertNull(
            WeChatShareCompatAccess.discover(
                wxBadLoader,
                DexAssistResult.Candidates(listOf(legacyInit, badModernInit))
            )
        )
    }

    private class Chain(private val member: Executable, private val receiver: Any?,
        private val arguments: Array<Any?>, private val original: (Array<Any?>) -> Any?) : XposedInterface.Chain {
        override fun getExecutable(): Executable = member
        override fun getThisObject(): Any? = receiver
        override fun getArgs(): List<Any?> = arguments.toList()
        override fun getArg(index: Int): Any? = arguments[index]
        override fun proceed(): Any? = original(arguments)
        override fun proceed(args: Array<Any?>): Any? = original(args)
        override fun proceedWith(thisObject: Any): Any? = proceed()
        override fun proceedWith(thisObject: Any, args: Array<Any?>): Any? = proceed(args)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (WeChatShareCompatFeatureInstallerTest) -> Unit) {
            val s = WeChatShareCompatFeatureInstallerTest()
            s.before()
            try {
                block(s)
            } finally {
                s.after()
            }
        }
        runIsolated { s -> s.`both share wrappers relax install register and send without changing unrelated instances`() }
        runIsolated { s -> s.`existing share instances are covered without changing another already created instance`() }
        runIsolated { s -> s.`missing package and rejected SDK request still fail`() }
        runIsolated { s -> s.`exception leaves no scope and another thread never inherits share scope`() }
        runIsolated { s -> s.`disabled other process and unknown host install nothing and do not read version when disabled`() }
        runIsolated { s -> s.`factory failure and wrapper failures do not claim full installation`() }
        runIsolated { s -> s.`original signed receiver succeeds when option is off and no instance changes`() }
        runIsolated { s -> s.`missing one audited wrapper is partial and missing SDK preserves all behavior`() }
        runIsolated { s -> s.`older host with one share wrapper installs completely`() }
        runIsolated { s -> s.`newer host uses its verified wrappers and leaves unrelated instances alone`() }
        runIsolated { s -> s.`newer host reports partial when the legacy share signature cannot be verified`() }
        runIsolated { s -> s.`audited profile includes the locally verified build and distinguishes nearby variants`() }
        runIsolated { s -> s.`drifted profile falls back to cached classes and retries idempotently`() }
        runIsolated { s -> s.`partial profile drift falls back to complete cache and discover rejects parameterized initialize`() }
    }
}
