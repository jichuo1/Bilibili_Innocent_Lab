package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SystemEnvironmentAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SystemEnvironmentPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistRequest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import io.github.jichuo1.meguviz.hook.api.ErrorPolicy
import io.github.jichuo1.meguviz.hook.api.HookId
import io.github.jichuo1.meguviz.hook.core.HookEnvironment as MeguvizHookEnvironment
import io.github.jichuo1.meguviz.hook.testing.ContractOracle
import io.github.jichuo1.meguviz.hook.testing.FakeBackend
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Constructor
import java.lang.reflect.Method

class SystemEnvironmentFeatureInstallerTest {
    object HostEnvironment {
        var calls = 0
        var next: Any? = linkedMapOf<Any?, Any?>()
        var failure: Throwable? = null

        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        fun collectEnv(skipOptions: Set<String>): Map<Any?, Any?> {
            calls++
            failure?.let { throw it }
            val current = next
            return current as Map<Any?, Any?>
        }
    }

    object HostHook {
        var calls = 0
        var next: Any? = linkedMapOf<String, Boolean>()

        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        fun collectHooks(): Map<String, Boolean> {
            calls++
            return next as Map<String, Boolean>
        }
    }

    object AmbiguousHostEnvironment {
        @JvmStatic
        fun envPrimary(skipOptions: Set<String>): Map<Any?, Any?> = mapOf("root" to "0")

        @JvmStatic
        fun envSecondary(skipOptions: Set<String>): Map<Any?, Any?> = mapOf("root" to "1")
    }

    private class FakeBackendRegistrar(
        private val failId: String? = null
    ) : HookRegistrar, AutoCloseable {
        val backend: FakeBackend = FakeBackend.create()
        private val megEnv: MeguvizHookEnvironment = MeguvizHookEnvironment.create(backend)
        val installedMethods = linkedMapOf<String, Method>()
        val registrationCounts = linkedMapOf<String, Int>()

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
            installedMethods[id] = method
            registrationCounts[id] = (registrationCounts[id] ?: 0) + 1
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
        missingLoader: Boolean = false,
        failId: String? = null,
        initialPoints: SystemEnvironmentPoints? = defaultPoints()
    ) : AutoCloseable {
        val registrar = FakeBackendRegistrar(failId)
        val stagesById = linkedMapOf<String, MutableList<FeatureRuntimeStage>>()
        val capabilities = linkedMapOf<String, FeatureInstallResult>()
        val coordinatorCapabilities = linkedMapOf<String, FeatureInstallResult>()
        val errors = mutableListOf<String>()
        val statuses = linkedMapOf<String, String>()
        var currentPoints: SystemEnvironmentPoints? = initialPoints

        val envMethod: Method = HostEnvironment::class.java.getDeclaredMethod("collectEnv", Set::class.java)
        val hookMethod: Method = HostHook::class.java.getDeclaredMethod("collectHooks")

        val environment = HookEnvironment(
            processName = process,
            classLoader = if (missingLoader) null else javaClass.classLoader,
            hookPoints = HookPointRegistry(javaClass.classLoader),
            registrar = registrar,
            logInfo = { _, _ -> },
            logError = { key, _ -> errors += key },
            reportStatus = { key, value -> statuses[key] = value },
            runtimeEvidence = { id, stage, _ -> stagesById.getOrPut(id) { mutableListOf() } += stage },
            installationEvidence = { record ->
                if (record.id != SystemEnvironmentFeatureInstaller.ID && record.result != null) {
                    coordinatorCapabilities[record.id] = record.result
                }
            },
            capabilityEvidence = { id, result -> capabilities[id] = result }
        )

        init {
            HostEnvironment.calls = 0
            HostEnvironment.failure = null
            HostHook.calls = 0
            registrar.backend.onTarget(envMethod) { _, arguments ->
                @Suppress("UNCHECKED_CAST")
                HostEnvironment.collectEnv((arguments.firstOrNull() as? Set<String>).orEmpty())
            }
            registrar.backend.onTarget(hookMethod) { _, _ ->
                HostHook.collectHooks()
            }
        }

        fun createInstaller(enabled: Boolean = true) = SystemEnvironmentFeatureInstaller(enabled) { currentPoints }

        fun installViaCoordinator(installer: SystemEnvironmentFeatureInstaller): List<FeatureInstallRecord> =
            FeatureInstallCoordinator(environment).installAll(listOf(installer))

        @Suppress("UNCHECKED_CAST")
        fun callEnv(skipOptions: Set<String> = emptySet()): Map<Any?, Any?> =
            registrar.backend.call(envMethod, null, skipOptions) as Map<Any?, Any?>

        @Suppress("UNCHECKED_CAST")
        fun callHook(): Map<Any?, Any?> =
            registrar.backend.call(hookMethod, null) as Map<Any?, Any?>

        override fun close() = registrar.close()

        companion object {
            fun defaultPoints() = SystemEnvironmentPoints(
                VersionAdapter.HookPoint(HostEnvironment::class.java.name, "collectEnv", listOf("java.util.Set")),
                VersionAdapter.HookPoint(HostHook::class.java.name, "collectHooks", emptyList())
            )
        }
    }

