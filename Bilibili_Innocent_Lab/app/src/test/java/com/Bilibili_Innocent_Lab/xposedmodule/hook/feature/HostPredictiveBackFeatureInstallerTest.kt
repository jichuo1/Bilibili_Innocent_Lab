package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.ComponentActivity
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import io.github.jichuo1.meguviz.hook.api.ErrorPolicy
import io.github.jichuo1.meguviz.hook.api.HookId
import io.github.jichuo1.meguviz.hook.core.HookEnvironment as MeguvizHookEnvironment
import io.github.jichuo1.meguviz.hook.testing.FakeBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Constructor
import java.lang.reflect.Method

class HostPredictiveBackFeatureInstallerTest {
    object FakeChecker {
        var nextOriginalResult: Boolean = false

        @JvmStatic
        fun isOnBackInvokedCallbackEnabled(context: Context?): Boolean = nextOriginalResult
    }

    object FakeDispatcher {
        var nextOriginalResult: Boolean = false

        @JvmStatic
        fun isOnBackInvokedCallbackEnabled(context: Context?): Boolean = nextOriginalResult
    }

    object FakeEmptyPlatformClass

    private open class FakeEligibleActivity : ComponentActivity()

    private class FakeCustomBackActivity : ComponentActivity() {
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onBackPressed() = Unit
    }

    private class FakeLegacyActivity : Activity()

    private class FakeNonActivityContext : ContextWrapper(null) {
        override fun getBaseContext(): Context? = null
    }

    private class FakeBackendRegistrar(
        private val failId: String? = null
    ) : HookRegistrar, AutoCloseable {
        val backend: FakeBackend = FakeBackend.create()
        private val megEnv: MeguvizHookEnvironment = MeguvizHookEnvironment.create(backend)
        val installedIds = linkedSetOf<String>()

        override fun first(
            id: String,
            className: String,
            methodName: String,
            block: ModernMemberHookCreator.() -> Unit
        ) = error("Unexpected first() call for $id")

        override fun all(
            id: String,
            className: String,
            methodName: String,
            block: ModernMemberHookCreator.() -> Unit
        ) = error("Unexpected all() call for $id")

        override fun exact(
            id: String,
            owner: Class<*>,
            methodName: String,
            vararg parameterTypes: Class<*>,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            check(id != failId) { "Simulated registration failure for $id" }
            val method = owner.getDeclaredMethod(methodName, *parameterTypes)
            val spec = ModernMemberHookCreator(method).apply(block)
                .toSpec(HookId.of(id), ErrorPolicy.PROPAGATE)
            val receipt = megEnv.install(spec)
            assertTrue(receipt.message(), receipt.isInstalled)
            installedIds += id
        }

        override fun adapted(
            id: String,
            point: VersionAdapter.HookPoint,
            exceptionPolicy: HookExceptionPolicy,
            block: ModernMemberHookCreator.() -> Unit
        ) = error("Unexpected adapted() call for $id")

        override fun constructor(
            id: String,
            constructor: Constructor<*>,
            block: ModernMemberHookCreator.() -> Unit
        ) = error("Unexpected constructor() call for $id")

        override fun close() {
            megEnv.close()
        }
    }

