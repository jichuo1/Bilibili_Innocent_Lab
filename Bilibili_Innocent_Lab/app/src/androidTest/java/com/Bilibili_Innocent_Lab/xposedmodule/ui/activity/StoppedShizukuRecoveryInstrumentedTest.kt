package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.RestartCapabilities
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart.ShizukuAvailability
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 只读验证服务已停止时的恢复入口；不停止服务、不授权、不点击保存或启动宿主。 */
@RunWith(AndroidJUnit4::class)
class StoppedShizukuRecoveryInstrumentedTest {
    @Test
    fun installedStoppedServiceCanReachManagerRecovery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyStoppedShizuku") == "true")
        val app = instrumentation.targetContext.applicationContext
        assumeTrue(ShizukuAvailability.read(app).status == ShizukuAvailability.Status.STOPPED)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                RestartCapabilities.warm(app, force = true)
                activity.showRestartConfirmDialog()
            }
            await(scenario, "Stopped Shizuku entry must be enabled") { activity ->
                button(activity, R.string.shizuku_restart_button)?.let { it.isShown && it.isEnabled } == true
            }
            scenario.onActivity { activity ->
                assertTrue(requireNotNull(button(activity, R.string.shizuku_restart_button)).performClick())
            }
            await(scenario, "Manager recovery action must be reachable") { activity ->
                button(activity, R.string.shizuku_open_manager)?.let { it.isShown && it.isEnabled } == true
            }
            scenario.onActivity { it.activeConfirmDialog?.dismiss() }
        }
    }

    private fun await(scenario: ActivityScenario<MainActivity>, message: String, predicate: (MainActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var matched = false
        while (!matched && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { matched = predicate(it) }
            if (!matched) SystemClock.sleep(50)
        }
        assertTrue(message, matched)
    }

    private fun button(activity: MainActivity, label: Int): Button? {
        val root = activity.activeConfirmDialog?.window?.decorView ?: return null
        val text = activity.getString(label)
        return descendants(root).filterIsInstance<Button>().firstOrNull { it.text.toString() == text }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