    @Test
    fun verifySanitizationAndPassThroughBehavior() {
        // dual boundaries sanitize detected results without mutating originals or looping
        Fixture().use { fixture ->
            val installer = fixture.createInstaller()
            val records = fixture.installViaCoordinator(installer)
            assertEquals(FeatureInstallResult.Installed(2, complete = true), records.single { it.id == SystemEnvironmentFeatureInstaller.ID }.result)
            assertFalse(installer.requiresAdaptationRetry)

            val originalEnv = linkedMapOf<Any?, Any?>(
                "root" to "1",
                "is_root" to "true",
                "axposed" to "true",
                "maps" to "xposed",
                "model" to "unchanged",
                "files" to "kept"
            )
            HostEnvironment.next = originalEnv

            val sanitizedEnv = fixture.callEnv()
            val expectedOracle = ContractOracle.originalReturns()
            assertEquals(expectedOracle.originalCalls(), fixture.registrar.backend.originalCallCount(fixture.envMethod))
            assertNotSame(originalEnv, sanitizedEnv)
            assertEquals("1", originalEnv["root"])
            assertEquals("true", originalEnv["is_root"])
            assertEquals("true", originalEnv["axposed"])
            assertEquals("xposed", originalEnv["maps"])
            assertEquals("0", sanitizedEnv["root"])
            assertEquals("false", sanitizedEnv["is_root"])
            assertEquals("false", sanitizedEnv["axposed"])
            assertEquals("", sanitizedEnv["maps"])
            assertEquals("unchanged", sanitizedEnv["model"])
            assertEquals("kept", sanitizedEnv["files"])

            HostEnvironment.next = linkedMapOf<Any?, Any?>(
                "root" to "true",
                "is_root" to "true",
                "axposed" to "false",
                "maps" to "substrate"
            )
            val secondEnv = fixture.callEnv(setOf("vm"))
            assertEquals("false", secondEnv["root"])
            assertEquals("false", secondEnv["is_root"])
            assertEquals("false", secondEnv["axposed"])
            assertEquals("", secondEnv["maps"])
            assertEquals(2, HostEnvironment.calls)
            assertEquals(2, fixture.registrar.backend.originalCallCount(fixture.envMethod))

            val originalHook = linkedMapOf(
                "xposed" to true,
                "substrate" to true,
                "frida" to true,
                "riru" to true,
                "other" to true
            )
            HostHook.next = originalHook
            val sanitizedHook = fixture.callHook()
            assertNotSame(originalHook, sanitizedHook)
            assertEquals(true, originalHook["xposed"])
            assertEquals(false, sanitizedHook["xposed"])
            assertEquals(false, sanitizedHook["substrate"])
            assertEquals(false, sanitizedHook["frida"])
            assertEquals(false, sanitizedHook["riru"])
            assertEquals(true, sanitizedHook["other"])

            val expectedStages = listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED)
            assertEquals(expectedStages, fixture.stagesById[SystemEnvironmentFeatureInstaller.ID])
            assertEquals(expectedStages, fixture.stagesById[SystemEnvironmentFeatureInstaller.CAP_ROOT])
            assertEquals(expectedStages, fixture.stagesById[SystemEnvironmentFeatureInstaller.CAP_HOOK])
            assertEquals(FeatureInstallResult.Installed(1), fixture.coordinatorCapabilities[SystemEnvironmentFeatureInstaller.CAP_ROOT])
            assertEquals(FeatureInstallResult.Installed(1), fixture.coordinatorCapabilities[SystemEnvironmentFeatureInstaller.CAP_HOOK])
        }

