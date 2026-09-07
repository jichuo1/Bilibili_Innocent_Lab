package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FeatureInstallCoordinatorTest {
    @Test
    fun independentLeafResultsDoNotInheritParentSuccessAndLateEvidenceCanReplaceUnknown() {
        val events = mutableListOf<FeatureInstallRecord>()
        var late: (() -> Unit)? = null
        val environment = HookEnvironment("test", javaClass.classLoader,
            HookPointRegistry(javaClass.classLoader), TestHookRegistrar, { _, _ -> }, { _, _ -> }, { _, _ -> },
            installationEvidence = { events += it })
        val installer = object : FeatureInstaller {
            override val id = "parent"
            override val capabilityIds = listOf("first", "missing", "deferred")
            override fun install(environment: HookEnvironment): FeatureInstallResult {
                environment.reportCapabilityCoverage("first", true, 2, 2)
                environment.reportCapabilityCoverage("missing", false, 2, 2)
                late = { environment.reportCapabilityCoverage("deferred", true, 1, 1) }
                return FeatureInstallResult.Installed(2)
            }
        }
        FeatureInstallCoordinator(environment).installAll(listOf(installer))
        assertEquals(FeatureInstallResult.Installed(2), events.last { it.id == "first" }.result)
        assertEquals(FeatureSkipReason.MISSING_HOST_STRUCTURE,
            (events.last { it.id == "missing" }.result as FeatureInstallResult.Skipped).reasonCode)
        assertEquals(FeatureInstallResult.Unverified, events.last { it.id == "deferred" }.result)
        late!!()
        assertEquals(FeatureInstallResult.Installed(1), events.last { it.id == "deferred" }.result)
    }

    @Test
    fun abortedInstallerKeepsProvenLeavesAndMarksRemainingLeavesFailed() {
        val events = mutableListOf<FeatureInstallRecord>()
        val environment = HookEnvironment("test", javaClass.classLoader,
            HookPointRegistry(javaClass.classLoader), TestHookRegistrar, { _, _ -> }, { _, _ -> }, { _, _ -> },
            installationEvidence = { events += it })
        FeatureInstallCoordinator(environment).installAll(listOf(object : FeatureInstaller {
            override val id = "parent"
            override val capabilityIds = listOf("proven", "unfinished")
            override fun install(environment: HookEnvironment): FeatureInstallResult {
                environment.reportCapabilityCoverage("proven", true, 1, 1)
                error("synthetic installer failure")
            }
        }))
        assertEquals(FeatureInstallResult.Installed(1), events.last { it.id == "proven" }.result)
        assertNotNull(events.last { it.id == "unfinished" }.failure)
    }

    @Test
    fun runtimeDiagnosticsFailureDoesNotEscapeIntoBusinessCallback() {
        val environment = HookEnvironment("test", javaClass.classLoader,
            HookPointRegistry(javaClass.classLoader), TestHookRegistrar, { _, _ -> }, { _, _ -> }, { _, _ -> },
            runtimeEvidence = { _, _, _ -> error("diagnostic sink failure") })
        environment.reportRuntimeEvidence("example", FeatureRuntimeStage.OBSERVED)
        environment.forCapabilityRuntime("child").reportRuntimeEvidence("parent", FeatureRuntimeStage.APPLIED)
    }


    @Test
    fun `keeps order and isolates installer failure`() {
        val order = mutableListOf<String>()
        val loggedFailures = mutableListOf<String>()
        val evidence = mutableListOf<FeatureInstallRecord>()
        val environment = HookEnvironment(
            processName = "test",
            classLoader = javaClass.classLoader,
            hookPoints = HookPointRegistry(javaClass.classLoader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, message -> loggedFailures += message },
            reportStatus = { _, _ -> },
            installationEvidence = { evidence += it }
        )
        val records = FeatureInstallCoordinator(environment).installAll(
            listOf(
                FunctionalFeatureInstaller("first") {
                    order += "first"
                    FeatureInstallResult.Installed()
                },
                FunctionalFeatureInstaller("broken") {
                    order += "broken"
                    error("expected")
                },
                FunctionalFeatureInstaller("last") {
                    order += "last"
                    FeatureInstallResult.Skipped("disabled")
                }
            )
        )

        assertEquals(listOf("first", "broken", "last"), order)
        assertEquals(listOf("first", "broken", "last"), records.map { it.id })
        assertEquals(records, evidence)
        assertNull(records[0].failure)
        assertNotNull(records[1].failure)
        assertEquals(FeatureInstallResult.Skipped("disabled"), records[2].result)
        assertEquals(1, loggedFailures.size)
        assertEquals(true, loggedFailures.single().contains("broken"))
    }

    @Test
    fun `diagnostic callback failure never interrupts feature installation`() {
        val environment = HookEnvironment(
            processName = "test",
            classLoader = javaClass.classLoader,
            hookPoints = HookPointRegistry(javaClass.classLoader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> },
            installationEvidence = { error("diagnostic-only failure") }
        )

        val records = FeatureInstallCoordinator(environment).installAll(
            listOf(FunctionalFeatureInstaller("still-installed") {
                FeatureInstallResult.Installed(2)
            })
        )

        assertEquals(FeatureInstallResult.Installed(2), records.single().result)
    }

    @Test
    fun `free-form skip reasons collapse to bounded diagnostic codes`() {
        assertEquals(
            FeatureSkipReason.MISSING_ADAPTER_POINT,
            FeatureInstallResult.Skipped("missing-adapter-point").reasonCode
        )
        assertEquals(
            FeatureSkipReason.AMBIGUOUS_HOST_STRUCTURE,
            FeatureInstallResult.Skipped("ambiguous-video-detail-getter").reasonCode
        )
        assertEquals(
            FeatureSkipReason.NO_SAFE_HOOK_POINT,
            FeatureInstallResult.Skipped("no_required_hook_point").reasonCode
        )
        assertEquals(
            FeatureSkipReason.OTHER,
            FeatureInstallResult.Skipped("future-unclassified-reason").reasonCode
        )
    }
}
