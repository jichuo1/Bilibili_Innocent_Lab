package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import java.util.Collections
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoRootSyncFlightRegistryTest {
    private val firstKey = NoRootSyncFlightRegistry.Key(
        intentGeneration = 1L,
        snapshotRevision = 10L,
        enabled = true,
        termsDecision = UserTermsDecision.ACCEPTED
    )

    @Test
    fun `same revision joins one active flight`() {
        val registry = NoRootSyncFlightRegistry<String>()
        val handled = mutableListOf<String>()
        val notified = mutableListOf<String?>()

        val first = registry.register(firstKey, handled::add, notified::add)
        val second = registry.register(firstKey, { error("must keep first handler") }) {
            notified += it
        }

        assertTrue(first.startsFlight)
        assertFalse(second.startsFlight)
        assertTrue(second.displacedListeners.isEmpty())
        assertEquals(first.token, second.token)
        val completion = registry.takeCompletion(first.token)!!
        completion.resultHandler("ok")
        completion.listeners.forEach { it("ok") }
        assertEquals(listOf("ok"), handled)
        assertEquals(listOf("ok", "ok"), notified)
    }

    @Test
    fun `new revision supersedes older listener and becomes current`() {
        val registry = NoRootSyncFlightRegistry<String>()
        val superseded = mutableListOf<String?>()
        registry.register(firstKey, {}, superseded::add)
        val nextKey = firstKey.copy(snapshotRevision = 11L)

        val registration = registry.register(nextKey, {}, null)
        val displacedToken = checkNotNull(registration.displacedToken)

        assertTrue(registration.startsFlight)
        assertEquals(firstKey, displacedToken.key)
        registration.displacedListeners.forEach { it(null) }
        assertEquals(listOf<String?>(null), superseded)
        assertFalse(registry.isCurrent(displacedToken))
        assertTrue(registry.isCurrent(registration.token))
        assertNull(registry.takeCompletion(displacedToken))
    }

    @Test
    fun `cancel only removes matching current flight`() {
        val registry = NoRootSyncFlightRegistry<String>()
        val notified = mutableListOf<String?>()
        val registration = registry.register(firstKey, {}, notified::add)
        val different = registry.register(firstKey.copy(snapshotRevision = 9L), {}, null)

        assertTrue(registry.cancel(registration.token).isEmpty())
        assertTrue(registry.isCurrent(different.token))
        registry.cancel(different.token).forEach { it(null) }

        assertTrue(notified.isEmpty())
        assertFalse(registry.isCurrent(different.token))
    }

    @Test
    fun `intent generation and enabled mode are part of flight identity`() {
        val registry = NoRootSyncFlightRegistry<String>()
        registry.register(firstKey, {}, null)

        val disabled = firstKey.copy(intentGeneration = 2L, enabled = false)
        val registration = registry.register(disabled, {}, null)

        assertTrue(registration.startsFlight)
        assertEquals(firstKey, registration.displacedToken?.key)
        assertTrue(registry.isCurrent(registration.token))
    }

    @Test
    fun `terms decision is part of flight identity`() {
        val registry = NoRootSyncFlightRegistry<String>()
        registry.register(firstKey, {}, null)

        val declined = firstKey.copy(termsDecision = UserTermsDecision.DECLINED)
        val registration = registry.register(declined, {}, null)

        assertTrue(registration.startsFlight)
        assertEquals(firstKey, registration.displacedToken?.key)
        assertTrue(registry.isCurrent(registration.token))
    }

    @Test
    fun `late completion from retried same key cannot complete new attempt`() {
        val registry = NoRootSyncFlightRegistry<String>()
        val first = registry.register(firstKey, {}, null)
        assertNotNull(registry.takeCompletion(first.token))

        val retry = registry.register(firstKey, {}, null)

        assertFalse(first.token == retry.token)
        assertNull(registry.takeCompletion(first.token))
        assertTrue(registry.isCurrent(retry.token))
        assertNotNull(registry.takeCompletion(retry.token))
    }

    @Test
    fun `concurrent callers of same revision start exactly one attempt`() {
        val registry = NoRootSyncFlightRegistry<String>()
        val start = CountDownLatch(1)
        val registrations = Collections.synchronizedList(
            mutableListOf<NoRootSyncFlightRegistry.Registration<String>>()
        )
        val threads = List(16) {
            Thread {
                start.await()
                registrations += registry.register(firstKey, {}, {})
            }
        }

        threads.forEach(Thread::start)
        start.countDown()
        threads.forEach(Thread::join)

        assertEquals(1, registrations.count { it.startsFlight })
        val token = registrations.first().token
        assertTrue(registrations.all { it.token == token })
        assertEquals(16, registry.takeCompletion(token)?.listeners?.size)
    }
}
