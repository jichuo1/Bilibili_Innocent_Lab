package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentScanEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerCodecForceFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerCodecPreference
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerDecodeMode
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import com.bapis.bilibili.app.playerunite.v1.CodeType
import com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReq
import com.bapis.bilibili.app.playerunite.v1.VideoVod
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kmpfixture.modern.Descriptor
import kmpfixture.modern.GrpcEngine
import kmpfixture.modern.Handler
import kmpfixture.modern.KMossException
import kmpfixture.modern.Service
import kotlinx.serialization.SerializationStrategy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HostAudioModelDiagnosticsTest {
    private fun receipt(state: String? = null) = HostRuntimeDiagnosticsCodec.encode(
        HostRuntimeDiagnosticsSnapshot(1000, HostRuntimeDiagnosticsCodec.TARGET_PACKAGE, emptyList(), audioModelState = state))
    fun `live model readiness survives the diagnostic wire`() {
        assertEquals("ready", HostRuntimeDiagnosticsCodec.decodeOrNull(receipt("ready"))?.audioModelState)
    }
    fun `old receipts stay readable without claiming model readiness`() {
        val old = JSONObject(receipt()).apply { remove("audio_model") }.toString()
        assertNotNull(HostRuntimeDiagnosticsCodec.decodeOrNull(old))
        assertNull(HostRuntimeDiagnosticsCodec.decodeOrNull(old)?.audioModelState)
    }
    fun `arbitrary text and non-string model states are rejected`() {
        for (value in listOf<Any>("/private/path/untrusted", 1, JSONObject())) {
            assertNull(HostRuntimeDiagnosticsCodec.decodeOrNull(JSONObject(receipt()).put("audio_model", value).toString()))
        }
    }

    fun `player codec force resolves direct grpc wire surface even when kmpGrpcWireEngine is null`() {
        val wireLoader = TestRenamingClassLoader(
            parent = javaClass.classLoader!!,
            templates = mapOf(KmpGrpcWireAdapter.ENGINE to GrpcEngine::class.java)
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

    private object DummyStrategy : SerializationStrategy<Any>

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostAudioModelDiagnosticsTest) -> Unit) {
            val s = HostAudioModelDiagnosticsTest()
            block(s)
        }
        runIsolated { s -> s.`live model readiness survives the diagnostic wire`() }
        runIsolated { s -> s.`old receipts stay readable without claiming model readiness`() }
        runIsolated { s -> s.`arbitrary text and non-string model states are rejected`() }
        runIsolated { s -> s.`player codec force resolves direct grpc wire surface even when kmpGrpcWireEngine is null`() }
    
        HostClassLoaderIdentityCases().`same named classes resolve and match only inside their host loader`()
        HostProcessWakePolicyCases().verifyAllCases()
        HostReceiptPayloadCases().verifyAllCases()
        HostRuntimeDiagnosticsCodecCases().verifyAllCases()
        HostThreadGuardCases().verifyAllCases()
    }
}

private class HostClassLoaderIdentityCases {
    fun `same named classes resolve and match only inside their host loader`() {
        val moduleType = Fixture::class.java
        val name = moduleType.name
        val bytes = requireNotNull(moduleType.getResourceAsStream("/" + name.replace('.', '/') + ".class"))
            .use { it.readBytes() }
        fun hostLoader() = object : ClassLoader(moduleType.classLoader) {
            override fun loadClass(requested: String, resolve: Boolean): Class<*> {
                if (requested != name) return super.loadClass(requested, resolve)
                return findLoadedClass(name) ?: defineClass(name, bytes, 0, bytes.size).also {
                    if (resolve) resolveClass(it)
                }
            }
        }
        val firstLoader = hostLoader()
        val secondLoader = hostLoader()
        val first = requireNotNull(KavaMemberLookup.classOrNull(firstLoader, name))
        val second = requireNotNull(KavaMemberLookup.classOrNull(secondLoader, name))
        val hostView = first.getDeclaredConstructor().newInstance()

        assertNotSame(moduleType, first)
        assertNotSame(first, second)
        assertTrue(first.isInstance(hostView))
        assertFalse(moduleType.isInstance(hostView))
        assertFalse(second.isInstance(hostView))
        assertSame(first, KavaMemberLookup.classOrNull(firstLoader, name))
    }

