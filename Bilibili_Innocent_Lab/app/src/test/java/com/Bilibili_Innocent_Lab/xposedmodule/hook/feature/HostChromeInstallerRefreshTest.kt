package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HomeChromeSemanticsPoint
import org.junit.Assert.*
import org.junit.Test

class HostChromeInstallerRefreshTest {
    private fun point(name: String) = HookPoint("host.Home", name, listOf("int"))

    @Test fun `top refresh only accepts new or changed top points`() {
        val top = point("oldTop")
        val installer = HostTopBarFxFeatureInstaller(true, true, null, chromePoints = mapOf("top" to top))
        assertFalse(installer.updateChromePoints(emptyMap()))
        assertFalse(installer.updateChromePoints(mapOf("top" to top, "bottom" to point("bottom"))))
        assertFalse(installer.updateChromePoints(mapOf("bottom_item" to point("item"))))
        val updated = point("newTop")
        assertTrue(installer.updateChromePoints(mapOf("top" to updated)))
        assertFalse(installer.updateChromePoints(mapOf("top" to updated)))
    }
    @Test fun `bottom refresh merges new leaf without forgetting root and ignores top`() {
        val bottom = point("bottom")
        val installer = HostBottomBarFxFeatureInstaller(true, true, null, chromePoints = mapOf("bottom" to bottom))
        assertFalse(installer.updateChromePoints(mapOf("top" to point("top"))))
        val item = point("item")
        assertTrue(installer.updateChromePoints(mapOf("bottom_item" to item)))
        assertFalse(installer.updateChromePoints(mapOf("bottom" to bottom, "bottom_item" to item)))
        val updated = point("updatedItem")
        assertTrue(installer.updateChromePoints(mapOf("bottom_item" to updated)))
        assertFalse(installer.updateChromePoints(mapOf("bottom" to bottom, "bottom_item" to updated)))
        val semantics = HomeChromeSemanticsPoint("host.CanonicalKeys", "description")
        assertTrue(installer.updateChromePoints(emptyMap(), semantics))
        assertFalse(installer.updateChromePoints(mapOf("bottom" to bottom, "bottom_item" to updated), semantics))
        assertFalse(installer.updateChromePoints(emptyMap()))
    }
}
