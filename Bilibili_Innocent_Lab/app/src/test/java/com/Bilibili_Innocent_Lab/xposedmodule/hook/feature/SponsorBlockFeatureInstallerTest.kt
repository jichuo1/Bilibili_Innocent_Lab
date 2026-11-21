package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import android.app.Activity
import android.app.Instrumentation
import org.junit.Assert.*
import org.junit.Test

class SponsorBlockFeatureInstallerTest {
    private open class LifecycleBase {
        open fun onResume() {}
    }
    private class LifecycleDetail : LifecycleBase() {
        override fun onResume() {}
        fun onResume(state: Boolean) {}
    }
    private fun environment(process: String = "tv.danmaku.bili", loader: ClassLoader? = null) = HookEnvironment(
        process, loader, HookPointRegistry(loader), TestHookRegistrar, { _, _ -> }, { _, _ -> }, { _, _ -> })

    fun disabledAndSecondaryProcessesNeverReachHostLookupOrCreateNetworkWorkers() {
        val unusable = object : ClassLoader() {
            override fun loadClass(name: String): Class<*> = throw AssertionError("Unexpected lookup: $name")
        }
        assertEquals(FeatureInstallResult.Skipped("disabled"), SponsorBlockFeatureInstaller(false, true)
            .install(environment(loader = unusable)))
        assertEquals(FeatureInstallResult.Skipped("non-main-process"), SponsorBlockFeatureInstaller(true, true)
            .install(environment("tv.danmaku.bili:push", unusable)))
    }

    fun missingLoaderAndMissingDetailBoundaryKeepTheHostUnchanged() {
        assertEquals(FeatureInstallResult.Skipped("missing-class-loader"), SponsorBlockFeatureInstaller(true, false)
            .install(environment()))
        val withoutDetail = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == SponsorBlockFeatureInstaller.DETAIL) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        assertEquals(FeatureInstallResult.Skipped("missing-detail-activity"), SponsorBlockFeatureInstaller(true, false)
            .install(environment(loader = withoutDetail)))
    }

    fun lifecycleLookupMustTargetTheActualNoArgumentOverride() {
        val method = com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
            .inheritedMethodOrNull(LifecycleDetail::class.java, "onResume")
        assertEquals(LifecycleDetail::class.java, method?.declaringClass)
        assertEquals(0, method?.parameterCount)
    }

    fun frameworkLifecycleDispatchersCarryTheActualActivityAsAnArgument() {
        for (name in listOf("callActivityOnResume", "callActivityOnPause", "callActivityOnDestroy")) {
            val method = com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
                .methodOrNull(Instrumentation::class.java, name, Activity::class.java)
            assertNotNull(method)
            assertEquals(Void.TYPE, method?.returnType)
            assertEquals(listOf(Activity::class.java), method?.parameterTypes?.toList())
        }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (SponsorBlockFeatureInstallerTest) -> Unit) {
            val s = SponsorBlockFeatureInstallerTest()
            block(s)
        }
        runIsolated { s -> s.disabledAndSecondaryProcessesNeverReachHostLookupOrCreateNetworkWorkers() }
        runIsolated { s -> s.missingLoaderAndMissingDetailBoundaryKeepTheHostUnchanged() }
        runIsolated { s -> s.lifecycleLookupMustTargetTheActualNoArgumentOverride() }
        runIsolated { s -> s.frameworkLifecycleDispatchersCarryTheActualActivityAsAnArgument() }
    }
}
