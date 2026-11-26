package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.showSettingsSearchDialog
import com.lumen.coacervation.engine.model.SkinId
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** 需要已授权、已选择 Liquid 的设备；不切换材质、不修改业务设置。 */
@SdkSuppress(minSdkVersion = 33)
@RunWith(AndroidJUnit4::class)
class LiquidRuntimeRegressionInstrumentedTest {
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    @Test fun searchAndRepeatedEdgePullsKeepTheRefractionBackend() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            SystemClock.sleep(1000)
            var cancel = ""
            fun assertBackend() = scenario.onActivity { activity ->
                assertEquals(SkinId.LIQUID, activity.currentSkinDiagnostics()?.effectiveSkin)
                assertEquals("REFRACTION", activity.liquidBackendName)
            }
            assertBackend()
            repeat(3) {
                scenario.onActivity { activity ->
                    cancel = activity.getString(R.string.dialog_cancel)
                    val anchor = descendants(activity.window.decorView).first {
                        it.contentDescription?.toString() == activity.getString(R.string.settings_search_description)
                    }
                    activity.showSettingsSearchDialog(anchor)
                }
                SystemClock.sleep(1000)
                assertBackend()
                onView(withText(cancel)).inRoot(isDialog()).perform(click())
                SystemClock.sleep(600)
            }
            var width = 0
            var height = 0
            scenario.onActivity { width = it.window.decorView.width; height = it.window.decorView.height }
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            repeat(3) {
                val down = SystemClock.uptimeMillis()
                fun inject(action: Int, fraction: Float) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                        width * .5f, height * fraction, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { automation.injectInputEvent(event, true) } finally { event.recycle() }
                }
                inject(MotionEvent.ACTION_DOWN, .3f)
                for (step in 1..12) { SystemClock.sleep(25); inject(MotionEvent.ACTION_MOVE, .3f + .3f * step / 12) }
                inject(MotionEvent.ACTION_UP, .6f)
                SystemClock.sleep(1000)
                assertBackend()
            }
        }
    }
}