        // partial skipOptions subsets sanitize present verified keys without inventing missing ones
        Fixture().use { fixture ->
            fixture.createInstaller().install(fixture.environment)

            val vmOnly = linkedMapOf<Any?, Any?>("root" to "1", "is_root" to "true")
            HostEnvironment.next = vmOnly
            val outVmOnly = fixture.callEnv(setOf("hook"))
            assertEquals(setOf("root", "is_root"), outVmOnly.keys)
            assertEquals("0", outVmOnly["root"])
            assertEquals("false", outVmOnly["is_root"])

            val hookOnly = linkedMapOf<Any?, Any?>("axposed" to "true", "maps" to "substrate")
            HostEnvironment.next = hookOnly
            val outHookOnly = fixture.callEnv(setOf("vm"))
            assertEquals(setOf("axposed", "maps"), outHookOnly.keys)
            assertEquals("false", outHookOnly["axposed"])
            assertEquals("", outHookOnly["maps"])
        }

        // clean maps are passed through zero-copy without claiming applied
        Fixture().use { fixture ->
            fixture.createInstaller().install(fixture.environment)

            val cleanEnv = linkedMapOf<Any?, Any?>(
                "root" to "0",
                "is_root" to "false",
                "axposed" to "false",
                "maps" to "unrelated-entry"
            )
            HostEnvironment.next = cleanEnv
            assertSame(cleanEnv, fixture.callEnv())

            val cleanHook = linkedMapOf(
                "xposed" to false,
                "substrate" to false,
                "frida" to false,
                "riru" to false,
                "custom" to true
            )
            HostHook.next = cleanHook
            assertSame(cleanHook, fixture.callHook())

            assertEquals(listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED), fixture.stagesById[SystemEnvironmentFeatureInstaller.ID])
            assertEquals(listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED), fixture.stagesById[SystemEnvironmentFeatureInstaller.CAP_ROOT])
            assertEquals(listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED), fixture.stagesById[SystemEnvironmentFeatureInstaller.CAP_HOOK])
        }

        // malformed or untrusted maps are returned unchanged without claiming observed or applied
        Fixture().use { fixture ->
            fixture.createInstaller().install(fixture.environment)

            val badMaps: List<Map<Any?, Any?>?> = listOf(
                null,
                emptyMap(),
                mapOf(1 to "1", "is_root" to "true"),
                mapOf("root" to 1, "is_root" to true, "axposed" to true),
                mapOf("root" to "untrusted", "is_root" to "false", "axposed" to "false"),
                mapOf("root" to "0", "maps" to 123)
            )
            for (bad in badMaps) {
                if (bad == null) {
                    assertNull(SystemEnvironmentResult.sanitizeEnvironment(null))
                } else {
                    HostEnvironment.next = bad
                    assertSame(bad, fixture.callEnv())
                }
            }

            assertNull(SystemEnvironmentResult.sanitizeHook(null))
            val incompleteHook = linkedMapOf<Any?, Any?>("xposed" to true, "substrate" to "not-bool")
            HostHook.next = incompleteHook
            assertSame(incompleteHook, fixture.callHook())

            assertEquals(listOf(FeatureRuntimeStage.ADAPTED), fixture.stagesById[SystemEnvironmentFeatureInstaller.ID])
            assertEquals(listOf(FeatureRuntimeStage.ADAPTED), fixture.stagesById[SystemEnvironmentFeatureInstaller.CAP_ROOT])
            assertEquals(listOf(FeatureRuntimeStage.ADAPTED), fixture.stagesById[SystemEnvironmentFeatureInstaller.CAP_HOOK])
        }
    }

    @Test
    fun verifyExceptionsGatingAndIncrementalRetry() {
        // original host exception is propagated via ContractOracle without applying or falsifying evidence
        Fixture().use { fixture ->
            fixture.createInstaller().install(fixture.environment)
            val failure = IllegalStateException("host read failure")
            HostEnvironment.failure = failure

            val thrown = runCatching { fixture.callEnv() }.exceptionOrNull()
            val expected = ContractOracle.originalThrows()
            assertSame(failure, thrown)
            assertEquals(expected.originalCalls(), fixture.registrar.backend.originalCallCount(fixture.envMethod))
            assertEquals(listOf(FeatureRuntimeStage.ADAPTED), fixture.stagesById[SystemEnvironmentFeatureInstaller.ID])
            assertEquals(listOf(FeatureRuntimeStage.ADAPTED), fixture.stagesById[SystemEnvironmentFeatureInstaller.CAP_ROOT])
        }

        // disabled non-main missing loader and registration failure behave cleanly
        Fixture().use { disabled ->
            val installer = disabled.createInstaller(enabled = false)
            assertEquals(emptyList<String>(), installer.capabilityIds)
            assertEquals(FeatureInstallResult.Skipped("disabled"), installer.install(disabled.environment))
            assertFalse(installer.requiresAdaptationRetry)
            assertEquals("disabled", disabled.statuses[SystemEnvironmentFeatureInstaller.STATUS])
        }

        Fixture(process = "tv.danmaku.bili:web").use { nonMain ->
            val installer = nonMain.createInstaller()
            assertEquals(FeatureInstallResult.Skipped("non-main-process"), installer.install(nonMain.environment))
            assertFalse(installer.requiresAdaptationRetry)
        }

        Fixture(missingLoader = true).use { noLoader ->
            val installer = noLoader.createInstaller()
            assertEquals(FeatureInstallResult.Skipped("missing-class-loader"), installer.install(noLoader.environment))
            assertFalse(installer.requiresAdaptationRetry)
            assertEquals("missing-class-loader", noLoader.statuses[SystemEnvironmentFeatureInstaller.STATUS])
        }

        Fixture(failId = SystemEnvironmentFeatureInstaller.HOOK_FEATURES).use { partialFail ->
            val installer = partialFail.createInstaller()
            assertEquals(FeatureInstallResult.Installed(1, complete = false), installer.install(partialFail.environment))
            assertFalse(installer.requiresAdaptationRetry)
            assertTrue(partialFail.capabilities[SystemEnvironmentFeatureInstaller.CAP_HOOK] is FeatureInstallResult.Skipped)
            assertEquals(listOf("system_environment_hook"), partialFail.errors)
        }

        // incremental adaptation retry installs remaining family without duplicating already-registered hook
        val envOnly = SystemEnvironmentPoints(environment = Fixture.defaultPoints().environment, hook = null)
        Fixture(initialPoints = envOnly).use { fixture ->
            val installer = fixture.createInstaller()

            val first = installer.install(fixture.environment)
            assertEquals(FeatureInstallResult.Installed(1, complete = false), first)
            assertTrue(installer.requiresAdaptationRetry)
            assertEquals(FeatureInstallResult.Installed(1), fixture.capabilities[SystemEnvironmentFeatureInstaller.CAP_ROOT])
            assertEquals(
                FeatureInstallResult.Skipped("missing-adapter-point"),
                fixture.capabilities[SystemEnvironmentFeatureInstaller.CAP_HOOK]
            )
            assertEquals(1, fixture.registrar.registrationCounts[SystemEnvironmentFeatureInstaller.HOOK_ENVIRONMENT])
            assertNull(fixture.registrar.registrationCounts[SystemEnvironmentFeatureInstaller.HOOK_FEATURES])

            fixture.currentPoints = Fixture.defaultPoints()
            val second = installer.install(fixture.environment)
            assertEquals(FeatureInstallResult.Installed(2, complete = true), second)
            assertFalse(installer.requiresAdaptationRetry)
            assertEquals(1, fixture.registrar.registrationCounts[SystemEnvironmentFeatureInstaller.HOOK_ENVIRONMENT])
            assertEquals(1, fixture.registrar.registrationCounts[SystemEnvironmentFeatureInstaller.HOOK_FEATURES])
            assertEquals(listOf(FeatureRuntimeStage.ADAPTED), fixture.stagesById[SystemEnvironmentFeatureInstaller.CAP_ROOT])
            assertEquals(listOf(FeatureRuntimeStage.ADAPTED), fixture.stagesById[SystemEnvironmentFeatureInstaller.CAP_HOOK])
            assertEquals(listOf(FeatureRuntimeStage.ADAPTED), fixture.stagesById[SystemEnvironmentFeatureInstaller.ID])
        }
    }

    @Test
    fun verifyAdapterPointsValidationAndLocate() {
        val full = Fixture.defaultPoints()
        assertEquals(full, SystemEnvironmentPoints.fromJson(full.toJson()))

        val partial = SystemEnvironmentPoints(environment = full.environment, hook = null)
        assertEquals(partial, SystemEnvironmentPoints.fromJson(partial.toJson()))
        assertTrue(partial.isStructurallyValid())

        // Invalid shapes must return null rather than partially trusting unvalidated fields.
        assertNull(SystemEnvironmentPoints.fromJson(JSONObject()))
        assertNull(SystemEnvironmentPoints.fromJson(JSONObject("""{"environment":{"cls":"bad class","m":"x","params":["java.util.Set"]}}""")))
        assertNull(SystemEnvironmentPoints.fromJson(JSONObject("""{"environment":{"cls":"a.B","m":"x","params":[]}}""")))
        assertNull(SystemEnvironmentPoints.fromJson(JSONObject("""{"hook":{"cls":"a.B","m":"x","params":[],"vf":"f"}}""")))

        // locate rejects ambiguous multi-method candidates even when each satisfies signature checks.
        val ambiguousEnv = AmbiguousHostEnvironment::class.java
        val m1 = ambiguousEnv.getDeclaredMethod("envPrimary", Set::class.java)
        val m2 = ambiguousEnv.getDeclaredMethod("envSecondary", Set::class.java)
        val hookMethod = HostHook::class.java.getDeclaredMethod("collectHooks")
        val session = DexAssistSession(
            engine = { request: DexAssistRequest ->
                when (request.query) {
                    DexAssistQuery.SYSTEM_ENV_ROOT_RESULT -> DexAssistResult.Candidates(listOf(m1, m2))
                    DexAssistQuery.SYSTEM_ENV_HOOK_RESULT -> DexAssistResult.Candidates(listOf(hookMethod))
                    else -> error("Unexpected query ${request.query}")
                }
            },
            codePaths = emptyList(),
            classLoader = javaClass.classLoader,
            planned = setOf(DexAssistQuery.SYSTEM_ENV_ROOT_RESULT, DexAssistQuery.SYSTEM_ENV_HOOK_RESULT)
        )
        val located = SystemEnvironmentAdapter.locate(javaClass.classLoader, session, enabled = true)
        assertNotNull(located)
        assertNull(located!!.environment)
        assertEquals(Fixture.defaultPoints().hook, located.hook)

        // Conclusive vs transient failure classification.
        assertTrue(SystemEnvironmentAdapter.conclusive(DexAssistResult.Candidates(listOf(hookMethod))))
        assertTrue(SystemEnvironmentAdapter.conclusive(DexAssistResult.Unavailable(DexAssistResult.Reason.NO_MATCH)))
        assertTrue(SystemEnvironmentAdapter.conclusive(DexAssistResult.Unavailable(DexAssistResult.Reason.TOO_MANY_MATCHES)))
        assertFalse(SystemEnvironmentAdapter.conclusive(null))
        assertFalse(SystemEnvironmentAdapter.conclusive(DexAssistResult.Unavailable(DexAssistResult.Reason.NATIVE_UNAVAILABLE)))
        assertFalse(SystemEnvironmentAdapter.conclusive(DexAssistResult.Unavailable(DexAssistResult.Reason.QUERY_FAILED)))
        assertFalse(SystemEnvironmentAdapter.conclusive(DexAssistResult.Unavailable(DexAssistResult.Reason.NO_CODE_PATH)))
        assertFalse(SystemEnvironmentAdapter.conclusive(DexAssistResult.Unavailable(DexAssistResult.Reason.PREVIOUS_ATTEMPT_UNFINISHED)))

        // Planned gating and cache merge preserve non-null family descriptors.
        assertTrue(
            SystemEnvironmentAdapter.planned(
                javaClass.classLoader,
                partial,
                attemptedMissing = emptySet(),
                enabled = false
            ).isEmpty()
        )
        assertEquals(
            setOf(SystemEnvironmentAdapter.Family.HOOK),
            SystemEnvironmentAdapter.planned(
                javaClass.classLoader,
                partial,
                attemptedMissing = emptySet(),
                enabled = true
            )
        )
        assertTrue(
            SystemEnvironmentAdapter.planned(
                javaClass.classLoader,
                partial,
                attemptedMissing = setOf(SystemEnvironmentAdapter.Family.HOOK),
                enabled = true
            ).isEmpty()
        )
        assertEquals(
            Fixture.defaultPoints(),
            SystemEnvironmentAdapter.merge(
                SystemEnvironmentPoints(environment = null, hook = Fixture.defaultPoints().hook),
                partial
            )
        )
    }
}
