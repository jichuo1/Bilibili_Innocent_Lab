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
    fun `main and secondary activity owners remain active together`() {
        val registry = SkinRenderSessionRegistry()
        val oldActivity = requireNotNull(registry.claim(pending, "owner-old"))
        val newActivity = requireNotNull(registry.claim(pending, "owner-new"))

        assertTrue(registry.isActiveFor(oldActivity, pending))
        assertTrue(registry.isActiveFor(newActivity, pending))
    }

    @Test
    fun `one activity close does not release another activity owner`() {
        val registry = SkinRenderSessionRegistry()
        val oldActivity = requireNotNull(registry.claim(pending, "owner-old"))
        val newActivity = requireNotNull(registry.claim(pending, "owner-new"))

        assertTrue(registry.release(oldActivity))
        assertFalse(registry.isActiveFor(oldActivity, pending))
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
    fun `skin selection invalidates every concurrent activity owner`() {
        val registry = SkinRenderSessionRegistry()
        val main = requireNotNull(registry.claim(pending, "owner-main"))
        val secondary = requireNotNull(registry.claim(pending, "owner-secondary"))

        registry.invalidate()

        assertFalse(registry.isActiveFor(main, pending))
        assertFalse(registry.isActiveFor(secondary, pending))
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
