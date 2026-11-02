package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
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
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostCardMaterialScope
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

    private fun capture(window: Window): Bitmap {
        val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var status = -1
        PixelCopy.request(window, bitmap, { status = it; done.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, status)
        return bitmap
    }
}
