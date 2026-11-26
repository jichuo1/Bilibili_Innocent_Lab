package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeContentSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeViewportSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceScope
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceStyle
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostBottomBarFxController
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import com.lumen.coacervation.engine.host.LumenSourceAvailability
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostChromeViewportInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun croppedChromeBlursFineDetailAndRefreshesChildContent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var window: Window
            lateinit var scope: HostSurfaceScope
            lateinit var glass: View
            lateinit var detail: View
            var phase = 0
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                window = activity.window
                val root = FrameLayout(activity)
                val owner = FrameLayout(activity)
                detail = object : View(activity) {
                    private val paint = Paint()
                    override fun onDraw(canvas: Canvas) {
                        for (x in 0 until width step 8) {
                            paint.color = if (phase == 0) Color.BLACK else Color.RED
                            canvas.drawRect(x.toFloat(), 0f, x + 4f, height.toFloat(), paint)
                            paint.color = if (phase == 0) Color.WHITE else Color.YELLOW
                            canvas.drawRect(x + 4f, 0f, x + 8f, height.toFloat(), paint)
                        }
                    }
                }
                owner.addView(detail, FrameLayout.LayoutParams(-1, -1))
                // The host content starts below its header. Overlay children do not inherit
                // this owner's ordinary parent chain when queried in Window coordinates.
                root.addView(owner, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 244 })
                glass = FrameLayout(activity).apply {
                    alpha = .98f
                    HostBottomBarFxController.prepareGlassContainer(this)
                }
                root.addView(glass, FrameLayout.LayoutParams(-1, 132).apply {
                    gravity = android.view.Gravity.BOTTOM
                    bottomMargin = 33; leftMargin = 104; rightMargin = 104
                })
                activity.setContentView(root)
            }
            val output = File(instrumentation.targetContext.getExternalFilesDir(null), "chrome-viewport").apply { mkdirs() }
            val results = ArrayList<String>()
            try {
                fun sample(name: String): Pair<Double, Double> {
                    val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
                    val done = CountDownLatch(1)
                    var result = -1
                    PixelCopy.request(window, bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
                    assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
                    val position = IntArray(2)
                    instrumentation.runOnMainSync { glass.getLocationInWindow(position) }
                    try {
                        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        var contrast = 0L; var red = 0L; var count = 0
                        for (y in 24 until glass.height - 24 step 4) for (x in 90 until glass.width - 90) {
                            val color = bitmap.getPixel(position[0] + x, position[1] + y)
                            val next = bitmap.getPixel(position[0] + x + 1, position[1] + y)
                            contrast += abs(Color.red(color) - Color.red(next))
                            red += Color.red(color); count++
                        }
                        return (contrast.toDouble() / count to red.toDouble() / count).also { results += "$name: $it" }
                    } finally { bitmap.recycle() }
                }
                for (backend in listOf(LumenSurfaceBackend.AUTO, LumenSurfaceBackend.SOFTWARE)) {
                    instrumentation.runOnMainSync {
                        phase = 0; detail.invalidate()
                        val colors = HostChromeColors(true, Color.MAGENTA)
                        scope = HostSurfaceScope(glass, colors, samplingBackend = backend)
                        scope.attach(glass, detail.parent as FrameLayout)
                        scope.floating(glass, true, 24f)
                    }
                    try {
                        SystemClock.sleep(700)
                        val before = sample("$backend-before")
                        instrumentation.runOnMainSync {
                            val diagnostics = requireNotNull(scope.diagnostics())
                            if (backend == LumenSurfaceBackend.AUTO) assertTrue(diagnostics.gpuDraws > 0)
                            else assertTrue(diagnostics.softwareDraws > 0)
                        }
                        assertTrue("$backend cropped chrome leaked sharp stripes: $before", before.first < 1)
                        instrumentation.runOnMainSync { glass.alpha = .98f }
                        SystemClock.sleep(80)
                        val inheritedAlpha = sample("$backend-inherited-alpha")
                        assertTrue("The inherited container alpha was not exercised: $inheritedAlpha",
                            inheritedAlpha.first > before.first + .8)
                        instrumentation.runOnMainSync { HostBottomBarFxController.prepareGlassContainer(glass) }
                        instrumentation.runOnMainSync { phase = 1; detail.invalidate() }
                        SystemClock.sleep(400)
                        val after = sample("$backend-after")
                        assertTrue("$backend cropped chrome froze child content: $before -> $after", after.second > before.second + 15)
                        assertTrue("$backend updated chrome leaked sharp stripes: $after", after.first < 1)
                        instrumentation.runOnMainSync { glass.alpha = .5f }
                        SystemClock.sleep(80)
                        instrumentation.runOnMainSync {
                            assertEquals("A later native fade was overridden", .5f, glass.alpha, 0f)
                            glass.alpha = 1f
                        }
                    } finally { instrumentation.runOnMainSync { scope.close() } }
                }
            } finally { File(output, "results.txt").writeText(results.joinToString("\n")) }
        }
    }

    @Test fun croppedChromePreservesPixelsAndTracksMovementWithLessGpuContent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var window: Window
            lateinit var cropped: HostSurfaceScope
            lateinit var reference: LumenSurfaceSession
            lateinit var first: View
            lateinit var second: View
            lateinit var owner: FrameLayout
            lateinit var referenceOwner: FrameLayout
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                window = activity.window
                val root = FrameLayout(activity)
                fun fixture(left: Int): Pair<FrameLayout, View> {
                    val owner = FrameLayout(activity).apply {
                        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(Color.BLUE, Color.RED))
                    }
                    // A native foreground as well as the owner's background must survive cropping.
                    owner.addView(View(activity).apply { setBackgroundColor(Color.GREEN) },
                        FrameLayout.LayoutParams(480, 110).apply { topMargin = 340 })
                    val glass = View(activity)
                    root.addView(owner, FrameLayout.LayoutParams(480, 900).apply { leftMargin = left + 40; topMargin = 244 })
                    root.addView(glass, FrameLayout.LayoutParams(350, 140).apply {
                        leftMargin = left + 90; topMargin = 544
                    })
                    return owner to glass
                }
                val a = fixture(0); val b = fixture(520)
                owner = a.first
                referenceOwner = b.first
                first = a.second; second = b.second
                activity.setContentView(root)
                val colors = HostChromeColors(false, Color.MAGENTA)
                val base = HostSurfaceStyle.floating(false, 12f)
                val options = base.copy(sampling = base.sampling.copy(blurRadiusDp = 6f,
                    softwareBlurRadiusDp = 6f, refractionEnabled = false))
                cropped = HostSurfaceScope(first, colors)
                cropped.attach(first, a.first)
                cropped.surface(first, options)
                reference = LumenSurfaceSession(activity, colors.palette())
                reference.bindSource(second, options, HostChromeContentSource(b.first, colors.palette().background))
            }
            try {
                SystemClock.sleep(900)
                instrumentation.runOnMainSync {
                    val small = requireNotNull(cropped.samplingPerformance()).logicalGpuContentPixels
                    val full = reference.performanceDiagnostics().logicalGpuContentPixels
                    assertTrue("Chrome content: $small / $full; ${cropped.diagnostics()}; ${cropped.samplingPerformance()}", small > 0 && small * 2 < full)
                    assertTrue(requireNotNull(cropped.diagnostics()).gpuDraws > 0)
                }
                compare(window, first, second)
                var recordings = 0L
                instrumentation.runOnMainSync { recordings = requireNotNull(cropped.diagnostics()).contentRecordings }
                repeat(12) {
                    instrumentation.runOnMainSync { first.invalidate() }
                    SystemClock.sleep(32)
                }
                instrumentation.runOnMainSync {
                    assertTrue("An idle chrome proxy kept recording",
                        requireNotNull(cropped.diagnostics()).contentRecordings - recordings <= 2)
                }
                instrumentation.runOnMainSync {
                    first.translationY = 260f; second.translationY = 260f
                    cropped.onVisualMovement(); reference.notifyPositionChanged()
                }
                SystemClock.sleep(400)
                compare(window, first, second)
                instrumentation.runOnMainSync {
                    owner.translationY = 90f; referenceOwner.translationY = 90f
                    reference.notifyPositionChanged()
                }
                SystemClock.sleep(400)
                compare(window, first, second)
                instrumentation.runOnMainSync {
                    owner.scrollY = 23; referenceOwner.scrollY = 23
                    reference.notifyPositionChanged()
                }
                SystemClock.sleep(400)
                compare(window, first, second)
                instrumentation.runOnMainSync {
                    cropped.detach()
                    assertNull(first.background)
                    cropped.attach(first, owner)
                }
                SystemClock.sleep(400)
                compare(window, first, second)
            } finally { instrumentation.runOnMainSync { cropped.close(); reference.close() } }
        }
    }

    @Test fun coordinateProxyCannotHideSelfSamplingFromTheEngineGuard() {
        instrumentation.runOnMainSync {
            val owner = FrameLayout(instrumentation.targetContext)
            val glass = View(owner.context)
            owner.addView(glass)
            owner.layout(0, 0, 480, 900); glass.layout(0, 0, 300, 100)
            val source = requireNotNull(HostChromeViewportSource.create(HostChromeContentSource(owner, Color.BLACK)) {
                it(glass, 0f, 0f, 20f)
            })
            try {
                source.sync()
                assertEquals(LumenSourceAvailability.PROHIBITED, source.availability)
            } finally { source.close() }
        }
    }

    private fun compare(window: Window, first: View, second: View) {
        val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = -1
        PixelCopy.request(window, bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
        try {
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
            val a = IntArray(2); val b = IntArray(2)
            instrumentation.runOnMainSync { first.getLocationInWindow(a); second.getLocationInWindow(b) }
            var total = 0L; var count = 0; var maximum = 0
            for (y in 18 until 122 step 5) for (x in 18 until 332 step 6) {
                val left = bitmap.getPixel(a[0] + x, a[1] + y)
                val right = bitmap.getPixel(b[0] + x, b[1] + y)
                for (shift in intArrayOf(0, 8, 16)) {
                    val difference = abs((left ushr shift and 255) - (right ushr shift and 255))
                    total += difference; count++; maximum = maxOf(maximum, difference)
                }
            }
            assertTrue("Cropped glass differs: mean=${total.toDouble() / count}, max=$maximum",
                total.toDouble() / count <= 1.0 && maximum <= 4)
        } finally { bitmap.recycle() }
    }
}
