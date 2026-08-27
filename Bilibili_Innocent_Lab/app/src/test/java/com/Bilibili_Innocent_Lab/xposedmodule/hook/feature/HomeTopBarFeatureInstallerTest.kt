package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.bilibili.lib.homepage.startdust.menu.a
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTopBarFeatureInstallerTest {

    @Test
    fun `recognizes only exact game center action from adapted config field`() {
        assertTrue(
            HomeTopBarFeatureInstaller.hasGameMenuAction(
                a("action://game_center/home/menu?from=home"),
                "config"
            )
        )
        assertFalse(
            HomeTopBarFeatureInstaller.hasGameMenuAction(
                a("action://search/home/menu"),
                "config"
            )
        )
        assertFalse(HomeTopBarFeatureInstaller.hasGameMenuAction(a(), "missing"))
    }
}
