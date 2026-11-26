package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
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
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassMaterial
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassRenderer
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 同内容、同位置、同设备的像素与帧耗时对照；不更改用户偏好。数字仅代表此受控场景。 */
@SdkSuppress(minSdkVersion = 33)
@RunWith(AndroidJUnit4::class)
class HostChromeRenderingInstrumentedTest {
    @Test fun compareOriginalSoftGpuAndLiquidWithIdenticalContent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "host-chrome-comparison").apply { mkdirs() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var window: Window
            lateinit var content: PatternContent
            lateinit var top: View
            lateinit var bottom: View
            var session: LumenSurfaceSession? = null
            var animator: ValueAnimator? = null
            val bindings = ArrayList<LumenSurfaceBinding>()
            val density = instrumentation.targetContext.resources.displayMetrics.density
            val palette = HostChromeColors(false, 0xFFFF6699.toInt()).palette()
            scenario.onActivity { activity ->
                window = activity.window
                val root = FrameLayout(activity)
                content = PatternContent(activity)
                root.addView(content, FrameLayout.LayoutParams(-1, -1))
                top = View(activity); bottom = View(activity)
                root.addView(top, FrameLayout.LayoutParams(-1, (44 * density).toInt()).apply {
                    leftMargin = (16 * density).toInt(); rightMargin = leftMargin; topMargin = (100 * density).toInt()
                })
                root.addView(bottom, FrameLayout.LayoutParams(-1, (64 * density).toInt()).apply {
                    gravity = android.view.Gravity.BOTTOM
                    leftMargin = (16 * density).toInt(); rightMargin = leftMargin; bottomMargin = (32 * density).toInt()
                })
                activity.setContentView(root)
            }
            val soft = arrayOfNulls<Bitmap>(1)
            val metricsThread = HandlerThread("ChromeFrameMetrics").apply { start() }
            val collecting = AtomicBoolean(false)
            val collectionStart = AtomicLong()
            data class FrameSample(val total: Long, val gpu: Long, val deadline: Long, val vsync: Long)
            val frames = java.util.Collections.synchronizedList(ArrayList<FrameSample>())
            val listener = Window.OnFrameMetricsAvailableListener { _, metric, _ ->
                val vsync = metric.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
                if (collecting.get() && vsync >= collectionStart.get()) frames.add(FrameSample(metric.getMetric(FrameMetrics.TOTAL_DURATION),
                    metric.getMetric(FrameMetrics.GPU_DURATION), metric.getMetric(FrameMetrics.DEADLINE), vsync))
            }
            instrumentation.runOnMainSync { window.addOnFrameMetricsAvailableListener(listener, Handler(metricsThread.looper)) }
            val results = ArrayList<String>()
            try {
                val profiles = listOf(
                    "soft-software" to HostChromeAppearance(renderer = HostGlassRenderer.SOFTWARE),
                    "soft-auto" to HostChromeAppearance(),
                    "liquid-auto" to HostChromeAppearance(material = HostGlassMaterial.LIQUID)
                )
                for ((name, appearance) in profiles) {
                    instrumentation.runOnMainSync {
                        session?.close(); bindings.clear()
                        session = null; content.offset = 0f
                        session = LumenSurfaceSession(top.context, palette)
                        bindings += session!!.bind(top, HostSurfaceStyle.floating(false, 22f, appearance), content)
                        bindings += session!!.bind(bottom, HostSurfaceStyle.floating(false, 32f, appearance), content)
                        content.invalidate(); top.invalidate(); bottom.invalidate()
                    }
                    SystemClock.sleep(100)
                    instrumentation.runOnMainSync { content.invalidate(); top.invalidate(); bottom.invalidate() }
                    SystemClock.sleep(700)
                    val picture = capture(window)
                    File(output, "$name.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    if (name == "soft-software") {
                        soft[0] = picture
                    } else {
                        val difference = surfaceDifference(soft[0]!!, picture, top, bottom)
                        results += "$name vs soft-software: mean absolute RGB difference=$difference/255"
                        if (name == "soft-auto") {
                            assertTrue("GPU and software soft lenses diverged: $difference", difference < 8.0)
                        }
                        if (name == "liquid-auto") {
                            assertTrue("Liquid and soft lens rendered identically", difference > .2)
                        }
                        picture.recycle()
                    }
                    instrumentation.runOnMainSync {
                        bindings.forEach {
                            assertEquals(if (appearance.renderer == HostGlassRenderer.SOFTWARE) LumenSurfaceBackend.SOFTWARE else LumenSurfaceBackend.GPU, it.diagnostics()?.backend)
                        }
                        animator = ValueAnimator.ofFloat(0f, 1f).apply {
                            duration = 1800; repeatCount = ValueAnimator.INFINITE
                            addUpdateListener {
                                content.offset = (it.animatedValue as Float) * 360 * density
                                content.invalidate(); session?.notifyPositionChanged()
                            }
                            start()
                        }
                    }
                    SystemClock.sleep(1000)
                    frames.clear(); collectionStart.set(System.nanoTime()); collecting.set(true)
                    SystemClock.sleep(4000)
                    collecting.set(false)
                    instrumentation.runOnMainSync { animator?.cancel(); animator = null }
                    val ended = System.nanoTime()
                    val recorded = synchronized(frames) { frames.toList().filter { it.vsync < ended }.distinctBy { it.vsync } }
                    assertTrue("No useful frame sample for $name", recorded.size > 30)
                    fun percentile(values: List<Long>, p: Double): Double {
                        val sorted = values.filter { it >= 0 }.sorted()
                        return if (sorted.isEmpty()) -1.0 else sorted[((sorted.size - 1) * p).toInt()] / 1_000_000.0
                    }
                    results += "$name frames=${recorded.size} duration=${(ended - collectionStart.get()) / 1_000_000.0}ms refresh=${top.display.refreshRate}Hz total p50/p95=${percentile(recorded.map { it.total }, .5)}/${percentile(recorded.map { it.total }, .95)}ms GPU p95=${percentile(recorded.map { it.gpu }, .95)}ms deadline misses=${recorded.count { it.deadline > 0 && it.total > it.deadline }}"
                    instrumentation.runOnMainSync {
                        bindings.first().setShapesPixels(top.width - top.height.toFloat(), 0f, top.height.toFloat(), top.height.toFloat(), 0f, 0f, 0f, 0f, false)
                        content.offset = 0f; content.invalidate(); session?.notifyPositionChanged()
                    }
                    SystemClock.sleep(700)
                    val collapsed = capture(window)
                    File(output, "$name-collapsed.png").outputStream().use { collapsed.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    collapsed.recycle()
                    instrumentation.runOnMainSync {
                        assertEquals(if (appearance.renderer == HostGlassRenderer.SOFTWARE) LumenSurfaceBackend.SOFTWARE else LumenSurfaceBackend.GPU, bindings.first().diagnostics()?.backend)
                    }
                }
            } finally {
                instrumentation.runOnMainSync {
                    animator?.cancel(); session?.close()
                    window.removeOnFrameMetricsAvailableListener(listener)
                }
                metricsThread.quitSafely(); metricsThread.join(3000)
                soft[0]?.recycle()
                File(output, "results.txt").writeText(results.joinToString("\n"))
                results.forEach { Log.i("HostChromeComparison", it) }
            }
        }
    }

    private fun surfaceDifference(a: Bitmap, b: Bitmap, vararg views: View): Double {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val regions = views.map { view ->
            val location = IntArray(2)
            instrumentation.runOnMainSync { view.getLocationInWindow(location) }
            android.graphics.Rect(location[0] + view.height / 2, location[1] + 3, location[0] + view.width - view.height / 2, location[1] + view.height - 3)
        }
        var difference = 0L; var count = 0L
        regions.forEach { region ->
            for (y in region.top until region.bottom step 3) for (x in region.left until region.right step 3) {
                val left = a.getPixel(x, y); val right = b.getPixel(x, y)
                difference += abs(Color.red(left) - Color.red(right)) + abs(Color.green(left) - Color.green(right)) + abs(Color.blue(left) - Color.blue(right))
                count += 3
            }
        }
        return difference.toDouble() / count.coerceAtLeast(1)
    }

    private fun capture(window: Window): Bitmap {
        val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var result = -1
        PixelCopy.request(window, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue(latch.await(3, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
        return bitmap
    }

    private class PatternContent(context: android.content.Context) : View(context) {
        var offset = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) = drawPattern(canvas)
        private fun drawPattern(canvas: Canvas) {
            canvas.drawColor(Color.rgb(234, 238, 242))
            val cell = 48 * resources.displayMetrics.density
            val colors = intArrayOf(0xFF2F6999.toInt(), 0xFFD47050.toInt(), 0xFF5C9774.toInt(), 0xFFE4C765.toInt())
            for (row in -2..(height / cell).toInt() + 8) for (col in 0..7) {
                paint.color = colors[((row + col) % 4 + 4) % 4]
                val x = col * width / 8f; val y = row * cell - offset % (cell * 4)
                canvas.drawRect(x + 3, y + 3, x + width / 8f - 3, y + cell - 3, paint)
                paint.color = Color.WHITE; paint.strokeWidth = 3f
                canvas.drawLine(x + 8, y + cell * .4f, x + width / 8f - 8, y + cell * .6f, paint)
            }
        }
    }
}
