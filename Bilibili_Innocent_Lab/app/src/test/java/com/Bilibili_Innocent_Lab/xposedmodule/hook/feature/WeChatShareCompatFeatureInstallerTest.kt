package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.WeChatShareCompatAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
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

    @Before fun before() = Runtime.reset()
    @After fun after() = Runtime.reset()
    private fun install(enabled: Boolean = true, version: Int = WeChatShareCompatAccess.HOST_VERSION,
        env: HookEnvironment = environment) = WeChatShareCompatFeatureInstaller(enabled) { version }.install(env)

    @Test fun `both share wrappers relax install register and send without changing unrelated instances`() {
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

    @Test fun `existing share instances are covered without changing another already created instance`() {
        val existing = BaseWXApiImplV10(true)
        val unrelated = BaseWXApiImplV10(true)
        val legacy = kz3.a(null, BiliShareConfiguration()).apply { api = existing }
        install(); legacy.t()
        assertSame(existing, legacy.api)
        assertTrue(existing.sendReq()); assertFalse(unrelated.sendReq())
        val modern = k(null, WeChatShareType.SESSION).apply { api = BaseWXApiImplV10(true) }
        assertTrue(modern.j()); assertTrue(modern.api!!.sendReq())
    }

    @Test fun `missing package and rejected SDK request still fail`() {
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

    @Test fun `exception leaves no scope and another thread never inherits share scope`() {
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

    @Test fun `disabled other process and unknown host install nothing and do not read version when disabled`() {
        assertEquals(FeatureInstallResult.Skipped("disabled"), WeChatShareCompatFeatureInstaller(false) {
            error("disabled must be inert")
        }.install(environment))
        assertTrue(install(env = environment.copy(processName = "tv.danmaku.bili:web")) is FeatureInstallResult.Skipped)
        assertTrue(install(version = 9110000) is FeatureInstallResult.Skipped)
        assertTrue(install(env = environment.copy(classLoader = null)) is FeatureInstallResult.Skipped)
        assertTrue(Runtime.hooks.isEmpty())
    }

    @Test fun `factory failure and wrapper failures do not claim full installation`() {
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

    @Test fun `original signed receiver succeeds when option is off and no instance changes`() {
        Runtime.trustedCertificate = true
        install(false)
        val legacy = kz3.a(null, BiliShareConfiguration()); legacy.t()
        assertTrue(legacy.api!!.sendReq()); assertTrue((legacy.api as BaseWXApiImplV10).checkSignature)
        assertTrue(stages.isEmpty())
    }

    @Test fun `missing one audited wrapper is partial and missing SDK preserves all behavior`() {
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
}
