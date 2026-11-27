package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostReceiptWire
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import io.github.libxposed.service.XposedService
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameworkServiceCallTest {
    private val ready = ModernFrameworkStatus(true, true, "LSPosed", 102, connectionId = 7)

    fun missingServiceAndMissingCapabilityDoNotExecuteTheOperation() {
        assertEquals(FrameworkServiceCall.Failure(FrameworkServiceCall.Reason.NOT_CONNECTED),
            callFrameworkService<Any, Unit>(null, ready, { true }, {}) { error("must not execute") })
        assertEquals(FrameworkServiceCall.Failure(FrameworkServiceCall.Reason.UNSUPPORTED),
            callFrameworkService(Any(), ready.copy(capable = false, properties = 0L), { true }, {}) { error("must not execute") })
    }

    fun unknownRoutingIdentityIsPendingRatherThanUnsupported() {
        val pending = readModernFrameworkCapabilities({ 102 }, { io.github.libxposed.service.XposedService.PROP_CAP_REMOTE })
            .withModernFrameworkIdentity { error("not read yet") }
        assertEquals(FrameworkServiceCall.Failure(FrameworkServiceCall.Reason.METADATA_UNAVAILABLE),
            callFrameworkService(Any(), pending, { true }, {}) { error("must not execute") })
    }

    fun aReplacedConnectionCannotReportTheOldModelAsReady() {
        var current = true
        val result = callFrameworkService(Any(), ready, { current }, {}) { current = false; "ready" }
        assertEquals(FrameworkServiceCall.Failure(FrameworkServiceCall.Reason.CONNECTION_CHANGED), result)
    }

    fun fileErrorsDoNotInvalidateALiveService() {
        var died = false
        val result = callFrameworkService(Any(), ready, { true }, { died = true }) { throw IOException("disk") }
        assertEquals(FrameworkServiceCall.Failure(FrameworkServiceCall.Reason.OPERATION_FAILED), result)
        assertFalse(died)
        assertEquals(FrameworkServiceCall.Success("ready", 7),
            callFrameworkService(Any(), ready, { true }, {}) { "ready" })
    }

    fun serviceDeathClassificationUnwrapsSdkExceptionsButIsBounded() {
        val dead = IOException("dead")
        assertTrue(isFrameworkServiceDeath(RuntimeException(RuntimeException(dead))) { it === dead })
        assertFalse(isFrameworkServiceDeath(IOException("storage")) { it === dead })
        var chain: Throwable = dead
        repeat(10) { chain = RuntimeException(chain) }
        assertFalse(isFrameworkServiceDeath(chain) { it === dead })
    }

    fun capabilitiesCanBePublishedBeforeOptionalMetadataIsRead() {
        val core = readModernFrameworkCapabilities({ 102 }, { io.github.libxposed.service.XposedService.PROP_CAP_REMOTE })
        assertTrue(core.capable)
        assertEquals("", core.name)
        val described = core.withModernFrameworkMetadata({ "LSPosed" }, { error("unavailable") }, { 7930 })
        assertTrue(described.capable)
        assertEquals("LSPosed", described.name)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FrameworkServiceCallTest) -> Unit) {
            val s = FrameworkServiceCallTest()
            block(s)
        }
        runIsolated { s -> s.missingServiceAndMissingCapabilityDoNotExecuteTheOperation() }
        runIsolated { s -> s.unknownRoutingIdentityIsPendingRatherThanUnsupported() }
        runIsolated { s -> s.aReplacedConnectionCannotReportTheOldModelAsReady() }
        runIsolated { s -> s.fileErrorsDoNotInvalidateALiveService() }
        runIsolated { s -> s.serviceDeathClassificationUnwrapsSdkExceptionsButIsBounded() }
        runIsolated { s -> s.capabilitiesCanBePublishedBeforeOptionalMetadataIsRead() }
    
        ModernFrameworkStatusCases().verifyAllCases()
        PublicationQueueCases().aHungPhysicalWriterCannotAccumulateExpiredManualWork()
        RemoteHookConfigCommitterCases().verifyAllCases()
        RemoteHookConfigContractCases().verifyAllCases()
        RemoteHookConfigPublishPolicyCases().`single publisher repeats for dirty settings or a newer terms decision`()
    }
}

private class ModernFrameworkStatusCases {
    fun `framework routing identity precedes optional version metadata`() {
        val core = readModernFrameworkCapabilities({ 102 }, { XposedService.PROP_CAP_REMOTE })
        val named = core.withModernFrameworkIdentity { "LSPatch" }
        assertTrue(named.capable)
        assertTrue(named.isLspatch)
        val versionUnavailable = named.withModernFrameworkVersions({ error("slow or unavailable") }, { 487 })
        assertTrue(versionUnavailable.capable)
        assertTrue(versionUnavailable.isLspatch)
        assertFalse(core.withModernFrameworkIdentity { error("identity unknown") }.capable)
        assertFalse(core.withModernFrameworkIdentity { "" }.capable)
    }
    fun `LSPatch identity is exact presentation metadata and never a capability grant`() {
        assertTrue(isLspatchFrameworkName("LSPatch"))
        assertTrue(isLspatchFrameworkName(" lspatch "))
        assertFalse(isLspatchFrameworkName("LSPosed-LSPatch"))
        assertFalse(isLspatchFrameworkName("Vector"))

        val status = readModernFrameworkStatus(
            { 100 }, { 0L }, { "LSPatch" }, { "1.2" }, { 487L }
        )
        assertTrue(status.isLspatch)
        assertFalse(status.capable)
    }

