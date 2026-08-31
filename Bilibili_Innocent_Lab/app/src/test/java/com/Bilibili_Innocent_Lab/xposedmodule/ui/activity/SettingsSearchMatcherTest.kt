package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchMatcherTest {

    private val items = listOf(
        SettingsSearchItem("portrait", "竖屏内容过滤", "选择需要过滤的内容", "进阶设置"),
        SettingsSearchItem("backup", "设置备份与恢复", "导出或导入备份", "基础设置"),
        SettingsSearchItem("duplicate", "竖屏内容过滤", "重复入口", "实验性功能")
    )

    @Test
    fun `multiple tokens must all match localized text`() {
        assertEquals(
            listOf("portrait"),
            SettingsSearchMatcher.search("竖屏 过滤", items).map { it.key }
        )
        assertTrue(SettingsSearchMatcher.search("竖屏 备份", items).isEmpty())
    }

    @Test
    fun `title matches rank ahead of detail matches and duplicates collapse`() {
        val results = SettingsSearchMatcher.search("设置", items)

        assertEquals("backup", results.first().key)
        assertEquals(results.map { it.title }.distinct(), results.map { it.title })
    }

    @Test
    fun `blank query and zero limit return no result`() {
        assertTrue(SettingsSearchMatcher.search("  ", items).isEmpty())
        assertTrue(SettingsSearchMatcher.search("设置", items, limit = 0).isEmpty())
    }
}
