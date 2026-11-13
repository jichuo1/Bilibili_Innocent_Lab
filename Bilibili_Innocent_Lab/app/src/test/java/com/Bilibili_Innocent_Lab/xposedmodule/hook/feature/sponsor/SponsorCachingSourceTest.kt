package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SponsorCachingSourceTest {
    @get:Rule val folder = TemporaryFolder()
    private var time = 10_000_000_000L
    private val video = SponsorVideoId("BV1pVWu6ZEzn", 43486676261)
    private val ad = SponsorSegment("a", 74_636, 103_813, 566_000)

    private class Network(var result: SponsorFetchResult) : SponsorSegmentSource {
        var calls = 0
        override fun fetch(video: SponsorVideoId, cancellation: SponsorRequestCancellation): SponsorFetchResult {
            calls++; return result
        }
    }

    private fun store() = SponsorSegmentStore(folder.root) { time }
    private fun source(network: Network) = SponsorCachingSource(network, store()) { time }
    private fun fetch(source: SponsorSegmentSource, id: SponsorVideoId = video) = source.fetch(id, SponsorRequestCancellation())
    private fun fileOf(id: SponsorVideoId) = File(File(folder.root, SponsorSegmentStore.DIRECTORY), SponsorSegmentStore.keyOf(id) + ".json")

    @Test fun aSuccessfulFetchIsStoredAndAFreshEntryIsServedWithoutTheNetwork() {
        val network = Network(SponsorFetchResult.Available(listOf(ad)))
        val source = source(network)
        assertEquals("", (fetch(source) as SponsorFetchResult.Available).origin)
        time += SponsorCachingSource.FRESH_MS - 1
        val hit = fetch(source) as SponsorFetchResult.Available
        assertEquals(listOf(ad), hit.segments); assertEquals("disk", hit.origin)
        assertEquals(1, network.calls)
    }

    @Test fun aStaleEntryIsRefreshedFromTheNetworkAndOverwritten() {
        val network = Network(SponsorFetchResult.Available(listOf(ad)))
        val source = source(network); fetch(source)
        time += SponsorCachingSource.FRESH_MS
        val changed = ad.copy(endMs = 110_000)
        network.result = SponsorFetchResult.Available(listOf(changed))
        assertEquals(listOf(changed), (fetch(source) as SponsorFetchResult.Available).segments)
        assertEquals(2, network.calls)
        // 刚写入的是新缓存：不再联网，哪怕网络此刻是坏的。
        network.result = SponsorFetchResult.Unavailable("timeout", 2_000)
        assertEquals("disk", (fetch(source) as SponsorFetchResult.Available).origin)
        assertEquals(2, network.calls)
    }

    @Test fun anyNetworkFailureFallsBackToAnOlderEntry() {
        val network = Network(SponsorFetchResult.Available(listOf(ad)))
        val source = source(network); fetch(source)
        for (reason in listOf("timeout", "dns-error", "tls-error", "network-error", "http-503", "rate-limited", "busy")) {
            network.result = SponsorFetchResult.Unavailable(reason, 2_000)
            time += SponsorCachingSource.FRESH_MS
            val fallback = fetch(source) as SponsorFetchResult.Available
            assertEquals(reason, "disk-stale", fallback.origin); assertEquals(listOf(ad), fallback.segments)
        }
    }

    @Test fun anEntryPastTheStaleLimitIsDeletedNotServed() {
        val network = Network(SponsorFetchResult.Available(listOf(ad)))
        val source = source(network); fetch(source)
        network.result = SponsorFetchResult.Unavailable("network-error", 2_000)
        time += SponsorCachingSource.STALE_MAX_MS + 1
        assertTrue(fetch(source) is SponsorFetchResult.Unavailable)
        assertFalse(fileOf(video).exists())
    }

    @Test fun anEntryFromTheFutureAfterAClockRollbackIsDeletedNotServed() {
        val network = Network(SponsorFetchResult.Available(listOf(ad)))
        val source = source(network); fetch(source)
        network.result = SponsorFetchResult.Unavailable("network-error", 2_000)
        time -= 3_600_000 // 倒退 1 小时：在容忍范围内被解析，但缓存年龄为负，不可信
        assertTrue(fetch(source) is SponsorFetchResult.Unavailable)
        assertFalse(fileOf(video).exists())
    }

    @Test fun theNetworkSayingThereAreNoSegmentsDropsTheOldEntry() {
        val network = Network(SponsorFetchResult.Available(listOf(ad)))
        val source = source(network); fetch(source)
        time += SponsorCachingSource.FRESH_MS
        network.result = SponsorFetchResult.Available(emptyList())
        assertTrue((fetch(source) as SponsorFetchResult.Available).segments.isEmpty())
        assertFalse(fileOf(video).exists())
        network.result = SponsorFetchResult.Unavailable("timeout", 2_000)
        assertTrue("the removed entry must not come back", fetch(source) is SponsorFetchResult.Unavailable)
    }

    @Test fun aVideoWithoutSegmentsLeavesNothingOnDisk() {
        val other = video.copy(cid = 9)
        fetch(source(Network(SponsorFetchResult.Available(emptyList()))), other)
        assertFalse(fileOf(other).exists())
    }

    @Test fun aCancelledRequestNeverFallsBackToTheCache() {
        val network = Network(SponsorFetchResult.Available(listOf(ad)))
        val source = source(network); fetch(source)
        time += SponsorCachingSource.FRESH_MS
        network.result = SponsorFetchResult.Unavailable("cancelled")
        assertEquals("cancelled", (fetch(source) as SponsorFetchResult.Unavailable).reason)
    }

    @Test fun withoutAStoreOrWithAnUnwritableOneTheNetworkResultPassesThroughUnchanged() {
        val result = SponsorFetchResult.Available(listOf(ad))
        assertSame(result, fetch(SponsorCachingSource(Network(result), null) { time }))
        val broken = SponsorCachingSource(Network(result), SponsorSegmentStore(folder.newFile("occupied")) { time }) { time }
        assertSame(result, fetch(broken))
    }

    @Test fun theRepositoryDeliversTheDiskOriginToItsListeners() {
        fetch(source(Network(SponsorFetchResult.Available(listOf(ad)))))
        val repository = SponsorSegmentRepository(source(Network(SponsorFetchResult.Unavailable("timeout", 2_000))))
        try {
            var delivered: SponsorFetchResult? = null
            val latch = CountDownLatch(1)
            repository.lookup(video) { delivered = it; latch.countDown() }
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals("disk", (delivered as SponsorFetchResult.Available).origin)
        } finally { repository.close() }
    }
}
