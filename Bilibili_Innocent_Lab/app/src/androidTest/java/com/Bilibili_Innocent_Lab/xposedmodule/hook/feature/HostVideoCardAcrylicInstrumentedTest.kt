package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardSurface
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostCardMaterialScope
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import com.lumen.coacervation.engine.host.LumenAcrylicOptions
import com.lumen.coacervation.engine.host.LumenAcrylicFallbackReason
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostVideoCardAcrylicInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun paleWallpaperKeepsItsHueInGpuAndSoftwareAcrylic() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var page: FrameLayout
            lateinit var source: View
            lateinit var card: View
            lateinit var window: Window
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                window = activity.window
                page = FrameLayout(activity)
                source = View(activity)
                card = View(activity)
                page.addView(source, FrameLayout.LayoutParams(-1, -1))
                page.addView(card, FrameLayout.LayoutParams(400, 240).apply { leftMargin = 60; topMargin = 320 })
                activity.setContentView(FrameLayout(activity).apply {
                    addView(page, FrameLayout.LayoutParams(640, 900))
                })
            }
            for (software in listOf(false, true)) {
                var scope: HostCardMaterialScope? = null
                var session: com.lumen.coacervation.engine.host.LumenSurfaceSession? = null
                var control: com.lumen.coacervation.engine.host.LumenFluentControl? = null
                try {
                    instrumentation.runOnMainSync {
                        if (software) {
                            val palette = HostVideoCardSurface.palette(false, page.context)
                            // 用公开预算选项强制实际软件采样，避免 GPU 帧注销软件目标。
                            session = com.lumen.coacervation.engine.host.LumenSurfaceSession(page.context, palette,
                                com.lumen.coacervation.engine.host.LumenSurfaceSessionOptions(maxGpuSurfacePixels = 16_384))
                            control = com.lumen.coacervation.engine.host.LumenFluentControl(card, palette,
                                com.lumen.coacervation.engine.host.LumenFluentControlOptions(
                                    acrylic = com.lumen.coacervation.engine.host.LumenAcrylicOptions(
                                        tintColor = palette.surface, fallbackColor = palette.surface)), session, source)
                        } else {
                            scope = HostCardMaterialScope(page, false)
                            scope!!.apply(card, 24f)
                        }
                    }
                    for (color in listOf(0xfff4e1ee.toInt(), 0xffe0f4e7.toInt())) {
                        instrumentation.runOnMainSync {
                            page.setBackgroundColor(color)
                            source.setBackgroundColor(color)
                            session?.notifyContentChanged()
                        }
                        SystemClock.sleep(1000)
                        instrumentation.runOnMainSync {
                            assertEquals(if (software) LumenSurfaceBackend.SOFTWARE else LumenSurfaceBackend.GPU,
                                (control?.diagnostics() ?: scope!!.diagnostics(card))?.backdropBackend)
                        }
                        val frame = capture(window)
                        try {
                            val pixel = pixel(frame, card)
                            val difference = Color.red(pixel) - Color.green(pixel)
                            assertTrue("Pastel wallpaper washed out (software=$software): ${Integer.toHexString(color)} -> ${Integer.toHexString(pixel)}",
                                if (Color.red(color) > Color.green(color)) difference >= 10 else difference <= -10)
                        } finally { frame.recycle() }
                    }
                } finally { instrumentation.runOnMainSync { scope?.close(); control?.close(); session?.close() } }
            }
        }
    }
    @Test fun smallerSharedMaterialKeepsTheSettledAcrylicPixelsAndQuarterOfTheContentArea() {
        val texture = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width)
                setPixel(x, y, Color.rgb(x * 4, if ((x / 4 + y / 4) % 2 == 0) 220 else 30, y * 4))
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var window: Window
                lateinit var reduced: HostCardMaterialScope
                lateinit var reference: HostCardMaterialScope
                lateinit var reducedCard: View
                lateinit var referenceCard: View
                scenario.onActivity { activity ->
                    activity.activeConfirmDialog?.dismiss()
                    window = activity.window
                    val root = FrameLayout(activity)
                    fun fixture(left: Int): Pair<FrameLayout, View> {
                        val page = FrameLayout(activity).apply {
                            background = BitmapDrawable(resources, texture).apply {
                                tileModeX = Shader.TileMode.REPEAT; tileModeY = Shader.TileMode.REPEAT
                                isFilterBitmap = true
                            }
                        }
                        val card = View(activity)
                        page.addView(card, FrameLayout.LayoutParams(300, 180).apply { leftMargin = 70; topMargin = 250 })
                        root.addView(page, FrameLayout.LayoutParams(480, 900).apply { leftMargin = left })
                        return page to card
                    }
                    val optimized = fixture(0)
                    val full = fixture(520)
                    reducedCard = optimized.second; referenceCard = full.second
                    activity.setContentView(root)
                    reduced = HostCardMaterialScope(optimized.first, false)
                    reference = HostCardMaterialScope(full.first, false, gpuScale = 1f)
                    reduced.apply(reducedCard, 20f); reference.apply(referenceCard, 20f)
                }
                try {
                    SystemClock.sleep(1000)
                    instrumentation.runOnMainSync {
                        assertEquals("${reduced.samplingDiagnostics()}; ${reduced.samplingPerformance()}; ${reduced.diagnostics(reducedCard)}",
                            LumenSurfaceBackend.GPU, reduced.diagnostics(reducedCard)?.backdropBackend)
                        assertEquals("${reference.samplingDiagnostics()}; ${reference.samplingPerformance()}; ${reference.diagnostics(referenceCard)}",
                            LumenSurfaceBackend.GPU, reference.diagnostics(referenceCard)?.backdropBackend)
                        val small = reduced.samplingPerformance().logicalGpuContentPixels
                        val large = reference.samplingPerformance().logicalGpuContentPixels
                        assertTrue("Input area did not shrink: $small / $large", small > 0 && small * 4 <= large)
                    }
                    val image = capture(window)
                    try {
                        val a = IntArray(2); val b = IntArray(2)
                        instrumentation.runOnMainSync {
                            reducedCard.getLocationInWindow(a); referenceCard.getLocationInWindow(b)
                        }
                        var total = 0L; var count = 0; var maximum = 0
                        for (y in 16 until 164 step 6) for (x in 16 until 284 step 6) {
                            val reducedPixel = image.getPixel(a[0] + x, a[1] + y)
                            val referencePixel = image.getPixel(b[0] + x, b[1] + y)
                            for (shift in intArrayOf(0, 8, 16)) {
                                val difference = abs((reducedPixel ushr shift and 255) - (referencePixel ushr shift and 255))
                                total += difference; count++; maximum = maxOf(maximum, difference)
                            }
                        }
                        val evidence = java.io.File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "acrylic-compare").apply { mkdirs() }
                        java.io.File(evidence, "comparison.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        java.io.File(evidence, "metrics.txt").writeText("mean=${total.toDouble() / count}, max=$maximum; reduced=${a.toList()}, reference=${b.toList()}")
                        assertTrue("Acrylic changed visibly: mean=${total.toDouble() / count}, max=$maximum",
                            total.toDouble() / count <= 2.0 && maximum <= 8)
                    } finally { image.recycle() }
                } finally { instrumentation.runOnMainSync { reduced.close(); reference.close() } }
            }
        } finally { texture.recycle() }
    }

    @Test fun releaseRestoresOnlyOwnedBackgroundAndKeepsHostPadding() {
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val list = FrameLayout(context)
            val first = View(context)
            val second = View(context)
            val original = ColorDrawable(Color.GREEN)
            first.background = original
            first.setPadding(3, 5, 7, 11)
            list.addView(first)
            list.addView(second)
            val scope = HostVideoCardSurface.Scope(list, false)
            try {
                val background = scope.apply(first, 12f)
                assertSame(background, scope.apply(first, 12f))
                assertNotSame(background, scope.apply(second, 12f))
                assertEquals(listOf(3, 5, 7, 11), listOf(first.paddingLeft, first.paddingTop,
                    first.paddingRight, first.paddingBottom))
                scope.release(first)
                assertSame(original, first.background)
                assertNull(scope.diagnostics(first))
                assertNotSame(background, scope.apply(first, 18f))
                val replacement = ColorDrawable(Color.BLUE)
                second.background = replacement
                scope.close()
                assertSame(original, first.background)
                assertSame(replacement, second.background)
            } finally { scope.close() }
        }
    }

    @Test fun realAcrylicSamplesWallpaperAndUpdatesWithoutSamplingCardChildren() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var list: FrameLayout
            lateinit var card: FrameLayout
            lateinit var window: Window
            lateinit var scope: HostVideoCardSurface.Scope
            lateinit var observer: ViewTreeObserver.OnPreDrawListener
            var night = false
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                window = activity.window
                list = FrameLayout(activity).apply { setBackgroundColor(Color.BLUE) }
                card = FrameLayout(activity)
                // 子内容是全红色时，采样源仍只能得到蓝色壁纸。
                card.addView(View(activity).apply { setBackgroundColor(Color.RED) },
                    FrameLayout.LayoutParams(120, 60))
                list.addView(card, FrameLayout.LayoutParams(400, 240).apply { leftMargin = 60; topMargin = 320 })
                activity.setContentView(list)
                scope = HostVideoCardSurface.Scope(list, false)
                scope.apply(card, 24f)
                observer = ViewTreeObserver.OnPreDrawListener { scope.refresh(night); true }
                list.viewTreeObserver.addOnPreDrawListener(observer)
            }
            try {
                SystemClock.sleep(900)
                instrumentation.runOnMainSync {
                    val status = requireNotNull(scope.diagnostics(card))
                    assertEquals("$status; ${scope.samplingDiagnostics()}", LumenAcrylicFallbackReason.NONE, status.fallbackReason)
                    assertTrue(status.toString(), status.acrylicVisible)
                    assertNotEquals(status.toString(), LumenSurfaceBackend.STATIC, status.backdropBackend)
                    // 顶栏的软采样会在同一窗口中重新绘制卡片，不能替换屏幕缓存中的 GPU 节点。
                    val sampled = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
                    try { card.draw(Canvas(sampled)) } finally { sampled.recycle() }
                    list.invalidate()
                }
                SystemClock.sleep(150)
                val blue = capture(window)
                try {
                    save(blue, "blue")
                    val before = pixel(blue, card)
                    assertTrue("Card sampled its own red children: $before", Color.blue(before) > Color.red(before))
                    instrumentation.runOnMainSync {
                        list.setBackgroundColor(Color.RED)
                    }
                    SystemClock.sleep(500)
                    val red = capture(window)
                    try {
                        save(red, "red")
                        val after = pixel(red, card)
                        var diagnostics = ""
                        instrumentation.runOnMainSync { diagnostics = scope.samplingDiagnostics().toString() }
                        assertTrue("Wallpaper did not update: $before -> $after; $diagnostics", Color.red(after) > Color.blue(after))
                        assertTrue(abs(Color.blue(before) - Color.blue(after)) > 5)
                        // The final input in a short burst must publish even when no
                        // scrolling or further invalidation keeps the window running.
                        val burstDone = CountDownLatch(1)
                        instrumentation.runOnMainSync {
                            list.setBackgroundColor(Color.BLUE)
                            list.postOnAnimation {
                                list.setBackgroundColor(Color.GREEN)
                                burstDone.countDown()
                            }
                        }
                        assertTrue(burstDone.await(3, TimeUnit.SECONDS))
                        SystemClock.sleep(500)
                        val settled = capture(window)
                        try {
                            val final = pixel(settled, card)
                            assertTrue("Last wallpaper in burst stayed stale: $final",
                                Color.green(final) > maxOf(Color.red(final), Color.blue(final)))
                        } finally { settled.recycle() }
                        // 视口尺寸改变不能使卡片缓存继续引用已丢弃的共享节点。
                        instrumentation.runOnMainSync {
                            list.layout(0, 0, list.width, list.height - 100)
                            list.setBackgroundColor(Color.BLUE)
                        }
                        SystemClock.sleep(400)
                        val resized = capture(window)
                        try {
                            val next = pixel(resized, card)
                            assertTrue("Viewport resize lost the shared material: $next", Color.blue(next) > Color.red(next))
                        } finally { resized.recycle() }
                    } finally { red.recycle() }
                } finally { blue.recycle() }
                instrumentation.runOnMainSync {
                    val drawable = card.background
                    night = true
                    scope.refresh(true)
                    assertSame(drawable, scope.apply(card, 24f))
                    scope.release(card)
                    assertNull(card.background)
                    scope.apply(card, 24f)
                }
                SystemClock.sleep(500)
                instrumentation.runOnMainSync {
                    assertTrue(requireNotNull(scope.diagnostics(card)).acrylicVisible)
                    list.visibility = View.GONE
                    scope.onPreDraw()
                    assertTrue(scope.samplingDiagnostics().paused)
                    list.visibility = View.VISIBLE
                    scope.onPreDraw()
                    assertFalse(scope.samplingDiagnostics().paused)
                }
            } finally {
                instrumentation.runOnMainSync {
                    list.viewTreeObserver.removeOnPreDrawListener(observer)
                    scope.close()
                }
            }
        }
    }

    @Test fun nativeAndComposeCardsShareOneMaterialAndFollowTheirWindowPosition() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var list: FrameLayout
            lateinit var first: View
            lateinit var second: View
            lateinit var compose: View
            lateinit var window: Window
            lateinit var scope: HostVideoCardSurface.Scope
            lateinit var paint: HostCardMaterialScope.CardPaint
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                window = activity.window
                list = FrameLayout(activity).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(Color.BLUE, Color.RED))
                }
                first = View(activity)
                second = View(activity)
                compose = object : View(activity) {
                    private val position = IntArray(2)
                    override fun onDraw(canvas: Canvas) {
                        getLocationInWindow(position)
                        paint.position(position[0].toFloat(), position[1].toFloat())
                        paint.setBounds(0, 0, width, height)
                        paint.draw(canvas)
                    }
                }
                list.addView(first, FrameLayout.LayoutParams(300, 180))
                list.addView(second, FrameLayout.LayoutParams(300, 180))
                list.addView(compose, FrameLayout.LayoutParams(300, 180))
                activity.setContentView(list)
                scope = HostVideoCardSurface.Scope(list, false)
                scope.apply(first, 20f)
                scope.apply(second, 20f)
                paint = scope.composePaint(compose, 20f)
            }
            try {
                SystemClock.sleep(1000)
                instrumentation.runOnMainSync {
                    assertTrue(list.height > 0 && first.height > 0 && second.height > 0)
                    val upper = (list.height * .25f).roundToInt() - first.height / 2
                    val lower = (list.height * .75f).roundToInt() - second.height / 2
                    val nativeCenter = (list.width / 3f).roundToInt()
                    val composeCenter = (list.width * 2f / 3f).roundToInt()
                    // 满足 GPU 声明余量，并比较左右镜像的相同边界条件；软件三轮模糊支持域可能更宽。
                    val gpuHalo = LumenAcrylicOptions().blurRadiusDp * 2f * list.resources.displayMetrics.density + 2f
                    assertTrue("Fixture centers lack declared GPU halo: width=${list.width}, native=$nativeCenter, compose=$composeCenter, halo=$gpuHalo",
                        nativeCenter >= gpuHalo && list.width - nativeCenter >= gpuHalo &&
                            composeCenter >= gpuHalo && list.width - composeCenter >= gpuHalo)
                    assertEquals("Fixture centers must share mirrored source-edge distances",
                        minOf(nativeCenter, list.width - nativeCenter), minOf(composeCenter, list.width - composeCenter))
                    first.layoutParams = (first.layoutParams as FrameLayout.LayoutParams).apply {
                        leftMargin = nativeCenter - first.width / 2; topMargin = upper
                    }
                    second.layoutParams = (second.layoutParams as FrameLayout.LayoutParams).apply {
                        leftMargin = nativeCenter - second.width / 2; topMargin = lower
                    }
                    compose.layoutParams = (compose.layoutParams as FrameLayout.LayoutParams).apply {
                        leftMargin = composeCenter - compose.width / 2; topMargin = upper
                    }
                }
                SystemClock.sleep(200)
                val before = capture(window)
                try {
                    save(before, "native-compose-before")
                    val top = pixel(before, first)
                    val bottom = pixel(before, second)
                    val fromCompose = pixel(before, compose)
                    var beforeDiagnostics = ""
                    instrumentation.runOnMainSync {
                        beforeDiagnostics = "${scope.diagnostics(second)}; ${scope.samplingDiagnostics()}; ${scope.samplingPerformance()}"
                    }
                    assertTrue("Wrong shared-field crop: $top / $bottom", Color.blue(top) > Color.blue(bottom))
                    assertTrue("Wrong Compose crop: $top / $fromCompose", abs(Color.blue(top) - Color.blue(fromCompose)) < 12)
                    var recordings = 0L
                    instrumentation.runOnMainSync {
                        assertEquals(1, scope.samplingDiagnostics().attachedSurfaces)
                        recordings = scope.materialRecordings
                        compose.translationY = (second.top - first.top).toFloat()
                        compose.invalidate()
                        // 同一帧重复绘制多个卡片不会增加采样绑定。
                        first.invalidate(); second.invalidate()
                    }
                    SystemClock.sleep(500)
                    val moved = capture(window)
                    try {
                        save(moved, "native-compose-moved")
                        val after = pixel(moved, compose)
                        val movedBottom = pixel(moved, second)
                        var diagnostics = ""
                        instrumentation.runOnMainSync {
                            val nativePosition = IntArray(2); val composePosition = IntArray(2)
                            second.getLocationInWindow(nativePosition); compose.getLocationInWindow(composePosition)
                            diagnostics = "${scope.diagnostics(second)}; ${scope.samplingDiagnostics()}; ${scope.samplingPerformance()}; " +
                                "recordings=$recordings->${scope.materialRecordings}; owner=${list.width}x${list.height}; " +
                                "native=${nativePosition.toList()}, compose=${composePosition.toList()}, translation=${compose.translationY}"
                        }
                        val details = "beforeTop=${Integer.toHexString(top)}, beforeBottom=${Integer.toHexString(bottom)}, " +
                            "beforeCompose=${Integer.toHexString(fromCompose)}, movedBottom=${Integer.toHexString(movedBottom)}, " +
                            "movedCompose=${Integer.toHexString(after)}; before=[$beforeDiagnostics]; moved=[$diagnostics]"
                        val evidence = File(instrumentation.targetContext.getExternalFilesDir(null), "lumen-mica")
                        File(evidence, "native-compose-metrics.txt").writeText(details)
                        assertTrue("Moved Compose crop stayed stale: $details", Color.red(after) > Color.red(fromCompose))
                        assertTrue("Moved Compose differs from native: $details", abs(Color.blue(bottom) - Color.blue(after)) < 12)
                    } finally { moved.recycle() }
                    instrumentation.runOnMainSync {
                        assertEquals(1, scope.samplingDiagnostics().attachedSurfaces)
                        assertTrue("Static wallpaper was re-recorded every frame", scope.materialRecordings - recordings < 8)
                        paint.alpha = 0; compose.invalidate()
                    }
                    SystemClock.sleep(200)
                    val transparent = capture(window)
                    try {
                        val raw = pixel(transparent, compose)
                        val position = IntArray(2)
                        instrumentation.runOnMainSync { compose.getLocationInWindow(position) }
                        val clear = transparent.getPixel(transparent.width - 30, position[1] + compose.height / 2)
                        assertTrue("Drawable alpha was not applied: $raw / $clear",
                            abs(Color.red(raw) - Color.red(clear)) <= 2 &&
                                abs(Color.blue(raw) - Color.blue(clear)) <= 2)
                    } finally { transparent.recycle() }
                    instrumentation.runOnMainSync {
                        paint.alpha = 255; compose.invalidate()
                        list.translationX = list.width * 2f
                    }
                    SystemClock.sleep(150)
                    instrumentation.runOnMainSync {
                        assertTrue("An attached offscreen page must stop acrylic sampling", scope.samplingDiagnostics().paused)
                        list.translationX = 0f
                    }
                    SystemClock.sleep(400)
                    val returned = capture(window)
                    try {
                        val nativePixel = pixel(returned, first)
                        val composePixel = pixel(returned, compose)
                        assertTrue("Native acrylic did not return without touch: $top -> $nativePixel",
                            abs(Color.blue(top) - Color.blue(nativePixel)) < 12)
                        assertTrue("Compose acrylic did not return without touch: $bottom -> $composePixel",
                            abs(Color.blue(bottom) - Color.blue(composePixel)) < 12)
                    } finally { returned.recycle() }
                    instrumentation.runOnMainSync {
                        assertFalse("Returning to the page must resume its shared field", scope.samplingDiagnostics().paused)
                        paint.close()
                        scope.close()
                        assertEquals(0, scope.samplingDiagnostics().attachedSurfaces)
                        assertNull(first.background); assertNull(second.background)
                    }
                } finally { before.recycle() }
            } finally { instrumentation.runOnMainSync { paint.close(); scope.close() } }
        }
    }

    private fun pixel(bitmap: Bitmap, view: View): Int {
        val location = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationInWindow(location) }
        return bitmap.getPixel(location[0] + view.width / 2, location[1] + view.height / 2)
    }

    private fun capture(window: Window): Bitmap {
        val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = -1
        PixelCopy.request(window, bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        return bitmap
    }

    private fun save(bitmap: Bitmap, name: String) {
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "lumen-mica").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
