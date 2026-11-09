package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundDrawable
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundRasterCache
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HostBackgroundRasterInstrumentedTest {
    @Test fun celestialArtworkIsStableAcrossThemeAndSizeChanges() {
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "host-background-raster-qa").apply { mkdirs() }
        val montage = Bitmap.createBitmap(1080, 1440, Bitmap.Config.ARGB_8888)
        val presets = listOf(HostBackgroundPreset.STARRY, HostBackgroundPreset.NEBULA, HostBackgroundPreset.METEOR)
        try {
            for ((column, preset) in presets.withIndex()) {
                val drawable = HostBackgroundDrawable(HostBackgroundConfig(preset), cacheEnabled = false)
                drawable.setBounds(0, 0, 360, 720)
                val day = Bitmap.createBitmap(360, 720, Bitmap.Config.ARGB_8888)
                val night = Bitmap.createBitmap(360, 720, Bitmap.Config.ARGB_8888)
                val restored = Bitmap.createBitmap(360, 720, Bitmap.Config.ARGB_8888)
                try {
                    drawable.draw(Canvas(day))
                    drawable.night = true
                    drawable.draw(Canvas(night))
                    assertTrue("Celestial artwork must retain its night sky in either app theme: $preset", day.sameAs(night))
                    drawable.setBounds(0, 0, 720, 360)
                    drawable.draw(Canvas(restored))
                    restored.eraseColor(Color.TRANSPARENT)
                    drawable.setBounds(0, 0, 360, 720)
                    drawable.night = false
                    drawable.draw(Canvas(restored))
                    assertTrue("Star positions must survive theme and size changes for $preset", day.sameAs(restored))
                    Canvas(montage).apply {
                        drawBitmap(day, column * 360f, 0f, null)
                        drawBitmap(night, column * 360f, 720f, null)
                    }
                } finally { day.recycle(); night.recycle(); restored.recycle() }
            }
            File(output, "celestial-presets.png").outputStream().use { montage.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { montage.recycle() }
    }

    @Test fun recycledSourceDoesNotBreakCacheEviction() {
        val source = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE); setHasAlpha(false) }
        fun request(key: HostBackgroundRasterCache.Key) {
            val latch = CountDownLatch(1)
            var result: Bitmap? = null
            HostBackgroundRasterCache.request(key) { result = it; latch.countDown() }
            assertTrue("Cache callback must complete", latch.await(5, TimeUnit.SECONDS))
            assertNotNull(result)
        }
        try {
            val key = HostBackgroundRasterCache.Key(HostBackgroundConfig(HostBackgroundPreset.CUSTOM), false, 40, 80, source)
            request(key)
            source.recycle()
            // 三张实际尺寸预设超过共享缓存上限，必须能安全淘汰已经释放源图的旧条目。
            for (preset in listOf(HostBackgroundPreset.AURORA, HostBackgroundPreset.SAKURA, HostBackgroundPreset.SUNSET)) {
                request(HostBackgroundRasterCache.Key(HostBackgroundConfig(preset), false, 1200, 2200, null))
            }
            assertNull(HostBackgroundRasterCache.get(key))
        } finally { if (!source.isRecycled) source.recycle() }
    }

    @Test fun hardwareRasterMatchesDirectDrawingAcrossPresetsThemesAndImages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.cacheDir, "host-background-raster-qa").apply { mkdirs() }
        val opaque = Bitmap.createBitmap(360, 720, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            Canvas(this).drawCircle(180f, 360f, 160f, Paint().apply { color = 0xff784ca0.toInt() })
            setHasAlpha(false)
        }
        val transparent = Bitmap.createBitmap(360, 720, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawCircle(180f, 360f, 160f, Paint().apply { color = 0x88784ca0.toInt() })
        }
        val rows = mutableListOf<String>()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // 条款门禁下 MainActivity 上方是模块的条款对话框（独立窗口，截图里一并出现）：它的滚动条在刚出现时可见、
                // 随后淡出。第一个用例（index 0）的“前”截图若落在淡出之前，就会得到一条 11x242 像素、误差约 40–90 的竖条差异，
                // 与背景绘制无关（未改动的 a89a6956 上同样出现）。先等滚动条淡出再逐用例比较。
                SystemClock.sleep(1500)
                lateinit var surface: View
                scenario.onActivity { activity ->
                    surface = View(activity)
                    (activity.window.decorView as ViewGroup).addView(surface, ViewGroup.LayoutParams(-1, -1))
                }
                val presets = HostBackgroundPreset.entries.filter { it != HostBackgroundPreset.OFF && it != HostBackgroundPreset.CUSTOM }
                val cases = presets.flatMap { preset -> listOf(false, true).map { Triple(HostBackgroundConfig(preset), it, null as Bitmap?) } } +
                    listOf(false, true).flatMap { night -> listOf(0, 35, 90).flatMap { veil -> listOf(opaque, transparent).map {
                        Triple(HostBackgroundConfig(HostBackgroundPreset.CUSTOM, veil = veil), night, it)
                    } } }
                for ((index, case) in cases.withIndex()) {
                    val (config, night, image) = case
                    scenario.onActivity { surface.background = HostBackgroundDrawable(config, night, image, cacheEnabled = false) }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(120)
                    val before = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    lateinit var cached: HostBackgroundDrawable
                    scenario.onActivity { cached = HostBackgroundDrawable(config, night, image); surface.background = cached }
                    if (image == null || !image.hasAlpha()) {
                        val deadline = SystemClock.uptimeMillis() + 4000
                        var ready = false
                        while (!ready && SystemClock.uptimeMillis() < deadline) {
                            SystemClock.sleep(50)
                            scenario.onActivity { ready = cached.isRasterReady }
                        }
                        assertTrue("Cache did not become ready for $case", ready)
                    } else {
                        SystemClock.sleep(250)
                        scenario.onActivity { assertFalse("Transparent images must keep original sampling", cached.isRasterReady) }
                    }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(120)
                    val after = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    var difference = 0L
                    var maxError = 0
                    var count = 0
                    val pixelsBefore = IntArray(before.width * before.height)
                    val pixelsAfter = IntArray(after.width * after.height)
                    before.getPixels(pixelsBefore, 0, before.width, 0, 0, before.width, before.height)
                    after.getPixels(pixelsAfter, 0, after.width, 0, 0, after.width, after.height)
                    // 系统状态栏时钟、手势提示和侧边系统浮层不属于背景，排除边缘后逐像素比较。
                    for (y in 160 until before.height - 160) for (x in 24 until before.width - 24) {
                        val a = pixelsBefore[y * before.width + x]; val b = pixelsAfter[y * after.width + x]
                        for (channel in 0..2) {
                            val shift = channel * 8
                            val error = abs(((a ushr shift) and 255) - ((b ushr shift) and 255))
                            difference += error; maxError = maxOf(maxError, error); count++
                        }
                    }
                    val mean = difference.toDouble() / count
                    rows += "$index preset=${config.preset} night=$night veil=${config.veil} alpha=${image?.hasAlpha()} maxError=$maxError meanError=$mean"
                    File(output, "results.txt").writeText(rows.joinToString("\n"))
                    if (index == 0 || index == 6 || index in 10..15 || maxError > 3 || mean > .7) {
                        File(output, "$index-before.png").outputStream().use { before.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        File(output, "$index-after.png").outputStream().use { after.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    before.recycle(); after.recycle()
                    // 代码云气纹理的 CPU/GPU 双线性取样与多层混色存在颜色舍入差异。
                    // 保留旧预设门禁；新预设仍限制单点与整图误差，拒绝星点丢失、移位或大面积变色。
                    val matches = if (config.preset.isCelestial) maxError <= 8 && mean <= 1.25
                        else maxError <= 3 && mean <= .7
                    assertTrue("Visible raster difference: ${rows.last()}", matches)
                }
                scenario.onActivity { surface.background = null; (surface.parent as ViewGroup).removeView(surface) }
            }
        } finally { opaque.recycle(); transparent.recycle() }
    }

    @Test fun transparentImagesFadeAndColorFiltersKeepTheDirectPath() {
        val source = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(0x808050c0.toInt()) }
        val target = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)
        val drawable = HostBackgroundDrawable(HostBackgroundConfig(HostBackgroundPreset.CUSTOM), image = source)
        drawable.setBounds(0, 0, 200, 400)
        drawable.draw(Canvas(target))
        assertEquals(255, Color.alpha(target.getPixel(100, 200)))
        target.eraseColor(Color.TRANSPARENT)
        drawable.alpha = 128
        drawable.draw(Canvas(target))
        assertTrue(Color.alpha(target.getPixel(100, 200)) in 128..254)
        target.eraseColor(Color.TRANSPARENT)
        drawable.alpha = 255
        drawable.setColorFilter(android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) }))
        drawable.draw(Canvas(target))
        val gray = target.getPixel(100, 200)
        assertTrue(abs(Color.red(gray) - Color.green(gray)) <= 1 && abs(Color.green(gray) - Color.blue(gray)) <= 1)
        target.eraseColor(Color.TRANSPARENT)
        drawable.alpha = 0
        drawable.draw(Canvas(target))
        assertEquals(Color.TRANSPARENT, target.getPixel(100, 200))
        source.recycle(); target.recycle()
    }
}
