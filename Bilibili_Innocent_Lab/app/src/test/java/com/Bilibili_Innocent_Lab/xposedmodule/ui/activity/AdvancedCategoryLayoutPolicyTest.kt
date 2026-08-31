package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdvancedCategoryLayoutPolicyTest {
    @Test
    fun `splits four categories and excludes legacy separators`() {
        assertEquals(
            listOf(
                AdvancedCategoryChildRange(1, 4),
                AdvancedCategoryChildRange(6, 9),
                AdvancedCategoryChildRange(11, 14),
                AdvancedCategoryChildRange(16, 20)
            ),
            AdvancedCategoryLayoutPolicy.resolve(
                markerIndices = listOf(0, 5, 10, 15),
                childCount = 20
            )
        )
    }

    @Test
    fun `rejects incomplete or unordered marker layout`() {
        assertNull(AdvancedCategoryLayoutPolicy.resolve(emptyList(), childCount = 20))
        assertNull(
            AdvancedCategoryLayoutPolicy.resolve(
                markerIndices = listOf(0, 10, 5, 15),
                childCount = 20
            )
        )
        assertNull(
            AdvancedCategoryLayoutPolicy.resolve(
                markerIndices = listOf(0, 5, 10, 20),
                childCount = 20
            )
        )
    }
}
