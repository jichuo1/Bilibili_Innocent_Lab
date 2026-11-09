package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuestPlaybackItemIdTest {
    @Test fun aidPlusCidIdIsSplit() {
        assertEquals(170001L to 279786L, GuestPlaybackHooks.parseItemId("170001+279786"))
        assertEquals(1L to 2L, GuestPlaybackHooks.parseItemId("1+2"))
    }

    @Test fun bareOrMalformedIdsAreNotGuessed() {
        assertNull(GuestPlaybackHooks.parseItemId("279786"))
        assertNull(GuestPlaybackHooks.parseItemId("170001+"))
        assertNull(GuestPlaybackHooks.parseItemId("+279786"))
        assertNull(GuestPlaybackHooks.parseItemId("170001+279786+3"))
        assertNull(GuestPlaybackHooks.parseItemId("av170001+279786"))
        assertNull(GuestPlaybackHooks.parseItemId(""))
    }

    @Test fun zeroAndOverflowingIdsAreRejected() {
        assertNull(GuestPlaybackHooks.parseItemId("0+279786"))
        assertNull(GuestPlaybackHooks.parseItemId("170001+0"))
        assertNull(GuestPlaybackHooks.parseItemId("99999999999999999999+1"))
    }
}
