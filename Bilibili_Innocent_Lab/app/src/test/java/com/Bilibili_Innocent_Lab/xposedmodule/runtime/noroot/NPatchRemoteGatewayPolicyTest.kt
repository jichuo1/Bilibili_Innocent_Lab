package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigDecodeResult
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NPatchRemoteGatewayPolicyTest {
    fun `transient unavailable connection retries once and can recover`() {
        var calls = 0
        assertEquals("ready", NPatchRemoteGateway.connectWithRetry({ true }) {
            if (++calls == 1) throw NPatchRemoteGateway.ManagerUnavailableException()
            "ready"
        })
        assertEquals(2, calls)
    }

    fun `unavailable connection stops after two attempts`() {
        var calls = 0
        assertThrows(NPatchRemoteGateway.ManagerUnavailableException::class.java) {
            NPatchRemoteGateway.connectWithRetry({ true }) {
                calls++
                throw NPatchRemoteGateway.ManagerUnavailableException()
            }
        }
        assertEquals(2, calls)
    }

    fun `explicit identity denial is not retried`() {
        var calls = 0
        assertThrows(SecurityException::class.java) {
            NPatchRemoteGateway.connectWithRetry({ true }) { calls++; throw SecurityException() }
        }
        assertEquals(1, calls)
    }

    fun `superseded intent prevents the retry`() {
        var current = true
        var calls = 0
        assertNull(NPatchRemoteGateway.connectWithRetry({ current }) {
            calls++; current = false
            throw NPatchRemoteGateway.ManagerUnavailableException()
        })
        assertEquals(1, calls)
    }

    fun `only the API 102 service descriptor is accepted`() {
        assertTrue(
            NPatchRemoteGateway.acceptsServiceDescriptor(
                "io.github.libxposed.service.IXposedService"
            )
        )
        assertFalse(NPatchRemoteGateway.acceptsServiceDescriptor(null))
        assertFalse(
            NPatchRemoteGateway.acceptsServiceDescriptor(
                "io.github.libxposed.service.ILegacyXposedService"
            )
        )
    }

    /**
     * 手写 Parcel 路径唯一没有类型保护的地方就是这两个交易码。
     *
     * 这里只钉"取值不会被人顺手改掉"。防上游改号的自动检测在
     * [IXposedServiceTransactionCodesTest]：它直接解析类路径上 `IXposedService$Stub.class` 的
     * 字节码常量（JVM 单测加载不了继承 `android.os.Binder` 的类），码值或方法集合变化即失败。
     */
    fun `remote preference transaction codes stay pinned to the libxposed AIDL`() {
        assertEquals(21, NPatchRemoteGateway.TRANSACTION_REQUEST_REMOTE_PREFERENCES)
        assertEquals(22, NPatchRemoteGateway.TRANSACTION_UPDATE_REMOTE_PREFERENCES)
        assertEquals(
            NPatchRemoteGateway.TRANSACTION_REQUEST_REMOTE_PREFERENCES + 1,
            NPatchRemoteGateway.TRANSACTION_UPDATE_REMOTE_PREFERENCES
        )
    }

    fun `connection circuit stays open only before its monotonic deadline`() {
        assertFalse(NPatchRemoteGateway.isConnectionCircuitOpen(100L, 0L))
        assertTrue(NPatchRemoteGateway.isConnectionCircuitOpen(100L, 101L))
        assertFalse(NPatchRemoteGateway.isConnectionCircuitOpen(100L, 100L))
        assertFalse(NPatchRemoteGateway.isConnectionCircuitOpen(101L, 100L))
    }

    fun `NPatch document reuses the canonical values and snapshot revisions`() {
        val snapshot = NoRootConfigSnapshot(
            schemaVersion = NoRootConfigSnapshotCodec.CURRENT_SCHEMA_VERSION,
            catalogVersion = SettingsCatalog.CATALOG_VERSION,
            modulePackage = BuildConfig.APPLICATION_ID,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
            revision = 21L,
            adapterResetRevision = 34L,
            enabled = true,
            values = mapOf(HookEntry.PREF_FREE_COPY_ENABLED to false)
        )

        val resolved = NPatchRemoteGateway.resolveRemoteValues(
            snapshot,
            mapOf(
                HookEntry.PREF_FREE_COPY_ENABLED to true,
                RemoteHookConfigContract.KEY_FREE_COPY_CONFIG_REVISION to 55L,
                RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP to 1L,
                "private_token" to "must-not-leak"
            )
        )

        assertEquals(RemoteHookConfigContract.hookValueKeys, resolved.keys)
        assertEquals(false, resolved[HookEntry.PREF_FREE_COPY_ENABLED])
        assertEquals(55L, resolved[RemoteHookConfigContract.KEY_FREE_COPY_CONFIG_REVISION])
        assertEquals(34L, resolved[RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP])
        assertFalse("private_token" in resolved)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (NPatchRemoteGatewayPolicyTest) -> Unit) {
            val s = NPatchRemoteGatewayPolicyTest()
            block(s)
        }
        runIsolated { s -> s.`transient unavailable connection retries once and can recover`() }
        runIsolated { s -> s.`unavailable connection stops after two attempts`() }
        runIsolated { s -> s.`explicit identity denial is not retried`() }
        runIsolated { s -> s.`superseded intent prevents the retry`() }
        runIsolated { s -> s.`only the API 102 service descriptor is accepted`() }
        runIsolated { s -> s.`remote preference transaction codes stay pinned to the libxposed AIDL`() }
        runIsolated { s -> s.`connection circuit stays open only before its monotonic deadline`() }
        runIsolated { s -> s.`NPatch document reuses the canonical values and snapshot revisions`() }
    
        NPatchRemotePublishCases().verifyAllCases()
        NPatchRuntimeProfileCases().verifyAllCases()
        NPatchScopeProbeCases().verifyAllCases()
        NPatchStartupEvidenceCases().verifyAllCases()
        NPatchStartupHistoryPolicyCases().verifyAllCases()
    }
}

