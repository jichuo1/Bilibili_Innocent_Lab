package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.junit.Assert.*
import org.junit.Test

class GuestHistoryBannerTest {
    class Banner
    class OneBannerPage { @JvmField var n: Banner? = null; @JvmField var title: String = "" }
    class TwoBannerPage { @JvmField var n: Banner? = null; @JvmField var t: Banner? = null }
    class StaticBannerPage { companion object { @JvmField var n: Banner? = null } }

    @Test fun historyBannerIsTheSingleInstanceFieldOfTheBannerType() {
        assertEquals("n", GuestNativeHistoryHooks.bannerField(OneBannerPage::class.java, Banner::class.java)?.name)
        assertNull(GuestNativeHistoryHooks.bannerField(TwoBannerPage::class.java, Banner::class.java))  // ambiguity is never guessed
        assertNull(GuestNativeHistoryHooks.bannerField(StaticBannerPage::class.java, Banner::class.java))
        assertNull(GuestNativeHistoryHooks.bannerField(OneBannerPage::class.java, null))               // type absent on this build
    }
}
