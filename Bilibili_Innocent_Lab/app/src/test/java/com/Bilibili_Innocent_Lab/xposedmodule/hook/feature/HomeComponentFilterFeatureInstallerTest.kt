package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeComponentFilterFeatureInstallerTest {

    @Test
    fun `matches only bilibili home component class candidates`() {
        assertTrue(
            HomeComponentFilterFeatureInstaller.isClassMatched(
                "popularfragment",
                "com.bilibili.app.home.PopularFragment"
            )
        )
        assertFalse(
            HomeComponentFilterFeatureInstaller.isClassMatched(
                "detail",
                "com.bilibili.ship.theseus.detail.UnitedBizDetailsFragment"
            )
        )
        assertFalse(
            HomeComponentFilterFeatureInstaller.isClassMatched(
                "popularfragment",
                "third.party.PopularFragment"
            )
        )
    }
}
