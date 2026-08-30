package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinPreferenceState
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinRenderSessionRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinRenderSessionRegistryTest {

    private val pending = SkinPreferenceState.pendingLiquid(
        rendererVersion = 7,
        activationAttemptId = "activation-a"
    )

    @Test
    fun `new activity owner makes old success callback stale`() {
        val registry = SkinRenderSessionRegistry()
        val oldActivity = requireNotNull(registry.claim(pending, "owner-old"))
        val newActivity = requireNotNull(registry.claim(pending, "owner-new"))

        assertFalse(registry.isActiveFor(oldActivity, pending))
        assertTrue(registry.isActiveFor(newActivity, pending))
    }

    @Test
    fun `old activity close cannot release new activity owner`() {
        val registry = SkinRenderSessionRegistry()
        val oldActivity = requireNotNull(registry.claim(pending, "owner-old"))
        val newActivity = requireNotNull(registry.claim(pending, "owner-new"))

        assertFalse(registry.release(oldActivity))
        assertTrue(registry.isActiveFor(newActivity, pending))
    }

    @Test
    fun `closed current owner makes its late failure stale`() {
        val registry = SkinRenderSessionRegistry()
        val owner = requireNotNull(registry.claim(pending, "owner-current"))

        assertTrue(registry.release(owner))
        assertFalse(registry.isActiveFor(owner, pending))
    }

    @Test
    fun `material state never grants liquid renderer ownership`() {
        val registry = SkinRenderSessionRegistry()

        assertNull(
            registry.claim(
                SkinPreferenceState.MATERIAL_DEFAULT,
                "owner-material"
            )
        )
    }
}
