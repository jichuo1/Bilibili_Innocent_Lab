package com.Bilibili_Innocent_Lab.xposedmodule.settings.terms

import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigPublishState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserTermsAuthorizationCoordinatorTest {

    @Test
    fun `pending acceptance remains closed until a successful capable publication`() {
        assertEquals(
            UserTermsSyncState.IDLE,
            resolve(hasPending = false)
        )
        assertEquals(
            UserTermsSyncState.WAITING_FOR_SERVICE,
            resolve(frameworkConnected = false)
        )
        assertEquals(
            UserTermsSyncState.UNSUPPORTED,
            resolve(frameworkCapable = false)
        )
        assertEquals(
            UserTermsSyncState.SYNCING,
            resolve(publishPending = true)
        )
        assertEquals(
            UserTermsSyncState.FAILED,
            resolve(publishState = RemoteHookConfigPublishState.FAILED)
        )
        assertEquals(
            UserTermsSyncState.PENDING,
            resolve()
        )
    }

    @Test
    fun `local finalization failure outranks remote progress`() {
        assertEquals(
            UserTermsSyncState.FAILED,
            resolve(
                localFailure = true,
                publishPending = true,
                publishState = RemoteHookConfigPublishState.PUBLISHING
            )
        )
    }

    @Test
    fun `activity recreation is requested only for a real authorization transition`() {
        assertTrue(
            didUserTermsAuthorizationComplete(
                UserTermsDecision.UNDECIDED,
                UserTermsDecision.ACCEPTED
            )
        )
        assertTrue(
            didUserTermsAuthorizationComplete(
                UserTermsDecision.DECLINED,
                UserTermsDecision.LEGACY_EXEMPT
            )
        )
        assertFalse(
            didUserTermsAuthorizationComplete(
                UserTermsDecision.ACCEPTED,
                UserTermsDecision.ACCEPTED
            )
        )
        assertFalse(
            didUserTermsAuthorizationComplete(
                UserTermsDecision.LEGACY_EXEMPT,
                UserTermsDecision.ACCEPTED
            )
        )
    }

    private fun resolve(
        hasPending: Boolean = true,
        localFailure: Boolean = false,
        publishPending: Boolean = false,
        publishState: RemoteHookConfigPublishState = RemoteHookConfigPublishState.READY,
        frameworkConnected: Boolean = true,
        frameworkCapable: Boolean = true
    ): UserTermsSyncState = resolveUserTermsSyncState(
        hasPendingAcceptance = hasPending,
        hasLocalFailure = localFailure,
        publishPending = publishPending,
        publishState = publishState,
        frameworkConnected = frameworkConnected,
        frameworkCapable = frameworkCapable
    )
}