    fun `Modern API 102 is accepted with remote capability and older APIs stay closed`() {
        val irena = readModernFrameworkStatus(
            { 102 }, { XposedService.PROP_CAP_REMOTE or XposedService.PROP_CAP_SYSTEM },
            { "LSPosed" }, { "2.0.0" }, { 7316L }
        )
        assertTrue(irena.capable)
        assertEquals("LSPosed", irena.name)
        assertEquals(102, irena.apiVersion)
        assertFalse(readModernFrameworkStatus(
            { 101 }, { XposedService.PROP_CAP_REMOTE }, { "LSPosed" }, { "old" }, { 1L }
        ).capable)
        assertFalse(readModernFrameworkStatus({ 102 }, { 0L }, { "Irena" }, { "2.0.0" }, { 1L }).capable)
    }
    fun `Vector build and independent capabilities are retained`() {
        val flags = XposedService.PROP_CAP_REMOTE or XposedService.PROP_CAP_SYSTEM or
            XposedService.PROP_RT_API_PROTECTION
        val status = readModernFrameworkStatus({ 102 }, { flags }, { "Vector" }, { "2.2 (3110) c4a701aa" }, { 3110L })
        assertTrue(status.capable)
        assertEquals(3110L, status.versionCode)
        assertEquals(flags, status.properties)
        assertNull(status.failureCode)
    }

    fun `optional metadata failure does not disable a capable framework`() {
        val status = readModernFrameworkStatus(
            { 102 }, { XposedService.PROP_CAP_REMOTE }, { "LSPosed" }, { error("binder failure") }, { 100L }
        )
        assertTrue(status.connected)
        assertTrue(status.capable)
        assertNull(status.version)
        assertEquals("framework_metadata_unavailable", status.failureCode)
    }

    fun `missing API or capability is never inferred from a framework name`() {
        val missingApi = readModernFrameworkStatus(
            { error("unavailable") }, { XposedService.PROP_CAP_REMOTE }, { "Vector" }, { "2.2" }, { 3080L }
        )
        val missingCapability = readModernFrameworkStatus({ 102 }, { 0L }, { "Vector" }, { "2.2" }, { 3080L })
        assertFalse(missingApi.capable)
        assertFalse(missingCapability.capable)
        assertTrue(missingApi.connected)
    }

    fun `framework metadata remains bounded and failed values remain unavailable`() {
        val status = readModernFrameworkStatus(
            { 102 }, { error("unavailable") }, { "v".repeat(1000) }, { "b".repeat(1000) }, { -1L }
        )
        assertEquals(128, status.name.length)
        assertEquals(128, status.version?.length)
        assertNull(status.properties)
        assertNull(status.versionCode)
        assertFalse(status.capable)
    }

    fun `only the NPatch no-root channel is gated by the no-root switch`() {
        val remoteOnly = XposedService.PROP_CAP_REMOTE
        val rootFlags = XposedService.PROP_CAP_REMOTE or XposedService.PROP_CAP_SYSTEM

        assertTrue(isNpatchConfigChannel(
            readModernFrameworkStatus({ 102 }, { remoteOnly }, { "NPatch" }, { "1.0.9" }, { 833L })
        ))
        // 改名后仍靠能力位识别：没有 CAP_SYSTEM 且不是 LSPatch 的实现同样算免 Root 通道。
        assertTrue(isNpatchConfigChannel(
            readModernFrameworkStatus({ 102 }, { remoteOnly }, { "RenamedPatch" }, { "1.0" }, { 1L })
        ))
        // 真 Root 框架与 LSPatch 的标准发布不受免 Root 开关影响。
        assertFalse(isNpatchConfigChannel(
            readModernFrameworkStatus({ 102 }, { rootFlags }, { "LSPosed" }, { "2.0" }, { 7316L })
        ))
        assertFalse(isNpatchConfigChannel(
            readModernFrameworkStatus({ 102 }, { remoteOnly }, { "LSPatch" }, { "1.0" }, { 487L })
        ))
        // 元数据未就绪时绝不判定，宁可退回文档判据也不能关掉 LSPosed 的发布。
        assertFalse(isNpatchConfigChannel(ModernFrameworkStatus(
            connected = false, capable = false, name = "", apiVersion = 0
        )))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ModernFrameworkStatusCases) -> Unit) {
            val s = ModernFrameworkStatusCases()
            block(s)
        }
        runIsolated { s -> s.`framework routing identity precedes optional version metadata`() }
        runIsolated { s -> s.`LSPatch identity is exact presentation metadata and never a capability grant`() }
        runIsolated { s -> s.`Modern API 102 is accepted with remote capability and older APIs stay closed`() }
        runIsolated { s -> s.`Vector build and independent capabilities are retained`() }
        runIsolated { s -> s.`optional metadata failure does not disable a capable framework`() }
        runIsolated { s -> s.`missing API or capability is never inferred from a framework name`() }
        runIsolated { s -> s.`framework metadata remains bounded and failed values remain unavailable`() }
        runIsolated { s -> s.`only the NPatch no-root channel is gated by the no-root switch`() }
    }
}

private class PublicationQueueCases {
    fun aHungPhysicalWriterCannotAccumulateExpiredManualWork() {
        val queue=HostReceiptWire.executor("test-writer")
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            queue.execute { entered.countDown(); release.await() }
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            var writes=0
            repeat(100) { i ->
                val attempt=RemotePublicationAttempt(i.toLong()+1,1,1,100,{1},{})
                val work=Runnable { if (attempt.isActive()) writes++ }
                attempt.onCancel { queue.remove(work) };queue.execute(work);attempt.cancel()
                assertEquals(0,queue.queue.size)
            }
            repeat(8) { queue.execute {} }
            try { queue.execute {};fail("must reject excess work") } catch (_: RejectedExecutionException) { }
            assertEquals(8,queue.queue.size);assertEquals(0,writes);assertEquals(1,queue.poolSize)
        } finally { release.countDown();queue.shutdown();assertTrue(queue.awaitTermination(2,TimeUnit.SECONDS)) }
    }
}

