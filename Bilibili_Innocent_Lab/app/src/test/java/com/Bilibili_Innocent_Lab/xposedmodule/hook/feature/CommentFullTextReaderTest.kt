package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.bilibili.app.comment3.ui.nextwidget.NextLikableTextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class CommentFullTextReaderTest {
    @Test fun readsCurrentFullSourceAfterViewReuseWithoutKeepingOldValue() {
        val reader = CommentFullTextReader()
        val view = NextLikableTextView("昵称: 完整正文[dog]")
        assertEquals("昵称: 完整正文[dog]", reader.read(view))
        view.rebind("另一位: 另一条完整回复")
        assertEquals("另一位: 另一条完整回复", reader.read(view))
        view.rebind("")
        assertNull(reader.read(view))
    }
    @Test fun unknownControlsAndUnboundedContentDoNotBecomeFullSources() {
        val reader = CommentFullTextReader()
        assertNull(reader.read(Any()))
        assertNull(reader.read(NextLikableTextView("x".repeat(6001))))
    }
    @Test fun hostNicknameClickContractIsCheckedOnEveryInteraction() {
        val reader = CommentFullTextReader()
        assertTrue(reader.isNicknameSpan(f00.u(true)))
        assertFalse(reader.isNicknameSpan(f00.u(false)))
        assertFalse(reader.isNicknameSpan(Any()))
    }
}
