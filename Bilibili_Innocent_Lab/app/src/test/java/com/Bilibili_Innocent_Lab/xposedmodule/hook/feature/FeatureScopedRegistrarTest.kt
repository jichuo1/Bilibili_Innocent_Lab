package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FeatureScopedRegistrarTest {
    private val loader = javaClass.classLoader!!

    private class Target {
        fun run() = Unit
    }

    /** 记录每次登记里 DSL 块执行后的故障归属，其余行为与空登记器相同。 */
    private class Recorder : HookRegistrar by TestHookRegistrar {
        override val rootRegistrar: HookRegistrar get() = this
        val owners = mutableListOf<Pair<String, String?>>()
        override fun exact(
            id: String, owner: Class<*>, methodName: String, vararg parameterTypes: Class<*>,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            owners += id to ModernMemberHookCreator(owner.getDeclaredMethod(methodName, *parameterTypes)).apply(block).faultOwner
        }
    }

    fun `a coordinated installer stamps its own id on every hook it registers`() {
        val recorder = Recorder()
        val environment = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), recorder,
            { _, _ -> }, { _, _ -> }, { _, _ -> })
        FeatureInstallCoordinator(environment).installAll(listOf(
            FunctionalFeatureInstaller("feature_one") { env ->
                env.registrar.exact("one.a", Target::class.java, "run") { }
                env.registrar.exact("one.b", Target::class.java, "run") { before { } }
                FeatureInstallResult.Installed(2)
            },
            FunctionalFeatureInstaller("feature_two") { env ->
                env.registrar.exact("two.a", Target::class.java, "run") { after { } }
                FeatureInstallResult.Installed(1)
            }
        ))
        assertEquals(
            listOf("one.a" to "feature_one", "one.b" to "feature_one", "two.a" to "feature_two"),
            recorder.owners
        )
    }

    fun `a hook registered outside any installer has no owner`() {
        val creator = ModernMemberHookCreator(Target::class.java.getDeclaredMethod("run"))
        assertNull(creator.faultOwner)
    }

    fun `the default exception policy is unchanged by scoping`() {
        assertEquals(HookExceptionPolicy.PROTECT_HOST, HookExceptionPolicy.values().first())
    }

    fun `rootRegistrar unwraps multi level scoping and delegate wrappers back to root`() {
        val root = Recorder()
        val delegated = object : HookRegistrar by root {}
        val nested = FeatureScopedRegistrar(FeatureScopedRegistrar(delegated, "outer"), "inner")
        val testDelegated = object : HookRegistrar by TestHookRegistrar {}
        val testScoped = FeatureScopedRegistrar(testDelegated, "test_feature")

        assertSame(root, root.rootRegistrar)
        assertSame(root, delegated.rootRegistrar)
        assertSame(root, nested.rootRegistrar)
        assertSame(TestHookRegistrar, TestHookRegistrar.rootRegistrar)
        assertSame(TestHookRegistrar, testScoped.rootRegistrar)

        val port = PlayerPortTestRegistrar()
        val wrappedPort = object : HookRegistrar by port {}
        assertSame(port, port.rootRegistrar)
        assertSame(port, wrappedPort.rootRegistrar)
        assertSame(port, FeatureScopedRegistrar(wrappedPort, "cap").rootRegistrar)
    }

    fun `forCapabilityRuntime attributes immediate and delayed hooks to the leaf capability id`() {
        val recorder = Recorder()
        val runtimeStages = mutableListOf<Triple<String, FeatureRuntimeStage, Int>>()
        val loggedErrors = mutableListOf<String>()
        var delayedInstall: (() -> Unit)? = null

        val environment = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = loader,
            hookPoints = HookPointRegistry(loader),
            registrar = recorder,
            logInfo = { _, _ -> },
            logError = { key, _ -> loggedErrors += key },
            reportStatus = { _, _ -> },
            runtimeEvidence = { id, stage, count -> runtimeStages += Triple(id, stage, count) }
        )

        FeatureInstallCoordinator(environment).installAll(listOf(
            object : FeatureInstaller {
                override val id = "parent_feature"
                override val capabilityIds = listOf("leaf_alpha", "leaf_beta")
                override fun install(environment: HookEnvironment): FeatureInstallResult {
                    environment.registrar.exact("parent.hook", Target::class.java, "run") { }
                    val leafAlphaEnv = environment.forCapabilityRuntime("leaf_alpha")
                    assertSame(recorder, leafAlphaEnv.registrar.rootRegistrar)
                    leafAlphaEnv.registrar.exact("leaf.alpha.immediate", Target::class.java, "run") { }

                    val leafBetaEnv = environment.forCapabilityRuntime("leaf_beta")
                    delayedInstall = {
                        leafBetaEnv.registrar.exact("leaf.beta.delayed", Target::class.java, "run") { }
                        leafBetaEnv.logError("delayed_key", "boom")
                    }
                    return FeatureInstallResult.Installed(2)
                }
            }
        ))

        delayedInstall!!.invoke()

        assertEquals(
            listOf(
                "parent.hook" to "parent_feature",
                "leaf.alpha.immediate" to "leaf_alpha",
                "leaf.beta.delayed" to "leaf_beta"
            ),
            recorder.owners
        )
        assertEquals(listOf("delayed_key"), loggedErrors)
        assertEquals(listOf(Triple("leaf_beta", FeatureRuntimeStage.ERROR, 1)), runtimeStages)

        // Even without a runtimeEvidence sink, forCapabilityRuntime still scopes registrar to the leaf id.
        val noSinkRecorder = Recorder()
        val noSinkEnv = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = loader,
            hookPoints = HookPointRegistry(loader),
            registrar = FeatureScopedRegistrar(noSinkRecorder, "parent_feature"),
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> },
            runtimeEvidence = null
        )
        val noSinkLeafEnv = noSinkEnv.forCapabilityRuntime("leaf_no_sink")
        assertSame(noSinkRecorder, noSinkLeafEnv.registrar.rootRegistrar)
        noSinkLeafEnv.registrar.exact("leaf.no_sink", Target::class.java, "run") { }
        assertEquals(listOf("leaf.no_sink" to "leaf_no_sink"), noSinkRecorder.owners)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FeatureScopedRegistrarTest) -> Unit) {
            val s = FeatureScopedRegistrarTest()
            block(s)
        }
        runIsolated { s -> s.`a coordinated installer stamps its own id on every hook it registers`() }
        runIsolated { s -> s.`a hook registered outside any installer has no owner`() }
        runIsolated { s -> s.`the default exception policy is unchanged by scoping`() }
        runIsolated { s -> s.`rootRegistrar unwraps multi level scoping and delegate wrappers back to root`() }
        runIsolated { s -> s.`forCapabilityRuntime attributes immediate and delayed hooks to the leaf capability id`() }
    }
}
