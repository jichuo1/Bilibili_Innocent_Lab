package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.SettingsHomeScrollView
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidActivityRenderer
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
import android.app.Activity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real ViewRoot/HWUI/fling regression; no consent, skin choice or application preference is changed. */
@SdkSuppress(minSdkVersion = 33)
@RunWith(AndroidJUnit4::class)
class LiquidScrollSamplingInstrumentedTest {
    @Test fun aLongCardKeepsItsRecordedOriginDuringBothMiddleListFlings() = verifyLayout(shortCards = false)

    @Test fun shortCardsRefreshOnReentryAndLeaveStationarySurfacesIdle() = verifyLayout(shortCards = true)

    private fun verifyLayout(shortCards: Boolean) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var fixture: LiquidScrollFixture
            scenario.onActivity { fixture = LiquidScrollFixture(it, shortCards) }
            try {
                assertTrue("Fixture did not receive a real draw", fixture.ready.await(8, TimeUnit.SECONDS))
                for (direction in listOf(1, -1, 0)) {
                    lateinit var trace: LiquidScrollTrace
                    scenario.onActivity { trace = fixture.observe(direction) }
                    assertTrue("Frame observation timed out", trace.finished.await(8, TimeUnit.SECONDS))
                    assertTrue(trace.failures.joinToString("\n"), trace.failures.isEmpty())
                    assertEquals("An unrelated stationary surface was rerecorded", 0, trace.stationaryDraws)
                    if (direction == 0) {
                        assertEquals("Idle observation unexpectedly scrolled", 0, trace.movingFrames)
                        assertEquals("Idle callbacks must not rerecord cards", 0, trace.cardDraws)
                    } else {
                        assertTrue("The actual NestedScrollView fling did not move", trace.movingFrames >= 4)
                        assertTrue("No visible hardware card was checked", trace.checkedCards >= 4)
                    }
                }
            } finally {
                scenario.onActivity { fixture.close() }
            }
        }
    }
}

private class LiquidScrollTrace {
    val finished = CountDownLatch(1)
    val failures = ArrayList<String>()
    var movingFrames = 0
    var checkedCards = 0
    var stationaryDraws = 0
    var cardDraws = 0

    fun fail(message: String) {
        if (failures.size < 12) failures += message
    }
}

/** The real Activity supplies only its window; all renderer preference reads use a test-owned namespace. */
private class LiquidRendererFixtureContext(owner: Activity) : Activity() {
    private val fixtureWindow = owner.window
    private val isolatedApplication = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().context) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("liquid_scroll_regression_$name", mode)
    }

    init { attachBaseContext(owner) }

    override fun getWindow(): Window = fixtureWindow
    override fun getApplicationContext(): Context = isolatedApplication
}

/** Records only when HWUI really invokes draw; replaying an old display list leaves this metadata old. */
private class RecordedLiquidCard(context: Context) : View(context) {
    private val location = IntArray(2)
    var recordedY = Int.MIN_VALUE
        private set
    var hardwareDraws = 0
        private set

    init { setWillNotDraw(false) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (canvas.isHardwareAccelerated) {
            getLocationOnScreen(location)
            recordedY = location[1]
            hardwareDraws++
        }
    }
}

