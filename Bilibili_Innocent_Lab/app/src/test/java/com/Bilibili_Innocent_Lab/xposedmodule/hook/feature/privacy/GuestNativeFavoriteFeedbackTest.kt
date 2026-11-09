package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

class GuestNativeFavoriteFeedbackTest {
    @Test fun fallsBackOnlyForTheMissingHostFavoriteRepository() {
        var calls = 0
        GuestNativeFavoriteHooks.withMissingRepositoryFallback({}, { calls++; true })
        assertEquals(0, calls)
        val expected = InvocationTargetException(NoClassDefFoundError("com.bilibili.community.favorite.FavorRepository"))
        GuestNativeFavoriteHooks.withMissingRepositoryFallback({ throw expected }, { calls++; true })
        assertEquals(1, calls)
        try {
            GuestNativeFavoriteHooks.withMissingRepositoryFallback({ throw expected }, { false })
            fail("A failed fallback must preserve the native error")
        } catch (error: InvocationTargetException) { assertSame(expected, error) }
        val unrelated = InvocationTargetException(NoClassDefFoundError("other.HostType"))
        try {
            GuestNativeFavoriteHooks.withMissingRepositoryFallback({ throw unrelated }, { calls++; true })
            fail("Unrelated host failures must not be hidden")
        } catch (error: InvocationTargetException) { assertSame(unrelated, error) }
        assertEquals(1, calls)
    }
}
