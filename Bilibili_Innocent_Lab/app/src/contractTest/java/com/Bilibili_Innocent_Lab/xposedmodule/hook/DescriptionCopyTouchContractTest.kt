package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DescriptionCopyTouchContractTest {
    private fun source() = SourceContract.read("hook/HookEntry.kt")

    @Test fun cancellationStopsHostAndModuleTimersWithoutTakingScrollOwnership() {
        val clear = source().after("private fun clearDescTouchSession(").before("private fun cancelDescLongPressCandidate()")
        assertTrue(clear.contains("if (resetHandled)"))
        assertTrue(clear.contains("view.cancelLongPress()"))
        val cancel = source().after("private fun cancelDescLongPressCandidate()").before("private fun observeDescTouch(")
        assertTrue(cancel.contains("removeCallbacks(descLongPressRunnable)"))
        assertTrue(cancel.contains("view.cancelLongPress()"))
        assertFalse(cancel.contains("descCopyGesture.reset()"))
        val observer = source().after("private fun observeDescTouch(").before("private val descLongPressRunnable")
        assertTrue(observer.contains("ACTION_POINTER_DOWN"))
        assertTrue(observer.contains("ACTION_POINTER_UP"))
        assertTrue(observer.contains("ACTION_CANCEL"))
        assertTrue(observer.contains("getHistoricalX(index, history)"))
        assertTrue(observer.contains("getHistoricalY(index, history)"))
        assertTrue(observer.contains("findPointerIndex(descCopyGesture.pointerId)"))
        assertFalse(observer.contains("requestDisallowInterceptTouchEvent"))
        assertFalse(observer.contains("dispatchTouchEvent"))
    }

    @Test fun allDescriptionEntriesRespectSameCandidateAndDeviceSlop() {
        val listener = source().after("private val sharedFreeCopyListener =").before("private fun hapticFeedback(")
        assertTrue(listener.contains("!view.isAttachedToWindow || !view.isShown"))
        assertTrue(listener.contains("view.windowVisibility != View.VISIBLE"))
        assertTrue(listener.contains("descTouchedView !== view || !descCopyGesture.isPending"))
        assertTrue(listener.contains("descCopyGesture.claim("))
        val timer = source().after("private val descLongPressRunnable =").before("private var suppressOfficialUntilMs")
        assertTrue(timer.contains("!descCopyGesture.isPending"))
        assertTrue(timer.contains("sharedFreeCopyListener.onLongClick(v)"))
        val dispatch = source().after("// 简介触摸长按检测：").before("// 拦截官方「复制简介全文」实现")
        assertTrue(dispatch.indexOf("observeDescTouch(ev)") < dispatch.indexOf("if (!isDescLikeTarget) {"))
        val description = dispatch.after("descCopyGesture.begin(")
        assertTrue(description.contains("scaledTouchSlop.toFloat()"))
        assertTrue(description.contains("descCopyGesture.isPending && !handled"))
        assertFalse(description.contains("60f"))
        assertFalse(description.contains("requestDisallowInterceptTouchEvent"))
    }

    @Test fun bubbleDismissReleasesOnlyHandledGestureAfterIdentityCheck() {
        val finish = source().after("private fun finishBubbleSession(").before("private val sharedFreeCopyListener =")
        assertTrue(finish.indexOf("activeBubbleSessionId != sessionId") < finish.indexOf("descCopyGesture.finishHandled()"))
        assertFalse(finish.contains("descCopyGesture.reset()"))
    }
}