    private class Fixture(
        process: String = "tv.danmaku.bili",
        failId: String? = null
    ) : AutoCloseable {
        val registrar = FakeBackendRegistrar(failId)
        val stagesById = linkedMapOf<String, MutableList<FeatureRuntimeStage>>()
        val statuses = linkedMapOf<String, String>()
        val errors = mutableListOf<String>()
        val syncedActivities = mutableListOf<String>()
        var systemEnabled = true
        var activitySyncResult = true

        val checkerMethod: Method = FakeChecker::class.java
            .getDeclaredMethod("isOnBackInvokedCallbackEnabled", Context::class.java)
        val dispatcherMethod: Method = FakeDispatcher::class.java
            .getDeclaredMethod("isOnBackInvokedCallbackEnabled", Context::class.java)
        val instrumentationOnCreate2Args: Method = Instrumentation::class.java
            .getDeclaredMethod("callActivityOnCreate", Activity::class.java, Bundle::class.java)
        val instrumentationOnCreate3Args: Method = Instrumentation::class.java.getDeclaredMethod(
            "callActivityOnCreate",
            Activity::class.java,
            Bundle::class.java,
            PersistableBundle::class.java
        )

        val environment = HookEnvironment(
            processName = process,
            classLoader = javaClass.classLoader,
            hookPoints = HookPointRegistry(javaClass.classLoader!!),
            registrar = registrar,
            logInfo = { _, _ -> },
            logError = { key, _ -> errors += key },
            reportStatus = { key, value -> statuses[key] = value },
            runtimeEvidence = { id, stage, _ ->
                stagesById.getOrPut(id) { mutableListOf() } += stage
            }
        )

        init {
            FakeChecker.nextOriginalResult = false
            FakeDispatcher.nextOriginalResult = false
            registrar.backend.onTarget(checkerMethod) { _, arguments ->
                FakeChecker.isOnBackInvokedCallbackEnabled(arguments.firstOrNull() as? Context)
            }
            registrar.backend.onTarget(dispatcherMethod) { _, arguments ->
                FakeDispatcher.isOnBackInvokedCallbackEnabled(arguments.firstOrNull() as? Context)
            }
            registrar.backend.onTarget(instrumentationOnCreate2Args) { _, _ -> null }
            registrar.backend.onTarget(instrumentationOnCreate3Args) { _, _ -> null }
        }

        fun createInstaller(
            enabled: Boolean = true,
            apiSupported: Boolean = true,
            dispatcherClass: Class<*>? = FakeDispatcher::class.java,
            checkerClass: Class<*>? = FakeChecker::class.java
        ) = HostPredictiveBackFeatureInstaller(
            enabled = enabled,
            apiSupported = { apiSupported },
            systemFeatureEnabled = { systemEnabled },
            dispatcherClassProvider = { dispatcherClass },
            checkerClassProvider = { checkerClass },
            activityInfoEnabler = { activity ->
                syncedActivities += activity.javaClass.name
                activitySyncResult
            }
        )

        fun callChecker(context: Context?): Boolean =
            registrar.backend.call(checkerMethod, null, context) as Boolean

        fun callDispatcher(context: Context?): Boolean =
            registrar.backend.call(dispatcherMethod, null, context) as Boolean

        fun callInstrumentationOnCreate(activity: Activity) {
            val stubInstrumentation = allocateWithoutConstructor(Instrumentation::class.java)
            registrar.backend.call(instrumentationOnCreate2Args, stubInstrumentation, activity, null)
        }

        override fun close() = registrar.close()
    }

    @Test
    fun `respects enable toggle process scope and minimum android api`() {
        Fixture().use { fixture ->
            val disabled = fixture.createInstaller(enabled = false).install(fixture.environment)
            assertEquals(FeatureInstallResult.Skipped("disabled"), disabled)
            assertEquals("disabled", fixture.statuses[HostPredictiveBackFeatureInstaller.STATUS])
            assertTrue(fixture.registrar.installedIds.isEmpty())
        }

        Fixture(process = "tv.danmaku.bili:download").use { fixture ->
            val webInstaller = fixture.createInstaller(enabled = true)
            assertTrue(webInstaller.install(fixture.environment) is FeatureInstallResult.Installed)
        }

        Fixture(process = "other.process").use { fixture ->
            val skipped = fixture.createInstaller(enabled = true).install(fixture.environment)
            assertEquals(FeatureInstallResult.Skipped("non-main-process"), skipped)
            assertTrue(fixture.registrar.installedIds.isEmpty())
        }

        Fixture().use { fixture ->
            val lowApi = fixture.createInstaller(enabled = true, apiSupported = false).install(fixture.environment)
            assertEquals(FeatureInstallResult.Skipped("not-applicable-host"), lowApi)
            assertEquals("not-applicable-host", fixture.statuses[HostPredictiveBackFeatureInstaller.STATUS])
        }
    }

