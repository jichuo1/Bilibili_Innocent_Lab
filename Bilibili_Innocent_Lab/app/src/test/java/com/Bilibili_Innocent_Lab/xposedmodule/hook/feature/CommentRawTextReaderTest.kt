package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommentRawTextReaderTest {
    private class Header { fun z() = HeaderRich() }
    private class HeaderRich { fun e() = 42 }
    private class Legacy(val raw: String) { fun z() = LegacyRich(raw) }
    private class LegacyRich(private val text: String) { fun e() = text }
    private class Modern(val raw: String) { fun f() = ModernRich(raw) }
    private class ModernRich(@JvmField val a: String)
    private class OtherModern(val raw: String) { fun f() = OtherRich(raw) }
    private class OtherRich(private val text: String) { fun getRaw() = text }
    private class Fallback { fun f() = HeaderRich(); fun z() = LegacyRich("原文[大哭]") }

    @Test fun mixedHeaderCannotPoisonFollowingCommentMembers() {
        val reader = CommentRawTextReader()
        assertNull(reader.read(Header(), modern = false))
        assertEquals("评论[大哭][大哭][大哭]", reader.read(Legacy("评论[大哭][大哭][大哭]"), modern = false))
        assertNull(reader.read(Header(), modern = false))
        assertEquals("另一条[dog]", reader.read(Legacy("另一条[dog]"), modern = false))
    }

    @Test fun modernAndLegacyModelsKeepIndependentPlans() {
        val reader = CommentRawTextReader()
        repeat(2) {
            assertEquals("新版[dog]", reader.read(Modern("新版[dog]")))
            assertEquals("旧版[大哭]", reader.read(Legacy("旧版[大哭]")))
            assertEquals("另一模型[cat]", reader.read(OtherModern("另一模型[cat]")))
        }
    }

    @Test fun invalidModernPayloadFallsBackToLegacyWithoutTruncation() {
        val reader = CommentRawTextReader()
        assertEquals("原文[大哭]", reader.read(Fallback()))
        val raw = "多行\n😀[dog]".repeat(300)
        assertEquals(raw, reader.read(Legacy(raw)))
        assertNull(reader.read(null))
        assertNull(reader.read(Any()))
    }
}
