package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GuestHostFavoriteSnackbarTest {
    private class LegacyUtil {
        companion object { @JvmField val INSTANCE = LegacyUtil() }
        fun show(value: String) { last = value }
        @JvmField var last: String? = null
    }

    private class StaticUtil {
        companion object {
            @JvmField var last: String? = null
            @JvmStatic fun show(value: String) { last = value }
        }
    }

    private class AmbiguousUtil {
        fun first(value: String) = Unit
        fun second(value: String) = Unit
    }

    @Test fun locatesOlderSingletonAndNewerStaticMethods() {
        val signature = arrayOf<Class<*>>(String::class.java)
        val legacy = GuestHostFavoriteSnackbar.locateShow(LegacyUtil::class.java, signature)
        assertNotNull(legacy)
        assertSame(LegacyUtil.INSTANCE, legacy!!.receiver)
        legacy.method.invoke(legacy.receiver, "old")
        assertEquals("old", LegacyUtil.INSTANCE.last)
        val modern = GuestHostFavoriteSnackbar.locateShow(StaticUtil::class.java, signature)
        assertNotNull(modern)
        assertNull(modern!!.receiver)
        modern.method.invoke(modern.receiver, "new")
        assertEquals("new", StaticUtil.last)
    }

    @Test fun rejectsAmbiguousMethods() {
        assertNull(GuestHostFavoriteSnackbar.locateShow(AmbiguousUtil::class.java, arrayOf(String::class.java)))
    }
}
