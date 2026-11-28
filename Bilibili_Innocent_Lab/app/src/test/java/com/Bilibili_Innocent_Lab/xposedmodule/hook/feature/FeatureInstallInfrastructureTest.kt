package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FeatureInstallInfrastructureTest {
    private val loader = javaClass.classLoader!!

    private class ScopedTarget {
        fun run() = Unit
    }

    /** 记录每次登记里 DSL 块执行后的故障归属，其余行为与空登记器相同。 */
    private class Recorder : HookRegistrar by TestHookRegistrar {
        override val rootRegistrar: HookRegistrar get() = this
        val owners = mutableListOf<Pair<String, String?>>()
        override fun exact(
            id: String,
            owner: Class<*>,
            methodName: String,
            vararg parameterTypes: Class<*>,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            owners += id to ModernMemberHookCreator(owner.getDeclaredMethod(methodName, *parameterTypes)).apply(block).faultOwner
        }
    }

    @Test
    fun verifyInstallCoordinationAndRetry() {
        // independentLeafResultsDoNotInheritParentSuccessAndLateEvidenceCanReplaceUnknown
        run {
            val events = mutableListOf<FeatureInstallRecord>()
            var late: (() -> Unit)? = null
            val environment = HookEnvironment(
                "test", loader, HookPointRegistry(loader), TestHookRegistrar, { _, _ -> }, { _, _ -> }, { _, _ -> },
                installationEvidence = { events += it }
            )
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
            assertEquals(
                FeatureSkipReason.MISSING_HOST_STRUCTURE,
                (events.last { it.id == "missing" }.result as FeatureInstallResult.Skipped).reasonCode
            )
            assertEquals(FeatureInstallResult.Unverified, events.last { it.id == "deferred" }.result)
            late!!()
            assertEquals(FeatureInstallResult.Installed(1), events.last { it.id == "deferred" }.result)
        }

        // abortedInstallerKeepsProvenLeavesAndMarksRemainingLeavesFailed
        run {
            val events = mutableListOf<FeatureInstallRecord>()
            val environment = HookEnvironment(
                "test", loader, HookPointRegistry(loader), TestHookRegistrar, { _, _ -> }, { _, _ -> }, { _, _ -> },
                installationEvidence = { events += it }
            )
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

        // runtimeDiagnosticsFailureDoesNotEscapeIntoBusinessCallback
        run {
            val environment = HookEnvironment(
                "test", loader, HookPointRegistry(loader), TestHookRegistrar, { _, _ -> }, { _, _ -> }, { _, _ -> },
                runtimeEvidence = { _, _, _ -> error("diagnostic sink failure") }
            )
            environment.reportRuntimeEvidence("example", FeatureRuntimeStage.OBSERVED)
            environment.forCapabilityRuntime("child").reportRuntimeEvidence("parent", FeatureRuntimeStage.APPLIED)
        }

        // keeps order and isolates installer failure
        run {
            val order = mutableListOf<String>()
            val loggedFailures = mutableListOf<String>()
            val evidence = mutableListOf<FeatureInstallRecord>()
            val environment = HookEnvironment(
                processName = "test",
                classLoader = loader,
                hookPoints = HookPointRegistry(loader),
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

        // diagnostic callback failure never interrupts feature installation
        run {
            val environment = HookEnvironment(
                processName = "test",
                classLoader = loader,
                hookPoints = HookPointRegistry(loader),
                registrar = TestHookRegistrar,
                logInfo = { _, _ -> },
                logError = { _, _ -> },
                reportStatus = { _, _ -> },
                installationEvidence = { error("diagnostic-only failure") }
            )
            val records = FeatureInstallCoordinator(environment).installAll(
                listOf(FunctionalFeatureInstaller("still-installed") { FeatureInstallResult.Installed(2) })
            )
            assertEquals(FeatureInstallResult.Installed(2), records.single().result)
        }

        // free-form skip reasons collapse to bounded diagnostic codes
        run {
            assertEquals(FeatureSkipReason.MISSING_ADAPTER_POINT, FeatureInstallResult.Skipped("missing-adapter-point").reasonCode)
            assertEquals(FeatureSkipReason.AMBIGUOUS_HOST_STRUCTURE, FeatureInstallResult.Skipped("ambiguous-video-detail-getter").reasonCode)
            assertEquals(FeatureSkipReason.NO_SAFE_HOOK_POINT, FeatureInstallResult.Skipped("no_required_hook_point").reasonCode)
            assertEquals(FeatureSkipReason.OTHER, FeatureInstallResult.Skipped("future-unclassified-reason").reasonCode)
        }

        // reentrant install preserves prior installed siblings and never upgrades unverified or skipped
        run {
            val events = mutableListOf<FeatureInstallRecord>()
            val latest = linkedMapOf<String, FeatureInstallRecord>()
            val environment = HookEnvironment(
                processName = "test",
                classLoader = loader,
                hookPoints = HookPointRegistry(loader),
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
                            environment.reportCapabilityCoverage("cap.deferred", true, 1, 1)
                            FeatureInstallResult.Installed(3, complete = true)
                        }
                        else -> {
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

        // late capability evidence before reentrant install is preserved monotonically
        run {
            val latest = linkedMapOf<String, FeatureInstallRecord>()
            var late: (() -> Unit)? = null
            var pass = 0
            val environment = HookEnvironment(
                processName = "test",
                classLoader = loader,
                hookPoints = HookPointRegistry(loader),
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

        // retryAdapted preserves registration order and enforces double checked retry guard
        run {
            val postedActions = mutableListOf<() -> Unit>()
            val executionOrder = mutableListOf<String>()
            val environment = HookEnvironment(
                processName = "tv.danmaku.bili",
                classLoader = loader,
                hookPoints = HookPointRegistry(loader),
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
            secondPending = false
            postedActions.single().invoke()

            assertEquals(listOf("retry_first", "retry_third"), executionOrder)
            postedActions.clear()
            coordinator.retryAdapted(adapted = null, ok = true)
            assertEquals(0, postedActions.size)
        }

        // retryAdapted isolates failures and triggers onAdaptationFailed when ok is false or postToMain absent
        run {
            val errors = mutableListOf<String>()
            val failedCallbacks = mutableListOf<String>()
            val ranOnMain = mutableListOf<String>()
            val posted = mutableListOf<() -> Unit>()
            val envWithMain = HookEnvironment(
                processName = "tv.danmaku.bili",
                classLoader = loader,
                hookPoints = HookPointRegistry(loader),
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

        // featureScopedRegistrar attributes faultOwner across parent and leaf capability runtimes
        run {
            val capturedOwners = mutableListOf<Pair<String, String?>>()
            val sampleMethod = String::class.java.getDeclaredMethod("isEmpty")
            val rootRegistrar = object : HookRegistrar by TestHookRegistrar {
                override val rootRegistrar: HookRegistrar get() = this
                override fun exact(
                    id: String,
                    owner: Class<*>,
                    methodName: String,
                    vararg parameterTypes: Class<*>,
                    block: ModernMemberHookCreator.() -> Unit
                ) {
                    val creator = ModernMemberHookCreator(sampleMethod).apply(block)
                    capturedOwners += id to creator.faultOwner
                }
            }
            val env = HookEnvironment(
                processName = "tv.danmaku.bili",
                classLoader = loader,
                hookPoints = HookPointRegistry(loader),
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
    }

    @Test
    fun verifyScopedRegistrarAndDisabledStatuses() {
        // FeatureScopedRegistrar: coordinated installer stamps its own id on every hook
        run {
            val recorder = Recorder()
            val environment = HookEnvironment(
                "tv.danmaku.bili", loader, HookPointRegistry(loader), recorder,
                { _, _ -> }, { _, _ -> }, { _, _ -> }
            )
            FeatureInstallCoordinator(environment).installAll(listOf(
                FunctionalFeatureInstaller("feature_one") { env ->
                    env.registrar.exact("one.a", ScopedTarget::class.java, "run") { }
                    env.registrar.exact("one.b", ScopedTarget::class.java, "run") { before { } }
                    FeatureInstallResult.Installed(2)
                },
                FunctionalFeatureInstaller("feature_two") { env ->
                    env.registrar.exact("two.a", ScopedTarget::class.java, "run") { after { } }
                    FeatureInstallResult.Installed(1)
                }
            ))
            assertEquals(
                listOf("one.a" to "feature_one", "one.b" to "feature_one", "two.a" to "feature_two"),
                recorder.owners
            )
        }

        // FeatureScopedRegistrar: hook outside any installer has no owner
        run {
            val creator = ModernMemberHookCreator(ScopedTarget::class.java.getDeclaredMethod("run"))
            assertNull(creator.faultOwner)
        }

        // FeatureScopedRegistrar: default exception policy unchanged
        run {
            assertEquals(HookExceptionPolicy.PROTECT_HOST, HookExceptionPolicy.values().first())
        }

        // FeatureScopedRegistrar: rootRegistrar unwraps wrappers back to root
        run {
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

        // FeatureScopedRegistrar: forCapabilityRuntime attributes immediate and delayed hooks to leaf capability id
        run {
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
                        environment.registrar.exact("parent.hook", ScopedTarget::class.java, "run") { }
                        val leafAlphaEnv = environment.forCapabilityRuntime("leaf_alpha")
                        assertSame(recorder, leafAlphaEnv.registrar.rootRegistrar)
                        leafAlphaEnv.registrar.exact("leaf.alpha.immediate", ScopedTarget::class.java, "run") { }

                        val leafBetaEnv = environment.forCapabilityRuntime("leaf_beta")
                        delayedInstall = {
                            leafBetaEnv.registrar.exact("leaf.beta.delayed", ScopedTarget::class.java, "run") { }
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
            noSinkLeafEnv.registrar.exact("leaf.no_sink", ScopedTarget::class.java, "run") { }
            assertEquals(listOf("leaf.no_sink" to "leaf_no_sink"), noSinkRecorder.owners)
        }

        // FeatureStatusInstaller: all 10 disabled feature installers report disabled without resolving hook points
        fun checkDisabled(expectedChannel: String, installer: FeatureInstaller) {
            val statuses = mutableListOf<Pair<String, String>>()
            val env = HookEnvironment(
                processName = "test",
                classLoader = loader,
                hookPoints = HookPointRegistry(loader),
                registrar = TestHookRegistrar,
                logInfo = { _, _ -> },
                logError = { _, _ -> },
                reportStatus = { channel, status -> statuses += channel to status }
            )
            assertEquals(FeatureInstallResult.Skipped("disabled"), installer.install(env))
            assertEquals(listOf(expectedChannel to "disabled"), statuses)
        }

        checkDisabled("adskip_status", PausedAdFeatureInstaller(enabled = false, points = null))
        checkDisabled("banner_ad_status", HomeBannerFeatureInstaller(enabled = false, point = null, collapseBanner = { false }))
        checkDisabled("gamecard_ad_status", GamePromotionFeatureInstaller(enabled = false))
        checkDisabled("detail_app_promotion_status", DetailAppPromotionFeatureInstaller(enabled = false))
        checkDisabled("home_top_bar_status", HomeTopBarFeatureInstaller(hideGameMenu = false, hideSearchDefaultWord = false, points = null))
        checkDisabled("dynamic_tabs_status", DynamicTabsFeatureInstaller(hideCity = false, hideSchool = false, preferVideo = false, point = null))
        checkDisabled("full_number_status", FullNumberFeatureInstaller(enabled = false, points = null))
        checkDisabled("player_portrait_status", PlayerPortraitFeatureInstaller(enabled = false, points = null))
        checkDisabled("teenagers_mode_status", TeenagersModeFeatureInstaller(enabled = false, points = null))
        checkDisabled(
            "comment_purify_status",
            CommentPurifyFeatureInstaller(
                removeSearchLinks = false,
                removeEmptyGuide = false,
                removeVoteWidgets = false,
                removeFollowButtons = false,
                removeQoe = false,
                removeOperations = false,
                points = null
            )
        )
    }
}
