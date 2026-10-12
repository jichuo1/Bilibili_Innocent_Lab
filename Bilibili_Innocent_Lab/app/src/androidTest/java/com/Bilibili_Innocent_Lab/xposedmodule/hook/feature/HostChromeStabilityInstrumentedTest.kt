package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.Dialog
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceScope
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceStyle
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassMaterial
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassRenderer
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import java.util.concurrent.CountDownLatch
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real window pixels and backend continuity; no preference changes. */
@SdkSuppress(minSdkVersion = 33)
@RunWith(AndroidJUnit4::class)
class HostChromeStabilityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val profiles = listOf(HostChromeAppearance(),
        HostChromeAppearance(renderer = HostGlassRenderer.SOFTWARE),
        HostChromeAppearance(material = HostGlassMaterial.LIQUID))

    @Test fun tintAnimationKeepsTheSampleAndSelectedBackend() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var content: Stripes
            lateinit var surface: View
            lateinit var window: Window
            scenario.onActivity { activity ->
                window = activity.window
                val root = FrameLayout(activity)
                content = Stripes(activity)
                surface = View(activity)
                root.addView(content, FrameLayout.LayoutParams(-1, -1))
                root.addView(surface, FrameLayout.LayoutParams(-1, 180).apply { topMargin = 400 })
                activity.setContentView(root)
            }
            for (profile in profiles) {
                var session: LumenSurfaceSession? = null
                lateinit var binding: LumenSurfaceBinding
                val options = HostSurfaceStyle.floating(true, 24f, profile)
                val expected = if (profile.renderer == HostGlassRenderer.SOFTWARE)
                    LumenSurfaceBackend.SOFTWARE else LumenSurfaceBackend.GPU
                try {
                    instrumentation.runOnMainSync {
                        session = LumenSurfaceSession(surface.context, HostChromeColors(true, Color.MAGENTA).palette())
                        binding = session!!.bind(surface, options, content)
                    }
                    SystemClock.sleep(600)
                    val reference = capture(window)
                    save(reference, "${profile.material}-${profile.renderer}-reference")
                    // Liquid intentionally retains clear detail through backdropOpacity; compare
                    // against its own settled material, while soft must suppress fine detail.
                    val maxDetail = if (profile.material == HostGlassMaterial.LIQUID)
                        contrast(reference, surface) + .5 else 2.0
                    try {
                        for (step in 1..12) {
                            instrumentation.runOnMainSync {
                                assertEquals("$profile before tint", expected, binding.diagnostics()?.backend)
                                // Both tint values change on every dark-legibility transition.
                                val tint = options.tintOpacity + step / 255f
                                binding.update(options.copy(tintOpacity = tint, fallbackTintOpacity = tint))
                                assertEquals("$profile discarded its ready frame on tint update", expected,
                                    binding.diagnostics()?.backend)
                            }
                            SystemClock.sleep(32)
                            val image = capture(window)
                            try {
                                val detail = contrast(image, surface)
                                if (step == 1 || detail >= maxDetail) save(image, "${profile.material}-${profile.renderer}-step-$step")
                                assertTrue("$profile exposed sharp content during tint: $detail (limit $maxDetail)", detail < maxDetail)
                            } finally { image.recycle() }
                        }
                        instrumentation.runOnMainSync {
                            content.phase = 1
                            content.invalidate()
                            session!!.notifyContentChanged()
                        }
                        SystemClock.sleep(250)
                        val updated = capture(window)
                        try {
                            assertTrue("$profile kept a frozen backdrop", difference(reference, updated, surface) > 8.0)
                        } finally { updated.recycle() }
                    } finally { reference.recycle() }
                } finally { instrumentation.runOnMainSync { session?.close(); content.phase = 0 } }
            }
        }
    }

    @Test fun hostGlassSurvivesFocusLossAndRecoversAfterMemoryPressure() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var content: Stripes
            lateinit var surface: View
            lateinit var window: Window
            lateinit var dialog: Dialog
            scenario.onActivity { activity ->
                window = activity.window
                val root = FrameLayout(activity)
                content = Stripes(activity)
                surface = View(activity)
                root.addView(content, FrameLayout.LayoutParams(-1, -1))
                root.addView(surface, FrameLayout.LayoutParams(-1, 180).apply { topMargin = 400 })
                activity.setContentView(root)
                dialog = Dialog(activity).apply {
                    setContentView(View(activity))
                    this.window!!.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    this.window!!.setGravity(Gravity.BOTTOM or Gravity.END)
                }
            }
            for (profile in profiles) {
                var scope: HostSurfaceScope? = null
                try {
                    instrumentation.runOnMainSync {
                        scope = HostSurfaceScope(surface, HostChromeColors(false, Color.MAGENTA), appearance = profile)
                        scope!!.attach(surface, content)
                        scope!!.floating(surface, false, 24f)
                    }
                    SystemClock.sleep(600)
                    val reference = capture(window)
                    try {
                        instrumentation.runOnMainSync {
                            dialog.show()
                            dialog.window!!.setLayout(1, 1)
                        }
                        SystemClock.sleep(150)
                        instrumentation.runOnMainSync {
                            assertFalse("Focus-loss trigger was not exercised", content.hasWindowFocus())
                            content.invalidate(); surface.invalidate()
                        }
                        SystemClock.sleep(100)
                        val unfocused = capture(window)
                        try {
                            assertTrue("$profile lost glass on focus loss", difference(reference, unfocused, surface) < 1.0)
                        } finally { unfocused.recycle() }
                        instrumentation.runOnMainSync {
                            // Exercise the real ComponentCallbacks2 path while focus is elsewhere.
                            (instrumentation.targetContext.applicationContext as Application).onTrimMemory(80)
                            dialog.dismiss()
                        }
                        SystemClock.sleep(400)
                        val resumed = capture(window)
                        try {
                            assertTrue("$profile failed to restore glass", difference(reference, resumed, surface) < 1.0)
                        } finally { resumed.recycle() }
                    } finally { reference.recycle() }
                } finally { instrumentation.runOnMainSync { dialog.dismiss(); scope?.close() } }
            }
        }
    }

    @Test fun transparentContentIncludesAndTracksTheActualAncestorBackground() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var root: FrameLayout
            lateinit var content: View
            lateinit var surface: View
            lateinit var window: Window
            scenario.onActivity { activity ->
                window = activity.window
                root = FrameLayout(activity).apply { background = StripeBackdrop() }
                val container = FrameLayout(activity)
                content = View(activity) // Entirely transparent, like the feed's card gaps.
                container.addView(content, FrameLayout.LayoutParams(-1, -1))
                root.addView(container, FrameLayout.LayoutParams(-1, -1).apply { leftMargin = 40; topMargin = 60 })
                surface = View(activity)
                root.addView(surface, FrameLayout.LayoutParams(-1, 180).apply { topMargin = 400 })
                activity.setContentView(root)
            }
            for (profile in profiles) {
                var scope: HostSurfaceScope? = null
                try {
                    instrumentation.runOnMainSync {
                        root.background = StripeBackdrop()
                        scope = HostSurfaceScope(surface, HostChromeColors(false, Color.MAGENTA), appearance = profile)
                        scope!!.attach(surface, content); scope!!.floating(surface, false, 24f)
                    }
                    SystemClock.sleep(500)
                    val striped = capture(window)
                    try {
                        save(striped, "ancestor-${profile.material}-${profile.renderer}")
                        val detail = contrast(striped, surface)
                        assertTrue("$profile sampled transparency instead of the ancestor background: $detail",
                            detail < if (profile.material == HostGlassMaterial.LIQUID) 10.0 else 2.0)
                        instrumentation.runOnMainSync {
                            // Only the ancestor changes; the transparent content stays clean.
                            root.background = ColorDrawable(Color.RED)
                        }
                        SystemClock.sleep(250)
                        val red = capture(window)
                        try {
                            save(red, "ancestor-red-${profile.material}-${profile.renderer}")
                            val changed = difference(striped, red, surface)
                            assertTrue("$profile froze the old ancestor background: $changed", changed > 20.0)
                            instrumentation.runOnMainSync { root.background = ColorDrawable(Color.BLUE) }
                            SystemClock.sleep(250)
                            val blue = capture(window)
                            try {
                                assertTrue("$profile missed a second background change", difference(red, blue, surface) > 20.0)
                            } finally { blue.recycle() }
                        } finally { red.recycle() }
                    } finally { striped.recycle() }
                } finally { instrumentation.runOnMainSync { scope?.close() } }
            }
        }
    }

    private fun capture(window: Window): Bitmap {
        val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = -1
        PixelCopy.request(window, bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue(done.await(3, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
        return bitmap
    }

    private fun save(bitmap: Bitmap, name: String) {
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "host-chrome-stability").apply { mkdirs() }
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun difference(a: Bitmap, b: Bitmap, surface: View): Double {
        val position = IntArray(2)
        instrumentation.runOnMainSync { surface.getLocationInWindow(position) }
        var total = 0L; var count = 0L
        for (y in position[1] + 40 until position[1] + surface.height - 40 step 3)
            for (x in position[0] + 100 until position[0] + surface.width - 100 step 3) {
                val left = a.getPixel(x, y); val right = b.getPixel(x, y)
                total += abs(Color.red(left) - Color.red(right)) + abs(Color.green(left) - Color.green(right)) +
                    abs(Color.blue(left) - Color.blue(right)); count += 3
            }
        return total.toDouble() / count.coerceAtLeast(1)
    }

    private fun contrast(bitmap: Bitmap, surface: View): Double {
        val position = IntArray(2)
        instrumentation.runOnMainSync { surface.getLocationInWindow(position) }
        var total = 0L; var count = 0L
        for (x in position[0] + 100 until position[0] + surface.width - 101) {
            total += abs(Color.red(bitmap.getPixel(x, position[1] + 90)) -
                Color.red(bitmap.getPixel(x + 1, position[1] + 90))); count++
        }
        return total.toDouble() / count.coerceAtLeast(1)
    }

    private class Stripes(context: android.content.Context) : FrameLayout(context) {
        var phase = 0
            set(value) { field = value; pattern.invalidate() }
        private val pattern = object : View(context) {
            private val paint = Paint()
            override fun onDraw(canvas: Canvas) {
                for (x in 0 until width step 8) {
                    paint.color = if (phase == 1) Color.RED else if (x % 16 == 0) Color.BLACK else Color.WHITE
                    canvas.drawRect(x.toFloat(), 0f, x + 8f, height.toFloat(), paint)
                }
            }
        }
        init {
            // Real feeds record child RenderNodes and also draw them in the visible hierarchy.
            val nested = FrameLayout(context)
            nested.addView(pattern, LayoutParams(-1, -1))
            addView(nested, LayoutParams(-1, -1))
        }
    }

    private class StripeBackdrop : Drawable() {
        private val paint = Paint()
        override fun draw(canvas: Canvas) {
            for (x in bounds.left until bounds.right step 8) {
                paint.color = if ((x - bounds.left) % 16 == 0) Color.BLACK else Color.WHITE
                canvas.drawRect(x.toFloat(), bounds.top.toFloat(), x + 8f, bounds.bottom.toFloat(), paint)
            }
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Suppress("DEPRECATION") override fun getOpacity(): Int = PixelFormat.OPAQUE
    }
}