private class RemoteHookConfigCommitterCases {
    fun disabledNpatchRevisionSurvivesStandardPublisherCleanup() {
        fun publishDisabled(revision: Long) = committer.publish(1L, BuildConfig.VERSION_CODE.toLong(),
            UserTermsDecision.ACCEPTED, values, 100L, backend, deliveryEnabled = false, noRootRevision = revision)
        assertTrue(publishDisabled(42L).succeeded)
        assertTrue(publishDisabled(42L).succeeded)
        assertEquals(1, backend.commits)
        assertTrue(publishDisabled(43L).succeeded)
        assertEquals(2, backend.commits)
        val snapshot = (RemoteHookConfigContract.decode(backend.remote) as RemoteHookConfigDecodeResult.Ready).snapshot
        assertEquals(43L, snapshot.noRootRevision)
        assertFalse(snapshot.authorized)
    }

    private val values = RemoteHookConfigContract.resolveSourceValues(emptyMap<String, Any>())
    private val committer = RemoteHookConfigCommitter()
    private val backend = CachedBackend()

    fun manualRefreshCannotUseTheAcknowledgedCache() {
        assertTrue(publish().succeeded)
        assertTrue(committer.publish(1L, BuildConfig.VERSION_CODE.toLong(), UserTermsDecision.ACCEPTED,
            values, 100L, backend, force = true).succeeded)
        assertEquals(2,backend.commits)
    }
    fun cancelledPublicationCannotCommitOrAcknowledge() {
        val result=committer.publish(1L,BuildConfig.VERSION_CODE.toLong(),UserTermsDecision.ACCEPTED,
            values,100L,backend,stillCurrent={false})
        assertFalse(result.succeeded); assertEquals(0,backend.commits)
    }

    fun aDisabledDeliveryMustNotProduceAnAuthorizingDocument() {
        assertTrue(publish(deliveryEnabled = false).succeeded)
        val disabled = RemoteHookConfigContract.decode(backend.remote) as RemoteHookConfigDecodeResult.Ready
        assertFalse(disabled.snapshot.deliveryEnabled)
        assertFalse(disabled.snapshot.authorized)

        // 同一份不可授权文档去重，不重复提交。
        val repeated = committer.publish(1L, BuildConfig.VERSION_CODE.toLong(), UserTermsDecision.ACCEPTED,
            values, 200L, backend, deliveryEnabled = false)
        assertTrue(repeated.succeeded)
        assertEquals(1, backend.commits)
    }

    private fun publish(
        decision: UserTermsDecision = UserTermsDecision.ACCEPTED,
        connectionId: Long = 1L,
        now: Long = 100L,
        deliveryEnabled: Boolean = true
    ) = committer.publish(
        connectionId, BuildConfig.VERSION_CODE.toLong(), decision, values, now, backend,
        deliveryEnabled = deliveryEnabled
    )

    fun `failed commit changes cache but retry must send and reach the server`() {
        backend.failCommit = true
        assertFalse(publish().succeeded)
        assertTrue(backend.cached.isNotEmpty())
        assertTrue(backend.remote.isEmpty())

        backend.failCommit = false
        assertTrue(publish().succeeded)
        assertEquals(2, backend.commits)
        assertEquals(backend.cached, backend.remote)
    }

    fun `failed decline cannot turn into a cached success on retry`() {
        assertTrue(publish().succeeded)
        backend.failCommit = true
        assertFalse(publish(UserTermsDecision.DECLINED).succeeded)
        assertFalse(publish(UserTermsDecision.DECLINED).succeeded)
        assertEquals(3, backend.commits)
        val remote = RemoteHookConfigContract.decode(backend.remote) as RemoteHookConfigDecodeResult.Ready
        assertEquals(UserTermsDecision.ACCEPTED, remote.snapshot.decision)

        backend.failCommit = false
        assertTrue(publish(UserTermsDecision.DECLINED).succeeded)
        val denied = RemoteHookConfigContract.decode(backend.remote) as RemoteHookConfigDecodeResult.Ready
        assertFalse(denied.snapshot.authorized)
    }

    fun `only an acknowledged snapshot on the same connection can skip IPC`() {
        val first = publish() as RemoteHookConfigPublishResult.Success
        val repeated = publish() as RemoteHookConfigPublishResult.Success
        assertFalse(repeated.changed)
        assertEquals(first.generation, repeated.generation)
        assertEquals(1, backend.commits)

        val reconnected = publish(connectionId = 2L) as RemoteHookConfigPublishResult.Success
        assertTrue(reconnected.changed)
        assertEquals(first.generation, reconnected.generation)
        assertEquals(2, backend.commits)
    }

    fun `a prepopulated client cache cannot acknowledge a new publisher`() {
        publish()
        val fresh = RemoteHookConfigCommitter()
        val result = fresh.publish(
            1L, BuildConfig.VERSION_CODE.toLong(), UserTermsDecision.ACCEPTED, values, 100L, backend
        ) as RemoteHookConfigPublishResult.Success
        assertTrue(result.changed)
        assertEquals(2, backend.commits)
    }

    fun `service death invalidates the previous acknowledgement`() {
        publish()
        committer.invalidate()
        assertTrue((publish() as RemoteHookConfigPublishResult.Success).changed)
        assertEquals(2, backend.commits)
    }

    fun `client cache corruption after commit fails validation and is retried`() {
        backend.corruptAfterCommit = true
        assertFalse(publish().succeeded)
        backend.corruptAfterCommit = false
        assertTrue(publish().succeeded)
        assertEquals(2, backend.commits)
    }

    fun `an exception also invalidates the previous acknowledgement`() {
        publish()
        backend.failRead = true
        assertFalse(publish().succeeded)
        backend.failRead = false
        assertTrue((publish() as RemoteHookConfigPublishResult.Success).changed)
        assertEquals(2, backend.commits)
    }

    fun `changed decision and a backward clock still advance the generation`() {
        val first = publish(now = 500L) as RemoteHookConfigPublishResult.Success
        val next = publish(UserTermsDecision.DECLINED, now = 1L) as RemoteHookConfigPublishResult.Success
        assertEquals(first.generation + 1L, next.generation)
    }