private class NPatchRemotePublishCases {
    private val decision = UserTermsDecision.ACCEPTED
    private val group = RemoteHookConfigContract.GROUP

    private fun snapshot(revision: Long = 21L, enabled: Boolean = true) = NoRootConfigSnapshot(
        schemaVersion = NoRootConfigSnapshotCodec.CURRENT_SCHEMA_VERSION,
        catalogVersion = SettingsCatalog.CATALOG_VERSION,
        modulePackage = BuildConfig.APPLICATION_ID,
        moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
        revision = revision,
        adapterResetRevision = 34L,
        enabled = enabled,
        values = emptyMap()
    )

    private val values: Map<String, Any> = NPatchRemoteGateway.resolveRemoteValues(snapshot(), emptyMap<String, Any>())

    private fun document(generation: Long, revision: Long = 5L, enabled: Boolean = true): Map<String, Any> =
        RemoteHookConfigContract.encode(
            generation = generation,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
            deliveryEnabled = enabled,
            noRootRevision = revision,
            decision = decision,
            values = values
        )

    private fun publish(
        model: UpstreamStoreModel,
        snapshot: NoRootConfigSnapshot = snapshot(),
        clock: () -> Long = { 1_000L },
        stillCurrent: () -> Boolean = { true }
    ) = NPatchRemoteGateway.publishRemotePreferences(
        channel = model,
        snapshot = snapshot,
        decision = decision,
        values = values,
        clockMillis = clock,
        stillCurrent = stillCurrent
    )

    fun `replacing an old document never exposes an empty or partial document`() {
        val stale = document(generation = 100L, revision = 5L) + ("legacy_removed_key" to "x")
        assertTrue(RemoteHookConfigContract.decode(stale) is RemoteHookConfigDecodeResult.Invalid)
        val model = UpstreamStoreModel(stale)

        assertEquals(NPatchRemoteGateway.SyncResult.Success, publish(model))

        assertTrue("reader must only ever see the old or the new document", model.observed.none { it.isEmpty() })
        assertEquals(listOf(stale.keys, model.store.keys), model.observed.map { it.keys })
        assertFalse("legacy_removed_key" in model.store)
        val ready = RemoteHookConfigContract.decode(model.store) as RemoteHookConfigDecodeResult.Ready
        assertEquals(21L, ready.snapshot.noRootRevision)
        assertEquals(values, ready.snapshot.values)
    }

    fun `the first publication into an empty store is a single visible transition`() {
        val model = UpstreamStoreModel(emptyMap())
        assertEquals(NPatchRemoteGateway.SyncResult.Success, publish(model))
        assertEquals(2, model.observed.size)
        assertTrue(RemoteHookConfigContract.decode(model.store) is RemoteHookConfigDecodeResult.Ready)
    }

