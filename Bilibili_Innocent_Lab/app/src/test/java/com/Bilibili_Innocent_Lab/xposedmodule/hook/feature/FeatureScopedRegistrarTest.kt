package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeatureScopedRegistrarTest {
    private val loader = javaClass.classLoader!!

    private class Target {
        fun run() = Unit
    }

    /** 记录每次登记里 DSL 块执行后的故障归属，其余行为与空登记器相同。 */
    private class Recorder : HookRegistrar by TestHookRegistrar {
        val owners = mutableListOf<Pair<String, String?>>()
        override fun exact(
            id: String, owner: Class<*>, methodName: String, vararg parameterTypes: Class<*>,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            owners += id to ModernMemberHookCreator(owner.getDeclaredMethod(methodName, *parameterTypes)).apply(block).faultOwner
        }
    }

    @Test
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

    @Test
    fun `a hook registered outside any installer has no owner`() {
        val creator = ModernMemberHookCreator(Target::class.java.getDeclaredMethod("run"))
        assertNull(creator.faultOwner)
    }

    @Test
    fun `the default exception policy is unchanged by scoping`() {
        assertEquals(HookExceptionPolicy.PROTECT_HOST, HookExceptionPolicy.values().first())
    }
}
