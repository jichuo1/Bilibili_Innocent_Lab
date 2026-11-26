package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.Context
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.widget.NestedScrollView
import com.lumen.coacervation.engine.liquid.LiquidStretchGestureObserver
import kotlin.math.abs

/** Observes an intentional vertical gesture without consuming or changing nested-scroll dispatch. */
@SuppressLint("ViewConstructor") // Programmatic-only View; every owner must supply its cancellation callbacks.
internal class SettingsHomeScrollView(
    context: Context,
    private val onUserScroll: () -> Unit,
    private val onContentTouch: () -> Unit
) :
    NestedScrollView(context), LiquidStretchGestureObserver {
    /** Bound after construction; actual offsets include fling, programmatic and accessible scrolls. */
    var onScrollPositionChanged: ((View) -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var originX = 0f
    private var originY = 0f
    private var notified = false
    private val keySession = SettingsUserScrollSession()

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (l != oldl || t != oldt) onScrollPositionChanged?.invoke(this)
    }

    override fun executeKeyEvent(event: KeyEvent): Boolean {
        val navigationKey = event.action == KeyEvent.ACTION_DOWN && when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END -> true
            else -> false
        }
        keySession.beginKey(navigationKey)
        var completed = false
        var handled = false
        try {
            handled = super.executeKeyEvent(event)
            completed = true
        } finally {
            val accepted = keySession.finishKey(handled)
            if (completed && accepted) onUserScroll()
        }
        return handled
    }

    override fun pageScroll(direction: Int): Boolean {
        val handled = super.pageScroll(direction)
        // core 1.19.0 executeKeyEvent calls pageScroll for SPACE/HOME/END, but returns false.
        // Observe the actual result only during an eligible key dispatch; direct calls stay silent.
        keySession.pageResult(handled)
        return handled
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val handled = super.performAccessibilityAction(action, arguments)
        val scrollAction = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
            action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ||
            action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id ||
            action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
        if (handled && scrollAction) onUserScroll()
        return handled
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val handled = super.onGenericMotionEvent(event)
        // The inherited method accepts supported non-zero pointer/rotary scroll axes only.
        if (handled && event.actionMasked == MotionEvent.ACTION_SCROLL) onUserScroll()
        return handled
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        observeTouch(event)
        return super.dispatchTouchEvent(event)
    }

    /** 回弹视口接住手势时绕过 [dispatchTouchEvent] 直接调 onTouchEvent，也经这里观察。 */
    override fun observeTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // NestedScrollView stops a programmatic smooth scroll on DOWN, even without a drag.
                onContentTouch()
                pointer = event.getPointerId(0)
                originX = event.x
                originY = event.y
                notified = false
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointer)
                if (!notified && index >= 0) {
                    val dx = abs(event.getX(index) - originX)
                    val dy = abs(event.getY(index) - originY)
                    if (dy > slop && dy > dx * 1.2f) {
                        notified = true
                        onUserScroll()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointer) pointer = MotionEvent.INVALID_POINTER_ID
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointer = MotionEvent.INVALID_POINTER_ID
        }
    }
}
