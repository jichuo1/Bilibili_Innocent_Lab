package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import org.junit.Assert.*
import org.junit.Test

class RemoteHookConfigCommitterTest {
    private val values = RemoteHookConfigContract.resolveSourceValues(emptyMap<String, Any>())
    private val committer = RemoteHookConfigCommitter()
    private val backend = CachedBackend()

    private fun publish(
        decision: UserTermsDecision = UserTermsDecision.ACCEPTED,
        connectionId: Long = 1L,
        now: Long = 100L
    ) = committer.publish(
        connectionId, BuildConfig.VERSION_CODE.toLong(), decision, values, now, backend
    )

    @Test
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

    @Test
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

    @Test
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

    @Test
    fun `a prepopulated client cache cannot acknowledge a new publisher`() {
        publish()
        val fresh = RemoteHookConfigCommitter()
        val result = fresh.publish(
            1L, BuildConfig.VERSION_CODE.toLong(), UserTermsDecision.ACCEPTED, values, 100L, backend
        ) as RemoteHookConfigPublishResult.Success
        assertTrue(result.changed)
        assertEquals(2, backend.commits)
    }

    @Test
    fun `service death invalidates the previous acknowledgement`() {
        publish()
        committer.invalidate()
        assertTrue((publish() as RemoteHookConfigPublishResult.Success).changed)
        assertEquals(2, backend.commits)
    }

    @Test
    fun `client cache corruption after commit fails validation and is retried`() {
        backend.corruptAfterCommit = true
        assertFalse(publish().succeeded)
        backend.corruptAfterCommit = false
        assertTrue(publish().succeeded)
        assertEquals(2, backend.commits)
    }

    @Test
    fun `an exception also invalidates the previous acknowledgement`() {
        publish()
        backend.failRead = true
        assertFalse(publish().succeeded)
        backend.failRead = false
        assertTrue((publish() as RemoteHookConfigPublishResult.Success).changed)
        assertEquals(2, backend.commits)
    }

    @Test
    fun `changed decision and a backward clock still advance the generation`() {
        val first = publish(now = 500L) as RemoteHookConfigPublishResult.Success
        val next = publish(UserTermsDecision.DECLINED, now = 1L) as RemoteHookConfigPublishResult.Success
        assertEquals(first.generation + 1L, next.generation)
    }

    @Test
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

    @Test
    fun `missing connection and exhausted generation fail before commit`() {
        assertFalse(publish(connectionId = 0L).succeeded)
        backend.cached = mapOf(RemoteHookConfigContract.KEY_GENERATION to Long.MAX_VALUE)
        assertFalse(publish().succeeded)
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
            return true
        }
    }

    @Test
    fun `Irena style store deletes obsolete keys without clear support`() {
        backend.cached = mapOf("obsolete_setting" to "old", "obsolete_schema_key" to true)
        backend.remote = backend.cached
        assertTrue(publish().succeeded)
        assertEquals(setOf("obsolete_setting", "obsolete_schema_key"), backend.removals.single())
        assertEquals(RemoteHookConfigContract.persistedKeys, backend.remote.keys)
        assertEquals(backend.cached, backend.remote)
    }

    @Test
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

    @Test
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
}
