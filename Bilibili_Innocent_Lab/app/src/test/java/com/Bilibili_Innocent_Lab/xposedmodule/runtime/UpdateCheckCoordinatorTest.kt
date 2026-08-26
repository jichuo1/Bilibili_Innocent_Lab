package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckCoordinatorTest {

    private val stable = GitHubReleaseChecker.UpdateChannel.STABLE
    private val preview = GitHubReleaseChecker.UpdateChannel.PREVIEW

    @Test
    fun firstRequestStartsImmediately() {
        val coordinator = UpdateCheckCoordinator()
        val request = UpdateCheckCoordinator.Request(stable, manual = false)

        assertEquals(request, coordinator.submit(request))
    }

    @Test
    fun channelSwitchQueuesManualRequestAndSuppressesOldResult() {
        val coordinator = UpdateCheckCoordinator()
        coordinator.submit(UpdateCheckCoordinator.Request(stable, manual = false))
        val previewRequest = UpdateCheckCoordinator.Request(preview, manual = true)

        assertNull(coordinator.submit(previewRequest))
        val completion = coordinator.complete(stable, selectedChannel = preview)

        assertFalse(completion.shouldDeliverResult)
        assertEquals(previewRequest, completion.nextRequest)
    }

    @Test
    fun returningToActiveChannelCancelsOlderQueuedRequest() {
        val coordinator = UpdateCheckCoordinator()
        coordinator.submit(UpdateCheckCoordinator.Request(stable, manual = false))
        coordinator.submit(UpdateCheckCoordinator.Request(preview, manual = true))
        coordinator.submit(UpdateCheckCoordinator.Request(stable, manual = true))

        val completion = coordinator.complete(stable, selectedChannel = stable)

        assertTrue(completion.shouldDeliverResult)
        assertNull(completion.nextRequest)
    }

    @Test
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

    @Test
    fun staleQueuedRequestIsDroppedWhenItNoLongerMatchesSelection() {
        val coordinator = UpdateCheckCoordinator()
        coordinator.submit(UpdateCheckCoordinator.Request(stable, manual = false))
        coordinator.submit(UpdateCheckCoordinator.Request(preview, manual = true))

        val completion = coordinator.complete(stable, selectedChannel = stable)

        assertTrue(completion.shouldDeliverResult)
        assertNull(completion.nextRequest)
    }
}
