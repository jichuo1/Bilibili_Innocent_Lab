package com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart

import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.AuthorityPublication
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationIdentity
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import org.junit.Assert.*
import org.junit.Test

class RestartConfigBarrierTest {
    private val publication = AuthorityPublication(PublicationIdentity("install", 1, 1, 1, "config"),
        UserTermsDecision.ACCEPTED, emptyMap(), pending = false, directAllowed = true)

    @Test fun standardCompatibilityCanRestartWhenRemoteServiceIsDisconnected() {
        val steps = mutableListOf<String>()
        assertTrue(persistStandardRestartConfig(
            persistLocal = { steps += "persist"; true },
            capture = { steps += "capture"; publication },
            lspatchDirect = { false },
            publishRemote = { steps += "remote-unavailable"; false },
            stillCurrent = { steps += "revalidate"; it === publication }
        ))
        assertEquals(listOf("persist", "capture", "remote-unavailable", "revalidate"), steps)
    }

    @Test fun aTransportExceptionStillAllowsAnAuthorizedCurrentDirectDocument() {
        assertTrue(save(publish = { error("service disconnected") }))
    }

    @Test fun ordinaryStandardConfigStillRequiresRemotePublication() {
        val standard = publication.copy(directAllowed = false)
        assertFalse(save(value = standard, publish = { false }))
        assertTrue(save(value = standard, publish = { true }))
    }

    @Test fun lspatchDirectConfigDoesNotAttemptToWriteTheHostOwnedStore() {
        assertTrue(save(value = publication.copy(directAllowed = false), lspatch = true,
            publish = { fail("LSPatch must use its current direct document"); false }))
    }

    @Test fun localWriteFailureCannotCaptureOrPublishAConfiguration() {
        assertFalse(persistStandardRestartConfig(
            persistLocal = { false },
            capture = { fail("A failed local write cannot create a publication"); publication },
            lspatchDirect = { fail("No transport may run after a failed save"); false },
            publishRemote = { fail("No transport may run after a failed save"); true },
            stillCurrent = { true }
        ))
    }

    @Test fun missingOrUnauthorizedPublicationsNeverReachATransport() {
        for (value in listOf(null, publication.copy(decision = UserTermsDecision.UNDECIDED))) {
            assertFalse(save(value = value, lspatch = true,
                publish = { fail("Authorization must precede publication"); true }))
        }
    }

    @Test fun aChangedPublicationCannotPassEitherSaveChannel() {
        assertFalse(save(current = false))
        assertFalse(save(value = publication.copy(directAllowed = false), publish = { true }, current = false))
        assertFalse(save(lspatch = true, current = false))
    }

    private fun save(value: AuthorityPublication? = publication, lspatch: Boolean = false,
                     publish: (AuthorityPublication) -> Boolean = { false }, current: Boolean = true): Boolean =
        persistStandardRestartConfig({ true }, { value }, { lspatch }, publish, { current })
}
