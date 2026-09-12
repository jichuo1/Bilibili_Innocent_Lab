package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.*
import org.junit.Test

class ModalTitleColorOwnersTest {
    @Test fun lateDismissCannotRestoreANewerPanelsSourceOrCaptureTransparentAsOriginal() {
        val colors = ModalTitleColorOwners<Any, Int>()
        val view = Any()
        val first = Any()
        val second = Any()
        colors.acquire(view, first, 0x123456)
        assertEquals(0x123456, colors.original(view, 0))
        colors.acquire(view, second, 0)
        assertNull(colors.release(view, first))
        assertEquals(0x123456, colors.original(view, 0))
        assertEquals(0x123456, colors.release(view, second))
        assertNull(colors.release(view, second))
    }

    @Test fun reentryAndCancelledReturnKeepTheSameLeaseUntilFinalClose() {
        val colors = ModalTitleColorOwners<Any, Int>()
        val view = Any()
        val owner = Any()
        repeat(5) { colors.acquire(view, owner, if (it == 0) 99 else 0) }
        assertEquals(99, colors.release(view, owner))
        assertNull(colors.release(view, owner))
        assertEquals(17, colors.original(view, 17))
    }

    @Test fun independentRowsAndUnrecognizedOwnersNeverRestoreEachOther() {
        val colors = ModalTitleColorOwners<Any, Int>()
        val one = Any(); val two = Any(); val owner = Any()
        colors.acquire(one, owner, 11)
        colors.acquire(two, owner, 22)
        assertNull(colors.release(one, Any()))
        assertEquals(22, colors.release(two, owner))
        assertEquals(11, colors.original(one, 0))
        assertEquals(11, colors.release(one, owner))
    }
}
