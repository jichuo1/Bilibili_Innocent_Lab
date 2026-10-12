package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardGridAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardStyle
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostHomeChannelCardsInstrumentedTest {
    @Test fun nestedLiveCoverAndInfoShareOneSurfaceWithoutChangingConstraints() = check(enabled = true)
    @Test fun disabledCardsPreserveLiveInfoAndCover() = check(enabled = false)
    @Test fun liveResourceNamesOutsideHomeDoNotDecorateMedia() = check(enabled = true, home = false)

    private fun check(enabled: Boolean, home: Boolean = true) {
        val hostContext = InstrumentationRegistry.getInstrumentation().targetContext.createPackageContext("tv.danmaku.bili", 0)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val context = object : ContextWrapper(activity) { override fun getResources() = hostContext.resources }
                fun hostId(name: String) = context.resources.getIdentifier(name, "id", "tv.danmaku.bili")
                    .also { assertTrue("Missing host ID $name", it != 0) }
                val root = FrameLayout(context)
                val body = FrameLayout(context)
                val cover = ImageView(context).apply { id = hostId("ivCover") }
                val info = FrameLayout(context).apply { id = hostId("bottomContaner") }
                val original = ColorDrawable(Color.DKGRAY)
                info.background = original
                info.addView(TextView(context).apply { id = hostId("tvTitle"); text = "Live" })
                body.addView(cover, FrameLayout.LayoutParams(397, 120))
                body.addView(info, FrameLayout.LayoutParams(397, 80).apply { topMargin = 120 })
                root.addView(body, FrameLayout.LayoutParams(397, 200).apply { leftMargin = 3; topMargin = 3 })
                val manager = LinearLayoutManager(context)
                val list = RecyclerView(context).apply { layoutManager = manager }
                if (home) FrameLayout(context).apply { id = hostId("liveTabContainer"); addView(list) }
                list.addView(root, RecyclerView.LayoutParams(400, 203))
                list.layout(0, 0, 1080, 1800)
                root.layout(0, 0, 400, 203)
                body.layout(3, 3, 400, 203)
                cover.layout(0, 0, 397, 120)
                info.layout(0, 120, 397, 200)
                val constraints = info.layoutParams
                val style = HostVideoCardStyle(requireNotNull(HostVideoCardGridAccess.resolve(javaClass.classLoader!!)),
                    HostVideoCardHostAccess(javaClass.classLoader!!), -1,
                    onApplied = {}, onError = { throw AssertionError(it) }, decorateCards = enabled)
                repeat(3) { style.bind(root); style.prepareMeasurement(manager, root) }
                assertSame(constraints, info.layoutParams)
                assertEquals(120, (info.layoutParams as FrameLayout.LayoutParams).topMargin)
                if (enabled && home) {
                    val margin = (root.layoutParams as RecyclerView.LayoutParams).leftMargin
                    assertTrue(margin > 0)
                    style.prepareMeasurement(manager, root)
                    assertEquals(margin, (root.layoutParams as RecyclerView.LayoutParams).leftMargin)
                    assertTrue(root.clipToOutline)
                    assertNotNull(root.background)
                    assertNull(info.background)
                    assertSame(ViewOutlineProvider.BOUNDS, cover.outlineProvider)
                    assertFalse(cover.clipToOutline)
                    assertTrue(style.replacesSeparators(list))
                } else {
                    assertEquals(0, (root.layoutParams as RecyclerView.LayoutParams).leftMargin)
                    assertFalse(root.clipToOutline)
                    assertNull(root.background)
                    assertSame(original, info.background)
                    assertSame(ViewOutlineProvider.BACKGROUND, cover.outlineProvider)
                    assertFalse(style.replacesSeparators(list))
                }
            }
        }
    }
}