    class Fixture
}

private class HostProcessWakePolicyCases {

    private val target = "tv.danmaku.bili"

    private fun provider(
        authority: String?,
        exported: Boolean = true,
        enabled: Boolean = true,
        readPermission: String? = null,
        writePermission: String? = null,
        processName: String? = "tv.danmaku.bili"
    ) = HostProviderCandidate(authority, exported, enabled, readPermission, writePermission, processName)

    /**
     * 真实清单片段（9.11.0(9110400) `aapt2 dump xmltree` + 8.90.2 真机 `dumpsys package providers`）。
     *
     * `VideoDownloadProvider` 是最重要的那个反例：它同样是 provider，但声明了
     * `android:process=":download"`——拉起来的是下载子进程，里面**没有快照桥**，纯白费。
     */
    fun picksExportedMainProcessProvidersAndRejectsTheDownloadOne() {
        val selected = HostProcessWakePolicy.selectAuthorities(target, listOf(
            provider("tv.danmaku.bili.providers.VideoDownloadProvider",
                exported = false, processName = "tv.danmaku.bili:download"),
            provider("tv.danmaku.bili.providers.BiliDataProvider"),
            provider("com.bilibili.lib.accessbridge.BiliAccessContentProvider"),
            provider("tv.danmaku.bili.fileprovider", exported = false),
            provider("tv.danmaku.bili.appinit", exported = false)
        ))
        assertEquals(listOf(
            "com.bilibili.lib.accessbridge.BiliAccessContentProvider",
            "tv.danmaku.bili.providers.BiliDataProvider"
        ), selected)
    }

    fun rejectsEverythingThatIsNotFreelyReachableInTheMainProcess() {
        listOf(
            provider("a.not.exported", exported = false),
            provider("a.disabled", enabled = false),
            provider("a.read.gated", readPermission = "tv.danmaku.bili.permission.X"),
            provider("a.write.gated", writePermission = "tv.danmaku.bili.permission.X"),
            provider("a.other.process", processName = "tv.danmaku.bili:web"),
            provider("a.null.process", processName = null),
            provider(null),
            provider("   ")
        ).forEach {
            assertEquals(it.authority.orEmpty(), emptyList<String>(),
                HostProcessWakePolicy.selectAuthorities(target, listOf(it)))
        }
    }

    /** `authority` 允许是分号分隔的多个；取第一个。顺序固定、条数有界，便于按日志复现。 */
    fun takesTheFirstAuthorityAndStaysBounded() {
        assertEquals(listOf("first.one"),
            HostProcessWakePolicy.selectAuthorities(target, listOf(provider("first.one;second.one"))))
        val many = (1..10).map { provider("auth.%02d".format(it)) }
        val selected = HostProcessWakePolicy.selectAuthorities(target, many.reversed())
        assertEquals(HostProcessWakePolicy.MAX_AUTHORITIES, selected.size)
        assertEquals(listOf("auth.01", "auth.02", "auth.03"), selected)
        assertEquals(selected, HostProcessWakePolicy.selectAuthorities(target, many))
    }

    /** 唤起只对"压根没人应答"有意义；协议、摘要、身份类失败唤起多少次都一样。 */
    fun onlyUnreachableFailuresAreWorthWakingTheHostFor() {
        listOf(ReceiptQueryFailure.UNHANDLED, ReceiptQueryFailure.TIMEOUT, ReceiptQueryFailure.SEND_FAILED)
            .forEach { assertTrue(it.name, HostProcessWakePolicy.shouldWake(it)) }
        listOf(ReceiptQueryFailure.NONE, ReceiptQueryFailure.MALFORMED_RESPONSE,
            ReceiptQueryFailure.NONCE_MISMATCH, ReceiptQueryFailure.UNSUPPORTED_PROTOCOL,
            ReceiptQueryFailure.DIGEST_MISMATCH, ReceiptQueryFailure.SOURCE_MISMATCH,
            ReceiptQueryFailure.SURFACE_MISMATCH, ReceiptQueryFailure.SESSION_MISMATCH,
            ReceiptQueryFailure.STORE_FAILED)
            .forEach { assertFalse(it.name, HostProcessWakePolicy.shouldWake(it)) }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostProcessWakePolicyCases) -> Unit) {
            val s = HostProcessWakePolicyCases()
            block(s)
        }
        runIsolated { s -> s.picksExportedMainProcessProvidersAndRejectsTheDownloadOne() }
        runIsolated { s -> s.rejectsEverythingThatIsNotFreelyReachableInTheMainProcess() }
        runIsolated { s -> s.takesTheFirstAuthorityAndStaysBounded() }
        runIsolated { s -> s.onlyUnreachableFailuresAreWorthWakingTheHostFor() }
    }
}

