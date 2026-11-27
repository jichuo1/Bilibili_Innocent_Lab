package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentController
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentNativeRoutingPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.agent.runForegroundSafely
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.StartupAdapterPlan
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterContract
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterTest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptDomain
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptDomainCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlan
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlanner
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PointFamily
import com.Bilibili_Innocent_Lab.xposedmodule.hook.config.SnapshotHookConfigSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerCodecForceFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerCodecPreference
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerDecodeMode
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.receiver.TermsMirrorFlushCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.FreeCopyImportJournal
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.FreeCopyJournalRead
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.PendingFreeCopyMirror
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigDiagnostics
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigPublishEvent
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigPublishListener
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigPublishResult
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigPublishState
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.StandardPublisherPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import com.bapis.bilibili.app.playerunite.v1.CodeType
import com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReq
import com.bapis.bilibili.app.playerunite.v1.VideoVod
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.RejectedExecutionException
import kmpfixture.modern.Descriptor
import kmpfixture.modern.GrpcEngine
import kmpfixture.modern.Handler
import kmpfixture.modern.KMossException
import kmpfixture.modern.Service
import kotlinx.serialization.SerializationStrategy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class StageACompatAndFgsTest {

    fun `terms mirror coordinator waits boundedly for standard channel without leaking listeners`() {
        val listeners = mutableListOf<RemoteHookConfigPublishListener>()
        var cancelledTimeout = false
        var noRootFinished = false
        var pendingFinishedCount = 0
        var scheduledDelayMs = -1L
        var timeoutAction: (() -> Unit)? = null
        var flushCalls = 0
        var diagnostics = RemoteHookConfigDiagnostics(
            state = RemoteHookConfigPublishState.WAITING_FOR_SERVICE,
            lastAttemptAtEpochMs = 0L,
            lastSuccessAtEpochMs = 0L,
            generation = 0L,
            failureCode = null,
            publishPending = true
        )

        val coordinator = TermsMirrorFlushCoordinator(
            isNoRootEnabled = { false },
            startNoRootRecovery = {},
            awaitNoRootRecovery = { onDone ->
                noRootFinished = true
                onDone()
            },
            addPublishListener = { listeners += it },
            removePublishListener = { listeners -= it },
            requestStandardFlush = { flushCalls++ },
            readDiagnostics = { diagnostics },
            scheduleTimeout = { delayMs, onTimeout ->
                scheduledDelayMs = delayMs
                timeoutAction = onTimeout
                { cancelledTimeout = true }
            },
            runAsync = { it.run() },
            onFinish = { pendingFinishedCount++ }
        )
        coordinator.start()

        assertTrue(noRootFinished)
        assertEquals(TermsMirrorFlushCoordinator.STANDARD_FLUSH_TIMEOUT_MS, scheduledDelayMs)
        assertEquals(3_000L, scheduledDelayMs)
        assertEquals(1, flushCalls)
        assertEquals(1, listeners.size)
        assertEquals(0, pendingFinishedCount)

        // 瞬态未连接失败不能提前结束等待。
        listeners.single().onRemoteHookConfigPublished(
            RemoteHookConfigPublishEvent(
                UserTermsDecision.ACCEPTED,
                RemoteHookConfigPublishResult.Failure("Xposed service is not connected")
            )
        )
        assertEquals(1, listeners.size)
        assertEquals(0, pendingFinishedCount)

        // 真正提交成功后立即解除监听、取消超时并 finish。
        listeners.single().onRemoteHookConfigPublished(
            RemoteHookConfigPublishEvent(
                UserTermsDecision.ACCEPTED,
                RemoteHookConfigPublishResult.Success(generation = 2L, changed = true)
            )
        )
        assertTrue(listeners.isEmpty())
        assertTrue(cancelledTimeout)
        assertEquals(1, pendingFinishedCount)

        // 超时兜底也不得泄漏监听，且 finish 只执行一次。
        timeoutAction?.invoke()
        assertEquals(1, pendingFinishedCount)
    }

    fun `terms mirror coordinator finishes cleanly on timeout bypass or async rejection`() {
        // 1. 3s 超时分支
        val timeoutListeners = mutableListOf<RemoteHookConfigPublishListener>()
        var timeoutCallback: (() -> Unit)? = null
        var timeoutFinishes = 0
        TermsMirrorFlushCoordinator(
            isNoRootEnabled = { false },
            startNoRootRecovery = {},
            awaitNoRootRecovery = { it() },
            addPublishListener = { timeoutListeners += it },
            removePublishListener = { timeoutListeners -= it },
            requestStandardFlush = {},
            readDiagnostics = {
                RemoteHookConfigDiagnostics(
                    RemoteHookConfigPublishState.WAITING_FOR_SERVICE, 0L, 0L, 0L, null, true
                )
            },
            scheduleTimeout = { _, onTimeout ->
                timeoutCallback = onTimeout
                {}
            },
            runAsync = { it.run() },
            onFinish = { timeoutFinishes++ }
        ).start()
        assertEquals(1, timeoutListeners.size)
        assertEquals(0, timeoutFinishes)
        timeoutCallback?.invoke()
        assertTrue(timeoutListeners.isEmpty())
        assertEquals(1, timeoutFinishes)

        // 2. NPatch 选定或 async 拒绝均能收敛并释放 PendingResult
        var asyncRejectedFinishes = 0
        val rejectedListeners = mutableListOf<RemoteHookConfigPublishListener>()
        TermsMirrorFlushCoordinator(
            isNoRootEnabled = { false },
            startNoRootRecovery = {},
            awaitNoRootRecovery = { it() },
            addPublishListener = { rejectedListeners += it },
            removePublishListener = { rejectedListeners -= it },
            requestStandardFlush = {},
            readDiagnostics = { error("unreachable") },
            scheduleTimeout = { _, _ -> {} },
            runAsync = { throw RejectedExecutionException("full") },
            onFinish = { asyncRejectedFinishes++ }
        ).start()
        assertTrue(rejectedListeners.isEmpty())
        assertEquals(1, asyncRejectedFinishes)

        // 3. 免 Root 开关开启时不注册标准通道监听器
        var noRootFinishes = 0
        val noRootListeners = mutableListOf<RemoteHookConfigPublishListener>()
        var awaitNoRootDone: (() -> Unit)? = null
        TermsMirrorFlushCoordinator(
            isNoRootEnabled = { true },
            startNoRootRecovery = {},
            awaitNoRootRecovery = { awaitNoRootDone = it },
            addPublishListener = { noRootListeners += it },
            removePublishListener = { noRootListeners -= it },
            requestStandardFlush = {},
            readDiagnostics = { error("unreachable") },
            scheduleTimeout = { _, _ -> error("no standard timeout expected") },
            runAsync = { error("no standard flush expected") },
            onFinish = { noRootFinishes++ }
        ).start()
        assertTrue(noRootListeners.isEmpty())
        assertEquals(0, noRootFinishes)
        awaitNoRootDone?.invoke()
        assertEquals(1, noRootFinishes)

        // 4. NPATCH_SELECTED bypass 事件也能结束标准等待
        val bypassListeners = mutableListOf<RemoteHookConfigPublishListener>()
        var bypassFinishes = 0
        TermsMirrorFlushCoordinator(
            isNoRootEnabled = { false },
            startNoRootRecovery = {},
            awaitNoRootRecovery = { it() },
            addPublishListener = { bypassListeners += it },
            removePublishListener = { bypassListeners -= it },
            requestStandardFlush = {},
            readDiagnostics = {
                RemoteHookConfigDiagnostics(
                    RemoteHookConfigPublishState.PUBLISHING, 0L, 0L, 0L, null, true
                )
            },
            scheduleTimeout = { _, _ -> {} },
            runAsync = { it.run() },
            onFinish = { bypassFinishes++ }
        ).start()
        bypassListeners.single().onRemoteHookConfigPublished(
            RemoteHookConfigPublishEvent(
                UserTermsDecision.ACCEPTED,
                RemoteHookConfigPublishResult.Failure(StandardPublisherPolicy.NPATCH_SELECTED)
            )
        )
        assertTrue(bypassListeners.isEmpty())
        assertEquals(1, bypassFinishes)
    }

    fun `npatch warm cache is preserved when excluded is nonempty and non-sync domains are stale`() {
        val defaultConfig = SnapshotHookConfigSource(
            RemoteHookConfigContract.resolveSourceValues(emptyMap<String, Any>()),
            revision = 1L
        )
        val excluded = StartupAdapterPlan.excludedForProcess(
            defaultConfig,
            processName = "tv.danmaku.bili",
            nPatchRuntime = true
        )
        assertTrue("NPatch main process normally has non-empty exclusions", excluded.isNotEmpty())

        val adaptPlan = AdaptPlanner.build(defaultConfig)
        val base = VersionAdapterTest().result().copy(
            domainRuleVersions = VersionAdapterContract.DOMAIN_RULE_VERSIONS,
            kmpGrpcWireEngine = VersionAdapter.HookPoint(
                "wire.Engine",
                "a",
                listOf("java.lang.Object", "[B", "java.lang.Object", "java.lang.Object")
            )
        )
        val fullCoverage = com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptCoverage(
            PointFamily.entries.associateWith {
                if (base.hasPoint(it)) {
                    com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptAssistGate.Attempt.FOUND
                } else {
                    com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptAssistGate.Attempt.CONCLUSIVE_MISSING
                }
            }
        )
        val cached = base.copy(coverage = fullCoverage)
        val snapshot = VersionAdapter.StartupCacheSnapshot(
            identity = VersionAdapter.HostIdentity(
                versionCode = cached.biliVersionCode,
                fingerprint = cached.hostFingerprint
            ),
            resetTimestamp = 0L,
            cached = cached
        )
        assertNotNull(snapshot.usableCache(highCandidateExists = true, plan = adaptPlan))
        assertTrue(HookEntry.isWarmCacheFreshForPlan(cached, adaptPlan))

        // 仅非同步域（如 KMP_WIRE / PLAYER_SPEED）或计划未请求的 SPLASH 域过期时，热缓存仍命中，
        // mergeRuntimeWithCached 会自动剥离过期域而保留计划内同步点位。
        assertFalse(adaptPlan.needs(PointFamily.SPLASH_ADS))
        val staleBackgroundOnly = cached.copy(
            domainRuleVersions = VersionAdapterContract.DOMAIN_RULE_VERSIONS + mapOf(
                AdaptDomain.KMP_WIRE.key to -1,
                AdaptDomain.PLAYER_SPEED.key to -1,
                AdaptDomain.SPLASH.key to -1
            )
        )
        assertTrue(HookEntry.isWarmCacheFreshForPlan(staleBackgroundOnly, adaptPlan))
        val merged = requireNotNull(VersionAdapter.mergeRuntimeWithCached(runtime = null, cached = staleBackgroundOnly))
        assertNull(merged.kmpGrpcWireEngine)
        assertNotNull(merged.commentHigh)

        // 若当前同步计划命中的域（如 COMMENT 或已启用的 SPLASH）规则过期，则拒绝热缓存命中。
        val staleComment = cached.copy(
            domainRuleVersions = VersionAdapterContract.DOMAIN_RULE_VERSIONS + (AdaptDomain.COMMENT.key to -1)
        )
        assertFalse(HookEntry.isWarmCacheFreshForPlan(staleComment, adaptPlan))

        val splashEnabledPlan = AdaptPlanner.build(
            SnapshotHookConfigSource(
                RemoteHookConfigContract.resolveSourceValues(
                    mapOf(FeaturePreferences.PURIFY_SPLASH_ADS to true)
                ),
                revision = 2L
            )
        )
        assertTrue(splashEnabledPlan.needs(PointFamily.SPLASH_ADS))
        assertFalse(HookEntry.isWarmCacheFreshForPlan(staleBackgroundOnly, splashEnabledPlan))
    }

    fun `player codec force resolves direct grpc wire surface even when kmpGrpcWireEngine is null`() {
        val wireLoader = renamingLoader(
            javaClass.classLoader!!,
            mapOf(KmpGrpcWireAdapter.ENGINE to GrpcEngine::class.java)
        )
        val direct = KmpGrpcWireAdapter.resolve(wireLoader, null)
        assertNotNull("cold cache null hook point must still resolve direct wire surface", direct)

        val registrar = PlayerPortTestRegistrar()
        val environment = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = wireLoader,
            hookPoints = HookPointRegistry(wireLoader),
            registrar = registrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> }
        )
        val result = PlayerCodecForceFeatureInstaller(
            codecPreferenceValue = PlayerCodecPreference.AV1.value,
            decodeModeValue = PlayerDecodeMode.FOLLOW_HOST.value,
            kmpGrpcWireEngine = null
        ).install(environment)

        assertEquals(FeatureInstallResult.Installed(5), result)
        assertTrue(
            "PlayerCodecForce must install KmpGrpcWireChannel directly on cold cache",
            registrar.hooks.containsKey("kmp.grpc.wire.channel")
        )

        val engineClass = wireLoader.loadClass(KmpGrpcWireAdapter.ENGINE)
        val engineInstance = engineClass.getConstructor(List::class.java).newInstance(emptyList<Any>())
        val descriptor = Descriptor(
            "bilibili.app.playerunite.v1",
            "Player",
            "PlayViewUnite",
            PlayViewUniteReq::class,
            DummyStrategy
        )
        val sink = object : Handler {
            override fun onNext(bytes: ByteArray?) = Unit
            override fun onCompleted() = Unit
            override fun onError(error: KMossException) = Unit
        }
        val sourceBytes = PlayViewUniteReq(VideoVod(CodeType.CODE_UNKNOWN, 16)).toByteArray()
        val wireBytes = registrar.invoke(
            "kmp.grpc.wire.channel",
            engineInstance,
            arrayOf(Service("grpc.biliapi.net"), descriptor, sourceBytes, sink, null)
        ) { args -> args[2] as ByteArray } as ByteArray
        val rewritten = PlayViewUniteReq.parseFrom(wireBytes)
        assertEquals(CodeType.CODEAV1, rewritten.getVod().getPreferCodecType())
        assertEquals(2064, rewritten.getVod().getFnval())
    }

    fun `publication authority store distinguishes direct boot transient faults from persistent faults`() {
        PublicationAuthorityStore.resetFaultStateForTest()
        try {
            val prefsMap = mutableMapOf<String, InMemoryPrefs>()
            val termsPrefs = InMemoryPrefs(
                mutableMapOf(
                    UserTermsConsentStore.KEY_DECISION to UserTermsDecision.ACCEPTED.name,
                    UserTermsConsentStore.KEY_TERMS_VERSION to UserTermsConsentStore.CURRENT_TERMS_VERSION,
                    UserTermsConsentStore.KEY_CONSENT_REVISION to 10L
                )
            )
            prefsMap[UserTermsConsentStore.PREF_FILE] = termsPrefs

            var failBridgeWithIo = false
            val bridgePrefs = InMemoryPrefs(mutableMapOf()) {
                if (failBridgeWithIo) throw RuntimeException(IOException("transient io"))
            }
            val pkg = "com.example.module"
            prefsMap["${pkg}_preferences"] = bridgePrefs

            val fakeContext = object : ContextWrapper(null) {
                override fun getApplicationContext(): Context = this
                override fun getPackageName(): String = pkg
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    prefsMap.getOrPut(name) { InMemoryPrefs(mutableMapOf()) }.instance
            }

            // 1. Direct Boot 未解锁前 fail-closed 返回 null，且不置死故障锁。
            assertNull(PublicationAuthorityStore.current(fakeContext, userUnlocked = { false }, storageProbe = { true }))
            assertFalse(PublicationAuthorityStore.isStorageFaultedForTest())
            assertFalse(PublicationAuthorityStore.isTransientFaultedForTest())

            // 用户解锁后立刻正常发布，不重启进程。
            val unlockedPub = PublicationAuthorityStore.current(fakeContext, userUnlocked = { true }, storageProbe = { true })
            assertNotNull(unlockedPub)
            assertEquals(UserTermsDecision.ACCEPTED, unlockedPub?.decision)

            // 2. 瞬态 IOException 触发 transientFault，但不永久锁死；恢复健康后自检放行。
            failBridgeWithIo = true
            assertNull(PublicationAuthorityStore.current(fakeContext, userUnlocked = { true }, storageProbe = { true }))
            assertFalse(PublicationAuthorityStore.isStorageFaultedForTest())
            assertTrue(PublicationAuthorityStore.isTransientFaultedForTest())

            // 健康探测仍不通过时维持 fail-closed。
            assertFalse(PublicationAuthorityStore.clearStorageFaultIfHealthy(fakeContext, userUnlocked = { true }, storageProbe = { false }))
            assertNull(PublicationAuthorityStore.current(fakeContext, userUnlocked = { true }, storageProbe = { false }))

            // 存储恢复健康后下一次读取自动通过自检并恢复授权。
            failBridgeWithIo = false
            val recoveredPub = PublicationAuthorityStore.current(fakeContext, userUnlocked = { true }, storageProbe = { true })
            assertNotNull(recoveredPub)
            assertFalse(PublicationAuthorityStore.isTransientFaultedForTest())

            // 3. 已解锁状态的持久化 SecurityException/IllegalStateException 锁定持久故障，不能被自检清除。
            var failWithSecurity = false
            val secureBridge = InMemoryPrefs(mutableMapOf()) {
                if (failWithSecurity) throw SecurityException("persistent permission denial")
            }
            prefsMap["${pkg}_preferences"] = secureBridge
            failWithSecurity = true
            assertNull(PublicationAuthorityStore.current(fakeContext, userUnlocked = { true }, storageProbe = { true }))
            assertTrue(PublicationAuthorityStore.isStorageFaultedForTest())
            failWithSecurity = false
            assertFalse(PublicationAuthorityStore.clearStorageFaultIfHealthy(fakeContext, userUnlocked = { true }, storageProbe = { true }))
            assertNull(PublicationAuthorityStore.current(fakeContext, userUnlocked = { true }, storageProbe = { true }))

            // 4. 显式 stopGrants() 同样保持不可自愈阻断。
            PublicationAuthorityStore.resetFaultStateForTest()
            PublicationAuthorityStore.stopGrants()
            assertFalse(PublicationAuthorityStore.clearStorageFaultIfHealthy(fakeContext, userUnlocked = { true }, storageProbe = { true }))
            assertNull(PublicationAuthorityStore.current(fakeContext, userUnlocked = { true }, storageProbe = { true }))
        } finally {
            PublicationAuthorityStore.resetFaultStateForTest()
        }
    }

    fun `atomic file stores recover from bak files without short-circuiting on baseFile`() {
        val tempDir = Files.createTempDirectory("stage-a-atomic").toFile()
        try {
            // 1. NoRootSupportStore
            val payloadJson = JSONObject().put("schema", 1).toString()
            assertEquals(
                payloadJson,
                NoRootSupportStore.readAtomicSnapshotPayload { ByteArrayInputStream(payloadJson.toByteArray()) }
            )
            assertNull(NoRootSupportStore.readAtomicSnapshotPayload { throw FileNotFoundException("missing") })

            // 2. FreeCopyConfigStore
            val validFreeCopyBytes = ByteArrayOutputStream().apply {
                DataOutputStream(this).use { out ->
                    out.writeInt(0x42494C46)
                    out.writeInt(1)
                    out.writeLong(77L)
                    out.writeBoolean(true)
                    out.writeBoolean(false)
                }
            }.toByteArray()
            val snapshot = FreeCopyConfigStore.readFrom { ByteArrayInputStream(validFreeCopyBytes) }
            assertEquals(FreeCopyConfigStore.Snapshot(commentEnabled = true, descriptionEnabled = false, revision = 77L), snapshot)
            assertNull(FreeCopyConfigStore.readFrom { throw FileNotFoundException("missing") })

            // 3. FreeCopyImportJournal: baseFile 缺失仅 .bak 存在时 exists 与 readFrom 仍可恢复
            val journalBase = File(tempDir, "settings_import_free_copy_journal.bin")
            val journalBak = File("${journalBase.path}.bak")
            assertFalse(FreeCopyImportJournal.hasAtomicFile(journalBase))
            assertEquals(
                FreeCopyJournalRead.Missing,
                FreeCopyImportJournal.readFrom(journalBase, { throw FileNotFoundException("absent") })
            )

            assertTrue(journalBak.writeBytesCompat(ByteArrayOutputStream().apply {
                DataOutputStream(this).use { out ->
                    out.writeInt(0x42494C4A)
                    out.writeInt(1)
                    out.writeLong(99L)
                    out.writeBoolean(false)
                    out.writeBoolean(true)
                }
            }.toByteArray()))
            assertFalse(journalBase.exists())
            assertTrue(FreeCopyImportJournal.hasAtomicFile(journalBase))
            assertEquals(
                FreeCopyJournalRead.Ready(PendingFreeCopyMirror(commentEnabled = false, descriptionEnabled = true, revision = 99L)),
                FreeCopyImportJournal.readFrom(journalBase, { journalBak.inputStream() })
            )
            // 文件确实存在但因权限/短暂 I/O 失败时返回 Unreadable，不得销毁前滚意图。
            assertEquals(
                FreeCopyJournalRead.Unreadable,
                FreeCopyImportJournal.readFrom(journalBase, { throw FileNotFoundException("EACCES") })
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    fun `fgs promotion helper and usesAccessibility policy handle background restrictions safely`() {
        assertTrue(runForegroundSafely { })
        assertFalse(runForegroundSafely { throw SecurityException("fgs denied") })
        assertFalse(runForegroundSafely { throw IllegalStateException("background fgs denied") })

        // 纯原生 RPC 任务即使启动时无障碍已连接也不应因无障碍抖动被取消。
        val routing = AgentNativeRoutingPolicy()
        routing.startingNative()
        routing.began()
        assertFalse(
            com.Bilibili_Innocent_Lab.xposedmodule.agent.agentTaskUsesAccessibility(
                routing.uiOnly,
                usedAccessibility = false
            )
        )
        // 执行过 get_ui_state / UI 操作后断连必须取消。
        assertTrue(
            com.Bilibili_Innocent_Lab.xposedmodule.agent.agentTaskUsesAccessibility(
                routing.uiOnly,
                usedAccessibility = true
            )
        )
        // 原生转交 UI 或纯 UI 任务必须依赖无障碍。
        assertTrue(
            routing.handoff(
                JSONObject().put("ok", true).put(
                    "data",
                    JSONObject().put("status", "finished").put("native_window_released", true)
                )
            )
        )
        assertTrue(
            com.Bilibili_Innocent_Lab.xposedmodule.agent.agentTaskUsesAccessibility(
                routing.uiOnly,
                usedAccessibility = false
            )
        )

        val uiOnlyPolicy = AgentNativeRoutingPolicy().apply { noNative() }
        assertTrue(
            com.Bilibili_Innocent_Lab.xposedmodule.agent.agentTaskUsesAccessibility(
                uiOnlyPolicy.uiOnly,
                usedAccessibility = false
            )
        )
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (StageACompatAndFgsTest) -> Unit) {
            val s = StageACompatAndFgsTest()
            block(s)
        }
        runIsolated { s -> s.`terms mirror coordinator waits boundedly for standard channel without leaking listeners`() }
        runIsolated { s -> s.`terms mirror coordinator finishes cleanly on timeout bypass or async rejection`() }
        runIsolated { s -> s.`npatch warm cache is preserved when excluded is nonempty and non-sync domains are stale`() }
        runIsolated { s -> s.`player codec force resolves direct grpc wire surface even when kmpGrpcWireEngine is null`() }
        runIsolated { s -> s.`publication authority store distinguishes direct boot transient faults from persistent faults`() }
        runIsolated { s -> s.`atomic file stores recover from bak files without short-circuiting on baseFile`() }
        runIsolated { s -> s.`fgs promotion helper and usesAccessibility policy handle background restrictions safely`() }
    }

    private object DummyStrategy : SerializationStrategy<Any>

    private fun File.writeBytesCompat(bytes: ByteArray): Boolean {
        writeBytes(bytes)
        return isFile
    }

    private class InMemoryPrefs(
        val values: MutableMap<String, Any?>,
        private val onGetAll: () -> Unit = {}
    ) {
        val instance: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "edit" -> createEditor()
                "getAll" -> {
                    onGetAll()
                    HashMap(values)
                }
                "contains" -> values.containsKey(args?.get(0) as? String)
                "getString" -> (values[args?.get(0) as? String] as? String) ?: (args?.getOrNull(1) as? String)
                "getLong" -> (values[args?.get(0) as? String] as? Long) ?: (args?.getOrNull(1) as? Long ?: 0L)
                "getInt" -> (values[args?.get(0) as? String] as? Int) ?: (args?.getOrNull(1) as? Int ?: 0)
                "getBoolean" -> (values[args?.get(0) as? String] as? Boolean) ?: (args?.getOrNull(1) as? Boolean ?: false)
                else -> null
            }
        } as SharedPreferences

        private fun createEditor(): SharedPreferences.Editor {
            val puts = LinkedHashMap<String, Any?>()
            val removes = mutableListOf<String>()
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)
            ) { _, method, args ->
                when (method.name) {
                    "putLong", "putInt", "putString", "putBoolean" -> {
                        puts[args!![0] as String] = args[1]
                        editor
                    }
                    "remove" -> {
                        removes += args!![0] as String
                        editor
                    }
                    "clear" -> {
                        values.clear()
                        editor
                    }
                    "apply" -> {
                        removes.forEach { values.remove(it) }
                        values.putAll(puts)
                        Unit
                    }
                    "commit" -> {
                        removes.forEach { values.remove(it) }
                        values.putAll(puts)
                        true
                    }
                    else -> editor
                }
            } as SharedPreferences.Editor
            return editor
        }
    }

    private fun renamingLoader(parent: ClassLoader, templates: Map<String, Class<*>>): ClassLoader =
        object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
                val template = templates[name] ?: return@synchronized super.loadClass(name, resolve)
                val loaded = findLoadedClass(name) ?: run {
                    val raw = requireNotNull(
                        template.getResourceAsStream("/${template.name.replace('.', '/')}.class")
                    ).use { it.readBytes() }
                    val fromInternal = template.name.replace('.', '/')
                    val toInternal = name.replace('.', '/')
                    val bytes = rewriteUtf8ConstantPool(raw, fromInternal, toInternal)
                    defineClass(name, bytes, 0, bytes.size)
                }
                if (resolve) resolveClass(loaded)
                loaded
            }
        }

    private fun rewriteUtf8ConstantPool(raw: ByteArray, from: String, to: String): ByteArray {
        val input = DataInputStream(ByteArrayInputStream(raw))
        val outBuffer = ByteArrayOutputStream()
        val output = DataOutputStream(outBuffer)
        output.writeInt(input.readInt())
        output.writeShort(input.readUnsignedShort())
        output.writeShort(input.readUnsignedShort())
        val count = input.readUnsignedShort()
        output.writeShort(count)
        var index = 1
        while (index < count) {
            val tag = input.readUnsignedByte()
            output.writeByte(tag)
            if (tag == 1) {
                val value = input.readUTF().replace(from, to)
                output.writeUTF(value)
            } else {
                val length = when (tag) {
                    3, 4, 9, 10, 11, 12, 17, 18 -> 4
                    5, 6 -> 8
                    7, 8, 16, 19, 20 -> 2
                    15 -> 3
                    else -> error("Unsupported CP tag: $tag")
                }
                repeat(length) { output.writeByte(input.readUnsignedByte()) }
                if (tag == 5 || tag == 6) index++
            }
            index++
        }
        output.write(input.readBytes())
        output.flush()
        return outBuffer.toByteArray()
    }
}