private class LiquidScrollFixture(activity: MainActivity, shortCards: Boolean) : AutoCloseable {
    val ready = CountDownLatch(1)
    private val density = activity.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).roundToInt()
    private val root = FrameLayout(activity)
    private val scroll = SettingsHomeScrollView(activity, {}, {})
    private val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val cards = ArrayList<RecordedLiquidCard>()
    private val stationary = RecordedLiquidCard(activity)
    private val choreographer = Choreographer.getInstance()
    private var frameCallback: Choreographer.FrameCallback? = null
    private var closed = false
    private var rendererFailed = false
    private var readyPosted = false
    private val visible = Rect()
    private val location = IntArray(2)
    private val renderer = LiquidActivityRenderer(LiquidRendererFixtureContext(activity), MonetColors(
        primary = Color.rgb(30, 120, 230), onPrimary = Color.WHITE,
        secondary = Color.rgb(90, 170, 140), tertiary = Color.rgb(210, 120, 90),
        surface = Color.rgb(28, 35, 45), background = Color.rgb(10, 18, 30),
        surfaceVariant = Color.rgb(45, 55, 70)
    ))
    private val drawObserver = ViewTreeObserver.OnDrawListener {
        if (!readyPosted && root.width > 0 && scroll.height > 0) {
            readyPosted = true
            root.post { ready.countDown() }
        }
    }

    init {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity.glowEngine?.onStop()
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.isFillViewport = true
        scroll.onScrollPositionChanged = renderer::notifyScrollPositionChanged
        repeat(if (shortCards) 60 else 1) {
            val card = RecordedLiquidCard(activity).apply {
                background = renderer.surface(Color.DKGRAY, 12f, SurfaceRole.CARD)
            }
            cards += card
            content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(if (shortCards) 240 else 12000)).apply {
                setMargins(dp(12), dp(4), dp(12), dp(4))
            })
        }
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = dp(56) })
        stationary.background = renderer.surface(Color.DKGRAY, 12f, SurfaceRole.CARD)
        root.addView(stationary, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        activity.setContentView(root)
        check(renderer.bindRoot(root, {}, { rendererFailed = true }))
        root.viewTreeObserver.addOnDrawListener(drawObserver)
        renderer.onStart()
    }

    fun observe(direction: Int): LiquidScrollTrace {
        check(!closed && frameCallback == null)
        check(!rendererFailed && renderer.backendName in listOf("REFRACTION", "BLUR")) {
            "The test requires a real optical backend, got ${renderer.backendName}"
        }
        val trace = LiquidScrollTrace()
        val range = (content.height - scroll.height).coerceAtLeast(0)
        check(range > scroll.height * 3) { "Fixture must have enough middle-list fling room" }
        scroll.fling(0)
        scroll.scrollTo(0, range / 2)
        var warmFrames = 2
        var remaining = if (direction == 0) 12 else 40
        var previousY = scroll.scrollY
        var stationaryStart = 0
        var cardStart = 0
        var started = false
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (closed) return
                if (warmFrames-- > 0) {
                    choreographer.postFrameCallback(this)
                    return
                }
                if (!started) {
                    started = true
                    previousY = scroll.scrollY
                    stationaryStart = stationary.hardwareDraws
                    cardStart = cards.sumOf { it.hardwareDraws }
                    if (direction != 0) scroll.fling(direction * 4500)
                    choreographer.postFrameCallback(this)
                    return
                }
                // This runs before the next traversal. The preceding frame may have changed scrollY
                // inside computeScroll; check even cards whose old display list was only replayed.
                val currentY = scroll.scrollY
                if (rendererFailed) trace.fail("Liquid failed while observing the real fling")
                if (currentY != previousY) trace.movingFrames++
                previousY = currentY
                if (currentY <= 0 || currentY >= range) trace.fail("Fling touched an edge: $currentY/$range")
                for ((index, card) in cards.withIndex()) {
                    if (!card.getGlobalVisibleRect(visible) || visible.isEmpty) continue
                    card.getLocationOnScreen(location)
                    trace.checkedCards++
                    if (card.recordedY != location[1]) trace.fail(
                        "card=$index scrollY=$currentY recorded=${card.recordedY} actual=${location[1]} draws=${card.hardwareDraws}")
                }
                if (--remaining > 0) {
                    choreographer.postFrameCallback(this)
                } else {
                    trace.stationaryDraws = stationary.hardwareDraws - stationaryStart
                    trace.cardDraws = cards.sumOf { it.hardwareDraws } - cardStart
                    frameCallback = null
                    trace.finished.countDown()
                }
            }
        }
        frameCallback = callback
        choreographer.postFrameCallback(callback)
        return trace
    }

    override fun close() {
        if (closed) return
        closed = true
        frameCallback?.let(choreographer::removeFrameCallback)
        frameCallback = null
        scroll.onScrollPositionChanged = null
        scroll.fling(0)
        root.viewTreeObserver.takeIf { it.isAlive }?.removeOnDrawListener(drawObserver)
        renderer.close()
    }
}