    fun `upstream clear is observable as an empty document which is why publication avoids it`() {
        val model = UpstreamStoreModel(document(generation = 100L))
        model.update(group, RemotePreferenceDiff(clear = true, delete = emptySet(), put = document(200L)))
        assertTrue(
            "documents the upstream clear-then-transaction window",
            model.observed.any { it.isEmpty() }
        )
        assertTrue(RemoteHookConfigContract.decode(emptyMap<String, Any>()) is RemoteHookConfigDecodeResult.Invalid)
    }

    fun `diff never clears and deletes exactly the obsolete keys`() {
        val random = Random(20261111)
        repeat(300) {
            val pool = (0 until 12).map { "k$it" }
            val current = pool.filter { random.nextBoolean() }.associateWith<String, Any> { "old-$it" }
            val target = pool.filter { random.nextBoolean() }.associateWith<String, Any> { "new-$it" }

            val diff = buildAtomicPublishDiff(current.keys, target)
            assertFalse(diff.clear)
            assertEquals(current.keys - target.keys, diff.delete)
            assertEquals(target, diff.put)

            val model = UpstreamStoreModel(current)
            model.update(group, diff)
            assertEquals(target, model.store)
            assertTrue(model.observed.size <= 2)
        }
    }

    fun `an already current document is not rewritten`() {
        val model = UpstreamStoreModel(emptyMap())
        assertEquals(NPatchRemoteGateway.SyncResult.Success, publish(model))
        val generation = model.store[RemoteHookConfigContract.KEY_GENERATION]

        assertEquals(NPatchRemoteGateway.SyncResult.Success, publish(model, clock = { 9_999L }))

        assertEquals(1, model.updates)
        assertEquals(generation, model.store[RemoteHookConfigContract.KEY_GENERATION])
    }

    fun `a superseded intent before the write performs no update`() {
        val model = UpstreamStoreModel(emptyMap())
        assertNull(publish(model, stillCurrent = { false }))
        assertEquals(0, model.updates)
        assertTrue(model.store.isEmpty())
    }

    fun `a superseded intent after the write skips the read-back`() {
        val model = UpstreamStoreModel(emptyMap())
        var calls = 0
        assertNull(publish(model, stillCurrent = { ++calls == 1 }))
        assertEquals(1, model.updates)
        assertEquals("only the initial snapshot was requested", 1, model.requests)
    }

    fun `generation stays above the previous document even when the clock is behind`() {
        val previous = 9_000_000_000_000L
        val model = UpstreamStoreModel(document(generation = previous, revision = 5L))
        assertEquals(NPatchRemoteGateway.SyncResult.Success, publish(model, clock = { 1L }))
        assertEquals(previous + 1L, model.store[RemoteHookConfigContract.KEY_GENERATION])
    }

    fun `a competing publisher between write and read-back is reported as a mismatch`() {
        val model = UpstreamStoreModel(emptyMap())
        model.afterUpdate = { store -> document(generation = 5_000_000L, revision = 99L).also { store.putAll(it) } }
        val error = assertThrows(IllegalStateException::class.java) { publish(model) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("content_mismatch"))
    }

    fun `a corrupted read-back is reported with the decode reason`() {
        val model = UpstreamStoreModel(emptyMap())
        model.afterUpdate = { store -> store[RemoteHookConfigContract.KEY_DIGEST] = "0".repeat(64) }
        val error = assertThrows(IllegalStateException::class.java) { publish(model) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("digest"))
    }

    fun `disabling delivery still publishes a complete document`() {
        val model = UpstreamStoreModel(document(generation = 10L, enabled = true))
        assertEquals(NPatchRemoteGateway.SyncResult.Success, publish(model, snapshot(revision = 22L, enabled = false)))
        val ready = RemoteHookConfigContract.decode(model.store) as RemoteHookConfigDecodeResult.Ready
        assertFalse(ready.snapshot.deliveryEnabled)
        assertEquals(22L, ready.snapshot.noRootRevision)
    }

    /**
     * 上游 `NPatchRemoteStore.updatePreferences` 的可观察语义：
     * `clear` 立即提交一次（读者可见空文档）；`delete` + `put` 一次提交。
     */
    private class UpstreamStoreModel(initial: Map<String, Any>) : RemotePreferenceChannel {
        var store: MutableMap<String, Any> = LinkedHashMap(initial)
        val observed = mutableListOf<Map<String, Any>>(LinkedHashMap(initial))
        var updates = 0
        var requests = 0

