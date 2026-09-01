package com.Bilibili_Innocent_Lab.xposedmodule.ui.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseNotesLinkPolicyTest {

    @Test
    fun `allows normalized https links without credentials`() {
        assertEquals(
            "https://github.com/jichuo1/Bilibili_Innocent_Lab/issues/12",
            ReleaseNotesLinkPolicy.resolveDestination(
                "https://github.com/jichuo1/Bilibili_Innocent_Lab/issues/12"
            )
        )
        assertEquals(
            "https://example.com:443/docs?q=1#part",
            ReleaseNotesLinkPolicy.resolveDestination("https://EXAMPLE.com:443/docs?q=1#part")
        )
    }

    @Test
    fun `resolves repository relative and github root links`() {
        assertEquals(
            "https://github.com/jichuo1/Bilibili_Innocent_Lab/issues/7",
            ReleaseNotesLinkPolicy.resolveDestination("issues/7")
        )
        assertEquals(
            "https://github.com/jichuo1/Bilibili_Innocent_Lab/releases/tag/v1.1.2",
            ReleaseNotesLinkPolicy.resolveDestination(
                "/jichuo1/Bilibili_Innocent_Lab/releases/tag/v1.1.2"
            )
        )
    }

    @Test
    fun `rejects active content local schemes credentials and nonstandard ports`() {
        assertNull(ReleaseNotesLinkPolicy.resolveDestination("javascript:alert(1)"))
        assertNull(ReleaseNotesLinkPolicy.resolveDestination("intent://host/#Intent;end"))
        assertNull(ReleaseNotesLinkPolicy.resolveDestination("file:///data/local/tmp/a"))
        assertNull(ReleaseNotesLinkPolicy.resolveDestination("https://user@example.com/path"))
        assertNull(ReleaseNotesLinkPolicy.resolveDestination("https://example.com:8443/path"))
        assertNull(ReleaseNotesLinkPolicy.resolveDestination("https://example.com/path\\escape"))
    }
}
