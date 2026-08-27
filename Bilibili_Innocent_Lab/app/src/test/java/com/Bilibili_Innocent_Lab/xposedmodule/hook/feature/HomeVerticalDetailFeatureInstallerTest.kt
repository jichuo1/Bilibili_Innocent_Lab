package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeVerticalDetailFeatureInstallerTest {

    @Test
    fun `rewrites only story scheme to normal video details`() {
        assertEquals(
            "bilibili://video/BV1TEST?from=feed",
            HomeVerticalDetailFeatureInstaller.rewriteStoryUri(
                "bilibili://story/BV1TEST?from=feed"
            )
        )
        assertNull(
            HomeVerticalDetailFeatureInstaller.rewriteStoryUri(
                "bilibili://video/BV1TEST"
            )
        )
    }
}
