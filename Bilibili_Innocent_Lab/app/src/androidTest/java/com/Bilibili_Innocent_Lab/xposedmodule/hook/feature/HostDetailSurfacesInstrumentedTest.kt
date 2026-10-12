package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundController
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundDrawable
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPagePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPages
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostDetailSurfaces
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.test.R as TestR
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.WeakHashMap

@RunWith(AndroidJUnit4::class)
class HostDetailSurfacesInstrumentedTest {
    private class Panel(activity: MainActivity) {
        private val testResources = InstrumentationRegistry.getInstrumentation().context.resources
        // 测试 id 来自测试 APK，生命周期和 Application 仍使用真实 Activity。
        private val context = object : ContextWrapper(activity) {
            override fun getResources(): Resources = testResources
        }
        val round = FrameLayout(context).apply { id = TestR.id.round_container }
        val root = FrameLayout(context).apply { id = TestR.id.content_container }
        val header = View(context).apply { id = TestR.id.tabs_back_board; setBackgroundColor(Color.WHITE) }
        val divider = View(context).apply { id = TestR.id.shadow_dig; setBackgroundColor(Color.GRAY) }
        val body = FrameLayout(context).apply { id = TestR.id.pager; setBackgroundColor(Color.WHITE) }
        val input = View(context).apply { id = TestR.id.fake_input_layout; setBackgroundColor(Color.WHITE) }
        val title = TextView(activity).apply { text = "简介 / 评论"; setTextColor(Color.RED) }
        init {
            round.addView(root, FrameLayout.LayoutParams(-1, -1))
            root.addView(header, FrameLayout.LayoutParams(-1, 80))
            root.addView(body, FrameLayout.LayoutParams(-1, 560).apply { topMargin = 80 })
            root.addView(divider, FrameLayout.LayoutParams(-1, 1).apply { topMargin = 79 })
            root.addView(title, FrameLayout.LayoutParams(-1, 80))
            body.addView(input, FrameLayout.LayoutParams(-1, 40).apply { topMargin = 520 })
            header.setPadding(3, 5, 7, 11)
        }
        fun layout() {
            round.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            round.layout(0, 0, 320, 640)
        }
    }

    @Test fun backgroundPassesKeepSurfaceBoundariesAndLeaveThePlaybackWindowAlone() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val decor = activity.window.decorView as ViewGroup
                val window = FrameLayout(activity)
                val originalWindow = ColorDrawable(Color.BLUE)
                val media = View(InstrumentationRegistry.getInstrumentation().context).apply {
                    id = TestR.id.video_area
                    setBackgroundColor(Color.RED)
                }
                val originalMedia = media.background
                val panel = Panel(activity)
                val originalChildCount = panel.root.childCount
                window.background = originalWindow
                window.addView(media, FrameLayout.LayoutParams(-1, -1))
                window.addView(panel.round, FrameLayout.LayoutParams(-1, -1))
                decor.addView(window, ViewGroup.LayoutParams(320, 640))
                window.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                window.layout(0, 0, 320, 640)
                panel.layout()
                val controller = HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.NEBULA), { throw it })
                val pages = HostBackgroundPages(controller, HostVideoCardHostAccess(activity.classLoader)) { throw it }
                try {
                    pages.attach(window, HostBackgroundPagePolicy.Kind.NATIVE)
                    repeat(3) { window.viewTreeObserver.dispatchOnGlobalLayout(); window.viewTreeObserver.dispatchOnPreDraw() }
                    assertSame(originalWindow, window.background)
                    assertSame(originalMedia, media.background)
                    assertTrue(panel.root.background is HostBackgroundDrawable)
                    val header = panel.header.background
                    val body = panel.body.background
                    val divider = panel.divider.background
                    val input = panel.input.background
                    assertNotNull(header)
                    assertNotNull(body)
                    assertNotNull(divider)
                    assertNotNull(input)
                    repeat(3) { window.viewTreeObserver.dispatchOnPreDraw() }
                    assertSame(header, panel.header.background)
                    assertSame(body, panel.body.background)
                    assertSame(divider, panel.divider.background)
                    assertSame(input, panel.input.background)
                    assertSame(panel.header, header.callback)
                    assertEquals(Color.RED, panel.title.currentTextColor)
                    assertEquals(originalChildCount, panel.root.childCount)
                    assertEquals(listOf(3, 5, 7, 11), listOf(panel.header.paddingLeft, panel.header.paddingTop,
                        panel.header.paddingRight, panel.header.paddingBottom))
                } finally { decor.removeView(window) }
            }
        }
    }

    @Test fun detachRestoresOnlyOwnedBackgroundsAndThemeChangesKeepTextAndClicks() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val decor = activity.window.decorView as ViewGroup
                val panel = Panel(activity)
                decor.addView(panel.round, ViewGroup.LayoutParams(320, 640))
                panel.layout()
                panel.root.background = HostBackgroundDrawable(HostBackgroundConfig(HostBackgroundPreset.AURORA))
                val originalHeader = panel.header.background
                val originalInput = panel.input.background
                val owned = WeakHashMap<View, Drawable>()
                val surfaces = HostDetailSurfaces(panel.root, owned)
                try {
                    surfaces.discover()
                    surfaces.apply(false)
                    val header = panel.header.background
                    surfaces.apply(true)
                    assertSame(header, panel.header.background)
                    var clicked = false
                    panel.input.setOnClickListener { clicked = true }
                    panel.input.performClick()
                    assertTrue(clicked)
                    assertEquals(Color.RED, panel.title.currentTextColor)
                    val replacement = ColorDrawable(Color.MAGENTA)
                    panel.body.background = replacement
                    surfaces.detach()
                    assertSame(originalHeader, panel.header.background)
                    assertSame(originalInput, panel.input.background)
                    assertSame(replacement, panel.body.background)
                    assertTrue(owned.isEmpty())
                    surfaces.apply(true)
                    assertNotSame(originalHeader, panel.header.background)
                    surfaces.detach()
                    assertSame(originalHeader, panel.header.background)
                    assertSame(replacement, panel.body.background)
                } finally { surfaces.detach(); decor.removeView(panel.round) }
            }
        }
    }
}
