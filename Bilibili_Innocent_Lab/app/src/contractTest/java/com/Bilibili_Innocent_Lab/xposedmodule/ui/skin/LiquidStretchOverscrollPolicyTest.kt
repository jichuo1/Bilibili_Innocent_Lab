package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidStretchOverscrollPolicyTest {
    @Test
    fun settingsHomeScrollViewObservesStretchBeforeDispatchingChildren() {
        val scroll = LceMotionSource.read(
            "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/SettingsHomeScrollView.kt"
        )
        assertTrue(scroll.contains("LiquidStretchGestureObserver"))
        val scrollDispatch = scroll.after("override fun dispatchTouchEvent(").before("override fun observeTouch(")
        assertTrue(scrollDispatch.indexOf("observeTouch(event)") in 0 until scrollDispatch.indexOf("super.dispatchTouchEvent(event)"))
    }
}
