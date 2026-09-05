package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdvancedCategoryLayoutPolicyTest {
    @Test
    fun `keeps the last control or description immediately before the next heading`() {
        assertEquals(
            listOf(
                AdvancedCategoryChildRange(1, 5),
                AdvancedCategoryChildRange(6, 10),
                AdvancedCategoryChildRange(11, 15),
                AdvancedCategoryChildRange(16, 20)
            ),
            AdvancedCategoryLayoutPolicy.resolve(
                markerIndices = listOf(0, 5, 10, 15),
                childCount = 20
            )
        )
    }

    @Test
    fun `every non heading child belongs to exactly one group for different menu sizes`() {
        for (groupCount in 1..12) {
            val markers = List(groupCount) { it * 4 }
            val childCount = groupCount * 4
            val ranges = requireNotNull(AdvancedCategoryLayoutPolicy.resolve(markers, childCount))
            val moved = ranges.flatMap { (it.startInclusive until it.endExclusive).toList() }
            assertEquals((0 until childCount).filterNot { it in markers }, moved)
            assertEquals(moved.size, moved.distinct().size)
        }
    }

    @Test
    fun `rejects incomplete or unordered marker layout`() {
        assertNull(AdvancedCategoryLayoutPolicy.resolve(emptyList(), childCount = 20))
        assertNull(AdvancedCategoryLayoutPolicy.resolve(listOf(1, 5), childCount = 20))
        assertNull(AdvancedCategoryLayoutPolicy.resolve(listOf(0, 0), childCount = 20))
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