    fun `a changed payload cannot reuse the success of the same decision`() {
        publish()
        val changedValues = values.toMutableMap().apply {
            this[RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP] = 77L
        }
        assertTrue(committer.publish(
            1L, BuildConfig.VERSION_CODE.toLong(), UserTermsDecision.ACCEPTED, changedValues, 101L, backend
        ).succeeded)
        assertEquals(2, backend.commits)
        assertEquals(77L, backend.remote[RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP])
    }

    fun `missing connection and exhausted generation fail before commit`() {
        assertFalse(publish(connectionId = 0L).succeeded)
        backend.cached = mapOf(RemoteHookConfigContract.KEY_GENERATION to Long.MAX_VALUE)
        assertFalse(publish().succeeded)
        assertEquals(0, backend.commits)
    }

    fun `every altered readback fails even when commit returned true`() {
        val mutations: List<(Map<String, Any>) -> Map<String, Any>> = listOf(
            { it - RemoteHookConfigContract.KEY_DIGEST },
            { it + (RemoteHookConfigContract.KEY_READY to false) },
            { it + (RemoteHookConfigContract.KEY_GENERATION to "100") },
            { it + (RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP to 77L) },
            { it + (RemoteHookConfigContract.KEY_DIGEST to "altered") },
            { it + ("unrecognized_key" to true) }
        )
        mutations.forEach { mutate ->
            val writer = RemoteHookConfigCommitter()
            val target = CachedBackend().apply { mutateReadback = mutate }
            val failed = writer.publish(1L, BuildConfig.VERSION_CODE.toLong(),
                UserTermsDecision.ACCEPTED, values, 100L, target)
            assertFalse(failed.succeeded)
            assertEquals(1, target.commits)
            target.mutateReadback = { it }
            val retry = writer.publish(1L, BuildConfig.VERSION_CODE.toLong(),
                UserTermsDecision.ACCEPTED, values, 101L, target)
            assertTrue(retry.succeeded)
            assertEquals(2, target.commits)
        }
    }

    fun `an invalid source or foreign module version cannot reach the remote commit`() {
        val invalidSources = listOf(
            values - RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP,
            values + (RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP to "wrong-type")
        )
        invalidSources.forEach { source ->
            val result = committer.publish(1L, BuildConfig.VERSION_CODE.toLong(),
                UserTermsDecision.ACCEPTED, source, 100L, backend)
            assertFalse(result.succeeded)
        }
        assertFalse(committer.publish(1L, BuildConfig.VERSION_CODE.toLong() + 1L,
            UserTermsDecision.ACCEPTED, values, 100L, backend).succeeded)
        assertEquals(0, backend.commits)
    }

    /** Reproduces Service 102's cache-before-IPC behavior, including its failure case. */
    private class CachedBackend : RemoteHookConfigBackend {
        var cached: Map<String, Any> = emptyMap()
        var remote: Map<String, Any> = emptyMap()
        var commits = 0
        var failCommit = false
        var failRead = false
        var corruptAfterCommit = false
        var mutateReadback: (Map<String, Any>) -> Map<String, Any> = { it }
        val removals = mutableListOf<Set<String>>()

        override fun readCached(): Map<String, *> {
            check(!failRead) { "simulated read failure" }
            return cached.toMap()
        }

        override fun commit(document: Map<String, Any>, removedKeys: Set<String>): Boolean {
            commits += 1
            removals += removedKeys.toSet()
            cached = (cached - removedKeys) + document
            if (failCommit) return false
            remote = (remote - removedKeys) + document
            if (corruptAfterCommit) cached = cached - RemoteHookConfigContract.KEY_DIGEST
            cached = mutateReadback(cached)
            return true
        }
    }

    fun `Irena style store deletes obsolete keys without clear support`() {
        backend.cached = mapOf("obsolete_setting" to "old", "obsolete_schema_key" to true)
        backend.remote = backend.cached
        assertTrue(publish().succeeded)
        assertEquals(setOf("obsolete_setting", "obsolete_schema_key"), backend.removals.single())
        assertEquals(RemoteHookConfigContract.persistedKeys, backend.remote.keys)
        assertEquals(backend.cached, backend.remote)
    }

    fun `failed cleanup is repeated even after the SDK cache forgot the obsolete key`() {
        backend.cached = mapOf("obsolete_setting" to "old")
        backend.remote = backend.cached
        backend.failCommit = true
        assertFalse(publish().succeeded)
        assertFalse(backend.cached.containsKey("obsolete_setting"))
        assertTrue(backend.remote.containsKey("obsolete_setting"))
        backend.failCommit = false
        assertTrue(publish().succeeded)
        assertEquals(listOf(setOf("obsolete_setting"), setOf("obsolete_setting")), backend.removals)
        assertEquals(RemoteHookConfigContract.persistedKeys, backend.remote.keys)
        assertEquals(backend.cached, backend.remote)
    }

    fun `pending cleanup is scoped to its service connection`() {
        backend.cached = mapOf("old_framework_key" to "old")
        backend.failCommit = true
        assertFalse(publish().succeeded)
        val nextBackend = CachedBackend().apply {
            cached = mapOf("new_framework_key" to "new")
            remote = cached
        }
        assertTrue(committer.publish(
            2L, BuildConfig.VERSION_CODE.toLong(), UserTermsDecision.ACCEPTED, values, 100L, nextBackend
        ).succeeded)
        assertEquals(setOf("new_framework_key"), nextBackend.removals.single())
        assertEquals(RemoteHookConfigContract.persistedKeys, nextBackend.remote.keys)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (RemoteHookConfigCommitterCases) -> Unit) {
            val s = RemoteHookConfigCommitterCases()
            block(s)
        }
        runIsolated { s -> s.disabledNpatchRevisionSurvivesStandardPublisherCleanup() }
        runIsolated { s -> s.manualRefreshCannotUseTheAcknowledgedCache() }
        runIsolated { s -> s.cancelledPublicationCannotCommitOrAcknowledge() }
        runIsolated { s -> s.aDisabledDeliveryMustNotProduceAnAuthorizingDocument() }
        runIsolated { s -> s.`failed commit changes cache but retry must send and reach the server`() }
        runIsolated { s -> s.`failed decline cannot turn into a cached success on retry`() }
        runIsolated { s -> s.`only an acknowledged snapshot on the same connection can skip IPC`() }
        runIsolated { s -> s.`a prepopulated client cache cannot acknowledge a new publisher`() }
        runIsolated { s -> s.`service death invalidates the previous acknowledgement`() }
        runIsolated { s -> s.`client cache corruption after commit fails validation and is retried`() }
        runIsolated { s -> s.`an exception also invalidates the previous acknowledgement`() }
        runIsolated { s -> s.`changed decision and a backward clock still advance the generation`() }
        runIsolated { s -> s.`a changed payload cannot reuse the success of the same decision`() }
        runIsolated { s -> s.`missing connection and exhausted generation fail before commit`() }
        runIsolated { s -> s.`every altered readback fails even when commit returned true`() }
        runIsolated { s -> s.`an invalid source or foreign module version cannot reach the remote commit`() }
        runIsolated { s -> s.`Irena style store deletes obsolete keys without clear support`() }
        runIsolated { s -> s.`failed cleanup is repeated even after the SDK cache forgot the obsolete key`() }
        runIsolated { s -> s.`pending cleanup is scoped to its service connection`() }
    }
}