private class HostReceiptPayloadCases {
    private val source = HostRuntimeDiagnosticsSource(9110400, 1000, 17)
    private fun scan(surface: String) = MineComponentSnapshotCodec.encode("tv.danmaku.bili", emptySet(),
        listOf(requireNotNull(MineComponentScanEntry.create("item", "test", "1", null, true))), surface, 1)
    private fun validate(channel: String, payload: String, expected: HostRuntimeDiagnosticsSource = source,
                         digest: String = HostRuntimeDiagnosticsQueryContract.sha256(payload)) =
        HostReceiptPayload.validate(channel, payload, digest, source, expected)

    fun `each surface stays in its own slot and versions must all match`() {
        val payload = scan("mine")
        assertEquals(ReceiptQueryFailure.NONE, validate("mine", payload))
        assertEquals(ReceiptQueryFailure.SURFACE_MISMATCH, validate("home_tabs", payload))
        assertEquals(ReceiptQueryFailure.SURFACE_MISMATCH, validate("unknown", payload))
        assertEquals(ReceiptQueryFailure.SOURCE_MISMATCH, validate("mine", payload, source.copy(targetVersionCode = 912)))
        assertEquals(ReceiptQueryFailure.SOURCE_MISMATCH, validate("mine", payload, source.copy(targetUpdateTime = 2000)))
        assertEquals(ReceiptQueryFailure.SOURCE_MISMATCH, validate("mine", payload, source.copy(moduleVersionCode = 18)))
    }
    fun `corrupt empty and oversized publications are never accepted`() {
        assertEquals(ReceiptQueryFailure.DIGEST_MISMATCH, validate("mine", scan("mine"), digest = "0".repeat(64)))
        assertEquals(ReceiptQueryFailure.MALFORMED_RESPONSE, validate("mine", "{}"))
        assertEquals(ReceiptQueryFailure.MALFORMED_RESPONSE, validate("mine", ""))
        assertEquals(ReceiptQueryFailure.MALFORMED_RESPONSE, validate("mine", "x".repeat(65537)))
        val empty = MineComponentSnapshotCodec.encode("tv.danmaku.bili", emptySet(), emptyList(), "mine", 1)
        assertEquals(ReceiptQueryFailure.MALFORMED_RESPONSE, validate("mine", empty))
    }
    fun `diagnostics codec remains mandatory independently of digest`() {
        val payload = HostRuntimeDiagnosticsCodec.encode(HostRuntimeDiagnosticsSnapshot(1, "tv.danmaku.bili", emptyList()))
        assertEquals(ReceiptQueryFailure.NONE, validate("diagnostics", payload))
        assertEquals(ReceiptQueryFailure.MALFORMED_RESPONSE, validate("diagnostics", scan("mine")))
        val expanded = payload + " ".repeat(70_000)
        assertTrue(expanded.length > 64 * 1024 && expanded.length <= HostRuntimeDiagnosticsCodec.MAX_PAYLOAD_CHARS)
        assertEquals(ReceiptQueryFailure.NONE, validate("diagnostics", expanded))
        val overExpanded = payload + " ".repeat(HostRuntimeDiagnosticsCodec.MAX_PAYLOAD_CHARS)
        assertEquals(ReceiptQueryFailure.MALFORMED_RESPONSE, validate("diagnostics", overExpanded))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostReceiptPayloadCases) -> Unit) {
            val s = HostReceiptPayloadCases()
            block(s)
        }
        runIsolated { s -> s.`each surface stays in its own slot and versions must all match`() }
        runIsolated { s -> s.`corrupt empty and oversized publications are never accepted`() }
        runIsolated { s -> s.`diagnostics codec remains mandatory independently of digest`() }
    }
}

