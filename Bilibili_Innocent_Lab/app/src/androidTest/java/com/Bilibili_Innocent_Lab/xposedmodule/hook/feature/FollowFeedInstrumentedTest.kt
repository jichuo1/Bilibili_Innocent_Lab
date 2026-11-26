package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedController
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedHeader
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedPersonalChrome
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedPersonalSelection
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedSelection
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedStyle
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedViewEdits
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedViewport

import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FollowFeedInstrumentedTest {
    private fun main(block: (Context) -> Unit) {
        val test = InstrumentationRegistry.getInstrumentation()
        test.runOnMainSync { block(test.targetContext) }
    }

    private fun pixel(view: View, width: Int = 240, height: Int = 160): Int {
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val result = bitmap.getPixel(width / 2, height / 2)
        bitmap.recycle()
        return result
    }

    @Test fun installedHostRetainsTheAuditedModelAndPainterContracts() = main { context ->
        val host = context.createPackageContext("tv.danmaku.bili",
            Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        val access = requireNotNull(FollowFeedHostAccess.resolve(host.classLoader))
        val points = requireNotNull(FollowFeedAdapter.direct(host.classLoader))
        val adapted = requireNotNull(FollowFeedAdapter.resolve(host.classLoader,
            points.copy(personalDecorationClass = null, personalSeparatorClass = null)))
        assertEquals("gv1.c", requireNotNull(adapted.personalDecoration).name)
        assertNotNull(adapted.separator)
        val item = host.classLoader.loadClass("com.bilibili.bplus.followinglist.model.q0")
        assertEquals(item, access.moduleClass.getDeclaredMethod("M").returnType)
        assertEquals(Long::class.javaPrimitiveType, item.getDeclaredMethod("f").returnType)
        assertEquals(item, access.moduleClass.getDeclaredMethod("O").returnType)
        assertEquals(Boolean::class.javaPrimitiveType, access.moduleClass.getDeclaredMethod("f0").returnType)
        assertTrue(List::class.java.isAssignableFrom(item.getDeclaredMethod("i").returnType))
        assertTrue(access.moduleClass.getDeclaredMethod("T").returnType.isEnum)
        assertTrue(access.moduleClass.isAssignableFrom(host.classLoader.loadClass("com.bilibili.bplus.followinglist.model.m7")))
        val recycler = host.classLoader.loadClass("androidx.recyclerview.widget.RecyclerView")
        assertNotNull(recycler.getMethod("getAdapter"))
        val authorHolder = recycler.getMethod("findViewHolderForAdapterPosition", Int::class.javaPrimitiveType)
        assertEquals(View::class.java, authorHolder.returnType.getField("itemView").type)
        val authorAdapter = host.classLoader.loadClass("com.bilibili.bplus.followinglist.quick.consume.u0")
        assertEquals(Int::class.javaPrimitiveType, authorAdapter.getMethod("getTarget").returnType)
        val painter = host.classLoader.loadClass("gv1.c")
        assertEquals(Void.TYPE, painter.getDeclaredMethod("k", Canvas::class.java, recycler,
            View::class.java, access.moduleClass).returnType)
        assertNotNull(recycler.getDeclaredMethod("dispatchChildAttached", View::class.java))
        val mediator = host.classLoader.loadClass("com.bilibili.bplus.followinglist.home.mediator.MediatorFragment")
        assertEquals(Boolean::class.javaPrimitiveType, mediator.getDeclaredMethod("ye", Int::class.javaPrimitiveType).returnType)
        val personal = host.classLoader.loadClass("com.bilibili.bplus.followinglist.quick.consume.VideoQuickConsumeFragment")
        assertNotNull(personal.getDeclaredMethod("onViewCreated", View::class.java, android.os.Bundle::class.java))
        assertNotNull(personal.getDeclaredMethod("onDestroyView"))
        assertNotNull(personal.getDeclaredMethod("setUserVisibleCompat", Boolean::class.javaPrimitiveType))
        assertNotNull(personal.getMethod("getUserVisibleHint"))
        val separator = host.classLoader.loadClass("com.bilibili.bplus.followinglist.quick.consume.y0")
        val state = host.classLoader.loadClass("androidx.recyclerview.widget.RecyclerView\$State")
        assertEquals(Void.TYPE, separator.getDeclaredMethod("onDrawOver", Canvas::class.java, recycler, state).returnType)
    }

    @Test fun personalFeedAcceptsTheNativeLayoutAndRestoresItAcrossPageSwitches() = main { context ->
        val host = context.createPackageContext("tv.danmaku.bili",
            Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        val bindingType = host.classLoader.loadClass("nr1.q")
        val binding = bindingType.getMethod("inflate", LayoutInflater::class.java)
            .invoke(null, LayoutInflater.from(host))
        val page = bindingType.getMethod("getRoot").invoke(binding) as ViewGroup
        val list = page.findViewById<ViewGroup>(host.resources.getIdentifier("recycler", "id", host.packageName))
        val header = page.findViewById<View>(host.resources.getIdentifier("header_container", "id", host.packageName))
        val originalChildren = page.childCount
        val originalBackground = page.background
        val originalHeader = header.background
        val originalPadding = list.paddingTop
        val controller = FollowFeedController(requireNotNull(FollowFeedHostAccess.resolve(host.classLoader)),
            compactHeaderSupported = true, report = {}, error = { throw it })
        // 首页模式不能把 UP 主页的通用 recycler ID 当成首页列表。
        controller.attach(page)
        assertEquals(originalChildren, page.childCount)
        controller.close(page)
        repeat(3) {
            controller.attach(page, personalFeed = true)
            assertEquals(originalChildren + 1, page.childCount)
            val layer = page.getChildAt(page.indexOfChild(list) - 1)
            assertTrue(layer is FrameLayout)
            assertFalse(layer.isClickable)
            assertEquals(maxOf(originalPadding,
                (FollowFeedStyle.GAP_DP * host.resources.displayMetrics.density).toInt()), list.paddingTop)
            assertEquals((page.background as ColorDrawable).color, (header.background as ColorDrawable).color)
            controller.attach(page, personalFeed = true)
            controller.visibility(page, false)
            controller.pause(page)
            controller.resume(page, personalFeed = true)
            controller.visibility(page, true)
            assertEquals(originalChildren + 1, page.childCount)
            controller.close(page)
            assertEquals(originalChildren, page.childCount)
            assertEquals(originalPadding, list.paddingTop)
            assertSame(originalBackground, page.background)
            assertSame(originalHeader, header.background)
        }
    }

    @Test fun nativePersonalArrowSurvivesAnUnavailableRingWithoutMutatingSharedDrawables() = main { context ->
        val host = context.createPackageContext("tv.danmaku.bili",
            Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        val layout = host.classLoader.loadClass("ir1.k").getField("d").getInt(null)
        val root = LayoutInflater.from(host).inflate(layout, null)
        val arrow = root.findViewById<View>(host.resources.getIdentifier("dy_arrow", "id", host.packageName))
        val original = requireNotNull(arrow.background)
        val sibling = requireNotNull(original.constantState).newDrawable(host.resources)
        fun render(drawable: android.graphics.drawable.Drawable): Bitmap {
            val bitmap = Bitmap.createBitmap(47, 47, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, 47, 47)
            drawable.draw(Canvas(bitmap))
            return bitmap
        }
        val reference = render(original)
        // 三角形的包围盒中心可能透明；取原图中实际不透明的像素验证配色。
        val sample = (0 until 47 * 47).first { Color.alpha(reference.getPixel(it % 47, it / 47)) == 255 }
        val originalPixel = reference.getPixel(sample % 47, sample / 47)
        fun assertColor(drawable: android.graphics.drawable.Drawable, expected: Int) {
            val bitmap = render(drawable)
            assertEquals(expected, bitmap.getPixel(sample % 47, sample / 47))
            for (y in 0 until 47) for (x in 0 until 47)
                assertEquals(Color.alpha(reference.getPixel(x, y)), Color.alpha(bitmap.getPixel(x, y)))
            bitmap.recycle()
        }
        val chrome = requireNotNull(FollowFeedPersonalChrome.create(root))
        for (dark in listOf(false, true, false)) {
            val palette = FollowFeedStyle.palette(HostChromeColors(dark, Color.BLUE))
            val native = arrow.background
            chrome.updatePalette(palette)
            assertSame(native, arrow.background)
            assertColor(requireNotNull(arrow.background), originalPixel)
            assertColor(sibling, originalPixel)
            val installed = arrow.background
            chrome.updatePalette(palette)
            assertSame(installed, arrow.background)
            // 宿主主题刷新也不能让未知 Adapter 丢失原生选中提示。
            arrow.background = requireNotNull(original.constantState).newDrawable(host.resources)
            chrome.updatePalette(palette)
            assertColor(requireNotNull(arrow.background), originalPixel)
        }
        val lastNative = requireNotNull(original.constantState).newDrawable(host.resources)
        arrow.background = lastNative
        chrome.onViewDetachedFromWindow(root)
        assertTrue(chrome.closed)
        assertSame(lastNative, arrow.background)
        reference.recycle()
    }

    class NativeAuthorAdapter(var actual: Int = -1) {
        var failing = false
        fun getTarget(): Int {
            check(!failing) { "host selection unavailable" }
            return actual
        }
    }

    class NativeAuthorHolder(@JvmField val itemView: View)

    /** 宿主 RecyclerView 的公开读取契约，头像项仍由真实宿主布局创建。 */
    class NativeAuthors(context: Context) : FrameLayout(context) {
        var nativeAdapter: Any? = null
        val items = HashMap<Int, View>()
        fun getAdapter() = nativeAdapter
        fun findViewHolderForAdapterPosition(position: Int): NativeAuthorHolder? =
            items[position]?.let(::NativeAuthorHolder)
    }

    @Test fun personalAvatarRingFollowsSelectionReuseAndPaletteWithoutChangingNativeViews() = main { context ->
        val host = context.createPackageContext("tv.danmaku.bili",
            Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        val layout = host.classLoader.loadClass("ir1.k").getField("d").getInt(null)
        val root = LayoutInflater.from(host).inflate(layout, null)
        val arrow = root.findViewById<View>(host.resources.getIdentifier("dy_arrow", "id", host.packageName))
        val originalArrow = arrow.background
        val listId = host.resources.getIdentifier("dy_recycler", "id", host.packageName)
        val nativeList = root.findViewById<View>(listId)
        val parent = nativeList.parent as ViewGroup
        val authors = NativeAuthors(host).apply { id = listId }
        parent.removeView(nativeList)
        parent.addView(authors, nativeList.layoutParams)
        val bindingType = host.classLoader.loadClass("nr1.x0")
        fun author() = bindingType.getMethod("inflate", LayoutInflater::class.java)
            .invoke(null, LayoutInflater.from(host)).let { bindingType.getMethod("getRoot").invoke(it) as ViewGroup }
        val first = author()
        val second = author()
        val width = (74 * host.resources.displayMetrics.density).toInt()
        val height = (100 * host.resources.displayMetrics.density).toInt()
        authors.addView(first, FrameLayout.LayoutParams(width, height))
        authors.addView(second, FrameLayout.LayoutParams(width, height).apply { leftMargin = width })
        authors.items[0] = first; authors.items[1] = second
        authors.measure(View.MeasureSpec.makeMeasureSpec(width * 2, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        authors.layout(0, 0, width * 2, height)
        val avatarId = host.resources.getIdentifier("avatar_container", "id", host.packageName)
        val avatar = first.findViewById<View>(avatarId)
        val originalBackground = avatar.background
        val originalForeground = first.foreground
        val children = first.childCount
        var clicks = 0
        first.setOnClickListener { clicks++ }
        val foreign = ColorDrawable(Color.RED).apply { setBounds(0, 0, 2, 2) }
        first.overlay.add(foreign)
        fun count(view: View, color: Int): Int {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            var result = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width)
                if (bitmap.getPixel(x, y) == color) result++
            bitmap.recycle()
            return result
        }
        val light = FollowFeedStyle.palette(HostChromeColors(false, Color.CYAN))
        val dark = FollowFeedStyle.palette(HostChromeColors(true, Color.MAGENTA))
        val chrome = requireNotNull(FollowFeedPersonalChrome.create(root))
        assertNull(FollowFeedPersonalSelection.create(authors)) // Adapter 尚未准备好。
        chrome.updatePalette(light)
        assertSame(originalArrow, arrow.background)
        val adapter = NativeAuthorAdapter(0)
        authors.nativeAdapter = adapter
        chrome.updatePalette(light)
        assertNotSame(originalArrow, arrow.background)
        assertTrue(count(first, light.primary) > 100)
        assertEquals(0, count(second, light.primary))
        repeat(5) { chrome.updatePalette(light) }
        adapter.actual = 1
        chrome.updatePalette(light) // 相同调色板也必须同步宿主选中索引。
        assertEquals(0, count(first, light.primary))
        assertTrue(count(second, light.primary) > 100)
        // 模拟已复用的头像项：同一个 View 现在对应另一个 Adapter 位置。
        authors.items.remove(0); authors.items[2] = first
        adapter.actual = 2
        chrome.updatePalette(dark)
        assertTrue(count(first, dark.primary) > 100)
        assertEquals(0, count(second, light.primary))
        avatar.visibility = View.INVISIBLE
        chrome.updatePalette(dark)
        assertEquals(0, count(first, dark.primary))
        assertSame(originalArrow, arrow.background)
        avatar.visibility = View.VISIBLE
        chrome.updatePalette(dark)
        assertTrue(count(first, dark.primary) > 100)
        adapter.actual = -1
        chrome.updatePalette(dark)
        assertEquals(0, count(first, dark.primary))
        assertSame(originalArrow, arrow.background)
        adapter.actual = 2
        chrome.updatePalette(dark)
        adapter.failing = true
        assertTrue(runCatching { chrome.updatePalette(dark) }.isFailure)
        assertSame(originalArrow, arrow.background)
        assertEquals(0, count(first, dark.primary))
        adapter.failing = false
        chrome.updatePalette(dark)
        assertTrue(count(first, dark.primary) > 100)
        first.performClick()
        assertEquals(1, clicks)
        assertSame(originalBackground, avatar.background)
        assertSame(originalForeground, first.foreground)
        assertEquals(children, first.childCount)
        chrome.onViewDetachedFromWindow(root)
        assertSame(originalArrow, arrow.background)
        assertEquals(0, count(first, dark.primary))
        assertEquals(4, count(first, Color.RED)) // 只清理自己的圆圈，不清理宿主 overlay。
        first.overlay.remove(foreign)
    }

    @Test fun realStaticPixelsAreOpaqueAndHaveNoCaptureBackend() = main { context ->
        val palette = FollowFeedStyle.palette(HostChromeColors(false, Color.BLUE))
        LumenSurfaceSession(context, palette).use { session ->
            val first = View(context)
            val second = View(context)
            val a = session.bind(first, FollowFeedStyle.card())
            val b = session.bind(second, FollowFeedStyle.card())
            assertNotSame(first.background, second.background)
            assertEquals(255, Color.alpha(pixel(first)))
            assertEquals(255, Color.alpha(pixel(second, 480, 320)))
            assertEquals(LumenSurfaceBackend.STATIC, a.diagnostics()!!.backend)
            assertEquals(LumenSurfaceMaterial.STATIC, b.diagnostics()!!.effectiveMaterial)
            assertEquals(0L, session.diagnostics().contentRecordings)
            assertEquals(0L, session.diagnostics().gpuDraws)
            assertEquals(0L, session.diagnostics().softwareDraws)
            a.close(); b.close()
            assertEquals(0, session.diagnostics().attachedSurfaces)
        }
    }

    @Test fun repeatedBindingAndExternalBackgroundOwnershipRemainBounded() = main { context ->
        val palette = FollowFeedStyle.palette(HostChromeColors(false, Color.BLUE))
        val view = View(context)
        val original = ColorDrawable(Color.RED)
        view.background = original
        LumenSurfaceSession(context, palette).use { session ->
            repeat(100) {
                val a = session.bind(view, FollowFeedStyle.card())
                val b = session.bind(view, FollowFeedStyle.card())
                assertNull(a.diagnostics())
                assertEquals(1, session.diagnostics().attachedSurfaces)
                pixel(view)
                b.close()
                assertEquals(0, session.diagnostics().attachedSurfaces)
                assertSame(original, view.background)
                session.pause(); session.resume()
            }
            val binding = session.bind(view, FollowFeedStyle.card())
            val later = ColorDrawable(Color.GREEN)
            view.background = later
            binding.close()
            assertSame(later, view.background)
            assertEquals(0L, session.diagnostics().contentRecordings)
        }
    }

    @Test fun paletteUpdateChangesDisplayedAndReboundSurfacesWithText() = main { context ->
        val light = FollowFeedStyle.palette(HostChromeColors(false, Color.BLUE))
        val dark = FollowFeedStyle.palette(HostChromeColors(true, Color.BLUE))
        val view = View(context)
        val title = TextView(context).apply { text = "长标题与文字" }
        val edits = FollowFeedViewEdits()
        LumenSurfaceSession(context, light).use { session ->
            var binding = session.bind(view, FollowFeedStyle.card())
            edits.text(title, light.textPrimary, 16f)
            val before = pixel(view)
            session.updatePalette(dark)
            edits.text(title, dark.textPrimary, 16f)
            val after = pixel(view)
            assertNotEquals(before, after)
            assertEquals(255, Color.alpha(after))
            assertEquals(dark.textPrimary, title.currentTextColor)
            binding.close()
            binding = session.bind(view, FollowFeedStyle.card())
            assertEquals(after, pixel(view))
            assertEquals(1L, session.diagnostics().paletteGeneration)
            binding.close(); edits.restore()
        }
    }

    /** 以真实宿主资源建立最小 TabLayout 契约，业务点击仍属于原 TabView。 */
    class NativeTabs(context: Context, count: Int = 2) : LinearLayout(context) {
        var actual = 1
        var clicks = 0
        fun getSelectedTabPosition() = actual
        init {
            val strip = LinearLayout(context)
            addView(strip)
            repeat(count) { index ->
                val tab = FrameLayout(context)
                tab.addView(TextView(context).apply {
                    id = context.resources.getIdentifier("dy_tv_title", "id", context.packageName)
                    check(id != 0)
                    text = if (index == 0) "全部" else "视频"
                })
                tab.setOnClickListener { clicks++; actual = index }
                strip.addView(tab)
            }
        }
    }

    /** 使用真实资源构造外层标题行、页内分段与发布入口的布局关系。 */
    class NativeHeader(context: Context, val page: FrameLayout) : FrameLayout(context) {
        private fun resource(name: String) = context.resources.getIdentifier(name, "id", context.packageName).also { check(it != 0) }
        val appBar = FrameLayout(context).apply { id = resource("fo_app_bar") }
        val plate = View(context).apply { id = resource("top_tab_container") }
        val title = TextView(context).apply { id = resource("title"); text = "关注" }
        val tabs = View(context).apply { id = resource("toolbar_tabs"); visibility = View.GONE }
        val publish = View(context).apply { id = resource("fo_publish_menu") }
        init {
            background = ColorDrawable(Color.WHITE)
            page.setPadding(0, 211, 0, 0)
            addView(page, FrameLayout.LayoutParams(-1, -2))
            addView(plate, FrameLayout.LayoutParams(-1, 212))
            addView(appBar, FrameLayout.LayoutParams(-1, 244))
            appBar.addView(title)
            appBar.addView(tabs)
            appBar.addView(publish, FrameLayout.LayoutParams(127, 127))
        }
    }

    @Test fun compactHeaderRetainsPublicationAndRestoresUnsupportedAndHiddenPages() = main { context ->
        val host = context.createPackageContext("tv.danmaku.bili", 0)
        val palette = FollowFeedStyle.palette(HostChromeColors(false, Color.BLUE))
        LumenSurfaceSession(host, palette).use { session ->
            val page = FrameLayout(host)
            val tabs = NativeTabs(host)
            page.addView(tabs, FrameLayout.LayoutParams(-1, 100))
            val native = NativeHeader(host, page)
            val originalBackground = native.background
            val originalParams = native.publish.layoutParams
            var clicks = 0
            native.publish.setOnClickListener { clicks++ }
            val selection = requireNotNull(FollowFeedSelection.create(tabs, session, palette))
            repeat(5) {
                val header = requireNotNull(FollowFeedHeader.create(page, selection, palette))
                assertEquals(View.GONE, native.appBar.visibility)
                assertEquals(View.GONE, native.plate.visibility)
                assertSame(selection.actionContainer, native.publish.parent)
                assertTrue(page.paddingTop < 211)
                assertEquals(palette.background, (native.background as ColorDrawable).color)
                native.publish.performClick()
                page.setPadding(0, header.mapNativePadding(244), 0, 0) // 宿主重发标题行占位。
                header.sync()
                assertTrue(page.paddingTop < 211)
                val dark = FollowFeedStyle.palette(HostChromeColors(true, Color.BLUE))
                header.updatePalette(dark)
                assertEquals(dark.background, (native.background as ColorDrawable).color)
                header.close()
                assertSame(native.appBar, native.publish.parent)
                assertSame(originalParams, native.publish.layoutParams)
                assertEquals(View.VISIBLE, native.appBar.visibility)
                assertEquals(View.VISIBLE, native.plate.visibility)
                assertEquals(244, page.paddingTop)
                assertSame(originalBackground, native.background)
                page.setPadding(0, 211, 0, 0)
            }
            assertEquals(5, clicks)
            native.tabs.visibility = View.VISIBLE
            assertNull(FollowFeedHeader.create(page, selection, palette))
            assertSame(native.appBar, native.publish.parent)
            native.tabs.visibility = View.GONE
            native.title.text = "未知页面"
            assertNull(FollowFeedHeader.create(page, selection, palette))
            assertEquals(211, page.paddingTop)
            selection.close()
        }
    }

    @Test fun pagePauseKeepsCompactHeaderUntilViewDestruction() = main { context ->
        val host = context.createPackageContext("tv.danmaku.bili",
            Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        val page = object : FrameLayout(host) {
            override fun isShown() = visibility == View.VISIBLE
        }
        val tabs = NativeTabs(host).apply {
            id = host.resources.getIdentifier("dy_tab_layout", "id", host.packageName)
        }
        page.addView(tabs, FrameLayout.LayoutParams(-1, 100))
        val native = NativeHeader(host, page)
        val controller = FollowFeedController(requireNotNull(FollowFeedHostAccess.resolve(host.classLoader)),
            compactHeaderSupported = true, report = {}, error = { throw it })
        controller.attach(page)
        page.viewTreeObserver.dispatchOnPreDraw()
        val compactPadding = page.paddingTop
        val actionParent = native.publish.parent
        assertEquals(View.GONE, native.appBar.visibility)
        repeat(5) {
            controller.pause(page)
            page.viewTreeObserver.dispatchOnPreDraw() // 转场中的底页仍可能参与绘制。
            assertEquals(View.GONE, native.appBar.visibility)
            assertEquals(View.GONE, native.plate.visibility)
            assertEquals(compactPadding, page.paddingTop)
            assertSame(actionParent, native.publish.parent)
            controller.resume(page)
            page.viewTreeObserver.dispatchOnPreDraw()
            assertSame(actionParent, native.publish.parent)
        }
        controller.close(page)
        assertEquals(View.VISIBLE, native.appBar.visibility)
        assertEquals(211, page.paddingTop)
        assertSame(native.appBar, native.publish.parent)
    }

    @Test fun extendedViewportPreservesRestPositionBottomAndOwnership() = main { context ->
        for (inset in listOf(0, 90, 135)) {
            val page = FrameLayout(context).apply { setPadding(0, inset, 0, 0) }
            val parent = FrameLayout(context)
            page.addView(parent, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 154 })
            val list = FrameLayout(context).apply { setPadding(7, 11, 9, 48) }
            parent.addView(list, FrameLayout.LayoutParams(-1, -1))
            val child = View(context)
            list.addView(child, FrameLayout.LayoutParams(100, 80))
            fun layout() {
                page.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY))
                page.layout(0, 0, 1080, 2340)
            }
            layout()
            val rest = parent.top + list.top + child.top
            val bottom = parent.top + list.bottom
            repeat(5) {
                val viewport = requireNotNull(FollowFeedViewport.create(list, page))
                viewport.sync(inset); viewport.sync(inset)
                layout()
                assertEquals(rest, parent.top + list.top + child.top)
                assertEquals(bottom, parent.top + list.bottom)
                assertEquals(11 + inset, list.paddingTop)
                assertFalse(page.clipChildren)
                assertFalse(list.clipToPadding)
                viewport.close(); layout()
                assertEquals(11, list.paddingTop)
                assertEquals(0, (list.layoutParams as FrameLayout.LayoutParams).topMargin)
                assertTrue(page.clipChildren)
                assertTrue(list.clipToPadding)
            }
            val viewport = requireNotNull(FollowFeedViewport.create(list, page))
            viewport.sync(inset)
            list.setPadding(7, 300, 9, 48) // 后续业务写入不能被恢复覆盖。
            viewport.close()
            assertEquals(300, list.paddingTop)
        }
    }

    @Test fun statusFallbackFadesAtConstantRateWithoutRecording() = main { context ->
        val palette = FollowFeedStyle.palette(HostChromeColors(false, Color.BLUE))
        LumenSurfaceSession(context, palette).use { session ->
            val view = View(context)
            val options = FollowFeedStyle.statusBand(palette, 90, 134)
            val binding = session.bind(view, options.copy(material = LumenSurfaceMaterial.STATIC,
                sampling = options.sampling.copy(enabled = false)))
            view.layout(0, 0, 240, 134)
            val bitmap = Bitmap.createBitmap(240, 134, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            assertTrue(Color.alpha(bitmap.getPixel(120, 40)) in 160..180)
            // 从顶端立即衰减，不再经过保持区或先慢后快的缓动区。
            assertTrue(Color.alpha(bitmap.getPixel(120, 90)) in 1..127)
            assertTrue(Color.alpha(bitmap.getPixel(120, 110)) in 1..254)
            var previous = 255
            for (y in 0 until 134) {
                val alpha = Color.alpha(bitmap.getPixel(120, y))
                assertTrue("渐隐应连续单调，y=$y", alpha <= previous)
                previous = alpha
            }
            val drops = (0..10).map { step ->
                Color.alpha(bitmap.getPixel(120, step * 10)) -
                    Color.alpha(bitmap.getPixel(120, step * 10 + 10))
            }
            // 10px 等距取样的透明度差应相同，仅允许 8bit 量化误差。
            assertTrue("等距衰减应均匀: $drops", drops.max() - drops.min() <= 2)
            // 引擎 mask 已在 View 边界之前归零，末尾整段必须完全透明。
            for (y in 124 until 134) assertEquals(0, Color.alpha(bitmap.getPixel(120, y)))
            bitmap.recycle()
            assertEquals(0L, session.diagnostics().contentRecordings)
            binding.close()
            assertEquals(0, session.diagnostics().attachedSurfaces)
        }
    }

    @Test fun selectionPreservesBusinessClickRestorationAndUnknownLayouts() = main { context ->
        val host = context.createPackageContext("tv.danmaku.bili", 0)
        val palette = FollowFeedStyle.palette(HostChromeColors(false, Color.BLUE))
        LumenSurfaceSession(host, palette).use { session ->
            val parent = FrameLayout(host)
            val tabs = NativeTabs(host)
            parent.addView(tabs, FrameLayout.LayoutParams(-1, 100))
            repeat(5) {
                val selection = requireNotNull(FollowFeedSelection.create(tabs, session, palette))
                assertEquals(tabs.actual, selection.choice.selectedIndex)
                val before = tabs.clicks
                selection.sync(false)
                assertEquals(before, tabs.clicks)
                selection.choice.options.getChildAt(1 - tabs.actual).performClick()
                assertEquals(before + 1, tabs.clicks)
                assertEquals(tabs.actual, selection.choice.selectedIndex)
                tabs.actual = 1 - tabs.actual // 宿主恢复或滑动切页，不应重复请求业务。
                selection.sync(false)
                assertEquals(tabs.actual, selection.choice.selectedIndex)
                assertEquals(before + 1, tabs.clicks)
                selection.updatePalette(FollowFeedStyle.palette(HostChromeColors(true, Color.BLUE)))
                selection.close()
                assertSame(parent, tabs.parent)
                assertEquals(100, tabs.layoutParams.height)
                assertEquals(1f, tabs.alpha, 0f)
                assertEquals(0, session.diagnostics().attachedSurfaces)
            }
            val unsupported = NativeTabs(host, 3)
            parent.addView(unsupported)
            assertNull(FollowFeedSelection.create(unsupported, session, palette))
            assertSame(parent, unsupported.parent)
            assertEquals(1f, unsupported.alpha, 0f)
        }
    }

    @Test fun scrolledSelectionClipsPixelsAndRestoresWithoutClippingTheFeed() = main { context ->
        val host = context.createPackageContext("tv.danmaku.bili", 0)
        val palette = FollowFeedStyle.palette(HostChromeColors(false, Color.BLUE))
        LumenSurfaceSession(host, palette).use { session ->
            val page = FrameLayout(host).apply { clipChildren = false; clipToPadding = false }
            val viewport = FrameLayout(host).apply { clipChildren = false; clipToPadding = false }
            val appBar = FrameLayout(host)
            val tabs = NativeTabs(host)
            val inset = 90
            page.addView(viewport, FrameLayout.LayoutParams(-1, -1).apply { topMargin = inset })
            viewport.addView(appBar, FrameLayout.LayoutParams(-1, -2))
            appBar.addView(tabs, FrameLayout.LayoutParams(-1, 100))
            val selection = requireNotNull(FollowFeedSelection.create(tabs, session, palette))
            // 纯色覆盖页签与发布容器，让逐像素断言检测全部越界绘制。
            selection.actionContainer.setBackgroundColor(Color.RED)
            page.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
            page.layout(0, 0, 1080, 1000)
            val headerHeight = appBar.height
            assertTrue(headerHeight > inset)
            fun draw(): Bitmap = Bitmap.createBitmap(1080, 1000, Bitmap.Config.ARGB_8888).also {
                page.draw(Canvas(it))
            }
            fun assertStatusEmpty(bitmap: Bitmap) {
                for (y in 0 until inset) assertEquals("header leaked at y=$y", 0,
                    Color.alpha(bitmap.getPixel(540, y)))
            }
            repeat(3) {
                appBar.offsetTopAndBottom(-headerHeight)
                selection.sync(false)
                assertEquals(View.INVISIBLE, selection.actionContainer.visibility)
                assertEquals(headerHeight, appBar.height)
                draw().also { assertStatusEmpty(it); it.recycle() }
                appBar.offsetTopAndBottom(headerHeight / 2)
                selection.sync(false)
                assertEquals(View.VISIBLE, selection.actionContainer.visibility)
                draw().also {
                    assertStatusEmpty(it)
                    assertEquals(Color.RED, it.getPixel(1, inset + 1))
                    it.recycle()
                }
                appBar.offsetTopAndBottom(headerHeight - headerHeight / 2)
                selection.sync(false)
                draw().also {
                    assertStatusEmpty(it)
                    assertEquals(Color.RED, it.getPixel(1, inset + 1))
                    it.recycle()
                }
                selection.choice.onSelect?.invoke(0)
                assertEquals(0, tabs.actual)
                selection.choice.onSelect?.invoke(1)
                assertEquals(1, tabs.actual)
            }
            assertEquals(6, tabs.clicks)
            assertFalse(viewport.clipChildren)
            assertFalse(page.clipChildren)
            selection.close()
            assertSame(appBar, tabs.parent)
            assertEquals(100, tabs.layoutParams.height)
            assertEquals(1f, tabs.alpha, 0f)
            assertFalse(viewport.clipChildren)
        }
    }

    @Test fun selectionFitsNarrowLargeFontLandscapeAndTabletWidths() = main { context ->
        val host = context.createPackageContext("tv.danmaku.bili", 0)
        for (scale in listOf(1f, 2f)) for (widthDp in listOf(280, 640, 1024)) {
            val config = Configuration(host.resources.configuration).apply { fontScale = scale }
            val configured = host.createConfigurationContext(config)
            val palette = FollowFeedStyle.palette(HostChromeColors(false, Color.BLUE))
            LumenSurfaceSession(configured, palette).use { session ->
                val parent = FrameLayout(configured)
                val tabs = NativeTabs(configured)
                parent.addView(tabs, FrameLayout.LayoutParams(-1, 100))
                val selection = requireNotNull(FollowFeedSelection.create(tabs, session, palette))
                val native = NativeHeader(configured, parent)
                val header = requireNotNull(FollowFeedHeader.create(parent, selection, palette))
                val width = (widthDp * configured.resources.displayMetrics.density).toInt()
                native.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
                native.layout(0, 0, width, native.measuredHeight)
                val a = selection.choice.options.getChildAt(0) as TextView
                val b = selection.choice.options.getChildAt(1) as TextView
                assertTrue(a.width > 0 && a.right <= b.left)
                // 紧凑筛选栏已改为 40dp；仍核对大字号文本、点击宽度和动作区不重叠。
                assertTrue(a.height >= 40 * configured.resources.displayMetrics.density)
                assertTrue(a.height >= a.lineHeight)
                assertTrue(native.publish.width >= 40 * configured.resources.displayMetrics.density)
                assertTrue(selection.choice.left >= 12 * configured.resources.displayMetrics.density)
                assertTrue(selection.choice.right <= native.publish.left)
                if (widthDp >= 640) {
                    assertTrue("font=$scale width=$widthDp bounds=${selection.choice.left}..${selection.choice.right} page=$width",
                        kotlin.math.abs(selection.choice.left + selection.choice.width / 2 - width / 2) <= 1)
                    assertTrue(selection.choice.width <= FollowFeedStyle.segmentWidth(scale) * configured.resources.displayMetrics.density + 1)
                }
                header.close()
                selection.close()
            }
        }
    }

    @Test fun nicknameAndLayoutEditsRestoreWithoutOverwritingLaterHostChanges() = main { context ->
        val name = TextView(context).apply {
            text = "一个很长的完整昵称"
            maxLines = 2
            setTextColor(Color.RED)
            layoutParams = FrameLayout.LayoutParams(180, 90)
            setPadding(7, 8, 9, 10)
        }
        val original = ColorDrawable(Color.BLUE)
        name.background = original
        val edits = FollowFeedViewEdits()
        repeat(5) {
            edits.background(name, null)
            edits.text(name, Color.GRAY, singleLine = true)
            edits.size(name, 200, 100)
            assertEquals(1, name.maxLines)
            assertEquals(name.text, name.contentDescription)
            assertEquals(7, name.paddingLeft)
            edits.restore()
            assertEquals(2, name.maxLines)
            assertEquals(180, name.layoutParams.width)
            assertSame(original, name.background)
        }
        edits.background(name, null)
        edits.text(name, Color.GRAY)
        val later = ColorDrawable(Color.GREEN)
        name.background = later
        name.setTextColor(Color.MAGENTA)
        edits.restore()
        assertSame(later, name.background)
        assertEquals(Color.MAGENTA, name.currentTextColor)
    }

    @Test fun frequentMoreNativeSelectorUsesPageColorAndRetainsClickAndRestoration() = main { context ->
        val host = context.createPackageContext("tv.danmaku.bili", 0)
        val selectorId = host.resources.getIdentifier("bg_card_selector", "drawable", host.packageName)
        check(selectorId != 0)
        val more = LinearLayout(host).apply {
            background = host.getDrawable(selectorId)
            setPadding(7, 8, 9, 10)
        }
        val original = requireNotNull(more.background)
        val untouched = requireNotNull(original.constantState).newDrawable(host.resources)
        val sibling = View(host).apply { background = untouched }
        val siblingColor = pixel(sibling)
        var clicks = 0
        more.setOnClickListener { clicks++ }
        val edits = FollowFeedViewEdits()
        for (dark in listOf(false, true, false)) {
            val palette = FollowFeedStyle.palette(HostChromeColors(dark, Color.BLUE))
            edits.backgroundTint(more, ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                intArrayOf(palette.surfaceVariant, palette.background)
            ))
            assertNotSame(original, more.background)
            assertEquals(palette.background, pixel(more))
            more.isPressed = true
            assertEquals(palette.surfaceVariant, pixel(more))
            more.isPressed = false
            assertEquals(palette.background, pixel(more))
            more.performClick()
            assertEquals(siblingColor, pixel(sibling)) // tint 不能污染宿主共享 constantState。
            assertEquals(7, more.paddingLeft)
            edits.restore()
            assertSame(original, more.background)
        }
        assertEquals(3, clicks)
        edits.backgroundTint(more, ColorStateList.valueOf(Color.BLUE))
        val later = ColorDrawable(Color.GREEN)
        more.background = later
        edits.restore()
        assertSame(later, more.background)
    }
}