private class RemoteHookConfigContractCases {

    fun `complete API 102 config round trips all whitelisted values`() {
        val values = RemoteHookConfigContract.resolveSourceValues(
            mapOf(
                HookEntry.PREF_FREE_COPY_ENABLED to false,
                FeaturePreferences.COMMENT_MIN_LEVEL to 5,
                FeaturePreferences.HIDE_PGC_AUTO_ACTIVITY_POPUP to true,
                FeaturePreferences.HIDE_DYNAMIC_FREQUENT_VISITS to true,
                FeaturePreferences.HIDE_SEARCH_HOME_RECOMMEND to true,
                FeaturePreferences.REMOVE_HOME_RECOMMEND_PGC to true,
                FeaturePreferences.REMOVE_HOME_RECOMMEND_SPECIAL_CARDS to false,
                FeaturePreferences.PLAYER_UNLOCK_BACKGROUND to true,
                FeaturePreferences.PLAYER_UNLOCK_SMALL_WINDOW to true,
                FeaturePreferences.PLAYER_UNLOCK_CAST to true,
                FeaturePreferences.PLAYER_DISABLE_LONG_PRESS to true,
                FeaturePreferences.PLAYER_LONG_PRESS_SPEED_PERCENT to 275,
                FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT to 125,
                RemoteHookConfigContract.KEY_FREE_COPY_CONFIG_REVISION to 42L,
                RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP to 84L,
                RemoteHookConfigContract.KEY_SEMANTIC_JEV_API_KEY to "  jev-test-key  "
            )
        )
        val encoded = RemoteHookConfigContract.encode(
            generation = 123L,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
            deliveryEnabled = true,
            noRootRevision = 77L,
            decision = UserTermsDecision.ACCEPTED,
            values = values
        )
        val decoded = RemoteHookConfigContract.decode(encoded)

        assertEquals(RemoteHookConfigContract.persistedKeys, encoded.keys)
        assertTrue(decoded is RemoteHookConfigDecodeResult.Ready)
        val snapshot = (decoded as RemoteHookConfigDecodeResult.Ready).snapshot
        assertEquals(123L, snapshot.generation)
        assertEquals(BuildConfig.VERSION_CODE.toLong(), snapshot.moduleVersionCode)
        assertTrue(snapshot.deliveryEnabled)
        assertEquals(77L, snapshot.noRootRevision)
        assertTrue(snapshot.authorized)
        assertEquals(values, snapshot.values)
        assertEquals(true, snapshot.values[FeaturePreferences.HIDE_PGC_AUTO_ACTIVITY_POPUP])
        assertEquals(true, snapshot.values[FeaturePreferences.HIDE_DYNAMIC_FREQUENT_VISITS])
        assertEquals(true, snapshot.values[FeaturePreferences.HIDE_SEARCH_HOME_RECOMMEND])
        assertEquals(false, defaultValues()[FeaturePreferences.HIDE_SEARCH_HOME_RECOMMEND])
        assertEquals(false, defaultValues()[FeaturePreferences.HIDE_DYNAMIC_FREQUENT_VISITS])
        assertEquals(true, snapshot.values[FeaturePreferences.REMOVE_HOME_RECOMMEND_PGC])
        assertEquals(false, snapshot.values[FeaturePreferences.REMOVE_HOME_RECOMMEND_SPECIAL_CARDS])
        assertEquals(false, defaultValues()[FeaturePreferences.REMOVE_HOME_RECOMMEND_PGC])
        assertEquals(false, defaultValues()[FeaturePreferences.REMOVE_HOME_RECOMMEND_SPECIAL_CARDS])
        assertEquals(false, defaultValues()[FeaturePreferences.HIDE_PGC_AUTO_ACTIVITY_POPUP])
        assertEquals(true, snapshot.values[FeaturePreferences.PLAYER_UNLOCK_BACKGROUND])
        assertEquals(true, snapshot.values[FeaturePreferences.PLAYER_UNLOCK_SMALL_WINDOW])
        assertEquals(true, snapshot.values[FeaturePreferences.PLAYER_UNLOCK_CAST])
        assertEquals(true, snapshot.values[FeaturePreferences.PLAYER_DISABLE_LONG_PRESS])
        assertEquals(275, snapshot.values[FeaturePreferences.PLAYER_LONG_PRESS_SPEED_PERCENT])
        assertEquals(125, snapshot.values[FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT])
        assertTrue(RemoteHookConfigContract.decode(encoded - FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT)
            is RemoteHookConfigDecodeResult.Invalid)
        assertEquals("jev-test-key", snapshot.values[RemoteHookConfigContract.KEY_SEMANTIC_JEV_API_KEY])
        // 目录外是运行时修订字段、插件索引摘要、全部运行时凭据（判定来源 + AI Insight）与两项生成调参，不能遗漏或多导出其它偏好。
        val runtimeKeys = setOf(RemoteHookConfigContract.KEY_FREE_COPY_CONFIG_REVISION,
            RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP,
            RemoteHookConfigContract.KEY_PLUGIN_INDEX_DIGEST) + RemoteHookConfigContract.RUNTIME_API_KEYS +
            RemoteHookConfigContract.INSIGHT_TUNING_KEYS
        assertEquals(runtimeKeys, snapshot.values.keys - SettingsCatalog.specs.map { it.storageKey }.toSet())
        assertEquals(SettingsCatalog.specs.size + 3 + RemoteHookConfigContract.RUNTIME_API_KEYS.size +
            RemoteHookConfigContract.INSIGHT_TUNING_KEYS.size, snapshot.values.size)
    }

