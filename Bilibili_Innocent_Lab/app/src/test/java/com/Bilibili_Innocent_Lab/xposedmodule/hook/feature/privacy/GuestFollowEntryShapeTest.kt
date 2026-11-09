package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.junit.Assert.*
import org.junit.Test

class GuestFollowEntryShapeTest {
    // The 关注 and 粉丝 clicks are Kotlin data objects; only their toString() survives renaming.
    object AttentionClick { override fun toString() = "ToAttentionClick" }
    object FansClick { override fun toString() = "ToFansClick" }
    class NotAnObject { override fun toString() = "ToAttentionClick" }

    @Test fun onlyTheAttentionClickObjectIsRecognised() {
        assertTrue(GuestMineFollowEntry.isAttentionClick(AttentionClick::class.java))
        assertFalse(GuestMineFollowEntry.isAttentionClick(FansClick::class.java))
        // A class that merely prints the same text but is not an object (no static instance of itself) never counts.
        assertFalse(GuestMineFollowEntry.isAttentionClick(NotAnObject::class.java))
    }
}
