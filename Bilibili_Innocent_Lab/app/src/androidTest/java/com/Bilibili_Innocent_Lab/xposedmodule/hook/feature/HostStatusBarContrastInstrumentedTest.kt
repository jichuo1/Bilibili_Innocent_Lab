package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import androidx.core.view.ViewCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostStatusBarContrast
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostStatusBarContrastInstrumentedTest {
    @Test fun contrastRestoresOnlyItsOwnForegroundAndAdoptsHostChanges() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val decor = activity.window.decorView
                val controller = ViewCompat.getWindowInsetsController(decor)
                assertNotNull(controller)
                controller ?: return@onActivity
                val initial = controller.isAppearanceLightStatusBars
                val contrast = HostStatusBarContrast()
                try {
                    contrast.apply(decor, darkContent = false)
                    assertTrue(controller.isAppearanceLightStatusBars)
                    contrast.restore(decor)
                    assertEquals(initial, controller.isAppearanceLightStatusBars)

                    contrast.apply(decor, darkContent = true)
                    assertFalse(controller.isAppearanceLightStatusBars)
                    controller.isAppearanceLightStatusBars = true
                    contrast.apply(decor, darkContent = true)
                    assertFalse(controller.isAppearanceLightStatusBars)
                    contrast.restore(decor)
                    assertTrue("A host foreground written after ours must become the restored value",
                        controller.isAppearanceLightStatusBars)
                } finally {
                    controller.isAppearanceLightStatusBars = initial
                }
            }
        }
    }
}
