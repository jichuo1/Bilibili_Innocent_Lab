package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostCardMaterialScope
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostUiPowerPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry.ViewSamplingMatrix
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Window pixels compare the committed mapping with the shortcut, including its fallback. */
@SdkSuppress(minSdkVersion = 31)
@RunWith(AndroidJUnit4::class)
class HostCardRenderingInstrumentedTest {
    @Test fun directChildMappingMatchesTheGeneralPathThroughScrollAndTransforms() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var window: Window
            val owners = ArrayList<FrameLayout>()
            val cards = ArrayList<View>()
            val scopes = ArrayList<HostCardMaterialScope>()
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                window = activity.window
                val root = FrameLayout(activity).apply { setBackgroundColor(Color.WHITE) }
                repeat(2) { index ->
                    val owner = FrameLayout(activity).apply {
                        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(Color.BLUE, Color.CYAN, Color.RED))
                        pivotX = 0f; pivotY = 0f
                    }
                    val card = View(activity).apply { pivotX = 0f; pivotY = 0f }
                    owner.addView(card, FrameLayout.LayoutParams(340, 240).apply { leftMargin = 60; topMargin = 260 })
                    root.addView(owner, FrameLayout.LayoutParams(480, 900).apply { leftMargin = index * 540; topMargin = 200 })
                    owners += owner; cards += card
                }
                activity.setContentView(root)
                repeat(2) { index ->
                    scopes += HostCardMaterialScope(owners[index], true, optimizeCardRendering = index == 1).also {
                        it.apply(cards[index], 24f)
                    }
                }
            }
            try {
                repeat(3) { pose ->
                    instrumentation.runOnMainSync {
                        owners.forEach { owner ->
                            owner.scrollTo(0, if (pose == 0) 0 else 73)
                            owner.scaleX = if (pose == 0) 1f else .86f
                            owner.scaleY = if (pose == 0) 1f else .91f
                            owner.rotation = if (pose == 0) 0f else 2.2f
                        }
                        cards.forEach { card ->
                            card.translationX = if (pose == 2) 3.25f else 0f
                            card.scaleX = if (pose == 2) .94f else 1f
                            card.rotation = if (pose == 2) -2f else 0f
                            card.invalidate()
                        }
                    }
                    SystemClock.sleep(900)
                    val frame = capture(window)
                    try {
                        instrumentation.runOnMainSync {
                            for (index in cards.indices) {
                                val state = requireNotNull(scopes[index].diagnostics(cards[index]))
                                assertTrue(state.toString(), state.acrylicVisible)
                                assertEquals(LumenSurfaceBackend.GPU, state.backdropBackend)
                            }
                            val mapping = ViewSamplingMatrix()
                            val matrix = Matrix()
                            val points = FloatArray(2)
                            val screen = IntArray(2)
                            val local = IntArray(2)
                            window.decorView.getLocationOnScreen(screen)
                            window.decorView.getLocationInWindow(local)
                            for (y in listOf(60f, 100f, 140f)) for (x in listOf(100f, 170f, 240f)) {
                                val colors = cards.map { card ->
                                    assertTrue(mapping.localToScreen(card, matrix))
                                    points[0] = x; points[1] = y; matrix.mapPoints(points)
                                    frame.getPixel(kotlin.math.round(points[0] - screen[0] + local[0]).toInt(),
                                        kotlin.math.round(points[1] - screen[1] + local[1]).toInt())
                                }
                                for (shift in listOf(0, 8, 16)) {
                                    val delta = kotlin.math.abs((colors[0] shr shift and 255) - (colors[1] shr shift and 255))
                                    assertTrue("Mapping changed pixels: pose=$pose x=$x y=$y delta=$delta", delta <= 3)
                                }
                            }
                        }
                    } finally { frame.recycle() }
                }
            } finally { instrumentation.runOnMainSync { scopes.forEach { it.close() } } }
        }
    }

    /** The settings switch picks the notification path; the retained one records less yet settles on the same pixels. */
    @Test fun theSwitchSelectsEagerOrRetainedRefreshAndTheTrailingFlushSettlesTheFinalMaterial() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var window: Window
            val backgrounds = ArrayList<ColorDrawable>()
            val owners = ArrayList<FrameLayout>()
            val cards = ArrayList<View>()
            val scopes = ArrayList<HostCardMaterialScope>()
            try {
                scenario.onActivity { activity ->
                    activity.activeConfirmDialog?.dismiss()
                    window = activity.window
                    val root = FrameLayout(activity).apply { setBackgroundColor(Color.WHITE) }
                    repeat(2) { index ->
                        val background = ColorDrawable(Color.rgb(20, 60, 160))
                        val owner = FrameLayout(activity).apply { this.background = background }
                        val card = View(activity)
                        owner.addView(card, FrameLayout.LayoutParams(340, 240).apply { leftMargin = 60; topMargin = 260 })
                        root.addView(owner, FrameLayout.LayoutParams(480, 900).apply { leftMargin = index * 540; topMargin = 200 })
                        backgrounds += background; owners += owner; cards += card
                    }
                    activity.setContentView(root)
                    // Scopes read the process policy when they are created: 0 = switch off, 1 = switch on.
                    repeat(2) { index ->
                        HostUiPowerPolicy.configure(index == 1)
                        scopes += HostCardMaterialScope(owners[index], true).also { it.apply(cards[index], 24f) }
                    }
                }
                SystemClock.sleep(900)
                instrumentation.runOnMainSync {
                    for (index in cards.indices) {
                        val state = requireNotNull(scopes[index].diagnostics(cards[index]))
                        assertEquals(LumenSurfaceBackend.GPU, state.backdropBackend)
                    }
                }
                // The engine reports the GPU state through its listener; the switch must decide the path from that.
                val retains = BooleanArray(2)
                instrumentation.runOnMainSync { scopes.forEachIndexed { index, scope -> retains[index] = scope.canRetainGpuFrame() } }
                assertEquals("switch off must never retain", false, retains[0])
                assertEquals("switch on must learn the GPU backend from the engine's events", true, retains[1])
                val before = IntArray(2)
                instrumentation.runOnMainSync { scopes.forEachIndexed { index, scope -> before[index] = scope.materialRecordings.toInt() } }
                val frames = 72
                var step = 0
                val startedAt = SystemClock.uptimeMillis()
                val finished = CountDownLatch(1)
                val callback = object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        backgrounds.forEach { it.color = Color.rgb(20 + step * 2, 60 + step, 160 - step) }
                        if (++step < frames) Choreographer.getInstance().postFrameCallback(this) else finished.countDown()
                    }
                }
                instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback(callback) }
                assertTrue(finished.await(15, TimeUnit.SECONDS))
                val framePeriodMs = (SystemClock.uptimeMillis() - startedAt) / frames.toFloat()
                SystemClock.sleep(900)
                val recorded = IntArray(2)
                instrumentation.runOnMainSync { scopes.forEachIndexed { index, scope -> recorded[index] = scope.materialRecordings.toInt() - before[index] } }
                // Lumen samples no faster than every 28 ms. A slower display (software GL emulator) never lets
                // the throttle engage, so the saving is only measurable when frames are faster than that.
                if (framePeriodMs < 24f) {
                    assertTrue("period=$framePeriodMs eager=${recorded[0]} retained=${recorded[1]}",
                        recorded[0] > 20 && recorded[1] < recorded[0] * 0.85)
                } else println("HostCardRendering: frame period $framePeriodMs ms is above the sampling interval; eager=${recorded[0]} retained=${recorded[1]}")
                val frame = capture(window)
                try {
                    val centers = cards.map { card ->
                        val at = IntArray(2)
                        instrumentation.runOnMainSync { card.getLocationInWindow(at) }
                        frame.getPixel(at[0] + card.width / 2, at[1] + card.height / 2)
                    }
                    for (shift in listOf(0, 8, 16)) {
                        val delta = kotlin.math.abs((centers[0] shr shift and 255) - (centers[1] shr shift and 255))
                        assertTrue("Final material differs: ${Integer.toHexString(centers[0])} vs ${Integer.toHexString(centers[1])}", delta <= 4)
                    }
                } finally { frame.recycle() }
            } finally {
                HostUiPowerPolicy.resetForTests()
                instrumentation.runOnMainSync { scopes.forEach { it.close() } }
            }
        }
    }

    private fun capture(window: Window): Bitmap {
        val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var status = -1
        PixelCopy.request(window, bitmap, { status = it; done.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, status)
        return bitmap
    }
}
