package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundConfigCodec
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundConfigIssue
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidBackgroundConfigCodecTest {

    @Test
    fun `empty preferences use clean automatic background`() {
        val decoded = LiquidBackgroundConfigCodec.decode(emptyMap<String, Any>())

        assertEquals(LiquidBackgroundConfig.AUTOMATIC, decoded.config)
        assertEquals(LiquidBackgroundConfigIssue.NONE, decoded.issue)
        assertFalse(decoded.needsRepair)
    }

    @Test
    fun `custom config round trips without external path`() {
        val config = customConfig()

        val encoded = LiquidBackgroundConfigCodec.encode(config)
        val decoded = LiquidBackgroundConfigCodec.decode(encoded)

        assertEquals(config, decoded.config)
        assertFalse(decoded.needsRepair)
        assertFalse(encoded.keys.any { it.contains("uri", ignoreCase = true) })
        assertFalse(encoded.keys.any { it.contains("path", ignoreCase = true) })
    }

    @Test
    fun `custom mode without asset repairs to automatic`() {
        val decoded = LiquidBackgroundConfigCodec.decode(
            mapOf(
                LiquidBackgroundConfigCodec.KEY_SCHEMA_VERSION to 1,
                LiquidBackgroundConfigCodec.KEY_MODE to LiquidBackgroundMode.CUSTOM.name
            )
        )

        assertEquals(LiquidBackgroundConfig.AUTOMATIC, decoded.config)
        assertEquals(LiquidBackgroundConfigIssue.TYPE_MISMATCH, decoded.issue)
        assertTrue(decoded.needsRepair)
    }

    @Test
    fun `future schema does not enable broken custom background`() {
        val values = LiquidBackgroundConfigCodec.encode(customConfig()).toMutableMap()
        values[LiquidBackgroundConfigCodec.KEY_SCHEMA_VERSION] = 99

        val decoded = LiquidBackgroundConfigCodec.decode(values)

        assertEquals(LiquidBackgroundConfig.AUTOMATIC, decoded.config)
        assertEquals(LiquidBackgroundConfigIssue.MISSING_OR_INVALID_SCHEMA, decoded.issue)
        assertTrue(decoded.needsRepair)
    }

    @Test
    fun `automatic mode removes stale custom fields`() {
        val values = LiquidBackgroundConfigCodec.encode(customConfig()).toMutableMap()
        values[LiquidBackgroundConfigCodec.KEY_MODE] = LiquidBackgroundMode.AUTOMATIC.name

        val decoded = LiquidBackgroundConfigCodec.decode(values)

        assertEquals(LiquidBackgroundConfig.AUTOMATIC, decoded.config)
        assertTrue(decoded.needsRepair)
    }

    private fun customConfig() = LiquidBackgroundConfig(
        mode = LiquidBackgroundMode.CUSTOM,
        assetId = "asset-123",
        assetSha256 = "ab".repeat(32),
        normalizedWidth = 1600,
        normalizedHeight = 900,
        displayName = "background.webp"
    )
}