        /** 模拟写入与读回之间的第三方改动。 */
        var afterUpdate: ((MutableMap<String, Any>) -> Unit)? = null

        override fun request(group: String): Map<String, Any> {
            requests++
            return LinkedHashMap(store)
        }

        override fun update(group: String, diff: RemotePreferenceDiff) {
            updates++
            if (diff.clear) {
                store = LinkedHashMap()
                observed += LinkedHashMap(store)
            }
            val next = LinkedHashMap(store)
            diff.delete.forEach { next.remove(it) }
            next.putAll(diff.put)
            store = next
            observed += LinkedHashMap(store)
            afterUpdate?.invoke(store)
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (NPatchRemotePublishCases) -> Unit) {
            val s = NPatchRemotePublishCases()
            block(s)
        }
        runIsolated { s -> s.`replacing an old document never exposes an empty or partial document`() }
        runIsolated { s -> s.`the first publication into an empty store is a single visible transition`() }
        runIsolated { s -> s.`upstream clear is observable as an empty document which is why publication avoids it`() }
        runIsolated { s -> s.`diff never clears and deletes exactly the obsolete keys`() }
        runIsolated { s -> s.`an already current document is not rewritten`() }
        runIsolated { s -> s.`a superseded intent before the write performs no update`() }
        runIsolated { s -> s.`a superseded intent after the write skips the read-back`() }
        runIsolated { s -> s.`generation stays above the previous document even when the clock is behind`() }
        runIsolated { s -> s.`a competing publisher between write and read-back is reported as a mismatch`() }
        runIsolated { s -> s.`a corrupted read-back is reported with the decode reason`() }
        runIsolated { s -> s.`disabling delivery still publishes a complete document`() }
    }
}

private class NPatchRuntimeProfileCases {
    fun readsManagerAndEmbeddedWithoutDependingOnManagerInstallation() {
        val manager = NPatchRuntimeProfiles.parseJson("""{"useManager":true,"sigBypassLevel":2,"debuggable":false,"lspConfig":{"API_CODE":102,"VERSION_CODE":833}}""")
        assertEquals(NPatchMode.MANAGER, manager.mode)
        assertEquals(2, manager.signatureLevel)
        assertEquals(false, manager.debuggable)
        assertEquals(102, manager.loaderApi)
        assertEquals(833L, manager.loaderVersionCode)
        assertTrue(manager.metadataValid)
        assertEquals(NPatchMode.EMBEDDED, NPatchRuntimeProfiles.parseJson("""{"useManager":false}""").mode)
    }

    fun absentMalformedAndWrongTypedModesStayUnknown() {
        for (raw in listOf("", "{", "[]", "{}", """{"useManager":"false"}""", """{"useManager":0}""")) {
            val profile = NPatchRuntimeProfiles.parseJson(raw)
            assertEquals(raw, NPatchMode.UNKNOWN, profile.mode)
            assertFalse(raw, profile.metadataValid)
        }
    }

    fun corruptOptionalFieldsDoNotBecomeCapabilities() {
        val profile = NPatchRuntimeProfiles.parseJson("""{"useManager":true,"sigBypassLevel":"2","debuggable":1,"lspConfig":{"API_CODE":102.5,"VERSION_CODE":-1}}""")
        assertEquals(NPatchMode.MANAGER, profile.mode)
        assertNull(profile.signatureLevel)
        assertNull(profile.debuggable)
        assertNull(profile.loaderApi)
        assertNull(profile.loaderVersionCode)
        assertNull(NPatchRuntimeProfiles.parseJson("""{"useManager":false,"sigBypassLevel":6}""").signatureLevel)
        assertFalse(NPatchRuntimeProfiles.parseJson(" ".repeat(65537)).metadataValid)
    }

    fun versionMismatchRequiresManagerModeAndTwoKnownVersions() {
        val patch = NPatchRuntimeProfile(mode = NPatchMode.MANAGER, loaderVersionCode = 833)
        assertTrue(NPatchEnvironment(patch, 846, "test").versionMismatch)
        assertFalse(NPatchEnvironment(patch, 833, "test").versionMismatch)
        assertFalse(NPatchEnvironment(patch, null, null).versionMismatch)
        assertFalse(NPatchEnvironment(patch.copy(mode = NPatchMode.EMBEDDED), 846, "test").versionMismatch)
        assertFalse(NPatchEnvironment(patch.copy(loaderVersionCode = null), 846, "test").versionMismatch)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (NPatchRuntimeProfileCases) -> Unit) {
            val s = NPatchRuntimeProfileCases()
            block(s)
        }
        runIsolated { s -> s.readsManagerAndEmbeddedWithoutDependingOnManagerInstallation() }
        runIsolated { s -> s.absentMalformedAndWrongTypedModesStayUnknown() }
        runIsolated { s -> s.corruptOptionalFieldsDoNotBecomeCapabilities() }
        runIsolated { s -> s.versionMismatchRequiresManagerModeAndTwoKnownVersions() }
    }
}

