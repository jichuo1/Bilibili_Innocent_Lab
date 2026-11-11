package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigDecodeResult
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发布逻辑在内存参考模型上的行为。
 *
 * [UpstreamStoreModel] 复刻 `NPatchRemoteStore.updatePreferences` 的可观察语义：`clear` 是事务之前
 * 单独提交的一条 DELETE，`delete` + `put` 在同一个事务里。这个前提由
 * `scripts/npatch` 的上游源码契约校验固定到具体提交，并在 G2 模拟器集成里与真实 Manager 差分比对；
 * 本测试只证明"在这样的存储上，发布路径不会让读者看到空文档或半份文档"。
 */
class NPatchRemotePublishTest {
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

    @Test
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

    @Test
    fun `the first publication into an empty store is a single visible transition`() {
        val model = UpstreamStoreModel(emptyMap())
        assertEquals(NPatchRemoteGateway.SyncResult.Success, publish(model))
        assertEquals(2, model.observed.size)
        assertTrue(RemoteHookConfigContract.decode(model.store) is RemoteHookConfigDecodeResult.Ready)
    }

    @Test
    fun `upstream clear is observable as an empty document which is why publication avoids it`() {
        val model = UpstreamStoreModel(document(generation = 100L))
        model.update(group, RemotePreferenceDiff(clear = true, delete = emptySet(), put = document(200L)))
        assertTrue(
            "documents the upstream clear-then-transaction window",
            model.observed.any { it.isEmpty() }
        )
        assertTrue(RemoteHookConfigContract.decode(emptyMap<String, Any>()) is RemoteHookConfigDecodeResult.Invalid)
    }

    @Test
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

    @Test
    fun `an already current document is not rewritten`() {
        val model = UpstreamStoreModel(emptyMap())
        assertEquals(NPatchRemoteGateway.SyncResult.Success, publish(model))
        val generation = model.store[RemoteHookConfigContract.KEY_GENERATION]

        assertEquals(NPatchRemoteGateway.SyncResult.Success, publish(model, clock = { 9_999L }))

        assertEquals(1, model.updates)
        assertEquals(generation, model.store[RemoteHookConfigContract.KEY_GENERATION])
    }

    @Test
    fun `a superseded intent before the write performs no update`() {
        val model = UpstreamStoreModel(emptyMap())
        assertNull(publish(model, stillCurrent = { false }))
        assertEquals(0, model.updates)
        assertTrue(model.store.isEmpty())
    }

    @Test
    fun `a superseded intent after the write skips the read-back`() {
        val model = UpstreamStoreModel(emptyMap())
        var calls = 0
        assertNull(publish(model, stillCurrent = { ++calls == 1 }))
        assertEquals(1, model.updates)
        assertEquals("only the initial snapshot was requested", 1, model.requests)
    }

    @Test
    fun `generation stays above the previous document even when the clock is behind`() {
        val previous = 9_000_000_000_000L
        val model = UpstreamStoreModel(document(generation = previous, revision = 5L))
        assertEquals(NPatchRemoteGateway.SyncResult.Success, publish(model, clock = { 1L }))
        assertEquals(previous + 1L, model.store[RemoteHookConfigContract.KEY_GENERATION])
    }

    @Test
    fun `a competing publisher between write and read-back is reported as a mismatch`() {
        val model = UpstreamStoreModel(emptyMap())
        model.afterUpdate = { store -> document(generation = 5_000_000L, revision = 99L).also { store.putAll(it) } }
        val error = assertThrows(IllegalStateException::class.java) { publish(model) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("content_mismatch"))
    }

    @Test
    fun `a corrupted read-back is reported with the decode reason`() {
        val model = UpstreamStoreModel(emptyMap())
        model.afterUpdate = { store -> store[RemoteHookConfigContract.KEY_DIGEST] = "0".repeat(64) }
        val error = assertThrows(IllegalStateException::class.java) { publish(model) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("digest"))
    }

    @Test
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
}
