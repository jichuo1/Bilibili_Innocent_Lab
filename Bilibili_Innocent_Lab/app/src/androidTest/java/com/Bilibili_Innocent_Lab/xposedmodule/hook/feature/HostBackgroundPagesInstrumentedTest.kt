package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Color
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.shape.MaterialShapeDrawable
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.*
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import com.Bilibili_Innocent_Lab.xposedmodule.test.R as TestR
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostBackgroundPagesInstrumentedTest {
    @Test fun commentHeaderContentAndInputShareOneWallpaperInEitherAttachOrder() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val decor = activity.window.decorView as ViewGroup
                for (childFirst in listOf(false, true)) {
                    val root = FrameLayout(activity)
                    val header = View(activity).apply { setBackgroundColor(Color.WHITE) }
                    val layer = FrameLayout(activity).apply { setBackgroundColor(Color.WHITE) }
                    val input = View(activity).apply { setBackgroundColor(Color.WHITE) }
                    val list = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity) }
                    root.addView(header, FrameLayout.LayoutParams(-1, 80))
                    root.addView(layer, FrameLayout.LayoutParams(-1, 520).apply { topMargin = 80 })
                    layer.addView(list, FrameLayout.LayoutParams(-1, -1))
                    root.addView(input, FrameLayout.LayoutParams(-1, 40).apply { topMargin = 600 })
                    decor.addView(root, ViewGroup.LayoutParams(320, 640))
                    root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, 320, 640)
                    val controller = HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.NEBULA), { throw it })
                    val pages = HostBackgroundPages(controller, HostVideoCardHostAccess(activity.classLoader)) { throw it }
                    fun draw() {
                        root.viewTreeObserver.dispatchOnGlobalLayout()
                        root.viewTreeObserver.dispatchOnPreDraw()
                    }
                    if (childFirst) { pages.attach(layer, HostBackgroundPagePolicy.Kind.COMMENT); draw() }
                    pages.attach(root, HostBackgroundPagePolicy.Kind.COMMENT)
                    pages.attach(layer, HostBackgroundPagePolicy.Kind.COMMENT)
                    draw()
                    assertSame(root, HostBackgroundPages.owner(list))
                    assertTrue(root.background is HostBackgroundDrawable)
                    assertNull(header.background)
                    assertNull(layer.background)
                    assertNull(input.background)
                    assertNull(list.background)
                    assertTrue(pages.scroll(list, 137))
                    assertEquals(137L, (root.background as HostBackgroundDrawable).scrollOffset)
                    controller.apply(list, false)
                    assertNull(list.background)
                    decor.removeView(root)
                }
            }
        }
    }

    @Test fun embeddedVideoTabsMoveTheHeaderWithTheirScrollAndInterpolateDuringPaging() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val decor = activity.window.decorView as ViewGroup
                val context = InstrumentationRegistry.getInstrumentation().context
                val round = FrameLayout(context).apply { id = TestR.id.round_container }
                val panel = FrameLayout(context).apply { id = TestR.id.content_container }
                val header = View(activity).apply { setBackgroundColor(Color.WHITE) }
                val comment = FrameLayout(activity).apply { setBackgroundColor(Color.WHITE) }
                val list = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity) }
                val intro = FrameLayout(activity).apply { setBackgroundColor(Color.WHITE); translationX = 320f }
                val introList = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity) }
                round.addView(panel, FrameLayout.LayoutParams(-1, -1))
                panel.addView(header, FrameLayout.LayoutParams(-1, 80))
                panel.addView(comment, FrameLayout.LayoutParams(-1, 560).apply { topMargin = 80 })
                comment.addView(list, FrameLayout.LayoutParams(-1, -1))
                panel.addView(intro, FrameLayout.LayoutParams(-1, 560).apply { topMargin = 80 })
                intro.addView(introList, FrameLayout.LayoutParams(-1, -1))
                decor.addView(round, ViewGroup.LayoutParams(320, 640))
                round.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                round.layout(0, 0, 320, 640)
                val controller = HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.NEBULA), { throw it })
                val pages = HostBackgroundPages(controller, HostVideoCardHostAccess(activity.classLoader)) { throw it }
                // 宿主卡片会先绑定列表，随后 Fragment 才注册页面身份。
                controller.apply(introList, false)
                assertTrue(introList.background is HostBackgroundDrawable)
                pages.attach(comment, HostBackgroundPagePolicy.Kind.COMMENT)
                round.viewTreeObserver.dispatchOnPreDraw()
                assertTrue(comment.background is HostBackgroundDrawable)
                pages.attach(intro, HostBackgroundPagePolicy.Kind.PAGE)
                pages.attach(panel, HostBackgroundPagePolicy.Kind.NATIVE)
                round.viewTreeObserver.dispatchOnGlobalLayout()
                round.viewTreeObserver.dispatchOnPreDraw()
                assertSame(panel, HostBackgroundPages.sharedWallpaperOwner(comment))
                assertSame(panel, HostBackgroundPages.sharedWallpaperOwner(intro))
                assertSame(comment, HostBackgroundPages.owner(list))
                assertNull(round.background)
                assertNull(header.background)
                assertNull(comment.background)
                assertNull(list.background)
                assertNull(intro.background)
                assertNull(introList.background)
                val wallpaper = panel.background as HostBackgroundDrawable
                assertTrue(pages.scroll(list, 281))
                assertEquals(281L, wallpaper.scrollOffset)
                assertFalse("Offscreen introduction must not move the header", pages.scroll(introList, 50))
                intro.translationX = 0f
                comment.translationX = 320f
                round.viewTreeObserver.dispatchOnPreDraw()
                assertEquals("The header must restore introduction's position", 0L, wallpaper.scrollOffset)
                assertTrue(pages.scroll(introList, 137))
                assertEquals(137L, wallpaper.scrollOffset)
                controller.apply(introList, false)
                assertNull(intro.background)
                assertNull(introList.background)
                intro.translationX = -80f
                comment.translationX = 240f
                round.viewTreeObserver.dispatchOnPreDraw()
                assertEquals("A quarter swipe must interpolate the two scroll positions", 173L, wallpaper.scrollOffset)
                intro.translationX = -160f
                comment.translationX = 160f
                round.viewTreeObserver.dispatchOnPreDraw()
                assertEquals("Halfway through paging, callback order must not select a tab abruptly", 209L, wallpaper.scrollOffset)
                intro.translationX = -320f
                comment.translationX = 0f
                round.viewTreeObserver.dispatchOnPreDraw()
                assertEquals("Returning to comments must restore their position", 281L, wallpaper.scrollOffset)
                controller.apply(list, true)
                assertTrue(wallpaper.night)
                assertNull(list.background)
                comment.visibility = View.GONE
                assertFalse(pages.scroll(list, 50))
                comment.visibility = View.VISIBLE
                round.viewTreeObserver.dispatchOnPreDraw()
                assertEquals(281L, wallpaper.scrollOffset)
                // 普通窗口/动态评论弹层不应借用视频前景面板。
                round.id = View.NO_ID
                assertNull(HostBackgroundPages.sharedWallpaperOwner(comment))
                decor.removeView(round)
            }
        }
    }

    @Test fun homeFeedsShareTheWindowWallpaperAndKeepTheirOwnScrollPositions() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val decor = activity.window.decorView as ViewGroup
                val window = FrameLayout(activity)
                val feeds = List(2) {
                    FrameLayout(activity).apply {
                        setBackgroundColor(Color.WHITE)
                        addView(RecyclerView(activity).apply {
                            id = TestR.id.dy_list
                            layoutManager = LinearLayoutManager(activity)
                            setBackgroundColor(Color.WHITE)
                        }, FrameLayout.LayoutParams(-1, -1))
                    }
                }
                feeds.forEach { window.addView(it, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 80 }) }
                feeds[1].visibility = View.GONE
                decor.addView(window, ViewGroup.LayoutParams(320, 640))
                window.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                window.layout(0, 0, 320, 640)
                // 模拟横向分页预加载，隐藏页也已经拥有完整布局。
                feeds[1].layout(0, 80, 320, 640)
                feeds[1].getChildAt(0).layout(0, 0, 320, 560)
                val controller = HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.AURORA), { throw it })
                val pages = HostBackgroundPages(controller, HostVideoCardHostAccess(activity.classLoader)) { throw it }
                feeds.forEach { pages.attach(it, HostBackgroundPagePolicy.Kind.FEED) }
                pages.attach(window, HostBackgroundPagePolicy.Kind.NATIVE)
                fun draw() {
                    window.viewTreeObserver.dispatchOnGlobalLayout()
                    window.viewTreeObserver.dispatchOnPreDraw()
                }
                draw()
                val wallpaper = window.background as HostBackgroundDrawable
                feeds.forEach { feed ->
                    assertSame(window, HostBackgroundPages.nativeFeedOwner(feed))
                }
                val first = feeds[0].getChildAt(0) as ViewGroup
                val second = feeds[1].getChildAt(0) as ViewGroup
                assertNull("Feed roots must expose the window wallpaper", feeds[0].background)
                assertNull("Lists must not restart the wallpaper below the header", first.background)
                assertTrue(pages.scroll(first, 137))
                assertEquals(137L, wallpaper.scrollOffset)
                controller.apply(first, false) // 卡片监听器也不能重新创建列表壁纸。
                assertNull(first.background)
                feeds[0].translationY = -80f
                draw()
                assertSame(wallpaper, window.background)
                assertEquals(137L, wallpaper.scrollOffset)
                feeds[0].visibility = View.GONE
                feeds[1].visibility = View.VISIBLE
                draw()
                assertEquals(0L, wallpaper.scrollOffset)
                assertNull(feeds[1].background)
                assertNull(second.background)
                assertTrue(pages.scroll(second, 281))
                assertEquals(281L, wallpaper.scrollOffset)
                assertFalse("Hidden feeds must not move the visible wallpaper", pages.scroll(first, 50))
                feeds[1].visibility = View.GONE
                feeds[0].visibility = View.VISIBLE
                draw()
                assertEquals("Returning to a feed must restore its own position", 137L, wallpaper.scrollOffset)
                decor.removeView(window)
            }
        }
    }

    @Test fun emptyDynamicPageKeepsCardLayerAndRestoresWallpaperAfterReattach() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val testContext = InstrumentationRegistry.getInstrumentation().context
                val root = FrameLayout(activity)
                val container = FrameLayout(activity).apply { setBackgroundColor(Color.WHITE) }
                val panel = View(activity).apply { background = GradientDrawable().apply { setColor(Color.DKGRAY); cornerRadius = 20f } }
                val panelSurface = panel.background
                val list = RecyclerView(testContext).apply { id = TestR.id.dy_list; layoutManager = LinearLayoutManager(testContext); setBackgroundColor(Color.WHITE) }
                val otherList = RecyclerView(testContext).apply { id = TestR.id.tv_right; layoutManager = LinearLayoutManager(testContext); visibility = View.GONE; setBackgroundColor(Color.WHITE) }
                val section = View(activity).apply { background = GradientDrawable().apply { setColor(Color.DKGRAY) }; setPadding(3, 5, 7, 9) }
                val image = ImageView(activity).apply { setBackgroundColor(Color.BLACK) }
                val imageSurface = image.background
                val action = View(activity).apply { background = StateListDrawable().apply { addState(intArrayOf(), GradientDrawable().apply { setColor(Color.RED) }) } }
                val actionSurface = action.background
                root.addView(container, FrameLayout.LayoutParams(-1, -1))
                container.addView(panel, FrameLayout.LayoutParams(-1, 120))
                container.addView(list, FrameLayout.LayoutParams(-1, -1))
                container.addView(otherList, FrameLayout.LayoutParams(-1, -1))
                root.addView(section, FrameLayout.LayoutParams(-1, 40))
                root.addView(image, FrameLayout.LayoutParams(80, 80))
                root.addView(action, FrameLayout.LayoutParams(80, 40))
                val decor = activity.window.decorView as ViewGroup
                decor.addView(root, ViewGroup.LayoutParams(320, 640))
                root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 320, 640)
                val controller = HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.STARRY), { throw it })
                val pages = HostBackgroundPages(controller, HostVideoCardHostAccess(activity.classLoader)) { throw it }
                pages.attach(container, HostBackgroundPagePolicy.Kind.PAGE)
                root.viewTreeObserver.dispatchOnGlobalLayout()
                root.viewTreeObserver.dispatchOnPreDraw()
                assertTrue(container.background is HostBackgroundDrawable)
                pages.attach(root, HostBackgroundPagePolicy.Kind.DYNAMIC)
                pages.attach(container, HostBackgroundPagePolicy.Kind.PAGE)
                root.viewTreeObserver.dispatchOnGlobalLayout()
                root.viewTreeObserver.dispatchOnPreDraw()
                val wallpaper = root.background as HostBackgroundDrawable
                assertSame("Nested pages must share the outer wallpaper", root, HostBackgroundPages.owner(list))
                assertNull(container.background)
                assertNull(list.background)
                assertSame(panelSurface, panel.background)
                assertSame(imageSurface, image.background)
                assertSame(actionSurface, action.background)
                assertEquals(Color.TRANSPARENT, (section.background as GradientDrawable).color?.defaultColor)
                assertEquals(3, section.paddingLeft)
                assertEquals(5, section.paddingTop)
                assertEquals(7, section.paddingRight)
                assertEquals(9, section.paddingBottom)
                controller.apply(list, true)
                assertSame(wallpaper, root.background)
                assertTrue(wallpaper.night)
                assertNull(list.background)
                assertTrue(pages.scroll(list, 120))
                assertEquals(120L, wallpaper.scrollOffset)
                list.visibility = View.GONE
                otherList.visibility = View.VISIBLE
                root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 320, 640)
                root.viewTreeObserver.dispatchOnGlobalLayout()
                root.viewTreeObserver.dispatchOnPreDraw()
                assertEquals("Each dynamic tab must retain its own wallpaper position", 0L, wallpaper.scrollOffset)
                assertNull("Full-width vertical lists must not depend on a particular resource name", otherList.background)
                list.visibility = View.VISIBLE
                otherList.visibility = View.GONE
                root.viewTreeObserver.dispatchOnGlobalLayout()
                root.viewTreeObserver.dispatchOnPreDraw()
                assertEquals(120L, wallpaper.scrollOffset)
                decor.removeView(root)
                decor.addView(root, ViewGroup.LayoutParams(320, 640))
                container.setBackgroundColor(Color.WHITE)
                root.viewTreeObserver.dispatchOnGlobalLayout()
                root.viewTreeObserver.dispatchOnPreDraw()
                assertSame(wallpaper, root.background)
                assertNull(container.background)
                decor.removeView(root)
                verifyNativeAndCommentCoverage(activity, decor)
            }
        }
    }

    private fun verifyNativeAndCommentCoverage(activity: MainActivity, decor: ViewGroup) {
        val base = InstrumentationRegistry.getInstrumentation().context
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            uiMode = uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or Configuration.UI_MODE_NIGHT_YES
        })
        val window = FrameLayout(context)
        val dynamic = FrameLayout(context).apply { setBackgroundColor(Color.WHITE) }
        val comment = FrameLayout(context).apply { setBackgroundColor(Color.WHITE) }
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            setBackgroundColor(Color.WHITE)
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = 3
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val row = FrameLayout(context).apply {
                        setBackgroundColor(Color.WHITE)
                        layoutParams = RecyclerView.LayoutParams(-1, 150)
                        addView(FrameLayout(context).apply { setBackgroundColor(Color.WHITE) }, FrameLayout.LayoutParams(-1, -1))
                    }
                    return object : RecyclerView.ViewHolder(row) {}
                }
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
            }
        }
        comment.addView(list, FrameLayout.LayoutParams(-1, -1))
        dynamic.addView(comment, FrameLayout.LayoutParams(-1, -1))
        window.addView(dynamic, FrameLayout.LayoutParams(-1, -1))
        val player = FrameLayout(context).apply {
            id = TestR.id.video_area
            setBackgroundColor(Color.BLACK)
            addView(FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }, FrameLayout.LayoutParams(-1, -1))
        }
        val playerSurface = player.background
        val playerChildSurface = player.getChildAt(0).background
        window.addView(player, FrameLayout.LayoutParams(-1, 80))
        val chrome = View(context).apply { background = MaterialShapeDrawable().apply {
            fillColor = ColorStateList.valueOf(Color.DKGRAY)
            setStroke(2f, Color.BLUE)
        } }
        window.addView(chrome, FrameLayout.LayoutParams(-1, 40))
        val gradient = View(context).apply {
            id = TestR.id.main_top_layout
            isClickable = true
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.DKGRAY, Color.TRANSPARENT))
        }
        window.addView(gradient, FrameLayout.LayoutParams(-1, 40))
        val input = View(context).apply {
            id = TestR.id.fake_input_layout
            background = LayerDrawable(arrayOf(ColorDrawable(Color.DKGRAY), ColorDrawable(Color.RED), ColorDrawable(Color.GRAY), ColorDrawable(Color.WHITE))).apply {
                setLayerWidth(1, 20)
                setLayerHeight(2, 1)
                setLayerWidth(3, 20)
            }
        }
        window.addView(input, FrameLayout.LayoutParams(-1, 40))
        val inputWithLine = View(context).apply {
            id = TestR.id.fake_input_layout
            background = LayerDrawable(arrayOf(
                GradientDrawable().apply { setColor(Color.GRAY) },
                GradientDrawable().apply { setColor(Color.DKGRAY) }
            )).apply { setLayerInset(1, 0, 1, 0, 0) }
        }
        window.addView(inputWithLine, FrameLayout.LayoutParams(-1, 40))
        val label = TextView(context).apply { text = "19"; setTextColor(Color.BLACK) }
        window.addView(label, FrameLayout.LayoutParams(80, 40))
        val bright = FrameLayout(context).apply { background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 8f } }
        val brightLabel = TextView(context).apply { text = "button"; setTextColor(Color.BLACK) }
        bright.addView(brightLabel, FrameLayout.LayoutParams(-1, -1))
        window.addView(bright, FrameLayout.LayoutParams(80, 40))
        decor.addView(window, ViewGroup.LayoutParams(320, 640))
        window.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        window.layout(0, 0, 320, 640)
        val controller = HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.STARRY), { throw it })
        val pages = HostBackgroundPages(controller, HostVideoCardHostAccess(activity.classLoader)) { throw it }
        pages.attach(dynamic, HostBackgroundPagePolicy.Kind.DYNAMIC)
        pages.attach(comment, HostBackgroundPagePolicy.Kind.COMMENT)
        // Activity 回调晚于子页面时不能吞掉专用渲染，评论层也不能被动态页面忽略。
        pages.attach(window, HostBackgroundPagePolicy.Kind.NATIVE)
        window.viewTreeObserver.dispatchOnGlobalLayout()
        window.viewTreeObserver.dispatchOnPreDraw()
        assertSame(window, HostBackgroundPages.owner(player))
        assertSame(dynamic, HostBackgroundPages.owner(dynamic))
        assertSame(comment, HostBackgroundPages.owner(list))
        assertTrue(window.background is HostBackgroundDrawable)
        assertTrue(dynamic.background is HostBackgroundDrawable)
        assertTrue(comment.background is HostBackgroundDrawable)
        assertNull(list.background)
        assertTrue(list.childCount > 0)
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i) as ViewGroup
            assertNull(row.background)
            assertNull(row.getChildAt(0).background)
        }
        assertSame(playerSurface, player.background)
        assertSame(playerChildSurface, player.getChildAt(0).background)
        assertEquals(Color.TRANSPARENT, (chrome.background as MaterialShapeDrawable).fillColor?.defaultColor)
        assertEquals(2f, (chrome.background as MaterialShapeDrawable).strokeWidth, .01f)
        assertEquals(Color.TRANSPARENT, (gradient.background as GradientDrawable).color?.defaultColor)
        val inputLayers = input.background as LayerDrawable
        assertEquals(Color.TRANSPARENT, (inputLayers.getDrawable(0) as ColorDrawable).color)
        assertEquals("Input business colors must be retained", Color.RED, (inputLayers.getDrawable(1) as ColorDrawable).color)
        assertEquals("Input separators must be retained", Color.GRAY, (inputLayers.getDrawable(2) as ColorDrawable).color)
        assertEquals("Small neutral controls must be retained", Color.WHITE, (inputLayers.getDrawable(3) as ColorDrawable).color)
        val borderedInput = inputWithLine.background as LayerDrawable
        assertEquals(1, borderedInput.getLayerHeight(0))
        assertEquals(Color.GRAY, (borderedInput.getDrawable(0) as GradientDrawable).color?.defaultColor)
        assertEquals(Color.TRANSPARENT, (borderedInput.getDrawable(1) as GradientDrawable).color?.defaultColor)
        assertEquals(0xffe3e5e7.toInt(), label.currentTextColor)
        assertEquals(Color.BLACK, brightLabel.currentTextColor)
        assertTrue(HostBackgroundPages.protectedAncestor(player.getChildAt(0)))
        controller.apply(list, true)
        assertNull(list.background)
        assertTrue((comment.background as HostBackgroundDrawable).night)
        decor.removeView(window)
    }
}