private class NPatchScopeProbeCases {
    fun `embedded scope ignores a stale manager denial without claiming activation`() {
        for (scope in NPatchScopeProbe.ScopeState.entries) {
            assertEquals(NPatchScopeProbe.ScopeState.UNKNOWN, NPatchScopeProbe.effectiveScope(NPatchMode.EMBEDDED, scope))
            assertEquals(scope, NPatchScopeProbe.effectiveScope(NPatchMode.MANAGER, scope))
            assertEquals(scope, NPatchScopeProbe.effectiveScope(NPatchMode.UNKNOWN, scope))
        }
    }

    fun `scope classification only trusts explicit module rows`() {
        val module = "com.Bilibili_Innocent_Lab.xposedmodule"

        assertEquals(
            NPatchScopeProbe.ScopeState.IN_SCOPE,
            NPatchScopeProbe.classify(listOf("com.example.other", module), module)
        )
        assertEquals(
            NPatchScopeProbe.ScopeState.OUT_OF_SCOPE,
            NPatchScopeProbe.classify(emptyList(), module)
        )
        assertEquals(
            NPatchScopeProbe.ScopeState.OUT_OF_SCOPE,
            NPatchScopeProbe.classify(listOf("com.example.other"), module)
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (NPatchScopeProbeCases) -> Unit) {
            val s = NPatchScopeProbeCases()
            block(s)
        }
        runIsolated { s -> s.`embedded scope ignores a stale manager denial without claiming activation`() }
        runIsolated { s -> s.`scope classification only trusts explicit module rows`() }
    }
}

private class NPatchStartupEvidenceCases {
    private fun sample(index: Int) = NPatchStartupEvidence("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
        index.toLong(), 1, 10, 20, 30, "granted", "provider", listOf(NPatchRouteEvidence("provider", "granted")))

    fun codecRejectsUnknownFieldsTypesRoutesAndUnboundedNumbers() {
        val encoded = NPatchStartupCodec.encode(listOf(sample(1)))
        assertEquals(listOf(sample(1)), NPatchStartupCodec.decode(encoded))
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("token", "secret") }, { it.put("admissionMs", "10") }, { it.put("attempt", 4294967297L) },
            { it.put("totalMs", -1) }, { it.put("selected", "https://secret") }, { it.put("installMs", 3.5) },
            { it.put("result", "secret") }, { it.put("session", "private-path") }
        )
        mutations.forEach { mutate ->
            val row = JSONObject(encoded.getJSONObject(0).toString()).apply(mutate)
            assertThrows(Exception::class.java) { NPatchStartupCodec.decode(JSONArray().put(row)) }
        }
    }

    fun boundedHistoryDeduplicatesBootAndKeepsLastAttempt() {
        val old = (1..8).map(::sample)
        val current = sample(8).copy(attempt = 2, admissionMs = 40)
        val merged = NPatchStartupCodec.merge(old, listOf(current, sample(9)))
        assertEquals(8, merged.size)
        assertEquals(2L, merged.first().startedAt)
        assertEquals(current, merged[6])
        assertEquals(8, NPatchStartupCodec.merge(merged, listOf(current)).size)
        assertEquals(4L, NPatchStartupCodec.p50((1L..8L).toList()))
        assertNull(NPatchStartupCodec.p50(emptyList()))
    }

    fun trackerPreservesRetryAndSeparatesUnknownInstallFromZero() {
        val tracker = NPatchStartupTracker()
        assertTrue(tracker.snapshot().isEmpty())
        tracker.admission(10, "timeout", "unknown", emptyList())
        assertNull(tracker.snapshot().single().installMs)
        tracker.admission(5, "granted", "local", listOf(NPatchRouteEvidence("local", "granted")))
        tracker.installation(0)
        val sample = tracker.snapshot().single()
        assertEquals(2, sample.attempt)
        assertEquals(0L, sample.installMs)
        assertEquals(5L, sample.totalMs)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (NPatchStartupEvidenceCases) -> Unit) {
            val s = NPatchStartupEvidenceCases()
            block(s)
        }
        runIsolated { s -> s.codecRejectsUnknownFieldsTypesRoutesAndUnboundedNumbers() }
        runIsolated { s -> s.boundedHistoryDeduplicatesBootAndKeepsLastAttempt() }
        runIsolated { s -> s.trackerPreservesRetryAndSeparatesUnknownInstallFromZero() }
    }
}

