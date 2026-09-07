package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.Assert.*
import org.junit.Test

class ColdStartUpdateStateTest {
    private val stable = GitHubReleaseChecker.UpdateChannel.STABLE
    private val preview = GitHubReleaseChecker.UpdateChannel.PREVIEW
    private fun release(tag: String) = GitHubReleaseChecker.ReleaseInfo(
        tag, tag, "notes", GitHubReleaseChecker.REPOSITORY_URL + "/releases/tag/" + tag, null, tag.contains("alpha")
    )

    @Test fun coldStartWaitsTenSecondsAndOnlyClaimsOncePerProcess() {
        val state = ColdStartUpdateState()
        state.resume(1, 1000)
        assertFalse(state.claimAutomatic(1, 10999))
        assertTrue(state.claimAutomatic(1, 11000))
        assertFalse(state.claimAutomatic(1, 20000))
        state.pause(1)
        state.resume(2, 30000)
        assertNull(state.remainingMs(2, 50000))
        assertFalse(state.claimAutomatic(2, 50000))
        val anotherProcess = ColdStartUpdateState()
        anotherProcess.resume(1, 0)
        assertTrue(anotherProcess.claimAutomatic(1, 10000))
    }

    @Test fun foregroundDwellRestartsAfterPauseAndOldActivityCannotClaim() {
        val state = ColdStartUpdateState()
        state.resume(1, 0)
        state.resume(1, 7000)
        assertEquals(3000L, state.remainingMs(1, 7000))
        state.pause(1)
        assertFalse(state.claimAutomatic(1, 20000))
        state.resume(2, 21000)
        state.pause(1)
        assertFalse(state.claimAutomatic(1, 40000))
        assertFalse(state.claimAutomatic(2, 30999))
        assertTrue(state.claimAutomatic(2, 31000))
    }

    @Test fun onlyCurrentChannelAndNewestRequestCanReplaceNotice() {
        val state = ColdStartUpdateState()
        val first = state.beginRequest()
        val second = state.beginRequest()
        assertFalse(state.accept(first, stable, stable, release("v1.2.0"), "1.1.4"))
        assertFalse(state.accept(second, preview, stable, release("v1.3.0-alpha.1"), "1.1.4"))
        assertTrue(state.accept(second, stable, stable, release("v1.2.0"), "1.1.4"))
        assertNotNull(state.noticeFor(stable))
        assertNull(state.noticeFor(preview))
        state.channelChanged()
        assertNull(state.noticeFor(stable))
    }

    @Test fun equalOlderAndInvalidVersionsNeverShowNewAndResultsSurviveActivityRecreation() {
        val state = ColdStartUpdateState()
        val request = state.beginRequest()
        state.resume(1, 0)
        state.pause(1)
        state.resume(2, 1000)
        assertTrue(state.accept(request, stable, stable, release("v1.2.0"), "1.1.4"))
        assertEquals("v1.2.0", state.noticeFor(stable)?.release?.tagName)
        for (tag in listOf("1.1.4", "1.1.3", "bad")) {
            state.accept(state.beginRequest(), stable, stable, release(tag), "1.1.4")
            assertNull(state.noticeFor(stable))
        }
    }
}
