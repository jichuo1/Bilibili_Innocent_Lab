package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupLazyGroupTest {

    @Test
    fun collapsedGroupDefersEntryViewConstructionUntilFirstExpandAndIsIdempotent() {
        val built = ArrayList<Pair<Int, String>>()
        val group = LazyEntryGroupState(listOf("alpha", "beta", "gamma")) { index, item ->
            built += index to item
        }

        // 默认收起期间不构建任何条目子视图。
        assertFalse(group.populated)
        assertTrue(built.isEmpty())

        // 首次点击展开时按序懒构建全部条目。
        assertTrue(group.ensurePopulated())
        assertTrue(group.populated)
        assertEquals(listOf(0 to "alpha", 1 to "beta", 2 to "gamma"), built)

        // 折叠再展开时不再重复构建子视图。
        assertFalse(group.ensurePopulated())
        assertEquals(3, built.size)
    }

    @Test
    fun emptyGroupRemainsUnpopulatedWithoutInvokingBuilder() {
        var calls = 0
        val emptyGroup = LazyEntryGroupState(emptyList<String>()) { _, _ -> calls++ }
        assertFalse(emptyGroup.ensurePopulated())
        assertFalse(emptyGroup.populated)
        assertEquals(0, calls)
    }

    @Test
    fun containerMorphDelegateGeometryHelpersTranslateAndValidateBounds() {
        val screenRect = SettingsBackupMotionRect(left = 120f, top = 260f, right = 420f, bottom = 360f)
        val local = ContainerMorphActivityDelegate.toLocal(screenRect, intArrayOf(20, 60))
        assertEquals(100f, local.left, 0.01f)
        assertEquals(200f, local.top, 0.01f)
        assertEquals(400f, local.right, 0.01f)
        assertEquals(300f, local.bottom, 0.01f)

        assertTrue(ContainerMorphActivityDelegate.isWithinWindow(local, hostWidth = 1080f, hostHeight = 2400f, tolerancePx = 8))
        val outOfBounds = SettingsBackupMotionRect(left = -30f, top = 0f, right = 400f, bottom = 300f)
        assertFalse(ContainerMorphActivityDelegate.isWithinWindow(outOfBounds, hostWidth = 1080f, hostHeight = 2400f, tolerancePx = 8))
    }
}
