package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareLinkPurifierTest {

    @Test
    fun `drops tracking parameters and keeps playback position`() {
        assertEquals(
            "https://www.bilibili.com/video/BV1xx411c7mD?p=2&t=90",
            ShareLinkPurifier.purifyUrl(
                "https://www.bilibili.com/video/BV1xx411c7mD" +
                    "?p=2&t=90&share_source=copy_web&vd_source=deadbeef&spm_id_from=333.999"
            )
        )
    }

    @Test
    fun `converts millisecond progress into the seconds parameter`() {
        assertEquals(
            "https://b23.tv/abcdefg?t=95",
            ShareLinkPurifier.purifyUrl("https://b23.tv/abcdefg?start_progress=95400&bbid=xyz")
        )
    }

    @Test
    fun `returns null when there is nothing to clean`() {
        assertNull(ShareLinkPurifier.purifyUrl("https://www.bilibili.com/video/BV1xx411c7mD"))
        assertNull(ShareLinkPurifier.purifyUrl("https://www.bilibili.com/video/BV1xx411c7mD?p=1"))
    }

    @Test
    fun `leaves third party and non http links untouched`() {
        assertNull(ShareLinkPurifier.purifyUrl("https://example.com/watch?utm_source=bili"))
        assertNull(ShareLinkPurifier.purifyUrl("bilibili://video/1?share_source=copy"))
        // 后缀相同但并非同一站点，不能误判成 B 站域名。
        assertNull(ShareLinkPurifier.purifyUrl("https://notbilibili.com/x?share_source=copy"))
    }

    @Test
    fun `keeps subdomains and strips the port only for host matching`() {
        assertEquals(
            "https://m.bilibili.com/video/BV1xx411c7mD",
            ShareLinkPurifier.purifyUrl(
                "https://m.bilibili.com/video/BV1xx411c7mD?share_medium=android"
            )
        )
    }

    @Test
    fun `preserves the fragment while rewriting the query`() {
        assertEquals(
            "https://www.bilibili.com/read/cv1?t=3#reply",
            ShareLinkPurifier.purifyUrl("https://www.bilibili.com/read/cv1?t=3&bbid=1#reply")
        )
    }

    @Test
    fun `purifies every bilibili link inside share text`() {
        val text = "看看这个 https://b23.tv/abc?share_source=copy_web 还有 https://example.com/a?x=1"
        assertEquals(
            "看看这个 https://b23.tv/abc 还有 https://example.com/a?x=1",
            ShareLinkPurifier.purifyText(text)
        )
    }

    @Test
    fun `share text without tracked links is left alone`() {
        assertNull(ShareLinkPurifier.purifyText("纯文本，没有链接"))
        assertNull(ShareLinkPurifier.purifyText("https://example.com/a?utm=1"))
    }

    @Test
    fun `purifying twice is stable`() {
        val once = requireNotNull(
            ShareLinkPurifier.purifyUrl("https://b23.tv/abc?share_source=copy_web&t=5")
        )
        assertEquals("https://b23.tv/abc?t=5", once)
        assertNull(ShareLinkPurifier.purifyUrl(once))
    }
}