private class HostRuntimeDiagnosticsCodecCases {
    fun `round trip keeps only bounded allowlisted evidence`() {
        var evidence: HostRuntimeFeatureEvidence? = null
        evidence = HostRuntimeDiagnosticsCodec.increment(
            evidence, "home_recommend_ads_removed", FeatureRuntimeStage.ADAPTED, 1
        )
        evidence = HostRuntimeDiagnosticsCodec.increment(
            evidence, "home_recommend_ads_removed", FeatureRuntimeStage.OBSERVED, 2
        )
        evidence = HostRuntimeDiagnosticsCodec.increment(
            evidence, "home_recommend_ads_removed", FeatureRuntimeStage.APPLIED, 3
        )
        evidence = HostRuntimeDiagnosticsCodec.withInstallOutcome(
            evidence,
            "home_recommend_ads_removed",
            HostFeatureInstallState.INSTALLED,
            hookCount = 3,
            reasonCode = null
        )
        val decoded = HostRuntimeDiagnosticsCodec.decodeOrNull(
            HostRuntimeDiagnosticsCodec.encode(
                HostRuntimeDiagnosticsSnapshot(
                    capturedAtEpochMs = 123L,
                    processName = HostRuntimeDiagnosticsCodec.TARGET_PACKAGE,
                    features = listOf(requireNotNull(evidence)),
                    bootstrap = HostRuntimeBootstrapEvidence(
                        bootstrapReached = true,
                        configState = HostConfigState.ACCEPTED,
                        configGeneration = 42L,
                        installChainState = HostInstallChainState.COMPLETED,
                        hookPointInstalledCount = 3
                    )
                )
            )
        )

        val feature = requireNotNull(decoded).features.single()
        assertEquals(1L, feature.adaptedCount)
        assertEquals(1L, feature.observedCount)
        assertEquals(1L, feature.appliedCount)
        assertEquals(1, decoded.adaptedFeatureCount)
        assertEquals(1, decoded.observedFeatureCount)
        assertEquals(1, decoded.appliedFeatureCount)
        assertEquals(HostFeatureInstallState.INSTALLED, feature.installState)
        assertEquals(3, feature.installedHookCount)
        assertEquals(42L, decoded.bootstrap.configGeneration)
        assertEquals(HostInstallChainState.COMPLETED, decoded.bootstrap.installChainState)
    }

    fun `unknown feature never enters the protocol`() {
        assertNull(
            HostRuntimeDiagnosticsCodec.increment(
                null, "arbitrary_member_name", FeatureRuntimeStage.APPLIED, 1
            )
        )
    }

    fun `decoder rejects unknown duplicate and invalid counters`() {
        val valid = HostRuntimeDiagnosticsCodec.encode(
            HostRuntimeDiagnosticsSnapshot(
                capturedAtEpochMs = 123L,
                processName = HostRuntimeDiagnosticsCodec.TARGET_PACKAGE,
                features = listOf(
                    HostRuntimeFeatureEvidence("comment_filter", 1L, 1L, 0L)
                )
            )
        )
        val unknown = JSONObject(valid).apply {
            getJSONArray("features").getJSONObject(0).put("id", "unknown")
        }.toString()
        val invalid = JSONObject(valid).apply {
            getJSONArray("features").getJSONObject(0).put("adapted", 2L)
        }.toString()
        val duplicate = JSONObject(valid).apply {
            getJSONArray("features").put(
                JSONObject(getJSONArray("features").getJSONObject(0).toString())
            )
        }.toString()

        assertNull(HostRuntimeDiagnosticsCodec.decodeOrNull(unknown))
        assertNull(HostRuntimeDiagnosticsCodec.decodeOrNull(invalid))
        assertNull(HostRuntimeDiagnosticsCodec.decodeOrNull(duplicate))
        assertTrue(valid.length <= HostRuntimeDiagnosticsCodec.MAX_PAYLOAD_CHARS)
    }