    @Test
    fun `missing or failed platform dispatchers never report installed`() {
        Fixture().use { fixture ->
            val missing = fixture.createInstaller(
                dispatcherClass = null,
                checkerClass = FakeEmptyPlatformClass::class.java
            ).install(fixture.environment)
            assertEquals(FeatureInstallResult.Skipped("missing-platform-dispatcher"), missing)
            assertEquals("missing-platform-dispatcher", fixture.statuses[HostPredictiveBackFeatureInstaller.STATUS])
            assertTrue(fixture.stagesById.isEmpty())
        }

        Fixture(failId = HostPredictiveBackFeatureInstaller.HOOK_CHECKER_ENABLED).use { fixture ->
            val failed = fixture.createInstaller(dispatcherClass = null).install(fixture.environment)
            assertEquals(FeatureInstallResult.Skipped("registration-failed"), failed)
            assertEquals("registration-failed", fixture.statuses[HostPredictiveBackFeatureInstaller.STATUS])
            assertTrue("host_predictive_back_checker" in fixture.errors)
        }
    }

    @Test
    fun `checker and dispatcher gates enable eligible activities only while respecting system switch`() {
        Fixture().use { fixture ->
            val installer = fixture.createInstaller()
            val result = installer.install(fixture.environment)
            assertEquals(FeatureInstallResult.Installed(4, complete = true), result)
            assertEquals(
                listOf(FeatureRuntimeStage.ADAPTED),
                fixture.stagesById[HostPredictiveBackFeatureInstaller.ID]
            )

            // 非 Activity 上下文及自定义返回页保持原返回值 false。
            val nonActivity = allocateWithoutConstructor(FakeNonActivityContext::class.java)
            assertFalse(fixture.callChecker(nonActivity))
            assertEquals(
                listOf(FeatureRuntimeStage.ADAPTED),
                fixture.stagesById[HostPredictiveBackFeatureInstaller.ID]
            )

            val customBack = allocateWithoutConstructor(FakeCustomBackActivity::class.java)
            assertFalse(fixture.callChecker(customBack))
            assertEquals(
                listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED),
                fixture.stagesById[HostPredictiveBackFeatureInstaller.ID]
            )

            val legacyActivity = allocateWithoutConstructor(FakeLegacyActivity::class.java)
            assertFalse(fixture.callChecker(legacyActivity))

            // 系统总开关关闭时不强行覆盖。
            val eligible = allocateWithoutConstructor(FakeEligibleActivity::class.java)
            fixture.systemEnabled = false
            assertFalse(fixture.callChecker(eligible))
            assertEquals(
                listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED),
                fixture.stagesById[HostPredictiveBackFeatureInstaller.ID]
            )

            // 系统允许时，标准 ComponentActivity 子类从 false 提升为 true 并上报 APPLIED。
            fixture.systemEnabled = true
            assertTrue(fixture.callChecker(eligible))
            assertTrue(fixture.callDispatcher(eligible))
            assertEquals(
                listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED),
                fixture.stagesById[HostPredictiveBackFeatureInstaller.ID]
            )
        }
    }

    @Test
    fun `instrumentation pre-create sync runs only for eligible activities when sync succeeds`() {
        Fixture().use { fixture ->
            val installer = fixture.createInstaller()
            val result = installer.install(fixture.environment)
            assertEquals(FeatureInstallResult.Installed(4, complete = true), result)

            val custom = allocateWithoutConstructor(FakeCustomBackActivity::class.java)
            fixture.callInstrumentationOnCreate(custom)
            assertTrue(fixture.syncedActivities.isEmpty())
            assertEquals(
                listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED),
                fixture.stagesById[HostPredictiveBackFeatureInstaller.ID]
            )

            val eligible = allocateWithoutConstructor(FakeEligibleActivity::class.java)
            fixture.activitySyncResult = false
            fixture.callInstrumentationOnCreate(eligible)
            assertEquals(listOf(FakeEligibleActivity::class.java.name), fixture.syncedActivities)
            // 同步返回 false 时不提前上报 APPLIED。
            assertEquals(
                listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED),
                fixture.stagesById[HostPredictiveBackFeatureInstaller.ID]
            )

            fixture.activitySyncResult = true
            fixture.callInstrumentationOnCreate(eligible)
            assertEquals(
                listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED),
                fixture.stagesById[HostPredictiveBackFeatureInstaller.ID]
            )
        }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private fun <T> allocateWithoutConstructor(type: Class<T>): T {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = field.get(null)
            val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
            return allocateInstance.invoke(unsafe, type) as T
        }
    }
}
