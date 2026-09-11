package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 折叠账本的行为约束。
 *
 * 这一层单独测是因为：隐藏 RecyclerView 的 item 根必须把尺寸归零
 * （`GONE` 不收缩那一格），而视图会被**回收复用**——
 * 归零之后还不回去，就会把别的内容压成一条永久空白项，
 * 比"留一格空白"这个原始问题更糟，而且在真机上很难归因。
 */
class ItemCollapseBookTest {

    /** 可直接断言的假 item：真 `View` 在单测里是会抛的桩（工程没有 Robolectric）。 */
    private class FakeBox(
        override var height: Int = 175,
        override var topMargin: Int = 12,
        override var bottomMargin: Int = 8,
        override var gone: Boolean = false
    ) : ItemBox {
        override val key: Any get() = this
        fun snapshot() = listOf(height, topMargin, bottomMargin, if (gone) 1 else 0)
    }

    private val book = ItemCollapseBook()

    @Test fun `collapsing zeroes the height and the vertical margins as well as hiding`() {
        val box = FakeBox()
        assertTrue(book.collapse(box))
        assertEquals(listOf(0, 0, 0, 1), box.snapshot())
        assertTrue(book.tracks(box))
    }

    /** 折叠前的尺寸要原样回来，包括 `WRAP_CONTENT` 这种负数常量。 */
    @Test fun `restoring brings back the exact original geometry`() {
        val wrapContent = -2
        val box = FakeBox(height = wrapContent, topMargin = 30, bottomMargin = 4)
        book.collapse(box)
        assertTrue(book.restore(box))
        assertEquals(listOf(wrapContent, 30, 4, 0), box.snapshot())
        assertFalse(book.tracks(box))
    }

    /**
     * 最危险的一条：重复折叠**不许**重新记账。
     *
     * 若第二次折叠把已经归零的值记成"原始尺寸"，还原就永远回不到 175，
     * 这一项会永久空白。
     */
    @Test fun `collapsing twice reapplies collapsed geometry but keeps the first recorded geometry`() {
        val box = FakeBox()
        assertTrue(book.collapse(box))
        // RecyclerView 复用/绑定时宿主可能把 LayoutParams 写回原值；第二次命中要压平它们，
        // 但绝不能把这些回填后的值当作新的原始尺寸记账。
        box.height = 244
        box.topMargin = 17
        box.bottomMargin = 9
        // 复现现场：宿主已保留 GONE，只是把占位尺寸写回了非零值。
        box.gone = true
        assertFalse("第二次折叠不是一次新的隐藏", book.collapse(box))
        assertEquals(listOf(0, 0, 0, 1), box.snapshot())
        book.restore(box)
        assertEquals(listOf(175, 12, 8, 0), box.snapshot())
    }

    /** 回收复用 → 还原 → 又命中 → 再折叠，这条来回路径必须收敛。 */
    @Test fun `a recycled box survives repeated collapse and restore cycles`() {
        val box = FakeBox()
        repeat(3) {
            assertTrue(book.collapse(box))
            assertEquals(listOf(0, 0, 0, 1), box.snapshot())
            assertTrue(book.restore(box))
            assertEquals(listOf(175, 12, 8, 0), box.snapshot())
        }
    }

    /** 没记录就什么都不动：绝大多数子项走这条路径，不能被误改。 */
    @Test fun `restoring an untracked box changes nothing`() {
        val box = FakeBox()
        assertFalse(book.tracks(box))
        assertFalse(book.restore(box))
        assertEquals(listOf(175, 12, 8, 0), box.snapshot())
    }

    /** 宿主自己就把它藏了的话，还原时不能"顺手"帮它变可见。 */
    @Test fun `restoring keeps a host hidden item hidden`() {
        val box = FakeBox(gone = true)
        book.collapse(box)
        book.restore(box)
        assertTrue("宿主的可见性不该被我们改成可见", box.gone)
        assertEquals(175, box.height)
    }

    /** 账本按身份记，不同 item 各记各的。 */
    @Test fun `each box is tracked independently`() {
        val banner = FakeBox(height = 175)
        val other = FakeBox(height = 96)
        book.collapse(banner)
        assertTrue(book.tracks(banner))
        assertFalse(book.tracks(other))
        assertEquals(96, other.height)
        book.restore(banner)
        assertEquals(175, banner.height)
    }
}
