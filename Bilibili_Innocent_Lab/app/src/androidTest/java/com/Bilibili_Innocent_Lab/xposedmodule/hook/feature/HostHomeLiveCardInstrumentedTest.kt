package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardStyle
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostCardMaterial
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostHomeLiveCardInstrumentedTest {
    @Test fun recommendationLiveCardUsesOneRoundedSurfaceAndClearsItsNativeBottomPanel() {
        val hostContext = try {
            InstrumentationRegistry.getInstrumentation().targetContext.createPackageContext("tv.danmaku.bili", 0)
        } catch (missing: PackageManager.NameNotFoundException) {
            assumeNoException("Official host is not installed for this user", missing)
            throw missing
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val context = object : ContextWrapper(activity) { override fun getResources() = hostContext.resources }
                fun hostId(name: String) = context.resources.getIdentifier(name, "id", "tv.danmaku.bili")
                    .also { assertTrue("Missing host ID $name", it != 0) }
                val root = FrameLayout(context)
                val cover = FrameLayout(context).apply { id = hostId("cover_layout") }
                val panelBackground = ColorDrawable(Color.WHITE)
                val bottomPanel = com.bilibili.magicasakura.widgets.TintView(context).apply {
                    id = hostId("bottom_background")
                    background = panelBackground
                }
                val title = TextView(context).apply { id = hostId("title_v2"); text = "Live" }
                root.addView(cover, FrameLayout.LayoutParams(400, 120))
                root.addView(bottomPanel, FrameLayout.LayoutParams(400, 80).apply { topMargin = 120 })
                root.addView(title, FrameLayout.LayoutParams(360, 40).apply { leftMargin = 20; topMargin = 130 })
                val list = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
                val parent = FrameLayout(context)
                parent.addView(list, FrameLayout.LayoutParams(-1, -1))
                list.addView(root, RecyclerView.LayoutParams(400, 200))
                list.layout(0, 0, 1080, 1800)
                root.layout(0, 0, 400, 200)
                cover.layout(0, 0, 400, 120)
                bottomPanel.layout(0, 120, 400, 200)

                val style = HostVideoCardStyle(null, HostVideoCardHostAccess(javaClass.classLoader!!), -1,
                    onApplied = {}, onError = { throw AssertionError(it) }, material = HostCardMaterial.ACRYLIC)
                style.bind(root)

                assertTrue(root.clipToOutline)
                assertNotNull(root.background)
                assertSame(ViewOutlineProvider.BOUNDS, cover.outlineProvider)
                assertFalse(cover.clipToOutline)
                assertNull(bottomPanel.background)
            }
        }
    }
}
