package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossAppBroadcastPolicyTest {

    @Test
    fun `sender identity is shared starting from Android 14`() {
        assertFalse(CrossAppBroadcastPolicy.shouldShareSenderIdentity(33))
        assertTrue(CrossAppBroadcastPolicy.shouldShareSenderIdentity(34))
        assertTrue(CrossAppBroadcastPolicy.shouldShareSenderIdentity(35))
    }

    @Test
    fun `ephemeral callback avoids synthetic permission before Android 13`() {
        assertEquals(
            CrossAppBroadcastPolicy.CallbackReceiverRegistration.PLATFORM_WITH_EPHEMERAL_PROOF,
            CrossAppBroadcastPolicy.callbackReceiverRegistration(28)
        )
        assertEquals(
            CrossAppBroadcastPolicy.CallbackReceiverRegistration.PLATFORM_WITH_EPHEMERAL_PROOF,
            CrossAppBroadcastPolicy.callbackReceiverRegistration(32)
        )
    }

    @Test
    fun `ephemeral callback is not exported starting from Android 13`() {
        assertEquals(
            CrossAppBroadcastPolicy.CallbackReceiverRegistration.NOT_EXPORTED,
            CrossAppBroadcastPolicy.callbackReceiverRegistration(33)
        )
        assertEquals(
            CrossAppBroadcastPolicy.CallbackReceiverRegistration.NOT_EXPORTED,
            CrossAppBroadcastPolicy.callbackReceiverRegistration(35)
        )
    }
}
