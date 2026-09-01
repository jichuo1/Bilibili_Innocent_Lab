package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote

import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteHookConfigPublishPolicyTest {

    @Test
    fun `single publisher repeats for dirty settings or a newer terms decision`() {
        assertFalse(
            shouldRepeatRemotePublish(
                dirty = false,
                attemptedDecision = UserTermsDecision.ACCEPTED,
                requestedDecision = UserTermsDecision.ACCEPTED
            )
        )
        assertTrue(
            shouldRepeatRemotePublish(
                dirty = true,
                attemptedDecision = UserTermsDecision.ACCEPTED,
                requestedDecision = UserTermsDecision.ACCEPTED
            )
        )
        assertTrue(
            shouldRepeatRemotePublish(
                dirty = false,
                attemptedDecision = UserTermsDecision.ACCEPTED,
                requestedDecision = UserTermsDecision.DECLINED
            )
        )
    }
}
