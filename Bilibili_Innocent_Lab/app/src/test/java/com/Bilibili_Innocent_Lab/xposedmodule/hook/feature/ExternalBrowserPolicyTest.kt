package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalBrowserPolicyTest {

    private val webActivity = "com.bilibili.lib.biliweb.MWebActivity"

    @Test
    fun `only the in-app web activity is redirected`() {
        assertTrue(
            ExternalBrowserPolicy.shouldOpenExternally(webActivity, "https", "example.com")
        )
        assertFalse(
            ExternalBrowserPolicy.shouldOpenExternally(
                "com.bilibili.video.videodetail.VideoDetailsActivity",
                "https",
                "example.com"
            )
        )
        assertFalse(ExternalBrowserPolicy.shouldOpenExternally(null, "https", "example.com"))
    }

    @Test
    fun `bilibili sites and payment gateways stay in the app`() {
        listOf(
            "www.bilibili.com",
            "passport.bilibili.com",
            "b23.tv",
            "m.bilibili.tv",
            "i0.hdslb.com",
            "mclient.alipay.com",
            "wx.tenpay.com",
            "cashier.95516.com"
        ).forEach { host ->
            assertFalse(host, ExternalBrowserPolicy.shouldOpenExternally(webActivity, "https", host))
        }
    }

    @Test
    fun `look-alike domains are not treated as bilibili`() {
        assertTrue(
            ExternalBrowserPolicy.shouldOpenExternally(webActivity, "https", "notbilibili.com")
        )
        assertTrue(
            ExternalBrowserPolicy.shouldOpenExternally(webActivity, "https", "bilibili.com.evil.cn")
        )
    }

    @Test
    fun `non web schemes and empty hosts are never redirected`() {
        assertFalse(ExternalBrowserPolicy.shouldOpenExternally(webActivity, "bilibili", "video"))
        assertFalse(ExternalBrowserPolicy.shouldOpenExternally(webActivity, "file", "example.com"))
        assertFalse(ExternalBrowserPolicy.shouldOpenExternally(webActivity, null, "example.com"))
        assertFalse(ExternalBrowserPolicy.shouldOpenExternally(webActivity, "https", null))
        assertFalse(ExternalBrowserPolicy.shouldOpenExternally(webActivity, "https", ""))
    }

    @Test
    fun `host matching ignores case port and trailing dot`() {
        assertFalse(
            ExternalBrowserPolicy.shouldOpenExternally(webActivity, "HTTPS", "WWW.BILIBILI.COM")
        )
        assertFalse(
            ExternalBrowserPolicy.shouldOpenExternally(webActivity, "https", "www.bilibili.com:443")
        )
        assertFalse(
            ExternalBrowserPolicy.shouldOpenExternally(webActivity, "https", "www.bilibili.com.")
        )
    }
}
