package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerPortraitFeatureInstallerTest {

    @Test
    fun `forces every host visibility request to gone`() {
        assertEquals(View.GONE, PlayerPortraitFeatureInstaller.hiddenVisibility(View.VISIBLE))
        assertEquals(View.GONE, PlayerPortraitFeatureInstaller.hiddenVisibility(View.INVISIBLE))
        assertEquals(View.GONE, PlayerPortraitFeatureInstaller.hiddenVisibility(View.GONE))
    }
}
