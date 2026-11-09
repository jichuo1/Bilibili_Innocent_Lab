package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundDrawable
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostCardMaterialScope
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceScope
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassMaterial
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import kotlin.math.abs

/** Identical effects, resolution, window and animation inputs; no preference writes. */
internal class HostUiPowerFixture(private val activity: MainActivity, reuseGpuFrames: Boolean,
    optimizeCardRendering: Boolean = true) : AutoCloseable {
    val window = activity.window
    private val brightness = window.attributes.screenBrightness
    private val mode = window.attributes.preferredDisplayModeId
    private val rate = window.attributes.preferredRefreshRate
    private val keptOn = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
    val wallpaper = HostBackgroundDrawable(HostBackgroundConfig(HostBackgroundPreset.STARRY))
    val content = FrameLayout(activity)
    val cardViews = ArrayList<View>()
    val top = View(activity)
    val bottom = View(activity)
    val topScope: HostSurfaceScope
    val bottomScope: HostSurfaceScope
    lateinit var cards: HostCardMaterialScope
        private set

    init {
        activity.activeConfirmDialog?.dismiss()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = .15f
            preferredDisplayModeId = window.decorView.display?.supportedModes?.minByOrNull { abs(it.refreshRate - 90f) }?.modeId ?: 0
            preferredRefreshRate = 90f
        }
        val root = FrameLayout(activity).apply { background = wallpaper }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        repeat(10) { index ->
            val card = FrameLayout(activity)
            card.addView(object : View(activity) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(canvas: Canvas) {
                    canvas.drawColor(Color.rgb(25 + index * 17, 65, 145))
                    paint.color = Color.WHITE
                    for (x in 0 until width step 27) canvas.drawRect(x.toFloat(), 0f, x + 9f, height.toFloat(), paint)
                }
            }, FrameLayout.LayoutParams(-1, 250))
            card.addView(object : View(activity) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 30f }
                override fun onDraw(canvas: Canvas) {
                    canvas.drawText("Lumen full effects $index", 20f, 45f, paint)
                    canvas.drawText("Prism / Acrylic / Wallpaper", 20f, 90f, paint)
                }
            }, FrameLayout.LayoutParams(-1, 150).apply { topMargin = 250 })
            content.addView(card, FrameLayout.LayoutParams(460, 400).apply {
                leftMargin = 50 + index % 2 * 510; topMargin = 330 + index / 2 * 455
            })
            cardViews += card
        }
        cards = HostCardMaterialScope(content, true, reuseGpuFrames = reuseGpuFrames, optimizeCardRendering = optimizeCardRendering)
        for (view in cardViews) cards.apply(view, 46f)
        root.addView(top, FrameLayout.LayoutParams(-1, 140).apply { leftMargin = 50; rightMargin = 50; topMargin = 140 })
        root.addView(bottom, FrameLayout.LayoutParams(-1, 140).apply {
            leftMargin = 75; rightMargin = 75; bottomMargin = 80; gravity = android.view.Gravity.BOTTOM
        })
        activity.setContentView(root)
        val appearance = HostChromeAppearance(HostGlassMaterial.PRISM)
        val colors = HostChromeColors(true, Color.BLUE)
        topScope = HostSurfaceScope(top, colors, appearance = appearance, reuseGpuFrames = reuseGpuFrames).also {
            it.attach(top, content); it.floating(top, true, 24f)
        }
        bottomScope = HostSurfaceScope(bottom, colors, appearance = appearance, reuseGpuFrames = reuseGpuFrames).also {
            it.attach(bottom, content); it.floating(bottom, true, 24f)
        }
    }

    fun offset(value: Int) {
        content.scrollTo(0, value)
        wallpaper.scrollOffset = value.toLong() / 2
    }

    override fun close() {
        topScope.close(); bottomScope.close(); cards.close()
        window.attributes = window.attributes.apply {
            screenBrightness = brightness; preferredDisplayModeId = mode; preferredRefreshRate = rate
        }
        if (!keptOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
