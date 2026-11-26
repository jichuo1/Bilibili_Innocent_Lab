package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceStyle
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceScope
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassMaterial
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Complete module chrome is the reference; fine detail must not leak through a second sample alpha. */
@SdkSuppress(minSdkVersion = 33)
@RunWith(AndroidJUnit4::class)
class HostChromeOpacityInstrumentedTest {
    @Test fun compareCompleteModuleSoftLensWithHostLayers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "host-chrome-opacity").apply { mkdirs() }
        val density = instrumentation.targetContext.resources.displayMetrics.density
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var window: Window
            lateinit var page: FrameLayout
            lateinit var content: View
            lateinit var glass: View
            var moduleSession: LumenSurfaceSession? = null
            var scope: HostSurfaceScope? = null
            val pictures = LinkedHashMap<String, Bitmap>()
            val results = ArrayList<String>()
            scenario.onActivity { activity ->
                window = activity.window
                page = FrameLayout(activity)
                content = DetailContent(activity)
                page.addView(content, FrameLayout.LayoutParams(-1, -1))
                glass = View(activity)
                page.addView(glass, FrameLayout.LayoutParams(-1, (64 * density).toInt()).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    leftMargin = (16 * density).toInt(); rightMargin = leftMargin
                })
                activity.setContentView(page)
            }
            try {
                for (profile in listOf("module-soft", "host-soft", "old-transparent-soft", "host-liquid",
                    "module-soft-dark", "host-soft-dark", "old-transparent-soft-dark")) {
                    instrumentation.runOnMainSync {
                        scope?.close(); moduleSession?.close(); scope = null; moduleSession = null
                        glass.background = null; page.background = null
                        val dark = profile.endsWith("-dark")
                        val colors = HostChromeColors(dark, 0xFFFF6699.toInt())
                        val palette = colors.palette()
                        if (profile.startsWith("module-soft")) {
                            val options = HostSurfaceStyle.floating(dark, 32f,
                                HostChromeAppearance(material = HostGlassMaterial.SOFT))
                            moduleSession = LumenSurfaceSession(page.context, palette).also {
                                it.bind(glass, options, content)
                            }
                        } else {
                            val liquid = profile.contains("liquid")
                            var options = HostSurfaceStyle.floating(dark, 32f,
                                HostChromeAppearance(material = if (liquid) HostGlassMaterial.LIQUID else HostGlassMaterial.SOFT))
                            if (profile.startsWith("old-transparent-soft")) options = options.copy(backdropOpacity = (if (dark) 135 else 143) / 255f)
                            scope = HostSurfaceScope(glass, colors).also { it.attach(glass, content); it.surface(glass, options) }
                        }
                        content.invalidate(); glass.invalidate()
                    }
                    SystemClock.sleep(1000)
                    instrumentation.runOnMainSync { content.invalidate(); glass.invalidate() }
                    SystemClock.sleep(150)
                    val picture = capture(window)
                    pictures[profile] = picture
                    File(output, "$profile.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    results += "$profile fine-detail edge contrast=${detailContrast(picture, glass)}"
                    instrumentation.runOnMainSync {
                        if (moduleSession != null) assertTrue("No GPU samples for $profile", moduleSession!!.diagnostics().gpuDraws > 0)
                        if (scope != null) assertTrue("No GPU samples for $profile", (scope!!.diagnostics()?.gpuDraws ?: 0) > 0)
                    }
                }
                val host = detailContrast(pictures.getValue("host-soft"), glass)
                val complete = detailContrast(pictures.getValue("module-soft"), glass)
                val old = detailContrast(pictures.getValue("old-transparent-soft"), glass)
                val liquid = detailContrast(pictures.getValue("host-liquid"), glass)
                assertTrue("Soft chrome still leaks unblurred detail: $host / $old", host < old * .1)
                assertTrue("Complete module and host soft detail diverged: $host / $complete", abs(host - complete) < .5)
                assertTrue("Liquid lost its distinct clear detail: $liquid / $host", liquid > host + 1)
                val dark = detailContrast(pictures.getValue("host-soft-dark"), glass)
                val darkOriginal = detailContrast(pictures.getValue("old-transparent-soft-dark"), glass)
                assertTrue("Dark legibility compensation restored the unwanted sample alpha: $dark / $darkOriginal", dark < darkOriginal * .1)
                results += "soft retains GPU 17dp and software 10dp; all profiles leave user preferences unchanged"
            } finally {
                instrumentation.runOnMainSync { scope?.close(); moduleSession?.close() }
                pictures.values.forEach(Bitmap::recycle)
                File(output, "results.txt").writeText(results.joinToString("\n"))
            }
        }
    }

    private fun detailContrast(bitmap: Bitmap, glass: View): Double {
        val position = IntArray(2)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { glass.getLocationInWindow(position) }
        var contrast = 0L; var count = 0L
        // Upper half contains only equal-width black/white stripes, with no text or surface edges.
        for (y in position[1] + 12 until position[1] + glass.height / 2 - 8 step 2)
            for (x in position[0] + glass.height until position[0] + glass.width - glass.height - 1) {
                contrast += abs(Color.red(bitmap.getPixel(x, y)) - Color.red(bitmap.getPixel(x + 1, y)))
                count++
            }
        return contrast.toDouble() / count.coerceAtLeast(1)
    }

    private fun capture(window: Window): Bitmap {
        val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1); var result = -1
        PixelCopy.request(window, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue(latch.await(3, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
        return bitmap
    }

    private class DetailContent(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val stripe = 4 * resources.displayMetrics.density
            for (column in 0..(width / stripe).toInt()) {
                paint.color = if (column % 2 == 0) Color.BLACK else Color.WHITE
                canvas.drawRect(column * stripe, 0f, (column + 1) * stripe, height.toFloat(), paint)
            }
            paint.color = Color.rgb(225, 230, 236)
            canvas.drawRect(0f, height / 2f, width.toFloat(), height.toFloat(), paint)
            paint.color = Color.BLACK; paint.textSize = 16 * resources.displayMetrics.density
            for (line in 0..height / 40) canvas.drawText("柔光透镜 · Liquid · 下方内容 ABC 123", 40f,
                height / 2f + line * 28 * resources.displayMetrics.density, paint)
        }
    }
}
