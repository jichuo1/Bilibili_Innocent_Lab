package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentPurifyFeatureInstallerTest {

    private data class UrlFixture(
        private val appUrl: String,
        private val displayText: String
    )

    @Test
    fun `keeps original map when no search destination exists`() {
        val source = linkedMapOf("topic" to "bilibili://topic/1")

        val filtered = CommentPurifyFeatureInstaller.withoutSearchUrls(source) {
            it.startsWith("bilibili://search")
        }

        assertSame(source, filtered)
    }

    @Test
    fun `removes only search entries and returns immutable ordered copy`() {
        val source = linkedMapOf(
            "search" to "bilibili://search?keyword=test",
            "video" to "bilibili://video/BV1",
            "web" to "https://example.com"
        )

        val filtered = CommentPurifyFeatureInstaller.withoutSearchUrls(source) {
            it.startsWith("bilibili://search")
        }

        assertEquals(listOf("video", "web"), filtered.keys.toList())
        assertFalse(filtered.containsKey("search"))
        assertEquals(3, source.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (filtered as MutableMap<String, String>)["new"] = "value"
        }
    }

    @Test
    fun `detects search uri from cached private text fields only`() {
        val installer = CommentPurifyFeatureInstaller(removeSearchLinks = true, points = null)

        assertTrue(installer.isSearchUrlValue(UrlFixture("bilibili://search?q=test", "test")))
        assertFalse(installer.isSearchUrlValue(UrlFixture("bilibili://video/BV1", "search")))
        assertFalse(installer.isSearchUrlValue(null))
    }
}
