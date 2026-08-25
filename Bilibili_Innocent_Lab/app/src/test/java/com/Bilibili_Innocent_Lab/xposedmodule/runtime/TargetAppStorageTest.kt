package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TargetAppStorageTest {

    @Test
    fun `maps Android uid to its user id`() {
        assertEquals(0, TargetAppStorage.userIdFromUid(10_251))
        assertEquals(10, TargetAppStorage.userIdFromUid(1_010_251))
    }

    @Test
    fun `builds a work profile cache path`() {
        assertEquals(
            "/data/user/10/tv.danmaku.bili/cache/innocent_lab_adapt.json",
            TargetAppStorage.cachePath("innocent_lab_adapt.json", 10)
        )
    }

    @Test
    fun `rejects a cache file path rather than a file name`() {
        assertThrows(IllegalArgumentException::class.java) {
            TargetAppStorage.cachePath("nested/cache.json", 0)
        }
    }
}
