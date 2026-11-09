package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuestHostFavoriteDialogReloadTest {
    private class LegacyDialog {
        fun unrelated() = Unit
        fun refresh() = Unit
        fun refresh(value: Int) = value
    }

    @Test fun learnsTheActualNoArgumentDialogCallerWhenSeveralExist() {
        val name = LegacyDialog::class.java.name
        val stack = arrayOf(
            StackTraceElement("host.UpdateCoroutine", "<init>", "source", 1),
            StackTraceElement(name, "refresh", "source", 2),
            StackTraceElement(name, "unrelated", "source", 3)
        )
        val method = GuestHostFavoriteDialog.resolveReloadFromStack(LegacyDialog::class.java, stack)
        assertEquals("refresh", method?.name)
        assertEquals(0, method?.parameterCount)
    }

    @Test fun ignoresFramesFromOtherClassesAndUnknownMethods() {
        val stack = arrayOf(StackTraceElement("host.Other", "refresh", "source", 1),
            StackTraceElement(LegacyDialog::class.java.name, "missing", "source", 2))
        assertNull(GuestHostFavoriteDialog.resolveReloadFromStack(LegacyDialog::class.java, stack))
    }
}