    /** JEV API Key：随 hook_config 下发且受摘要保护，但不是目录项（不进备份），超长/非字符串按未配置处理。 */
    fun `jev api key is a signed runtime credential outside the backup catalog`() {
        val key = RemoteHookConfigContract.KEY_SEMANTIC_JEV_API_KEY
        assertTrue(key in RemoteHookConfigContract.hookValueKeys)
        assertTrue(SettingsCatalog.specs.none { it.storageKey == key })
        assertEquals("", defaultValues()[key])
        assertEquals("", RemoteHookConfigContract.resolveSourceValues(mapOf(key to 42))[key])
        assertEquals("", RemoteHookConfigContract.resolveSourceValues(
            mapOf(key to "x".repeat(RemoteHookConfigContract.MAX_SEMANTIC_JEV_API_KEY_LENGTH + 1)))[key])

        val encoded = RemoteHookConfigContract.encode(
            generation = 1L,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
            deliveryEnabled = true,
            noRootRevision = 0L,
            decision = UserTermsDecision.ACCEPTED,
            values = RemoteHookConfigContract.resolveSourceValues(mapOf(key to "secret"))
        )
        // 换 Key 不重签 ⇒ 摘要不符，宿主整组拒收（与其它值一样 fail-closed）。
        assertTrue(RemoteHookConfigContract.decode(encoded + (key to "other")) is RemoteHookConfigDecodeResult.Invalid)
        assertTrue(RemoteHookConfigContract.decode(encoded + (key to 7)) is RemoteHookConfigDecodeResult.Invalid)
    }

    fun `source resolution rejects arbitrary keys and repairs invalid values`() {
        val resolved = RemoteHookConfigContract.resolveSourceValues(
            mapOf(
                "private_token" to "must-not-leak",
                HookEntry.PREF_FREE_COPY_ENABLED to "wrong-type",
                FeaturePreferences.COMMENT_MIN_LEVEL to 999,
                FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT to 999,
                FeaturePreferences.PLAYER_LONG_PRESS_SPEED_PERCENT to Float.NaN,
                RemoteHookConfigContract.KEY_FREE_COPY_CONFIG_REVISION to -1L
            )
        )

        assertEquals(RemoteHookConfigContract.hookValueKeys, resolved.keys)
        assertFalse("private_token" in resolved)
        assertEquals(true, resolved[HookEntry.PREF_FREE_COPY_ENABLED])
        assertEquals(6, resolved[FeaturePreferences.COMMENT_MIN_LEVEL])
        assertEquals(0, resolved[FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT])
        assertEquals(0, resolved[FeaturePreferences.PLAYER_LONG_PRESS_SPEED_PERCENT])
        assertEquals(0L, resolved[RemoteHookConfigContract.KEY_FREE_COPY_CONFIG_REVISION])
    }

    fun `terms and tamper validation fail closed`() {
        UserTermsDecision.entries.forEach { decision ->
            val decoded = RemoteHookConfigContract.decode(
                encode(decision = decision)
            ) as RemoteHookConfigDecodeResult.Ready
            assertEquals(decision.isAuthorized, decoded.snapshot.authorized)
        }
        val disabled = RemoteHookConfigContract.decode(
            encode(decision = UserTermsDecision.ACCEPTED, deliveryEnabled = false)
        ) as RemoteHookConfigDecodeResult.Ready
        assertFalse(disabled.snapshot.authorized)

        val encoded = encode(generation = 7L)
        assertInvalid(encoded.toMutableMap().apply {
            put(HookEntry.PREF_FREE_COPY_ENABLED, false)
        }, "digest")
        assertInvalid(encoded.toMutableMap().apply {
            put("unknown", true)
        }, "key-set")
    }

    fun `v4 snapshot missing v5 keys fails with schema before key-set`() {
        val encoded = encode(generation = 8L)
        val v4Incomplete = encoded.toMutableMap().apply {
            put(RemoteHookConfigContract.KEY_SCHEMA_VERSION, 4)
            remove(RemoteHookConfigContract.KEY_PLUGIN_INDEX_DIGEST)
        }
        assertInvalid(v4Incomplete, "schema")
        assertInvalid(encoded.toMutableMap().apply {
            put(RemoteHookConfigContract.KEY_SCHEMA_VERSION, "5")
            remove(RemoteHookConfigContract.KEY_PLUGIN_INDEX_DIGEST)
        }, "schema")
        assertInvalid(encoded.toMutableMap().apply {
            put(RemoteHookConfigContract.KEY_CATALOG_VERSION, SettingsCatalog.CATALOG_VERSION - 1)
            remove(RemoteHookConfigContract.KEY_PLUGIN_INDEX_DIGEST)
        }, "catalog")
        // v5 协议版本完整但缺失功能键或包含未知键时仍精准命中 key-set 校验。
        assertInvalid(encoded - FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT, "key-set")
        assertInvalid(encoded + ("unexpected_v5_key" to 1), "key-set")
    }

