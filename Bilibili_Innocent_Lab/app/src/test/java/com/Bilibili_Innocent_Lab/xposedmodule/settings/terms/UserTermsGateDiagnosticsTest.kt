package com.Bilibili_Innocent_Lab.xposedmodule.settings.terms

import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigPublishState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserTermsGateDiagnosticsTest {

    @Test
    fun `android uid encoding resolves primary and secondary users`() {
        assertEquals(0, androidUserIdFromUid(10_234))
        assertEquals(10, androidUserIdFromUid(1_010_234))
    }

    @Test
    fun `same-user result remains unknown until target identity is visible`() {
        assertNull(resolveSameAndroidUser(moduleUserId = 10, targetUserId = null))
        assertTrue(resolveSameAndroidUser(moduleUserId = 10, targetUserId = 10) == true)
        assertFalse(resolveSameAndroidUser(moduleUserId = 10, targetUserId = 0) == true)
    }

    @Test
    fun `remote failure codes are normalized for gated diagnostics`() {
        assertEquals(
            USER_TERMS_FAILURE_SERVICE_NOT_CONNECTED,
            failureCode(explicit = "service_not_connected")
        )
        assertEquals(
            USER_TERMS_FAILURE_API_UNSUPPORTED,
            failureCode(explicit = "remote_preferences_unsupported")
        )
        assertEquals(
            USER_TERMS_FAILURE_REMOTE_PUBLISH,
            failureCode(explicit = "publish_failed")
        )
    }

    @Test
    fun `local finalization failure keeps its distinct code`() {
        assertEquals(
            UserTermsAuthorizationCoordinator.FAILURE_LOCAL_WRITE,
            failureCode(explicit = UserTermsAuthorizationCoordinator.FAILURE_LOCAL_WRITE)
        )
    }

    @Test
    fun `current framework state supplies a bounded fallback code`() {
        assertEquals(
            USER_TERMS_FAILURE_SERVICE_NOT_CONNECTED,
            failureCode(connected = false, capable = false)
        )
        assertEquals(
            USER_TERMS_FAILURE_API_UNSUPPORTED,
            failureCode(connected = true, capable = false)
        )
        assertEquals(
            USER_TERMS_FAILURE_REMOTE_PUBLISH,
            failureCode(
                connected = true,
                capable = true,
                state = RemoteHookConfigPublishState.FAILED
            )
        )
        assertNull(
            failureCode(
                connected = true,
                capable = true,
                state = RemoteHookConfigPublishState.READY
            )
        )
    }

    private fun failureCode(
        explicit: String? = null,
        connected: Boolean = true,
        capable: Boolean = true,
        state: RemoteHookConfigPublishState = RemoteHookConfigPublishState.READY
    ): String? = resolveUserTermsGateFailureCode(
        explicitFailureCode = explicit,
        frameworkConnected = connected,
        frameworkCapable = capable,
        publishState = state
    )
}
