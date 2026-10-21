package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top

import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostTopTabCenteringInstrumentedTest {
    @Test fun customParentPlacementRestoresWithoutFalseApplicationOrRepeatedLayoutAndRetriesAfterResize() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val titleId = View.generateViewId()
                val fixtureResources = object : Resources(activity.assets, activity.resources.displayMetrics, activity.resources.configuration) {
                    override fun getResourceEntryName(id: Int): String =
                        if (id == titleId) "tab_title" else super.getResourceEntryName(id)
                }
                val context = object : ContextWrapper(activity) {
                    override fun getResources(): Resources = fixtureResources
                }
                var overridePlacement = true
                var paddingWrites = 0
                val dock = FrameLayout(context)
                val scroll = object : HorizontalScrollView(context) {
                    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
                        paddingWrites++
                        super.setPadding(left, top, right, bottom)
                    }
                    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                        super.onLayout(changed, left, top, right, bottom)
                        if (overridePlacement) getChildAt(0)?.let { child ->
                            child.layout(0, 0, child.measuredWidth, child.measuredHeight)
                        }
                    }
                }
                val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                repeat(2) {
                    strip.addView(TextView(context).apply { id = titleId; text = "Tab" }, LinearLayout.LayoutParams(80, 100))
                }
                val originalGravity = strip.gravity
                scroll.addView(strip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 100))
                dock.addView(scroll, FrameLayout.LayoutParams(540, 100))
                dock.addView(FrameLayout(context).apply { isClickable = true }, FrameLayout.LayoutParams(60, 100, Gravity.RIGHT))
                activity.setContentView(dock)
                var binding: HostTopTabCenteringBinding? = null
                fun measure(width: Int = 600) {
                    repeat(6) {
                        dock.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
                        dock.layout(0, 0, width, 100)
                        binding?.sync()
                    }
                }
                measure()
                var applied = 0
                binding = NativeTopTabsCentering.attach(dock, scroll) { applied++ }
                measure()
                assertEquals(0, applied)
                assertEquals(0, scroll.paddingLeft)
                assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, strip.layoutParams.width)
                assertEquals(originalGravity, strip.gravity)
                assertFalse(scroll.isFillViewport)
                val settledWrites = paddingWrites
                measure()
                assertEquals("A custom parent's rejected generation must not cause an endless relayout", settledWrites, paddingWrites)
                overridePlacement = false
                measure(640)
                assertTrue(applied > 0)
                val first = Rect(0, 0, 80, 100).also { dock.offsetDescendantRectToMyCoords(strip.getChildAt(0), it) }
                val last = Rect(0, 0, 80, 100).also { dock.offsetDescendantRectToMyCoords(strip.getChildAt(1), it) }
                assertEquals(320, (first.left + last.right) / 2)
                NativeTopTabsCentering.release(dock)
            }
        }
    }

    @Test fun nativeMeasurementCentersTabsAndPreservesMenuClickIndicatorAndRestoration() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val titleIds = hashSetOf<Int>()
                val fixtureResources = object : Resources(activity.assets, activity.resources.displayMetrics, activity.resources.configuration) {
                    override fun getResourceEntryName(id: Int): String =
                        if (id in titleIds) "tab_title" else super.getResourceEntryName(id)
                }
                val context = object : ContextWrapper(activity) {
                    override fun getResources(): Resources = fixtureResources
                }
                var tabClicks = 0
                var menuClicks = 0
                val dock = FrameLayout(context)
                val parentIndicator = Rect()
                val indicatorPaint = Paint()
                val scroll = object : HorizontalScrollView(context) {
                    override fun onDraw(canvas: Canvas) {
                        super.onDraw(canvas)
                        val row = getChildAt(0) as? ViewGroup ?: return
                        val selected = row.getChildAt(0) ?: return
                        // Both real 8.91 strips paint in their parent, adding strip.left to child.left.
                        parentIndicator.set(row.left + selected.left, height - 4,
                            row.left + selected.right, height)
                        canvas.drawRect(parentIndicator, indicatorPaint)
                    }
                }.apply { setWillNotDraw(false) }
                val strip = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                }
                val originalGravity = strip.gravity
                scroll.addView(strip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 100))
                dock.addView(scroll, FrameLayout.LayoutParams(540, 100, Gravity.LEFT))
                val menu = FrameLayout(context).apply { setOnClickListener { menuClicks++ } }
                dock.addView(menu, FrameLayout.LayoutParams(60, 100, Gravity.RIGHT))
                fun tab(): FrameLayout = FrameLayout(context).apply {
                    setOnClickListener { tabClicks++ }
                    val titleId = View.generateViewId().also { titleIds += it }
                    addView(TextView(context).apply { id = titleId; text = "Tab" }, FrameLayout.LayoutParams(80, 44, Gravity.CENTER))
                    addView(View(context), FrameLayout.LayoutParams(40, 4, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
                    strip.addView(this, LinearLayout.LayoutParams(80, 100))
                }
                val first = tab().apply { isSelected = true }
                val second = tab()
                activity.setContentView(dock)
                var binding: HostTopTabCenteringBinding? = null
                fun measure() {
                    repeat(3) {
                        dock.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
                        dock.layout(0, 0, 600, 100)
                        binding?.sync()
                    }
                }
                fun rect(view: View) = Rect(0, 0, view.width, view.height).also { dock.offsetDescendantRectToMyCoords(view, it) }
                fun verifyParentIndicator() {
                    val bitmap = Bitmap.createBitmap(scroll.width, scroll.height, Bitmap.Config.ARGB_8888)
                    try { scroll.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
                    assertEquals(rect(first).exactCenterX(), scroll.left + parentIndicator.exactCenterX() - scroll.scrollX, .5f)
                }
                fun click(x: Float) {
                    val time = SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, 50f, 0)
                    val up = MotionEvent.obtain(time, time + 20, MotionEvent.ACTION_UP, x, 50f, 0)
                    try { assertTrue(dock.dispatchTouchEvent(down)); assertTrue(dock.dispatchTouchEvent(up)) }
                    finally { down.recycle(); up.recycle() }
                }
                measure()
                val menuBefore = rect(menu)
                binding = requireNotNull(NativeTopTabsCentering.attach(dock, scroll))
                var applied = 0
                assertSame(binding, NativeTopTabsCentering.attach(dock, scroll) { applied++ })
                assertEquals("Registration and pending measurement are not application evidence", 0, applied)
                assertSame(binding, NativeTopTabsCentering.attach(dock, scroll))
                measure()
                assertTrue(applied > 0)
                val stableApplied = applied
                measure()
                assertEquals(stableApplied, applied)
                assertTrue(scroll.isFillViewport)
                assertEquals(300, (rect(first).left + rect(second).right) / 2)
                assertEquals(menuBefore, rect(menu))
                assertEquals(40, first.getChildAt(1).width)
                assertEquals(20, first.getChildAt(1).left)
                assertTrue(first.isSelected)
                verifyParentIndicator()
                click(rect(first).exactCenterX())
                click(rect(menu).exactCenterX())
                assertEquals(1, tabClicks)
                assertEquals(1, menuClicks)

                // Some parents overlay the menu instead of subtracting its width from the scroll view.
                scroll.layoutParams = scroll.layoutParams.apply { width = 600 }
                measure()
                assertEquals(300, (rect(first).left + rect(second).right) / 2)
                assertTrue(rect(second).right <= rect(menu).left)
                assertEquals(menuBefore, rect(menu))
                verifyParentIndicator()

                val third = tab()
                measure()
                assertEquals(0, scroll.paddingLeft)
                assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, strip.layoutParams.width)
                assertEquals(originalGravity, strip.gravity)
                assertFalse(scroll.isFillViewport)
                val overflow = List(5) { tab() }
                measure()
                scroll.scrollTo(20, 0)
                assertTrue(scroll.scrollX > 0)
                (overflow + third).forEach(strip::removeView)
                // Synchronize before the native layout gets a chance to clamp its old scroll.
                // A viewport incorrectly mapped from Rect(0,0,...) would look negative and refuse this plan.
                assertTrue(scroll.scrollX > 0)
                requireNotNull(binding).sync()
                assertTrue(scroll.isFillViewport)
                assertTrue(scroll.paddingLeft > 0)
                measure()
                assertEquals("Native HorizontalScrollView must clamp the old scroll after shrinking its child", 0, scroll.scrollX)
                assertEquals(300, (rect(first).left + rect(second).right) / 2)
                verifyParentIndicator()

                // A real host update takes priority over restoration of the width we previously owned.
                strip.layoutParams = strip.layoutParams.apply { width = 222 }
                strip.addView(third, LinearLayout.LayoutParams(80, 100))
                requireNotNull(binding).sync()
                assertEquals(222, strip.layoutParams.width)
                assertEquals(0, scroll.paddingLeft)
                assertEquals(originalGravity, strip.gravity)
                assertFalse(scroll.isFillViewport)
                strip.removeView(third)
                measure()
                activity.setContentView(FrameLayout(activity))
                assertEquals(222, strip.layoutParams.width)
                assertEquals(0, scroll.paddingLeft)
                assertEquals(originalGravity, strip.gravity)
                NativeTopTabsCentering.release(dock)
            }
        }
    }
}
