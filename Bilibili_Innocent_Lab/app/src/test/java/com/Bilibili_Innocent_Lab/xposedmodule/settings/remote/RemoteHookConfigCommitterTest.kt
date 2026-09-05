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

        override fun readCached(): Map<String, *> {
            check(!failRead) { "simulated read failure" }
            return cached.toMap()
        }

        override fun commit(document: Map<String, Any>): Boolean {
            commits += 1
            cached = document.toMap()
            if (failCommit) return false
            remote = document.toMap()
            if (corruptAfterCommit) cached = cached - RemoteHookConfigContract.KEY_DIGEST
            return true
        }
    }
}
