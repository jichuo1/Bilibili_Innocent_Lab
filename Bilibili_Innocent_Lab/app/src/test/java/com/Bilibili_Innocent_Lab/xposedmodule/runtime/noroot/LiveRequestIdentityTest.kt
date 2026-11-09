package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import org.junit.Assert.*
import org.junit.Test

class LiveRequestIdentityTest {
    private val module = BuildConfig.APPLICATION_ID
    @Test fun systemSenderAndProofBindTheSameUidWhenCreatorPackageIsFiltered() {
        assertTrue(LiveRequestIdentity.accepts(null, 123, null, module, 123, true))
        assertFalse(LiveRequestIdentity.accepts(null, 123, null, null, 123, true))
    }
    @Test fun virtualizedPackageInfoCannotOverrideAuthoritativeModernSenderIdentity() {
        assertTrue(LiveRequestIdentity.accepts(module, 123, 999, module, 123, true))
    }
    @Test fun aDifferentCreatorUidCannotBorrowTheCurrentSendersPackage() {
        assertFalse(LiveRequestIdentity.accepts(module, 999, 999, module, 123, true))
    }
    @Test fun aDifferentSenderPackageCannotBorrowTheModulesProof() {
        assertFalse(LiveRequestIdentity.accepts(module, 123, 123, "other", 123, true))
    }
    @Test fun visibleCreatorPackagesMustMatchEvenWhenTheSystemSenderMatches() {
        assertFalse(LiveRequestIdentity.accepts("other", 123, 123, module, 123, true))
    }
    @Test fun olderSystemsRequireARealCreatorPackageAndMatchObservableUid() {
        assertTrue(LiveRequestIdentity.accepts(module, 123, null, null, -1, false))
        assertTrue(LiveRequestIdentity.accepts(module, 123, 123, null, -1, false))
        assertFalse(LiveRequestIdentity.accepts(null, 123, 123, module, 123, false))
        assertFalse(LiveRequestIdentity.accepts(module, 123, 999, null, -1, false))
    }
    @Test fun invalidUidsNeverAuthorizeARequest() {
        for (uid in listOf(-1, 0)) {
            assertFalse(LiveRequestIdentity.accepts(module, uid, uid, module, uid, true))
            assertFalse(LiveRequestIdentity.accepts(module, uid, uid, null, -1, false))
        }
    }
}
