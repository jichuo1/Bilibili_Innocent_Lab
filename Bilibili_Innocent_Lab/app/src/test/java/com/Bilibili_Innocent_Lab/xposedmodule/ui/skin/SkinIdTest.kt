package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SkinId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkinIdTest {

    @Test
    fun `storage values are stable protocol identifiers`() {
        assertEquals("material_you", SkinId.MATERIAL_YOU.storageValue)
        assertEquals("liquid_v1", SkinId.LIQUID.storageValue)
        assertEquals(
            SkinId.entries.size,
            SkinId.entries.map(SkinId::storageValue).distinct().size
        )
    }

    @Test
    fun `storage value parsing is exact and rejects unknown aliases`() {
        SkinId.entries.forEach { skin ->
            assertEquals(skin, SkinId.fromStorageValue(skin.storageValue))
        }
        listOf(null, "", "MATERIAL_YOU", "liquid", "LIQUID_V1").forEach { raw ->
            assertNull("raw=$raw", SkinId.fromStorageValue(raw))
        }
    }
}
