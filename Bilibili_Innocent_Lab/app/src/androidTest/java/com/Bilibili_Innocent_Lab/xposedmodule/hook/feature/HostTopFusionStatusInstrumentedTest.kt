package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopFusionBinding
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopIslandPageActions
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostTopFusionStatusInstrumentedTest {
    @Test fun wallpaperAndDockedMaterialOwnStatusForegroundOnlyWhenVisible() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val decor = activity.window.decorView as ViewGroup
                val controller = WindowCompat.getInsetsController(activity.window, decor)
                val initial = controller.isAppearanceLightStatusBars
                val container = FrameLayout(activity)
                decor.addView(container, ViewGroup.LayoutParams(320, 240))
                val colors = HostChromeColors(false, 0xFFFF6699.toInt())
                val binding = HostTopFusionBinding.attach(
                    container = container,
                    pager = null,
                    pageActions = HostTopIslandPageActions(container, null),
                    backdrop = null,
                    basePadding = 0,
                    density = activity.resources.displayMetrics.density,
                    statusBarInset = 24,
                    palette = colors.palette(),
                    isDark = false
                )
                assertNotNull(binding)
                binding ?: return@onActivity
                val progressField = HostTopFusionBinding::class.java.getDeclaredField("dockingProgress")
                progressField.isAccessible = true
                try {
                    controller.isAppearanceLightStatusBars = false

                    binding.refreshMaterial(colors, preserveHostArtwork = false, wallpaper = true)
                    binding.sync()
                    assertTrue("module wallpaper must not keep white icons on a light top surface",
                        controller.isAppearanceLightStatusBars)

                    binding.refreshMaterial(colors, preserveHostArtwork = true, wallpaper = false)
                    binding.sync()
                    assertFalse("host artwork keeps its own foreground while docked at top",
                        controller.isAppearanceLightStatusBars)

                    progressField.setFloat(binding, 0f)
                    binding.sync()
                    assertTrue("scrolled glass material owns the foreground even over host artwork",
                        controller.isAppearanceLightStatusBars)

                    progressField.setFloat(binding, 1f)
                    binding.sync()
                    assertFalse("returning to the host artwork restores its foreground",
                        controller.isAppearanceLightStatusBars)
                } finally {
                    decor.removeView(container)
                    controller.isAppearanceLightStatusBars = initial
                }
            }
        }
    }
}
