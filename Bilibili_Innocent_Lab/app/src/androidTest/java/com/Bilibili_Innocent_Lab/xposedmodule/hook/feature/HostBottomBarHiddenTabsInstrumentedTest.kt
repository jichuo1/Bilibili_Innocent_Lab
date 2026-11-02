package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostBottomBarDockLayer
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostBottomBarFxConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostBottomBarFxController
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeTheme

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.ModernNavigationMotion
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** 使用测试机真实宿主资源和原生 View 验证槽位、滑块及点击映射，不修改用户配置。 */
@RunWith(AndroidJUnit4::class)
class HostBottomBarHiddenTabsInstrumentedTest {
    @Test fun reboundLabelsAreRemeasuredBeforeTheSameFrameIsCentered() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext.createPackageContext("tv.danmaku.bili", 0)
            val normalId = context.resources.getIdentifier("normal_ll", "id", context.packageName)
            check(normalId != 0)
            val host = FrameLayout(context)
            val container = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            host.addView(container, FrameLayout.LayoutParams(-1, -1))
            val tab = FrameLayout(context)
            container.addView(tab, LinearLayout.LayoutParams(0, -1, 1f))
            val normal = LinearLayout(context).apply {
                id = normalId
                orientation = LinearLayout.VERTICAL
            }
            val icon = View(context)
            val label = TextView(context).apply { text = "Home" }
            normal.addView(icon, LinearLayout.LayoutParams(48, 48))
            normal.addView(label, LinearLayout.LayoutParams(48, 32))
            tab.addView(normal, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER))
            fun measureAndLayout() {
                host.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(144, View.MeasureSpec.EXACTLY))
                host.layout(0, 0, 320, 144)
            }
            repeat(3) {
                // 切页绑定重新显示文字，宿主先按图文组合完成布局。
                label.visibility = View.VISIBLE
                measureAndLayout()
                assertEquals(80, normal.measuredHeight)
                HostBottomBarFxController.alignTabContent(host, container, 1f, 4,
                    HostBottomBarFxConfig(iconOnly = true))
                // pre-draw 校正后即可绘制；不能等下一次 traversal 才把图标放回中央。
                assertEquals(View.GONE, label.visibility)
                assertEquals(48, normal.measuredHeight)
                assertEquals(48, normal.top + icon.top)
                assertEquals(144, host.height)
            }
        }
    }

    @Test fun hiddenMiddleTabsShareGeometryAndClicksWithTheCapsule() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext.createPackageContext("tv.danmaku.bili", 0)
            val density = context.resources.displayMetrics.density
            val host = FrameLayout(context)
            val container = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            host.addView(container, FrameLayout.LayoutParams(-1, -1))
            var clicked = -1
            val tabs = (0 until 5).map { index ->
                object : FrameLayout(context) {
                    // 夹具不挂 Activity 窗口；仅模拟附着后 isShown 的可见性门控。
                    override fun isShown(): Boolean = visibility == View.VISIBLE
                }.apply {
                    visibility = if (index == 1 || index == 3) View.GONE else View.VISIBLE
                    isSelected = index == 0
                    setOnClickListener {
                        clicked = index
                        for (i in 0 until container.childCount) container.getChildAt(i).isSelected = i == index
                    }
                    container.addView(this, LinearLayout.LayoutParams(0, -1, 1f))
                }
            }
            val colors = HostChromeTheme(context).read()
            val dock = HostBottomBarDockLayer(context, HostBottomBarFxConfig(), host, container,
                colors.palette(), colors.dark, backdrop = null, requestSanitization = {})
            val width = (360 * density).roundToInt()
            val height = (64 * density).roundToInt()
            val inset = ModernNavigationMotion.INSET_DP * density
            fun layout() {
                // 独立测量的夹具没有窗口 pre-draw；显式执行生产可见性守卫请求的重测。
                dock.requestLayout()
                dock.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                dock.layout(0, 0, width, height)
            }
            fun tap(slot: Int, count: Int, hostIndex: Int = -1) {
                val x = inset + (slot + 0.5f) * (width - inset * 2f) / count
                val now = SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, height / 2f, 0)
                try {
                    assertTrue(dock.dispatchTouch(event, hostIndex))
                    event.action = MotionEvent.ACTION_UP
                    assertTrue(dock.dispatchTouch(event, hostIndex))
                } finally { event.recycle() }
            }
            layout()
            val slotWidth = (width - inset * 2f) / 3
            assertEquals(slotWidth.roundToInt(), dock.selectionView.measuredWidth)
            // 单项 OnTouchListener 传的是宿主索引，父容器空白处传的是几何槽位。
            tap(2, 3, hostIndex = 4)
            assertEquals(4, clicked)
            assertEquals(slotWidth * 2, dock.selectionView.translationX, 0.001f)
            tap(1, 3)
            assertEquals(2, clicked)
            assertEquals(slotWidth, dock.selectionView.translationX, 0.001f)

            // 宿主晚绑定/再次隐藏：即便底栏外壳尺寸未变，也要重测滑块并映射选中项。
            tabs[0].visibility = View.GONE
            layout()
            assertEquals(((width - inset * 2f) / 2).roundToInt(), dock.selectionView.measuredWidth)
            assertEquals(0f, dock.selectionView.translationX, 0.001f)
            tap(1, 2, hostIndex = 4)
            assertEquals(4, clicked)
            tabs.forEach { it.visibility = View.GONE }
            layout()
            assertEquals(View.INVISIBLE, dock.selectionView.visibility)
            dock.dispose()
        }
    }
}
