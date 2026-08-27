package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentFilterFeatureInstallerTest {

    @Test
    fun `filters exact keyword containment case insensitively`() {
        val rules = RuleSetCodec.parse("剧透，Spoiler")

        assertTrue(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals("这条含有剧透", 6),
                rules,
                null
            )
        )
        assertTrue(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals("SPOILER warning", 6),
                rules,
                null
            )
        )
        assertFalse(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals("正常评论", 6),
                rules,
                null
            )
        )
    }

    @Test
    fun `removes only known levels below threshold and fails open when unreadable`() {
        assertTrue(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals(level = 2),
                emptySet(),
                3
            )
        )
        assertFalse(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals(level = 3),
                emptySet(),
                3
            )
        )
        assertFalse(
            CommentFilterFeatureInstaller.shouldRemove(
                CommentFilterFeatureInstaller.Signals(level = null),
                emptySet(),
                3
            )
        )
    }

    @Test
    fun `returns original list when unchanged and immutable copy when filtered`() {
        val source = listOf("keep", "drop", "keep-2")
        val unchanged = CommentFilterFeatureInstaller.filterComments(source) { false }
        val filtered = CommentFilterFeatureInstaller.filterComments(source) { it == "drop" }

        assertSame(source, unchanged)
        assertEquals(listOf("keep", "keep-2"), filtered)
        assertEquals(source, listOf("keep", "drop", "keep-2"))
    }
}
