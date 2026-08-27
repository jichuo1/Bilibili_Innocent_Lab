package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRelateFilterFeatureInstallerTest {

    @Test
    fun `normalizes protobuf card type prefix and matches exact type`() {
        assertTrue(
            VideoRelateFilterFeatureInstaller.shouldRemove(
                "CARD_TYPE_GAME",
                setOf("game")
            )
        )
        assertFalse(
            VideoRelateFilterFeatureInstaller.shouldRemove(
                "BANGUMI_AV",
                setOf("AV")
            )
        )
    }
}