private class NPatchStartupHistoryPolicyCases {
    fun absentProcessHistorySurvivesWithoutBecomingCurrentEvidence() {
        val main = row("tv.danmaku.bili", 1)
        val web = row("tv.danmaku.bili:web", 2)
        val first = NPatchStartupHistoryPolicy.merge(emptyList(), listOf(main, web))
        val quiet = NPatchStartupHistoryPolicy.merge(first.persisted, listOf(main))
        assertEquals(listOf(main), quiet.current)
        assertEquals(2, quiet.persisted.size)
        val nextWeb = web.copy(pid = 99, startups = listOf(sample(3)))
        val next = NPatchStartupHistoryPolicy.merge(quiet.persisted, listOf(main, nextWeb))
        assertEquals(listOf(sample(2), sample(3)), next.current.single { it.processName == web.processName }.startups)
        assertEquals(99, next.current.single { it.processName == web.processName }.pid)
    }

    fun hostModuleAndCarrierChangesDiscardAllPreviousProcessHistory() {
        val main = row("tv.danmaku.bili", 1)
        val web = row("tv.danmaku.bili:web", 2)
        listOf(main.copy(targetUpdateTime = 2), main.copy(moduleVersionCode = 23), main.copy(mode = NPatchMode.EMBEDDED)).forEach { fresh ->
            val update = NPatchStartupHistoryPolicy.merge(listOf(main, web), listOf(fresh))
            assertEquals(listOf(fresh), update.current)
            assertEquals(listOf(fresh), update.persisted)
        }
    }

    fun processAndPerProcessHistoryRemainBoundedWithFreshRowsPrioritized() {
        val old = (1..16).map { row("tv.danmaku.bili:p$it", it) }
        val main = row("tv.danmaku.bili", 17)
        val update = NPatchStartupHistoryPolicy.merge(old, listOf(main))
        assertEquals(16, update.persisted.size)
        assertEquals(main, update.persisted.first())
        assertFalse(update.persisted.any { it.processName == "tv.danmaku.bili:p1" })
        val previous = main.copy(startups = (1..8).map(::sample))
        val next = NPatchStartupHistoryPolicy.merge(listOf(previous), listOf(main.copy(startups = listOf(sample(9)))))
        assertEquals((2..9).map(::sample), next.current.single().startups)
        assertNotNull(NPatchProcessCodec.decode(NPatchProcessCodec.encode(update.persisted)))
    }

    fun inconsistentFreshInstallationAndDuplicateProcessesAreRejected() {
        val main = row("tv.danmaku.bili", 1)
        assertThrows(IllegalArgumentException::class.java) {
            NPatchStartupHistoryPolicy.merge(emptyList(), listOf(main, main))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NPatchStartupHistoryPolicy.merge(emptyList(), listOf(main, row("tv.danmaku.bili:web", 2).copy(moduleVersionCode = 23)))
        }
    }

    private fun row(name: String, boot: Int) = NPatchProcessSnapshot(name, boot, NPatchMode.MANAGER,
        NPatchProcessState.INSTALLED, "module_direct", 10, 1, 22, startups = listOf(sample(boot)))
    private fun sample(index: Int) = NPatchStartupEvidence("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
        index.toLong(), 1, 10, 20, 30, "granted", "provider", listOf(NPatchRouteEvidence("provider", "granted")))

    fun verifyAllCases() {
        fun runIsolated(block: (NPatchStartupHistoryPolicyCases) -> Unit) {
            val s = NPatchStartupHistoryPolicyCases()
            block(s)
        }
        runIsolated { s -> s.absentProcessHistorySurvivesWithoutBecomingCurrentEvidence() }
        runIsolated { s -> s.hostModuleAndCarrierChangesDiscardAllPreviousProcessHistory() }
        runIsolated { s -> s.processAndPerProcessHistoryRemainBoundedWithFreshRowsPrioritized() }
        runIsolated { s -> s.inconsistentFreshInstallationAndDuplicateProcessesAreRejected() }
    }
}
