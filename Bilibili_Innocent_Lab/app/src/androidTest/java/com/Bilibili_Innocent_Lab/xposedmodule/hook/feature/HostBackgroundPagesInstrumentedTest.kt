package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.ContextWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.ListView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import com.google.android.material.shape.MaterialShapeDrawable
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
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
import io.github.jichuo1.meguviz.android.extensions.rootStatusBarInsetTopOrNull
import java.util.WeakHashMap

@RunWith(AndroidJUnit4::class)
class HostBackgroundPagesInstrumentedTest {
    private fun resourceContext(activity: MainActivity) =
        object : ContextWrapper(InstrumentationRegistry.getInstrumentation().context) {
            override fun getApplicationContext() = activity.applicationContext
        }

    @Test fun channelSuggestionsCoverResultsAcrossQueryAndVisibilityChanges() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val context = resourceContext(activity)
                val decor = activity.window.decorView as ViewGroup
                val root = FrameLayout(context)
                val content = FrameLayout(context).apply { id = TestR.id.content_frame }
                val results = RecyclerView(context).apply {
                    id = TestR.id.recycler
                    layoutManager = LinearLayoutManager(context)
                }
                val suggestions = ListView(context).apply {
                    id = TestR.id.suggest_list
                    setBackgroundColor(Color.WHITE)
                    adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, arrayOf("suggestion"))
                    setPadding(3, 5, 7, 9)
                }
                val original = suggestions.background
                content.addView(results, FrameLayout.LayoutParams(-1, -1))
                content.addView(suggestions, FrameLayout.LayoutParams(-1, -1))
                root.addView(content, FrameLayout.LayoutParams(-1, -1))
                decor.addView(root, ViewGroup.LayoutParams(320, 640))
                val controller = HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.AURORA), { throw it })
                val pages = HostBackgroundPages(controller, HostVideoCardHostAccess(activity.classLoader)) { throw it }
                fun frame() {
                    root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, 320, 640)
                    root.viewTreeObserver.dispatchOnGlobalLayout()
                    repeat(2) { root.viewTreeObserver.dispatchOnPreDraw() }
                }
                try {
                    pages.attach(root, HostBackgroundPagePolicy.Kind.NATIVE)
                    frame()
                    assertSame("Suggestions must cover results even below their last row", original, suggestions.background)
                    assertTrue(HostBackgroundPages.protectedAncestor(suggestions.getChildAt(0)))
                    val bitmap = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888)
                    try {
                        root.draw(Canvas(bitmap))
                        assertEquals(Color.WHITE, bitmap.getPixel(160, 600))
                    } finally { bitmap.recycle() }
                    for (visibility in listOf(View.GONE, View.VISIBLE, View.GONE, View.VISIBLE)) {
                        suggestions.visibility = visibility
                        frame()
                        assertSame(original, suggestions.background)
                        assertEquals(View.VISIBLE, results.visibility)
                    }
                    var clicked = false
                    suggestions.setOnItemClickListener { _, _, _, _ -> clicked = true }
                    suggestions.performItemClick(suggestions.getChildAt(0), 0, 0)
                    assertTrue(clicked)
                    assertEquals(listOf(3, 5, 7, 9), listOf(suggestions.paddingLeft, suggestions.paddingTop,
                        suggestions.paddingRight, suggestions.paddingBottom))
                    pages.attach(suggestions, HostBackgroundPagePolicy.Kind.PAGE)
                    assertSame(root, HostBackgroundPages.owner(suggestions))
                } finally { decor.removeView(root) }
            }
        }
    }

    @Test fun suggestionNamesAloneDoNotProtectAnOrdinaryPageList() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val context = resourceContext(activity)
                val content = FrameLayout(context).apply { id = TestR.id.content_frame }
                val list = ListView(context).apply { id = TestR.id.suggest_list }
                content.addView(list)
                assertFalse(HostBackgroundPages.protectedAncestor(list))
                content.addView(View(context).apply { id = TestR.id.recycler })
                content.id = View.NO_ID
                assertFalse(HostBackgroundPages.protectedAncestor(list))
            }
        }
    }

    @Test fun commentRestrictionMasksKeepTheirSurfaceAndCloseAction() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val context = resourceContext(activity)
                val decor = activity.window.decorView as ViewGroup
                val root = FrameLayout(context).apply { id = TestR.id.root_container }
                val content = FrameLayout(context).apply { id = TestR.id.content }
                val footer = FrameLayout(context).apply { id = TestR.id.footer_container }
                content.addView(footer)
                root.addView(content, FrameLayout.LayoutParams(-1, -1))
                decor.addView(root, ViewGroup.LayoutParams(320, 640))
                val pages = HostBackgroundPages(HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.AURORA), { throw it }),
                    HostVideoCardHostAccess(activity.classLoader)) { throw it }
                fun frame() {
                    root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, 320, 640)
                    root.viewTreeObserver.dispatchOnGlobalLayout()
                    repeat(2) { root.viewTreeObserver.dispatchOnPreDraw() }
                }
                try {
                    pages.attach(root, HostBackgroundPagePolicy.Kind.NATIVE)
                    for (label in listOf("课堂模式", "青少年模式")) {
                        val mask = NestedScrollView(context).apply { isFillViewport = true }
                        val body = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            isClickable = true
                            isFocusable = true
                            setBackgroundColor(Color.WHITE)
                        }
                        val icon = ImageView(context).apply { setBackgroundColor(Color.MAGENTA) }
                        val title = TextView(context).apply { text = label; setTextColor(Color.BLACK) }
                        val close = TextView(context).apply { id = TestR.id.close; text = "关闭" }
                        body.addView(icon, LinearLayout.LayoutParams(80, 80))
                        body.addView(title)
                        body.addView(close)
                        val original = body.background
                        mask.addView(body, ViewGroup.LayoutParams(-1, -1))
                        root.addView(mask, FrameLayout.LayoutParams(-1, -1))
                        frame()
                        assertSame("The restriction mask must cover the comments", original, body.background)
                        assertTrue(HostBackgroundPages.protectedAncestor(title))
                        pages.attach(mask, HostBackgroundPagePolicy.Kind.COMMENT)
                        assertSame(root, HostBackgroundPages.owner(mask))
                        val bitmap = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888)
                        try {
                            root.draw(Canvas(bitmap))
                            assertEquals(Color.WHITE, bitmap.getPixel(160, 600))
                        } finally { bitmap.recycle() }
                        close.setOnClickListener { root.removeView(mask) }
                        close.performClick()
                        frame()
                        assertSame(original, body.background)
                        assertEquals(Color.BLACK, title.currentTextColor)
                        content.removeView(footer)
                        root.addView(mask, FrameLayout.LayoutParams(-1, -1))
                        assertFalse("The parent comment layout must also match", HostBackgroundPages.protectedAncestor(mask))
                        root.removeView(mask)
                        content.addView(footer)
                    }
                } finally { decor.removeView(root) }
            }
        }
    }

    @Test fun searchToolbarKeepsItsMaterialWhenResultTabsCollapse() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val context = resourceContext(activity)
                val decor = activity.window.decorView as ViewGroup
                val root = FrameLayout(context).apply { setBackgroundColor(Color.WHITE) }
                val toolbar = View(context).apply { id = TestR.id.search_input_layout }
                val tabs = View(context).apply { id = TestR.id.container_strip }
                val results = FrameLayout(context)
                root.addView(toolbar, FrameLayout.LayoutParams(-1, 100))
                results.addView(tabs, FrameLayout.LayoutParams(-1, 60))
                root.addView(results, FrameLayout.LayoutParams(-1, 540).apply { topMargin = 100 })
                decor.addView(root, ViewGroup.LayoutParams(320, 640))
                root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 320, 640)
                val chrome = HostPageChrome(root, WeakHashMap())
                val bitmap = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888)
                try {
                    chrome.discover()
                    fun toolbarPixel(): Int {
                        chrome.apply(true)
                        root.draw(Canvas(bitmap))
                        return bitmap.getPixel(10, 80)
                    }
                    val expanded = toolbarPixel()
                    assertTrue("The search toolbar must have a visible material", Color.red(expanded) < 220)
                    // AppBarLayout 用负偏移收起分类栏，搜索栏位置和大小保持不变。
                    tabs.offsetTopAndBottom(-60)
                    assertEquals("Collapsing tabs must not fade out the search toolbar", expanded, toolbarPixel())
                    tabs.visibility = View.GONE
                    assertEquals("Fully hidden tabs must retain the toolbar material", expanded, toolbarPixel())
                    tabs.offsetTopAndBottom(60)
                    tabs.visibility = View.VISIBLE
                    assertEquals("Expanding tabs must keep the same toolbar intensity", expanded, toolbarPixel())
                } finally {
                    chrome.close()
                    decor.removeView(root)
                    bitmap.recycle()
                }
            }
        }
    }

    @Test fun statusBarFollowsAdjacentChromeAndReleasesItsNonInteractiveBand() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val root = activity.window.decorView as FrameLayout
                val context = resourceContext(activity)
                val inset = root.rootStatusBarInsetTopOrNull() ?: 0
                assertTrue("The fixture needs a visible system status bar", inset > 0)
                val originalCount = root.childCount
                val toolbar = View(context).apply { id = TestR.id.nav_top_bar }
                val authors = View(context).apply { id = TestR.id.dy_recycler; visibility = View.GONE }
                root.addView(toolbar, FrameLayout.LayoutParams(-1, 100).apply { topMargin = inset })
                root.addView(authors, FrameLayout.LayoutParams(-1, 100).apply { topMargin = inset })
                // 先确定栏位的窗口坐标，后续切换不挪动宿主布局。
                toolbar.layout(0, inset, root.width, inset + 100)
                authors.layout(0, inset, root.width, inset + 100)
                val chrome = HostPageChrome(root, WeakHashMap())
                try {
                    chrome.discover(); chrome.apply(true)
                    assertEquals(originalCount + 3, root.childCount)
                    val band = root.getChildAt(root.childCount - 1)
                    assertEquals(inset, band.layoutParams.height)
                    assertNotNull(band.background)
                    assertFalse(band.isClickable)
                    assertFalse(band.isFocusable)
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, band.importantForAccessibility)
                    val toolbarMaterial = band.background
                    toolbar.visibility = View.GONE
                    chrome.apply(true)
                    assertEquals("A page without adjacent chrome must expose its wallpaper", View.GONE, band.visibility)
                    authors.visibility = View.VISIBLE
                    chrome.discover(); chrome.apply(true)
                    assertSame("Page switches must reuse one status band", band, root.getChildAt(root.childCount - 1))
                    assertEquals(View.VISIBLE, band.visibility)
                    assertNotSame("The authors' lighter material must replace the toolbar material", toolbarMaterial, band.background)
                    authors.translationX = root.width.toFloat()
                    chrome.apply(true)
                    assertEquals("Offscreen pages must not leave their status tint behind", View.GONE, band.visibility)
                    authors.translationX = 0f
                    authors.alpha = 0f
                    chrome.apply(true)
                    assertEquals(View.GONE, band.visibility)
                    authors.alpha = 1f
                    authors.layout(0, inset, root.width, inset)
                    chrome.apply(true)
                    assertEquals("Collapsed bars must not leave a status tint", View.GONE, band.visibility)
                    authors.layout(0, inset, root.width, inset + 100)
                    chrome.apply(false)
                    assertEquals(originalCount + 3, root.childCount)
                    assertEquals(inset, toolbar.top)
                } finally {
                    chrome.close()
                    root.removeView(toolbar); root.removeView(authors)
                }
                assertEquals("The added status surface must leave with its session", originalCount, root.childCount)
                assertNull(toolbar.background)
                assertNull(authors.background)
            }
        }
    }

    @Test fun embeddedCommentImageViewerKeepsItsBackdropAndControlsInsideNativePages() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val decor = activity.window.decorView as ViewGroup
                val root = FrameLayout(activity)
                val viewer = com.bilibili.app.comment3.ui.widget.imagecardviewer.ui.widget.ViewerFixture(activity).apply {
                    setBackgroundColor(Color.BLACK)
                }
                val mediaBackground = viewer.background
                val controls = FrameLayout(activity).apply { setBackgroundColor(Color.DKGRAY) }
                val controlsBackground = controls.background
                val title = TextView(activity).apply { text = "查看原图"; setTextColor(Color.WHITE) }
                val image = ImageView(activity).apply { setImageDrawable(ColorDrawable(0x80ffffff.toInt())) }
                val content = image.drawable
                controls.addView(title, FrameLayout.LayoutParams(-1, -1))
                viewer.addView(image, FrameLayout.LayoutParams(-1, -1))
                viewer.addView(controls, FrameLayout.LayoutParams(-1, 60))
                root.addView(viewer, FrameLayout.LayoutParams(-1, -1))
                decor.addView(root, ViewGroup.LayoutParams(320, 640))
                root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 320, 640)
                val pages = HostBackgroundPages(HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.NEBULA), { throw it }),
                    HostVideoCardHostAccess(activity.classLoader)) { throw it }
                pages.attach(root, HostBackgroundPagePolicy.Kind.NATIVE)
                pages.attach(viewer, HostBackgroundPagePolicy.Kind.COMMENT)
                repeat(2) {
                    root.viewTreeObserver.dispatchOnGlobalLayout()
                    root.viewTreeObserver.dispatchOnPreDraw()
                    assertTrue(root.background is HostBackgroundDrawable)
                    assertSame(root, HostBackgroundPages.owner(viewer))
                    assertTrue(HostBackgroundPages.protectedAncestor(controls))
                    assertSame(mediaBackground, viewer.background)
                    assertSame(controlsBackground, controls.background)
                    assertSame(content, image.drawable)
                    assertEquals(Color.WHITE, title.currentTextColor)
                }
                decor.removeView(root)
            }
        }
    }

    @Test fun embeddedOpusCommentsShareTheArticleWorldButReplyOverlaysStayIndependent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val context = resourceContext(activity)
                val decor = activity.window.decorView as ViewGroup
                for (childFirst in listOf(false, true)) {
                    val root = FrameLayout(context)
                    val article = RecyclerView(context).apply { id = TestR.id.opus_dy_list; layoutManager = LinearLayoutManager(context) }
                    val tabs = View(context).apply { setBackgroundColor(Color.WHITE) }
                    val pager = FrameLayout(context).apply { id = TestR.id.opus_pager }
                    val comment = FrameLayout(context).apply { setBackgroundColor(Color.WHITE) }
                    val list = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context); setBackgroundColor(Color.WHITE) }
                    root.addView(article, FrameLayout.LayoutParams(-1, 240))
                    root.addView(tabs, FrameLayout.LayoutParams(-1, 40).apply { topMargin = 240 })
                    root.addView(pager, FrameLayout.LayoutParams(-1, 360).apply { topMargin = 280 })
                    pager.addView(comment, FrameLayout.LayoutParams(-1, -1))
                    comment.addView(list, FrameLayout.LayoutParams(-1, -1))
                    decor.addView(root, ViewGroup.LayoutParams(320, 640))
                    root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, 320, 640)
                    val controller = HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.NEBULA), { throw it })
                    val pages = HostBackgroundPages(controller, HostVideoCardHostAccess(activity.classLoader)) { throw it }
                    fun draw() { root.viewTreeObserver.dispatchOnGlobalLayout(); root.viewTreeObserver.dispatchOnPreDraw() }
                    if (childFirst) { pages.attach(comment, HostBackgroundPagePolicy.Kind.COMMENT); draw() }
                    pages.attach(root, HostBackgroundPagePolicy.Kind.OPUS)
                    pages.attach(comment, HostBackgroundPagePolicy.Kind.COMMENT)
                    draw()
                    assertSame(root, HostBackgroundPages.sharedWallpaperOwner(comment))
                    assertNull(comment.background)
                    assertNull(list.background)
                    assertNull(tabs.background)
                    val wallpaper = root.background as HostBackgroundDrawable
                    assertTrue(pages.scroll(article, 200))
                    assertEquals(100L, wallpaper.scrollOffset)
                    assertTrue(pages.scroll(list, 80))
                    draw()
                    assertEquals(140L, wallpaper.scrollOffset)
                    // 正文已移出视口，不能丢掉其累计距离，也不能由绘制回调顺序覆写评论距离。
                    article.translationY = -640f
                    draw()
                    assertEquals(140L, wallpaper.scrollOffset)
                    controller.apply(list, true)
                    assertNull(comment.background)
                    val reply = FrameLayout(context).apply { setBackgroundColor(Color.WHITE) }
                    root.addView(reply, FrameLayout.LayoutParams(-1, -1))
                    pages.attach(reply, HostBackgroundPagePolicy.Kind.COMMENT)
                    draw()
                    assertNull(HostBackgroundPages.sharedWallpaperOwner(reply))
                    assertTrue(reply.background is HostBackgroundDrawable)
                    root.removeView(reply)
                    assertTrue(pages.scroll(list, -80))
                    draw()
                    assertEquals(100L, wallpaper.scrollOffset)
                    decor.removeView(root)
                }
            }
        }
    }

    @Test fun favoriteTabActionShadeRevealsWallpaperAcrossHostReapplication() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val context = resourceContext(activity)
                val decor = activity.window.decorView as ViewGroup
                val root = FrameLayout(context)
                val tabs = FrameLayout(context).apply { id = TestR.id.top_container }
                val shade = View(context).apply { id = TestR.id.right_button_start_bg; setPadding(2, 3, 4, 5) }
                val button = FrameLayout(context).apply { id = TestR.id.right_button }
                val icon = ImageView(context).apply { id = TestR.id.icon_2; setImageDrawable(ColorDrawable(Color.GRAY)) }
                val iconDrawable = icon.drawable
                button.addView(icon, FrameLayout.LayoutParams(-1, -1))
                tabs.addView(View(context).apply { id = TestR.id.tab_layout }, FrameLayout.LayoutParams(240, 60))
                tabs.addView(shade, FrameLayout.LayoutParams(12, 60).apply { leftMargin = 240 })
                tabs.addView(button, FrameLayout.LayoutParams(68, 60).apply { leftMargin = 252 })
                // 收藏的筛选栏实际位于 ViewPager2 的内部 RecyclerView 中。
                val pager = ViewPager2(context).apply {
                    adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                        override fun getItemCount() = 1
                        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                            object : RecyclerView.ViewHolder(FrameLayout(context).apply {
                                layoutParams = ViewGroup.LayoutParams(-1, -1)
                                addView(tabs, FrameLayout.LayoutParams(-1, -1))
                            }) {}
                        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
                    }
                }
                root.addView(pager, FrameLayout.LayoutParams(-1, 60))
                // 同名但不属于收藏标签栏的业务渐变必须保留。
                val unrelated = View(context).apply {
                    id = TestR.id.right_button_start_bg
                    background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.TRANSPARENT, Color.WHITE))
                }
                val original = unrelated.background
                root.addView(unrelated, FrameLayout.LayoutParams(12, 60).apply { topMargin = 60 })
                decor.addView(root, ViewGroup.LayoutParams(320, 640))
                root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 320, 640)
                val pages = HostBackgroundPages(HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.NEBULA), { throw it }),
                    HostVideoCardHostAccess(activity.classLoader)) { throw it }
                pages.attach(root, HostBackgroundPagePolicy.Kind.NATIVE)
                for (color in listOf(Color.WHITE, Color.BLACK, Color.MAGENTA)) {
                    shade.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.TRANSPARENT, color))
                    button.background = ColorDrawable(color)
                    icon.background = ColorDrawable(color)
                    val background = shade.background
                    val buttonBackground = button.background
                    val iconBackground = icon.background
                    repeat(2) {
                        root.viewTreeObserver.dispatchOnGlobalLayout()
                        root.viewTreeObserver.dispatchOnPreDraw()
                        assertNotNull("The favorite filter inside ViewPager2 needs its own material", tabs.background)
                        if (color == Color.MAGENTA) assertSame(background, shade.background) else assertNull(shade.background)
                        if (color == Color.MAGENTA) {
                            assertSame(buttonBackground, button.background)
                            assertSame(iconBackground, icon.background)
                        } else {
                            assertNull(button.background)
                            assertNull(icon.background)
                        }
                        assertSame(original, unrelated.background)
                        assertSame(iconDrawable, icon.drawable)
                        assertEquals(listOf(2, 3, 4, 5), listOf(shade.paddingLeft, shade.paddingTop, shade.paddingRight, shade.paddingBottom))
                    }
                }
                decor.removeView(root)
            }
        }
    }

    @Test fun storyMediaRootsAndControlsKeepTheirSurfacesAcrossLayoutChanges() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val context = resourceContext(activity)
                val decor = activity.window.decorView as ViewGroup
                val root = FrameLayout(context)
                val media = FrameLayout(context).apply { id = TestR.id.story_activity_root; setBackgroundColor(Color.BLACK) }
                val controls = FrameLayout(context).apply { id = TestR.id.story_list_item_controller; setBackgroundColor(Color.TRANSPARENT) }
                val shade = View(context).apply { setBackgroundColor(Color.DKGRAY) }
                val list = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context); setBackgroundColor(Color.BLACK) }
                root.addView(media, FrameLayout.LayoutParams(-1, -1))
                media.addView(list, FrameLayout.LayoutParams(-1, -1))
                media.addView(controls, FrameLayout.LayoutParams(-1, -1))
                controls.addView(shade, FrameLayout.LayoutParams(-1, 100))
                decor.addView(root, ViewGroup.LayoutParams(320, 640))
                root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 320, 640)
                val surfaces = listOf(media, controls, shade, list)
                val originals = surfaces.map { it.background }
                val controller = HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.NEBULA), { throw it })
                val pages = HostBackgroundPages(controller, HostVideoCardHostAccess(activity.classLoader)) { throw it }
                // 即使嵌在已接入的普通页里，也不能把壁纸附到媒体根或清理其控制器。
                pages.attach(root, HostBackgroundPagePolicy.Kind.NATIVE)
                pages.attach(media, HostBackgroundPagePolicy.Kind.NATIVE)
                pages.attach(controls, HostBackgroundPagePolicy.Kind.PAGE)
                repeat(3) { pass ->
                    controls.visibility = if (pass == 1) View.INVISIBLE else View.VISIBLE
                    root.viewTreeObserver.dispatchOnGlobalLayout()
                    root.viewTreeObserver.dispatchOnPreDraw()
                    surfaces.forEachIndexed { index, view ->
                        assertSame(originals[index], view.background)
                        assertTrue(HostBackgroundPages.protectedAncestor(view))
                        assertSame(root, HostBackgroundPages.owner(view))
                    }
                    assertFalse(pages.scroll(list, 200))
                }
                decor.removeView(root)
            }
        }
    }

    @Test fun tabActionGradientsRevealWallpaperWithoutChangingIconsOrOtherImages() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val context = resourceContext(activity)
                val decor = activity.window.decorView as ViewGroup
                val root = FrameLayout(context)
                val strip = FrameLayout(context).apply { id = TestR.id.container_strip }
                val filterContainer = FrameLayout(context).apply { id = TestR.id.filter_icon_container }
                val filter = ImageView(context).apply { id = TestR.id.filter_icon; setImageDrawable(ColorDrawable(Color.GRAY)); setPadding(28, 0, 16, 0) }
                val tabs = FrameLayout(context).apply { id = TestR.id.tab_box }
                val manageContainer = FrameLayout(context)
                val manage = ImageView(context).apply { id = TestR.id.history_manage; setImageDrawable(ColorDrawable(Color.GRAY)) }
                val cover = ImageView(context)
                fun gradient(color: Int) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.TRANSPARENT, color))
                cover.background = gradient(Color.WHITE)
                val coverBackground = cover.background
                val filterIcon = filter.drawable
                val manageIcon = manage.drawable
                root.addView(strip, FrameLayout.LayoutParams(-1, 60))
                strip.addView(filterContainer, FrameLayout.LayoutParams(64, 60))
                filterContainer.addView(filter, FrameLayout.LayoutParams(-1, -1))
                root.addView(tabs, FrameLayout.LayoutParams(-1, 60).apply { topMargin = 60 })
                tabs.addView(manageContainer, FrameLayout.LayoutParams(62, 34))
                manageContainer.addView(manage, FrameLayout.LayoutParams(20, 20))
                root.addView(cover, FrameLayout.LayoutParams(100, 100).apply { topMargin = 120 })
                decor.addView(root, ViewGroup.LayoutParams(320, 640))
                root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 320, 640)
                val controller = HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.NEBULA), { throw it })
                val pages = HostBackgroundPages(controller, HostVideoCardHostAccess(activity.classLoader)) { throw it }
                pages.attach(root, HostBackgroundPagePolicy.Kind.NATIVE)
                for (color in listOf(Color.WHITE, Color.BLACK, Color.MAGENTA)) {
                    filter.background = gradient(color)
                    manageContainer.background = LayerDrawable(arrayOf(gradient(color), GradientDrawable().apply { setColor(color) })).apply {
                        setLayerInset(0, 0, 0, 50, 0)
                        setLayerInset(1, 12, 0, 0, 0)
                    }
                    val filterBackground = filter.background
                    val manageBackground = manageContainer.background
                    repeat(2) {
                        root.viewTreeObserver.dispatchOnGlobalLayout()
                        root.viewTreeObserver.dispatchOnPreDraw()
                        if (color == Color.MAGENTA) {
                            assertSame(filterBackground, filter.background)
                            assertSame(manageBackground, manageContainer.background)
                        } else {
                            assertNull(filter.background)
                            assertNull(manageContainer.background)
                        }
                        assertSame(filterIcon, filter.drawable)
                        assertSame(manageIcon, manage.drawable)
                        assertSame(coverBackground, cover.background)
                        assertEquals(28, filter.paddingLeft)
                        assertEquals(16, filter.paddingRight)
                    }
                }
                decor.removeView(root)
            }
        }
    }

    @Test fun splashAndPinnedSelectionHeaderKeepTheirOccludingSurfaces() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val context = resourceContext(activity)
                val decor = activity.window.decorView as ViewGroup
                val root = FrameLayout(context)
                val splash = FrameLayout(context).apply { id = TestR.id.splash_layout; setBackgroundColor(Color.BLACK) }
                val splashContent = FrameLayout(context).apply { id = TestR.id.splash_container; setBackgroundColor(Color.WHITE) }
                val selection = FrameLayout(context)
                val list = RecyclerView(context).apply { id = TestR.id.select_list; layoutManager = LinearLayoutManager(context) }
                val header = FrameLayout(context).apply { id = TestR.id.head_layout; setBackgroundColor(Color.DKGRAY) }
                val headerChild = View(context).apply { setBackgroundColor(Color.GRAY) }
                val title = FrameLayout(context).apply { id = TestR.id.top_layout; setBackgroundColor(Color.DKGRAY) }
                header.addView(headerChild, FrameLayout.LayoutParams(-1, -1))
                selection.addView(list, FrameLayout.LayoutParams(-1, -1))
                selection.addView(header, FrameLayout.LayoutParams(-1, 100))
                selection.addView(title, FrameLayout.LayoutParams(-1, 40))
                val plainHeader = View(context).apply { id = TestR.id.head_layout; setBackgroundColor(Color.WHITE) }
                root.addView(selection, FrameLayout.LayoutParams(-1, -1))
                root.addView(plainHeader, FrameLayout.LayoutParams(-1, 40))
                root.addView(splash, FrameLayout.LayoutParams(-1, -1))
                root.addView(splashContent, FrameLayout.LayoutParams(-1, -1))
                val overlays = listOf(splash, splashContent, header, headerChild, title)
                val originals = overlays.map { it.background }
                decor.addView(root, ViewGroup.LayoutParams(320, 640))
                root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 320, 640)
                val controller = HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.NEBULA), { throw it })
                val pages = HostBackgroundPages(controller, HostVideoCardHostAccess(activity.classLoader)) { throw it }
                pages.attach(root, HostBackgroundPagePolicy.Kind.NATIVE)
                repeat(2) {
                    root.viewTreeObserver.dispatchOnGlobalLayout()
                    root.viewTreeObserver.dispatchOnPreDraw()
                    overlays.forEachIndexed { index, view ->
                        assertSame(originals[index], view.background)
                        assertTrue(HostBackgroundPages.protectedAncestor(view))
                    }
                    assertNull(plainHeader.background)
                    assertNull(list.background)
                    assertTrue(root.background is HostBackgroundDrawable)
                    splash.visibility = View.INVISIBLE
                    header.translationY = -50f
                }
                decor.removeView(root)
            }
        }
    }

    @Test fun commentHeaderContentAndInputShareOneWallpaperInEitherAttachOrder() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
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
                    assertEquals(68L, (root.background as HostBackgroundDrawable).scrollOffset)
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
                activity.activeConfirmDialog?.dismiss()
                val decor = activity.window.decorView as ViewGroup
                val testResources = InstrumentationRegistry.getInstrumentation().context.resources
                val context = object : ContextWrapper(activity) {
                    override fun getResources() = testResources
                }
                val round = FrameLayout(context).apply { id = TestR.id.round_container }
                val panel = FrameLayout(context).apply { id = TestR.id.content_container }
                val pager = FrameLayout(context).apply { id = TestR.id.pager }
                val header = View(activity).apply { setBackgroundColor(Color.WHITE) }
                val comment = FrameLayout(activity).apply { setBackgroundColor(Color.WHITE) }
                val list = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity) }
                val intro = FrameLayout(activity).apply { setBackgroundColor(Color.WHITE); translationX = 320f }
                val introList = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity) }
                round.addView(panel, FrameLayout.LayoutParams(-1, -1))
                panel.addView(header, FrameLayout.LayoutParams(-1, 80))
                panel.addView(pager, FrameLayout.LayoutParams(-1, 560).apply { topMargin = 80 })
                pager.addView(comment, FrameLayout.LayoutParams(-1, -1))
                comment.addView(list, FrameLayout.LayoutParams(-1, -1))
                pager.addView(intro, FrameLayout.LayoutParams(-1, -1))
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
                // 楼中楼与标签页是兄弟叠层，宿主保留底层主评论的可见性，弹层必须有独立底板。
                val overlayHost = FrameLayout(context)
                val detail = FrameLayout(context).apply { setBackgroundColor(Color.WHITE) }
                val detailList = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
                detail.addView(detailList, FrameLayout.LayoutParams(-1, -1))
                overlayHost.addView(detail, FrameLayout.LayoutParams(-1, -1))
                panel.addView(overlayHost, FrameLayout.LayoutParams(-1, -1))
                round.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                round.layout(0, 0, 320, 640)
                pages.attach(detail, HostBackgroundPagePolicy.Kind.COMMENT)
                round.viewTreeObserver.dispatchOnGlobalLayout()
                round.viewTreeObserver.dispatchOnPreDraw()
                assertNull("Reply overlays must not share the wallpaper behind the main comments", HostBackgroundPages.sharedWallpaperOwner(detail))
                assertSame(detail, HostBackgroundPages.owner(detailList))
                assertTrue(detail.background is HostBackgroundDrawable)
                assertNull(detailList.background)
                controller.apply(detailList, true)
                assertTrue(detail.background is HostBackgroundDrawable)
                assertEquals(View.VISIBLE, comment.visibility)
                assertEquals(View.VISIBLE, pager.visibility)
                panel.removeView(overlayHost)
                val wallpaper = panel.background as HostBackgroundDrawable
                assertTrue(pages.scroll(list, 281))
                assertEquals(140L, wallpaper.scrollOffset)
                assertFalse("Offscreen introduction must not move the header", pages.scroll(introList, 50))
                intro.translationX = 0f
                comment.translationX = 320f
                round.viewTreeObserver.dispatchOnPreDraw()
                assertEquals("The header must restore introduction's position", 0L, wallpaper.scrollOffset)
                assertTrue(pages.scroll(introList, 137))
                assertEquals(68L, wallpaper.scrollOffset)
                controller.apply(introList, false)
                assertNull(intro.background)
                assertNull(introList.background)
                intro.translationX = -80f
                comment.translationX = 240f
                round.viewTreeObserver.dispatchOnPreDraw()
                assertEquals("A quarter swipe must interpolate the two scroll positions", 86L, wallpaper.scrollOffset)
                intro.translationX = -160f
                comment.translationX = 160f
                round.viewTreeObserver.dispatchOnPreDraw()
                assertEquals("Halfway through paging, callback order must not select a tab abruptly", 104L, wallpaper.scrollOffset)
                intro.translationX = -320f
                comment.translationX = 0f
                round.viewTreeObserver.dispatchOnPreDraw()
                assertEquals("Returning to comments must restore their position", 140L, wallpaper.scrollOffset)
                controller.apply(list, true)
                assertTrue(wallpaper.night)
                assertNull(list.background)
                comment.visibility = View.GONE
                assertFalse(pages.scroll(list, 50))
                comment.visibility = View.VISIBLE
                round.viewTreeObserver.dispatchOnPreDraw()
                assertEquals(140L, wallpaper.scrollOffset)
                // 普通窗口/动态评论弹层不应借用视频前景面板。
                round.id = View.NO_ID
                assertNull(HostBackgroundPages.sharedWallpaperOwner(comment))
                decor.removeView(round)
            }
        }
    }

    @Test fun personalFeedSharesWallpaperWithAuthorsAndKeepsChromeAcrossLayouts() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val decor = activity.window.decorView as ViewGroup
                val context = resourceContext(activity)
                val window = FrameLayout(context)
                val authors = RecyclerView(context).apply {
                    id = TestR.id.dy_recycler
                    layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
                }
                val feed = FrameLayout(context).apply { setBackgroundColor(Color.WHITE) }
                val header = FrameLayout(context).apply {
                    id = TestR.id.header_container
                    setPadding(7, 3, 9, 4)
                }
                var clicks = 0
                val action = TextView(context).apply { text = "关闭"; setOnClickListener { clicks++ } }
                header.addView(action)
                val list = RecyclerView(context).apply {
                    id = TestR.id.recycler
                    layoutManager = LinearLayoutManager(activity)
                    setBackgroundColor(Color.WHITE)
                }
                feed.addView(header, FrameLayout.LayoutParams(-1, 48))
                feed.addView(list, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 48 })
                window.addView(authors, FrameLayout.LayoutParams(-1, 80))
                window.addView(feed, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 80 })
                decor.addView(window, ViewGroup.LayoutParams(320, 640))
                window.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                window.layout(0, 0, 320, 640)
                val controller = HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.AURORA), { throw it })
                val pages = HostBackgroundPages(controller, HostVideoCardHostAccess(activity.classLoader)) { throw it }
                pages.attach(feed, HostBackgroundPagePolicy.Kind.DYNAMIC)
                pages.attach(window, HostBackgroundPagePolicy.Kind.NATIVE)
                fun draw() {
                    window.viewTreeObserver.dispatchOnGlobalLayout()
                    window.viewTreeObserver.dispatchOnPreDraw()
                }
                draw()
                val wallpaper = window.background as HostBackgroundDrawable
                assertSame(window, HostBackgroundPages.sharedWallpaperOwner(feed))
                assertNull("Personal content must not restart the image below the authors", feed.background)
                assertNull(list.background)
                assertNotNull(authors.background)
                assertNotNull(header.background)
                val material = header.background
                controller.apply(list, false)
                assertNull(list.background)
                assertTrue(pages.scroll(list, 137))
                assertEquals(68L, wallpaper.scrollOffset)
                draw()
                assertSame("Neutral clearing must retain the installed chrome", material, header.background)
                assertEquals(7, header.paddingLeft)
                assertEquals(4, header.paddingBottom)
                action.performClick()
                assertEquals(1, clicks)
                assertEquals(80, feed.top)
                decor.removeView(window)
                assertNull("Detach must restore the original header background", header.background)
            }
        }
    }

    @Test fun homeFeedsShareTheWindowWallpaperAndKeepTheirOwnScrollPositions() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
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
                assertEquals(68L, wallpaper.scrollOffset)
                controller.apply(first, false) // 卡片监听器也不能重新创建列表壁纸。
                assertNull(first.background)
                feeds[0].translationY = -80f
                draw()
                assertSame(wallpaper, window.background)
                assertEquals(68L, wallpaper.scrollOffset)
                feeds[0].visibility = View.GONE
                feeds[1].visibility = View.VISIBLE
                draw()
                assertEquals(0L, wallpaper.scrollOffset)
                assertNull(feeds[1].background)
                assertNull(second.background)
                assertTrue(pages.scroll(second, 281))
                assertEquals(140L, wallpaper.scrollOffset)
                assertFalse("Hidden feeds must not move the visible wallpaper", pages.scroll(first, 50))
                feeds[1].visibility = View.GONE
                feeds[0].visibility = View.VISIBLE
                draw()
                assertEquals("Returning to a feed must restore its own position", 68L, wallpaper.scrollOffset)
                decor.removeView(window)
            }
        }
    }

    @Test fun emptyDynamicPageKeepsCardLayerAndRestoresWallpaperAfterReattach() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
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
                assertEquals(60L, wallpaper.scrollOffset)
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
                assertEquals(60L, wallpaper.scrollOffset)
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
        assertNull("Dynamic content must expose the shared window wallpaper", dynamic.background)
        assertSame(window, HostBackgroundPages.sharedWallpaperOwner(dynamic))
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
