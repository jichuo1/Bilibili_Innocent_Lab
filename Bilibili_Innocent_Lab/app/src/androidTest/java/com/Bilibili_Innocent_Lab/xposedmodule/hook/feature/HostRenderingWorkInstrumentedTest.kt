package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.os.Debug
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundDrawable
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeContentSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeViewportSource
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry.ViewSamplingMatrix
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class HostRenderingWorkInstrumentedTest {
    @Test fun versionWorkOnNestedCardsMatchesTheOriginalSnapshot() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var content: FrameLayout
            lateinit var glass: View
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val root = FrameLayout(activity)
                content = FrameLayout(activity).apply { clipChildren = false }
                root.addView(content, FrameLayout.LayoutParams(900, 1600))
                repeat(12) { row ->
                    var parent = content
                    repeat(16) { depth ->
                        val group = FrameLayout(activity).apply { clipChildren = false }
                        parent.addView(group, FrameLayout.LayoutParams(800, 120).apply {
                            if (depth == 0) topMargin = row * 125
                        })
                        group.addView(View(activity), FrameLayout.LayoutParams(80, 80).apply { leftMargin = depth * 5 })
                        parent = group
                    }
                }
                glass = View(activity)
                root.addView(glass, FrameLayout.LayoutParams(850, 1000).apply { topMargin = 200 })
                activity.setContentView(root)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                val contentSource = HostChromeContentSource(content, Color.BLACK)
                val source = HostChromeViewportSource(contentSource) { it(glass, 0f, 0f, 20f) }
                val mapping = ViewSamplingMatrix()
                val matrix = Matrix()
                val values = FloatArray(9)
                val bounds = RectF()
                fun reference(): Long = mapping.withAncestorMemo {
                    var version = contentSource.contentVersion
                    fun mix(value: Long) { version = version * 31 + value }
                    mix(source.coordinateView.left.toLong()); mix(source.coordinateView.top.toLong())
                    mix(source.coordinateView.width.toLong()); mix(source.coordinateView.height.toLong())
                    fun visit(view: View, depth: Int) {
                        if (depth >= 128 || view.visibility != View.VISIBLE || view.alpha <= 0f ||
                            !mapping.sourceToTarget(view, source.coordinateView, matrix)) return
                        bounds.set(0f, 0f, view.width.toFloat(), view.height.toFloat()); matrix.mapRect(bounds)
                        val intersects = bounds.intersects(0f, 0f, source.coordinateView.width.toFloat(), source.coordinateView.height.toFloat())
                        if (!intersects && (view !is ViewGroup || view.clipChildren)) return
                        mix(System.identityHashCode(view).toLong()); mix(view.width.toLong()); mix(view.height.toLong())
                        mix(view.scrollX.toLong()); mix(view.scrollY.toLong()); mix(view.alpha.toBits().toLong())
                        mix(if (view.isDirty) view.drawingTime else -1L)
                        matrix.getValues(values); for (value in values) mix(value.toBits().toLong())
                        if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index), depth + 1)
                    }
                    visit(content, 0)
                    version and Long.MAX_VALUE
                }
                try {
                    source.sync()
                    assertEquals("The optimized traversal changed an integer-position snapshot", reference(), source.contentVersion)
                    repeat(60) { reference(); source.contentVersion }
                    val referenceRuns = ArrayList<Long>()
                    val optimizedRuns = ArrayList<Long>()
                    repeat(5) {
                        var start = Debug.threadCpuTimeNanos()
                        repeat(100) { reference() }
                        referenceRuns += Debug.threadCpuTimeNanos() - start
                        start = Debug.threadCpuTimeNanos()
                        repeat(100) { source.contentVersion }
                        optimizedRuns += Debug.threadCpuTimeNanos() - start
                    }
                    val output = File(it.getExternalFilesDir(null), "host-ui-power").apply { mkdirs() }
                    File(output, "version-work.json").writeText(JSONObject()
                        .put("referenceCpuNsPerRead", referenceRuns.sorted()[2] / 100.0)
                        .put("optimizedCpuNsPerRead", optimizedRuns.sorted()[2] / 100.0).toString(2))
                } finally { source.close() }
            }
        }
    }

    @Test fun opaqueSamplingKeepsPixelsThroughAlphaTransformsAndFilters() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var base: FrameLayout
            lateinit var wallpaper: FrameLayout
            lateinit var content: FrameLayout
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                base = FrameLayout(activity).apply { setBackgroundColor(Color.RED) }
                wallpaper = FrameLayout(activity)
                content = FrameLayout(activity).apply { setBackgroundColor(0x330000FF) }
                wallpaper.addView(content, FrameLayout.LayoutParams(160, 240).apply { leftMargin = 40; topMargin = 60 })
                base.addView(wallpaper, FrameLayout.LayoutParams(260, 400))
                activity.setContentView(base)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                val source = HostChromeContentSource(content, Color.GREEN)
                val backgrounds = listOf<Drawable>(ColorDrawable(Color.BLUE), ColorDrawable(0x550000FF),
                    ColorDrawable(Color.BLUE).apply { colorFilter = PorterDuffColorFilter(0x55FF00FF, PorterDuff.Mode.SRC_IN) },
                    HostBackgroundDrawable(HostBackgroundConfig(HostBackgroundPreset.STARRY), cacheEnabled = false))
                for (background in backgrounds) for (alpha in listOf(255, 120, 0)) for (rotation in listOf(0f, 13f)) {
                    wallpaper.background = background
                    background.alpha = alpha
                    wallpaper.rotation = rotation
                    val actual = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
                    val expected = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
                    try {
                        source.drawContent(Canvas(actual))
                        reference(content, Color.GREEN, Canvas(expected))
                        assertTrue("${background.javaClass.simpleName}: alpha=$alpha rotation=$rotation", actual.sameAs(expected))
                    } finally { actual.recycle(); expected.recycle() }
                }
                wallpaper.rotation = 0f
                wallpaper.scaleX = .4f
                wallpaper.background = ColorDrawable(Color.BLUE)
                val actual = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
                val expected = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
                try {
                    source.drawContent(Canvas(actual)); reference(content, Color.GREEN, Canvas(expected))
                    assertTrue("A partially covering opaque ancestor lost the Window base", actual.sameAs(expected))
                } finally { actual.recycle(); expected.recycle() }
            }
        }
    }

    @Test fun viewportVersionTracksNestedTransformsAndIgnoresExcludedForeground() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var content: FrameLayout
            lateinit var parent: FrameLayout
            lateinit var child: View
            lateinit var glass: View
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val root = FrameLayout(activity)
                content = FrameLayout(activity)
                parent = FrameLayout(activity).apply { clipChildren = false }
                child = View(activity).apply { setBackgroundColor(Color.RED) }
                parent.addView(child, FrameLayout.LayoutParams(80, 80).apply { leftMargin = 50; topMargin = 50 })
                content.addView(parent, FrameLayout.LayoutParams(200, 300).apply { topMargin = 20 })
                root.addView(content, FrameLayout.LayoutParams(300, 500))
                glass = View(activity)
                root.addView(glass, FrameLayout.LayoutParams(240, 180).apply { topMargin = 30 })
                activity.setContentView(root)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                val source = HostChromeViewportSource(HostChromeContentSource(content, Color.BLACK)) { it(glass, 0f, 0f, 10f) }
                val backgroundOnly = HostChromeViewportSource(HostChromeContentSource(content, Color.BLACK,
                    includeViewContent = false)) { it(glass, 0f, 0f, 10f) }
                try {
                    source.sync(); backgroundOnly.sync()
                    var version = source.contentVersion
                    assertEquals(version, source.contentVersion)
                    val backgroundVersion = backgroundOnly.contentVersion
                    fun changed(action: () -> Unit) {
                        action()
                        val next = source.contentVersion
                        assertNotEquals("Nested property movement must update the version immediately", version, next)
                        version = next
                        assertEquals("A background-only field followed unrelated foreground work",
                            backgroundVersion, backgroundOnly.contentVersion)
                    }
                    changed { parent.rotation = 17f }
                    changed { parent.scaleX = .8f; parent.scaleY = 1.1f }
                    changed { parent.scrollTo(7, 11) }
                    changed { child.translationX = 3.5f; child.translationY = 9.25f }
                    changed { child.alpha = .5f }
                    changed { child.visibility = View.INVISIBLE }
                    changed { child.visibility = View.VISIBLE }
                } finally { source.close(); backgroundOnly.close() }
            }
        }
    }

    /** Original full composition, independent of the optimized ancestor selection. */
    private fun reference(content: View, base: Int, canvas: Canvas) {
        canvas.drawColor(base)
        val ancestors = ArrayList<View>()
        var parent = content.parent as? View
        while (parent != null) { ancestors.add(parent); parent = parent.parent as? View }
        val mapping = ViewSamplingMatrix()
        val matrix = Matrix()
        val bounds = Rect()
        for (view in ancestors.asReversed()) {
            val background = view.background ?: continue
            if (!mapping.sourceToTarget(view, content, matrix)) continue
            val saved = canvas.save()
            bounds.set(background.bounds)
            try {
                canvas.concat(matrix)
                background.setBounds(0, 0, view.width, view.height)
                background.draw(canvas)
            } finally { background.bounds = bounds; canvas.restoreToCount(saved) }
        }
        canvas.translate(-content.scrollX.toFloat(), -content.scrollY.toFloat())
        content.draw(canvas)
    }
}