    fun `decoder rejects impossible bootstrap and install combinations`() {
        val valid = HostRuntimeDiagnosticsCodec.encode(
            HostRuntimeDiagnosticsSnapshot(
                capturedAtEpochMs = 123L,
                processName = HostRuntimeDiagnosticsCodec.TARGET_PACKAGE,
                features = listOf(
                    HostRuntimeFeatureEvidence(
                        "comment_filter",
                        1L,
                        0L,
                        0L,
                        HostFeatureInstallState.INSTALLED,
                        installedHookCount = 1
                    )
                )
            )
        )
        val acceptedWithoutGeneration = JSONObject(valid).apply {
            getJSONObject("bootstrap").put("config", HostConfigState.ACCEPTED.name)
        }.toString()
        val installedWithoutHooks = JSONObject(valid).apply {
            getJSONArray("features").getJSONObject(0).put("hooks", 0)
        }.toString()

        assertNull(HostRuntimeDiagnosticsCodec.decodeOrNull(acceptedWithoutGeneration))
        assertNull(HostRuntimeDiagnosticsCodec.decodeOrNull(installedWithoutHooks))
    }

    fun `expanded limits preserve schema 3 wire compatibility up to 512 features and 128 KiB`() {
        assertEquals(3, HostRuntimeDiagnosticsCodec.CURRENT_SCHEMA_VERSION)
        assertEquals(512, HostRuntimeDiagnosticsCodec.MAX_FEATURE_COUNT)
        assertEquals(128 * 1024, HostRuntimeDiagnosticsCodec.MAX_PAYLOAD_CHARS)

        val entries512 = List(512) { HostRuntimeFeatureEvidence("comment_filter", 1L, 0L, 0L) }
        val encoded512 = HostRuntimeDiagnosticsCodec.encode(
            HostRuntimeDiagnosticsSnapshot(123L, HostRuntimeDiagnosticsCodec.TARGET_PACKAGE, entries512)
        )
        assertNotNull(HostRuntimeDiagnosticsCodec.decodeOrNull(encoded512))
        assertTrue(
            runCatching {
                HostRuntimeDiagnosticsCodec.encode(
                    HostRuntimeDiagnosticsSnapshot(
                        123L,
                        HostRuntimeDiagnosticsCodec.TARGET_PACKAGE,
                        entries512 + HostRuntimeFeatureEvidence("comment_filter", 1L, 0L, 0L)
                    )
                )
            }.exceptionOrNull() is IllegalArgumentException
        )

        val paddingCount = HostRuntimeDiagnosticsCodec.MAX_PAYLOAD_CHARS - encoded512.length
        assertTrue(paddingCount > 64 * 1024)
        val exactMaxPayload = encoded512 + " ".repeat(paddingCount)
        assertEquals(HostRuntimeDiagnosticsCodec.MAX_PAYLOAD_CHARS, exactMaxPayload.length)
        assertNotNull(HostRuntimeDiagnosticsCodec.decodeOrNull(exactMaxPayload))
        assertNull(HostRuntimeDiagnosticsCodec.decodeOrNull(exactMaxPayload + " "))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostRuntimeDiagnosticsCodecCases) -> Unit) {
            val s = HostRuntimeDiagnosticsCodecCases()
            block(s)
        }
        runIsolated { s -> s.`round trip keeps only bounded allowlisted evidence`() }
        runIsolated { s -> s.`unknown feature never enters the protocol`() }
        runIsolated { s -> s.`decoder rejects unknown duplicate and invalid counters`() }
        runIsolated { s -> s.`decoder rejects impossible bootstrap and install combinations`() }
        runIsolated { s -> s.`expanded limits preserve schema 3 wire compatibility up to 512 features and 128 KiB`() }
    }
}

private class HostThreadGuardCases {

    fun setUp() = HostThreadGuard.resetForTests()

    fun tearDown() = HostThreadGuard.resetForTests()

    fun `run swallows any throwable and keeps counting`() {
        HostThreadGuard.run("k") { throw IllegalStateException("boom") }
        HostThreadGuard.run("k") { throw NoSuchMethodError("linkage") }
        // Error 也必须拦住：宿主进程死于 StackOverflowError 和死于 NPE 没有区别。
        HostThreadGuard.run("k") { throw StackOverflowError() }
        assertEquals(3L, HostThreadGuard.failureCounts()["k"])
    }

    fun `run does not intercept the success path`() {
        var ran = false
        HostThreadGuard.run("k") { ran = true }
        assertTrue(ran)
        assertTrue(HostThreadGuard.failureCounts().isEmpty())
    }