    fun `encoder rejects incomplete values and nonpositive generations`() {
        val incomplete = defaultValues().toMutableMap().apply {
            remove(HookEntry.PREF_FREE_COPY_ENABLED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            encode(values = incomplete)
        }
        assertThrows(IllegalArgumentException::class.java) {
            encode(generation = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            encode(noRootRevision = -1L)
        }
    }

    fun `module version and delivery metadata fail closed`() {
        val encoded = encode(generation = 9L, noRootRevision = 15L)
        assertInvalid(encoded.toMutableMap().apply {
            put(
                RemoteHookConfigContract.KEY_MODULE_VERSION_CODE,
                BuildConfig.VERSION_CODE.toLong() + 1L
            )
        }, "module-version")
        assertInvalid(encoded.toMutableMap().apply {
            put(RemoteHookConfigContract.KEY_DELIVERY_ENABLED, false)
        }, "digest")
        assertInvalid(encoded.toMutableMap().apply {
            put(RemoteHookConfigContract.KEY_NO_ROOT_REVISION, -1L)
        }, "no-root-revision")
    }

    private fun defaultValues(): Map<String, Any> =
        RemoteHookConfigContract.resolveSourceValues(emptyMap<String, Any>())

    private fun encode(
        generation: Long = 1L,
        moduleVersionCode: Long = BuildConfig.VERSION_CODE.toLong(),
        deliveryEnabled: Boolean = true,
        noRootRevision: Long = 0L,
        decision: UserTermsDecision = UserTermsDecision.ACCEPTED,
        values: Map<String, Any> = defaultValues()
    ): Map<String, Any> = RemoteHookConfigContract.encode(
        generation = generation,
        moduleVersionCode = moduleVersionCode,
        deliveryEnabled = deliveryEnabled,
        noRootRevision = noRootRevision,
        decision = decision,
        values = values
    )

    private fun assertInvalid(values: Map<String, *>, reasonPrefix: String) {
        val decoded = RemoteHookConfigContract.decode(values)
        assertTrue(decoded is RemoteHookConfigDecodeResult.Invalid)
        assertTrue(
            (decoded as RemoteHookConfigDecodeResult.Invalid).reason,
            decoded.reason.startsWith(reasonPrefix)
        )
    }

    /** AI Insight 生成调参是签名下发的 Int 运行时键：缺省回落默认、越界夹到范围、不进目录与备份。 */
    fun `insight tuning keys are clamped signed runtime integers outside the backup catalog`() {
        val budgetKey = RemoteHookConfigContract.KEY_INSIGHT_OUTPUT_BUDGET
        val timeoutKey = RemoteHookConfigContract.KEY_INSIGHT_TIMEOUT_MS
        val config = com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight.InsightConfig
        assertTrue(budgetKey in RemoteHookConfigContract.hookValueKeys)
        assertTrue(timeoutKey in RemoteHookConfigContract.hookValueKeys)
        assertTrue(SettingsCatalog.specs.none { it.storageKey == budgetKey || it.storageKey == timeoutKey })
        assertEquals(config.DEFAULT_OUTPUT_BUDGET, defaultValues()[budgetKey])
        assertEquals(config.DEFAULT_TIMEOUT_MS, defaultValues()[timeoutKey])
        // 越界 Int 夹到范围边界，错误类型回落默认值，绝不把极端值下发到宿主。
        assertEquals(8192, RemoteHookConfigContract.resolveSourceValues(mapOf(budgetKey to 99999))[budgetKey])
        assertEquals(30_000, RemoteHookConfigContract.resolveSourceValues(mapOf(timeoutKey to 1))[timeoutKey])
        assertEquals(defaultValues()[budgetKey], RemoteHookConfigContract.resolveSourceValues(mapOf(budgetKey to "512"))[budgetKey])
        assertEquals(defaultValues()[timeoutKey], RemoteHookConfigContract.resolveSourceValues(mapOf(timeoutKey to 30_000L))[timeoutKey])
        val inRange = RemoteHookConfigContract.resolveSourceValues(mapOf(budgetKey to 512, timeoutKey to 60_000))
        assertEquals(512, inRange[budgetKey]); assertEquals(60_000, inRange[timeoutKey])
        // 改调参不重签 ⇒ 摘要不符，宿主整组拒收。
        val encoded = RemoteHookConfigContract.encode(
            generation = 1L,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
            deliveryEnabled = true,
            noRootRevision = 0L,
            decision = UserTermsDecision.ACCEPTED,
            values = inRange
        )
        assertTrue(RemoteHookConfigContract.decode(encoded) is RemoteHookConfigDecodeResult.Ready)
        assertTrue(RemoteHookConfigContract.decode(encoded + (budgetKey to 4096)) is RemoteHookConfigDecodeResult.Invalid)
    }

    /** 2–8 号判定来源的 Key 与 1 号同等对待：签名保护、不进目录、超长按未配置。 */
    fun `extra semantic source keys are signed runtime credentials too`() {
        assertEquals(8, RemoteHookConfigContract.SEMANTIC_API_KEYS.size)
        assertEquals(RemoteHookConfigContract.KEY_SEMANTIC_JEV_API_KEY, RemoteHookConfigContract.semanticApiKey(1))
        RemoteHookConfigContract.SEMANTIC_API_KEYS.drop(1).forEach { key ->
            assertTrue(key in RemoteHookConfigContract.hookValueKeys)
            assertTrue(SettingsCatalog.specs.none { it.storageKey == key })
            assertEquals("", defaultValues()[key])
            assertEquals("", RemoteHookConfigContract.resolveSourceValues(
                mapOf(key to "x".repeat(RemoteHookConfigContract.MAX_SEMANTIC_JEV_API_KEY_LENGTH + 1)))[key])
            val encoded = RemoteHookConfigContract.encode(
                generation = 1L,
                moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
                deliveryEnabled = true,
                noRootRevision = 0L,
                decision = UserTermsDecision.ACCEPTED,
                values = RemoteHookConfigContract.resolveSourceValues(mapOf(key to "secret"))
            )
            val decoded = RemoteHookConfigContract.decode(encoded)
            assertEquals("secret", (decoded as RemoteHookConfigDecodeResult.Ready).snapshot.values[key])
            assertTrue(RemoteHookConfigContract.decode(encoded + (key to "other")) is RemoteHookConfigDecodeResult.Invalid)
        }
    }

    /** AI Insight 的 Key 与判定来源 Key 同等对待：签名保护、不进目录、超长或非字符串按未配置处理。 */
    fun `ai insight api key is a signed runtime credential outside the backup catalog`() {
        val key = RemoteHookConfigContract.KEY_AI_INSIGHT_API_KEY
        assertTrue(key in RemoteHookConfigContract.hookValueKeys)
        assertTrue(key in RemoteHookConfigContract.RUNTIME_API_KEYS)
        assertTrue(SettingsCatalog.specs.none { it.storageKey == key })
        assertEquals("", defaultValues()[key])
        assertEquals("", RemoteHookConfigContract.resolveSourceValues(mapOf(key to 42))[key])
        assertEquals("", RemoteHookConfigContract.resolveSourceValues(
            mapOf(key to "x".repeat(RemoteHookConfigContract.MAX_SEMANTIC_JEV_API_KEY_LENGTH + 1)))[key])
        assertEquals("secret", RemoteHookConfigContract.resolveSourceValues(mapOf(key to "  secret  "))[key])
        val encoded = RemoteHookConfigContract.encode(
            generation = 1L,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
            deliveryEnabled = true,
            noRootRevision = 0L,
            decision = UserTermsDecision.ACCEPTED,
            values = RemoteHookConfigContract.resolveSourceValues(mapOf(key to "secret"))
        )
        assertEquals("secret", (RemoteHookConfigContract.decode(encoded)
                as RemoteHookConfigDecodeResult.Ready).snapshot.values[key])
        // 改 Key 不重签 ⇒ 摘要不符，宿主整组拒收（与其它运行时凭据一样 fail-closed）。
        assertTrue(RemoteHookConfigContract.decode(encoded + (key to "other")) is RemoteHookConfigDecodeResult.Invalid)
    }

    /** 插件索引摘要是受摘要保护的运行时信任锚：合法 64-hex 透传，非法类型/hex 落回空串，绝不进目录与备份。 */
    fun `plugin index digest is a signed runtime trust anchor outside the backup catalog`() {
        assertEquals(5, RemoteHookConfigContract.SCHEMA_VERSION)
        val key = RemoteHookConfigContract.KEY_PLUGIN_INDEX_DIGEST
        assertTrue(key in RemoteHookConfigContract.hookValueKeys)
        assertTrue(SettingsCatalog.specs.none { it.storageKey == key })
        assertEquals("", defaultValues()[key])

        val validDigest = "0123456789abcdef".repeat(4)
        assertEquals(validDigest, RemoteHookConfigContract.resolveSourceValues(mapOf(key to "  $validDigest  "))[key])
        assertEquals("", RemoteHookConfigContract.resolveSourceValues(mapOf(key to 12345))[key])
        assertEquals("", RemoteHookConfigContract.resolveSourceValues(mapOf(key to "A".repeat(64)))[key])
        assertEquals("", RemoteHookConfigContract.resolveSourceValues(mapOf(key to "0".repeat(63)))[key])
        assertEquals("", RemoteHookConfigContract.resolveSourceValues(mapOf(key to "g".repeat(64)))[key])

        val encoded = encode(values = RemoteHookConfigContract.resolveSourceValues(mapOf(key to validDigest)))
        val decoded = RemoteHookConfigContract.decode(encoded) as RemoteHookConfigDecodeResult.Ready
        assertEquals(validDigest, decoded.snapshot.values[key])
        assertTrue(RemoteHookConfigContract.decode(encoded + (key to "f".repeat(64))) is RemoteHookConfigDecodeResult.Invalid)
        assertTrue(RemoteHookConfigContract.decode(encoded + (key to "")) is RemoteHookConfigDecodeResult.Invalid)
        assertTrue(RemoteHookConfigContract.decode(encoded + (key to 42)) is RemoteHookConfigDecodeResult.Invalid)
        assertThrows(IllegalArgumentException::class.java) {
            encode(values = defaultValues() + (key to "not-a-valid-sha256"))
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (RemoteHookConfigContractCases) -> Unit) {
            val s = RemoteHookConfigContractCases()
            block(s)
        }
        runIsolated { s -> s.`complete API 102 config round trips all whitelisted values`() }
        runIsolated { s -> s.`jev api key is a signed runtime credential outside the backup catalog`() }
        runIsolated { s -> s.`source resolution rejects arbitrary keys and repairs invalid values`() }
        runIsolated { s -> s.`terms and tamper validation fail closed`() }
        runIsolated { s -> s.`v4 snapshot missing v5 keys fails with schema before key-set`() }
        runIsolated { s -> s.`encoder rejects incomplete values and nonpositive generations`() }
        runIsolated { s -> s.`module version and delivery metadata fail closed`() }
        runIsolated { s -> s.`insight tuning keys are clamped signed runtime integers outside the backup catalog`() }
        runIsolated { s -> s.`extra semantic source keys are signed runtime credentials too`() }
        runIsolated { s -> s.`ai insight api key is a signed runtime credential outside the backup catalog`() }
        runIsolated { s -> s.`plugin index digest is a signed runtime trust anchor outside the backup catalog`() }
    }
}

private class RemoteHookConfigPublishPolicyCases {

    fun `single publisher repeats for dirty settings or a newer terms decision`() {
        assertFalse(
            shouldRepeatRemotePublish(
                dirty = false,
                attemptedDecision = UserTermsDecision.ACCEPTED,
                requestedDecision = UserTermsDecision.ACCEPTED
            )
        )
        assertTrue(
            shouldRepeatRemotePublish(
                dirty = true,
                attemptedDecision = UserTermsDecision.ACCEPTED,
                requestedDecision = UserTermsDecision.ACCEPTED
            )
        )
        assertTrue(
            shouldRepeatRemotePublish(
                dirty = false,
                attemptedDecision = UserTermsDecision.ACCEPTED,
                requestedDecision = UserTermsDecision.DECLINED
            )
        )
    }
}
