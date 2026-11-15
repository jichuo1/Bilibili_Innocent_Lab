package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleFreeCopyPolicyTest {
    private val widget = "tv.danmaku.bili.videopage.common.widget.view.ExpandableTextView"
    private val legacyWidget = "com.mall.videodetail.vd.videopage.common.widget.view.ExpandableTextView"

    @Test fun `title needs the id, the detail widget class and the arrow sibling all at once`() {
        val chain = listOf(widget, "tv.danmaku.bili.videopage.common.widget.view.a", "androidx.appcompat.widget.AppCompatTextView")
        assertTrue(TitleFreeCopyPolicy.isTitleWidget(true, chain, true))
        // id/title 是宿主里极常见的资源名：缺任何一项都不能当成标题。
        assertFalse(TitleFreeCopyPolicy.isTitleWidget(false, chain, true))
        assertFalse(TitleFreeCopyPolicy.isTitleWidget(true, chain, false))
        assertFalse(TitleFreeCopyPolicy.isTitleWidget(true, listOf("android.widget.TextView"), true))
        assertFalse(TitleFreeCopyPolicy.isTitleWidget(true, emptyList(), true))
    }

    @Test fun `other expandable text views in the host are not the video title widget`() {
        for (name in listOf(
            "com.bilibili.ship.theseus.cheese.widget.ExpandableTextView",
            "com.bilibili.app.comm.comment2.widget.CommentExpandableTextView",
            "com.bilibili.app.comment3.ui.widget.ExpandableTextView",
            "com.bilibili.column.ui.widget.ExpandableTextView",
            "com.bilibili.ogv.misc.roledetail.ExpandableTextView"
        )) {
            assertFalse(name, TitleFreeCopyPolicy.isTitleWidget(true, listOf(name), true))
        }
    }

    @Test fun `a host subclass of the widget still counts and both known package names are accepted`() {
        assertTrue(TitleFreeCopyPolicy.isTitleWidget(true, listOf("x.y.Sub", widget), true))
        assertTrue(TitleFreeCopyPolicy.isTitleWidget(true, listOf(legacyWidget), true))
        assertEquals(listOf(widget, legacyWidget), TitleFreeCopyPolicy.WIDGET_CLASS_NAMES)
    }

    @Test fun `resource names match what the host layout declares`() {
        // theseus_module_ugc_headline_layout 里的两个 id（8.84.0～9.19.0 逐版核对）。
        assertEquals("title", TitleFreeCopyPolicy.VIEW_ID_NAME)
        assertEquals("arrow", TitleFreeCopyPolicy.ARROW_ID_NAME)
    }

    @Test fun `the original text field is the smallest named CharSequence field whatever the names are`() {
        // 三种在宿主里实际出现过的混淆名；dex 按名字排序存放字段，原文恒为名字最小的那个。
        assertEquals(0, TitleFreeCopyPolicy.originFieldIndex(listOf("l", "m", "n")))
        assertEquals(0, TitleFreeCopyPolicy.originFieldIndex(listOf("r", "s", "t")))
        assertEquals(0, TitleFreeCopyPolicy.originFieldIndex(listOf("m", "n", "o")))
        // 不依赖反射返回字段的先后顺序。
        assertEquals(1, TitleFreeCopyPolicy.originFieldIndex(listOf("n", "l", "m")))
        assertEquals(2, TitleFreeCopyPolicy.originFieldIndex(listOf("o", "n", "m")))
        assertEquals(0, TitleFreeCopyPolicy.originFieldIndex(listOf("x")))
        assertNull(TitleFreeCopyPolicy.originFieldIndex(emptyList()))
    }

    @Test fun `choose prefers the original text when the visible text is its collapsed or expanded form`() {
        val full = "这是一个很长很长的视频标题用来测试收起时被截断的情况"
        // 收起态：原文前缀 + 省略 + 展开提示
        assertEquals(full, TitleFreeCopyPolicy.choose(full, "这是一个很长很长的视频…展开"))
        // 展开态：原文 + 收起提示
        assertEquals(full, TitleFreeCopyPolicy.choose(full, full + "收起"))
        // 短标题：可见文本就是原文
        assertEquals("短标题", TitleFreeCopyPolicy.choose("短标题", "短标题"))
    }

    @Test fun `choose falls back to the visible text when the guessed field holds something else`() {
        // 猜错字段（里面是别的文本）时，复制眼前看到的，也不复制错内容。
        assertEquals("真正的标题", TitleFreeCopyPolicy.choose("完全不相干的另一段文本", "真正的标题"))
    }

    @Test fun `choose handles a missing side`() {
        assertEquals("可见", TitleFreeCopyPolicy.choose("", "可见"))
        assertEquals("原文", TitleFreeCopyPolicy.choose("原文", ""))
        assertEquals("", TitleFreeCopyPolicy.choose("", ""))
    }

    @Test fun `normalize keeps a plain title untouched`() {
        assertEquals("【4K】这是一个标题", TitleFreeCopyPolicy.normalize("【4K】这是一个标题"))
        // 行内的连续空格是标题的一部分，不能被压缩。
        assertEquals("A  B", TitleFreeCopyPolicy.normalize("A  B"))
    }

    @Test fun `normalize drops the badge placeholder blanks and zero width characters`() {
        // 宿主在标题前放 4 个空格给角标图片；ReplacementSpan 剔除后可能剩下空白或零宽字符。
        assertEquals("标题", TitleFreeCopyPolicy.normalize("    标题"))
        assertEquals("标题", TitleFreeCopyPolicy.normalize("标题  \n"))
        assertEquals("标题", TitleFreeCopyPolicy.normalize("\u200B\uFEFF标\u2060题\u200B"))
    }

    @Test fun `normalize folds stray line breaks into one line and unifies CRLF`() {
        assertEquals("上 下", TitleFreeCopyPolicy.normalize("上\r\n下"))
        assertEquals("上 下", TitleFreeCopyPolicy.normalize("上\r下"))
        assertEquals("上 下", TitleFreeCopyPolicy.normalize("上\n\n \n下"))
    }

    @Test fun `normalize returns an empty string when nothing copyable is left`() {
        assertEquals("", TitleFreeCopyPolicy.normalize(""))
        assertEquals("", TitleFreeCopyPolicy.normalize("   \n \r\n"))
        assertEquals("", TitleFreeCopyPolicy.normalize("\u200B\u200B"))
    }

    @Test fun `normalize caps the length`() {
        val long = "字".repeat(TitleFreeCopyPolicy.MAX_TITLE_LENGTH + 50)
        assertEquals(TitleFreeCopyPolicy.MAX_TITLE_LENGTH, TitleFreeCopyPolicy.normalize(long).length)
    }
}