    fun `call returns the fallback on failure and the value on success`() {
        assertEquals(7, HostThreadGuard.call("k", 7) { error("boom") })
        assertEquals(3, HostThreadGuard.call("k", 7) { 3 })
        assertNull(HostThreadGuard.call<String?>("n", null) { error("boom") })
        assertEquals(1L, HostThreadGuard.failureCounts()["k"])
    }

    fun `a labeled return passes through the guard without counting as a failure`() {
        var reachedEnd = false
        val listener = fun(early: Boolean): Boolean {
            HostThreadGuard.run("k") {
                if (early) return true
                reachedEnd = true
            }
            return false
        }
        assertTrue(listener(true))
        assertFalse(reachedEnd)
        assertFalse(listener(false))
        assertTrue(reachedEnd)
        assertTrue(HostThreadGuard.failureCounts().isEmpty())
    }

    fun `host failures keep their identity and module failures stay in the guard`() {
        val hostCalls = HostCallPassthrough()
        val hostFailure = IllegalStateException("host click")
        var finished = false
        val handled = HostThreadGuard.call("touch", false) {
            hostCalls.host { throw hostFailure }
            finished = true
            true
        }
        assertTrue("Module state still finishes after a host failure", finished)
        assertTrue(handled)
        assertTrue("Host failures are not module failures", HostThreadGuard.failureCounts().isEmpty())
        val thrown = runCatching { hostCalls.rethrow() }.exceptionOrNull()
        assertTrue("The same throwable goes back to the host", thrown === hostFailure)

        hostCalls.rethrow()
        assertFalse(HostThreadGuard.call("touch", false) {
            hostCalls.host { }
            error("module bug")
        })
        hostCalls.rethrow()
        assertEquals(1L, HostThreadGuard.failureCounts()["touch"])
    }

    fun `runnable is reusable so removeCallbacks still matches the same instance`() {
        var calls = 0
        val runnable = HostThreadGuard.runnable("k") {
            calls++
            throw IllegalArgumentException("boom")
        }
        runnable.run()
        runnable.run()
        assertEquals(2, calls)
        assertEquals(2L, HostThreadGuard.failureCounts()["k"])
    }

    fun `failures are counted per key and never deduplicated away`() {
        repeat(10) { HostThreadGuard.run("a") { error("boom") } }
        HostThreadGuard.run("b") { error("boom") }
        // 超过日志上限后仍然继续计数——重复崩溃不能变成静默。
        assertTrue(10L > HostThreadGuard.LOG_LIMIT_PER_KEY)
        assertEquals(10L, HostThreadGuard.failureCounts()["a"])
        assertEquals(1L, HostThreadGuard.failureCounts()["b"])
    }

    fun `guarded thread body does not terminate on an escaping throwable`() {
        val finished = CountDownLatch(1)
        var uncaught: Throwable? = null
        val thread = Thread({
            HostThreadGuard.run("worker") { throw IllegalStateException("boom") }
            finished.countDown()
        }, "guard-test")
        thread.setUncaughtExceptionHandler { _, throwable -> uncaught = throwable }
        thread.start()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        thread.join(5_000)
        assertNull(uncaught)
        assertFalse(thread.isAlive)
    }

    fun `counts accumulate across threads`() {
        val threads = (1..4).map {
            Thread { repeat(25) { HostThreadGuard.run("shared") { error("boom") } } }
        }
        threads.forEach(Thread::start)
        threads.forEach { it.join(5_000) }
        assertEquals(100L, HostThreadGuard.failureCounts()["shared"])
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostThreadGuardCases) -> Unit) {
            val s = HostThreadGuardCases()
            s.setUp()
            try {
                block(s)
            } finally {
                s.tearDown()
            }
        }
        runIsolated { s -> s.`run swallows any throwable and keeps counting`() }
        runIsolated { s -> s.`run does not intercept the success path`() }
        runIsolated { s -> s.`call returns the fallback on failure and the value on success`() }
        runIsolated { s -> s.`a labeled return passes through the guard without counting as a failure`() }
        runIsolated { s -> s.`host failures keep their identity and module failures stay in the guard`() }
        runIsolated { s -> s.`runnable is reusable so removeCallbacks still matches the same instance`() }
        runIsolated { s -> s.`failures are counted per key and never deduplicated away`() }
        runIsolated { s -> s.`guarded thread body does not terminate on an escaping throwable`() }
        runIsolated { s -> s.`counts accumulate across threads`() }
    }
}
