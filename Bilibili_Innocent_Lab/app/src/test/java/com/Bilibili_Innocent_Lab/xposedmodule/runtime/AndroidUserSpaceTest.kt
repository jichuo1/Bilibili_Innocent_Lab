package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUserSpaceTest {

    @Test
    fun `uid encoding resolves primary secondary and clone users`() {
        assertEquals(0, AndroidUserSpace.userIdFromUid(10_234))
        assertEquals(10, AndroidUserSpace.userIdFromUid(1_010_234))
        // MIUI 应用双开使用 userId 999。
        assertEquals(999, AndroidUserSpace.userIdFromUid(99_910_234))
    }

    @Test
    fun `negative uid never fabricates a clone profile`() {
        assertEquals(0, AndroidUserSpace.userIdFromUid(-1))
        assertEquals(0, AndroidUserSpace.userIdFromUid(Int.MIN_VALUE))
        assertFalse(
            AndroidUserSpace.isSecondaryOrCloneProfile(AndroidUserSpace.userIdFromUid(-1))
        )
    }

    @Test
    fun `only non-primary users are treated as possible clone profiles`() {
        assertFalse(AndroidUserSpace.isSecondaryOrCloneProfile(0))
        assertTrue(AndroidUserSpace.isSecondaryOrCloneProfile(10))
        assertTrue(AndroidUserSpace.isSecondaryOrCloneProfile(999))
    }

    @Test
    fun `same-user comparison stays unknown while the target is invisible`() {
        assertNull(AndroidUserSpace.resolveSameUser(moduleUserId = 999, targetUserId = null))
        assertEquals(true, AndroidUserSpace.resolveSameUser(999, 999))
        assertEquals(false, AndroidUserSpace.resolveSameUser(999, 0))
        assertEquals(false, AndroidUserSpace.resolveSameUser(0, 999))
    }

    @Test
    fun `snapshot derives clone flag from the module user only`() {
        val clone = AndroidUserSpaceSnapshot(
            moduleUserId = 999,
            targetUserId = 999,
            sameUser = true
        )
        assertTrue(clone.possibleSecondaryOrCloneProfile)

        val primaryModuleWithCloneTarget = AndroidUserSpaceSnapshot(
            moduleUserId = 0,
            targetUserId = 999,
            sameUser = false
        )
        assertFalse(primaryModuleWithCloneTarget.possibleSecondaryOrCloneProfile)
    }

    @Test
    fun `neutral snapshot produces no multi-user hint`() {
        val neutral = AndroidUserSpaceSnapshot.PRIMARY
        assertEquals(AndroidUserSpace.PRIMARY_USER_ID, neutral.moduleUserId)
        assertNull(neutral.targetUserId)
        assertNull(neutral.sameUser)
        assertFalse(neutral.possibleSecondaryOrCloneProfile)
    }

    @Test
    fun `target cache path helper agrees with the shared user resolution`() {
        assertEquals(
            AndroidUserSpace.userIdFromUid(99_910_234),
            TargetAppStorage.userIdFromUid(99_910_234)
        )
        assertEquals(
            "/data/user/999/tv.danmaku.bili/cache/innocent_lab_adapt.json",
            TargetAppStorage.cachePath(
                "innocent_lab_adapt.json",
                AndroidUserSpace.userIdFromUid(99_910_234)
            )
        )
    }
}
