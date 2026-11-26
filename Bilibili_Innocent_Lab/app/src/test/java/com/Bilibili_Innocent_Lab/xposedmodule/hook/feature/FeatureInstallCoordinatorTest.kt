package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FeatureInstallCoordinatorTest {
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

    fun runtimeDiagnosticsFailureDoesNotEscapeIntoBusinessCallback() {
        val environment = HookEnvironment("test", javaClass.classLoader,
            HookPointRegistry(javaClass.classLoader), TestHookRegistrar, { _, _ -> }, { _, _ -> }, { _, _ -> },
            runtimeEvidence = { _, _, _ -> error("diagnostic sink failure") })
        environment.reportRuntimeEvidence("example", FeatureRuntimeStage.OBSERVED)
        environment.forCapabilityRuntime("child").reportRuntimeEvidence("parent", FeatureRuntimeStage.APPLIED)
    }


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

    fun `reentrant install preserves prior installed siblings and never upgrades unverified or skipped`() {
        val events = mutableListOf<FeatureInstallRecord>()
        val latest = linkedMapOf<String, FeatureInstallRecord>()
        val environment = HookEnvironment(
            processName = "test",
            classLoader = javaClass.classLoader,
            hookPoints = HookPointRegistry(javaClass.classLoader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> },
            installationEvidence = { record ->
                events += record
                latest[record.id] = record
            }
        )
        val coordinator = FeatureInstallCoordinator(environment)
        var pass = 0
        val installer = object : FeatureInstaller {
            override val id = "parent"
            override val capabilityIds = listOf("cap.installed", "cap.deferred", "cap.skipped", "cap.never")
            override fun install(environment: HookEnvironment): FeatureInstallResult {
                pass += 1
                return when (pass) {
                    1 -> {
                        environment.reportCapabilityCoverage("cap.installed", true, 2, 2)
                        environment.reportCapability("cap.skipped", FeatureInstallResult.Skipped("missing-adapter-point"))
                        FeatureInstallResult.Installed(2, complete = false)
                    }
                    2 -> {
                        // Retry only reports the newly adapted capability; cap.installed is omitted.
                        environment.reportCapabilityCoverage("cap.deferred", true, 1, 1)
                        FeatureInstallResult.Installed(3, complete = true)
                    }
                    else -> {
                        // Even a no-op Skipped/throwing re-entry must keep prior Installed leaves intact.
                        if (pass == 3) FeatureInstallResult.Skipped("no-new-retry-hooks")
                        else error("late retry boom")
                    }
                }
            }
        }

        coordinator.installAll(listOf(installer))
        assertEquals(FeatureInstallResult.Installed(2), latest["cap.installed"]?.result)
        assertEquals(FeatureInstallResult.Unverified, latest["cap.deferred"]?.result)
        assertEquals(FeatureInstallResult.Skipped("missing-adapter-point"), latest["cap.skipped"]?.result)
        assertEquals(FeatureInstallResult.Unverified, latest["cap.never"]?.result)

        events.clear()
        coordinator.installAll(listOf(installer))
        assertEquals(FeatureInstallResult.Installed(2), latest["cap.installed"]?.result)
        assertEquals(FeatureInstallResult.Installed(1), latest["cap.deferred"]?.result)
        assertEquals(FeatureInstallResult.Unverified, latest["cap.skipped"]?.result)
        assertEquals(FeatureInstallResult.Unverified, latest["cap.never"]?.result)
        assertEquals(
            "Re-entry must not re-emit or overwrite already Installed sibling",
            0,
            events.count { it.id == "cap.installed" }
        )

        events.clear()
        coordinator.installAll(listOf(installer))
        assertEquals(FeatureInstallResult.Installed(2), latest["cap.installed"]?.result)
        assertEquals(FeatureInstallResult.Installed(1), latest["cap.deferred"]?.result)
        assertEquals(FeatureInstallResult.Skipped("no-new-retry-hooks"), latest["cap.skipped"]?.result)
        assertEquals(FeatureInstallResult.Skipped("no-new-retry-hooks"), latest["cap.never"]?.result)
        assertEquals(0, events.count { it.id == "cap.installed" || it.id == "cap.deferred" })

        events.clear()
        coordinator.installAll(listOf(installer))
        assertEquals(FeatureInstallResult.Installed(2), latest["cap.installed"]?.result)
        assertEquals(FeatureInstallResult.Installed(1), latest["cap.deferred"]?.result)
        assertNotNull(latest["cap.skipped"]?.failure)
        assertNotNull(latest["cap.never"]?.failure)
    }

    fun `late capability evidence before reentrant install is preserved monotonically`() {
        val latest = linkedMapOf<String, FeatureInstallRecord>()
        var late: (() -> Unit)? = null
        var pass = 0
        val environment = HookEnvironment(
            processName = "test",
            classLoader = javaClass.classLoader,
            hookPoints = HookPointRegistry(javaClass.classLoader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> },
            installationEvidence = { latest[it.id] = it }
        )
        val coordinator = FeatureInstallCoordinator(environment)
        val installer = object : FeatureInstaller {
            override val id = "parent"
            override val capabilityIds = listOf("cap.late", "cap.unreported")
            override fun install(environment: HookEnvironment): FeatureInstallResult {
                pass += 1
                if (pass == 1) {
                    late = { environment.reportCapabilityCoverage("cap.late", true, 1, 1) }
                }
                return FeatureInstallResult.Installed(1)
            }
        }

        coordinator.installAll(listOf(installer))
        assertEquals(FeatureInstallResult.Unverified, latest["cap.late"]?.result)
        assertEquals(FeatureInstallResult.Unverified, latest["cap.unreported"]?.result)

        late!!()
        assertEquals(FeatureInstallResult.Installed(1), latest["cap.late"]?.result)

        coordinator.installAll(listOf(installer))
        assertEquals(FeatureInstallResult.Installed(1), latest["cap.late"]?.result)
        assertEquals(FeatureInstallResult.Unverified, latest["cap.unreported"]?.result)
    }

    fun `retryAdapted preserves registration order and enforces double checked retry guard`() {
        val postedActions = mutableListOf<() -> Unit>()
        val executionOrder = mutableListOf<String>()
        val environment = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = javaClass.classLoader,
            hookPoints = HookPointRegistry(javaClass.classLoader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> },
            postToMain = { action -> postedActions += action }
        )
        val coordinator = FeatureInstallCoordinator(environment)

        var retrying = false
        var firstPending = true
        val first = object : AdaptRetryableInstaller {
            override val id = "retry_first"
            override val isAdaptationRetryPending: Boolean get() = firstPending
            override fun prepareAdaptedRetry(adapted: com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptResult?) = firstPending
            override fun install(environment: HookEnvironment): FeatureInstallResult {
                executionOrder += id
                if (retrying) firstPending = false
                return FeatureInstallResult.Installed(1)
            }
        }
        var secondPending = true
        val cancelledBeforeMain = object : AdaptRetryableInstaller {
            override val id = "retry_cleared_before_main"
            override val isAdaptationRetryPending: Boolean get() = secondPending
            override fun prepareAdaptedRetry(adapted: com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptResult?) = secondPending
            override fun install(environment: HookEnvironment): FeatureInstallResult {
                executionOrder += id
                return FeatureInstallResult.Installed(1)
            }
        }
        var thirdPending = true
        val third = object : AdaptRetryableInstaller {
            override val id = "retry_third"
            override val isAdaptationRetryPending: Boolean get() = thirdPending
            override fun prepareAdaptedRetry(adapted: com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptResult?) = thirdPending
            override fun install(environment: HookEnvironment): FeatureInstallResult {
                executionOrder += id
                if (retrying) thirdPending = false
                return FeatureInstallResult.Installed(1)
            }
        }

        coordinator.installAll(listOf(first, cancelledBeforeMain, third))
        executionOrder.clear()
        retrying = true

        coordinator.retryAdapted(adapted = null, ok = true)
        assertEquals("postToMain should be queued once", 1, postedActions.size)
        // Simulate main-thread state flipping before the posted batch runs.
        secondPending = false
        postedActions.single().invoke()

        assertEquals(listOf("retry_first", "retry_third"), executionOrder)
        // Re-invoking retryAdapted after pending flags cleared is a no-op.
        postedActions.clear()
        coordinator.retryAdapted(adapted = null, ok = true)
        assertEquals(0, postedActions.size)
    }

    fun `retryAdapted isolates failures and triggers onAdaptationFailed when ok is false or postToMain absent`() {
        val errors = mutableListOf<String>()
        val failedCallbacks = mutableListOf<String>()
        val ranOnMain = mutableListOf<String>()
        val posted = mutableListOf<() -> Unit>()
        val envWithMain = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = javaClass.classLoader,
            hookPoints = HookPointRegistry(javaClass.classLoader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, msg -> errors += msg },
            reportStatus = { _, _ -> },
            postToMain = { posted += it }
        )
        val coordinator = FeatureInstallCoordinator(envWithMain)

        val badPrepare = object : AdaptRetryableInstaller {
            override val id = "bad_prepare"
            override val isAdaptationRetryPending = true
            override fun prepareAdaptedRetry(adapted: com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptResult?): Boolean {
                error("prepare boom")
            }
            override fun install(environment: HookEnvironment) = FeatureInstallResult.Installed(1)
            override fun onAdaptationFailed() { failedCallbacks += id }
        }
        val badRetry = object : AdaptRetryableInstaller {
            override val id = "bad_retry"
            override val isAdaptationRetryPending = true
            override fun prepareAdaptedRetry(adapted: com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptResult?) = true
            override fun retryAdapted(environment: HookEnvironment, coordinator: FeatureInstallCoordinator) {
                error("retry boom")
            }
            override fun install(environment: HookEnvironment) = FeatureInstallResult.Installed(1)
            override fun onAdaptationFailed() { failedCallbacks += id }
        }
        val healthy = object : AdaptRetryableInstaller {
            override val id = "healthy"
            override val isAdaptationRetryPending = true
            override fun prepareAdaptedRetry(adapted: com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptResult?) = true
            override fun install(environment: HookEnvironment): FeatureInstallResult {
                ranOnMain += id
                return FeatureInstallResult.Installed(1)
            }
            override fun onAdaptationFailed() { failedCallbacks += id }
        }
        coordinator.registerRetryable(badPrepare)
        coordinator.registerRetryable(badRetry)
        coordinator.registerRetryable(healthy)

        coordinator.retryAdapted(adapted = null, ok = true)
        assertEquals(1, posted.size)
        posted.single().invoke()
        assertEquals(listOf("healthy"), ranOnMain)
        assertEquals(2, errors.size)

        coordinator.retryAdapted(adapted = null, ok = false)
        assertEquals(listOf("bad_prepare", "bad_retry", "healthy"), failedCallbacks)

        failedCallbacks.clear()
        val envWithoutMain = envWithMain.copy(postToMain = null)
        val noMainCoordinator = FeatureInstallCoordinator(envWithoutMain)
        noMainCoordinator.registerRetryable(healthy)
        noMainCoordinator.retryAdapted(adapted = null, ok = true)
        assertEquals(listOf("healthy"), failedCallbacks)
    }

    fun `featureScopedRegistrar attributes faultOwner across parent and leaf capability runtimes`() {
        val capturedOwners = mutableListOf<Pair<String, String?>>()
        val sampleMethod = String::class.java.getDeclaredMethod("isEmpty")
        val rootRegistrar = object : HookRegistrar by TestHookRegistrar {
            override val rootRegistrar: HookRegistrar get() = this
            override fun exact(
                id: String,
                owner: Class<*>,
                methodName: String,
                vararg parameterTypes: Class<*>,
                block: com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator.() -> Unit
            ) {
                val creator = com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator(sampleMethod).apply(block)
                capturedOwners += id to creator.faultOwner
            }
        }
        val env = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = javaClass.classLoader,
            hookPoints = HookPointRegistry(javaClass.classLoader),
            registrar = rootRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> }
        )
        val installer = object : FeatureInstaller {
            override val id = "parent_feature"
            override val capabilityIds = listOf("leaf_a")
            override fun install(environment: HookEnvironment): FeatureInstallResult {
                environment.registrar.exact("shared.hook", String::class.java, "isEmpty") {}
                environment.forCapabilityRuntime("leaf_a").registrar.exact("leaf.hook", String::class.java, "isEmpty") {}
                return FeatureInstallResult.Installed(2)
            }
        }
        FeatureInstallCoordinator(env).installAll(listOf(installer))
        assertEquals(
            listOf("shared.hook" to "parent_feature", "leaf.hook" to "leaf_a"),
            capturedOwners
        )
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FeatureInstallCoordinatorTest) -> Unit) {
            val s = FeatureInstallCoordinatorTest()
            block(s)
        }
        runIsolated { s -> s.independentLeafResultsDoNotInheritParentSuccessAndLateEvidenceCanReplaceUnknown() }
        runIsolated { s -> s.abortedInstallerKeepsProvenLeavesAndMarksRemainingLeavesFailed() }
        runIsolated { s -> s.runtimeDiagnosticsFailureDoesNotEscapeIntoBusinessCallback() }
        runIsolated { s -> s.`keeps order and isolates installer failure`() }
        runIsolated { s -> s.`diagnostic callback failure never interrupts feature installation`() }
        runIsolated { s -> s.`free-form skip reasons collapse to bounded diagnostic codes`() }
        runIsolated { s -> s.`reentrant install preserves prior installed siblings and never upgrades unverified or skipped`() }
        runIsolated { s -> s.`late capability evidence before reentrant install is preserved monotonically`() }
        runIsolated { s -> s.`retryAdapted preserves registration order and enforces double checked retry guard`() }
        runIsolated { s -> s.`retryAdapted isolates failures and triggers onAdaptationFailed when ok is false or postToMain absent`() }
        runIsolated { s -> s.`featureScopedRegistrar attributes faultOwner across parent and leaf capability runtimes`() }
    }
}
