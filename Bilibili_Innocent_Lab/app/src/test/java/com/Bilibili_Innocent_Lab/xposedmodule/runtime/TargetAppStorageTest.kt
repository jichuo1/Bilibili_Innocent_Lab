package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetAppStorageTest {

    fun `maps Android uid to its user id`() {
        assertEquals(0, TargetAppStorage.userIdFromUid(10_251))
        assertEquals(10, TargetAppStorage.userIdFromUid(1_010_251))
    }

    fun `builds a work profile cache path`() {
        assertEquals(
            "/data/user/10/tv.danmaku.bili/cache/innocent_lab_adapt.json",
            TargetAppStorage.cachePath("innocent_lab_adapt.json", 10)
        )
    }

    fun `rejects a cache file path rather than a file name`() {
        assertThrows(IllegalArgumentException::class.java) {
            TargetAppStorage.cachePath("nested/cache.json", 0)
        }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (TargetAppStorageTest) -> Unit) {
            val s = TargetAppStorageTest()
            block(s)
        }
        runIsolated { s -> s.`maps Android uid to its user id`() }
        runIsolated { s -> s.`builds a work profile cache path`() }
        runIsolated { s -> s.`rejects a cache file path rather than a file name`() }
    
        UpdateCheckCoordinatorCases().verifyAllCases()
    }
}

private class UpdateCheckCoordinatorCases {

    private val stable = GitHubReleaseChecker.UpdateChannel.STABLE
    private val preview = GitHubReleaseChecker.UpdateChannel.PREVIEW

    fun firstRequestStartsImmediately() {
        val coordinator = UpdateCheckCoordinator()
        val request = UpdateCheckCoordinator.Request(stable, manual = false)

        assertEquals(request, coordinator.submit(request))
    }

    fun channelSwitchQueuesManualRequestAndSuppressesOldResult() {
        val coordinator = UpdateCheckCoordinator()
        coordinator.submit(UpdateCheckCoordinator.Request(stable, manual = false))
        val previewRequest = UpdateCheckCoordinator.Request(preview, manual = true)

        assertNull(coordinator.submit(previewRequest))
        val completion = coordinator.complete(stable, selectedChannel = preview)

        assertFalse(completion.shouldDeliverResult)
        assertEquals(previewRequest, completion.nextRequest)
    }

    fun returningToActiveChannelCancelsOlderQueuedRequest() {
        val coordinator = UpdateCheckCoordinator()
        coordinator.submit(UpdateCheckCoordinator.Request(stable, manual = false))
        coordinator.submit(UpdateCheckCoordinator.Request(preview, manual = true))
        coordinator.submit(UpdateCheckCoordinator.Request(stable, manual = true))

        val completion = coordinator.complete(stable, selectedChannel = stable)

        assertTrue(completion.shouldDeliverResult)
        assertNull(completion.nextRequest)
    }

    fun automaticRequestIsNotQueuedBehindActiveCheck() {
        val coordinator = UpdateCheckCoordinator()
        coordinator.submit(UpdateCheckCoordinator.Request(stable, manual = true))

        assertNull(
            coordinator.submit(UpdateCheckCoordinator.Request(preview, manual = false))
        )
        val completion = coordinator.complete(stable, selectedChannel = stable)

        assertTrue(completion.shouldDeliverResult)
        assertNull(completion.nextRequest)
    }

    fun staleQueuedRequestIsDroppedWhenItNoLongerMatchesSelection() {
        val coordinator = UpdateCheckCoordinator()
        coordinator.submit(UpdateCheckCoordinator.Request(stable, manual = false))
        coordinator.submit(UpdateCheckCoordinator.Request(preview, manual = true))

        val completion = coordinator.complete(stable, selectedChannel = stable)

        assertTrue(completion.shouldDeliverResult)
        assertNull(completion.nextRequest)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (UpdateCheckCoordinatorCases) -> Unit) {
            val s = UpdateCheckCoordinatorCases()
            block(s)
        }
        runIsolated { s -> s.firstRequestStartsImmediately() }
        runIsolated { s -> s.channelSwitchQueuesManualRequestAndSuppressesOldResult() }
        runIsolated { s -> s.returningToActiveChannelCancelsOlderQueuedRequest() }
        runIsolated { s -> s.automaticRequestIsNotQueuedBehindActiveCheck() }
        runIsolated { s -> s.staleQueuedRequestIsDroppedWhenItNoLongerMatchesSelection() }
    }
}
